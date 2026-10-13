(ns resolver-sim.protocols.sew.accounting-projection
  "Canonical Sew accounting-projection.v1 builder.

   This is deliberately separate from the legacy funds-ledger-view. It provides
   a closed, per-asset conservation projection with explicit completeness and
   source/cutpoint commitments. Legacy token keys are accepted only through an
   explicit asset mapping supplied by the caller."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.protocols.sew.types :as t]))

(def schema-version "sew/accounting-projection.v1")
(def projection-domain "prf.sew.accounting-projection.v1")
(def sha256-ref? hash-ref/valid-sha256-ref?)

(declare root!)

(def asset-identity-fields
  #{:schema-version :chain-id :token-address :unit :asset/root})

(defn- validate-asset-identity! [legacy-key asset]
  (when-not (= asset-identity-fields (set (keys asset)))
    (throw (ex-info "accounting asset identity has invalid fields"
                    {:legacy-key legacy-key
                     :unknown (set/difference (set (keys asset)) asset-identity-fields)
                     :missing (set/difference asset-identity-fields (set (keys asset)))})))
  (when-not (and (= "prf/evm-erc20-asset.v1" (:schema-version asset))
                 (integer? (:chain-id asset))
                 (pos? (:chain-id asset))
                 (string? (:token-address asset))
                 (not (str/blank? (:token-address asset)))
                 (= :erc20-base-unit (:unit asset)))
    (throw (ex-info "accounting asset identity is malformed"
                    {:legacy-key legacy-key :asset asset})))
  (root! :asset/root (:asset/root asset)))

(defn with-accounting-metadata
  "Attach explicit accounting metadata to a Sew world.

   This constructor intentionally does not derive any value. Callers must
   provide the token-to-asset mapping and committed source/cutpoint metadata;
   projection derivation remains fail-closed when any of those are absent."
  [world metadata]
  (when-not (map? metadata)
    (throw (ex-info "accounting metadata requires a map"
                    {:metadata metadata})))
  (let [{:keys [assets cutpoint source-roots from through world-root complete?]} metadata
        asset-roots (mapv (fn [[legacy-key asset]]
                            (when-not (map? asset)
                              (throw (ex-info "accounting asset identity requires a map"
                                              {:legacy-key legacy-key :asset asset})))
                            (validate-asset-identity! legacy-key asset))
                          assets)]
  (when-not (map? assets)
    (throw (ex-info "accounting metadata requires an asset mapping" {})))
  (when-not (map? source-roots)
    (throw (ex-info "accounting metadata requires source roots" {})))
  (doseq [[source root] source-roots]
    (when-not (= :empty root)
      (root! source root)))
  (when-not world-root
    (throw (ex-info "accounting metadata requires a world root" {})))
  (let [cutpoint* (or cutpoint {:mode :simulation :world-root world-root})]
    (when-not (map? cutpoint*)
      (throw (ex-info "accounting metadata requires a cutpoint map" {})))
    (when-not (:mode cutpoint*)
      (throw (ex-info "accounting metadata cutpoint requires a mode" {})))
    (root! :cutpoint/world-root (:world-root cutpoint*)))
  (when-not (= (count asset-roots) (count (distinct asset-roots)))
    (throw (ex-info "accounting metadata contains duplicate asset roots"
                    {:asset-roots asset-roots})))
  (when complete?
    (when-not (and (sha256-ref? from)
                   (sha256-ref? through)
                   (seq source-roots))
      (throw (ex-info "complete accounting metadata requires range and sources"
                      {:from from :through through
                       :source-roots (keys source-roots)}))))
  (assoc world
         :accounting/assets assets
         :accounting/cutpoint (or cutpoint {:mode :simulation :world-root world-root})
         :accounting/source-roots source-roots
         :accounting/from from
         :accounting/through through
         :accounting/world-root world-root
         :params (assoc (:params world) :accounting/complete? (true? complete?)))))

(def current-held-buckets
  #{:active-principal :release-claimable :refund-claimable
    :accrued-protocol-fees :bond-obligations :yield-custody
    :explicit-reserves :other-modeled-custody})

(def outflow-buckets
  #{:release-withdrawals :refund-withdrawals :fee-withdrawals
    :authorized-other-outflows})

(defn- non-negative-integer! [field value]
  (when-not (and (integer? value) (not (neg? value)))
    (throw (ex-info "accounting projection requires a non-negative integer"
                    {:field field :value value})))
  value)

