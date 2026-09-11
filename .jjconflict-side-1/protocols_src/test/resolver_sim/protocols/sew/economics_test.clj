(ns resolver-sim.protocols.sew.economics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.slash-distribution :as sd]
            [resolver-sim.protocols.sew.economics :as sew-econ]
                        [resolver-sim.pro-rata.allocation :as pro-rata]
                        [resolver-sim.pro-rata.evidence :as mechanism-evidence]))

(deftest sew-economic-policy-helpers
  (testing "Sew-specific fees, bonds, slashes, and escrow caps live in the Sew adapter"
    (is (= 15 (sew-econ/calculate-escrow-fee 1000 150)))
    (is (= {:fee 10 :net 990}
           (sew-econ/calculate-appeal-bond-fee 1000 100)))
    (is (= 25
           (sew-econ/calculate-challenge-bond-amount
            1000 {:challenge-bond-bps 250 :appeal-bond-amount 50})))
    (is (= 50
           (sew-econ/calculate-challenge-bond-amount
            1000 {:challenge-bond-bps 0 :appeal-bond-amount 50})))
    (is (= 30
           (sew-econ/calculate-appeal-bond-amount
            1000 {:appeal-bond-bps 300})))
    (is (= 20 (sew-econ/calculate-bounty 1000 200)))
    (is (= 25 (sew-econ/calculate-slash-amount-from-basis 1000 250)))
    (is (= 25 (sew-econ/calculate-reversal-slash 1000 250)))
    (is (= 1500.0 (sew-econ/calculate-escrow-cap 1000 1.5)))))

(deftest sew-slashing-distribution
  (testing "Sew slash distribution keeps the historical default split"
    (is (= {:insurance 500 :protocol 300 :retained 200}
           (sew-econ/calculate-slashing-distribution 1000 0)))
    (is (= 990
           (let [{:keys [insurance protocol retained]}
                 (sew-econ/calculate-slashing-distribution 1000 10)]
             (+ insurance protocol retained))))))

(deftest sew-slash-allocation-uses-slashable-stake-as-default-weight
  (testing "Sew adapter maps slashable stake into generic allocation weight"
    (let [result (sew-econ/calculate-sew-slash-allocation
                  {:slash-amount 100
                   :liable-parties [{:id :resolver-a
                                     :slashable-stake 300
                                     :available-slashable 300}
                                    {:id :resolver-b
                                     :slashable-stake 100
                                     :available-slashable 100}]})]
      (is (= 400 (:total-basis result)))
      (is (= [75 25] (mapv :paid (:allocations result))))
      (is (= [300 100] (mapv :basis-amount (:allocations result))))
      (is (= [3/4 1/4] (mapv :share (:allocations result))))
      (is (= [300 100] (mapv :cap (:allocations result))))
      (is (empty? (mechanism-evidence/evidence-violations
                   (:mechanism/evidence result))))
      (is (= (:evidence/hash (:mechanism/evidence result))
             (get-in result [:mechanism/evidence-reference :evidence/hash]))))))

(deftest sew-slash-allocation-preserves-caps-and-legacy-shape
  (testing "Sew adapter applies available-slashable caps and returns historical keys"
    (let [result (sew-econ/calculate-sew-slash-allocation
                  {:slash-obligation 100
                   :liable-parties [{:id :resolver-a
                                     :slashable-stake 300
                                     :available-slashable 70}
                                    {:id :resolver-b
                                     :slashable-stake 100
                                     :available-slashable 100}]})]
      (is (= :slashable-stake (:basis result)))
      (is (= :available-slashable (:cap-field result)))
      (is (= 95 (:recovered-total result)))
      (is (= 5 (:unmet-total result)))
      (is (= [70 25] (mapv :paid (:allocations result))))
      (is (= [5 0] (mapv :unmet (:allocations result))))
      (is (= [70 100] (mapv :cap (:allocations result)))))))

(deftest sew-slash-projection-artifact-uses-current-allocation-input
  (testing "Sew sidecar projection artifact comes from the same parties, basis, caps, and amount"
    (let [input {:slash-obligation 100
                 :liable-parties [{:id :resolver-a
                                   :slashable-stake 300
                                   :available-slashable 70}
                                  {:id :resolver-b
                                   :slashable-stake 100
                                   :available-slashable 100}]
                 :source {:world-hash "sha256:sew-world"}}
          allocation (sew-econ/calculate-sew-slash-allocation input)
          artifact (sew-econ/build-sew-slash-projection-artifact input)]
      (is (= 95 (:recovered-total allocation)))
      (is (= 100N (get-in artifact [:projection :total-obligation])))
      (is (= {:resolver-a 300N :resolver-b 100N}
             (get-in artifact [:projection :weights])))
      (is (= {:resolver-a 70N :resolver-b 100N}
             (get-in artifact [:projection :caps])))
      (is (= :slashable-stake (get-in artifact [:source :basis])))
      (is (= :available-slashable (get-in artifact [:source :cap-field])))
      (is (= "sha256:sew-world" (get-in artifact [:source :world-hash])))
      (is (nil? (hc/validate-canonical-value! artifact)))
      (is (= (:projection-hash artifact)
             (hc/hash-with-intent {:hash/intent :projection-artifact}
                                  (dissoc artifact :projection-hash)))))))

