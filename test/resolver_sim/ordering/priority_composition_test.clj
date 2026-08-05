(ns resolver-sim.ordering.priority-composition-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.ordering.priority :as p]
            [resolver-sim.ordering.priority-composition :as pc]))

;; ── Fixtures ────────────────────────────────────────────────────────────

(def subjects
  [{:subject/id :claim/a :subject/kind :claim}
   {:subject/id :claim/b :subject/kind :claim}
   {:subject/id :claim/c :subject/kind :claim}])

(defn- classify-claim
  [subject]
  (case (:subject/id subject)
    :claim/a {:priority/tier 1 :priority/reason :secured-claim}
    :claim/b {:priority/tier 1 :priority/reason :secured-claim}
    :claim/c {:priority/tier 2 :priority/reason :subordinated-claim}))

(def order
  (p/build-priority-order
   {:subjects subjects
    :classifier classify-claim
    :comparison-basis {:method :declared-tier :parameter-root "claims/v1"}}))

(defn- exception-reason
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:reason (ex-data error)))))

(def default-demand
  {:claim/a 80 :claim/b 80 :claim/c 20})

;; ── apply-priority-allocation ───────────────────────────────────────────

(deftest apply-priority-allocation-proposal-example
  (let [result (pc/apply-priority-allocation
                {:priority-order order
                 :available-capacity 100
                 :demand-by-subject default-demand
                 :within-class-policy {:method :pro-rata}})]
    (is (= 50 (get-in result [:allocations :claim/a])))
    (is (= 50 (get-in result [:allocations :claim/b])))
    (is (= 0 (get-in result [:allocations :claim/c])))
    (is (= 0 (:exhausted-at-rank result)))
    (is (= 0 (:partially-satisfied-class result)))))

(deftest apply-priority-allocation-full-satisfaction
  (let [result (pc/apply-priority-allocation
                {:priority-order order
                 :available-capacity 200
                 :demand-by-subject default-demand})]
    (is (= 80 (get-in result [:allocations :claim/a])))
    (is (= 80 (get-in result [:allocations :claim/b])))
    (is (= 20 (get-in result [:allocations :claim/c])))
    (is (nil? (:exhausted-at-rank result)))
    (is (nil? (:partially-satisfied-class result)))))

(deftest apply-priority-allocation-lower-class-ineligible
  (testing "a lower-priority class receives nothing once capacity is exhausted"
    (let [result (pc/apply-priority-allocation
                  {:priority-order order
                   :available-capacity 160
                   :demand-by-subject default-demand})]
      (is (= 80 (get-in result [:allocations :claim/a])))
      (is (= 80 (get-in result [:allocations :claim/b])))
      (is (= 0 (get-in result [:allocations :claim/c])))
      (is (= 0 (:exhausted-at-rank result)))
      (is (nil? (:partially-satisfied-class result))))))

(deftest apply-priority-allocation-first-satisfied
  (testing "first-satisfied fills members in canonical order within a class"
    (let [result (pc/apply-priority-allocation
                  {:priority-order order
                   :available-capacity 100
                   :demand-by-subject default-demand
                   :within-class-policy {:method :first-satisfied}})]
      (is (= 80 (get-in result [:allocations :claim/a])))
      (is (= 20 (get-in result [:allocations :claim/b])))
      (is (= 0 (get-in result [:allocations :claim/c])))
      (is (= 0 (:exhausted-at-rank result)))
      (is (= 0 (:partially-satisfied-class result))))))

(deftest apply-priority-allocation-empty-capacity
  (let [result (pc/apply-priority-allocation
                {:priority-order order
                 :available-capacity 0
                 :demand-by-subject default-demand})]
    (is (= 0 (get-in result [:allocations :claim/a])))
    (is (= 0 (get-in result [:allocations :claim/c])))
    (is (nil? (:exhausted-at-rank result)))))

