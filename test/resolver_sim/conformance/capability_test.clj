(ns resolver-sim.conformance.capability-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.capability :as cap]))

(deftest compare-capability-satisfied
  (is (= :satisfied
         (:kind (cap/compare-capability
                 {:capability :action/replay :version 1}
                 {:action/replay 1}))))
  (testing "available version may exceed required version"
    (is (= :satisfied
           (:kind (cap/compare-capability
                   {:capability :action/replay :version 1}
                   {:action/replay 2}))))))

(deftest compare-capability-missing
  (let [r (cap/compare-capability
           {:capability :action/appeal :version 2}
           {:action/replay 1})]
    (is (= :missing (:kind r)))
    (is (= 2 (:required r)))
    (is (nil? (:available r)))))

(deftest compare-capability-version-conflict
  (let [r (cap/compare-capability
           {:capability :action/appeal :version 2}
           {:action/appeal 1})]
    (is (= :version-conflict (:kind r)))
    (is (= 2 (:required r)))
    (is (= 1 (:available r)))))

(deftest compare-capability-accepts-seq-form
  (is (= :satisfied
         (:kind (cap/compare-capability
                 {:capability :profile/invariants :version 1}
                 [{:capability :profile/invariants :version 1}])))))

(deftest compatible-capabilities-ok
  (let [r (cap/compatible-capabilities?
           [{:capability :action/replay :version 1}]
           {:action/replay 1 :projection/state 2}
           [{:capability :projection/state :version 2}])]
    (is (:compatible? r))
    (is (empty? (:missing-capabilities r)))
    (is (empty? (:version-conflicts r)))
    (is (= 2 (count (:satisfied-capabilities r))))
    (is (empty? (:unsupported-capabilities r)))))

(deftest compatible-capabilities-missing-and-conflict
  (let [r (cap/compatible-capabilities?
           [{:capability :action/replay :version 1}
            {:capability :action/appeal :version 2}]
           {:action/replay 1 :action/appeal 1}
           [{:capability :projection/state :version 2}])]
    (is (not (:compatible? r)))
    (is (= 1 (count (:version-conflicts r))))
    (is (= :action/appeal (get-in r [:version-conflicts 0 :capability])))
    (is (= :projection/state (get-in r [:missing-capabilities 0 :capability])))
    (is (= 2 (count (:unsupported-capabilities r))))))

(deftest compatible-capabilities-unsupported-categorized
  (let [r (cap/compatible-capabilities?
           [{:capability :action/replay :version 1}]
           {} ; no capabilities at all
           nil)]
    (is (not (:compatible? r)))
    (is (= :action/replay (get-in r [:missing-capabilities 0 :capability])))))

(deftest unsupported-capability-predicate
  (is (cap/unsupported-capability? {:kind :missing}))
  (is (cap/unsupported-capability? {:kind :version-conflict}))
  (is (not (cap/unsupported-capability? {:kind :satisfied}))))

;; ---------------------------------------------------------------------------
;; Observed capability satisfaction (declared / resolved / exercised)
;; ---------------------------------------------------------------------------

(deftest capability-status-stages
  (let [s (cap/capability-status
           {:capability :semantic-validation :version 1}
           #{:semantic-validation} #{:semantic-validation} #{:semantic-validation})]
    (is (true? (:declared? s)))
    (is (true? (:resolved? s)))
    (is (true? (:exercised? s))))
  (testing "declared but not exercised is not claimable"
    (let [s (cap/capability-status
             {:capability :semantic-validation :version 1}
             #{:semantic-validation} #{:semantic-validation} #{})]
      (is (false? (:exercised? s))))))

(deftest receipt-satisfies-capability-only-with-pass-and-subject
  (let [subject-roots #{"sha256:subject-a"}
        pass-receipt {:capability/id :semantic-validation
                      :status :pass
                      :subject/root "sha256:subject-a"}]
    (is (cap/receipt-satisfies-capability? :semantic-validation pass-receipt subject-roots))
    (testing "wrong capability id does not satisfy"
      (is (not (cap/receipt-satisfies-capability?
                :action/replay pass-receipt subject-roots))))
    (testing "fail receipt does not satisfy"
      (is (not (cap/receipt-satisfies-capability?
                :semantic-validation
                (assoc pass-receipt :status :fail) subject-roots))))
    (testing "receipt for a different subject does not satisfy"
      (is (not (cap/receipt-satisfies-capability?
                :semantic-validation
                (assoc pass-receipt :subject/root "sha256:other") subject-roots))))))

(deftest observed-capabilities-map
  (let [required [{:capability :semantic-validation :version 1}
                  {:capability :action/replay :version 1}]
        receipts [{:capability/id :semantic-validation :status :pass
                   :subject/root "sha256:s"}
                  {:capability/id :action/replay :status :fail
                   :subject/root "sha256:s"}]
        observed (cap/observed-capabilities required receipts #{"sha256:s"})]
    (is (true? (get observed :semantic-validation)))
    (is (false? (get observed :action/replay)))))

(deftest capability-claimable-requires-all-exercised
  (let [required [{:capability :semantic-validation :version 1}
                  {:capability :action/replay :version 1}]]
    (is (cap/capability-claimable? required #{:semantic-validation :action/replay}))
    (is (not (cap/capability-claimable? required #{:semantic-validation})))
    (is (not (cap/capability-claimable? required #{})))))
