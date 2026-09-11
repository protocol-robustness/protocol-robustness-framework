(ns resolver-sim.benchmark.research-workflow
  "Protocol-neutral researcher execution lifecycle.

   This namespace is deliberately an orchestration and verification boundary:
   it consumes the authoritative research command, assignment, outcome, and
   extension-composition contracts.  It neither grants chain authority nor
   interprets a valid epistemic conclusion (including disagreement) as an
   execution failure."
  (:require [resolver-sim.benchmark.research-analysis-closure :as closure]
            [resolver-sim.benchmark.research-assignment :as assignment]
            [resolver-sim.benchmark.research-command :as command]
            [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.composition.research-provenance :as executable-provenance]
            [resolver-sim.composition.semantic :as composition]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-execution.v1")
(def ^:const schema-version-v2 "research-execution.v2")
(def ^:const supported-classifications
  #{:completed :disagreement :inconclusive :unsupported :unavailable :failed})

(defn- execution-preimage [execution]
  ;; The trace root is canonical; the byte encoding is retained only to permit
  ;; transport/replay and is outside the canonical value algebra.
  (dissoc execution :research-execution/root :research-execution/trace :errors))

(defn execution-root
  "Content-addressed exact execution identity.  It intentionally includes the
   resolved composition root in addition to portable command identity."
  [execution]
  (hash-ref/sha256-ref
   (hc/domain-hash :research-execution (execution-preimage execution))))

(defn command-trace
  "Build the ordered trace commitment used by this lifecycle.  The existing
   v2 trace is used because it commits ordered command identities and rejects
   malformed/empty components; v1 remains readable legacy compatibility."
  [research-command]
  (command/build-command-trace-v2 {:commands [research-command]}))

(defn- trace-valid? [research-command trace]
  (and (= command/command-trace-v2-schema-version (:trace/schema-version trace))
       (= command/command-trace-v2-purpose (:trace/purpose trace))
       (= [(:command/hash research-command)] (:trace/components trace))
       (= (:trace/root (command-trace research-command)) (:trace/root trace))))

(declare validate-execution)

(defn- validate-execution-v1
  "Verify an execution envelope independently of its origin.

   `:research-execution/origin` is provenance only (`:in-band` or
   `:out-of-band`).  Admission is derived from this verification, never from
   the origin declaration or a supplied accepted boolean."
  [{:keys [research-command research-assignment outcome-manifest
           semantic-composition trace execution] :as _context}]
  (let [errors (atom [])
        execution (or execution {})
        trace (or trace (:research-execution/trace execution))
        command-check (command/validate-command research-command)
        assignment-check (assignment/validate-assignment research-assignment)
        outcome-check (outcome/validate-manifest outcome-manifest)
        composition-check (composition/validate semantic-composition)]
    (when-not (= command/schema-version-v2 (:schema-version research-command))
      (swap! errors conj :research/v1-command-not-admissible))
    (when-not (:valid? command-check) (swap! errors conj :research/invalid-command))
    (when-not (:valid? assignment-check) (swap! errors conj :research/invalid-assignment))
    (when-not (:valid? outcome-check) (swap! errors conj :research/invalid-outcome))
    (when-not (:valid? composition-check) (swap! errors conj :research/unresolved-or-invalid-composition))
    (when-not (= (:command/hash research-command)
                 (:research-assignment/command-root research-assignment))
      (swap! errors conj :research/assignment-command-mismatch))
    (when-not (:complete? (outcome/outcome-complete-for-command? research-command outcome-manifest))
      (swap! errors conj :research/requested-output-closure-failed))
    (when-not (trace-valid? research-command trace)
      (swap! errors conj :research/invalid-command-trace))
    (when-not (= (:command/hash research-command) (:research-execution/command-root execution))
      (swap! errors conj :research/execution-command-mismatch))
    (when-not (= (:research-assignment/hash research-assignment)
                 (:research-execution/assignment-root execution))
      (swap! errors conj :research/execution-assignment-mismatch))
    (when-not (= (:benchmark-outcome/hash outcome-manifest)
                 (:research-execution/outcome-root execution))
      (swap! errors conj :research/execution-outcome-mismatch))
    (when-not (= (:trace/root trace) (:research-execution/trace-root execution))
      (swap! errors conj :research/execution-trace-mismatch))
    (when-not (= (composition/composition-root semantic-composition)
                 (:research-execution/composition-root execution))
      (swap! errors conj :research/execution-composition-mismatch))
    (when-not (contains? supported-classifications
                         (:research-execution/classification execution))
      (swap! errors conj :research/invalid-classification))
    (when-not (contains? #{:in-band :out-of-band} (:research-execution/origin execution))
      (swap! errors conj :research/invalid-origin))
    (when-not (= schema-version (:schema-version execution))
      (swap! errors conj :research/unsupported-execution-schema))
    (when-not (= (:research-execution/root execution) (execution-root execution))
      (swap! errors conj :research/execution-root-mismatch))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn record-execution
  "Construct an immutable execution envelope after a shared benchmark runner
   has produced an outcome.  A valid `:disagreement` is retained as a valid
   execution result; only `:failed` denotes an execution failure."
  [{:keys [research-command research-assignment outcome-manifest
           semantic-composition origin classification interaction-root] :as context}]
  (let [trace (command-trace research-command)
        base {:schema-version schema-version
              :research-execution/origin (or origin :in-band)
              :research-execution/classification (or classification :completed)
              :research-execution/command-root (:command/hash research-command)
              :research-execution/assignment-root (:research-assignment/hash research-assignment)
              :research-execution/outcome-root (:benchmark-outcome/hash outcome-manifest)
              :research-execution/trace-root (:trace/root trace)
              :research-execution/composition-root (composition/composition-root semantic-composition)}
        base (cond-> base interaction-root (assoc :research-execution/interaction-root interaction-root))
        execution (assoc base :research-execution/root (execution-root base))
        verification (validate-execution (assoc context :trace trace :execution execution))]
    (when-not (:valid? verification)
      (throw (ex-info "Research execution cannot be recorded" verification)))
    (assoc execution :research-execution/trace trace)))

(defn- trace-v3-valid?
  [research-command provenance trace]
  (and (= command/command-trace-v3-schema-version (:trace/schema-version trace))
       (= command/command-trace-v3-purpose (:trace/purpose trace))
       (= (:trace/root (command/build-command-trace-v3
                        {:research-command research-command
                         :executable-command-provenance provenance}))
          (:trace/root trace))))

(defn validate-execution-v2
  "Verify a research-execution.v2 envelope and its CC3 provenance source.
   The composition-owned adapter is re-run here, so a supplied root tuple never
   grants admission without the existing lineage and concatenation contracts."
  [{:keys [research-command research-assignment outcome-manifest
           semantic-composition executable-command-provenance-input trace execution]}]
  (let [errors (atom [])
        execution (or execution {})
        trace (or trace (:research-execution/trace execution))
        provenance (try
                     (executable-provenance/verified-executable-command-provenance!
                      executable-command-provenance-input)
                     (catch clojure.lang.ExceptionInfo _
                       (swap! errors conj :research/invalid-executable-command-provenance)
                       nil))
        command-check (command/validate-command research-command)
        assignment-check (assignment/validate-assignment research-assignment)
        outcome-check (outcome/validate-manifest outcome-manifest)
        composition-check (composition/validate semantic-composition)]
    (when-not (= command/schema-version-v2 (:schema-version research-command))
      (swap! errors conj :research/v1-command-not-admissible))
    (when-not (:valid? command-check) (swap! errors conj :research/invalid-command))
    (when-not (:valid? assignment-check) (swap! errors conj :research/invalid-assignment))
    (when-not (:valid? outcome-check) (swap! errors conj :research/invalid-outcome))
    (when-not (:valid? composition-check) (swap! errors conj :research/unresolved-or-invalid-composition))
    (when-not (= (:command/hash research-command)
                 (:research-assignment/command-root research-assignment))
      (swap! errors conj :research/assignment-command-mismatch))
    (when-not (:complete? (outcome/outcome-complete-for-command? research-command outcome-manifest))
      (swap! errors conj :research/requested-output-closure-failed))
    (when-not (and provenance (trace-v3-valid? research-command provenance trace))
      (swap! errors conj :research/invalid-command-trace))
    (when-not (= (:command/hash research-command) (:research-execution/command-root execution))
      (swap! errors conj :research/execution-command-mismatch))
    (when-not (= (:research-assignment/hash research-assignment) (:research-execution/assignment-root execution))
      (swap! errors conj :research/execution-assignment-mismatch))
    (when-not (= (:benchmark-outcome/hash outcome-manifest) (:research-execution/outcome-root execution))
      (swap! errors conj :research/execution-outcome-mismatch))
    (when-not (= (:trace/root trace) (:research-execution/trace-root execution))
      (swap! errors conj :research/execution-trace-mismatch))
    (when-not (= (composition/composition-root semantic-composition)
                 (:research-execution/composition-root execution))
      (swap! errors conj :research/execution-composition-mismatch))
    (doseq [[provenance-key execution-key]
            [[:command/root :research-execution/executable-command-root]
             [:command/combination-root :research-execution/include-combination-root]
             [:command/concatenation-chain-root :research-execution/concatenation-chain-root]]]
      (when-not (= (get provenance provenance-key) (get execution execution-key))
        (swap! errors conj :research/executable-provenance-mismatch)))
    (when-not (contains? supported-classifications (:research-execution/classification execution))
      (swap! errors conj :research/invalid-classification))
    (when-not (contains? #{:in-band :out-of-band} (:research-execution/origin execution))
      (swap! errors conj :research/invalid-origin))
    (when-not (= schema-version-v2 (:schema-version execution))
      (swap! errors conj :research/unsupported-execution-schema))
    (when-not (= (:research-execution/root execution) (execution-root execution))
      (swap! errors conj :research/execution-root-mismatch))
    {:valid? (empty? @errors) :errors (vec @errors)}))

(defn record-execution-v2
  "Record research-execution.v2 with a verified CC3 provenance binding."
  [{:keys [research-command research-assignment outcome-manifest semantic-composition
           executable-command-provenance-input origin classification interaction-root] :as context}]
  (let [provenance (executable-provenance/verified-executable-command-provenance!
                    executable-command-provenance-input)
        trace (command/build-command-trace-v3
               {:research-command research-command
                :executable-command-provenance provenance})
        base {:schema-version schema-version-v2
              :research-execution/origin (or origin :in-band)
              :research-execution/classification (or classification :completed)
              :research-execution/command-root (:command/hash research-command)
              :research-execution/executable-command-root (:command/root provenance)
              :research-execution/include-combination-root (:command/combination-root provenance)
              :research-execution/concatenation-chain-root (:command/concatenation-chain-root provenance)
              :research-execution/assignment-root (:research-assignment/hash research-assignment)
              :research-execution/outcome-root (:benchmark-outcome/hash outcome-manifest)
              :research-execution/trace-root (:trace/root trace)
              :research-execution/composition-root (composition/composition-root semantic-composition)}
        base (cond-> base interaction-root (assoc :research-execution/interaction-root interaction-root))
        execution (assoc base :research-execution/root (execution-root base))
        verification (validate-execution-v2 (assoc context :trace trace :execution execution))]
    (when-not (:valid? verification)
      (throw (ex-info "Research execution v2 cannot be recorded" verification)))
    (assoc execution :research-execution/trace trace)))

(defn validate-execution
  "Dispatch historical v1 and CC3-bound v2 execution verification by schema."
  [context]
  (case (get-in context [:execution :schema-version])
    "research-execution.v2" (validate-execution-v2 context)
    (validate-execution-v1 context)))

(defn submit
  "Verify a result created in- or out-of-band before deriving admission.
   Out-of-band provenance is neither a failure nor an admission bypass."
  [context]
  (let [verification (validate-execution context)
        classification (get-in context [:execution :research-execution/classification])]
    {:submission/origin (get-in context [:execution :research-execution/origin])
     :submission/status (if (:valid? verification) :accepted :rejected)
     :submission/execution-classification classification
     :submission/execution-failed? (= :failed classification)
     :verification verification}))

(defn verify
  "Derive complete researcher-analysis verification, including the existing
   analysis closure.  The closure remains the authority for evidence class."
  [{:keys [research-command incentive-model deviation-domain research-assignment
           outcome-manifest] :as context}]
  (let [submission (submit context)
        analysis (closure/verify-closure {:research-command research-command
                                          :incentive-model incentive-model
                                          :deviation-domain deviation-domain
                                          :research-assignment research-assignment
                                          :outcome-manifest outcome-manifest})]
    {:verified? (and (= :accepted (:submission/status submission))
                     (closure/closure-valid? analysis))
     :submission submission
     :analysis-closure analysis}))

(defn reproduce
  "Compare a persisted execution with an independently supplied reproduction
   context. Exact mode requires execution identity equality; conforming mode
   requires portable command identity and uses `differential` for results."
  [mode original reproduced]
  (let [left (verify original)
        right (verify reproduced)
        exact? (= (get-in original [:execution :research-execution/root])
                  (get-in reproduced [:execution :research-execution/root]))
        command-same? (= (get-in original [:research-command :command/hash])
                         (get-in reproduced [:research-command :command/hash]))]
    {:reproduction/mode mode
     :reproduction/original-valid? (:verified? left)
     :reproduction/reproduced-valid? (:verified? right)
     :reproduction/portable-command-match? command-same?
     :reproduction/exact-execution-match? exact?
     :reproduction/status (cond
                            (not (and (:verified? left) (:verified? right))) :invalid
                            (= mode :exact-environment) (if exact? :reproduced :mismatch)
                            (= mode :independent-conforming) (if command-same? :comparable :scope-mismatch)
                            :else :unsupported)}))

(defn- semantic-projection [context]
  (let [execution (:execution context)
        manifest (:outcome-manifest context)]
    {:command-root (get-in context [:research-command :command/hash])
     :assignment-root (get-in context [:research-assignment :research-assignment/hash])
     :output-roots (select-keys manifest [:outcomes/operational-root
                                          :outcomes/incentive-root
                                          :outcomes/incentive-compatibility-root
                                          :outcomes/incentives-strategies-root
                                          :outcomes/incentives-coalitions-root])
     :evidence-class (get-in (verify context) [:analysis-closure :research-analysis/evidence-class])
     :classification (:research-execution/classification execution)}))

(defn differential
  "Versioned semantic comparison of two research executions. Runtime metadata
   is deliberately excluded. A successful comparison establishes that the
   requested forbidden canonical differences are empty."
  [left right]
  (let [a (semantic-projection left)
        b (semantic-projection right)
        differences (into {}
                          (keep (fn [k] (when (not= (get a k) (get b k))
                                          [k {:left (get a k) :right (get b k)}])))
                          (keys a))]
    {:schema-version "research-differential.v1"
     :comparison/policy :research-semantic-projection.v1
     :comparison/left a
     :comparison/right b
     :comparison/canonical-differences differences
     :comparison/forbidden-canonical-differences differences
     :comparison/equivalent? (empty? differences)}))
