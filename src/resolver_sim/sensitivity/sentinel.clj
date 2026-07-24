(ns resolver-sim.sensitivity.sentinel
  "Sensitivity sentinel: classification and enforcement layer for artifact
   disclosure boundaries.

   Implements SENSITIVITY_SENTINEL_SPEC_V1.

   Usage:
     (require '[resolver-sim.sensitivity.sentinel :as sentinel])

     ;; Classify an artifact
     (sentinel/classify artifact)

     ;; Check if disclosure is allowed
     (sentinel/disclosure-allowed? artifact :public-bundle)

     ;; Assert (throws on block)
     (sentinel/assert-export-allowed! artifact {:sink :ipfs})

     ;; Full sentinel report
     (sentinel/sentinel-report artifact :ipfs)"
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.time Instant]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const sentinel-version "sensitivity-sentinel.v1")

;; ── Sensitivity Levels (ordered: low → high) ────────────────────────────────

(def levels
  "Ordered vector of sensitivity levels (lowest to highest)."
  [:sensitivity/public
   :sensitivity/internal
   :sensitivity/private
   :sensitivity/embargoed
   :sensitivity/critical-private])

(def level-set (set levels))

(def level-order
  "Map from level keyword to position in the severity ordering."
  (into {} (map-indexed (fn [i l] [l i]) levels)))

(defn level-index
  [level]
  (get level-order level (count levels)))

(defn level>=
  "True if a is at least as sensitive as b (or higher)."
  [a b]
  (>= (level-index a) (level-index b)))

(defn level>
  "True if a is strictly more sensitive than b."
  [a b]
  (> (level-index a) (level-index b)))

;; ── Sink Classes ────────────────────────────────────────────────────────────

