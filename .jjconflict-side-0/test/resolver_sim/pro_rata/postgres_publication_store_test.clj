(ns resolver-sim.pro-rata.postgres-publication-store-test
  "PostgreSQL integration tests for durable application-bound publication.
   Requires DATABASE_URL or the local compose PostgreSQL on port 5433."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [resolver-sim.db.pool :as pool]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.extensions.manifest :as manifest]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.invocation-publication-binding-test :as fixture]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.pro-rata.postgres-publication-store :as pg])
  (:import [java.nio.file Files]))

(def ^:dynamic *ds* nil)
(defn database-url [] (or (System/getenv "DATABASE_URL")
                          "jdbc:postgresql://localhost:5433/postgres?user=postgres&password=postgres"))

(defn skip-if-no-db [f]
  (try
    (let [ds (pool/pool (database-url) {})]
      (jdbc/execute-one! ds ["SELECT 1"])
      (.close ds)
      (f))
    (catch Exception e
      (println "PostgreSQL publication integration tests skipped (" (.getMessage e) ")")
      (System/exit 0))))

(defn pg-fixture [f]
  (let [ds (pool/pool (database-url) {})]
    (pg/ensure-schema! ds)
    (binding [*ds* ds]
      (try (f)
           (finally
             (jdbc/execute! ds ["TRUNCATE TABLE prf_economic_publication_binding, prf_economic_publication_ordering, prf_economic_publication_partition"])
             (.close ds))))))

(use-fixtures :once skip-if-no-db)
(use-fixtures :each pg-fixture)

