(ns resolver-sim.notebook-support.speds.narrative
  "P1: Evidence-backed narrative frame model.

   Builds a canonical, renderer-independent frame map from committed artifacts
   (coverage, trace, golden report). The frame model is the single source of
   truth for what a scenario image may assert; renderers consume it and never
   infer on their own.

   Architectural rule (P0): narrative rendering may SIMPLIFY evidence but may
   NEVER STRENGTHEN it. Every substantive claim therefore carries a
   :claim/basis in #{:observed :derived :not-measured} and an identifiable
   :claim/source. Missing data renders explicitly as unavailable, never as a
   synthetic guarantee.

   This namespace is pure and deterministic: build-frame returns identical
   output for identical input (no wall-clock, no randomness)."
  (:require [clojure.string :as str]
            [resolver-sim.notebook-support.speds.data :as data]))

(def frame-schema-version "speds.frame.v1")

(def claim-bases
  "The only truth-states a claim may carry."
  #{:observed :derived :not-measured})

(defn claim
  "Construct a canonical claim.
   id      - stable keyword id
   value   - the fact being asserted (nil when not measured)
   basis   - :observed | :derived | :not-measured
   source- vector of [:artifact & path] or similar provenance"
  [id value basis source]
  {:claim/id id
   :claim/value value
   :claim/basis basis
   :claim/source (vec source)})

(defn- scenario-of
  [artifacts scenario-id]
  (data/find-scenario-by-id (:coverage artifacts) scenario-id))

(defn- trace-of
  [artifacts scenario-id]
  (let [norm (data/scenario-golden-key scenario-id)]
    (first (filter (fn [t]
                     (or (= (:id t) scenario-id)
                         (= (data/scenario-golden-key (:scenario-id t)) norm)
                         (= (data/scenario-golden-key (:id t)) norm)))
                   (or (:all-traces artifacts) [])))))

(defn- golden-of
  [artifacts scenario-id]
  (data/find-golden-report (:golden-reports artifacts) scenario-id))

;; ──────────────────────────────────────────────────────────────────────────
;; Section builders. Each returns structured data whose substantive leaves
;; are claim maps. No section ever invents a value that is not in its source.
;; ──────────────────────────────────────────────────────────────────────────

(defn- threat-section
  [scenario scenario-id]
  (let [tags (:threat-tags scenario)]
    {:threat/tags
     (if (seq tags)
       (claim :threat/tags tags :observed
              [[:coverage :scenarios scenario-id :threat-tags]])
       (claim :threat/tags nil :not-measured []))
     :threat/purpose
     (if (some? (:purpose scenario))
       (claim :threat/purpose (:purpose scenario) :observed
              [[:coverage :scenarios scenario-id :purpose]])
       (claim :threat/purpose nil :not-measured []))}))

(defn- response-section
  "Guards observed on the scenario, each annotated with whether the transition
   it protects was actually exercised in the trace (derived when events exist)."
  [scenario trace scenario-id]
  (let [guards  (or (:guards scenario) [])
        events  (or (:events trace) [])
        actions (set (map :action events))]
    {:response/guards
     (mapv (fn [{:keys [guard transition]}]
             {:guard/id guard
              :guard/transition transition
              :guard/exercised
              (if (seq events)
                (claim :guard/exercised? (contains? actions transition) :derived
                       [[:coverage :scenarios scenario-id :guards]
                        [:trace :events :action]])
                (claim :guard/exercised? nil :not-measured []))})
           guards)
     :response/basis (if (seq guards) :observed :not-measured)}))

(defn- expected-outcome
  "Scenario expectations are recorded verbatim in the trace (observed facts
   about what the scenario asserts), never reinterpreted as actual results.
   A specific expectation key that is absent or nil is :not-measured, never
   :observed with no value."
  [trace]
  (let [exp (:expectations trace)]
    {:outcome/expected
     {:terminal
      (if (some? (:terminal exp))
        (claim :outcome/expected-terminal (:terminal exp) :observed
               [[:trace :expectations :terminal]])
        (claim :outcome/expected-terminal nil :not-measured []))
      :metrics
      (if (some? (:metrics exp))
        (claim :outcome/expected-metrics (:metrics exp) :observed
               [[:trace :expectations :metrics]])
        (claim :outcome/expected-metrics nil :not-measured []))}}))