(defn- root! [field value]
  (when-not (sha256-ref? value)
    (throw (ex-info "accounting projection requires a SHA-256 reference"
                    {:field field :value value})))
  value)

(defn- closed-map! [field allowed value]
  (when-not (map? value)
    (throw (ex-info "accounting projection requires a map" {:field field})))
  (when (seq (set/difference (set (keys value)) allowed))
    (throw (ex-info "accounting projection contains unknown fields"
                    {:field field
                     :unknown (set/difference (set (keys value)) allowed)})))
  value)

(defn- asset-root [asset]
  (or (:asset/root asset)
      (throw (ex-info "asset identity requires :asset/root" {:asset asset}))))

(defn- sum-values [m]
  (reduce + 0 (map (fn [[bucket amount]]
                     (when-not (contains? current-held-buckets bucket)
                       (throw (ex-info "unknown held accounting bucket"
                                       {:bucket bucket})))
                     (non-negative-integer! bucket amount))
                   m)))

(defn- sum-outflows [m]
  (reduce + 0 (map (fn [[bucket amount]]
                     (when-not (contains? outflow-buckets bucket)
                       (throw (ex-info "unknown accounting outflow bucket"
                                       {:bucket bucket})))
                     (non-negative-integer! bucket amount))
                   m)))

(defn- asset-row [row]
  (closed-map! :asset-row
               #{:asset/root :asset/identity :inflows :outflows :held :adjustments
                 :conservation}
               row)
  (let [asset (or (:asset/identity row)
                  (throw (ex-info "asset row requires :asset/identity" {})))
        asset-root* (asset-root asset)
        inflows (or (:inflows row) {})
        outflows (or (:outflows row) {})
        held (or (:held row) {})
        inflow-total (reduce (fn [total [bucket amount]]
                               (when-not (= bucket :external-funding)
                                 (throw (ex-info "unknown accounting inflow bucket"
                                                 {:bucket bucket})))
                               (+ total (non-negative-integer! bucket amount)))
                             0 inflows)
        outflow-total (sum-outflows outflows)
        held-total (sum-values held)
        difference (- inflow-total outflow-total held-total)]
    (when-not (= asset-root* (:asset/root row))
      (throw (ex-info "asset row root does not match asset identity"
                      {:row-root (:asset/root row) :asset-root asset-root*})))
    {:asset/root asset-root*
     :asset/identity asset
     :inflows {:external-funding inflow-total}
     :outflows (into (sorted-map) outflows)
     :held (into (sorted-map) held)
     :adjustments (or (:adjustments row) {})
     :conservation {:inflows inflow-total
                    :outflows outflow-total
                    :held held-total
                    :difference difference
                    :holds? (zero? difference)}}))

