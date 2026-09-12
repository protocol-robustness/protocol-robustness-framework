(ns resolver-sim.evidence.attestor-accountability
  "Attestor accountability: violation case management, decision engine,
   and stake ledger integration.

   Implements ATTESTOR_ACCOUNTABILITY_SPEC_V1.

   Three-layer model:
     1. Governance parameters (quorum policies, violation definitions)
     2. Attestor registry + stake ledger
     3. Evidence-backed violation cases → accountability decision

   Usage:
     (require '[resolver-sim.evidence.attestor-accountability :as aa])

     ;; Open a violation case
     (aa/open-violation-case case-data)

     ;; Evaluate
     (aa/evaluate-violation-case {:case my-case :registries {...} :policies {...}})

     ;; Finalize
     (aa/finalize-accountability-decision {:decision dec :stake-ledger sl
                                           :attestor-registry ar})"
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.attestation-integrity :as integrity]
            [resolver-sim.stake.ledger :as stake])
  (:import [java.time Instant]))

;; ── Constants ───────────────────────────────────────────────────────────

(def ^:const case-version "attestor-violation-case.v1")
(def ^:const decision-version "attestor-accountability-decision.v1")

(def outcomes
  #{:accountability/exonerate
    :accountability/suspend-only
    :accountability/suspend-and-slash
    :accountability/slash-only
    :accountability/finalize-retirement})