(defn- actual-outcome
  "Actual results come only from the committed golden report. Each metric is
   claimed :observed if present in the report, otherwise :not-measured.

   The report's :theory block (when present) carries the property-check
   verdict — e.g. :status :not-falsified, :mechanism-status :fail with a
   mechanism reason. This is the honest headline for adversarial /
   falsification frames, and is deliberately kept distinct from the raw
   replay :outcome (which only records that the replay completed)."
  [golden scenario-id]
  (let [g (or golden {})
        metrics (:metrics g {})
        theory (when (map? (:theory g)) (:theory g))
        present? (fn [k] (and golden (contains? metrics k)))
        tclaim (fn [id k]
                 (if (and theory (some? (get theory k)))
                   (claim id (get theory k) :observed
                          [[:golden-report scenario-id :theory k]])
                   (claim id nil :not-measured [])))
        mech-reason (when theory
                      (some (fn [[_ mech]] (:reason mech))
                            (:mechanism-summary theory)))]
    {:outcome/actual
     {:outcome
      (if golden
        (claim :outcome/actual (:outcome g) :observed
               [[:golden-report scenario-id :outcome]])
        (claim :outcome/actual nil :not-measured []))
      :theory
      {:result/theory-status (tclaim :result/theory-status :status)
       :result/falsified? (tclaim :result/falsified? :falsified?)
       :result/mechanism-status (tclaim :result/mechanism-status :mechanism-status)
       :result/mechanism-reason
       (if (some? mech-reason)
         (claim :result/mechanism-reason mech-reason :observed
                [[:golden-report scenario-id :theory :mechanism-summary]])
         (claim :result/mechanism-reason nil :not-measured []))
       :result/display-label (tclaim :result/display-label :display-label)}
      :final-state-hash
      (if (and golden (:final-state-hash g))
        (claim :outcome/final-state-hash (:final-state-hash g) :observed
               [[:golden-report scenario-id :final-state-hash]])
        (claim :outcome/final-state-hash nil :not-measured []))
      :metrics
      (into {}
            (for [[k label] {:attack-attempts :outcome/attack-attempts
                             :attack-successes :outcome/attack-successes
                             :rejected-attacks :outcome/rejected-attacks}]
              [label
               (if (present? k)
                 (claim label (get metrics k) :observed
                        [[:golden-report scenario-id :metrics k]])
                 (claim label nil :not-measured []))]))}}))

(defn- outcome-section
  [trace golden scenario-id]
  (let [events (or (:events trace) [])]
    (merge
     {:outcome/events
      (if (seq events)
        (mapv (fn [{:keys [seq agent action time]}]
                {:event/seq seq :event/agent agent
                 :event/action action :event/time time})
              events)
        [])}
     (expected-outcome trace)
     (actual-outcome golden scenario-id))))

(defn- evidence-section
  "Direct references to the committed artifacts that support the frame."
  [scenario trace golden scenario-id]
  (cond-> []
    trace
    (conj {:evidence/artifact "trace"
           :evidence/ref (or (:scenario-id trace) scenario-id)
           :evidence/file (or (:_filename trace) "unknown.trace.json")
           :evidence/basis :observed})

    golden
    (conj {:evidence/artifact "golden-report"
           :evidence/ref (:trace-id golden)
           :evidence/digest (:final-state-hash golden)
           :evidence/basis :observed})

    scenario
    (conj {:evidence/artifact "coverage"
           :evidence/ref scenario-id
           :evidence/file (:path scenario)
           :evidence/basis :observed})))

(defn- guarantees-section
  "Guarantees are only asserted from recorded data. A missing measure renders
   as :not-measured, never as a passed check."
  [golden]
  (let [metrics (get-in golden [:metrics])
        inv-violations (when (and golden (contains? metrics :invariant-violations))
                         (get metrics :invariant-violations))]
    [{:claim/id :guarantee/invariant-violations
      :claim/value inv-violations
      :claim/basis (if inv-violations :observed :not-measured)
      :claim/source (if inv-violations
                      [[:golden-report :metrics :invariant-violations]]
                      [])}]))

(defn- provenance-section
  [artifacts scenario-id trace golden]
  (let [canon (data/canonical-summary (:summary artifacts))]
    {:provenance/scenario-id scenario-id
     :provenance/trace-file (or (and trace (:_filename trace)) "unavailable")
     :provenance/golden-report (when golden (:trace-id golden))
     :provenance/run-id (:run-id canon)
     :provenance/git-sha (:git-sha canon)}))

(defn build-frame
  "Canonical evidence-backed narrative frame for a scenario.
   Deterministic: same artifacts + scenario-id => identical frame."
  [artifacts scenario-id]
  (let [scenario (scenario-of artifacts scenario-id)
        trace    (trace-of artifacts scenario-id)
        golden   (golden-of artifacts scenario-id)
        title    (or (:title scenario)
                     (-> scenario-id
                         (str/replace #"^scenarios/" "")
                         (str/replace #"-" " ")
                         (str/upper-case)))]
    {:frame/schema frame-schema-version
     :frame/id (subs (data/sha256-hex (str scenario-id)) 0 16)
     :scenario/id scenario-id
     :scenario/title title
     :threat (threat-section scenario scenario-id)
     :response (response-section scenario trace scenario-id)
     :outcome (outcome-section trace golden scenario-id)
     :evidence (evidence-section scenario trace golden scenario-id)
     :guarantees (guarantees-section golden)
     :provenance (provenance-section artifacts scenario-id trace golden)}))
