(ns resolver-sim.commands.witness-build
  "Benchmark pipeline phase for building and persisting the execution witness.
   Runs after evidence finalisation and before canonical assurance.
   Produces a witness only when the benchmark definition declares a
   trust-sequence-definition-root — and fails closed when configured
   but missing."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.procedure-execution-witness :as pew]
            [resolver-sim.assurance.witness-verifier :as wv]))

(def witness-filename "manifest/execution-witness.json")

;; ── Shared configuration-aware decision ────────────────────────────────────

(defn witness-requirement
  "Determine the witness state relative to benchmark configuration.
   
   Returns one of:
     :required-present   — configured and witness file exists
     :required-missing   — configured but witness file absent → hard failure
     :unexpected-present — not configured but witness file exists
     :not-required       — neither configured nor present → legacy pass
   
   The `definition-root?` argument is whether the benchmark definition
   declares a trust-sequence-definition-root. The `witness-exists?`
   argument is whether the witness file is on disk."
  [definition-root? witness-exists?]
  (cond
    (and definition-root? witness-exists?) :required-present
    definition-root?                       :required-missing
    witness-exists?                        :unexpected-present
    :else                                  :not-required))

(defn configured-root
  "Read the trust-sequence-definition-root from a benchmark definition,
   returning the root string or nil when not configured."
  [run-root]
  (try
    (let [f (io/file run-root "benchmark/definition.edn")]
      (when (.isFile f)
        (:benchmark/trust-sequence-definition-root (edn/read-string (slurp f)))))
    (catch Exception _ nil)))

(defn configured-correlation-id
  "Read the expected correlation id from a benchmark definition,
   returning the id string or nil."
  [run-root]
  (try
    (let [f (io/file run-root "benchmark/definition.edn")]
      (when (.isFile f)
        (:benchmark/expected-correlation-id (edn/read-string (slurp f)))))
    (catch Exception _ nil)))

(defn- plan-committed-root
  "Read the trust-sequence-definition-root from the pre-execution
   execution plan. This artifact is written before scenario execution
   begins, so it is the authoritative pre-execution commitment.
   Returns the root string or nil."
  [run-root]
  (try
    (let [f (io/file run-root "benchmark/execution-plan.edn")]
      (when (.isFile f)
        (:trust-sequence-definition-root (edn/read-string (slurp f)))))
    (catch Exception _ nil)))

(defn- plan-committed-correlation-id
  "Read the expected-correlation-id from the execution plan."
  [run-root]
  (try
    (let [f (io/file run-root "benchmark/execution-plan.edn")]
      (when (.isFile f)
        (:expected-correlation-id (edn/read-string (slurp f)))))
    (catch Exception _ nil)))

;; ── Evidence directory resolution ──────────────────────────────────────────

(defn- resolve-scenario-evidence-dir
  [context]
  (let [index-file (io/file (str (:benchmark/index-file context)))]
    (when (.isFile index-file)
      (try
        (let [index (edn/read-string (slurp index-file))
              executions (:executions index [])
              execution (first executions)
              artifacts (get-in execution [:scenario/artifacts])
              ev-dir (when artifacts
                       (let [ev-root (:scenario/evidence-root execution)
                             artifact-dir (:scenario/artifact-dir artifacts)]
                         (when (and ev-root artifact-dir)
                           (str (io/file artifact-dir "event-evidence")))))]
          (when ev-dir ev-dir))
        (catch Exception _ nil)))))

(defn- resolve-scenario-registry
  [context]
  (let [index-file (io/file (str (:benchmark/index-file context)))]
    (when (.isFile index-file)
      (try
        (let [index (edn/read-string (slurp index-file))
              executions (:executions index [])
              execution (first executions)
              artifacts (get-in execution [:scenario/artifacts])
              reg-path (:scenario/evidence-registry artifacts)]
          (when reg-path
            (json/read-str (slurp reg-path) :key-fn keyword)))
        (catch Exception _ nil)))))

(defn- resolve-scenario-cursor
  [context]
  (let [index-file (io/file (str (:benchmark/index-file context)))]
    (when (.isFile index-file)
      (try
        (let [index (edn/read-string (slurp index-file))
              executions (:executions index [])
              execution (first executions)
              artifacts (get-in execution [:scenario/artifacts])
              cursor-path (:scenario/chain-cursor artifacts)]
          (when cursor-path
            (json/read-str (slurp cursor-path) :key-fn keyword)))
        (catch Exception _ nil)))))

;; ── Adapter loading ────────────────────────────────────────────────────────

