(ns resolver-sim.benchmark.research-execution-projection-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.case-set :as case-set]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.research-execution-projection :as proj]))

(def coverage-measure
  {:measure/id :measure/coverage-margin
   :measure/kind :requirement-margin.v1
   :measure/domain {:kind :integer :unit :usdc}
   :measure/observation {:observation/id :coverage/covered-capacity}
   :measure/requirement {:requirement/kind :constant :value 800N :unit :usdc}
   :measure/satisfying-direction :at-least})

(def source
  {:artifact/schema "research-source.v1"
   :research/id :study/coverage-under-stress
   :research/title "Coverage under stress"
   :research/question "How much capacity remains under utilization stress?"
   :research/vary {:parameter/senior-bond [500N 1000N 2000N]
                   :parameter/utilization-bps [5000N 8000N 9500N]}
   :research/measures [coverage-measure]
   :research/hypotheses []})

(def dual-measure-source
  (assoc source :research/measures
         [coverage-measure
          {:measure/id :measure/loss-margin
           :measure/kind :requirement-margin.v1
           :measure/domain {:kind :integer :unit :usdc}
           :measure/observation {:observation/id :loss/unmet-capacity}
           :measure/requirement {:requirement/kind :constant :value 50N :unit :usdc}
           :measure/satisfying-direction :at-most}]))

(defn- exec-id [n]
  (str "sha256:" (format "%064x" n)))

(defn- plan-with [n]
  (mapv (fn [ordinal]
          {:execution/ordinal ordinal
           :execution/id (exec-id ordinal)})
        (range 1 (inc n))))

(deftest projection-binds-d-c-e-and-maps-11
  (let [frozen (research/freeze-research source)
        plan (plan-with 9)
        p (proj/build-projection frozen plan)]
    (is (= "research-execution-projection.v1" (:artifact/schema p)))
    (is (= (:research-definition/root frozen) (:research-definition/root p)))
    (is (= (proj/research-case-axis-root frozen) (:research-case-axis/root p)))
    (is (= (proj/execution-case-set-root plan) (:execution-case-set/root p)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:projection/root p)))
    (is (= p (proj/build-projection frozen plan)))
    (is (= (mapv (fn [b] {:research-case/key (:research-case/key b)
                          :execution/id (:execution/id b)})
                 (:case-bindings p))
           (mapv (fn [k e] {:research-case/key k :execution/id (:execution/id e)})
                 (range 9) plan)))
    (is (= 0 (proj/execution->research-case p (exec-id 1))))
    (is (= 8 (proj/execution->research-case p (exec-id 9))))
    (is (= [(exec-id 1)] (proj/research-case->executions p 0)))
    (is (= [(exec-id 9)] (proj/research-case->executions p 8)))
    (is (= [0 1 2 3 4 5 6 7 8] (proj/research-case-keys frozen)))))

(deftest projection-fails-closed-on-cardinality-mismatch
  (let [frozen (research/freeze-research source)]
    (is (thrown? clojure.lang.ExceptionInfo (proj/build-projection frozen (plan-with 8))))
    (is (thrown? clojure.lang.ExceptionInfo (proj/build-projection frozen (plan-with 10))))
    (is (thrown? clojure.lang.ExceptionInfo (proj/build-projection frozen [])))))

(deftest projection-anti-transplants-across-research-scopes
  (let [frozen1 (research/freeze-research source)
        frozen2 (research/freeze-research
                 (assoc-in source [:research/vary :parameter/senior-bond] [600N 900N 1500N]))
        plan (plan-with 9)
        p1 (proj/build-projection frozen1 plan)
        p2 (proj/build-projection frozen2 plan)]
    ;; both share the same execution case set E
    (is (= (:execution-case-set/root p1) (:execution-case-set/root p2)))
    ;; but bind different research scopes, so P differs
    (is (not= (:research-definition/root p1) (:research-definition/root p2)))
    (is (not= (:research-case-axis/root p1) (:research-case-axis/root p2)))
    (is (not= (:projection/root p1) (:projection/root p2)))
    (is (= (proj/verify-projection frozen1 plan p1) (proj/verify-projection frozen1 plan p1)))))

(deftest verify-projection-fails-closed-on-disagreement
  (let [frozen (research/freeze-research source)
        plan (plan-with 9)
        p (proj/build-projection frozen plan)]
    (is (true? (:projection/valid? (proj/verify-projection frozen plan p))))
    ;; tampered definition root
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/verify-projection frozen plan (assoc p :research-definition/root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))))
    ;; tampered case-axis root
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/verify-projection frozen plan (assoc p :research-case-axis/root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))))
    ;; swapped binding target (different execution-id)
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/verify-projection frozen plan (update-in p [:case-bindings 0 :execution/id] (constantly (exec-id 99))))))
    ;; stale execution case set root
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/verify-projection frozen plan (assoc p :execution-case-set/root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))))
    ;; recomputed root disagreeing with committed root
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/verify-projection frozen plan (assoc p :projection/root "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"))))))