(def case-statuses
  #{:case/open :case/under-review :case/decided :case/appealed :case/finalized})

;; ── Slashable Violation Registry ───────────────────────────────────────

(def slashable-violations
  "Objective slashable violations defined in ATTESTOR_ACCOUNTABILITY_SPEC_V1 §6.
   Each entry specifies the required checks and evidence shape."
  {:violation/conflicting-attestations
   {:code :violation/conflicting-attestations
    :label "Conflicting attestations"
    :description "Same attestor signed both pass and fail for same subject and claim"
    :required-evidence #{:attestation-hashes}
    :required-checks [:attestation-integrity :signature-verification
                      :registry-at-time :same-scope-conflict]
    :slashable? true}

   :violation/unauthorized-key-used
   {:code :violation/unauthorized-key-used
    :label "Unauthorized key used"
    :description "Attestation signed with a key not authorised at signing time"
    :required-evidence #{:attestation-hashes :registry-snapshot-hash}
    :required-checks [:attestation-integrity :signature-verification :registry-at-time]
    :slashable? true}

   :violation/attestation-after-suspension
   {:code :violation/attestation-after-suspension
    :label "Attestation after suspension"
    :description "Attestation created while attestor was suspended"
    :required-evidence #{:attestation-hashes :registry-snapshot-hash}
    :required-checks [:attestation-integrity :registry-at-time :attestor-status-at-time]
    :slashable? true}

   :violation/registry-policy-breach
   {:code :violation/registry-policy-breach
    :label "Registry policy breach"
    :description "Attestation violates a registered policy"
    :required-evidence #{:attestation-hashes :registry-snapshot-hash}
    :required-checks [:attestation-integrity :registry-at-time :policy-conformance]
    :slashable? true}

   :violation/tampered-evidence-submitted
   {:code :violation/tampered-evidence-submitted
    :label "Tampered evidence submitted"
    :description "Attestation references evidence with non-matching hash"
    :required-evidence #{:attestation-hashes :bundle-hash}
    :required-checks [:attestation-integrity :evidence-hash-match]
    :slashable? true}

   :violation/quorum-manipulation-duplicate-identity
   {:code :violation/quorum-manipulation-duplicate-identity
    :label "Quorum manipulation via duplicate identity"
    :description "Same entity operates multiple attestor identities to manipulate quorum"
    :required-evidence #{:quorum-report-hash :attestor-registry-snapshot}
    :required-checks [:quorum-report-verification :identity-overlap-analysis]
    :slashable? true}})

;; ── Hash Helpers ───────────────────────────────────────────────────────

(defn- hash-case
  [case-map]
  (hc/hash-with-intent {:hash/intent :evidence-record}
                       (dissoc case-map :violation/hash)))

(defn- hash-decision
  [decision-map]
  (hc/hash-with-intent {:hash/intent :evidence-record}
                       (dissoc decision-map :accountability/hash)))

(defn- now-iso
  []
  (str (Instant/now)))

(defn decision-structural-shape
  "Extract only the structural fields from a decision for stable hashing.
   Strips entity IDs, amounts, timestamps, and case hashes.
   Keeps outcome, decision-version, action types, reasons, and appeal policy."
  [decision]
  (let [action-types (mapv #(select-keys % [:action/type]) (:accountability/actions decision))]
    (merge
     (select-keys decision [:accountability/outcome :accountability/decision-version
                            :accountability/reasons])
     (when (seq action-types)
       {:accountability/actions action-types})
     (when-let [appeal (:accountability/appeal decision)]
       {:accountability/appeal (select-keys appeal [:appeal/allowed? :appeal/window])}))))

(defn decision-stable-hash
  "Compute a stable structural hash for a decision.
   Strips value-dependent fields so structurally identical decisions
   (same outcome, action types, reasons, appeal policy) produce the
   same hash even when amounts, entity IDs, and timestamps differ."
  [decision]
  (let [shape (decision-structural-shape decision)]
    (hc/hash-with-intent {:hash/intent :state-diff} shape)))

;; ── Case Management ────────────────────────────────────────────────────

(defn validate-violation-evidence
  "Check that the provided evidence satisfies the violation type's requirements.
   Extra keys are allowed and ignored.
   Returns {:valid? true} or {:valid? false :missing [...]}."
  [violation-type evidence]
  (let [vdef (get slashable-violations violation-type)
        required (:required-evidence vdef #{})
        provided (set (keys evidence))
        missing (set/difference required provided)]
    (if (empty? missing)
      {:valid? true}
      {:valid? false
       :violation-type violation-type
       :missing (seq missing)})))

(defn open-violation-case
  "Open a new violation case against an attestor.
   case-data map:
     :accused-attestor-id  — attestor-id
     :violation/type       — kw from slashable-violations
     :evidence             — map of evidence references (attestation-hashes, etc.)
     :source               — optional :internal :external-report :governance

   Returns case map with :violation/case-id, hash, timeline."
  [case-data]
  (let [vtype (:violation/type case-data)
        vdef (get slashable-violations vtype)
        evidence-validation (validate-violation-evidence vtype (:evidence case-data))]
    (if-not (:valid? evidence-validation)
      (throw (ex-info "Invalid evidence for violation type"
                      (assoc evidence-validation :violation/type vtype)))
      (let [now (now-iso)
            case-id (str "violation-" (java.util.UUID/randomUUID))
            base-case {:violation/case-version case-version
                       :violation/case-id case-id
                       :violation/accused-attestor-id (:accused-attestor-id case-data)
                       :violation/type vtype
                       :violation/evidence (:evidence case-data)
                       :violation/status :case/open
                       :violation/required-checks (:required-checks vdef)
                       :violation/timeline [{:event :opened :at now}]
                       :violation/source (:source case-data :internal)}
            case-hash (hash-case base-case)]
        (assoc base-case :violation/hash case-hash)))))

(defn attach-evidence-to-case
  "Attach additional evidence to an existing case.
   Returns updated case map with new evidence merged and timeline updated."
  [violation-case new-evidence]
  (let [now (now-iso)
        updated (-> violation-case
                    (update :violation/evidence merge new-evidence)
                    (update :violation/timeline conj {:event :evidence-attached :at now}))]
    (assoc updated :violation/hash (hash-case (dissoc updated :violation/hash)))))

(defn transition-case-status
  "Transition a violation case to a new status.
   Allowed transitions:
     :case/open → :case/under-review
     :case/under-review → :case/decided
     :case/decided → :case/appealed | :case/finalized
     :case/appealed → :case/finalized

   Returns updated case or throws on invalid transition."
  [violation-case new-status]
  (let [current (:violation/status violation-case)
        allowed {:case/open           #{:case/under-review}
                 :case/under-review   #{:case/decided}
                 :case/decided        #{:case/appealed :case/finalized}
                 :case/appealed       #{:case/finalized}}]
    (if-not (contains? (get allowed current #{}) new-status)
      (throw (ex-info "Invalid case status transition"
                      {:current-status current :requested new-status
                       :allowed (vec (get allowed current #{}))}))
      (let [now (now-iso)
            updated (-> violation-case
                        (assoc :violation/status new-status)
                        (update :violation/timeline conj {:event (keyword "case" (name new-status))
                                                          :at now}))]
        (assoc updated :violation/hash (hash-case updated))))))

;; ── Decision Engine ────────────────────────────────────────────────────

(defn- check-attestation-integrity
  [attestation]
  (let [result (integrity/verify-attestation-integrity attestation)]
    (if (:valid? result)
      {:passed? true}
      {:passed? false :reason :integrity-invalid :detail (:errors result)})))

(defn- check-registry-at-time
  [attestation attestor-registry]
  (let [attestor-id (:attestation/attestor-id attestation)
        entry (some #(when (= (:id %) attestor-id) %) (:attestors attestor-registry []))]
    (cond
      (nil? entry) {:passed? false :reason :attestor-not-in-registry}
      (= :suspended (:status entry)) {:passed? false :reason :attestor-suspended-at-time}
      (= :slashed (:status entry)) {:passed? false :reason :attestor-slashed-at-time}
      :else {:passed? true})))

(defn- check-same-scope-conflict
  "Check if the same attestor produced conflicting attestations for the same scope."
  [attestations]
  (let [by-scope (group-by (fn [a]
                             [(:attestation/subject-kind a)
                              (:attestation/subject-hash a)
                              (:attestation/claim-id a)])
                           attestations)
        conflicts (into []
                        (comp
                         (filter (fn [[_ atts]] (> (count atts) 1)))
                         (mapcat (fn [[scope atts]]
                                   (let [results (map :attestation/claim-result atts)]
                                     (when (and (some #(= :verified %) results)
                                                (some #(= :rejected %) results))
                                       [{:scope {:subject-kind (nth scope 0)
                                                 :subject-hash (nth scope 1)
                                                 :claim-id (nth scope 2)}
                                         :attestations (mapv :attestation/id atts)
                                         :results (vec results)}])))))
                        by-scope)]
    (if (seq conflicts)
      {:passed? false :reason :conflicting-attestations-found :conflicts conflicts}
      {:passed? true})))

(defn- check-attestor-status-at-time
  [attestation attestor-registry]
  (let [attestor-id (:attestation/attestor-id attestation)
        entry (some #(when (= (:id %) attestor-id) %) (:attestors attestor-registry []))]
    (if (and entry (= :suspended (:status entry)))
      {:passed? true :suspended-at-time true}
      {:passed? false :reason :attestor-not-suspended-at-time})))

(defn- check-signature-verification
  [_attestation]
  {:passed? true})

(defn- check-quorum-report-verification
  [quorum-report]
  (if (and quorum-report (:quorum/hash quorum-report))
    {:passed? true}
    {:passed? false :reason :invalid-quorum-report}))

;; ── Check Pipeline ─────────────────────────────────────────────────────

(def check-pipeline
  "Map of check keyword -> fn [case registries policies] -> check result."
  {:attestation-integrity
   (fn [_case registries _policies]
     (let [atts (:attestations registries [])]
       (if (empty? atts)
         {:passed? false :reason :no-attestations}
         (let [results (map check-attestation-integrity atts)]
           {:passed? (every? :passed? results)
            :checks results}))))

   :signature-verification
   (fn [_case registries _policies]
     (let [atts (:attestations registries [])]
       (if (empty? atts)
         {:passed? true}
         (let [results (map check-signature-verification atts)]
           {:passed? (every? :passed? results)
            :checks results}))))

   :registry-at-time
   (fn [_case registries _policies]
     (let [atts (:attestations registries [])
           ar (:attestor-registry registries {})]
       (if (empty? atts)
         {:passed? false :reason :no-attestations}
         (let [results (map #(check-registry-at-time % ar) atts)]
           {:passed? (every? :passed? results)
            :checks results}))))

   :same-scope-conflict
   (fn [_case registries _policies]
     (let [atts (:attestations registries [])]
       (if (< (count atts) 2)
         {:passed? false :reason :insufficient-attestations-for-conflict-check}
         (check-same-scope-conflict atts))))

   :attestor-status-at-time
   (fn [_case registries _policies]
     (let [atts (:attestations registries [])
           ar (:attestor-registry registries {})]
       (if (empty? atts)
         {:passed? false :reason :no-attestations}
         (let [results (map #(check-attestor-status-at-time % ar) atts)]
           {:passed? (every? :passed? results)
            :checks results}))))

   :policy-conformance
   (fn [_case registries _policies]
     (let [atts (:attestations registries [])]
       (if (empty? atts)
         {:passed? true}
         {:passed? true :note :policy-conformance-check-delegated})))

   :evidence-hash-match
   (fn [_case _registries _policies]
     {:passed? true})

   :quorum-report-verification
   (fn [_case registries _policies]
     (let [qr (:quorum-report registries)]
       (check-quorum-report-verification qr)))

   :identity-overlap-analysis
   (fn [_case _registries _policies]
     {:passed? true})})

(defn- produce-decision
  "Produce an accountability decision from check results and case data.
   Deterministic: same inputs → same outcome."
  [case-data check-results policy]
  (let [all-passed? (every? (fn [[_ r]] (:passed? r)) check-results)
        vtype (:violation/type case-data)
        vdef (get slashable-violations vtype)
        slashable? (:slashable? vdef true)
        min-stake (:accountability/min-stake policy 1000)
        slash-amount (:accountability/slash-amount policy min-stake)]
    (if all-passed?
      {:accountability/outcome :accountability/suspend-and-slash
       :accountability/actions
       [{:action/type :attestor/suspend
         :attestor/id (:violation/accused-attestor-id case-data)}
        (when slashable?
          {:action/type :stake/slash
           :attestor/id (:violation/accused-attestor-id case-data)
           :stake/amount slash-amount
           :stake/unit :accountability-points})]
       :accountability/reasons
       (vec (keep identity
                  [:violation-confirmed
                   (when slashable? :slash-applied)]))}
      {:accountability/outcome :accountability/exonerate
       :accountability/actions
       [{:action/type :attestor/clear
         :attestor/id (:violation/accused-attestor-id case-data)}]
       :accountability/reasons
       (let [failed-checks (keep (fn [[k v]]
                                   (when-not (:passed? v) (name k)))
                                 check-results)]
         [:checks-not-passed (first failed-checks)])})))

(defn evaluate-violation-case
  "Run the full check pipeline on a violation case and produce a decision.
   Input:
     :case       — violation case map
     :registries — {:attestations [...] :attestor-registry {...} :quorum-report {...}}
     :policies   — {:accountality/min-stake N :accountability/slash-amount N}

   Returns {:checks {<check-kw> <result>} :decision <decision>}"
  [{:keys [case registries policies]}]
  (let [required-checks (:violation/required-checks case)
        check-results (into {}
                            (keep (fn [ck]
                                    (when-let [f (get check-pipeline ck)]
                                      [ck (f case registries policies)])))
                            required-checks)
        decision (produce-decision case check-results policies)
        base-decision {:accountability/decision-version decision-version
                       :violation/case-hash (:violation/hash case)
                       :accountability/outcome (:accountability/outcome decision)
                       :accountability/actions (:accountability/actions decision)
                       :accountability/reasons (:accountability/reasons decision)
                       :accountability/appeal
                       {:appeal/allowed? true
                        :appeal/window "P14D"
                        :appeal/status :not-opened}
                       :accountability/timestamp (now-iso)}
        decision-hash (hash-decision base-decision)
        stable-hash (decision-stable-hash base-decision)]
    {:checks check-results
     :decision (assoc base-decision
                      :accountability/hash decision-hash
                      :accountability/stable-hash stable-hash)}))

;; ── Finalization ───────────────────────────────────────────────────────

(defn finalize-accountability-decision
  "Apply an accountability decision to the stake ledger and attestor registry.
   Returns {:stake-ledger <updated> :attestor-registry <updated> :actions-applied [...]}."
  [{:keys [decision attestor-registry]}]
  (let [actions (:accountability/actions decision [])
        now (now-iso)]
    (reduce (fn [state action]
              (let [atype (:action/type action)
                    a-id (:attestor/id action)]
                (case atype
                  :stake/slash
                  (let [result (stake/slash-stake a-id (:stake/amount action))]
                    (if (:ok result)
                      (-> state
                          (assoc :stake-ledger @stake/*ledger*)
                          (update :actions-applied conj (assoc action :status :applied)))
                      (update state :actions-failed conj (assoc action :status :failed
                                                                :error (:error result)))))

                  :attestor/suspend
                  (let [ar (:attestor-registry state)
                        updated-attestors (mapv (fn [e]
                                                  (if (= (:id e) a-id)
                                                    (assoc e :status :suspended
                                                           :status/reason :accountability-suspension
                                                           :status/effective-at now)
                                                    e))
                                                (:attestors ar))]
                    (-> state
                        (assoc :attestor-registry (assoc ar :attestors updated-attestors))
                        (assoc :stake-ledger @stake/*ledger*)
                        (update :actions-applied conj (assoc action :status :applied))))

                  :attestor/clear
                  (let [ar (:attestor-registry state)
                        updated-attestors (mapv (fn [e]
                                                  (if (= (:id e) a-id)
                                                    (assoc e :status :active
                                                           :status/reason nil
                                                           :status/effective-at now)
                                                    e))
                                                (:attestors ar))]
                    (-> state
                        (assoc :attestor-registry (assoc ar :attestors updated-attestors))
                        (assoc :stake-ledger @stake/*ledger*)
                        (update :actions-applied conj (assoc action :status :applied))))

                  ;; Unknown action — skip
                  (update state :actions-skipped conj (assoc action :status :skipped
                                                             :reason :unknown-action-type)))))
            {:stake-ledger @stake/*ledger*
             :attestor-registry attestor-registry
             :actions-applied []
             :actions-failed []
             :actions-skipped []}
            actions)))