(defn load-adapter
  "Load the evidence adapter for a protocol.
   
   The protocol descriptor selects the adapter. Returns the adapter map.
   
   Throws when the adapter namespace cannot be resolved — there is no
   silent fallback for a configured trust sequence."
  [protocol-id]
  (let [adapter-sym (case protocol-id
                      :protocol/sew
                      'resolver-sim.protocols.sew.procedure-evidence/sew-evidence-adapter
                      nil)]
    (if (nil? adapter-sym)
      (throw (ex-info "No evidence adapter registered for protocol"
                      {:protocol/id protocol-id}))
      (try
        @(requiring-resolve adapter-sym)
        (catch Exception e
          (throw (ex-info "Failed to load evidence adapter"
                          {:protocol/id protocol-id :adapter adapter-sym
                           :error (.getMessage e)})))))))

;; ── Definition resolution ──────────────────────────────────────────────────

(defn- resolve-definition-from-run-root
  "Load and validate the trust-sequence-definition from a run root.
   Returns the definition map or nil when not configured."
  [run-root]
  (try
    (let [ts-root (configured-root run-root)]
      (when ts-root
        (let [src (some-> (io/resource "data/sequences/force-authorised-custody-adjustment.edn")
                          slurp
                          edn/read-string)
              defn (when src
                     (tsd/build-definition
                      {:id (:trust-sequence-definition/id src)
                       :provider (:trust-sequence-definition/provider src)
                       :steps (:trust-sequence-definition/steps src)}))]
          (when (and defn (= (:trust-sequence-definition/root defn) ts-root))
            defn))))
    (catch Exception _ nil)))

;; ── Witness builder ────────────────────────────────────────────────────────

(defn- build-witness-from-scenario
  [context definition adapter expected-auth-id]
  (let [ev-dir (resolve-scenario-evidence-dir context)
        registry (resolve-scenario-registry context)
        cursor (resolve-scenario-cursor context)]
    (when (and ev-dir registry cursor)
      (let [raw-index (wv/build-evidence-index ev-dir)
            scenario-chain (let [records (vals (:evidence-index/by-content-hash raw-index))]
                             (chain/verify-scenario-chain records))
            chain-valid? (= :verified (:chain/status scenario-chain))
            chain-head (when chain-valid? (:chain/head-hash scenario-chain))
            final-index (wv/finalise-evidence-index raw-index (:registry-hash registry) chain-head)
            evidence-map (:evidence-index/by-content-hash raw-index)
            step-ev-types (:step-evidence-types adapter)
            step-bindings (reduce (fn [acc step]
                                    (let [step-id (:step/id step)
                                          expected-type (get step-ev-types step-id)
                                          matching (filter (fn [ev]
                                                             (and (= (:evidence/type ev) expected-type)
                                                                  (or (nil? expected-auth-id)
                                                                      (= (get-in ev [:inputs :force-auth/auth-id]) expected-auth-id))))
                                                           (vals evidence-map))
                                          match-count (count matching)]
                                      (if (= 1 match-count)
                                        ;; Persist step ids as their namespaced string form:
                                        ;; the witness is committed as JSON, which cannot
                                        ;; preserve keyword namespaces in values. Keeping the
                                        ;; in-memory witness identical to its persisted bytes
                                        ;; keeps :procedure-witness/root-integrity recomputable.
                                        (conj acc {:step/id (str step-id) :evidence (first matching)})
                                        (throw (ex-info (str "Expected exactly 1 evidence for step " step-id
                                                             " but found " match-count)
                                                        {:step/id step-id :expected-type expected-type
                                                         :match-count match-count
                                                         :auth-id expected-auth-id})))))
                                  []
                                  (:trust-sequence-definition/steps definition))
            initial-input-root (:world/before-hash (get-in step-bindings [0 :evidence]))
            result-root (:world/after-hash (get-in step-bindings [(dec (count step-bindings)) :evidence]))
            run-id (str "witness-" (java.util.UUID/randomUUID))
            witness (pew/build-witness
                     {:id run-id
                      :definition-root (:trust-sequence-definition/root definition)
                      :initial-input-root initial-input-root
                      :step-bindings step-bindings
                      :result-root result-root})]
        (assoc {:witness witness
                :root (:procedure-execution-witness/root witness)
                :path witness-filename
                :definition-root (:trust-sequence-definition/root definition)
                :evidence-index final-index
                :chain-result scenario-chain
                :chain-verified? chain-valid?
                :chain-head chain-head}
               :definition definition)))))

;; ── Pipeline phase entry point ─────────────────────────────────────────────

