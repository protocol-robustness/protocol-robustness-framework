(ns resolver-sim.assurance.witness-verifier
  "Pure verification phases for procedure execution witnesses.
   
   All verification functions are pure: they accept data maps and return
   structured check results. Filesystem loading, chain verification, and
   evidence-index construction happen in verify-witness-from-finalised-evidence.
   
   Each phase returns {:checks [...]}. The aggregate verify-witness
   combines phases with prerequisite awareness — later phases are
   skipped or return :not-run when their dependencies fail."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.procedure-evidence :as pev]
            [resolver-sim.evidence.chain :as chain]))

;; ── Check helpers ──────────────────────────────────────────────────────────

(defn- pass [code & {:as context}]
  (merge {:check/code code :check/status :pass} context))

(defn- fail [code detail & {:as context}]
  (merge {:check/code code :check/status :fail :check/detail detail} context))

(defn- not-run [code detail & {:as context}]
  (merge {:check/code code :check/status :not-run :check/detail detail} context))

;; ── Phase 1: Structural integrity ───────────────────────────────────────────

(defn verify-structural-integrity
  "Check witness root recomputes correctly and step IDs are unique."
  [witness]
  (let [steps (:procedure-execution-witness/steps witness [])
        step-ids (map :step/id steps)
        base (dissoc witness :procedure-execution-witness/root)
        expected-root (hc/hash-with-intent {:hash/intent :procedure-execution-witness} base)
        actual-root (:procedure-execution-witness/root witness)
        checks (atom [])]
    (if (= expected-root actual-root)
      (swap! checks conj (pass :procedure-witness/root-integrity))
      (swap! checks conj (fail :procedure-witness/root-integrity
                               (str "expected root " expected-root " got " actual-root))))
    (if (= (count step-ids) (count (set step-ids)))
      (swap! checks conj (pass :procedure-witness/step-ids-unique))
      (swap! checks conj (fail :procedure-witness/step-ids-unique "duplicate step ids")))
    @checks))

;; ── Phase 2: Definition binding ────────────────────────────────────────────

(defn verify-definition-binding
  "Check definition root validates, matches witness, and step IDs/order agree."
  [witness definition]
  (let [steps (:procedure-execution-witness/steps witness [])
        checks (atom [])]
    (let [def-validation (tsd/validate-definition definition)]
      (if (:valid? def-validation)
        (swap! checks conj (pass :procedure-witness/definition-valid))
        (swap! checks conj (fail :procedure-witness/definition-valid
                                 (pr-str (:errors def-validation))))))
    (let [def-root (:trust-sequence-definition/root definition)
          witness-def-root (:procedure-execution-witness/definition-root witness)]
      (if (= def-root witness-def-root)
        (swap! checks conj (pass :procedure-witness/definition-root-matches))
        (swap! checks conj (fail :procedure-witness/definition-root-matches
                                 (str "definition root " def-root " != witness " witness-def-root)))))
    (let [def-step-ids (set (map :step/id (:trust-sequence-definition/steps definition [])))
          witness-step-ids (set (map :step/id steps))]
      (if (= def-step-ids witness-step-ids)
        (swap! checks conj (pass :procedure-witness/step-ids-match-definition))
        (swap! checks conj (fail :procedure-witness/step-ids-match-definition
                                 (str "definition " def-step-ids " != witness " witness-step-ids)))))
    (let [def-order (mapv :step/id (:trust-sequence-definition/steps definition []))
          witness-order (mapv :step/id steps)]
      (if (= def-order witness-order)
        (swap! checks conj (pass :procedure-witness/step-order-matches-definition))
        (swap! checks conj (fail :procedure-witness/step-order-matches-definition
                                 (str "expected order " def-order " got " witness-order))))
      @checks)))

;; ── Phase 3: Evidence resolution ────────────────────────────────────────────

(defn- resolve-step-evidence
  [steps evidence-index adapter]
  (let [checks (atom [])
        resolved (mapv (fn [step]
                         (let [eh (:step/evidence-content-hash step)
                               ev (get (:evidence-index/by-content-hash evidence-index) eh)]
                           (if (nil? ev)
                             (do (swap! checks conj
                                        (fail :procedure-witness/evidence-not-found
                                              (str "content hash " eh " not in evidence index")
                                              :step/id (:step/id step)))
                                 nil)
                             (do (swap! checks conj
                                        (pass :procedure-witness/evidence-resolved
                                              :step/id (:step/id step)))
                                 ev))))
                       steps)]
    {:resolved resolved :checks @checks}))

