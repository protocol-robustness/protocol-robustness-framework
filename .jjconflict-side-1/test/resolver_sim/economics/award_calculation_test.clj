(ns resolver-sim.economics.award-calculation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.award-calculation :as ac]
            [resolver-sim.economics.award-policy :as ap]))

(defn- valid-award-input
  []
  {:award/id "award-001"
   :award/policy-root "sha256:policy"
   :award/pool-availability-root "sha256:pool"
   :award/claim-set-root "sha256:claims"
   :award/evidence-set-root "sha256:evidence"
   :award/beneficiary-id "beneficiary-001"
   :award/calculation-time "2026-07-30T00:00:00Z"
   :award/scale 1
   :award/calculation-components
   [{:component/id :base-award
     :component/kind :base
     :component/amount 1000
     :component/source-root "sha256:src1"}
    {:component/id :deduction-fee
     :component/kind :deduction
     :component/amount -200
     :component/source-root "sha256:src2"}]
   :award/eligibility-result
   {:eligible? true
    :checks [{:check/id :claim-valid
              :check/pass? true
              :check/evidence-root "sha256:ev1"}
             {:check/id :beneficiary-active
              :check/pass? true}]}})

;; ── build-award-calculation ──────────────────────────────────────────────────

(deftest build-components-sum-to-amount
  (let [award (ac/build-award-calculation (valid-award-input))]
    (is (= :award-calculation.v2 (:artifact/type award)))
    (is (= 800 (:award/amount award))
        "1000 + (-200) = 800")
    (is (some? (:artifact/hash award)))
    (is (= (+' 1000 -200) (:award/amount award)))))

(deftest build-base-only-components
  (let [award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/calculation-components
                      [{:component/id :single
                        :component/kind :base
                        :component/amount 500
                        :component/source-root "sha256:src"}]))]
    (is (= 500 (:award/amount award)))))

(deftest build-deductions-exceed-base-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/calculation-components
                       [{:component/id :base-part
                         :component/kind :base
                         :component/amount 100
                         :component/source-root "sha256:s1"}
                        {:component/id :deduction-part
                         :component/kind :deduction
                         :component/amount -200
                         :component/source-root "sha256:s2"}])))
      "100 + (-200) = -100 → negative amount rejected"))

(deftest build-component-sign-mismatch-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/calculation-components
                       [{:component/id :bad-base
                         :component/kind :base
                         :component/amount -50
                         :component/source-root "sha256:s"}])))
      "base kind requires non-negative amount"))

(deftest build-deduction-positive-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/calculation-components
                       [{:component/id :bad-ded
                         :component/kind :deduction
                         :component/amount 50
                         :component/source-root "sha256:s"}])))
      "deduction kind requires non-positive amount"))

(deftest build-missing-component-id-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/calculation-components
                       [{:component/kind :base
                         :component/amount 100
                         :component/source-root "sha256:s"}])))))

(deftest build-duplicate-component-id-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/calculation-components
                       [{:component/id :dup
                         :component/kind :base
                         :component/amount 100
                         :component/source-root "sha256:s1"}
                        {:component/id :dup
                         :component/kind :deduction
                         :component/amount -50
                         :component/source-root "sha256:s2"}])))))

(deftest build-component-permutation-same-hash
  (let [comps-a [{:component/id :a :component/kind :base
                  :component/amount 100 :component/source-root "sha256:s1"}
                 {:component/id :b :component/kind :deduction
                  :component/amount -30 :component/source-root "sha256:s2"}]
        comps-b [{:component/id :b :component/kind :deduction
                  :component/amount -30 :component/source-root "sha256:s2"}
                 {:component/id :a :component/kind :base
                  :component/amount 100 :component/source-root "sha256:s1"}]
        er {:eligible? true
            :checks [{:check/id :c1 :check/pass? true}]}
        a (ac/build-award-calculation
           (assoc (valid-award-input)
                  :award/calculation-components comps-a
                  :award/eligibility-result er))
        b (ac/build-award-calculation
           (assoc (valid-award-input)
                  :award/calculation-components comps-b
                  :award/eligibility-result er))]
    (is (= (:artifact/hash a) (:artifact/hash b)))))

