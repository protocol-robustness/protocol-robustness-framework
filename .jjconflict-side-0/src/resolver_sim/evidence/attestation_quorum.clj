(ns resolver-sim.evidence.attestation-quorum
  "Multi-attestor quorum verification: determines whether enough independent
   attestors agree on the same claim about the same subject under a declared
   quorum policy.

   Implements ATTESTATION_QUORUM_SPEC_V1.

   Usage:
     (require '[resolver-sim.evidence.attestation-quorum :as aq])

     (aq/verify-quorum
       {:policy quorum-policy
        :attestations [att-1 att-2]
        :attestor-registry registry-map})

     (aq/group-attestations-by-quorum-scope attestations)
     (aq/explain-quorum-report report)"
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.attestation-integrity :as integrity]))

;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:const report-version "attestation-quorum-report.v1")

(def outcomes
  #{:quorum/confirmed :quorum/rejected :quorum/conflicted
    :quorum/insufficient-quorum :quorum/invalid-input})

;; ── Policy Helpers ───────────────────────────────────────────────────────────

(defn- policy-hash
  [policy]
  (hc/hash-with-intent {:hash/intent :evidence-record}
                       (dissoc policy :policy/hash)))

(defn- parse-k-of-n
  [rule]
  (let [k (:k rule)
        n (:n rule)]
    (when (and (pos-int? k) (pos-int? n))
      {:k k :n n})))

;; ── Scope Matching ───────────────────────────────────────────────────────────

(defn- scope-matches?
  "Check if an attestation matches a quorum scope exactly."
  [attestation scope]
  (and (= (:subject-kind scope) (:attestation/subject-kind attestation))
       (= (:subject-hash scope) (:attestation/subject-hash attestation))
       (or (nil? (:claim-definition-hash scope))
           (= (:claim-definition-hash scope) (:attestation/claim-id attestation)))))

;; ── Attestation Eligibility ──────────────────────────────────────────────────

(defn- check-integrity
  [attestation]
  (let [result (integrity/verify-attestation-integrity attestation)]
    (if (:valid? result)
      {:eligible? true}
      {:eligible? false :reason :integrity-invalid :detail (:errors result)})))

(defn- check-signature
  [attestation]
  (if (some? (:attestation/signature attestation))
    {:eligible? true}
    {:eligible? true :note :unsigned}
    ;; V1: signature presence noted but does not exclude.
    ;; Full signature verification requires verify-fn which is external.
    ))

