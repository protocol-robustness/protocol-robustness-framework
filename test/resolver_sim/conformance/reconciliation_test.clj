(ns resolver-sim.conformance.reconciliation-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.conformance.reconciliation :as rec]))

(def ^:private plan
  {:plan/root "sha256:plan"
   :steps [{:step/id :schema-validation :requires [] :produces [:schema-validation-receipt]}
           {:step/id :semantic-validation :requires [:schema-validation-receipt] :produces [:semantic-validation-receipt]}
           {:step/id :capability-check :requires [:semantic-validation-receipt] :produces [:capability-check-receipt]}
           {:step/id :replay :requires [:capability-check-receipt] :produces [:replay-receipt]}]})

(def ^:private subject-set
  {:subject-set/root "sha256:subjects"
   :subjects ["sew-001" "sew-002"]})

(defn- r [step-id subject status]
  {:step/id step-id
   :subject/id subject
   :subject/root (str "sha256:" subject)
   :subject-set/root "sha256:subjects"
   :status status})

(defn- complete-observations
  "One pass receipt per planned step per required subject."
  []
  (mapcat (fn [sid]
            [{:step/id :schema-validation :subject/id sid :subject/root (str "sha256:" sid)
              :subject-set/root "sha256:subjects" :status :pass}
             {:step/id :semantic-validation :subject/id sid :subject/root (str "sha256:" sid)
              :subject-set/root "sha256:subjects" :status :pass}
             {:step/id :capability-check :subject/id sid :subject/root (str "sha256:" sid)
              :subject-set/root "sha256:subjects" :status :pass}
             {:step/id :replay :subject/id sid :subject/root (str "sha256:" sid)
              :subject-set/root "sha256:subjects" :status :pass}])
          ["sew-001" "sew-002"]))

(deftest all-steps-covered-passes
  (let [res (rec/reconcile plan (complete-observations) subject-set)]
    (is (= :pass (:reconciliation/status res)))
    (is (empty? (:missing-steps res)))
    (is (empty? (:unexpected-steps res)))
    (is (empty? (:duplicate-steps res)))
    (is (empty? (:subject-mismatches res)))
    (is (empty? (:dependency-mismatches res)))
    (is (= 2 (count (:terminal-receipts res))))
    (is (string? (:reconciliation/root res)))
    (is (rec/passed? res))))

(deftest missing-step-rejected
  (let [obs (remove #(and (= :replay (:step/id %)) (= "sew-002" (:subject/id %)))
                    (complete-observations))
        res (rec/reconcile plan obs subject-set)]
    (is (= :fail (:reconciliation/status res)))
    (is (some #(and (= :replay (:step/id %)) (= "sew-002" (:subject/id %)))
              (:missing-steps res)))))

(deftest duplicate-step-rejected
  (let [obs (conj (complete-observations)
                  (r :replay "sew-001" :pass))
        res (rec/reconcile plan obs subject-set)]
    (is (= :fail (:reconciliation/status res)))
    (is (some #(and (= :replay (:step/id %)) (= "sew-001" (:subject/id %)))
              (:duplicate-steps res)))))

(deftest unexpected-step-rejected
  (let [obs (conj (complete-observations)
                  (r :reconciliation "sew-001" :pass))
        res (rec/reconcile plan obs subject-set)]
    (is (= :fail (:reconciliation/status res)))
    (is (some #(= :reconciliation (:step/id %)) (:unexpected-steps res)))))

(deftest wrong-subject-rejected
  (let [obs (conj (complete-observations)
                  {:step/id :replay :subject/id "sew-999"
                   :subject/root "sha256:sew-999"
                   :subject-set/root "sha256:subjects" :status :pass})
        res (rec/reconcile plan obs subject-set)]
    (is (= :fail (:reconciliation/status res)))
    (is (some #(= "sew-999" (:subject/id %)) (:subject-mismatches res)))))

(deftest failed-prerequisite-invalidates-downstream
  ;; sew-002's semantic validation fails -> its capability-check and replay
  ;; receipts become inadmissible via the dependency rule.
  (let [base (complete-observations)
        obs (mapv (fn [x]
                    (if (and (= :semantic-validation (:step/id x))
                             (= "sew-002" (:subject/id x)))
                      (assoc x :status :fail)
                      x))
                  base)
        res (rec/reconcile plan obs subject-set)]
    (is (= :fail (:reconciliation/status res)))
    (is (some #(and (= :capability-check (:step/id %)) (= "sew-002" (:subject/id %)))
              (:dependency-mismatches res)))))

(deftest skippable-step-allowed-without-receipt
  (let [plan' (assoc-in plan [:steps 1 :skippable?] true)
        obs (remove #(= :semantic-validation (:step/id %)) (complete-observations))
        res (rec/reconcile plan' obs subject-set)]
    ;; with semantic skipped, capability-check depends on semantic but semantic
    ;; is skippable -> requirement removed; capability receipts admissible
    (is (= :pass (:reconciliation/status res)))))

(deftest reconciliation-root-deterministic
  (is (= (rec/reconciliation-root (rec/reconcile plan (complete-observations) subject-set))
         (rec/reconciliation-root (rec/reconcile plan (complete-observations) subject-set))))
  (testing "different observations change the root"
    (is (not= (rec/reconciliation-root (rec/reconcile plan (complete-observations) subject-set))
              (rec/reconciliation-root
               (rec/reconcile plan
                              (drop-last (complete-observations))
                              subject-set))))))