(deftest build-deterministic-hash
  (let [a (ac/build-award-calculation (valid-award-input))
        b (ac/build-award-calculation (valid-award-input))]
    (is (= (:artifact/hash a) (:artifact/hash b)))))

(deftest build-different-policy-different-hash
  (let [a (ac/build-award-calculation (valid-award-input))
        b (ac/build-award-calculation
           (assoc (valid-award-input) :award/policy-root "sha256:other"))]
    (is (not= (:artifact/hash a) (:artifact/hash b)))))

(deftest build-different-pool-different-hash
  (let [a (ac/build-award-calculation (valid-award-input))
        b (ac/build-award-calculation
           (assoc (valid-award-input)
                  :award/pool-availability-root "sha256:other-pool"))]
    (is (not= (:artifact/hash a) (:artifact/hash b)))))

(deftest build-different-beneficiary-different-hash
  (let [a (ac/build-award-calculation (valid-award-input))
        b (ac/build-award-calculation
           (assoc (valid-award-input)
                  :award/beneficiary-id "other-beneficiary"))]
    (is (not= (:artifact/hash a) (:artifact/hash b)))))

;; ── Eligibility ──────────────────────────────────────────────────────────────

(deftest build-eligibility-all-pass-eligible
  (let [award (ac/build-award-calculation (valid-award-input))]
    (is (true? (get-in award [:award/eligibility-result :eligible?])))))

(deftest build-eligibility-any-fail-ineligible
  (let [award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/calculation-components
                      [{:component/id :zero-base
                        :component/kind :base
                        :component/amount 0
                        :component/source-root "sha256:s"}]
                      :award/eligibility-result
                      {:eligible? false
                       :checks [{:check/id :claim-invalid
                                 :check/pass? false
                                 :check/evidence-root "sha256:fail"}]}))]
    (is (false? (get-in award [:award/eligibility-result :eligible?])))
    (is (zero? (:award/amount award))
        "ineligible award must have amount 0")))

(deftest build-ineligible-positive-amount-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/eligibility-result
                       {:eligible? false
                        :checks [{:check/id :some-fail
                                  :check/pass? false}]})))
      "ineligible with positive amount (800) must be rejected"))

(deftest build-eligibility-declared-mismatch-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/eligibility-result
                       {:eligible? true
                        :checks [{:check/id :actually-fails
                                  :check/pass? false}]})))
      "declared eligible?=true but all checks must pass"))

(deftest build-empty-checks-rejected
  (is (thrown? Exception
               (ac/build-award-calculation
                (assoc (valid-award-input)
                       :award/eligibility-result
                       {:eligible? true
                        :checks []})))))

(deftest build-eligibility-checks-canonicalized
  (let [er {:eligible? true
            :checks [{:check/id :z-check :check/pass? true}
                     {:check/id :a-check :check/pass? true}]}
        award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/calculation-components
                      [{:component/id :base
                        :component/kind :base
                        :component/amount 100
                        :component/source-root "sha256:s"}]
                      :award/eligibility-result er))
        check-ids (map :check/id
                       (get-in award [:award/eligibility-result :checks]))]
    (is (= [:a-check :z-check] check-ids))))

;; ── claim-set-root ──────────────────────────────────────────────────────────

(deftest claim-set-root-deterministic
  (let [a (ac/claim-set-root ["sha256:c1" "sha256:c2"])
        b (ac/claim-set-root ["sha256:c2" "sha256:c1"])]
    (is (= a b))))

(deftest claim-set-root-duplicate-rejected
  (is (thrown? Exception (ac/claim-set-root ["sha256:c1" "sha256:c1"]))))

;; ── verify-award-calculation ─────────────────────────────────────────────────