(deftest plan-entries-carry-explicit-research-coordinates
  (let [frozen (research/freeze-research source)
        plan (plan-with 9)
        p (proj/build-projection frozen plan)
        projected (proj/project-plan-entries frozen p plan)]
    (is (= 9 (count projected)))
    (doseq [entry projected]
      (is (= (:research-definition/root frozen) (:research-definition/root entry)))
      (is (= (proj/research-case-axis-root frozen) (:research-case-axis/root entry)))
      (is (= (:projection/root p) (:research-execution-projection/root entry)))
      (is (integer? (:research-case/key entry))))
    ;; research-case/key == execution ordinal - 1 in this 1:1 pilot
    (is (= [0 1 2 3 4 5 6 7 8] (mapv :research-case/key projected)))
    ;; runner's (merge plan-entry ...) propagates research coordinates to results
    (let [result (merge (first projected) {:execution/id (:execution/id (first projected))
                                           :case/key (case-set/case-key-for-execution 1)})]
      (is (= (:research-definition/root frozen) (:research-definition/root result)))
      (is (= 0 (:research-case/key result))))
    ;; an execution outside the projection has no valid coordinate -> fail closed
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/project-plan-entries frozen p (conj plan {:execution/ordinal 10 :execution/id (exec-id 10)}))))))

(deftest research-and-runner-case-keys-are-distinct-scoped-spaces
  (let [frozen (research/freeze-research source)
        plan (plan-with 9)
        p (proj/build-projection frozen plan)
        e-root (:execution-case-set/root p)
        c-root (proj/research-case-axis-root frozen)]
    ;; runner case set E has its own :case/key space
    (is (= 0 (case-set/case-key-for-execution 1)))
    (is (= 8 (case-set/case-key-for-execution 9)))
    ;; same integer 0 in two different scopes names different things
    (is (not= {:scope/root c-root :key 0}
              {:scope/root e-root :key 0}))
    (is (= 0 (proj/execution->research-case p (exec-id 1))))))

(deftest plan-root-commits-research-lineage
  (let [frozen1 (research/freeze-research source)
        frozen2 (research/freeze-research
                 (assoc-in source [:research/vary :parameter/senior-bond] [600N 900N 1500N]))
        plan (plan-with 9)
        prepared1 (proj/prepare-research-plan frozen1 plan)
        prepared2 (proj/prepare-research-plan frozen2 plan)
        ;; a plan with no research annotation has a different (empty-lineage) root
        bare-root (proj/plan-root plan)]
    (is (= 9 (count (:plan prepared1))))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:plan-root prepared1)))
    (is (not= bare-root (:plan-root prepared1)))
    ;; same ordinary execution ids / ordinals, different research semantics -> different plan root
    (is (not= (:plan-root prepared1) (:plan-root prepared2)))
    ;; deterministic
    (is (= (:plan-root prepared1) (:plan-root (proj/prepare-research-plan frozen1 plan))))))

(deftest prepare-and-verify-research-run-projection
  (let [frozen (research/freeze-research source)
        plan (plan-with 9)
        prepared (proj/prepare-research-plan frozen plan)
        verified (proj/verify-research-run-projection frozen prepared)]
    (is (true? (:research-lineage/valid? verified)))
    (is (= (:research-definition/root frozen) (:research-definition/root verified)))
    (is (= (:plan-root prepared) (:plan-root verified)))
    ;; tampered committed plan root fails closed
    (is (thrown? clojure.lang.ExceptionInfo
                 (proj/verify-research-run-projection frozen (assoc prepared :plan-root (exec-id 999)))))
    ;; a plan whose research coordinate was stripped fails closed
    (let [stripped (update prepared :plan (fn [entries] (mapv #(dissoc % :research-definition/root) entries)))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (proj/verify-research-run-projection frozen stripped))))))

(deftest results->matrix-derives-c-x-m-from-genuine-result-shape
  (let [frozen (research/freeze-research source)
        ;; realistic runner result shape: ordinal + id + case/key, no research fields
        results (mapv (fn [ordinal]
                        {:execution/ordinal ordinal
                         :execution/id (exec-id ordinal)
                         :case/key (dec ordinal)
                         :scenario/outcome :pass})
                      (range 1 10))
        extract (fn [result] (+ 700N (* 100N (dec (:execution/ordinal result)))))
        compiled (proj/compile-research-matrix-from-results frozen results extract "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        matrix (:matrix compiled)
        projection (:projection compiled)
        e-root (:execution-case-set/root compiled)]
    (is (= 9 (count (:matrix/values matrix))))
    ;; matrix binds D + C + M (semantic axes), NOT E (runner case set)
    (is (= (:research-definition/root frozen) (:research-definition/root matrix)))
    (is (= (proj/research-case-axis-root frozen) (:case-axis/root matrix)))
    (is (not= e-root (:case-axis/root matrix)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:measure-axis/root matrix)))
    ;; the projection maps each research case to exactly one execution in this 1:1 pilot
    (is (= (mapv #(exec-id (inc %)) (range 9))
           (mapv #(nth (proj/research-case->executions projection %) 0) (range 9))))
    (is (= :deficit (get-in matrix [:matrix/values [0 0] :measure/status])))))

(deftest results->matrix-accepts-distinct-user-defined-measure-observations
  (let [frozen (research/freeze-research dual-measure-source)
        results (mapv (fn [ordinal]
                        {:execution/ordinal ordinal
                         :execution/id (exec-id ordinal)})
                      (range 1 10))
        compiled (proj/compile-research-matrix-from-results
                  frozen results
                  (fn [result]
                    {:measure/coverage-margin (+ 700N (:execution/ordinal result))
                     :measure/loss-margin (dec (:execution/ordinal result))})
                  "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
    (is (= 18 (count (:matrix/values (:matrix compiled)))))
    (is (= :deficit (get-in compiled [:matrix :matrix/values [0 0] :measure/status])))
    (is (= :satisfied (get-in compiled [:matrix :matrix/values [0 1] :measure/status])))))
