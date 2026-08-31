(ns resolver-sim.risk.risk-kernel-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.risk.limit-evaluation :as le]
            [resolver-sim.risk.limit-policy :as lp]
            [resolver-sim.risk.pro-rata-producer :as producer]
            [resolver-sim.risk.projection :as rp])
  (:import [clojure.lang ExceptionInfo]))

(def ^:private unit-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "usd-micros"})))

(def ^:private other-unit-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "usd-micros-other"})))

(def ^:private valuation-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "valuation-basis"})))

(def ^:private source-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "source-state"})))

(def ^:private other-source-root
  (hash-ref/sha256-ref (hc/domain-hash :prf-risk-projection-v1 {:fixture "other-source-state"})))

(def ^:private qa "3f3ef786b34d6dd716e1812c8b74a7a0e1f05aa5f3230588f6f5bcd00c6c8392")
(def ^:private qb "22d1fb414b6d62b69391bb42b9b94314a5b2fa77a7e8abd507cf3152ed4fa38e")
(def ^:private qc "997164d13810adf6cab52541447d083c4472e5ca6052cc6ddd9501318ac5730c")

(defn- base-projection-args []
  {:source/root source-root
   :valuation-basis/root valuation-root
   :unit/root unit-root
   :time-basis {:basis :state-derived}})

;; ── risk-projection.v1 ────────────────────────────────────────────────────

(deftest test-projection-fixture-is-valid-and-root-verifies
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows [{:exposure/id "exp-a"
                          :exposure/subject-root (hash-ref/sha256-ref qa)
                          :exposure/current 80000
                          :exposure/after 90000
                          :exposure/peak 90000
                          :exposure/domains [{:risk/domain "human/principal"
                                              :risk/member "resolver-a"}]}]))]
    (is (rp/projection-valid? p))
    (is (= :pass (:status (rp/verify-root p))))
    (is (= {:exposure/current 80000
            :exposure/after 90000
            :exposure/conservative-peak 90000}
           (:risk-projection/summary p)))
    (is (rp/exact-state-bound? p))))

(deftest test-projection-root-golden
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows [{:exposure/id "exp-a"
                          :exposure/subject-root (hash-ref/sha256-ref qa)
                          :exposure/current 1000
                          :exposure/after 1000
                          :exposure/peak 1000
                          :exposure/domains []}]))]
    (is (= (:risk-projection/root p) (rp/projection-root p)))))

(deftest test-projection-rejects-peak-below-phases
  (is (thrown-with-msg? ExceptionInfo #"invalid exposure phase amounts"
                        (rp/exposure-row
                         {:exposure/id "x" :exposure/subject-root (hash-ref/sha256-ref qa)
                          :exposure/current 100 :exposure/after 200 :exposure/peak 150
                          :exposure/domains []}))))

(deftest test-projection-rejects-duplicate-exposure-ids
  (is (thrown-with-msg? ExceptionInfo #"duplicate :exposure/id"
                        (rp/projection
                         (assoc (base-projection-args)
                                :rows [{:exposure/id "exp-a"
                                        :exposure/subject-root (hash-ref/sha256-ref qa)
                                        :exposure/current 1 :exposure/after 1 :exposure/peak 1
                                        :exposure/domains []}
                                       {:exposure/id "exp-a"
                                        :exposure/subject-root (hash-ref/sha256-ref qb)
                                        :exposure/current 2 :exposure/after 2 :exposure/peak 2
                                        :exposure/domains []}])))))

(deftest test-projection-rejects-as-of-without-at
  (is (thrown-with-msg? ExceptionInfo #":as-of time basis requires integer :at"
                        (rp/projection
                         (assoc (base-projection-args)
                                :time-basis {:basis :as-of}
                                :rows [])))))

(deftest test-projection-as-of-is-not-exact-state-bound
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :time-basis {:basis :as-of :at 1000}
                  :rows []))]
    (is (rp/projection-valid? p))
    (is (not (rp/exact-state-bound? p)))))