(defn- check-attestor-active
  [attestation attestor-registry]
  (let [attestor-id (:attestation/attestor-id attestation)
        att-reg (:attestors attestor-registry)
        entry (some #(when (= (:id %) attestor-id) %) att-reg)]
    (cond
      (nil? entry) {:eligible? false :reason :attestor-not-in-registry}
      (not= :active (:status entry)) {:eligible? false :reason :attestor-not-active}
      :else {:eligible? true})))

(defn- check-key-authorized
  [attestation attestor-registry]
  (let [attestor-id (:attestation/attestor-id attestation)
        signing-key-id (:attestation/signing-key-id attestation)
        att-reg (:attestors attestor-registry)
        entry (some #(when (= (:id %) attestor-id) %) att-reg)]
    (if (nil? signing-key-id)
      {:eligible? true :note :unsigned}
      (let [key-history (:key-history entry [])
            authorized? (some (fn [kh]
                                (and (= (:key-id kh) signing-key-id)
                                     (= :active (:status kh))))
                              key-history)]
        (if authorized?
          {:eligible? true}
          {:eligible? false :reason :key-not-authorized})))))

(defn- check-subject-matches
  [attestation scope]
  (if (scope-matches? attestation scope)
    {:eligible? true}
    {:eligible? false :reason :subject-mismatch}))

(defn- check-claim-matches
  [attestation scope]
  (let [scope-cdh (:claim-definition-hash scope)]
    (if (nil? scope-cdh)
      {:eligible? true}
      (let [att-claim-id (:attestation/claim-id attestation)]
        (if (= scope-cdh att-claim-id)
          {:eligible? true}
          {:eligible? false :reason :claim-mismatch})))))

;; ── Independence ─────────────────────────────────────────────────────────────

(defn- attestor-identity
  "Extract the identity fields from an attestation for independence checking.
   V1 uses attestor-id as the primary key, with optional operator and root."
  [attestation]
  {:attestor/id (:attestation/attestor-id attestation)
   :attestor/hash (:attestation/id attestation)
   :claim/result (:attestation/claim-result attestation)})

(defn- check-independence
  "Check independence of attestations under a policy.
   Returns a map of {:eligible [atts] :excluded [{:attestation/hash :reason}] :conflicts [...]}"
  [attestations policy]
  (let [independence (:quorum/independence policy)
        distinct-attestor? (:distinct-attestor-id? independence true)
        exclude-delegates? (:exclude-delegates-of-same-root? independence false)]
    (loop [remaining (vec attestations)
           seen #{}
           eligible []
           excluded []
           conflicts []]
      (if (empty? remaining)
        {:eligible eligible :excluded excluded :conflicts conflicts}
        (let [att (first remaining)
              id (:attestation/attestor-id att)
              hash (:attestation/id att)
              claim-result (:attestation/claim-result att)]
          (cond
            ;; Same attestor already seen — check for conflict
            (and distinct-attestor? (contains? seen id))
            (let [existing-eligible (filter #(= (:attestation/attestor-id %) id) eligible)
                  existing-result (when (seq existing-eligible)
                                    (:attestation/claim-result (first existing-eligible)))]
              (if (and existing-result (not= existing-result claim-result))
                ;; Same attestor, different result → conflict
                (recur (rest remaining) seen
                       (remove #(= (:attestation/attestor-id %) id) eligible)
                       (conj excluded {:attestation/hash hash :reason :conflicting-result})
                       (conj conflicts {:type :same-attestor-duplicate-vote
                                        :attestor/id id
                                        :attestations [(:attestation/hash (first existing-eligible)) hash]}))
                ;; Same attestor, same result → exclude as duplicate
                (recur (rest remaining) seen eligible
                       (conj excluded {:attestation/hash hash :reason :duplicate-attestor})
                       conflicts)))
            ;; New independent attestor
            :else
            (recur (rest remaining) (conj seen id)
                   (conj eligible att) excluded conflicts)))))))

;; ── Outcome Determination ────────────────────────────────────────────────────

(defn- determine-outcome
  "Determine quorum outcome from eligible attestations and conflict info."
  [eligible-attestations conflicts rule]
  (let [k (:k rule 2)
        pass-count (count (filter #(= :verified (:attestation/claim-result %)) eligible-attestations))
        fail-count (count (filter #(= :rejected (:attestation/claim-result %)) eligible-attestations))
        other-count (- (count eligible-attestations) pass-count fail-count)]
    (cond
      (seq conflicts) {:outcome :quorum/conflicted
                       :pass-count pass-count :fail-count fail-count
                       :other-count other-count
                       :k k}
      (>= pass-count k) {:outcome :quorum/confirmed
                         :pass-count pass-count :fail-count fail-count
                         :other-count other-count :k k}
      (>= fail-count k) {:outcome :quorum/rejected
                         :pass-count pass-count :fail-count fail-count
                         :other-count other-count :k k}
      (zero? (count eligible-attestations)) {:outcome :quorum/invalid-input
                                             :pass-count 0 :fail-count 0
                                             :other-count 0 :k k}
      :else {:outcome :quorum/insufficient-quorum
             :pass-count pass-count :fail-count fail-count
             :other-count other-count :k k})))

;; ── Core Verification ───────────────────────────────────────────────────────

(defn verify-quorum
  "Verify a quorum of attestations under a policy.

   Arguments (map):
     :policy            — quorum policy map
     :attestations      — vector of attestation records
     :attestor-registry — attestor registry map with :attestors entries

   Returns a quorum report map."
  [{:keys [policy attestations attestor-registry]
    :or {attestations []}}]
  (let [scope (:quorum/scope policy)
        rule (:quorum/rule policy)
        k-of-n (parse-k-of-n rule)
        _ (when (nil? k-of-n)
            (throw (ex-info "Invalid k-of-n rule" {:rule rule})))

        ;; Scope check
        scope-matched (filter #(scope-matches? % scope) attestations)
        scope-mismatched (remove #(scope-matches? % scope) attestations)
        scope-excluded (mapv (fn [a] {:attestation/hash (:attestation/id a)
                                      :reason :subject-mismatch})
                             scope-mismatched)

        ;; Eligibility checks
        eligibility-results (mapv (fn [a]
                                    (let [integrity (check-integrity a)
                                          sig (check-signature a)
                                          active (check-attestor-active a attestor-registry)
                                          key-auth (check-key-authorized a attestor-registry)
                                          subj (check-subject-matches a scope)
                                          claim (check-claim-matches a scope)
                                          all-pass? (and (:eligible? integrity)
                                                         (:eligible? active)
                                                         (:eligible? key-auth)
                                                         (:eligible? subj)
                                                         (:eligible? claim))
                                          reasons (keep (fn [r] (when-not (:eligible? r) (:reason r)))
                                                        [integrity active key-auth subj claim])]
                                      {:attestation a
                                       :eligible? all-pass?
                                       :reasons reasons}))
                                  scope-matched)

        eligible (mapv :attestation (filter :eligible? eligibility-results))
        ineligible (mapv (fn [r]
                           {:attestation/hash (:attestation/id (:attestation r))
                            :reason (first (:reasons r))})
                         (remove :eligible? eligibility-results))

        ;; Independence
        {:keys [eligible independent-excluded conflicts]}
        (let [indep (check-independence eligible policy)]
          {:eligible (:eligible indep)
           :independent-excluded (:excluded indep)
           :conflicts (:conflicts indep)})

        ;; Outcome
        outcome-info (determine-outcome eligible conflicts k-of-n)
        outcome (:outcome outcome-info)

        ;; Build report
        submitted-count (count attestations)
        eligible-count (count eligible)
        excluded-count (+ (count ineligible) (count independent-excluded) (count scope-excluded))
        pass-count (:pass-count outcome-info 0)
        fail-count (:fail-count outcome-info 0)
        other-count (:other-count outcome-info 0)

        base-report {:quorum/report-version report-version
                     :quorum/policy-hash (policy-hash policy)
                     :quorum/scope scope
                     :quorum/outcome outcome
                     :quorum/counts {:submitted submitted-count
                                     :eligible eligible-count
                                     :excluded excluded-count
                                     :pass pass-count
                                     :fail fail-count
                                     :inconclusive other-count}
                     :quorum/eligible-attestors
                     (mapv (fn [a]
                             {:attestor/id (:attestation/attestor-id a)
                              :attestation/hash (:attestation/id a)
                              :claim/result (:attestation/claim-result a)})
                           eligible)
                     :quorum/excluded-attestations
                     (vec (concat ineligible independent-excluded scope-excluded))
                     :quorum/conflicts conflicts}
        report-hash (hc/hash-with-intent {:hash/intent :evidence-record}
                                         (dissoc base-report :quorum/hash))]
    (assoc base-report :quorum/hash report-hash)))

;; ── Grouping ─────────────────────────────────────────────────────────────────

(defn group-attestations-by-quorum-scope
  "Group attestations by their subject and claim scope for quorum evaluation.
   Returns a map of {scope-key -> [attestations]} where scope-key is
   {:subject-kind :subject-hash :claim-id}."
  [attestations]
  (group-by (fn [a]
              {:subject-kind (:attestation/subject-kind a)
               :subject-hash (:attestation/subject-hash a)
               :claim-id (:attestation/claim-id a)})
            attestations))

;; ── Explanation ──────────────────────────────────────────────────────────────

(defn explain-quorum-report
  "Produce a human-readable summary of a quorum report."
  [report]
  (let [outcome (:quorum/outcome report)
        counts (:quorum/counts report)
        scope (:quorum/scope report)]
    (case outcome
      :quorum/confirmed
      (str "Quorum CONFIRMED: " (:pass counts) " independent attestors confirmed "
           "the claim on subject " (:subject-hash scope))

      :quorum/rejected
      (str "Quorum REJECTED: " (:fail counts) " independent attestors rejected "
           "the claim on subject " (:subject-hash scope))

      :quorum/conflicted
      (str "Quorum CONFLICTED: " (:pass counts) " for, " (:fail counts) " against — "
           (count (:quorum/conflicts report)) " conflict(s) detected on subject "
           (:subject-hash scope))

      :quorum/insufficient-quorum
      (str "Quorum INSUFFICIENT: " (:eligible counts) " eligible of "
           (:pass counts) " pass / " (:fail counts) " fail — "
           "need k attestations on subject " (:subject-hash scope))

      :quorum/invalid-input
      (str "Quorum INVALID: no eligible attestations for subject "
           (:subject-hash scope))

      (str "Quorum unknown outcome: " outcome))))