(defn- durable-resolver [resolved]
  (let [entries [[:use-case-application :application/root]
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
        objects (into {}
                      (map (fn [[k field]]
                             (let [body (get resolved k)
                                   root (if (= k :capability-descriptor)
                                          (ref/sha256-ref (manifest/capability-descriptor-root body))
                                          (ref/sha256-ref (get body field)))]
                               [root body])))
                      entries)]
    #(get objects %)))

(defn- prepared []
  (let [resolved (@#'fixture/fixture)
        publication-binding (binding/build-binding resolved)
        backend (cas/create-store
                 (str (Files/createTempDirectory "resolver-sim-publication-cas-"
                                                 (make-array java.nio.file.attribute.FileAttribute 0))))]
    (pg/persist-durable-prerequisites! backend resolved)
    {:resolved resolved
     :binding publication-binding
     :ordering (:publication-ordering resolved)
     :backend backend
     :resolver (pg/sealed-durable-resolver backend resolved)}))

(defn- competing-publication [resolved]
  (let [ordering (publication/build-publication-ordering
                  {:receipt (:receipt resolved)
                   :realization (:protocol-transaction-realization resolved)
                   :canonical-transition (:canonical-transition resolved)
                   :action :pro-rata/apply
                   :scope :test
                   :conflict-key [:test]
                   :commit-index 2
                   :previous-transaction-hash nil
                   :input-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"})]
    {:ordering ordering
     :binding (binding/build-binding (assoc resolved :publication-ordering ordering))}))

(defn- publication-for-scope [resolved scope input-root]
  (let [ordering (publication/build-publication-ordering
                  {:receipt (:receipt resolved)
                   :realization (:protocol-transaction-realization resolved)
                   :canonical-transition (:canonical-transition resolved)
                   :action :pro-rata/apply
                   :scope scope
                   :conflict-key [scope]
                   :commit-index 1
                   :previous-transaction-hash nil
                   :input-root input-root})]
    {:ordering ordering
     :binding (binding/build-binding (assoc resolved :publication-ordering ordering))}))

(deftest durable-publication-survives-restart-and-is-idempotent
  (let [{:keys [resolved binding ordering resolver backend]} (prepared)
        first-store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        result (pg/publish-application-bound! first-store ordering binding resolved 0)
        reopened-backend (cas/create-store (:root backend))
        second-store (pg/postgres-store *ds* {:resolve-durable-artifact
                                              (pg/sealed-durable-resolver reopened-backend resolved)})
        conflict-key (:transaction/conflict-key ordering)
        resolved-publication (pg/resolve-authoritative-application-publication second-store conflict-key)
        retry (pg/publish-application-bound! second-store ordering binding resolved 0)]
    (is (= :committed (:status result)))
    (is (= (:publication/head result) (pg/current-head second-store conflict-key)))
    (is (= binding (:publication/binding resolved-publication)))
    (is (= ordering (:publication/ordering resolved-publication)))
    (is (= :idempotent (:status retry)))
    (is (= (:publication/head result) (:publication/head retry)))))

(deftest resolve-authoritative-publication-reports-v1-authority-and-correspondence
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        conflict-key (:transaction/conflict-key ordering)]
    (pg/publish-application-bound! store ordering binding resolved 0)
    (let [result (pg/resolve-authoritative-publication store conflict-key)]
      (is (= :committed (get-in result [:authority :status])))
      (is (nil? (get-in result [:authority :reason])))
      (is (= :verified (get-in result [:correspondence :status])))
      (is (nil? (get-in result [:correspondence :reason])))
      (is (= :not-applicable (get-in result [:semantic-recompilation :status])))
      (is (nil? (get-in result [:semantic-recompilation :reason])))
      (is (= :complete (get-in result [:reachability :status])))
      (is (nil? (get-in result [:reachability :reason])))
      (is (empty? (get-in result [:reachability :missing-roots]))))))

(deftest resolve-authoritative-publication-retains-authority-when-artifacts-are-unavailable
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        unavailable-store (pg/postgres-store *ds* {:resolve-durable-artifact (constantly nil)})
        conflict-key (:transaction/conflict-key ordering)]
    (pg/publish-application-bound! store ordering binding resolved 0)
    (let [result (pg/resolve-authoritative-publication unavailable-store conflict-key)]
      (is (= :committed (get-in result [:authority :status])))
      (is (nil? (get-in result [:authority :reason])))
      (is (= :unverified (get-in result [:correspondence :status])))
      (is (= :missing (get-in result [:correspondence :reason])))
      (is (= :not-applicable (get-in result [:semantic-recompilation :status])))
      (is (= :incomplete (get-in result [:reachability :status])))
      (is (= :missing (get-in result [:reachability :reason])))
      (is (seq (get-in result [:reachability :missing-roots]))))))

(deftest stale-predecessor-does-not-advance-publication
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        conflict-key (:transaction/conflict-key ordering)
        competing (competing-publication resolved)]
    (is (= :committed (:status (pg/publish-application-bound! store ordering binding resolved 0))))
    (is (= :contention (:status (pg/publish-application-bound! store (:ordering competing)
                                                               (:binding competing) resolved 0))))
    (is (= 1 (:publication/sequence (pg/current-head store conflict-key))))))

(deftest injected-transaction-failures-leave-no-authoritative-successor
  (doseq [point [:after-prerequisite-validation :after-ordering-insert
                 :after-binding-insert :before-head-update :after-head-update]]
    (let [{:keys [resolved ordering resolver] :as prepared} (prepared)
          pub-binding (:binding prepared)
          store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
          conflict-key (:transaction/conflict-key ordering)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (binding [pg/*transaction-hook*
                             #(when (= point %) (throw (ex-info "injected" {:point point})))]
                     (pg/publish-application-bound! store ordering pub-binding resolved 0)))
          (str point " rolls back"))
      (is (nil? (pg/current-head store conflict-key)))
      (is (= 0 (:count (jdbc/execute-one! *ds* ["SELECT count(*) AS count FROM prf_economic_publication_ordering"]))))
      (is (= 0 (:count (jdbc/execute-one! *ds* ["SELECT count(*) AS count FROM prf_economic_publication_binding"])))))))

(deftest corrupt-retained-authority-bodies-fail-closed-on-read
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        conflict-key (:transaction/conflict-key ordering)
        root (:pro-rata-invocation-publication-binding/root binding)]
    (pg/publish-application-bound! store ordering binding resolved 0)
    (jdbc/execute! *ds* ["UPDATE prf_economic_publication_binding SET binding_edn = ? WHERE binding_root = ?"
                         "{:corrupt true}\n" root])
    (is (nil? (pg/resolve-binding store root)))
    (is (nil? (pg/resolve-authoritative-application-publication store conflict-key)))))

(deftest concurrent-same-predecessor-has-one-authoritative-successor
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        competing (competing-publication resolved)
        a (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        b (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        start (promise)
        fa (future @start (pg/publish-application-bound! a ordering binding resolved 0))
        fb (future @start (pg/publish-application-bound! b (:ordering competing)
                                                         (:binding competing) resolved 0))]
    (deliver start true)
    (let [results [@fa @fb]
          statuses (frequencies (map :status results))
          head (pg/current-head a (:transaction/conflict-key ordering))]
      (is (= 1 (:committed statuses)))
      (is (= 1 (:contention statuses)))
      (is (= 1 (:publication/sequence head)))
      (is (some? (pg/resolve-authoritative-application-publication a
                                                                   (:transaction/conflict-key ordering)))))))

(deftest missing-ordering-and-corrupt-head-fail-closed
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        conflict-key (:transaction/conflict-key ordering)
        partition (pg/partition-id conflict-key)]
    (pg/publish-application-bound! store ordering binding resolved 0)
    (jdbc/execute! *ds* ["DELETE FROM prf_economic_publication_binding WHERE binding_root = ?"
                         (:pro-rata-invocation-publication-binding/root binding)])
    (is (nil? (pg/resolve-authoritative-application-publication store conflict-key)))
    (jdbc/execute! *ds* ["UPDATE prf_economic_publication_partition SET head_root = ? WHERE partition_id = ?"
                         "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" partition])
    (is (nil? (pg/current-head store conflict-key)))))

(deftest ordering-linkage-corruption-fails-closed
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        conflict-key (:transaction/conflict-key ordering)
        binding-root (:pro-rata-invocation-publication-binding/root binding)]
    (pg/publish-application-bound! store ordering binding resolved 0)
    (jdbc/execute! *ds* ["DELETE FROM prf_economic_publication_binding WHERE binding_root = ?"
                         binding-root])
    (jdbc/execute! *ds* ["DELETE FROM prf_economic_publication_ordering WHERE ordering_hash = ?"
                         (:transaction-ordering/hash ordering)])
    (is (nil? (pg/resolve-ordering store (:transaction-ordering/hash ordering))))
    (is (nil? (pg/resolve-authoritative-application-publication store conflict-key)))
    (jdbc/execute! *ds* ["INSERT INTO prf_economic_publication_ordering (ordering_hash, ordering_edn) VALUES (?, ?)"
                         (:transaction-ordering/hash ordering) (pr-str (assoc ordering :transaction-ordering/hash "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"))])
    (jdbc/execute! *ds* ["INSERT INTO prf_economic_publication_binding (binding_root, ordering_hash, binding_edn) VALUES (?, ?, ?)"
                         binding-root (:transaction-ordering/hash ordering) (pr-str binding)])
    (jdbc/execute! *ds* ["INSERT INTO prf_economic_publication_ordering (ordering_hash, ordering_edn) VALUES (?, ?)"
                         "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
                         (pr-str ordering)])
    (jdbc/execute! *ds* ["UPDATE prf_economic_publication_binding SET ordering_hash = ? WHERE binding_root = ?"
                         "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee" binding-root])
    (is (nil? (pg/resolve-authoritative-application-publication store conflict-key)))))

(deftest retained-objects-reject-alternate-canonical-bodies
  (let [{:keys [resolved binding ordering resolver]} (prepared)
        store (pg/postgres-store *ds* {:resolve-durable-artifact resolver})]
    (pg/publish-application-bound! store ordering binding resolved 0)
    (is (thrown? Exception
                 (jdbc/execute! *ds* ["INSERT INTO prf_economic_publication_ordering (ordering_hash, ordering_edn) VALUES (?, ?)"
                                      (:transaction-ordering/hash ordering) "{:alternate true}\n"])))
    (is (thrown? Exception
                 (jdbc/execute! *ds* ["INSERT INTO prf_economic_publication_binding (binding_root, ordering_hash, binding_edn) VALUES (?, ?, ?)"
                                      (:pro-rata-invocation-publication-binding/root binding)
                                      (:transaction-ordering/hash ordering) "{:alternate true}\n"])))
    (is (= ordering (pg/resolve-ordering store (:transaction-ordering/hash ordering))))
    (is (= binding (pg/resolve-binding store (:pro-rata-invocation-publication-binding/root binding))))))

(deftest different-partitions-advance-independently
  (let [{:keys [resolved resolver]} (prepared)
        p1 (publication-for-scope resolved :partition-a
                                  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        p2 (publication-for-scope resolved :partition-b
                                  "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        a (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        b (pg/postgres-store *ds* {:resolve-durable-artifact resolver})
        start (promise)
        fa (future @start (pg/publish-application-bound! a (:ordering p1) (:binding p1) resolved 0))
        fb (future @start (pg/publish-application-bound! b (:ordering p2) (:binding p2) resolved 0))]
    (deliver start true)
    (is (= #{:committed} (set (map #(-> % deref :status) [fa fb]))))
    (is (= 1 (:publication/sequence (pg/current-head a [:partition-a]))))
    (is (= 1 (:publication/sequence (pg/current-head b [:partition-b]))))
    (is (= (pg/partition-id [:partition-a]) (pg/partition-id [:partition-a])))
    (is (not= (pg/partition-id [:partition-a]) (pg/partition-id [:partition-b])))))