(deftest test-projection-member-equal-to-subject-root-normalizes-to-omitted
  (let [subject (hash-ref/sha256-ref qa)
        with-explicit (rp/projection
                       (assoc (base-projection-args)
                              :rows [{:exposure/id "a"
                                      :exposure/subject-root subject
                                      :exposure/current 1 :exposure/after 1 :exposure/peak 1
                                      :exposure/domains [{:risk/domain "human/principal"
                                                          :risk/member subject}]}]))
        with-omitted (rp/projection
                      (assoc (base-projection-args)
                             :rows [{:exposure/id "a"
                                     :exposure/subject-root subject
                                     :exposure/current 1 :exposure/after 1 :exposure/peak 1
                                     :exposure/domains [{:risk/domain "human/principal"}]}]))]
    ;; canonical interpretation: explicit member == subject root and omitted
    ;; member are SEMANTICALLY IDENTICAL -> identical committed content/root
    (is (= [{:risk/domain "human/principal"}]
           (:exposure/domains (first (:risk-projection/exposures with-explicit)))))
    (is (= (:risk-projection/root with-explicit)
           (:risk-projection/root with-omitted)))))

(deftest test-projection-deduplicates-identical-domain-entries
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows [{:exposure/id "a"
                          :exposure/subject-root (hash-ref/sha256-ref qa)
                          :exposure/current 1 :exposure/after 1 :exposure/peak 1
                          :exposure/domains [{:risk/domain "technical" :risk/member "r1"}
                                             {:risk/domain "technical" :risk/member "r1"}
                                             {:risk/domain "human/principal"}
                                             {:risk/domain "human/principal"}]}]))]
    (is (= [{:risk/domain "human/principal"}
            {:risk/domain "technical" :risk/member "r1"}]
           (:exposure/domains (first (:risk-projection/exposures p)))))
    (is (rp/projection-valid? p))))

(deftest test-projection-summary-is-purely-derived-from-rows
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows [{:exposure/id "a" :exposure/subject-root (hash-ref/sha256-ref qa)
                          :exposure/current 10 :exposure/after 20 :exposure/peak 30
                          :exposure/domains []}
                         {:exposure/id "b" :exposure/subject-root (hash-ref/sha256-ref qb)
                          :exposure/current 5 :exposure/after 7 :exposure/peak 9
                          :exposure/domains []}]))]
    (is (= {:exposure/current 15 :exposure/after 27 :exposure/conservative-peak 39}
           (:risk-projection/summary p)))
    ;; tampering the summary away from the derived values invalidates
    (is (= :fail (:status (rp/verify-root
                           (update p :risk-projection/summary
                                   assoc :exposure/conservative-peak 999)))))))

(deftest test-projection-validator-rejects-unknown-keys-at-every-level
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows [{:exposure/id "a" :exposure/subject-root (hash-ref/sha256-ref qa)
                          :exposure/current 1 :exposure/after 1 :exposure/peak 1
                          :exposure/domains [{:risk/domain "technical"}]}]))]
    (is (not (:valid? (rp/validate-projection (assoc p :intruder 1)))))
    (is (not (:valid? (rp/validate-projection
                       (update-in p [:risk-projection/exposures 0] assoc :intruder 1)))))
    (is (not (:valid? (rp/validate-projection
                       (update-in p [:risk-projection/exposures 0 :exposure/domains 0]
                                  assoc :intruder 1)))))))

;; ── risk-limit-policy.v1 ──────────────────────────────────────────────────

(defn- demo-policy
  ([] (demo-policy :conservative-peak 1000000 100000 100000))
  ([basis global domain concentration]
   (lp/policy
    {:id "demo-risk-limit-policy"
     :unit/root unit-root
     :basis basis
     :limits [{:limit/kind :global :limit/id "global-conservative-peak" :limit/amount global}
              {:limit/kind :domain :limit/id "new-implementation"
               :risk/domain "technical/new-implementation" :limit/amount domain}
              {:limit/kind :concentration :limit/id "per-resolver"
               :risk/domain "human/principal" :limit/amount concentration}]})))

(deftest test-policy-fixture-is-valid-and-root-verifies
  (let [p (demo-policy)]
    (is (lp/policy-valid? p))
    (is (= :pass (:status (lp/verify-root p))))))