(defn- check-evidence-types
  [steps resolved adapter]
  (mapcat (fn [step evidence]
            (let [expected (pev/expected-evidence-type adapter (:step/id step))]
              (if (nil? expected) []
                (if (and evidence (= expected (:evidence/type evidence)))
                  [(pass :procedure-witness/evidence-type-matches :step/id (:step/id step))]
                  [(fail :procedure-witness/evidence-type-mismatch
                         (str "expected " expected " got " (if evidence (:evidence/type evidence) "nil"))
                         :step/id (:step/id step))]))))
          steps resolved))

(defn- check-input-output-roots
  [steps resolved]
  (mapcat (fn [step evidence]
            (if (nil? evidence) []
              [(if (= (:step/input-root step) (:world/before-hash evidence))
                 (pass :procedure-witness/input-root-matches :step/id (:step/id step))
                 (fail :procedure-witness/input-root-mismatch
                       (str "expected " (:step/input-root step) " got " (:world/before-hash evidence))
                       :step/id (:step/id step)))
               (if (= (:step/output-root step) (:world/after-hash evidence))
                 (pass :procedure-witness/output-root-matches :step/id (:step/id step))
                 (fail :procedure-witness/output-root-mismatch
                       (str "expected " (:step/output-root step) " got " (:world/after-hash evidence))
                       :step/id (:step/id step)))]))
          steps resolved))

(defn verify-evidence-resolution
  [witness evidence-index adapter]
  (let [steps (:procedure-execution-witness/steps witness [])
        {:keys [resolved checks]} (resolve-step-evidence steps evidence-index adapter)
        type-checks (check-evidence-types steps resolved adapter)
        root-checks (check-input-output-roots steps resolved)]
    (vec (concat checks type-checks root-checks))))

;; ── Phase 4: Chain integrity ────────────────────────────────────────────────