(deftest verify-award-calculation-passes
  (let [award (ac/build-award-calculation (valid-award-input))]
    (is (:valid? (ac/verify-award-calculation award)))))

(deftest verify-award-calculation-detects-tampered-amount
  (let [award (assoc (ac/build-award-calculation (valid-award-input))
                     :award/amount 999)]
    (is (false? (:valid? (ac/verify-award-calculation award))))))

(deftest verify-award-calculation-detects-tampered-component
  (let [award (ac/build-award-calculation (valid-award-input))
        tampered-comps (update (:award/calculation-components award)
                               0 assoc :component/amount 999)
        tampered (assoc award :award/calculation-components tampered-comps)]
    (is (false? (:valid? (ac/verify-award-calculation tampered))))))

(deftest verify-award-calculation-detects-tampered-hash
  (let [award (assoc (ac/build-award-calculation (valid-award-input))
                     :artifact/hash "sha256:tampered")]
    (is (false? (:valid? (ac/verify-award-calculation award))))))

(deftest verify-award-calculation-detects-eligibility-mismatch
  (let [award (assoc-in (ac/build-award-calculation (valid-award-input))
                        [:award/eligibility-result :eligible?] false)]
    (is (false? (:valid? (ac/verify-award-calculation award))))))

(deftest verify-award-calculation-non-throwing
  (let [result (ac/verify-award-calculation {:not-an-award true})]
    (is (false? (:valid? result)))
    (is (vector? (:errors result)))))

(deftest verify-award-calculation-ineligible-zero-amount-passes
  (let [award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/calculation-components
                      [{:component/id :zero
                        :component/kind :base
                        :component/amount 0
                        :component/source-root "sha256:s"}]
                      :award/eligibility-result
                      {:eligible? false
                       :checks [{:check/id :fail
                                 :check/pass? false}]}))]
    (is (:valid? (ac/verify-award-calculation award)))
    (is (zero? (:award/amount award)))
    (is (false? (get-in award [:award/eligibility-result :eligible?])))))

;; ── check-set-root domain separation ─────────────────────────────────────────

(deftest check-set-and-claim-set-distinct-domains
  (let [claim-root (ac/claim-set-root ["sha256:a" "sha256:b"])
        check-root (ac/check-set-root [:a :b])]
    (is (not= claim-root check-root)
        "claim-set and check-set must not share a hash domain")))

(deftest check-set-root-permutation-stable
  (let [a (ac/check-set-root [:b :a :c])
        b (ac/check-set-root [:a :c :b])]
    (is (= a b))))

(deftest check-set-root-duplicate-rejected
  (is (thrown? Exception (ac/check-set-root [:a :a]))))

;; ── check-set-root binding in artifact ───────────────────────────────────────

(deftest build-with-matching-check-set-root
  (let [check-ids [:claim-valid :beneficiary-active]
        er {:eligible? true
            :checks (mapv (fn [id] {:check/id id :check/pass? true}) check-ids)}
        csr (ac/check-set-root check-ids)
        award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/eligibility-result er
                      :award/check-set-root csr))]
    (is (= csr (:award/check-set-root award)))))

(deftest build-with-mismatched-check-set-root-rejected
  (let [er {:eligible? true
            :checks [{:check/id :claim-valid :check/pass? true}
                     {:check/id :beneficiary-active :check/pass? true}]}
        ;; Expected check set includes an extra required check not supplied
        expected-ids [:claim-valid :beneficiary-active :pool-verified]
        csr (ac/check-set-root expected-ids)]
    (is (thrown? Exception
                 (ac/build-award-calculation
                  (assoc (valid-award-input)
                         :award/eligibility-result er
                         :award/check-set-root csr)))
        "artifact supplies {claim-valid, beneficiary-active} but check-set-root commits {+pool-verified}")))

;; ── review-mode eligibility completeness ─────────────────────────────────────