(deftest test-policy-root-golden
  (let [p (demo-policy)]
    (is (= (:risk-limit-policy/root p) (lp/policy-root p)))))

(deftest test-policy-rejects-unknown-kind-and-duplicate-ids
  (is (thrown-with-msg? ExceptionInfo #"invalid risk-limit-policy limits"
                        (lp/policy
                         {:id "p" :unit/root unit-root :basis :conservative-peak
                          :limits [{:limit/kind :quantum :limit/id "x" :limit/amount 1}]})))
  (is (thrown-with-msg? ExceptionInfo #"invalid risk-limit-policy limits"
                        (lp/policy
                         {:id "p" :unit/root unit-root :basis :conservative-peak
                          :limits [{:limit/kind :global :limit/id "x" :limit/amount 1}
                                   {:limit/kind :global :limit/id "x" :limit/amount 2}]}))))

(deftest test-policy-rejects-unknown-basis-and-unknown-keys
  (is (thrown-with-msg? ExceptionInfo #"basis must be"
                        (lp/policy
                         {:id "p" :unit/root unit-root :basis :peak
                          :limits []})))
  (let [p (demo-policy)]
    (is (not (:valid? (lp/validate-policy (assoc p :backdoor true)))))
    (is (not (:valid? (lp/validate-policy
                       (update-in p [:risk-limit-policy/limits 0] assoc :intruder 1)))))))

;; ── risk-limit-evaluation.v1 ──────────────────────────────────────────────

(defn- rows-for [spec]
  (mapv (fn [[id root current after peak domains]]
          {:exposure/id id
           :exposure/subject-root (hash-ref/sha256-ref root)
           :exposure/current current
           :exposure/after after
           :exposure/peak peak
           :exposure/domains domains})
        spec))

(deftest test-evaluation-passes-globally-safe-operation
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows (rows-for
                         [["exp-a" qa 80000 90000 90000
                           [{:risk/domain "human/principal" :risk/member "resolver-a"}
                            {:risk/domain "technical"}]]])))
        e (le/evaluate p (demo-policy))]
    (is (le/evaluation-valid? e))
    (is (= :pass (:status (le/verify-root e))))
    (is (= :pass (:risk-limit-evaluation/status e)))
    (is (empty? (le/violations e)))))

(deftest test-evaluation-detects-domain-limit-violation
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows (rows-for
                         [["exp-b" qb 50000 150000 150000
                           [{:risk/domain "technical/new-implementation"}]]])))
        e (le/evaluate p (demo-policy))]
    (is (= :violation (:risk-limit-evaluation/status e)))
    (is (= ["new-implementation"] (le/violations e)))
    (let [r (->> (:risk-limit-evaluation/results e)
                 (filter #(= "new-implementation" (:limit/id %)))
                 first)]
      (is (= 150000 (:evaluated/amount r)))
      (is (= 100000 (:limit/amount r)))
      ;; global was fine
      (is (= :pass (:status (first (:risk-limit-evaluation/results e))))))))

(deftest test-evaluation-detects-principal-concentration-violation
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows (rows-for
                         [["exp-a" qa 80000 80000 80000
                           [{:risk/domain "human/principal" :risk/member "resolver-a"}]]
                          ["exp-c" qc 30000 30000 30000
                           [{:risk/domain "human/principal" :risk/member "resolver-a"}]]])))
        e (le/evaluate p (demo-policy))]
    (is (= ["per-resolver"] (le/violations e)))
    (let [r (->> (:risk-limit-evaluation/results e)
                 (filter #(= "per-resolver" (:limit/id %)))
                 first)]
      (is (= [{:risk/member "resolver-a" :exposure/amount 110000}]
             (:evaluated/members r)))
      (is (= 110000 (:evaluated/amount r))))))