(deftest sew-slash-allocation-from-projection-shadows-current-path
  (testing "projection-based Sew allocation matches the current Sew allocation on the same fixtures"
    (doseq [input [{:slash-obligation 100
                    :slash-policy {:policy/id :test-policy}
                    :liable-parties [{:id :resolver-a
                                      :slashable-stake 300
                                      :available-slashable 300}
                                     {:id :resolver-b
                                      :slashable-stake 100
                                      :available-slashable 100}]}
                   {:slash-obligation 100
                    :liable-parties [{:id :resolver-a
                                      :slashable-stake 300
                                      :available-slashable 70}
                                     {:id :resolver-b
                                      :slashable-stake 100
                                      :available-slashable 100}]}
                   {:slash-obligation 100
                    :liable-parties [{:id :resolver-a
                                      :slashable-stake 0
                                      :available-slashable 70}
                                     {:id :resolver-b
                                      :slashable-stake 0
                                      :available-slashable 100}]}
                   {:slash-obligation 7
                    :basis :custom-weight
                    :cap-field :custom-cap
                    :liable-parties [{:id :resolver-a
                                      :custom-weight 1
                                      :custom-cap 10}
                                     {:id :resolver-b
                                      :custom-weight 1
                                      :custom-cap 1}
                                     {:id :resolver-c
                                      :custom-weight 1
                                      :custom-cap 10}]}]]
      (let [current (sew-econ/calculate-sew-slash-allocation input)
            artifact (sew-econ/build-sew-slash-projection-artifact input)
            from-projection (sew-econ/calculate-sew-slash-allocation-from-projection artifact)]
        ;; Projection remains a legacy presentation artifact and intentionally
        ;; does not reconstruct the complete mechanism evidence envelope.
        (is (= (dissoc current :mechanism/evidence :mechanism/evidence-reference)
               from-projection))))))

(deftest sew-adapter-preserves-presentation-while-binding-canonical-mechanism-rows
  (let [parties [{:id :resolver-c :slashable-stake 1 :available-slashable 10}
                 {:id :resolver-a :slashable-stake 1 :available-slashable 10}
                 {:id :resolver-b :slashable-stake 1 :available-slashable 10}]
        mechanism (pro-rata/allocate
                   {:allocation/id :sew-conformance
                    :available 4
                    :rows (mapv (fn [party]
                                  {:row/id [:sew-slash-row (:id party)]
                                   :obligation/id (:id party)
                                   :requested 4
                                   :weight (:slashable-stake party)
                                   :cap (:available-slashable party)})
                                parties)})
        presentation (sew-econ/calculate-sew-slash-allocation
                      {:slash-obligation 4 :liable-parties parties})
        by-mechanism (into {} (map (fn [row]
                                     [(second (:row/id row)) (:allocated row)])
                                   (:rows mechanism)))
        by-presentation (into {} (map (juxt :id :paid) (:allocations presentation)))]
    ;; Mechanism order is canonical, while the public Sew presentation retains
    ;; the supplied liable-party order.
    (is (= [[:sew-slash-row :resolver-a]
            [:sew-slash-row :resolver-b]
            [:sew-slash-row :resolver-c]]
           (mapv :row/id (:rows mechanism))))
    (is (= [:resolver-c :resolver-a :resolver-b]
           (mapv :id (:allocations presentation))))
    (is (= by-mechanism by-presentation))
    (is (= (:allocated-total mechanism) (:recovered-total presentation)))
    (is (= (reduce + 0 (map :unmet (:rows mechanism)))
           (:unmet-total presentation)))
    (is (= (get-in presentation [:mechanism/evidence :mechanism/result :allocation/hash])
           (get-in presentation [:mechanism/evidence-reference :allocation/hash])))))

(deftest sew-resolution-call-site-uses-sew-economics-adapter
  (testing "resolution query path does not call the deprecated payoffs slash wrapper directly"
    (let [source (slurp "protocols_src/resolver_sim/protocols/sew/resolution.clj")]
      (is (str/includes? source "sew-econ/calculate-sew-slash-allocation"))
      (is (not (str/includes? source "payoffs/calculate-prorata-slash-allocation"))))))

;; ── Phase 2: parity tests (new engine matches old results) ──────────────

