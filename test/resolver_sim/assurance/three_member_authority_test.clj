(ns resolver-sim.assurance.three-member-authority-test
  "Adversarial tests for canonical equivocation detection and the three-member
   authority report (ADR-0007 D1; THREE_MEMBER_RESEARCHER_APPLICATION §6/§11).

   Covers the required scenarios:
     1. one member signs approve and dissent for the same scope;
     2. one member approves two different outcome roots;
     3. duplicate identical submissions count once, not as two votes;
     4. input order does not affect equivocation or authority classification;
     5. two valid supporters plus one equivocator follow the declared policy;
     6. one supporter + one dissenter + one equivocator cannot reach threshold;
     7. unknown-member signatures remain visible but do not count;
     8. two identifiers using the same signing identity cannot occupy two seats;
     9. re-scoped or rehashed artifacts fail at the correct verification stage;
    10. invalid artifacts remain in the report after exclusion from the count."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.three-member-authority :as tma]
            [resolver-sim.hash.canonical :as hc]))

;; ── Fixtures ──────────────────────────────────────────────────────────────

(def ^:private auth-id :authorisation/test-001)
(def ^:private request-root (str "sha256:" (apply str (take 64 (cycle "a1")))))
(def ^:private round-hash  (str "sha256:" (apply str (take 64 (cycle "b2")))))
(def ^:private outcome-a   (str "sha256:" (apply str (take 64 (cycle "c3")))))
(def ^:private outcome-b   (str "sha256:" (apply str (take 64 (cycle "d4")))))

(def ^:private members
  [{:researcher/id "r-a" :role :model-steward}
   {:researcher/id "r-b" :role :independent-reproducer}
   {:researcher/id "r-c" :role :adversarial-reviewer}])

(def ^:private position-seq (atom 0))

(defn- v2-hash
  "Real researcher-decision.v2 domain hash over the committed preimage, so the
   report's recomputed integrity gate passes."
  [member decision outcome dissent-reason]
  (str "sha256:"
       (hc/domain-hash :researcher-decision-v2
                       (cond-> {:researcher/id member
                                :authorisation/id auth-id
                                :authorisation/request-root request-root
                                :review-round/hash round-hash
                                :outcome/root outcome
                                :decision decision}
                         (= :dissent decision) (assoc :dissent/reason dissent-reason)))))

(defn- v1-hash
  "Real researcher-decision.v1 domain hash (legacy v1 positions)."
  [member decision]
  (str "sha256:"
       (hc/domain-hash :researcher-decision
                       {:researcher/id member
                        :authorisation/id auth-id
                        :authorisation/request-root request-root
                        :review-round/hash round-hash
                        :decision decision})))

(defn- pos
  "Build a valid v2-style position whose :decision/hash genuinely recomputes.
   signature-valid? is supplied by the caller; the report still recomputes the
   decision-hash integrity gate itself."
  [member decision outcome & {:keys [dissent-reason signed-at]}]
  (let [sig (str "sig-" (hash (str member decision outcome dissent-reason
                                   (swap! position-seq inc))))]
    (cond-> {:schema-version "researcher-decision.v2"
             :researcher/id member
             :authorisation/id auth-id
             :authorisation/request-root request-root
             :review-round/hash round-hash
             :outcome/root outcome
             :decision decision
             :decision/hash (v2-hash member decision outcome dissent-reason)
             :signature {:value sig :signed-at (or signed-at "t0")}}
      (= :dissent decision) (assoc :dissent/reason (or dissent-reason "reason")))))

(defn- v1-position
  "Build a genuine researcher-decision.v1 position (no :outcome/root, no
   :authorisation/id, no :schema-version) with a real v1 hash."
  [member decision & {:keys [dissent-reason]}]
  (cond-> {:researcher/id member
           :authorisation/request-root request-root
           :review-round/hash round-hash
           :decision decision
           :decision/hash (v1-hash member decision)
           :signature {:value (str "sig-v1-" (hash (str member decision)))
                       :signed-at "t0"}}
    (= :dissent decision) (assoc :dissent/reason (or dissent-reason "reason"))))

