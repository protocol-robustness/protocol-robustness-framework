(ns resolver-sim.economics.payoffs-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.util.concurrent CountDownLatch]))

(deftest runtime-claimant-parallelism-is-captured-before-executor-submission
  (let [items (mapv (fn [i] {:id (keyword (str "claim-" i)) :weight 1}) (range 16))
        worker-threads (atom #{})
        started (CountDownLatch. 2)
        serial (payoffs/allocate-pro-rata {:amount 101 :items items
                                            :ordering-policy :canonical-id
                                            :rounding :floor-with-largest-remainder})
        parallel (binding [payoffs/*pro-rata-parallelism* 2
                           payoffs/*pro-rata-parallel-threshold* 1]
                   (payoffs/allocate-pro-rata
                    {:amount 101
                     :items items
                     :ordering-policy :canonical-id
                     :rounding :floor-with-largest-remainder
                     ;; This claimant-local accessor executes in the detached
                     ;; worker, while the dynamic budget was captured before
                     ;; executor submission.
                     :weight-fn (fn [item]
                                  (swap! worker-threads conj (.getName (Thread/currentThread)))
                                  ;; The first two submitted rows cannot both
                                  ;; reach this point on one worker.
                                  (when (< (Long/parseLong (subs (name (:id item)) 6)) 2)
                                    (.countDown started)
                                    (.await started))
                                  (:weight item))}))]
    (is (= serial parallel))
    (is (> (count @worker-threads) 1)
        (str "Expected bounded detached workers, got " @worker-threads))
    (is (= (hc/hash-with-intent {:hash/intent :projection-artifact} serial)
           (hc/hash-with-intent {:hash/intent :projection-artifact} parallel)))))

(deftest capped-redistribution-round-trace-is-identical-under-claimant-parallelism
  (let [items [{:id :a :weight 100 :cap 10}
               {:id :b :weight 100 :cap 10}
               {:id :c :weight 100 :cap 35}
               {:id :d :weight 100 :cap nil}]
        request {:amount 100
                 :items items
                 :id-fn :id :weight-fn :weight :cap-fn :cap
                 :rounding :floor-with-largest-remainder
                 :ordering-policy :canonical-id}
        serial (payoffs/allocate-pro-rata-with-redistribution request)
        parallel (binding [payoffs/*pro-rata-parallelism* 2
                           payoffs/*pro-rata-parallel-threshold* 1]
                   (payoffs/allocate-pro-rata-with-redistribution request))]
    ;; This compares allocations, every committed active-set pass, residual
    ;; metadata, and canonical policy fields—not merely aggregate totals.
    (is (= serial parallel))
    (is (= (:redistribution serial) (:redistribution parallel)))
    (is (= (hc/hash-with-intent {:hash/intent :projection-artifact} serial)
           (hc/hash-with-intent {:hash/intent :projection-artifact} parallel)))))

(deftest claimant-threshold-and-observer-fallback-preserve-allocation-semantics
  (let [run (fn [n opts]
              (let [threads (atom #{})
                    items (mapv (fn [i] {:id i :weight 1}) (range n))
                    result (binding [payoffs/*pro-rata-parallel-threshold* 16]
                             (payoffs/allocate-pro-rata
                              (merge {:amount (+ n 3)
                                      :items items
                                      :rounding :floor-with-largest-remainder
                                      :ordering-policy :input-order
                                      :weight-fn (fn [item]
                                                   (swap! threads conj (.getName (Thread/currentThread)))
                                                   (:weight item))}
                                     opts)))]
                {:result result :threads @threads}))
        serial-15 (run 15 {:parallelism 2})
        serial-16 (run 16 {:parallelism 1})
        parallel-16 (run 16 {:parallelism 2})
        parallel-17 (run 17 {:parallelism 2})
        progress (payoffs/make-pro-rata-progress-atom)
        with-observer (run 17 {:parallelism 2
                               :progress-atom progress})]
    ;; Below threshold and explicit p=1 both use one physical worker; the
    ;; semantic result remains the same as the parallel-eligible branch.
    (is (= 1 (count (:threads serial-15))))
    (is (= 1 (count (:threads serial-16))))
    (is (= (:result serial-16) (:result parallel-16)))
    (is (= (:result parallel-16)
           (:result (run 16 {:parallelism 8}))))
    (is (= (:result parallel-17)
           (:result (run 17 {:parallelism 1}))))
    ;; Observers intentionally force compatibility/serial execution.
    (is (= 1 (count (:threads with-observer))))
    (is (= :completed (:status @progress)))
    (is (= (:result parallel-17) (:result with-observer)))))

(deftest forced-claimant-completion-order-cannot-affect-tie-break-policy
  (let [run (fn [items ordering-policy release-order]
              (let [ids (mapv :id items)
                    started (CountDownLatch. (count items))
                    gates (zipmap ids (repeatedly (count items) promise))
                    completed-gates (zipmap ids (repeatedly (count items) promise))
                    completed (atom [])
                    releaser (doto (Thread.
                                    (fn []
                                      (.await started)
                                      (doseq [id release-order]
                                        (deliver (get gates id) true)
                                        @(get completed-gates id))))
                                   (.start))
                    result (binding [payoffs/*pro-rata-parallelism* 4
                                     payoffs/*pro-rata-parallel-threshold* 1]
                             (payoffs/allocate-pro-rata
                              {:amount 2
                               :items items
                               :id-fn :id
                               :cap-fn (constantly nil)
                               :weight-fn (fn [item]
                                            (.countDown started)
                                            (.await started)
                                            @(get gates (:id item))
                                            (swap! completed conj (:id item))
                                            (deliver (get completed-gates (:id item)) true)
                                            1)
                               :rounding :floor-with-largest-remainder
                               :ordering-policy ordering-policy}))]
                (.join releaser 10000)
                {:result result :completed @completed}))
        canonical-items [{:id :a} {:id :b} {:id :c} {:id :d}]
        input-items [{:id :d} {:id :c} {:id :b} {:id :a}]
        canonical (run canonical-items :canonical-id [:d :c :b :a])
        input-order (run input-items :input-order [:a :b :c :d])]
    (testing "canonical ID tie-break ignores forced reverse completion"
      (is (= [:d :c :b :a] (:completed canonical)))
      (is (= [:a :b :c :d] (mapv :id (get-in canonical [:result :allocations]))))
      (is (= [:a :b]
             (mapv :id (filter #(pos? (:allocated %))
                               (get-in canonical [:result :allocations]))))))
    (testing "input-order tie-break preserves original ordinal, not completion order"
      (is (= [:a :b :c :d] (:completed input-order)))
      (is (= [:d :c :b :a] (mapv :id (get-in input-order [:result :allocations]))))
      (is (= [:d :c]
             (mapv :id (filter #(pos? (:allocated %))
                               (get-in input-order [:result :allocations]))))))))

(deftest callback-progress-observers-force-serial-claimant-execution
  (let [items (mapv (fn [i] {:id i :weight 1}) (range 17))
        run (fn [opts]
              (let [threads (atom #{})
                    result (binding [payoffs/*pro-rata-parallel-threshold* 1]
                             (payoffs/allocate-pro-rata
                              (merge {:amount 23
                                      :items items
                                      :rounding :floor-with-largest-remainder
                                      :weight-fn (fn [item]
                                                   (swap! threads conj (.getName (Thread/currentThread)))
                                                   (:weight item))}
                                     opts)))]
                {:result result :threads @threads}))
        callback-events (atom [])
        callback-only (run {:parallelism 2
                            :on-progress #(swap! callback-events conj %)})
        atom-progress (payoffs/make-pro-rata-progress-atom)
        both-events (atom [])
        both (run {:parallelism 2
                   :progress-atom atom-progress
                   :on-progress #(swap! both-events conj %)})]
    (is (= 1 (count (:threads callback-only))))
    (is (= 1 (count (:threads both))))
    (is (seq @callback-events))
    ;; Existing observer precedence is :on-progress over :progress-atom.
    (is (seq @both-events))
    (is (= :pending (:status @atom-progress)))
    (is (= (:result callback-only) (:result both)))))

(deftest allocate-pro-rata-equal-weights
  (testing "equal weights split evenly"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 50
                   :items [{:id :a :weight 100}
                           {:id :b :weight 100}]})]
      (is (= [{:id :a :allocated 25 :unmet 0 :weight 100 :cap nil}
              {:id :b :allocated 25 :unmet 0 :weight 100 :cap nil}]
             (:allocations result)))
      (is (= 50 (:total-requested result)))
      (is (= 50 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-reports-caller-owned-progress
  (let [progress (payoffs/make-pro-rata-progress-atom)
        result (payoffs/allocate-pro-rata
                {:amount 10
                 :items [{:id :a :weight 1}
                         {:id :b :weight 1}
                         {:id :c :weight 1}]
                 :progress-atom progress})]
    (is (= [3 3 3] (mapv :allocated (:allocations result))))
    (is (= {:status :completed
            :phase :completed
            :current 3
            :total 3}
           @progress))))

(deftest evaluate-pro-rata-allocation-builds-pure-canonical-package
  (let [events (atom [])
        request {:schema-version 1
                 :allocation/id :allocation/example-1
                 :amount 100
                 :unit {:asset :test-token :decimals 0}
                 :participants [{:id :a :weight 40}
                                {:id :b :weight 60}]
                 :policy {:rounding :floor-with-largest-remainder
                          :cap-treatment :unallocated
                          :tie-break :input-order}
                 :on-progress #(swap! events conj %)}
        evaluated (payoffs/evaluate-pro-rata-allocation request)
        replayed (payoffs/evaluate-pro-rata-allocation (dissoc request :on-progress))]
    (is (= [40 60] (mapv :allocated (get-in evaluated [:allocation :allocations]))))
    (is (= :passed (get-in evaluated [:validation :status])))
    (is (= :partial (get-in evaluated [:validation :coverage-status])))
    (is (= 1 (get-in evaluated [:validation :not-evaluated-check-count])))
    (is (= (:result evaluated) (:result replayed))
        "runtime observer identity and events do not change canonical output")
    (is (string? (get-in evaluated [:projection :artifact/hash])))
    (is (string? (get-in evaluated [:result :artifact/hash])))
    (is (= :completed (:status (last @events))))))

(deftest evaluate-pro-rata-allocation-rejects-function-valued-request
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"cannot contain functions"
                        (payoffs/evaluate-pro-rata-allocation
                         {:amount 10
                          :participants [{:id :a :weight 10}]
                          :policy {}
                          :source {:derived (fn [_] 1)}}))))

(deftest evaluate-pro-rata-allocation-rejects-invalid-canonical-participants
  (doseq [request [{:amount 10 :unit :unit :participants [{:id :a :weight 1} {:id :a :weight 2}]}
                   {:amount -1 :unit :unit :participants [{:id :a :weight 1}]}
                   {:amount 10 :unit :unit :participants [{:id :a :weight -1}]}
                   {:amount 10 :unit :unit :participants [{:id :a :weight 0}]}]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (payoffs/evaluate-pro-rata-allocation request)))))

(deftest allocate-pro-rata-unequal-weights
  (testing "unequal weights allocate proportionally"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 400
                   :items [{:id :a :weight 1000}
                           {:id :b :weight 500}
                           {:id :c :weight 500}]})]
      (is (= [200 100 100] (mapv :allocated (:allocations result))))
      (is (= 400 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-zero-weight-items
  (testing "zero-weight items receive no allocation while positive-weight items can receive all funds"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 50
                   :items [{:id :a :weight 100}
                           {:id :b :weight 0}]})]
      (is (= [50 0] (mapv :allocated (:allocations result))))
      (is (= [100 0] (mapv :weight (:allocations result))))
      (is (= 50 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))))
  (testing "all zero weights leave the amount unallocated as remainder"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 100
                   :items [{:id :a :weight 0}
                           {:id :b :weight 0}]})]
      (is (= [0 0] (mapv :allocated (:allocations result))))
      (is (= 0 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 100 (:remainder result))))))

(deftest allocate-pro-rata-capped-allocation
  (testing "caps limit individual allocations and record unmet amount"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 400
                   :items [{:id :a :weight 1000 :cap 1000}
                           {:id :b :weight 500 :cap 60}
                           {:id :c :weight 500 :cap 500}]
                   :cap-fn :cap})]
      (is (= [200 60 100] (mapv :allocated (:allocations result))))
      (is (= [0 40 0] (mapv :unmet (:allocations result))))
      (is (= 360 (:total-allocated result)))
      (is (= 40 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-dust-and-remainder-behavior
  (testing "default floor rounding leaves integer dust in remainder"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 10
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]})]
      (is (= [3 3 3] (mapv :allocated (:allocations result))))
      (is (= 9 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 1 (:remainder result)))))
  (testing "largest-remainder rounding distributes dust deterministically by input order"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 10
                   :rounding :floor-with-largest-remainder
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]})]
      (is (= [4 3 3] (mapv :allocated (:allocations result))))
      (is (= 10 (:total-allocated result)))
      (is (= 0 (:total-unmet result)))
      (is (= 0 (:remainder result))))))

(deftest allocate-pro-rata-never-produces-negative-allocations
  (testing "negative amount, weights, and caps are clamped so allocations stay non-negative"
    (let [result (payoffs/allocate-pro-rata
                  {:amount 100
                   :items [{:id :a :weight -10 :cap 50}
                           {:id :b :weight 10 :cap -1}]
                   :cap-fn :cap})]
      (is (every? #(<= 0 (:allocated %)) (:allocations result)))
      (is (every? #(<= 0 (:unmet %)) (:allocations result)))
      (is (= [0 0] (mapv :allocated (:allocations result))))
      (is (= 100 (:total-unmet result)))))
  (testing "negative requested amount becomes zero"
    (let [result (payoffs/allocate-pro-rata
                  {:amount -100
                   :items [{:id :a :weight 10}]})]
      (is (= 0 (:total-requested result)))
      (is (= 0 (:total-allocated result)))
      (is (= 0 (:total-unmet result))))))

(deftest allocate-pro-rata-validates-inputs-and-policies
  (testing "unsupported policies fail explicitly"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported pro-rata rounding policy"
                          (payoffs/allocate-pro-rata {:amount 10
                                                      :rounding :bankers
                                                      :items [{:id :a :weight 1}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unsupported pro-rata remainder policy"
                          (payoffs/allocate-pro-rata {:amount 10
                                                      :remainder-policy :redistribute
                                                      :items [{:id :a :weight 1}]}))))
  (testing "fractional amounts and weights are rejected rather than truncated"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected an integer amount"
                          (payoffs/allocate-pro-rata {:amount 10.5
                                                      :items [{:id :a :weight 1}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Expected an integer amount"
                          (payoffs/allocate-pro-rata {:amount 10
                                                      :items [{:id :a :weight 1.5}]})))))

(deftest allocate-pro-rata-conservation
  (testing "requested = allocated + unmet + remainder"
    (doseq [spec [{:amount 400
                   :items [{:id :a :weight 1000 :cap 1000}
                           {:id :b :weight 500 :cap 60}
                           {:id :c :weight 500 :cap 500}]
                   :cap-fn :cap}
                  {:amount 10
                   :items [{:id :a :weight 1}
                           {:id :b :weight 1}
                           {:id :c :weight 1}]}
                  {:amount 100
                   :items [{:id :a :weight 0}]}]]
      (let [result (payoffs/allocate-pro-rata spec)]
        (is (= (:total-requested result)
               (+ (:total-allocated result)
                  (:total-unmet result)
                  (:remainder result))))))))

(deftest build-projection-artifact-validates-phase-4-contract
  (testing "projection artifacts are stable, canonical-safe, registered, summarized, and complete"
    (let [input {:amount 10
                 :rounding :floor-with-largest-remainder
                 :items [{:id :a :weight 1}
                         {:id :b :weight 1}
                         {:id :c :weight 1}]}
          artifact (payoffs/build-projection-artifact
                    input
                    {:source {:source/type :unit-test
                              :world-hash "sha256:test-world"}})
          artifact-again (payoffs/build-projection-artifact
                          input
                          {:source {:source/type :unit-test
                                    :world-hash "sha256:test-world"}})]
      (is (= (:projection-hash artifact) (:projection-hash artifact-again)))
      (is (= (:projection-hash artifact)
             (hc/hash-with-intent {:hash/intent :projection-artifact}
                                  (dissoc artifact :projection-hash))))
      (is (nil? (hc/validate-canonical-value! artifact)))
      (is (payoffs/registered-intent (get-in artifact [:intent :id])))
      (is (payoffs/registered-projection-definition (:projection-definition-id artifact)))
      (is (= {:participant-count 3
              :eligible-count 3
              :total-weight 3N
              :total-obligation 10N}
             (:summary artifact)))
      (is (= [:a :b :c] (get-in artifact [:projection :participants])))
      (is (= [:a :b :c] (get-in artifact [:projection :eligible-participants])))
      (is (= {:a 1N :b 1N :c 1N} (get-in artifact [:projection :weights])))
      (is (= 10N (get-in artifact [:projection :total-obligation])))
      (is (= :floor-with-largest-remainder
             (get-in artifact [:projection :constraints :rounding])))
      (is (seq (:claims artifact)))
      (is (:source-hash (:source artifact))))))

(deftest projection-artifact-rejects-tampered-source-hash
  (let [artifact (payoffs/build-projection-artifact
                  {:amount 10 :items [{:id :a :weight 1}]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"source hash mismatch"
                          (payoffs/calculate-prorata-from-projection
                           (assoc-in artifact [:source :source-hash] "tampered"))))))

(deftest calculate-prorata-from-projection-shadows-direct-allocation
  (testing "projection artifacts replay to the same generic pro-rata allocation as direct input"
    (doseq [input [{:amount 400
                    :items [{:id :a :weight 1000 :cap 1000}
                            {:id :b :weight 500 :cap 60}
                            {:id :c :weight 500 :cap 500}]
                    :cap-fn :cap
                    :rounding :floor-with-largest-remainder
                    :remainder-policy :unallocated
                    :ordering-policy :input-order}
                   {:amount 10
                    :items [{:id :a :weight 1}
                            {:id :b :weight 1}
                            {:id :c :weight 1}]
                    :rounding :floor-with-largest-remainder
                    :remainder-policy :unallocated
                    :ordering-policy :input-order}
                   {:amount 100
                    :items [{:id :a :weight 0}
                            {:id :b :weight 0}]
                    :rounding :floor-with-largest-remainder
                    :remainder-policy :unallocated
                    :ordering-policy :input-order}]]
      (let [artifact (payoffs/build-projection-artifact input)
            direct (payoffs/allocate-pro-rata input)
            from-projection (payoffs/calculate-prorata-from-projection artifact)]
        (is (= direct from-projection))))))

(deftest payoffs-namespace-remains-protocol-generic
  (testing "generic economics has no namespace dependency on resolver-sim.protocols.sew"
    (let [source (slurp "src/resolver_sim/economics/payoffs.clj")]
      (is (not (str/includes? source "resolver-sim.protocols.sew")))
      (doseq [forbidden ["escrow" "slashable-stake" "liable-parties" "workflow"
                         "appeal" "bond" "governance" "junior" "senior"
                         "fraud" "reversal" "timeout"]]
        (is (not (str/includes? (str/lower-case source) forbidden))
            (str "payoffs.clj should not contain protocol term: " forbidden))))))

(deftest generic-basis-point-accounting
  (testing "basis point amounts use integer-safe truncation"
    (is (= 15 (payoffs/calculate-bps-amount 1000 150)))
    (is (= 0 (payoffs/calculate-bps-amount 99 1)))))

(deftest generic-net-after-bps-fee
  (testing "fee and net are reported without domain-specific naming"
    (is (= {:fee 15 :net 985}
           (payoffs/calculate-net-after-bps-fee 1000 150)))))

(deftest generic-capacity-limit
  (testing "capacity limit is generic scalar multiplication"
    (is (= 1000.0 (payoffs/calculate-capacity-limit 1000)))
    (is (= 1500.0 (payoffs/calculate-capacity-limit 1000 1.5)))))

;; ── Artifact identity and concept hash tests ───────────────────────────

(deftest projection-artifact-claims-include-concept-hash
  (testing "projection artifact claims entries have :claim-definition-concept-hash alongside :claim-definition-hash"
    (let [artifact (payoffs/build-projection-artifact
                    {:amount 100
                     :items [{:id :a :weight 100} {:id :b :weight 100}]
                     :id-fn :id
                     :weight-fn :weight})
          claims (:claims artifact)]
      (is (seq claims) "projection artifact has claims")
      (is (every? :claim-definition-concept-hash claims) "each claim has :claim-definition-concept-hash")
      (is (every? string? (map :claim-definition-concept-hash claims)) "concept-hash values are strings")
      (is (every? :claim-definition-hash claims) "still has :claim-definition-hash")
      (is (every? #(not= (:claim-definition-concept-hash %) (:claim-definition-hash %)) claims)
          "concept-hash differs from canonical claim-definition-hash in each claim"))))

(deftest execution-artifact-binds-a-validated-evaluation-package
  (let [evaluation (payoffs/evaluate-pro-rata-allocation
                    {:amount 10
                     :unit :abstract-claim
                     :participants [{:id :a :weight 1}]
                     :policy {}})
        artifact (payoffs/build-pro-rata-allocation-result-artifact
                  {:projection-artifact (get-in evaluation [:projection :artifact/value])
                   :evaluation evaluation
                   :world-before-hash "before"
                   :world-after-hash "after"
                   :action-hash "action"
                   :action-hash-at "action-at"})]
    (is (= (get-in evaluation [:result :artifact/hash])
           (:evaluation-result-hash artifact)))
    (is (= (:allocation evaluation) (:allocation-result artifact)))))

(deftest allocation-result-hash-changes-with-allocation-input
  (testing "changing allocation-input changes the allocation result hash"
    (let [base-opts {:projection-artifact (payoffs/build-projection-artifact
                                           {:amount 50
                                            :items [{:id :a :weight 100} {:id :b :weight 100}]
                                            :id-fn :id
                                            :weight-fn :weight})
                     :allocation-result (payoffs/allocate-pro-rata
                                         {:amount 50
                                          :items [{:id :a :weight 100} {:id :b :weight 100}]})}]
      (let [r1 (payoffs/build-pro-rata-allocation-result-artifact
                (assoc base-opts :allocation-input {:source :input-a}))
            r2 (payoffs/build-pro-rata-allocation-result-artifact
                (assoc base-opts :allocation-input {:source :input-b}))]
        (is (not= (:allocation-result-hash r1) (:allocation-result-hash r2))
            "different allocation-input produces different result hash")))))

;; ── Allocate pro-rata with redistribution ──────────────────────────────

(deftest allocate-pro-rata-with-redistribution-redistributes
  (testing "capped items release excess to uncapped items"
    (let [result (payoffs/allocate-pro-rata-with-redistribution
                  {:amount 100
                   :items [{:id :a :weight 100 :cap 30}
                           {:id :b :weight 100 :cap nil}
                           {:id :c :weight 100 :cap nil}]
                   :id-fn :id :weight-fn :weight :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      (is (= 100 (:total-allocated result))
          "all available liquidity allocated")
      (is (= 0 (:total-unmet result))
          "no unmet after redistribution")
      (is (= 30 (:allocated (first (filter #(= :a (:id %)) (:allocations result)))))
          "capped item a receives exactly cap")
      (is (some? (:redistribution result))
          "redistribution metadata present"))))

(deftest allocate-pro-rata-with-redistribution-no-caps-identical
  (testing "without caps, result matches allocate-pro-rata"
    (let [items [{:id :a :weight 100} {:id :b :weight 100} {:id :c :weight 100}]
          with-redist (payoffs/allocate-pro-rata-with-redistribution
                       {:amount 100 :items items
                        :id-fn :id :weight-fn :weight :cap-fn (constantly nil)
                        :rounding :floor-with-largest-remainder})
          vanilla (payoffs/allocate-pro-rata
                   {:amount 100 :items items
                    :id-fn :id :weight-fn :weight :cap-fn (constantly nil)
                    :rounding :floor-with-largest-remainder})]
      (is (= (:total-allocated vanilla) (:total-allocated with-redist)))
      (is (= (mapv :allocated (:allocations vanilla))
             (mapv :allocated (:allocations with-redist)))))))

(deftest redistribution-respects-residual-capacity-across-passes
  (let [items [{:id :a :weight 1 :cap 140}
               {:id :b :weight 1 :cap 50}
               {:id :c :weight 1 :cap 110}]
        result (payoffs/allocate-pro-rata-with-redistribution
                {:amount 300
                 :items items
                 :id-fn :id :weight-fn :weight :cap-fn :cap
                 :rounding :floor-with-largest-remainder})
        allocations (into {} (map (juxt :id :allocated) (:allocations result)))]
    (is (every? (fn [{:keys [id cap]}] (<= (get allocations id 0) cap)) items))
    (is (= 140 (get allocations :a)))
    (is (= 50 (get allocations :b)))
    (is (= 110 (get allocations :c)))))

(deftest allocate-pro-rata-with-redistribution-all-capped
  (testing "all items capped => no redistribution, remainder reported"
    (let [result (payoffs/allocate-pro-rata-with-redistribution
                  {:amount 100
                   :items [{:id :a :weight 100 :cap 10}
                           {:id :b :weight 100 :cap 10}]
                   :id-fn :id :weight-fn :weight :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      (is (= 20 (:total-allocated result))
          "each capped at 10")
      (is (= 80 (:total-unmet result))
          "unmet = remainder from caps")
      (is (nil? (:redistribution result))
          "no redistribution when all capped"))))

;; ── Redistribution characterization tests ──────────────────────────────

(deftest redistribution-multi-level-cascading-caps
  (testing "multi-level cascading caps: excess from capped items flows to remaining uncapped"
    (let [items [{:id :a :weight 100 :cap 10}
                 {:id :b :weight 100 :cap 10}
                 {:id :c :weight 100 :cap nil}]
          result (payoffs/allocate-pro-rata-with-redistribution
                  {:amount 100 :items items
                   :id-fn :id :weight-fn :weight :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      (is (= 100 (:total-allocated result))
          "100% of available allocated")
      (is (= 0 (:total-unmet result))
          "no unmet after full redistribution")
      (is (= 10 (get (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations result))) :a))
          "item a capped at 10")
      (is (= 10 (get (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations result))) :b))
          "item b capped at 10")
      (is (= 80 (get (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations result))) :c))
          "item c receives all excess")
      (is (some? (:redistribution result))
          "redistribution metadata present"))))

(deftest redistribution-all-capped-excess-remains
  (testing "when all items hit caps, single-pass result is returned with no redistribution"
    (let [items [{:id :a :weight 100 :cap 30}
                 {:id :b :weight 100 :cap 30}
                 {:id :c :weight 100 :cap 30}]
          result (payoffs/allocate-pro-rata-with-redistribution
                  {:amount 100 :items items
                   :id-fn :id :weight-fn :weight :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      (is (= 90 (:total-allocated result))
          "total capped at sum of caps")
      (is (every? #(= 30 (:allocated %)) (:allocations result))
          "every item gets exactly its cap")
      (is (nil? (:redistribution result))
          "no redistribution pass when every item is capped"))))

(deftest redistribution-single-item-capped
  (testing "single capped item's excess flows to uncapped"
    (let [items [{:id :a :weight 100 :cap 10}
                 {:id :b :weight 100 :cap nil}]
          result (payoffs/allocate-pro-rata-with-redistribution
                  {:amount 100 :items items
                   :id-fn :id :weight-fn :weight :cap-fn :cap
                   :rounding :floor-with-largest-remainder})]
      (is (= 100 (:total-allocated result))
          "100% allocated")
      (is (= 10 (get (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations result))) :a))
          "item a capped at 10")
      (is (= 90 (get (into {} (map (fn [a] [(:id a) (:allocated a)]) (:allocations result))) :b))
          "item b gets remaining 90")
      (is (some? (:redistribution result))
          "redistribution metadata present"))))

(deftest redistribution-conservation
  (testing "conservation holds across all redistribution scenarios"
    (doseq [items [[{:id :a :weight 100 :cap 30}
                    {:id :b :weight 100 :cap nil}
                    {:id :c :weight 100 :cap nil}]
                   [{:id :a :weight 100 :cap 10}
                    {:id :b :weight 100 :cap 10}
                    {:id :c :weight 100 :cap nil}]
                   [{:id :a :weight 100 :cap 5}
                    {:id :b :weight 200 :cap 20}
                    {:id :c :weight 300 :cap nil}]]]
      (let [result (payoffs/allocate-pro-rata-with-redistribution
                    {:amount 200 :items items
                     :id-fn :id :weight-fn :weight :cap-fn :cap
                     :rounding :floor-with-largest-remainder})]
        (is (<= (+ (:total-allocated result) (:total-unmet result) (:remainder result))
                200)
            "total does not exceed available")
        (is (>= (:total-allocated result) 0)
            "non-negative allocations")
        (is (every? #(>= (:allocated %) 0) (:allocations result))
            "no negative per-item allocations")))))
