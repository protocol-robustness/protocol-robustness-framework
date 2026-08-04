(ns resolver-sim.io.scenario-export
  "Export Clojure invariant scenario maps to trace.json / scenarios/edn/*.edn (pure)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.config.paths :as paths]))

(defn- agent->trace-agent
  [{:keys [id address strategy role]}]
  {:id      id
   :address address
   :type    (or role strategy "honest")})

(defn- agent->public-json-agent
  [{:keys [id address strategy role]}]
  (cond-> {:id id :address address}
    (or strategy role) (assoc :strategy (or strategy role "honest"))
    role             (assoc :role role)))

(defn scenario-id->public-json-filename
  "Map s19-dr3-... → S19_dr3-....edn (public scenarios/ naming)."
  [scenario-id]
  (when-let [[_ n rest] (re-matches #"^s(\d+[a-z]?)-(.+)$" scenario-id)]
    (str "S" n "_" rest ".edn")))

(defn- stringify-escalation-keys
  [params]
  (if-let [ers (:escalation-resolvers params)]
    (assoc params :escalation-resolvers
           (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) ers)))
    params))

(defn- prepare-protocol-params [params]
  (-> params stringify-escalation-keys walk/stringify-keys))

(defn- json-value
  ([v]
   (cond
     (keyword? v) (name v)
     (symbol? v)  (str v)
     :else v))
  ([_k v] (json-value v)))

(def ^:private public-metadata-keys
  [:description :scenario-title :scenario-family :scenario-purpose :purpose
   :title :threat-tags :security-properties :threat-model :expected-outcome
   :theory :expectations :notes :provenance])

(defn- attach-public-metadata [doc scenario]
  (reduce (fn [d k]
            (if (contains? scenario k)
              (assoc d k (get scenario k))
              d))
          doc
          public-metadata-keys))

(defn scenario->trace-document
  "Build a schema 1.1 trace document from an invariant scenario map.
   Prefers :title, :threat-tags, :purpose from the scenario map when present,
   falling back to metadata, then defaults."
  [scenario {:keys [title purpose threat-tags]}]
  (let [sid     (:scenario-id scenario)
        sc-title (or (:title scenario) title sid)
        sc-purpose (or (:purpose scenario) purpose "regression")
        sc-tags (vec (or (:threat-tags scenario) threat-tags []))]
    (-> (cond-> {:schema-version     "1.1"
                 :id                 (str (paths/scenarios-root) "/" sid)
                 :title              sc-title
                 :purpose            sc-purpose
                 :threat-tags        sc-tags
                 :scenario-id        sid
                 :initial-block-time (:initial-block-time scenario 1000)
                 :agents             (mapv agent->trace-agent (:agents scenario []))
                 :protocol-params    (prepare-protocol-params (:protocol-params scenario {}))
                 :events             (:events scenario [])
                 :scenario-author    (:scenario-author scenario)}
          (:expected-errors scenario)
          (assoc :expected-errors
                 (mapv #(update % :error json-value) (:expected-errors scenario)))
          (:strict-expected-errors? scenario)
          (assoc :strict-expected-errors? true)
          (:allow-open-disputes? scenario)
          (assoc :allow-open-disputes? true)
          (:notes scenario)
          (assoc :notes (:notes scenario)))
        (attach-public-metadata scenario))))

(defn scenario->public-json-document
  "Build scenarios/edn/*.edn document (schema 1.0) from an invariant scenario map.
   Includes :title and :threat-tags when present on the scenario."
  [scenario]
  (let [sid (:scenario-id scenario)
        base (cond-> {:schema-version     "1.0"
                      :scenario-id        sid
                      :initial-block-time (:initial-block-time scenario 1000)
                      :agents             (mapv agent->public-json-agent (:agents scenario []))
                      :protocol-params    (prepare-protocol-params (:protocol-params scenario {}))
                      :events             (:events scenario [])}
               (:title scenario) (assoc :title (:title scenario))
               (:threat-tags scenario) (assoc :threat-tags (vec (:threat-tags scenario)))
               (:scenario-author scenario) (assoc :scenario-author (:scenario-author scenario))
               (:expected-errors scenario)
               (assoc :expected-errors
                      (mapv #(update % :error json-value) (:expected-errors scenario)))
               (:strict-expected-errors? scenario)
               (assoc :strict-expected-errors? true)
               (:allow-open-disputes? scenario)
               (assoc :allow-open-disputes? true))]
    (attach-public-metadata base scenario)))

(def default-export-metadata
  {"s19-dr3-kleros-escalation-rejected-l0-resolves"
   {:title "DR3 Kleros: Escalation Rejected, L0 Resolves"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s62-cross-token-isolation-under-dispute-load"
   {:title "Cross-Token Isolation Under Concurrent Disputes"
    :purpose "regression"
    :threat-tags ["cross-token-contamination" "accounting-integrity" "concurrent-disputes"]}

   "s62-cross-token-fee-on-transfer-under-dispute-load"
   {:title "Fee-on-Transfer Under Concurrent Disputes"
    :purpose "regression"
    :threat-tags ["fee-on-transfer" "accounting-integrity" "concurrent-disputes"]}

   "s62-cross-token-parallel-appeal-depths-under-dispute-load"
   {:title "Parallel Appeal Depths Under Concurrent Disputes"
    :purpose "regression"
    :threat-tags ["appeal-escalation" "cross-token-contamination" "concurrent-disputes"]}

   "s62-resolver-capacity-concurrent-dispute-load"
   {:title "Resolver Capacity Under Concurrent Dispute Load"
    :purpose "regression"
    :threat-tags ["resolver-capacity" "dispute-flooding" "concurrent-disputes"]}

   "s26-forking-strategist-l1-reversal"
   {:title "Forking Strategist: L1 Reversal"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s27-forking-strategist-l2-fork"
   {:title "Forking Strategist: L2 Fork"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s28-forking-strategist-late-escalation-rejected"
   {:title "Forking Strategist: Late Escalation Rejected"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s29-forking-strategist-seller-escalates"
   {:title "Forking Strategist: Seller Escalates"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s30-forking-strategist-double-loss"
   {:title "Forking Strategist: Double Loss"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s31-forking-strategist-all-levels-confirm"
   {:title "Forking Strategist: All Levels Confirm"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s32-forking-strategist-premature-settlement-rejected"
   {:title "Forking Strategist: Premature Settlement Rejected"
    :purpose "adversarial-robustness"
    :threat-tags ["forking-strategist" "appeal-escalation"]}

   "s33-forking-strategist-two-escrow-fork-isolation"
   {:title "Fork Isolation Across Two Disputed Escrows"
    :purpose "adversarial-robustness"
    :threat-tags ["fork-isolation" "state-isolation" "appeal-escalation"]}

   "s20-dr3-kleros-max-escalation-guard"
   {:title "DR3 Kleros: Max Escalation Guard"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s21-dr3-kleros-pending-cleared-on-escalation"
   {:title "DR3 Kleros: Pending Cleared On Escalation"
    :purpose "regression"
    :threat-tags ["appeal-escalation"]}

   "s22-status-leak-agree-cancel-over-dispute"
   {:title "Status Leak: Agree-Cancel Over Dispute"
    :purpose "regression"
    :threat-tags ["state-transition" "concurrent-disputes"]}

   "s23-preemptive-escalation-blocked"
   {:title "DR3 Kleros: Preemptive Escalation Blocked"
    :purpose "regression"
    :threat-tags ["escalation-abuse"]}})

(defn write-json-file
  [doc path]
  (io/make-parents path)
  (spit path (json/write-str doc {:indent true :key-fn name :value-fn json-value})))

(defn- write-edn-file
  "Write a Clojure map as proper EDN with keyword keys."
  [doc path]
  (io/make-parents path)
  (spit path (pr-str doc)))

(defn- scenario-inline-metadata
  "Extract metadata directly from the scenario map if available.
   Returns nil when the scenario has no inline metadata keys."
  [scenario]
  (let [title (:title scenario)
        purpose (:purpose scenario)
        tags (:threat-tags scenario)]
    (when (or title purpose tags)
      (cond-> {}
        title   (assoc :title title)
        purpose (assoc :purpose purpose)
        tags    (assoc :threat-tags tags)))))

(defn export-scenario-files!
  "Write trace fixture and optional public scenario EDN for one scenario map.
   Metadata is resolved in this order (last wins):
     1. hardcoded default-export-metadata map (by scenario-id)
     2. inline keys from the scenario map itself (:title, :purpose, :threat-tags)
     3. caller-supplied :metadata argument"
  [scenario & {:keys [metadata write-public-json?]}]
  (let [sid         (:scenario-id scenario)
        meta        (merge (get default-export-metadata sid {})
                           (scenario-inline-metadata scenario)
                           metadata)
        trace-doc   (scenario->trace-document scenario meta)
        trace-p     (str (paths/traces-dir) "/" sid ".trace.json")
        public-name (scenario-id->public-json-filename sid)
        scen-p      (when (and write-public-json? public-name)
                      (str sc/*scenario-dir* "/" public-name))]
    (write-json-file trace-doc trace-p)
    (when scen-p
      (write-edn-file (scenario->public-json-document scenario) scen-p))
    (cond-> {:trace-path trace-p :scenario-id sid}
      scen-p (assoc :scenario-path scen-p))))