(deftest review-mode-requires-both-roots
  (let [er {:eligible? true
            :checks [{:check/id :claim-valid :check/pass? true}]}]
    (is (thrown? Exception
                 (ac/build-award-calculation
                  (assoc (valid-award-input)
                         :award/mode :review
                         :award/eligibility-result er)))
        "review mode without eligibility-policy-root and check-set-root is rejected")))

(deftest review-mode-with-both-roots-passes
  (let [er {:eligible? true
            :checks [{:check/id :claim-valid :check/pass? true}]}
        csr (ac/check-set-root [:claim-valid])
        award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/mode :review
                      :award/eligibility-policy-root "sha256:elig-policy"
                      :award/check-set-root csr
                      :award/eligibility-result er))]
    (is (= :review (:award/mode award)))
    (is (:valid? (ac/verify-award-calculation award)))))

(deftest generic-mode-roots-optional
  (let [award (ac/build-award-calculation (valid-award-input))]
    (is (= :generic (:award/mode award))
        "default mode is :generic with optional roots")
    (is (nil? (:award/check-set-root award)))))

;; ── Policy-relative completeness ─────────────────────────────────────────────

(defn- build-policy-bound-award
  "Build a review-mode award whose check set exactly matches the policy."
  [policy]
  (let [check-ids (:policy/required-check-ids policy)
        csr (:policy/check-set-root policy)
        er {:eligible? true
            :checks (mapv (fn [id] {:check/id id :check/pass? true}) check-ids)}]
    (ac/build-award-calculation
     (assoc (valid-award-input)
            :award/mode :review
            :award/eligibility-policy-root (:artifact/hash policy)
            :award/check-set-root csr
            :award/eligibility-result er))))

(deftest policy-relative-completeness-passes
  (let [policy (ap/build-award-policy
                {:policy/id "policy-p"
                 :policy/required-check-ids [:claim-valid :beneficiary-active]})
        award (build-policy-bound-award policy)
        result (ac/verify-award-calculation
                award {:policy-resolver (fn [_] policy)})]
    (is (:valid? result)
        "award whose check set exactly matches policy check-set is complete")))

(deftest policy-relative-completeness-detects-subset
  ;; The exact gap the review identified: policy requires {A B C}, the award
  ;; supplies {A B} but carries an internally-consistent check-set-root for
  ;; {A B}.  Resolution against the policy must reject it.
  (let [policy (ap/build-award-policy
                {:policy/id "policy-abc"
                 :policy/required-check-ids [:a :b :c]})
        ;; Award deliberately supplies only {A B}
        er {:eligible? true
            :checks [{:check/id :a :check/pass? true}
                     {:check/id :b :check/pass? true}]}
        csr (ac/check-set-root [:a :b])
        award (ac/build-award-calculation
               (assoc (valid-award-input)
                      :award/mode :review
                      :award/eligibility-policy-root (:artifact/hash policy)
                      :award/check-set-root csr
                      :award/eligibility-result er))
        ;; Internally consistent: derived root == supplied check-set-root
        internal (ac/verify-award-calculation award)
        ;; Policy-relative: policy requires {A B C}
        external (ac/verify-award-calculation
                  award {:policy-resolver (fn [_] policy)})]
    (is (:valid? internal)
        "artifact is internally consistent (supplied checks match carried check-set-root)")
    (is (false? (:valid? external))
        "policy-relative completeness rejects {A B} against policy requiring {A B C}")
    (is (some #(= :policy-supplied-check-set-mismatch (:type %))
              (:errors external)))))

(deftest policy-relative-completeness-no-resolver-is-partial
  ;; Without a policy-resolver, review-mode verification can only establish
  ;; internal consistency, not policy-relative completeness.
  (let [policy (ap/build-award-policy
                {:policy/id "policy-p"
                 :policy/required-check-ids [:claim-valid :beneficiary-active]})
        award (build-policy-bound-award policy)
        result (ac/verify-award-calculation award)]
    (is (:valid? result)
        "without a resolver, verification confirms internal consistency only")))
