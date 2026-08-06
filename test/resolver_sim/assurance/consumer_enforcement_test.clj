(ns resolver-sim.assurance.consumer-enforcement-test
  "Consumer-enforcement audit: prove that builder-computed convenience status
   (`authorisation-approved?`, `authorisation-status`) is NOT three-member
   authority, and that consumers must gate on `evaluate-three-member-authority`.

   Findings this locks in:
     - `verdict_policy.clj` gates supersession execution on
       `authorisation-approved?` (approve-count status) — a bypass;
     - `verify-authorisation-usable` uses `authorisation-approved?` — usability,
       not authority;
     - `force_authorised_execution_evidence` verifies signatures + policy
       threshold but not three-member outcome concurrence or equivocation.
   None of these must be mistaken for the canonical authority gate."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.assurance.three-member-authority :as tma]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private auth-id :authorisation/test-001)
(def ^:private request-root (str "sha256:" (apply str (take 64 (cycle "a1")))))
(def ^:private round-hash  (str "sha256:" (apply str (take 64 (cycle "b2")))))
(def ^:private outcome-a   (str "sha256:" (apply str (take 64 (cycle "c3")))))
(def ^:private outcome-b   (str "sha256:" (apply str (take 64 (cycle "d4")))))

(def ^:private members
  [{:researcher/id "r-a" :role :model-steward}
   {:researcher/id "r-b" :role :independent-reproducer}
   {:researcher/id "r-c" :role :adversarial-reviewer}])

(defn- v1-hash
  [member decision]
  (str "sha256:"
       (hc/domain-hash :researcher-decision
                       {:researcher/id member
                        :authorisation/id auth-id
                        :authorisation/request-root request-root
                        :review-round/hash round-hash
                        :decision decision})))

(defn- v1-approve
  "Genuine researcher-decision.v1 position (no outcome root, no schema-version)."
  [member]
  {:researcher/id member
   :authorisation/request-root request-root
   :review-round/hash round-hash
   :decision :approve
   :decision/hash (v1-hash member :approve)
   :signature {:value (str "sig-" member) :signed-at "t0"}})

(defn- build-fa
  "Build a real force-authorisation artifact via the builder (v1-only refs)."
  [refs]
  (rfa/build-authorisation
   {:authorisation/id auth-id
    :authorisation/policy {:policy/id :research/three-member-force-authorisation
                           :policy/version 1
                           :policy/schema-version "force-authorisation-policy.v1"
                           :policy/hash (str "sha256:" (apply str (take 64 (cycle "99"))))}
    :authorisation/review-round {:review-round/id :review-round/test
                                 :review-round/hash round-hash}
    :authorisation/request-root request-root
    :authorisation/target {:target/kind :benchmark-branch
                           :target/baseline-content-root outcome-a
                           :target/branch-descriptor-hash outcome-a
                           :target/proposed-content-root outcome-a}
    :authorisation/decision-references refs
    :authorisation/threshold {:required 2 :eligible 3}}))

(defn- authority-report
  "Run the canonical authority report on a built FA artifact."
  [fa]
  (tma/evaluate-three-member-authority
   :authorisation fa
   :review-round {:review-round/members members}
   :signature-valid? (constantly true)))

(deftest builder-approve-status-is-not-authority
  (testing "a v1-only authorisation is 'approved' by the builder convenience..."
    (let [fa (build-fa [(v1-approve "r-a") (v1-approve "r-b")])]
      (is (rfa/authorisation-valid? fa))
      (is (true? (rfa/authorisation-approved? fa))
          "builder status: :approved from approve counts")
      (is (= :approved (rfa/authorisation-status fa)))))
  (testing "...but it is NOT three-member authority (no complete-outcome concurrence)"
    (let [fa (build-fa [(v1-approve "r-a") (v1-approve "r-b")])
          report (authority-report fa)]
      (is (= :not-authorised (:authority-status report)))
      (is (= :authoritative-target (:outcome-source report)))
      (is (zero? (:counted-support report))
          "v1 approvals never count toward a complete-outcome concurrence")
      (is (contains? (set (:authority/reasons report))
                     :non-target-outcome-concurrence)))))

(deftest builder-approve-masks-outcome-divergence
  (testing "two approves over different outcome roots still 'approve' the builder"
    (let [d-a (assoc (v1-approve "r-a") :outcome/root outcome-a
                     :schema-version "researcher-decision.v2"
                     :authorisation/id auth-id
                     :decision/hash
                     (str "sha256:"
                          (hc/domain-hash :researcher-decision-v2
                                          {:researcher/id "r-a"
                                           :authorisation/id auth-id
                                           :authorisation/request-root request-root
                                           :review-round/hash round-hash
                                           :outcome/root outcome-a
                                           :decision :approve})))
          d-b (assoc (v1-approve "r-b") :outcome/root outcome-b
                     :schema-version "researcher-decision.v2"
                     :authorisation/id auth-id
                     :decision/hash
                     (str "sha256:"
                          (hc/domain-hash :researcher-decision-v2
                                          {:researcher/id "r-b"
                                           :authorisation/id auth-id
                                           :authorisation/request-root request-root
                                           :review-round/hash round-hash
                                           :outcome/root outcome-b
                                           :decision :approve})))
          fa (build-fa [d-a d-b])
          report (authority-report fa)]
      (is (true? (rfa/authorisation-approved? fa)))
      (is (= :not-authorised (:authority-status report))
          "the authority report refuses divergent-outcome approvals")
      (is (contains? (set (:authority/reasons report))
                     :non-target-outcome-concurrence)))))

(deftest consumer-gates-must-use-authority-report
  (testing "the canonical enforcement hook is evaluate-three-member-authority"
    (let [fa (build-fa [(v1-approve "r-a") (v1-approve "r-b") (v1-approve "r-c")])
          report (authority-report fa)]
      (is (= :not-authorised (:authority-status report))
          "even three v1 approvals cannot establish complete-outcome authority")
      (is (true? (:policy-conforming? report))
          "the profile conforms; the positions still cannot concur"))))

(deftest cancellation-authority-is-profile-gate-not-bypass
  (testing "classify-cancellation-gates treats the certificate as a verified
            profile and never fabricates position-level authority"
    (let [r (cfa/classify-cancellation-gates
             {:decision-opts {:profile-id "cfa/2-3"}
              :target-state :proposed
              :certificate (cfa/declare-profile {:member-count 3 :threshold 2
                                                 :profile-id "cert/2-3"})
              :snapshot-binding-valid? true
              :transition-won? true})]
      (is (true? (:cancellation/authorised? r)))
      (is (true? (:cancellation/committable? r)))
      (is (not (contains? r :counted-support))
          "the cancellation gate does not itself verify positions"))))