(deftest test-evaluation-concentration-member-falls-back-to-subject-root
  ;; attribution without :risk/member groups under the row's subject root
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows (rows-for
                         [["exp-a" qa 80000 80000 80000
                           [{:risk/domain "human/principal"}]]
                          ["exp-c" qc 30000 30000 30000
                           [{:risk/domain "human/principal"}]]])))
        e (le/evaluate p (demo-policy))]
    (is (= :pass (:risk-limit-evaluation/status e)))
    (let [r (->> (:risk-limit-evaluation/results e)
                 (filter #(= "per-resolver" (:limit/id %)))
                 first)]
      (is (= [{:risk/member (hash-ref/sha256-ref qa) :exposure/amount 80000}
              {:risk/member (hash-ref/sha256-ref qc) :exposure/amount 30000}]
             (:evaluated/members r))))))

(deftest test-evaluation-detects-conservative-peak-violation-despite-acceptable-final
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows (rows-for
                         [["exp-a" qa 500000 700000 950000
                           [{:risk/domain "human/principal" :risk/member "resolver-a"}]]])))
        peak-e (le/evaluate p (demo-policy :conservative-peak 1000000 100000 100000))]
    (is (= ["per-resolver"] (le/violations peak-e)))
    (is (= 950000 (:evaluated/amount
                   (->> (:risk-limit-evaluation/results peak-e)
                        (filter #(= "per-resolver" (:limit/id %)))
                        first))))
    ;; final exposure 700k WOULD pass an :after-basis 800k policy — the
    ;; conservative-peak basis is what catches the transient 950k.
    (is (= :pass (:risk-limit-evaluation/status
                  (le/evaluate p (lp/policy
                                  {:id "after-800k" :unit/root unit-root :basis :after
                                   :limits [{:limit/kind :global :limit/id "g"
                                             :limit/amount 800000}]})))))))

(deftest test-evaluation-requires-unit-root-match
  (let [p (rp/projection (assoc (base-projection-args) :rows []))]
    (is (thrown-with-msg? ExceptionInfo #"units differ"
                          (le/evaluate p (lp/policy
                                          {:id "p" :unit/root other-unit-root :basis :after
                                           :limits []}))))))

(deftest test-evaluation-verifies-supplied-policy-and-projection-roots
  (let [p (rp/projection (assoc (base-projection-args) :rows []))
        policy (demo-policy)
        tampered-projection (assoc p :risk-projection/root
                                   (hash-ref/sha256-ref qa))
        tampered-policy (assoc policy :risk-limit-policy/root
                               (hash-ref/sha256-ref qb))]
    (is (thrown-with-msg? ExceptionInfo #"projection root verification failed"
                          (le/evaluate tampered-projection policy)))
    (is (thrown-with-msg? ExceptionInfo #"policy root verification failed"
                          (le/evaluate p tampered-policy)))))

(deftest test-evaluation-is-fail-closed-on-shape-and-unknown-keys
  (let [p (rp/projection (assoc (base-projection-args) :rows []))
        policy (demo-policy)
        tampered (assoc p :intruder-key 1)]
    (is (thrown-with-msg? ExceptionInfo #"evaluation requires a valid projection"
                          (le/evaluate tampered policy)))
    (is (thrown-with-msg? ExceptionInfo #"evaluation requires a valid policy"
                          (le/evaluate p (assoc policy :intruder-key 1))))
    (let [e (le/evaluate p policy)]
      (is (not (:valid? (le/validate-evaluation (assoc e :intruder 1)))))
      (is (= :fail (:status (le/verify-root (assoc e :risk-limit-evaluation/root
                                                   (hash-ref/sha256-ref qc)))))))))

(deftest test-evaluation-bound-requires-exact-candidate-state-binding
  (let [p (rp/projection (assoc (base-projection-args) :rows []))
        policy (demo-policy)]
    ;; exact binding: candidate-state-root == source-root on an
    ;; exact-state-bound projection passes
    (is (= :pass (:risk-limit-evaluation/status
                  (le/evaluate-bound p policy source-root))))
    ;; mismatched candidate root fails closed
    (is (thrown-with-msg? ExceptionInfo #"does not match"
                          (le/evaluate-bound p policy other-source-root)))
    ;; as-of (observational) projection is never live admission evidence
    (let [obs (rp/projection (assoc (base-projection-args)
                                    :time-basis {:basis :as-of :at 1000}
                                    :rows []))]
      (is (thrown-with-msg? ExceptionInfo #"exact-state-bound"
                            (le/evaluate-bound obs policy source-root))))))