(def safe-sinks
  #{:local :sealed-log :private-encrypted-bundle :sealed-private-workspace})

(def low-risk-sinks
  #{:encrypted-bundle :git-commit})

(def medium-risk-sinks
  #{:public-bundle :public-ci-artifact})

(def high-risk-sinks
  #{:ipfs :nostr-public-relay :on-chain-registry})

(def public-sinks
  "Sinks that result in public disclosure."
  (set/union medium-risk-sinks high-risk-sinks))

(def all-sinks
  (set/union safe-sinks low-risk-sinks medium-risk-sinks high-risk-sinks))

;; ── Reason Codes ────────────────────────────────────────────────────────────

(def reason-code-set
  #{:contains-live-vulnerability
    :potential-live-vulnerability
    :contains-reproducible-exploit-path
    :contains-protocol-identifier
    :contains-counterparty-identifier
    :contains-private-researcher-identity
    :contains-unredacted-scenario
    :contains-claim-result
    :contains-attestation
    :contains-unpublished-evidence
    :contains-linkable-subject-hash
    :contains-public-sink-reference
    :contains-timing-metadata})

;; ── Disclosure Matrix ───────────────────────────────────────────────────────

(defn disclosure-allowed?
  "Check if an artifact at the given sensitivity level may be sent to a sink.

   Returns true if the movement is allowed by the disclosure matrix, false
   if blocked.

   Arguments:
     level — sensitivity level keyword (e.g. :sensitivity/private)
     sink  — sink keyword (e.g. :ipfs)

   When level is unknown, defaults to :sensitivity/critical-private (blocked).
   When sink is unknown, defaults to blocked (conservative)."
  [level sink]
  (let [effective-level (if (contains? level-set level) level :sensitivity/critical-private)]
    (cond
      ;; Safe sinks: all levels allowed
      (contains? safe-sinks sink) true
      ;; Low-risk sinks: public, internal allowed
      (contains? low-risk-sinks sink)
      (contains? #{:sensitivity/public :sensitivity/internal} effective-level)
      ;; Public sinks (medium + high risk): only public allowed
      (contains? public-sinks sink)
      (= :sensitivity/public effective-level)
      ;; Unknown sink: blocked
      :else false)))

;; ── Risk Severities ──────────────────────────────────────────────────────────

(def risk-severities
  "Ordered vector of risk severities (lowest to highest)."
  [:risk-severity/low
   :risk-severity/medium
   :risk-severity/high
   :risk-severity/critical])

(def risk-severity-set (set risk-severities))

(def risk-severity-order
  (into {} (map-indexed (fn [i s] [s i]) risk-severities)))

(defn risk-severity>=
  "True if a is at least as severe as b (or higher)."
  [a b]
  (>= (get risk-severity-order a 0) (get risk-severity-order b 0)))

;; ── Classification ───────────────────────────────────────────────────────────

;; ── Evidence-Backed Classification ───────────────────────────────────────────

(defn classify-from-findings
  "Classify an artifact based on safety findings (evidence) from scanning.
   
   Maps finding rule IDs to sensitivity levels and reason codes.
   Findings provide concrete evidence for the classification rather than
   just structural heuristics.
   
   Arguments:
     findings — vector of finding maps from sensitivity-findings
                Each finding has :rule/id and :rule/version
   
   Returns {:level <kw> :reasons [<kw> ...] :findings [<finding-ref> ...]}"
  [findings]
  (when (seq findings)
    (let [highest-level (apply max-key
                               level-index
                               (map (fn [f]
                                      (case (:rule/id f)
                                        :secret-scanner/private-key :sensitivity/private
                                        :secret-scanner/credential-assignment :sensitivity/private
                                        :secret-scanner/bearer-auth :sensitivity/internal
                                        :secret-scanner/jwt-token :sensitivity/internal
                                        :secret-scanner/github-token :sensitivity/internal
                                        :secret-scanner/npm-token :sensitivity/internal
                                        :sensitivity/critical-private))
                                    findings))
          reason-codes (vec (distinct
                             (mapcat (fn [f]
                                       (case (:rule/id f)
                                         :secret-scanner/private-key [:contains-live-vulnerability]
                                         :secret-scanner/credential-assignment [:contains-unpublished-evidence]
                                         :secret-scanner/bearer-auth [:contains-unpublished-evidence]
                                         :secret-scanner/jwt-token [:contains-protocol-identifier]
                                         :secret-scanner/github-token [:contains-linkable-subject-hash]
                                         :secret-scanner/npm-token [:contains-linkable-subject-hash]
                                         [:contains-unpublished-evidence]))
                                     findings)))]
      {:level highest-level
       :reasons reason-codes
       :findings (vec (map :finding/id findings))})))

(defn- evidence-backed-classification
  "Attempt to classify based on evidence findings attached to the artifact.
   Looks for :sensitivity/findings or :safety/findings keys.
   
   Arguments:
     artifact — artifact map that may contain findings under :sensitivity/findings
   
   Returns {:level <kw> :reasons [<kw> ...]} or nil if no findings present."
  [artifact]
  (when-let [findings (or (:sensitivity/findings artifact)
                          (:safety/findings artifact))]
    (classify-from-findings findings)))

;; ── Classification ───────────────────────────────────────────────────────────

(defn classify-structural
  "Classify an artifact using structural heuristics only.
   
   Returns a sensitivity level based on artifact shape, ignoring any
   declared :sensitivity/level metadata. Evidence-backed classification
   (via safety findings) is preferred when available; structural heuristics
   act as a conservative fallback when no evidence is present.
   
   See `classify` for the full list of rules."
  [artifact]
  (let [;; First try evidence-backed classification if findings are present
        evidence-classification (evidence-backed-classification artifact)
         ;; Evidence node: fail status
        result-status (get-in artifact [:result :status])
        failure-details (get-in artifact [:result :failure-details])
         ;; Attestation presence
        is-attestation (some? (:attestation/id artifact))
        claim-result (:attestation/claim-result artifact)
        claim-id (:attestation/claim-id artifact)
         ;; Claim result
        holds? (:holds? artifact)
         ;; Evidence presence
        has-attestations (seq (:attestations artifact))
         ;; Provenance
        provenance (:attestation/provenance artifact)
        scenario-id (or (:scenario-id provenance)
                        (:scenario-id artifact))
         ;; Scenario content (unredacted)
        scenario-events (seq (:events artifact))
        scenario-agents (seq (:agents artifact))
         ;; Subject
        subject-kind (:attestation/subject-kind artifact)
         ;; Bundle
        bundle-kind (:bundle/kind artifact)]
    (if evidence-classification
      (:level evidence-classification)
      (cond
         ;; Critical: evidence node with failure details (reproducible issues)
        (and (some? result-status) (seq failure-details))
        :sensitivity/private

         ;; Critical: attestation with claim-id (references a claim definition)
        (and is-attestation claim-id)
        :sensitivity/private

         ;; Critical: attestation on claim subject (references a claim result)
        (and is-attestation (= :claim subject-kind))
        :sensitivity/private

         ;; High: attestation (credible attributable statement)
        is-attestation :sensitivity/internal

         ;; High: evidence node with fail status
        (= :fail result-status) :sensitivity/internal

         ;; Medium: evidence with attestations attached
        has-attestations :sensitivity/internal

         ;; Medium: claim result with failed claim
        (false? holds?) :sensitivity/internal

         ;; Medium: passing claim result (no failure)
        (true? holds?) :sensitivity/internal

         ;; Medium: unredacted scenario content with events and agents
        (and scenario-id scenario-events scenario-agents)
        :sensitivity/private

         ;; Medium: artifact with scenario provenance
        scenario-id :sensitivity/internal

         ;; Bundle: classify as bundle
        bundle-kind :sensitivity/internal

         ;; Default: conservative
        :else :sensitivity/critical-private))))

(defn classify
  "Classify an artifact and return its sensitivity level.

   Examines artifact content and structure using structural heuristics,
   then applies any declared :sensitivity/level as a floor.

   Structural heuristics (lowest to highest):
   - Evidence nodes with :result :status :fail → at least :internal
   - Evidence nodes with failure details → at least :private
   - Attestations → at least :internal
   - Attestations with claim results → at least :private
   - Claim results with :holds? false → at least :internal
   - Claim results with :holds? true (passing) → at least :internal
   - Artifacts with :scenario-id + :events + :agents (unredacted scenario) → at least :private
   - Artifacts with provenance + scenario-id → at least :internal
   - Unredacted scenario content → at least :private
   - Unknown structure → :critical-private (conservative default)

   Declared metadata (respected as floor):
   - If artifact carries :sensitivity/level, the result is at least that level
   - If artifact carries :sensitivity/risk-meta with :risk-severity :critical,
     override mode escalates to :multi-party-approval

   Arguments:
     artifact — any artifact map (attestation, evidence node, claim result, bundle)

   Returns a sensitivity level keyword."
  [artifact]
  (let [structural (classify-structural artifact)
        declared (:sensitivity/level artifact)]
    (if (and declared (contains? level-set declared))
      (if (level>= structural declared) structural declared)
      structural)))

;; ── Sentinel Report ─────────────────────────────────────────────────────────

(defn policy-hash
  "Compute a deterministic hash of the sentinel policy configuration.
   Covers version, level definitions, sink set, and disclosure rules.
   This is a public pure function — no side effects."
  []
  (hc/hash-with-intent {:hash/intent :evidence-record}
                       {:version sentinel-version
                        :levels levels
                        :sinks (vec (sort all-sinks))
                        :disclosure-rules "level>=:public required for public-sinks"}))

(def ^{:deprecated "0.1.0" :private true} compute-policy-hash
  "Deprecated alias; use policy-hash instead."
  policy-hash)

(defn default-reasons
  [level]
  (case level
    :sensitivity/public []
    :sensitivity/internal [:contains-unpublished-evidence]
    :sensitivity/private [:contains-unpublished-evidence :contains-attestation]
    :sensitivity/embargoed [:contains-unpublished-evidence :contains-attestation
                            :contains-timing-metadata]
    :sensitivity/critical-private [:contains-unpublished-evidence :contains-attestation
                                   :contains-reproducible-exploit-path]
    [:contains-unpublished-evidence]))

(defn effective-override-mode
  "Determine the override mode for a given level and risk metadata.

   Escalates to :multi-party-approval when:
   - Level is :sensitivity/critical-private or higher, OR
   - Risk meta contains :risk-severity :critical"
  [level risk-meta]
  (cond
    (level>= level :sensitivity/critical-private) :multi-party-approval
    (and risk-meta
         (:risk-severity risk-meta)
         (risk-severity>= (:risk-severity risk-meta) :risk-severity/critical))
    :multi-party-approval
    :else :single))

(defn- declared-reasons
  "Returns extra reason codes declared in the artifact's :sensitivity/risk-meta."
  [artifact]
  (vec (get-in artifact [:sensitivity/risk-meta :reason-codes] [])))

(defn- finding-reasons
  "Extract reason codes from safety findings attached to the artifact.
   
   Arguments:
     artifact — artifact map with :sensitivity/findings or :safety/findings
   
   Returns vector of reason code keywords, or empty vector if no findings."
  [artifact]
  (let [findings (or (:sensitivity/findings artifact)
                     (:safety/findings artifact))]
    (when (seq findings)
      (vec (distinct
            (mapcat (fn [f]
                      (case (:rule/id f)
                        :secret-scanner/private-key [:contains-live-vulnerability]
                        :secret-scanner/credential-assignment [:contains-unpublished-evidence]
                        :secret-scanner/bearer-auth [:contains-unpublished-evidence]
                        :secret-scanner/jwt-token [:contains-protocol-identifier]
                        :secret-scanner/github-token [:contains-linkable-subject-hash]
                        :secret-scanner/npm-token [:contains-linkable-subject-hash]
                        [:contains-unpublished-evidence]))
                    findings))))))

(defn- extract-risk-meta
  "Extract the risk metadata map from an artifact, if present."
  [artifact]
  (when-let [rm (:sensitivity/risk-meta artifact)]
    (let [allowed-keys #{:value-at-risk :risk-severity :risk-vector}]
      (select-keys rm allowed-keys))))

(defn sentinel-report
  "Produce a full sentinel report for an artifact and requested sink.
   
   Arguments:
     artifact — artifact map to classify
     sink     — requested sink keyword
   
   Returns the sentinel report map including:
   - :sentinel/report-hash over structural fields (deterministic)
   - :sentinel/declared-level if the artifact carried explicit metadata
   - :sentinel/risk-meta if the artifact carried risk annotation
   - Override mode may escalate to :multi-party-approval for :critical risk"
  [artifact sink]
  (let [level (classify artifact)
        allowed? (disclosure-allowed? level sink)
        decision (if allowed? :allowed :blocked)
        structural-level (classify-structural artifact)
        declared-level (:sensitivity/level artifact)
        risk-meta (extract-risk-meta artifact)
        reasons (vec (distinct (concat (default-reasons level)
                                       (declared-reasons artifact)
                                       (finding-reasons artifact))))
        allowed-sinks (vec (sort (filter #(disclosure-allowed? level %) all-sinks)))
        input-kind (cond (:attestation/id artifact) :attestation-record
                         (:node-hash artifact) :evidence-node
                         (:holds? artifact) :claim-result
                         (:bundle/kind artifact) :bundle
                         :else :unknown)
        input-hash (or (:attestation/id artifact)
                       (:node-hash artifact)
                       (:bundle/root-hash artifact)
                       (hc/hash-with-intent {:hash/intent :evidence-record} artifact))
        base-report {:sentinel/version sentinel-version
                     :sentinel/policy-hash (compute-policy-hash)
                     :sentinel/evaluated-at (str (Instant/now))
                     :sentinel/input-kind input-kind
                     :sentinel/input-hash input-hash
                     :sentinel/requested-sink sink
                     :sentinel/decision decision
                     :sentinel/level level
                     :sentinel/structural-level structural-level
                     :sentinel/reasons reasons
                     :sentinel/allowed-sinks allowed-sinks
                     :sentinel/redaction-required? (level>= level :sensitivity/private)
                     :sentinel/override-required?
                     {:required? (and (not allowed?)
                                      (level>= level :sensitivity/private))
                      :mode (effective-override-mode level risk-meta)}}
        base-report (cond-> base-report
                       declared-level (assoc :sentinel/declared-level declared-level)
                       risk-meta (assoc :sentinel/risk-meta risk-meta
                                        :sentinel/risk-meta-hash
                                        (hc/hash-with-intent
                                         {:hash/intent :evidence-record}
                                         risk-meta))
                       (seq (:sensitivity/findings artifact))
                      (assoc :sentinel/evidence-findings (:sensitivity/findings artifact)))
        report-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                         (dissoc base-report
                                                 :sentinel/report-hash
                                                 :sentinel/evaluated-at))]
    (assoc base-report :sentinel/report-hash report-hash)))

;; ── Assertion Functions ──────────────────────────────────────────────────────

(defn assert-disclosure-allowed!
  "Assert that an artifact may be sent to a sink.

   Classifies the artifact, checks the sink against the disclosure matrix,
   and returns the sentinel report if allowed. Throws if blocked.

   Arguments:
     artifact — artifact map
     opts     — map with :sink key (required)

   Returns the sentinel report on success.

   Throws ex-info with :sentinel/blocked on failure."
  [artifact & [{:keys [sink]}]]
  (let [report (sentinel-report artifact sink)]
    (if (= :allowed (:sentinel/decision report))
      report
      (throw (ex-info (str "Disclosure blocked by sensitivity sentinel: "
                           (:sentinel/level report) " → " sink)
                      {:sentinel/blocked true
                       :sentinel/report report})))))

(defn assert-export-allowed!
  "Assert that an artifact may be exported to a sink.
   Delegates to assert-disclosure-allowed!."
  [artifact {:keys [sink]}]
  (assert-disclosure-allowed! artifact {:sink sink}))

(defn assert-publish-allowed!
  "Assert that an evidence node may be published to a sink."
  [evidence-node {:keys [sink]}]
  (assert-disclosure-allowed! evidence-node {:sink sink}))

(defn assert-relay-allowed!
  "Assert that a sealed event may be relayed to a sink."
  [sealed-event {:keys [sink]}]
  (assert-disclosure-allowed! sealed-event {:sink sink}))

(defn assert-attestation-allowed!
  "Assert that an attestation may be sent to a sink."
  [attestation {:keys [sink]}]
  (assert-disclosure-allowed! attestation {:sink sink}))

;; ── Override Enforcement ─────────────────────────────────────────────────────

(defn check-override-requirements!
  "Check that override requirements are satisfied for a given effective
   sensitivity and risk metadata.

   When the override mode is :multi-party-approval, the approvals map
   MUST contain at least two distinct approved-by entries. When mode
   is :single, at least one entry is required.

   Arguments:
     level     — effective sensitivity level
     risk-meta — risk metadata map (or nil)
     approvals — vector of {:approved-by <id> :approved-at <str> :reason <str>}

   Returns {:override-required? <bool>
            :mode <:single | :multi-party-approval>
            :satisfied? <bool>
            :required-count <int>
            :actual-count <int>
            :reasons [<str> ...]}

   Throws ex-info with :sensitivity/override-failed when not satisfied,
   to fail closed."
  [level risk-meta approvals]
  (let [mode (effective-override-mode level risk-meta)
        required? (and (not (disclosure-allowed? level :public-bundle))
                       (level>= level :sensitivity/private))
        approvals (vec approvals)
        actual-count (count (distinct (map :approved-by approvals)))
        required-count (if (= :multi-party-approval mode) 2 1)
        satisfied? (or (not required?) (>= actual-count required-count))]
    (if satisfied?
      {:override-required? required?
       :mode mode
       :satisfied? true
       :required-count required-count
       :actual-count actual-count
       :reasons []}
      (let [reasons [(str "Override required: mode=" mode
                          " required=" required-count
                          " actual=" actual-count)]]
        (throw (ex-info (str "Sensitivity override requirements not satisfied: "
                             "mode=" mode " requires " required-count
                             " approvals, got " actual-count)
                        {:sensitivity/override-failed true
                         :sensitivity/level level
                         :sensitivity/mode mode
                         :sensitivity/required-count required-count
                         :sensitivity/actual-count actual-count
                         :sensitivity/reasons reasons
                         :sensitivity/approvals approvals}))))))
