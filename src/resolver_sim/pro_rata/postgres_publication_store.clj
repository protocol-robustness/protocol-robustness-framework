(ns resolver-sim.pro-rata.postgres-publication-store
  "Durable PostgreSQL authority for application-bound economic publication.

   PostgreSQL establishes authoritative H -> B -> O reachability. Artifact
   bodies outside those three records remain in the injected immutable artifact
   store and must resolve before this adapter enters its transaction."
  (:require [clojure.edn :as edn]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.extensions.manifest :as manifest]))

(def ^:private partition-table "prf_economic_publication_partition")
(def ^:private binding-table "prf_economic_publication_binding")
(def ^:private ordering-table "prf_economic_publication_ordering")
(def ^:private max-transaction-attempts 5)

(def ^:dynamic *transaction-hook*
  "Test-only hook invoked inside the authoritative transaction at named durable
   boundaries. Production binds no hook."
  nil)

(defn- transaction-hook! [point]
  (when *transaction-hook* (*transaction-hook* point)))

(defn ensure-schema! [datasource]
  ((requiring-resolve 'resolver-sim.db.migrate/migrate!) datasource)
  datasource)

(defn- row-opts [] {:builder-fn rs/as-unqualified-maps})
(defn- encode [value] (cas/canonical-edn value))
(defn- decode [value] (edn/read-string value))

(defn partition-id
  "Stable partition identity for exactly one ordering conflict key. The canonical
   conflict-key body is retained separately for diagnostic verification."
  [conflict-key]
  (when-not (vector? conflict-key)
    (throw (ex-info "Economic publication conflict key must be a vector"
                    {:reason :invalid-conflict-key :conflict-key conflict-key})))
  (ref/sha256-ref (hc/domain-hash "PRF_ECONOMIC_PUBLICATION_CONFLICT_KEY_V1" conflict-key)))

(defn- retryable-transaction-error? [throwable]
  (loop [cause throwable]
    (cond
      (nil? cause) false
      (and (instance? java.sql.SQLException cause)
           (contains? #{"40001" "40P01"} (.getSQLState ^java.sql.SQLException cause))) true
      :else (recur (.getCause ^Throwable cause)))))

(defn- with-transaction-retry! [f]
  (loop [attempt 1]
    (let [result (try
                   {:value (f)}
                   (catch Throwable error {:error error}))]
      (if-let [error (:error result)]
        (if (and (< attempt max-transaction-attempts)
                 (retryable-transaction-error? error))
          (do (Thread/sleep (+ 5 (* 5 attempt))) (recur (inc attempt)))
          (throw error))
        (:value result)))))

(defn- head-valid? [head]
  (and (= publication/application-publication-head-schema (:schema-version head))
       (= (:publication/head-root head) (publication/application-head-root head))))

(defn- dependency-root [[k field] resolved]
  (let [body (get resolved k)]
    (case k
      :capability-descriptor (ref/sha256-ref (manifest/capability-descriptor-root body))
      (ref/sha256-ref (get body field)))))

(def prerequisite-artifacts
  "Return the durable closure required by a publication. V1 retains its original
  closure; V2 adds only the compilation binding and the bodies needed to
  deterministically recompile it after restart."
  (fn [resolved]
    (into [[:use-case-application :application/root]
           [:capability-binding :binding/root]
           [:capability-descriptor nil]
           [:executable-distribution :executable-distribution/root]
           [:output :pro-rata-output/root]
           [:allocation :allocation/hash]
           [:pro-rata-application :application/root]
           [:pro-rata-transition :transition/root]
           [:canonical-transition :canonical-effect-transition/root]
           [:receipt :applied-effect-receipt/root]
           [:protocol-transaction-realization :protocol-transaction-realization/root]
           [:transition-binding :transition-binding/root]
           [:protocol-effect-realization :protocol-effect-realization/root]]
          (when (= "pro-rata-capability-output.v2" (get-in resolved [:output :pro-rata-output/schema]))
            [[:effect-compilation-binding :effect-compilation-binding/root]
             [:compilation :effect-compilation/root]
             [:target-map :target-map/root]
             [:semantics :effect-compilation-semantics/root]]))))

(defn- durable-prerequisites! [resolver resolved]
  (when-not (fn? resolver)
    (throw (ex-info "Durable artifact resolver is required before publication"
                    {:reason :durable-prerequisite-resolver-required})))
  (doseq [entry (prerequisite-artifacts resolved)
          :let [body (get resolved (first entry))
                root (dependency-root entry resolved)]]
    (when-not (and (map? body) root (= body (resolver root)))
      (throw (ex-info "Publication prerequisite is not durably resolvable"
                      {:reason :durable-prerequisite-unresolved
                       :artifact (first entry) :root root})))))

(defn persist-durable-prerequisites!
  "Persist the exact existing binding-validation closure in a filesystem CAS and
   require its persistent durable-install seal before PostgreSQL publication.
   The CAS retains bodies only; it establishes no publication authority."
  [cas-store resolved]
  (doseq [entry (prerequisite-artifacts resolved)
          :let [body (get resolved (first entry))
                root (dependency-root entry resolved)
                result (cas/put-if-absent! cas-store {:hash-reference root
                                                      :artifact body
                                                      :verify #(= body %)})]]
    (when-not (:crash-durable? result)
      (throw (ex-info "Publication prerequisite CAS object is not durably sealed"
                      {:reason :durable-prerequisite-not-sealed
                       :artifact (first entry) :root root}))))
  (durable-prerequisites!
   (fn [root]
     (let [expected (some (fn [entry]
                            (when (= root (dependency-root entry resolved))
                              (get resolved (first entry))))
                          (prerequisite-artifacts resolved))]
       (cas/resolve-durable-artifact cas-store root #(= expected %))))
   resolved)
  true)

(defn sealed-durable-resolver
  "Build the concrete resolver used by the publication adapter after
   `persist-durable-prerequisites!` has sealed this exact validation closure."
  [cas-store resolved]
  (fn [root]
    (let [expected (some (fn [entry]
                           (when (= root (dependency-root entry resolved))
                             (get resolved (first entry))))
                         (prerequisite-artifacts resolved))]
      (cas/resolve-durable-artifact cas-store root #(= expected %)))))

(defn- locked-partition! [tx conflict-key]
  (let [id (partition-id conflict-key)
        initial (publication/initial-application-head)
        inserted (jdbc/execute-one!
                  tx [(str "INSERT INTO " partition-table
                           " (partition_id, conflict_key_edn, head_edn, head_root, store_version)"
                           " VALUES (?, ?, ?, ?, 0) ON CONFLICT (partition_id) DO NOTHING"
                           " RETURNING partition_id")
                      id (encode conflict-key) (encode initial) (:publication/head-root initial)]
                  (row-opts))
        row (jdbc/execute-one!
             tx [(str "SELECT conflict_key_edn, head_edn, head_root, store_version FROM " partition-table
                      " WHERE partition_id = ? FOR UPDATE") id]
             (row-opts))]
    (when-not (= conflict-key (decode (:conflict_key_edn row)))
      (throw (ex-info "Publication partition conflict-key mismatch"
                      {:reason :partition-conflict-key-mismatch :partition-id id})))
    {:id id :created? (some? inserted) :head (decode (:head_edn row)) :version (:store_version row)}))

(defn- immutable-insert! [tx table key body-column root body]
  (let [existing (jdbc/execute-one! tx [(str "SELECT " body-column " FROM " table
                                             " WHERE " key " = ?") root] (row-opts))
        encoded (encode body)]
    (cond
      (nil? existing) (jdbc/execute! tx [(str "INSERT INTO " table " (" key ", " body-column
                                              ") VALUES (?, ?)") root encoded])
      (= encoded (:binding_edn existing (:ordering_edn existing))) nil
      :else (throw (ex-info "Immutable publication object conflicts with retained body"
                            {:reason :publication-immutable-conflict :root root})))))

(defn- retained-ordering! [tx ordering]
  (immutable-insert! tx ordering-table "ordering_hash" "ordering_edn"
                     (:transaction-ordering/hash ordering) ordering))

(defn- retained-binding! [tx binding]
  (let [root (:pro-rata-invocation-publication-binding/root binding)
        existing (jdbc/execute-one! tx [(str "SELECT binding_edn, ordering_hash FROM " binding-table
                                             " WHERE binding_root = ?") root] (row-opts))
        encoded (encode binding)
        ordering-root (:publication-ordering/root binding)]
    (cond
      (nil? existing) (jdbc/execute! tx [(str "INSERT INTO " binding-table
                                              " (binding_root, ordering_hash, binding_edn) VALUES (?, ?, ?)")
                                         root ordering-root encoded])
      (and (= encoded (:binding_edn existing)) (= ordering-root (:ordering_hash existing))) nil
      :else (throw (ex-info "Immutable publication binding conflicts with retained body"
                            {:reason :publication-immutable-conflict :root root})))))

(deftype PostgresPublicationStore [datasource resolve-durable-artifact]
  Object
  (toString [_] "PostgresPublicationStore"))

(defn postgres-store
  "Create a durable store. `resolve-durable-artifact` must return the exact
   immutable body for a qualified root only after crash-durable retention."
  [datasource {:keys [resolve-durable-artifact]}]
  (PostgresPublicationStore. datasource resolve-durable-artifact))

(defn current-head [store conflict-key]
  (let [id (partition-id conflict-key)
        row (jdbc/execute-one! (.datasource store)
                               [(str "SELECT head_edn, head_root FROM " partition-table
                                     " WHERE partition_id = ?") id] (row-opts))
        head (some-> row :head_edn decode)]
    (when (and head (head-valid? head) (= (:publication/head-root head) (:head_root row))) head)))

(defn resolve-ordering [store ordering-root]
  (let [ordering (some-> (jdbc/execute-one! (.datasource store)
                                            [(str "SELECT ordering_edn FROM " ordering-table
                                                  " WHERE ordering_hash = ?") ordering-root] (row-opts))
                         :ordering_edn decode)]
    (when (and ordering (= ordering-root (:transaction-ordering/hash ordering))
               (:valid? (ordering/verify-ordering ordering))) ordering)))

(defn resolve-binding [store binding-root]
  (let [binding (some-> (jdbc/execute-one! (.datasource store)
                                           [(str "SELECT binding_edn FROM " binding-table
                                                 " WHERE binding_root = ?") binding-root] (row-opts))
                        :binding_edn decode)]
    (when (and binding (= binding-root (:pro-rata-invocation-publication-binding/root binding))
               (= binding-root (binding/binding-root binding))) binding)))

(defn resolve-authoritative-application-publication [store conflict-key]
  (when-let [head (current-head store conflict-key)]
    (when-let [binding (resolve-binding store (:publication/application-binding-root head))]
      (when-let [ordering (resolve-ordering store (:publication/last-ordering-root head))]
        (when (and (= (:publication/last-ordering-root head) (:publication-ordering/root binding))
                   (= (:transaction-ordering/hash ordering) (:publication-ordering/root binding)))
          {:publication/head head :publication/binding binding :publication/ordering ordering})))))

(defn publish-application-bound!
  "Durably retain B and O and then advance the conflict-key partition head.
  An exact retry whose intended successor is already current is idempotent. The
  sealed resolver is injected into V2 validation only; it is not semantic input."
  [store ordering binding resolved expected-version]
  (let [resolved (assoc resolved
                        :publication-ordering ordering
                        :resolve-body (.resolve-durable-artifact store))]
    (when-not (and (binding/binding-eligible? binding resolved)
                   (= (:publication-ordering/root binding) (:transaction-ordering/hash ordering)))
      (throw (ex-info "Invalid application-bound publication" {:reason :invalid-application-binding})))
    (durable-prerequisites! (.resolve-durable-artifact store) resolved)
    (let [conflict-key (:transaction/conflict-key ordering)]
      (with-transaction-retry!
        #(jdbc/with-transaction [tx (.datasource store) {:isolation :serializable}]
           (let [{:keys [id head version]} (locked-partition! tx conflict-key)
                 binding-root (:pro-rata-invocation-publication-binding/root binding)
                 intended (let [base {:schema-version publication/application-publication-head-schema
                                      :publication/last-ordering-root (:transaction-ordering/hash ordering)
                                      :publication/application-binding-root binding-root
                                      :publication/sequence (inc (:publication/sequence head))
                                      :publication/predecessor-root (:publication/head-root head)}]
                            (assoc base :publication/head-root (publication/application-head-root base)))]
             (cond
               (and (= binding-root (:publication/application-binding-root head))
                    (= (:transaction-ordering/hash ordering)
                       (:publication/last-ordering-root head)))
               {:status :idempotent :publication/head head
                :publication/ordering-root (:transaction-ordering/hash ordering)
                :publication/application-binding-root binding-root}
               (and (some? expected-version) (not= expected-version version))
               {:status :contention :reason :version-mismatch :expected-version expected-version
                :observed-version version}
               :else
               (do (transaction-hook! :after-prerequisite-validation)
                   (retained-ordering! tx ordering)
                   (transaction-hook! :after-ordering-insert)
                   (retained-binding! tx binding)
                   (transaction-hook! :after-binding-insert)
                   (transaction-hook! :before-head-update)
                   (jdbc/execute! tx [(str "UPDATE " partition-table
                                           " SET head_edn = ?, head_root = ?, store_version = ? WHERE partition_id = ?")
                                      (encode intended) (:publication/head-root intended) (inc version) id])
                   (transaction-hook! :after-head-update)
                   {:status :committed :publication/head intended
                    :publication/ordering-root (:transaction-ordering/hash ordering)
                    :publication/application-binding-root binding-root}))))))))