;; ── pro-rata producer ─────────────────────────────────────────────────────

(defn- pro-rata-projection
  [state-before stages attribution]
  (producer/exposure-projection
   {:time-basis {:basis :state-derived}
    :state-before state-before
    :stages stages
    :attribution attribution}
   {:source-root source-root
    :valuation-basis-root valuation-root
    :unit-root unit-root}))

(defn- state-before-root [state]
  (hash-ref/sha256-ref (effects/state-root state)))

(deftest test-producer-globally-safe-operation
  (let [p (pro-rata-projection
           {qa 80000}
           [[(effects/delta qa 10000)]]
           {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}
                {:risk/domain "technical"}]})
        e (le/evaluate p (demo-policy))]
    (is (rp/projection-valid? p))
    (is (= 1 (count (:risk-projection/exposures p))))
    (is (= {:exposure/current 80000 :exposure/after 90000
            :exposure/conservative-peak 90000}
           (:risk-projection/summary p)))
    (is (= :pass (:risk-limit-evaluation/status e)))))

(deftest test-producer-defaults-source-root-to-canonical-state-root
  (let [p (producer/exposure-projection
           {:time-basis {:basis :state-derived}
            :state-before {qa 80000}
            :stages [[(effects/delta qa 10000)]]}
           {:valuation-basis-root valuation-root
            :unit-root unit-root})]
    (is (rp/exact-state-bound? p))
    (is (= (state-before-root {qa 80000}) (:risk-projection/source-root p)))
    ;; and a live-admission evaluation binds to that exact state
    (let [e (le/evaluate-bound p (demo-policy) (state-before-root {qa 80000}))]
      (is (= :pass (:risk-limit-evaluation/status e))))))

(deftest test-producer-domain-violation-from-effects
  (let [p (pro-rata-projection
           {qb 50000}
           [[(effects/delta qb 100000)]]
           {qb [{:risk/domain "technical/new-implementation"}]})
        e (le/evaluate p (demo-policy))]
    (is (= ["new-implementation"] (le/violations e)))
    (is (= :pass (:status (first (:risk-limit-evaluation/results e)))))))

(deftest test-producer-concentration-violation-across-subjects
  (let [p (pro-rata-projection
           {qa 80000 qc 30000}
           [[(effects/delta qa 5000) (effects/delta qc 5000)]]
           {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}]
            qc [{:risk/domain "human/principal" :risk/member "resolver-a"}]})
        e (le/evaluate p (demo-policy))]
    (is (= ["per-resolver"] (le/violations e)))))

(deftest test-producer-conservative-peak-violation-despite-acceptable-final
  ;; Fill 450k of new claims (950k outstanding), then settle 250k (700k).
  (let [p (pro-rata-projection
           {qa 500000}
           [[(effects/delta qa 450000)]
            [(effects/delta qa -250000)]]
           {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}]})]
    (is (rp/projection-valid? p))
    ;; per-row peak (950k) is the EXACT peak of that subject along the path;
    ;; summary conservative-peak equals it because there is one row
    (is (= {:exposure/current 500000 :exposure/after 700000
            :exposure/conservative-peak 950000}
           (:risk-projection/summary p)))
    (let [peak-e (le/evaluate p (lp/policy
                                 {:id "peak-800k" :unit/root unit-root
                                  :basis :conservative-peak
                                  :limits [{:limit/kind :global :limit/id "g"
                                            :limit/amount 800000}]}))]
      (is (= :violation (:risk-limit-evaluation/status peak-e)))
      (is (= ["g"] (le/violations peak-e))))
    ;; An :after-basis policy would pass — demonstrating why
    ;; :conservative-peak is required.
    (let [after-e (le/evaluate p (lp/policy
                                  {:id "after-800k" :unit/root unit-root :basis :after
                                   :limits [{:limit/kind :global :limit/id "g"
                                             :limit/amount 800000}]}))]
      (is (= :pass (:risk-limit-evaluation/status after-e))))))

