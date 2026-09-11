(ns resolver-sim.assurance.deterministic-evidence-test
  "Tests for the deterministic-operation evidence contract and replay-assurance
   honesty (THREE_MEMBER_RESEARCHER_APPLICATION §8/§5; three-member task
   Phase 4/5).

   Invariant: no researcher panel is required because no researcher discretion
   is exercised; verification remains mandatory."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.canonical-force-authorisation :as cfa]))

;; ── Complete evidence fixtures ────────────────────────────────────────────

(defn- complete-evidence
  "A fully-populated evidence map for a deterministic operation."
  [_operation]
  (let [base {:lifecycle/profile-id :prf.lifecycle-window/force-authorisation
              :lifecycle/profile-version 1
              :lifecycle/profile cfa/force-authorisation-window
              :lifecycle/state :consumed
              :cutpoint :consumed
              :applicable-time "2026-08-06T00:00:00Z"
              :target/id "alloc/9"
              :target/hash "sha256:0"
              :domain-projection :force-authorisation
              :certificate/hash "sha256:cert"
              :conflict-key "alloc/9"
              :conflict-key-result :won
              :rule/id :expiry-rule-v1
              :operation/provenance {:operator :runner :at "2026-08-06T00:00:00Z"}}]
    base))

(deftest complete-evidence-valid-for-every-deterministic-operation
  (doseq [op [:submit-cancel-request :expire-at-deadline
              :deterministic-invalidation :reject-post-cutpoint-cancellation
              :execute-certified-cancellation :apply-deterministic-fallback]]
    (is (true? (cfa/deterministic-operation-verified? op (complete-evidence op)))
        (str op " with complete evidence must verify"))))

(deftest missing-single-evidence-category-fails-closed
  (doseq [op [:expire-at-deadline :deterministic-invalidation
              :reject-post-cutpoint-cancellation :execute-certified-cancellation
              :apply-deterministic-fallback :submit-cancel-request]]
    (testing (str op)
      (let [contract (get cfa/deterministic-operation-evidence op)
            full (complete-evidence op)]
        (doseq [cat (:evidence contract)]
          (let [r (cfa/deterministic-operation-evidence-valid?
                   op (dissoc full cat))]
            (is (false? (:valid? r)) (str "missing " cat " must fail"))
            (is (contains? (set (:missing-evidence r)) cat)))
          (let [r (cfa/deterministic-operation-evidence-valid?
                   op (assoc full cat nil))]
            (is (false? (:valid? r)) (str "nil " cat " must fail"))))))))

(deftest inconsistent-state-fails
  (testing "a post-cutpoint operation executed while the window is still open"
    (let [r (cfa/deterministic-operation-evidence-valid?
             :reject-post-cutpoint-cancellation
             (assoc (complete-evidence :reject-post-cutpoint-cancellation)
                    :lifecycle/state :proposed))]
      (is (false? (:valid? r)))
      (is (false? (:consistent? r)))))
  (testing "a closed post-cutpoint state stays consistent"
    (is (true? (:valid?
                (cfa/deterministic-operation-evidence-valid?
                 :reject-post-cutpoint-cancellation
                 (complete-evidence :reject-post-cutpoint-cancellation))))))
  (testing "certified execution refuses an open window too"
    (let [r (cfa/deterministic-operation-evidence-valid?
             :execute-certified-cancellation
             (assoc (complete-evidence :execute-certified-cancellation)
                    :lifecycle/state :proposed))]
      (is (false? (:valid? r)))
      (is (false? (:consistent? r)))))
  (testing "the :lifecycle/profile is a required category for post-cutpoint
            operations, so the open-window contradiction can never silently pass"
    (doseq [op [:reject-post-cutpoint-cancellation :execute-certified-cancellation]]
      (let [full (complete-evidence op)]
        (is (contains? (set (:evidence (get cfa/deterministic-operation-evidence op)))
                       :lifecycle/profile))
        (is (false? (:valid?
                     (cfa/deterministic-operation-evidence-valid?
                      op (dissoc full :lifecycle/profile))))
            (str op " without :lifecycle/profile must fail closed"))))))

(deftest unknown-operation-invalid
  (let [r (cfa/deterministic-operation-evidence-valid?
           :decide-cancel-valid-authorisation (complete-evidence :expire-at-deadline))]
    (is (false? (:valid? r))
        "the canonical contested decision is NOT a deterministic operation")
    (is (= :unknown-operation (:reason r)))))

(deftest certificate-does-not-make-deterministic-op-a-decision
  (testing "a certificate may exist, but deterministic operations stay deterministic"
    (doseq [op [:expire-at-deadline :deterministic-invalidation
                :reject-post-cutpoint-cancellation :execute-certified-cancellation
                :apply-deterministic-fallback :submit-cancel-request]]
      (is (false? (cfa/cancellation-decision-required? op)))
      (is (true? (cfa/deterministic-operation-verified?
                  op (complete-evidence op)))
          "they still require verification, just not a panel"))))

;; ── Replay-assurance honesty (contract 8 / Phase 5) ──────────────────────

(deftest independent-replay-means-recomputation-only
  (let [evidence {:target-evidence {:allocation/id 9 :phase :randomness-requested}
                  :lifecycle-profile cfa/probabilistic-allocation-window
                  :domain-projection (fn [e] (:phase e))
                  :decision-opts {:profile-id "alloc/2-3"}}
        replay (cfa/cancellation-window-assertion evidence)
        supplied (cfa/cancellation-window-assertion
                  (cfa/classify-cancellation
                   {:profile-id "alloc/2-3" :window cfa/probabilistic-allocation-window}
                   :randomness-requested))]
    (is (= :independent-replay (:assurance replay)))
    (is (= :structural-check (:assurance supplied)))
    (is (= :passing (:status replay)))
    (is (= :passing (:status supplied)))
    (testing "no stronger process property is implied by the label"
      (is (not (contains? replay :implementation-independent?)))
      (is (not (contains? replay :state-independent?)))
      (is (not (contains? replay :process-separated?)))
      (is (not (contains? replay :transition-atomic?))))))

(deftest replay-docstring-vocabulary-is-recomputation
  (testing "the project vocabulary defines independent-replay as recomputation"
    (let [doc (:doc (meta #'cfa/cancellation-window-assertion))]
      (is (some? doc))
      (is (re-find #"RECOMPUTATION independence" doc))
      (is (re-find #"does NOT establish process\s+separation" doc)))))