(defn verify-chain-integrity
  [witness evidence-index]
  (let [steps (:procedure-execution-witness/steps witness [])
        seqs (map :step/evidence-chain-seq steps)
        all-self-hashes (:evidence-index/all-chain-self-hashes evidence-index #{})
        checks (atom [])]
    (if (= seqs (sort seqs))
      (swap! checks conj (pass :procedure-witness/chain-seq-monotonic))
      (swap! checks conj (fail :procedure-witness/chain-seq-monotonic
                               (str "seqs " seqs " not monotonic"))))
    (if (= (count seqs) (count (set seqs)))
      (swap! checks conj (pass :procedure-witness/chain-seq-unique))
      (swap! checks conj (fail :procedure-witness/chain-seq-unique
                               (str "duplicate seqs: " seqs))))
    (let [resolved (mapv (fn [step]
                           (get (:evidence-index/by-content-hash evidence-index)
                                (:step/evidence-content-hash step)))
                         steps)]
      (doseq [i (range 1 (count steps))]
        (let [ev (nth resolved i)
              step-id (:step/id (nth steps i))]
          (if (nil? ev)
            (swap! checks conj (not-run :procedure-witness/chain-ancestry
                                        "prerequisite evidence not resolved" :step/id step-id))
            (let [prev-hash (:evidence/chain-prev-hash ev)]
              (if (or (nil? prev-hash) (contains? all-self-hashes prev-hash))
                (swap! checks conj (pass :procedure-witness/chain-ancestry :step/id step-id))
                (swap! checks conj (fail :procedure-witness/chain-ancestry
                                         (str "prev-hash " prev-hash " not in chain") :step/id step-id))))))))
    @checks))

;; ── Phase 5: Correlation identity ───────────────────────────────────────────

(defn- collect-correlation-ids
  [steps resolved adapter]
  (let [checks (atom [])
        ids (mapv (fn [step evidence]
                    (cond
                      (nil? evidence)
                      (do (swap! checks conj
                                 (not-run :procedure-witness/correlation-id
                                          "prerequisite evidence not resolved" :step/id (:step/id step)))
                          nil)
                      :else
                      (let [cid (pev/correlation-id adapter evidence)]
                        (if (= cid pev/unknown-type)
                          (do (swap! checks conj
                                     (fail :procedure-witness/correlation-unknown-type
                                           (str "no correlation path for evidence type "
                                                (:evidence/type evidence))
                                           :step/id (:step/id step)
                                           :evidence-type (:evidence/type evidence)))
                              nil)
                          (do (swap! checks conj
                                     (pass :procedure-witness/correlation-id :step/id (:step/id step)))
                              cid)))))
                  steps resolved)]
    {:ids ids :checks @checks}))

(defn verify-correlation-identity
  [witness evidence-index adapter & {:keys [expected-correlation-id]}]
  (let [steps (:procedure-execution-witness/steps witness [])
        {:keys [ids checks]} (collect-correlation-ids steps
                               (mapv (fn [s]
                                       (get (:evidence-index/by-content-hash evidence-index)
                                            (:step/evidence-content-hash s)))
                                     steps)
                               adapter)
        all-same? (and (seq ids) (apply = ids))
        all-valid? (every? some? ids)]
    (concat
     checks
     [(if (and all-valid? all-same?)
        (pass :procedure-witness/correlation-internally-consistent)
        (fail :procedure-witness/correlation-internally-consistent
              (cond (empty? (remove nil? ids)) "no correlation ids could be extracted"
                    :else (str "inconsistent ids: " ids))))
      (if (nil? expected-correlation-id)
        (not-run :procedure-witness/correlation-matches-planned-instance
                 "no expected correlation id provided")
        (if (and all-valid? all-same? (= (first ids) expected-correlation-id))
          (pass :procedure-witness/correlation-matches-planned-instance
                :expected expected-correlation-id)
          (fail :procedure-witness/correlation-matches-planned-instance
                (str "expected " expected-correlation-id
                     " got " (when all-same? (first ids)))
                :expected expected-correlation-id)))])))

;; ── Phase 6: Result binding ─────────────────────────────────────────────────

(defn verify-result-binding
  [witness]
  (let [steps (:procedure-execution-witness/steps witness [])
        initial-input-root (:procedure-execution-witness/initial-input-root witness)
        result-root (:procedure-execution-witness/result-root witness)]
    (if (empty? steps)
      [(not-run :procedure-witness/result-binding "no steps in witness")]
      (let [first-step (first steps)
            last-step (last steps)]
        [(if (= (:step/input-root first-step) initial-input-root)
           (pass :procedure-witness/initial-input-matches)
           (fail :procedure-witness/initial-input-mismatch
                 (str "expected " initial-input-root " got " (:step/input-root first-step))))
         (if (= (:step/output-root last-step) result-root)
           (pass :procedure-witness/final-output-matches-result)
           (fail :procedure-witness/final-output-mismatch
                 (str "expected " result-root " got " (:step/output-root last-step))))]))))

;; ── Pure verifier (no filesystem access) ────────────────────────────────────

(defn verify-witness
  "Full verification of an execution witness against persisted artifacts.
   
   Arguments:
     witness        — the procedure-execution-witness.v1 map
     definition     — the resolved trust-sequence-definition.v1 map
     evidence-index — {:evidence-index/by-content-hash {hash -> evidence}
                        :evidence-index/all-chain-self-hashes #{hash}
                        :evidence-index/status :chain-verified
                        :evidence-index/registry-root \"...\"
                        :evidence-index/chain-head \"...\"}
     opts           — {:evidence-adapter adapter
                        :expected-correlation-id id}
   
   Returns {:valid? bool :checks [...] :pass-count [...] :fail-count [...]}."
  [witness definition evidence-index & [{:keys [evidence-adapter expected-correlation-id]}]]
  (let [adapter (or evidence-adapter {})
        phases [(vec (verify-structural-integrity witness))
                (vec (verify-definition-binding witness definition))
                (vec (verify-evidence-resolution witness evidence-index adapter))
                (vec (verify-chain-integrity witness evidence-index))
                (vec (verify-correlation-identity witness evidence-index adapter
                                                  :expected-correlation-id
                                                  expected-correlation-id))
                (vec (verify-result-binding witness))]
        all-checks (apply concat phases)
        failures (filter #(= :fail (:check/status %)) all-checks)]
    {:valid? (empty? failures)
     :checks (vec all-checks)
     :pass-count (count (filter #(= :pass (:check/status %)) all-checks))
     :fail-count (count failures)
     :not-run-count (count (filter #(= :not-run (:check/status %)) all-checks))
     :witness-root (:procedure-execution-witness/root witness)}))

(defn verify-witness-summary
  "Return a concise summary map suitable for canonical-assurance checks."
  [witness definition evidence-index & [{:keys [evidence-adapter expected-correlation-id]}]]
  (let [result (verify-witness witness definition evidence-index
                               {:evidence-adapter evidence-adapter
                                :expected-correlation-id expected-correlation-id})]
    {:execution-witness-verified (:valid? result)
     :execution-witness-root (:witness-root result)
     :execution-witness-checks-passed (:pass-count result)
     :execution-witness-checks-failed (:fail-count result)}))

;; ── Evidence index builders (filesystem access, for callers) ───────────────

(defn- read-evidence-file
  [f]
  (try (json/read-str (slurp f) :key-fn keyword) (catch Exception _ nil)))

(defn- find-evidence-files
  [event-evidence-dir]
  (let [d (io/file event-evidence-dir)]
    (when (.isDirectory d)
      (sort (filter #(.endsWith (.getName %) ".json") (file-seq d))))))

(defn build-evidence-index
  "Build evidence index from an event-evidence directory.
   Returns {:evidence-index/by-content-hash {hash -> record}
            :evidence-index/all-chain-self-hashes #{hash}
            :evidence-index/status :unverified}."
  [event-evidence-dir]
  (let [files (find-evidence-files event-evidence-dir)
        evidence-map (into {}
                          (keep (fn [f]
                                  (when-let [ev (read-evidence-file f)]
                                    (when-let [eh (:evidence/hash ev)] [eh ev]))))
                          files)
        all-self-hashes (set (keep :evidence/chain-self-hash (vals evidence-map)))]
    {:evidence-index/by-content-hash evidence-map
     :evidence-index/all-chain-self-hashes all-self-hashes
     :evidence-index/status :unverified}))

(defn finalise-evidence-index
  "Mark an evidence index as chain-verified and annotate with chain metadata.
   Returns an evidence-index with :evidence-index/status :chain-verified."
  [evidence-index registry-root chain-head]
  (assoc evidence-index
         :evidence-index/status :chain-verified
         :evidence-index/registry-root registry-root
         :evidence-index/chain-head chain-head))

;; ── Orchestration facade ───────────────────────────────────────────────────

(defn verify-witness-from-finalised-evidence
  "End-to-end witness verification from finalised evidence artifacts.
   
   Reads the evidence directory, builds an index, runs the canonical
   chain verifier, and then runs the pure witness verifier.
   
   Arguments:
     witness        — the procedure-execution-witness.v1 map
     definition     — the resolved trust-sequence-definition.v1 map
     event-evidence-dir — path to the event-evidence directory
     evidence-registry  — parsed evidence-registry.json map
     chain-cursor       — parsed chain-cursor-final.json map
     opts           — {:evidence-adapter adapter
                        :expected-correlation-id id}
   
   Returns {:valid? bool
            :witness-result {:valid? bool :checks [...]}
            :chain-result {:chain/status keyword :chain/errors [...]}}."
  [witness definition event-evidence-dir evidence-registry chain-cursor
   & [{:keys [evidence-adapter expected-correlation-id]}]]
  (let [;; Verify the registry hash
        registry-valid? (:valid (chain/verify-registry-hash evidence-registry))
        
        ;; Verify scenario chain
        scenario-chain (try
                         (let [records (vals (get-in evidence-registry [:evidence-index/by-content-hash
                                                                        (:evidence-index/by-content-hash
                                                                         (build-evidence-index event-evidence-dir))]
                                                    {}))]
                           (chain/verify-scenario-chain records))
                         (catch Exception _
                           {:chain/status :invalid :chain/errors [(str "chain verification failed")]}))

        chain-valid? (= :verified (:chain/status scenario-chain))
        
        ;; Build and finalise evidence index
        raw-index (build-evidence-index event-evidence-dir)
        chain-head (when chain-valid? (:chain/head-hash scenario-chain))
        registry-root (:registry-hash evidence-registry)
        final-index (finalise-evidence-index raw-index registry-root chain-head)
        
        ;; Run pure witness verifier
        witness-result (verify-witness witness definition final-index
                                       {:evidence-adapter evidence-adapter
                                        :expected-correlation-id expected-correlation-id})

        ;; Chain-specific checks
        chain-checks [(if registry-valid?
                        (pass :evidence-chain/registry-hash-valid)
                        (fail :evidence-chain/registry-hash-invalid
                              (str "registry hash mismatch")))
                      (if chain-valid?
                        (pass :evidence-chain/chain-valid
                              :chain-head chain-head
                              :reachable-hashes (:chain/reachable-hashes scenario-chain))
                        (fail :evidence-chain/chain-invalid
                              (pr-str (:chain/errors scenario-chain))))]
        
        all-checks (vec (concat chain-checks (:checks witness-result)))
        failures (filter #(= :fail (:check/status %)) all-checks)]
    {:valid? (empty? failures)
     :checks all-checks
     :witness-result (assoc witness-result :checks (:checks witness-result))
     :chain-result (assoc scenario-chain :registry-valid? registry-valid?)
     :pass-count (count (filter #(= :pass (:check/status %)) all-checks))
     :fail-count (count failures)
     :not-run-count (count (filter #(= :not-run (:check/status %)) all-checks))
     :evidence-index/status (:evidence-index/status final-index)
     :evidence-index/registry-root registry-root
     :evidence-index/chain-head chain-head}))