(deftest test-producer-identical-inputs-yield-identical-root
  (let [a (pro-rata-projection
           {qa 80000 qc 30000}
           [[(effects/delta qa 5000) (effects/delta qc 5000)]]
           {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}]
            qc [{:risk/domain "technical"}]})
        b (pro-rata-projection
           {qa 80000 qc 30000}
           [[(effects/delta qa 5000) (effects/delta qc 5000)]]
           {qa [{:risk/domain "human/principal" :risk/member "resolver-a"}]
            qc [{:risk/domain "technical"}]})]
    (is (= (:risk-projection/root a) (:risk-projection/root b)))
    (is (= a b))))

(deftest test-producer-effect-ordering-changes-state-and-root
  ;; Same state-after, different intermediate states -> different per-row peak
  ;; and different committed root. Within a stage effects are composed; across
  ;; stages order is meaningful.
  (let [before {qa 100}
        up-then-down (pro-rata-projection before
                                          [[(effects/delta qa 100)]
                                           [(effects/delta qa -50)]]
                                          {})
        down-then-up (pro-rata-projection before
                                          [[(effects/delta qa -50)]
                                           [(effects/delta qa 100)]]
                                          {})]
    (is (= 150 (:exposure/after (first (:risk-projection/exposures up-then-down)))))
    (is (= 150 (:exposure/after (first (:risk-projection/exposures down-then-up)))))
    ;; paths [100,200,150] vs [100,50,150]: both end at 150, different peaks
    (is (= 200 (:exposure/peak (first (:risk-projection/exposures up-then-down)))))
    (is (= 150 (:exposure/peak (first (:risk-projection/exposures down-then-up)))))
    (is (not= (:risk-projection/root up-then-down)
              (:risk-projection/root down-then-up)))))

(deftest test-producer-rejects-underflowing-operation
  (is (thrown-with-msg? ExceptionInfo #"underflow"
                        (pro-rata-projection
                         {qa 100}
                         [[(effects/delta qa -200)]]
                         {}))))

(deftest test-producer-attribution-fn
  (let [p (producer/exposure-projection
           {:time-basis {:basis :state-derived}
            :state-before {qa 1000}
            :attribution-fn (fn [q] (when (= q qa)
                                      [{:risk/domain "infrastructure"}]))}
           {:source-root source-root
            :valuation-basis-root valuation-root
            :unit-root unit-root})]
    (is (= ["infrastructure"]
           (mapv :risk/domain (:exposure/domains (first (:risk-projection/exposures p))))))))

;; ── tampering invalidates canonical identity ──────────────────────────────

(deftest test-tampering-invalidates-projection-root
  (let [p (rp/projection
           (assoc (base-projection-args)
                  :rows (rows-for
                         [["exp-a" qa 100 200 300
                           [{:risk/domain "technical" :risk/member "resolver-a"}]]])))]
    (is (= :pass (:status (rp/verify-root p))))
    (doseq [[label tamper]
            [["source-root" (assoc p :risk-projection/source-root other-source-root)]
             ["unit-root" (assoc p :risk-projection/unit-root other-unit-root)]
             ["valuation-basis-root"
              (assoc p :risk-projection/valuation-basis-root other-unit-root)]
             ["row current" (update-in p [:risk-projection/exposures 0]
                                       assoc :exposure/current 101)]
             ["row after" (update-in p [:risk-projection/exposures 0]
                                     assoc :exposure/after 201)]
             ["row peak" (update-in p [:risk-projection/exposures 0]
                                    assoc :exposure/peak 301)]
             ["subject-root" (update-in p [:risk-projection/exposures 0]
                                        assoc :exposure/subject-root (hash-ref/sha256-ref qb))]
             ["domain attribution" (update-in p [:risk-projection/exposures 0 :exposure/domains]
                                              (constantly [{:risk/domain "infrastructure"}]))]]]
      (is (= :fail (:status (rp/verify-root tamper))) (str label))
      (is (not= (:risk-projection/root p) (rp/projection-root tamper))
          (str label)))))