(defn- parity-assert
  "Assert that v2 matches v1 for a valid case.  For invalid cases that the
   new engine intentionally rejects (source overdrawn, negative allocations),
   return :v2-rejected rather than throwing."
  [amount bounty & [opts]]
  (try
    (let [v1 (apply sew-econ/calculate-slashing-distribution amount bounty (if opts [opts] []))
          v2 (apply sew-econ/calculate-slashing-distribution-v2 amount bounty (if opts [opts] []))]
      (is (= v1 v2) (str "v1=" v1 " v2=" v2 " for amount=" amount " bounty=" bounty)))
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [violations]} (ex-data e)
            ids (set (map :violation/id violations))]
        (is (or (contains? ids :violation/source-overdrawn)
                (contains? ids :violation/negative-final-allocation)
                (contains? ids :violation/rate-out-of-range))
            (str "unexpected rejection for amount=" amount " bounty=" bounty
                 " violations=" (pr-str violations)))
        :v2-rejected))))

(deftest parity-default-split-no-bounty
  (testing "v2 matches v1 for default 50/30/20 split with zero bounty"
    (is (= (sew-econ/calculate-slashing-distribution 1000 0)
           (sew-econ/calculate-slashing-distribution-v2 1000 0)))))

(deftest parity-default-split-with-bounty-even
  (testing "v2 matches v1 for default split with even bounty amount"
    (is (= (sew-econ/calculate-slashing-distribution 1000 100)
           (sew-econ/calculate-slashing-distribution-v2 1000 100)))))

(deftest parity-default-split-with-bounty-odd
  (testing "v2 matches v1 for default split with odd bounty amount"
    (is (= (sew-econ/calculate-slashing-distribution 100 5)
           (sew-econ/calculate-slashing-distribution-v2 100 5)))))

(deftest parity-default-split-large-amount
  (testing "v2 matches v1 for large amounts"
    (is (= (sew-econ/calculate-slashing-distribution 1000000 10000)
           (sew-econ/calculate-slashing-distribution-v2 1000000 10000)))))

(deftest parity-zero-amount
  (testing "v2 matches v1 for zero amount"
    (is (= (sew-econ/calculate-slashing-distribution 0 0)
           (sew-econ/calculate-slashing-distribution-v2 0 0)))))

(deftest parity-larger-bounty-than-insurance-rejected
  (testing "v2 rejects bounty exceeding source capacity (v1 produced negative)"
    (is (= :v2-rejected (parity-assert 10 60)))))

(deftest parity-custom-bps-override
  (testing "v2 matches v1 with custom bps overrides (excluding source-overdrawn cases)"
    (doseq [[insurance protocol] [[7000 1000] [2000 3000] [4000 4000]]]
      (let [opts {:insurance-cut-bps insurance :protocol-retained-bps protocol}
            v1 (sew-econ/calculate-slashing-distribution 1000 50 opts)
            v2 (sew-econ/calculate-slashing-distribution-v2 1000 50 opts)]
        (is (= v1 v2) (str "mismatch for insurance=" insurance " protocol=" protocol))))))

(deftest parity-custom-bps-zero-protocol-rejected
  (testing "v2 rejects protocol=0 with bounty (source-overdrawn)"
    (is (= :v2-rejected
           (parity-assert 1000 50 {:insurance-cut-bps 8000 :protocol-retained-bps 0})))))

(deftest parity-small-edge-cases
  (testing "v2 matches v1 for small amount and bounty edge cases"
    (doseq [[amount bounty] [[1 1] [2 1] [7 5] [10 10] [100 1]
                             [1000 7] [10000 99] [101 7]]]
      (let [result (parity-assert amount bounty)]
        (when (and (= result :v2-rejected)
                   (not= amount 101) (not (and (= amount 2) (= bounty 1))))
          (str "unexpected rejection: amount=" amount " bounty=" bounty))))))

(deftest parity-tiny-bounty-within-capacity
  (testing "v2 accepts amount=1, bounty=0"
    (is (= (sew-econ/calculate-slashing-distribution 1 0)
           (sew-econ/calculate-slashing-distribution-v2 1 0)))))

(deftest parity-conservation
  (testing "v2 conserves insurance+protocol+retained = amount - bounty"
    (doseq [[amount bounty] [[100 0] [100 10] [1000 7] [10000 500]]]
      (let [{:keys [insurance protocol retained]}
            (sew-econ/calculate-slashing-distribution-v2 amount bounty)
            total (+ insurance protocol retained)]
        (is (= total (- amount bounty))
            (str "amount=" amount " bounty=" bounty " total=" total))))))

(deftest parity-sew-policy-policy-hash
  (testing "sew-default-slash-distribution-policy-hash is computed and stable"
    (let [h sew-econ/sew-default-slash-distribution-policy-hash]
      (is (string? h))
      (is (pos? (count h)))
      (is (= 64 (count h))
          "hash is 32 bytes = 64 hex chars"))))