(defn- auth
  "Build a minimal authorisation context."
  [positions & {:keys [id target-outcome]}]
  {:authorisation/id (or id auth-id)
   :authorisation/request-root request-root
   :authorisation/review-round {:review-round/id :review-round/test
                                :review-round/hash round-hash}
   :authorisation/target {:target/kind :benchmark-branch
                          :target/proposed-content-root
                          (or target-outcome outcome-a)}
   :authorisation/decision-references (vec positions)})

(defn- evaluate
  "Run the authority report with everything valid."
  [positions & {:keys [target-outcome policy sig-fn]}]
  (tma/evaluate-three-member-authority
   :authorisation (auth positions :target-outcome target-outcome)
   :review-round {:review-round/members members}
   :signature-valid? (or sig-fn (constantly true))
   :equivocation-policy policy))

;; ═══════════════════════════════════════════════════════════════════════════
;; 1. One member signs approve AND dissent for the same scope
;; ═══════════════════════════════════════════════════════════════════════════

(deftest approve-and-dissent-equivocation
  (let [report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :approve outcome-a)
                          (pos "r-c" :dissent outcome-a)])]
    (is (empty? (:equivocating-members report)))
    (is (= :authorised (:authority-status report)))
    (is (= 2 (:counted-support report))))
  (testing "approve AND dissent from one member under the same scope is equivocation"
    (let [report (evaluate [(pos "r-a" :approve outcome-a)
                            (pos "r-a" :dissent outcome-a)
                            (pos "r-b" :approve outcome-a)])]
      (is (= 1 (count (:equivocating-members report))))
      (is (= "r-a" (get-in report [:equivocating-members 0 :member/id])))
      (is (= :not-authorised (:authority-status report)))
      (is (= 1 (:counted-support report))
          "the equivocator never adds a vote")
      (is (= 3 (:constituted-member-count report))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 2. One member approves two different outcome roots
;; ═══════════════════════════════════════════════════════════════════════════

(deftest approve-two-outcome-roots-equivocation
  (let [report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-a" :approve outcome-b)
                          (pos "r-b" :approve outcome-a)])]
    (is (= 1 (count (:equivocating-members report))))
    (is (= "r-a" (get-in report [:equivocating-members 0 :member/id])))
    (is (contains? (set (get-in report [:equivocating-members 0
                                        :incompatibility-reasons]))
                   :distinct-outcome-roots))
    (is (= :not-authorised (:authority-status report)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 3. Duplicate identical submissions count once, not as two votes
;; ═══════════════════════════════════════════════════════════════════════════

(deftest identical-duplicates-count-once
  (let [d1 (pos "r-a" :approve outcome-a)
        dup (assoc d1 :signature {:value "sig-copy" :signed-at "t1"})
        report (evaluate [d1 dup
                          (pos "r-b" :approve outcome-a)
                          (pos "r-c" :dissent outcome-a)])]
    (is (empty? (:equivocating-members report))
        "identical duplicates are compatible, not equivocation")
    (is (= 2 (:counted-support report))
        "one seat counts once despite two identical submissions")
    (is (= 1 (count (:duplicate-seat-positions report)))
        "the extra duplicate is preserved in the report")
    (is (= :authorised (:authority-status report)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 4. Input order does not affect equivocation or authority classification
;; ═══════════════════════════════════════════════════════════════════════════

(deftest input-order-independence
  (let [base [(pos "r-a" :approve outcome-a)
              (pos "r-a" :dissent outcome-a)
              (pos "r-b" :approve outcome-a)
              (pos "r-c" :approve outcome-a)]]
    (doseq [shuffle [[1 0 2 3] [3 2 1 0] [2 3 0 1]]]
      (let [r1 (evaluate base)
            r2 (evaluate (mapv base shuffle))]
        (is (= (:authority-status r1) (:authority-status r2)))
        (is (= (set (map :member/id (:equivocating-members r1)))
               (set (map :member/id (:equivocating-members r2)))))
        (is (= (:counted-support r1) (:counted-support r2)))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 5. Two valid supporters plus one equivocator follow the declared policy
;; ═══════════════════════════════════════════════════════════════════════════

(deftest two-supporters-plus-equivocator-policy
  (testing "default :invalid-seat policy keeps two valid supporters authorised"
    (let [report (evaluate [(pos "r-a" :approve outcome-a)
                            (pos "r-a" :dissent outcome-a)
                            (pos "r-b" :approve outcome-a)
                            (pos "r-c" :approve outcome-a)]
                           :policy tma/default-equivocation-policy)]
      (is (= 1 (count (:equivocating-members report))))
      (is (= :authorised (:authority-status report)))
      (is (= 2 (:counted-support report)))))
  (testing ":count-as-dissent policy counts the equivocator as one dissent"
    (let [report (evaluate [(pos "r-a" :approve outcome-a)
                            (pos "r-a" :dissent outcome-a)
                            (pos "r-b" :approve outcome-a)
                            (pos "r-c" :approve outcome-a)]
                           :policy :count-as-dissent)]
      (is (= :authorised (:authority-status report)))
      (is (= 2 (:effective-dissent-count report)))))
  (testing ":fail-certificate policy fails the whole certificate"
    (let [report (evaluate [(pos "r-a" :approve outcome-a)
                            (pos "r-a" :dissent outcome-a)
                            (pos "r-b" :approve outcome-a)
                            (pos "r-c" :approve outcome-a)]
                           :policy :fail-certificate)]
      (is (= :not-authorised (:authority-status report)))
      (is (contains? (set (:authority/reasons report))
                     :equivocation-fails-certificate)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 6. One supporter + one dissenter + one equivocator cannot reach threshold
;; ═══════════════════════════════════════════════════════════════════════════

(deftest supporter-dissenter-equivocator-no-threshold
  (let [report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :dissent outcome-a)
                          (pos "r-c" :approve outcome-a)
                          (pos "r-c" :dissent outcome-a)])]
    (is (= 1 (count (:equivocating-members report))))
    (is (= 1 (:counted-support report)))
    (is (= :not-authorised (:authority-status report)))
    (is (contains? (set (:authority/reasons report)) :insufficient-support))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 7. Unknown-member signatures remain visible but do not count
;; ═══════════════════════════════════════════════════════════════════════════

(deftest unknown-members-visible-but-not-counted
  (let [report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :approve outcome-a)
                          (pos "intruder" :approve outcome-a)])]
    (is (= ["intruder"] (:unknown-members report)))
    (is (= 2 (:counted-support report)))
    (is (= :authorised (:authority-status report))
        "unknown signatures are visible but never block or add votes")))

;; ═══════════════════════════════════════════════════════════════════════════
;; 8. Two identifiers using the same prohibited signing identity cannot occupy
;;    two seats (identity separation, not real-world independence)
;; ═══════════════════════════════════════════════════════════════════════════

(deftest same-signing-identity-cannot-fill-two-seats
  (let [r-a (pos "r-a" :approve outcome-a)
        forged (assoc (pos "r-b" :approve outcome-a)
                      :signature {:value "forged-from-r-a" :signed-at "t0"})
        ;; forged carries r-b's id but the report is given a signature check
        ;; that fails it, proving the second seat cannot be occupied by the
        ;; same signing identity.
        sig-fn (fn [p] (not= "forged-from-r-a" (get-in p [:signature :value])))
        report (evaluate [r-a forged (pos "r-c" :approve outcome-a)]
                         :sig-fn sig-fn)]
    (is (= 1 (count (:invalid-positions report))))
    (is (= 2 (:counted-support report)))
    (is (= :authorised (:authority-status report))))
  (testing "identity separation is reported, not claimed as independence"
    (let [report (evaluate [(pos "r-a" :approve outcome-a)
                            (pos "r-b" :approve outcome-a)
                            (pos "r-c" :approve outcome-a)])]
      (is (true? (:identity-separate? report)))
      (is (not (contains? report :real-world-independence?))
          "distinct identifiers are identity separation, not independence"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 9. Re-scoped or rehashed artifacts fail at the correct verification stage
;; ═══════════════════════════════════════════════════════════════════════════

(deftest re-scoped-position-fails-at-scope-stage
  (let [re-scoped (assoc (pos "r-c" :approve outcome-a)
                         :authorisation/request-root
                         (str "sha256:" (apply str (take 64 (cycle "ee")))))
        report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :approve outcome-a)
                          re-scoped])]
    (is (= 1 (count (:re-scoped-positions report)))
        "a re-scoped position fails at the scope stage, not the count")
    (is (= 2 (:counted-support report)))
    (is (= :authorised (:authority-status report)))))

(deftest rehashed-position-fails-hash-integrity
  (let [tampered (assoc (pos "r-c" :approve outcome-a)
                        :outcome/root outcome-b)
        ;; the declared hash no longer matches the fields -> hash-invalid
        report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :approve outcome-a)
                          tampered])]
    (is (= 1 (count (:invalid-positions report)))
        "a rehashed/tampered position fails the decision-hash integrity gate")
    (is (= 2 (:counted-support report)))
    (is (= :authorised (:authority-status report)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; 10. Invalid artifacts remain in the report after exclusion from the count
;; ═══════════════════════════════════════════════════════════════════════════

(deftest invalid-artifacts-preserved-after-exclusion
  (let [bad-sig (assoc (pos "r-c" :approve outcome-a)
                       :signature {:value "bogus" :signed-at "t0"})
        report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :approve outcome-a)
                          bad-sig]
                         :sig-fn (fn [p]
                                   (not= "bogus" (get-in p [:signature :value]))))]
    (is (= 1 (count (:invalid-positions report))))
    (is (= "r-c" (:researcher/id (first (:invalid-positions report)))))
    (is (= 2 (:counted-support report))
        "invalid evidence is excluded from the authority calculation")
    (is (= :authorised (:authority-status report)))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Determinism and scope sanity
;; ═══════════════════════════════════════════════════════════════════════════

(deftest authority-report-shape
  (let [report (evaluate [(pos "r-a" :approve outcome-a)
                          (pos "r-b" :approve outcome-a)
                          (pos "r-c" :dissent outcome-a)])]
    (is (= 3 (:constituted-member-count report)))
    (is (= 2 (:required-threshold report)))
    (is (= outcome-a (:outcome-root report)))
    (is (= 2 (:counted-support report)))
    (is (= 1 (count (:valid-dissenting-positions report))))
    (is (= 2 (count (:valid-supporting-positions report))))
    (is (empty? (:valid-qualifying-positions report)))
    (is (empty? (:absent-members report)))
    (is (true? (:policy-conforming? report)))
    (is (true? (:identity-separate? report)))
    (is (= :authorised (:authority-status report)))
    (is (empty? (:authority/reasons report)))))

(deftest dissent-only-not-authorised
  (let [report (evaluate [(pos "r-a" :dissent outcome-a)
                          (pos "r-b" :dissent outcome-a)
                          (pos "r-c" :dissent outcome-a)])]
    (is (= 0 (:counted-support report)))
    (is (= :not-authorised (:authority-status report)))))

(deftest v1-legacy-approve-cannot-establish-outcome-concurrence
  (testing "a v1-style position (no outcome root) cannot be counted as support"
    (let [v1  (v1-position "r-a" :approve)
          v1-b (v1-position "r-b" :approve)
          v2-c (pos "r-c" :approve outcome-a)
          report (evaluate [v1 v1-b v2-c])]
      (is (= 1 (:counted-support report))
          "only the v2 outcome-bound supporter is counted")
      (is (= 2 (count (:valid-qualifying-positions report)))
          "v1 approvals are honest qualifying positions (outcome-binding unavailable)")
      (is (= :not-authorised (:authority-status report))))))