(deftest allocation-consumes-a-validated-priority-artifact
  (testing "an invalid priority artifact is rejected rather than redefined"
    (let [tampered (assoc-in order [:priority-classes 0 :members] [:claim/x :claim/y])]
      (is (= :invalid-priority-order
             (exception-reason #(pc/apply-priority-allocation
                                 {:priority-order tampered
                                  :available-capacity 10}))))))
  (testing "demand for a non-member subject is rejected"
    (is (= :demand-for-unclassified-subject
           (exception-reason #(pc/apply-priority-allocation
                               {:priority-order order
                                :available-capacity 10
                                :demand-by-subject {:claim/nope 5}})))))
  (testing "missing or invalid capacity is rejected"
    (is (= :missing-priority-order
           (exception-reason #(pc/apply-priority-allocation
                               {:available-capacity 10}))))
    (is (= :invalid-available-capacity
           (exception-reason #(pc/apply-priority-allocation
                               {:priority-order order
                                :available-capacity -5})))))
  (testing "an unregistered within-class policy is rejected"
    (is (= :unsupported-within-class-policy
           (exception-reason #(pc/apply-priority-allocation
                               {:priority-order order
                                :available-capacity 10
                                :demand-by-subject {:claim/a 5}
                                :within-class-policy {:method :auction}}))))))

(deftest deterministic-diagnostics-live-outside-the-normative-shape
  (let [result (pc/apply-priority-allocation
                {:priority-order order
                 :available-capacity 100
                 :demand-by-subject default-demand})]
    (testing "normative fields only"
      (is (= #{:allocations :exhausted-at-rank :partially-satisfied-class
               :allocation/diagnostics}
             (set (keys result))))
      (is (integer? (:exhausted-at-rank result)))
      (is (= 0 (:exhausted-at-rank result)))
      (is (map? (:allocations result))))
    (testing "derived diagnostics are deterministic"
      (is (= 30 (get-in result [:allocation/diagnostics :unmet :claim/a])))
      (is (= 20 (get-in result [:allocation/diagnostics :unmet :claim/c])))
      (is (= 0 (get-in result [:allocation/diagnostics :capacity-after]))))))

;; ── Within-class policy registry ────────────────────────────────────────

(deftest within-class-policies-are-extension-backed
  (let [policy {:policy/name :last-satisfied
                :policy/description "Reverse-canonical sequential fill"
                :policy/allocate
                (fn [rows allocatable]
                  (let [ordered (sort-by (comp p/canonical-subject-key :row/id) rows)]
                    (loop [remaining allocatable
                           pending (reverse ordered)
                           allocated {}]
                      (if (or (empty? pending) (zero? remaining))
                        {:allocated allocated
                         :unmet (into {} (map (fn [row]
                                                [(:row/id row)
                                                 (- (:requested row)
                                                    (get allocated (:row/id row) 0))])
                                              ordered))}
                        (let [row (first pending)
                              amount (min remaining (:requested row))]
                          (recur (- remaining amount)
                                 (rest pending)
                                 (assoc allocated (:row/id row) amount)))))))}
        _ (pc/register-within-class-policy! policy)
        result (pc/apply-priority-allocation
                {:priority-order order
                 :available-capacity 100
                 :demand-by-subject default-demand
                 :within-class-policy {:method :last-satisfied}})]
    (is (= :last-satisfied (:policy/name (:last-satisfied pc/within-class-policies))))
    (testing "custom policy is a capacity-allocation policy, not a priority semantic"
      (is (= 20 (get-in result [:allocations :claim/a])))
      (is (= 80 (get-in result [:allocations :claim/b])))
      (is (= 0 (get-in result [:allocations :claim/c]))))))

;; ── execution-order (serialization-only) ────────────────────────────────

(deftest execution-order-flattens-classes-in-canonical-order
  (is (= {:execution-order {:method :canonical-subject-id
                            :semantics :serialization-only}
          :subject-ids [:claim/a :claim/b :claim/c]}
         (pc/execution-order order)))
  (is (= [:claim/a :claim/b :claim/c]
         (:subject-ids (pc/execution-order order
                                           {:method :canonical-subject-id
                                            :semantics :serialization-only}))))
  (testing "an unregistered serialization method is rejected"
    (is (= :unsupported-execution-order-method
           (exception-reason #(pc/execution-order order
                                                  {:method :entry-sequence
                                                   :semantics :serialization-only}))))))