(defn build-and-write!
  "Build and persist the execution witness from the benchmark pipeline context.
   
   Behaviour by configuration state:
     definition-root configured + valid evidence → build and persist witness
     definition-root configured + evidence missing → hard failure
     definition-root not configured → skip, return nil
   
   Uses the protocol descriptor from the resolved definition to select
   the evidence adapter. Fails closed when the adapter cannot be loaded."
  [context]
  (let [run-root (str (:run/root context))
        ts-root (configured-root run-root)]
    (if (nil? ts-root)
      (do (println "  [witness] No trust-sequence definition configured — skipping")
          nil)
      (let [definition (resolve-definition-from-run-root run-root)]
        (when (nil? definition)
          (throw (ex-info "Trust-sequence definition root configured but could not resolve definition"
                          {:trust-sequence-definition-root ts-root})))
        (let [plan-root (plan-committed-root run-root)
              _ (when-not plan-root
                  (throw (ex-info "Trust-sequence definition root configured but execution plan has no committed root"
                                  {:trust-sequence-definition-root ts-root
                                   :hint "The execution plan was likely written by an older pipeline version that does not include trust-sequence fields"})))
              _ (when-not (= ts-root plan-root)
                  (throw (ex-info "Pre-execution plan root does not match source definition root"
                                  {:definition-root ts-root :plan-root plan-root})))
              adapter (load-adapter
                       (get-in definition [:trust-sequence-definition/provider :protocol/id]))
              expected-id (or (plan-committed-correlation-id run-root)
                              (do (println "  [witness] WARNING: no expected-correlation-id in execution plan")
                                  nil))
              result (build-witness-from-scenario context definition adapter expected-id)]
          (when (nil? result)
            (throw (ex-info "Witness build failed: could not resolve scenario evidence"
                            {:definition-root ts-root})))
          (let [out-file (io/file run-root witness-filename)]
            (io/make-parents out-file)
            (spit out-file (json/write-str (:witness result)
                                           {:key-fn (fn [k] (if (keyword? k)
                                                              (if-let [ns (namespace k)]
                                                                (str ns "/" (name k)) (name k)) (str k)))
                                            :indent true})))
          (println (str "  [witness] Built and persisted: " (:root result)))
          (assoc result :definition definition))))))

;; ── Canonical assurance helper (used by run_benchmark.clj) ──────────────────

(defn canonical-witness-verification
  "Run witness verification from canonical assurance context.
   Returns {:valid? bool :checks []} or nil when no witness is configured.
   Throws when configured but unable to verify."
  [run-root]
  (let [ts-root (configured-root run-root)]
    (when ts-root
      (let [definition (resolve-definition-from-run-root run-root)
            _ (when (nil? definition)
                (throw (ex-info "Canonical assurance: trust-sequence definition root configured but resolution failed"
                                {:trust-sequence-definition-root ts-root})))
            adapter (load-adapter
                     (get-in definition [:trust-sequence-definition/provider :protocol/id]))
            witness-file (io/file run-root witness-filename)]
        (when-not (.isFile witness-file)
          (throw (ex-info "Canonical assurance: configured execution witness missing"
                          {:trust-sequence-definition-root ts-root
                           :expected-path (str witness-file)})))
        (let [witness (json/read-str (slurp witness-file) :key-fn keyword)
              index-file (io/file run-root "benchmark/index.edn")
              ;; Resolve the scenario execution artifacts the same way the
              ;; witness builder does (build-witness-from-scenario): the index
              ;; execution carries :scenario/artifacts, not a top-level :dir.
              execution (when (.isFile index-file)
                          (first (:executions (edn/read-string (slurp index-file)))))
              artifacts (:scenario/artifacts execution)
              artifact-dir (:scenario/artifact-dir artifacts)
              ev-dir (when artifact-dir (str (io/file artifact-dir "event-evidence")))
              reg-file (:scenario/evidence-registry artifacts)
              cursor-file (:scenario/chain-cursor artifacts)
              registry (when (and reg-file (.isFile (io/file reg-file)))
                         (json/read-str (slurp reg-file) :key-fn keyword))
              cursor (when (and cursor-file (.isFile (io/file cursor-file)))
                       (json/read-str (slurp cursor-file) :key-fn keyword))
              expected-id (or (plan-committed-correlation-id run-root)
                              (configured-correlation-id run-root))
              plan-root (plan-committed-root run-root)]
          (if (and definition witness ev-dir registry cursor)
            (wv/verify-witness-from-finalised-evidence
             witness definition ev-dir registry cursor
             {:evidence-adapter adapter
              :expected-correlation-id expected-id
              :plan-root plan-root})
            (throw (ex-info "Canonical assurance: insufficient artifacts for witness verification"
                            {:has-definition (some? definition)
                             :has-witness (some? witness)
                             :has-ev-dir (some? ev-dir)
                             :has-registry (some? registry)
                             :has-cursor (some? cursor)}))))))))