;; ── Phase 3: boundary tests ─────────────────────────────────────────────

(deftest build-sew-slash-distribution-is-deterministic
  (testing "same inputs produce identical distribution hash"
    (let [r1 (sew-econ/build-sew-slash-distribution
              1000 200
              :challenger "0xalice"
              :workflow-reference "wf-1"
              :evidence-reference "sha256:test-evidence")
          r2 (sew-econ/build-sew-slash-distribution
              1000 200
              :challenger "0xalice"
              :workflow-reference "wf-1"
              :evidence-reference "sha256:test-evidence")]
      (is (= :valid (:status r1)))
      (is (= :valid (:status r2)))
      (is (= (:distribution/hash (:distribution r1))
             (:distribution/hash (:distribution r2)))))))

(deftest build-sew-slash-distribution-no-challenger-produces-no-award
  (testing "no challenger → no resolved awards → distribution has empty awards"
    (let [result (sew-econ/build-sew-slash-distribution 1000 200)]
      (is (= :valid (:status result)))
      (is (= [] (:distribution/awards (:distribution result))))
      ;; base = final (no deductions)
      (is (= (:distribution/base-allocations (:distribution result))
             (:distribution/final-allocations (:distribution result)))))))

(deftest build-sew-slash-distribution-zero-bounty-produces-no-award
  (testing "zero bounty-bps → no positive award (award amount is zero)"
    (let [result (sew-econ/build-sew-slash-distribution
                  1000 0
                  :challenger "0xbob"
                  :workflow-reference "wf-2"
                  :evidence-reference "sha256:test")]
      (is (= :valid (:status result)))
      (is (= [] (:distribution/awards (:distribution result)))))))

(deftest build-sew-slash-distribution-one-award-per-positive
  (testing "one positive award produces exactly one award entry"
    (let [result (sew-econ/build-sew-slash-distribution
                  1000 200
                  :challenger "0xcarol"
                  :workflow-reference "wf-3"
                  :evidence-reference "sha256:test-evidence")]
      (is (= :valid (:status result)))
      (let [awards (:distribution/awards (:distribution result))]
        (is (= 1 (count awards)))
        (is (= :sew.award/challenge-bounty (:award/id (first awards))))
        (is (= 20 (:award/amount (first awards))))))))

(deftest build-sew-slash-distribution-two-awards-two-obligations
  (testing "two awards for same beneficiary remain two traceable obligations"
    ;; Build a custom policy with two awards
    (let [policy (-> sew-econ/sew-default-slash-distribution-policy
                     (assoc :awards
                       [(first (:awards sew-econ/sew-default-slash-distribution-policy))
                        {:award/id :sew.award/second-bounty
                         :amount
                         {:method        :resolved-amount
                          :scale         10000}
                         :eligibility
                         {:trigger                    :sew.trigger/successful-challenge
                          :beneficiary-role           :sew.participant/challenger
                          :requires-evidence-reference? true}
                         :funding
                         {:method       :weighted-deduction
                          :scale        10000
                          :weights      {:sew.allocation/insurance 10000}
                          :remainder-to :sew.allocation/insurance}
                         :settlement
                         {:allocation-id   :sew.allocation/second-bounty-pool
                          :obligation-kind :sew.obligation/challenge-bounty}}]))
          param-ctx {:source-root "sew:test"
                     :values {:sew.parameter/challenge-bounty-bps 200}}
          resolved-awards [{:award/id :sew.award/challenge-bounty
                            :eligibility {:trigger :sew.trigger/successful-challenge
                                          :evidence-reference "sha256:test-evidence-1"}
                            :beneficiary {:participant/id "0xdave"
                                          :participant/role :sew.participant/challenger}}
                           {:award/id :sew.award/second-bounty
                            :award/amount 15
                            :eligibility {:trigger :sew.trigger/successful-challenge
                                          :evidence-reference "sha256:test-evidence-2"}
                            :beneficiary {:participant/id "0xdave"
                                          :participant/role :sew.participant/challenger}}]
          result (sd/build-slash-distribution
                  {:gross-amount 1000
                   :policy policy
                   :parameter-context param-ctx
                   :resolved-awards resolved-awards
                   :context {:source-reference "sew:two-awards"}})]
      (is (= :valid (:status result)))
      (let [awards (:distribution/awards (:distribution result))]
        (is (= 2 (count awards)) "two awards, not one aggregated")
        (is (= #{(:award/id (first awards)) (:award/id (second awards))}
               #{:sew.award/challenge-bounty :sew.award/second-bounty}))
        ;; Each award has its own amount — they are not combined
        (is (some #(= 20 (:award/amount %)) awards))
        (is (some #(= 15 (:award/amount %)) awards))))))