(defn- validate-completeness [value]
  (closed-map! :completeness
               #{:status :from :through :covered-sources :missing-sources :reason}
               value)
  (when-not (contains? #{:complete :incomplete} (:status value))
    (throw (ex-info "invalid accounting completeness status" {:value value})))
  (when (and (= :complete (:status value)) (seq (:missing-sources value)))
    (throw (ex-info "complete accounting cannot list missing sources" {:value value})))
  (when (and (= :incomplete (:status value)) (empty? (:missing-sources value)))
    (throw (ex-info "incomplete accounting requires missing sources" {:value value})))
  (assoc value :covered-sources (vec (sort (or (:covered-sources value) [])))))

(defn- projection-root [body]
  (hash-ref/sha256-ref
   (hc/domain-hash projection-domain body)))

(defn accounting-projection
  "Build a canonical Sew accounting projection.

   Required input keys are :cutpoint, :source-roots, :completeness, and
   :assets. :assets is a vector of rows whose :asset/identity is a validated
   domain-qualified identity. This function intentionally does not infer asset
   identity from legacy token keys."
  [{:keys [cutpoint source-roots completeness assets] :as input}]
  (closed-map! :projection
               #{:cutpoint :source-roots :completeness :assets}
               input)
  (when-not (map? cutpoint)
    (throw (ex-info "accounting projection requires a cutpoint" {})))
  (when-not (map? source-roots)
    (throw (ex-info "accounting projection requires source roots" {})))
  (doseq [[source root] source-roots]
    (if (= :empty root)
      nil
      (root! source root)))
  (let [complete (validate-completeness completeness)]
    (when-not (vector? assets)
      (throw (ex-info "accounting projection assets must be a vector" {})))
    (let [rows (mapv asset-row assets)
          roots (map :asset/root rows)]
      (when-not (= (count roots) (count (distinct roots)))
        (throw (ex-info "accounting projection contains duplicate asset roots" {})))
      (let [status (cond
                     (= :incomplete (:status complete)) :not-evaluated
                     (every? #(get-in % [:conservation :holds?]) rows) :pass
                     :else :fail)
            body {:accounting-projection/schema schema-version
                  :accounting-projection/scope :sew
                  :accounting-projection/cutpoint cutpoint
                  :accounting-projection/source-roots (into (sorted-map) source-roots)
                  :accounting-projection/completeness complete
                  :accounting-projection/assets rows
                  :accounting-projection/summary
                  {:asset-count (count rows)
                   :complete? (= :complete (:status complete))
                   :conservation-status status
                   :difference-by-asset
                   (into (sorted-map)
                         (map (fn [row]
                                [(:asset/root row)
                                 (get-in row [:conservation :difference])])
                              rows))}}]
        (assoc body :accounting-projection/root (projection-root body))))))

(defn accounting-projection-valid?
  "Validate the derived projection root and fail-closed completeness status."
  [projection]
  (try
    (let [root (:accounting-projection/root projection)
          body (dissoc projection :accounting-projection/root)
          rebuilt (accounting-projection
                   {:cutpoint (:accounting-projection/cutpoint projection)
                    :source-roots (:accounting-projection/source-roots projection)
                    :completeness (:accounting-projection/completeness projection)
                    :assets (:accounting-projection/assets projection)})]
      {:valid? (and (= root (:accounting-projection/root rebuilt))
                    (= body (dissoc rebuilt :accounting-projection/root)))
       :complete? (= :complete (get-in projection [:accounting-projection/completeness :status]))
       :conservation-status (get-in projection [:accounting-projection/summary :conservation-status])})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :error (ex-data e)})))

(defn derive-accounting-projection
  "Derive a canonical projection from a Sew world using explicit asset metadata.

   `:accounting/assets` maps each legacy token key to a validated asset identity.
   No symbol, string, or bare address is resolved implicitly. Worlds without
   that mapping return an incomplete diagnostic result rather than a guessed
   projection."
  [world]
  (let [asset-map (get world :accounting/assets)
        tokens (-> #{}
                   (into (keys (:total-principal-deposited world)))
                   (into (keys (:total-held world)))
                   (into (keys (:total-fees world)))
                   (into (keys (:total-withdrawn world)))
                   (into (keys (:total-bonds-posted world))))
        missing (vec (remove #(contains? asset-map %) tokens))]
    (if (or (not (map? asset-map)) (seq missing))
      {:accounting-projection/schema schema-version
       :accounting-projection/status :not-evaluated
       :accounting-projection/reason :missing-explicit-asset-mapping
       :accounting-projection/missing-assets missing}
      (let [rows (for [token (sort-by str tokens)
                       :let [asset (get asset-map token)
                             root (:asset/root asset)
                             principal (get (:total-principal-deposited world) token 0)
                             held (get (:total-held world) token 0)
                             fees (get (:total-fees world) token 0)
                             withdrawn (get (:total-withdrawn world) token 0)
                             bonds (get (:total-bonds-posted world) token 0)]]
                   {:asset/root root
                    :asset/identity asset
                    :inflows {:external-funding principal}
                    :outflows {:fee-withdrawals (get (:total-fees-withdrawn world) token 0)
                               :authorized-other-outflows withdrawn}
                    :held {:active-principal held
                           :accrued-protocol-fees fees
                           :bond-obligations bonds}})
            complete? (true? (get-in world [:params :accounting/complete?]))]
        (accounting-projection
         {:cutpoint (or (:accounting/cutpoint world)
                        {:mode :simulation
                         :world-root (or (:accounting/world-root world)
                                         (throw (ex-info "accounting derivation requires world root" {})))})
          :source-roots (or (:accounting/source-roots world)
                            (throw (ex-info "accounting derivation requires source roots" {})))
          :completeness {:status (if complete? :complete :incomplete)
                         :from (:accounting/from world)
                         :through (:accounting/through world)
                         :covered-sources (or (:accounting/covered-sources world)
                                             (set (keys (:accounting/source-roots world))))
                         :missing-sources (if complete? [] [:accounting/completeness])
                         :reason (when-not complete? :missing-completeness-declaration)}
          :assets (vec rows)})))))
