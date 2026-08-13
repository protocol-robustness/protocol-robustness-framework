(ns resolver-sim.benchmark.game-theory-validation-test
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.runner]
            [resolver-sim.io.scenarios]
            [resolver-sim.protocols.sew.accounting :as sew-accounting]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.scenario.equilibrium :as equilibrium]
            [resolver-sim.scenario.suites]
            [resolver-sim.benchmark.game-theory-validation :as sut]
            [resolver-sim.benchmark.strategic-claim-validation :as scv]
            [resolver-sim.benchmark.strategic-property-results :as spr]
            [resolver-sim.validation.gate :as gate]
            [resolver-sim.yield.strategic-partial-fill :as strategic-partial-fill]))

(def valid-partial-fill-decision
  {:decision/id "partial-fill-0123456789abcdef"
   :decision/hash "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
   :requested {:principal 100}
   :filled {:principal 60}
   :deferred {:principal 40}
   :haircut {}
   :unrealized {}
   :settlement-mode :partial-fill
   :policy {:mode :waterfall
            :rounding-policy :floor-and-carry
            :fill-order [:principal]}
   :evidence {:available-liquidity 60
              :shortage 40
              :total-requested 100
              :fill-mode :waterfall}})

(deftest strategic-claim-validation-emits-auditable-artifact
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-validation-test")
        manifest {:benchmark/id :benchmark/prf-shortfall-allocation-v0
                  :benchmark/scenario-suite :suite/sew-shortfall-allocation-v0
                  :benchmark/scenarios [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                                         :dimension :allocation/partial-fill
                                         :claim :allocation-complete}
                                        {:scenario/id "S103_negative-yield-shortfall-cascade"
                                         :dimension :allocation/shortfall
                                         :claim :conservation}
                                        {:scenario/id "S104_resolver-stake-shortfall"
                                         :dimension :allocation/stake-liquidity-blocking
                                         :claim :no-invariant-errors}]}
        scenario-043 {:scenario-id "s-dr-043-payout-shortfall-deferred"
                      :scenario-title "Payout shortfall deferred"
                      :scenario-purpose "Partial fill should defer the remainder."
                      :threat-tags ["dispute-resolution" "shortfall" "yield"]}
        scenario-103 {:scenario-id "s103-negative-yield-shortfall-cascade"
                      :title "Negative Yield and Liquidity Shortfall Cascade"
                      :purpose "yield-stress"
                      :threat-tags ["negative-yield" "shortfall" "deferred-recovery"]}
        scenario-104 {:scenario-id "s104-resolver-stake-shortfall"
                      :title "Resolver stake shortfall"
                      :purpose "liquidity-stress"
                      :threat-tags ["stake" "liquidity" "blocking"]}
        evidence {:results [{:file "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :simulator/scenario-path "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "a"))
                             :partial-fill-decisions [valid-partial-fill-decision]
                             :invariant-results [{:id :inv/a :result :pass}]}
                            {:file "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :simulator/scenario-path "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "b"))
                             :invariant-results [{:id :inv/b :result :pass}]}
                            {:file "scenarios/edn/S104_resolver-stake-shortfall.edn"
                             :simulator/scenario-path "scenarios/edn/S104_resolver-stake-shortfall.edn"
                             :outcome :fail
                             :halt-reason :invariant-violation
                             :scenario/evidence-root (apply str (repeat 64 "c"))
                             :invariant-results [{:id :inv/c :result :fail}]}]}
        {:keys [exit-code artifact output-files]}
        (with-redefs [resolver-sim.benchmark.runner/load-manifest (fn [_] manifest)
                      resolver-sim.benchmark.runner/run-benchmark (fn [_] evidence)
                      resolver-sim.scenario.suites/suite-paths
                      (fn [_]
                        ["scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                         "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                         "scenarios/edn/S104_resolver-stake-shortfall.edn"])
                      resolver-sim.io.scenarios/load-scenario-file
                      (fn [path]
                        (case path
                          "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn" scenario-043
                          "scenarios/edn/S103_negative-yield-shortfall-cascade.edn" scenario-103
                          "scenarios/edn/S104_resolver-stake-shortfall.edn" scenario-104
                          (throw (ex-info "unexpected scenario path" {:path path}))))
                      resolver-sim.yield.strategic-partial-fill/validate-strategic-properties
                      (fn [& _]
                        {:summary {:states-examined 100}
                         :properties
                         [{:property :strategy/split-invariance
                           :status :verified :verdict :verified
                           :state-count 100 :violation-count 0}]})]
          (sut/run-strategic-claim-validation :out-dir out-dir))
        level-verdicts (into {}
                             (map (juxt :mechanism-level identity))
                             (:level-verdicts artifact))
        evidence-roots (->> (:matched-scenarios artifact)
                            (mapcat :evidence-references)
                            (filter #(= :scenario-evidence-root (:reference/type %)))
                            (map :reference/value))
        matched-scenario-ids (set (map :scenario/id (:matched-scenarios artifact)))]
    (testing "artifact summary and claim identity"
      (is (= (if (get-in artifact [:summary :valid?]) 0 1) exit-code))
      (is (= :game-theoretic-validation (:artifact/kind artifact)))
      (is (= "game-theoretic-validation.artifact.v1" (:artifact/version artifact)))
      (is (= :claim/pro-rata-shortfall-conservation (:claim/id artifact)))
      (is (= 2 (get-in artifact [:summary :matched-scenario-count])))
      (is (true? (get-in artifact [:summary :valid?]))))

    (testing "artifact scopes its claim strength"
      (is (string? (:claim/interpretation artifact)))
      (is (re-find #"not falsified" (:claim/interpretation artifact)))
      (is (vector? (:claim/validation-classes artifact)))
      (is (contains? (set (:claim/validation-classes artifact))
                     :validation.class/algebraic-integrity))
      (is (contains? (set (:claim/validation-classes artifact))
                     :validation.class/deviation-resistance)))

    (testing "matched scenarios carry auditable reasons and evidence references"
      (is (= #{"S-DR-043-payout-shortfall-deferred"
               "S103_negative-yield-shortfall-cascade"}
             matched-scenario-ids))
      (is (= #{{:scenario/id "S-DR-043-payout-shortfall-deferred"
                :dimension :allocation/partial-fill
                :claim :allocation-complete}
               {:scenario/id "S103_negative-yield-shortfall-cascade"
                :dimension :allocation/shortfall
                :claim :conservation}}
             (set (map :benchmark/declaration (:matched-scenarios artifact)))))
      (is (every? #(= #{:benchmark/dimension
                        :scenario/threat-tags
                        :scenario/evidence-root}
                      (set (map :reason/id (:match-reasons %))))
                  (:matched-scenarios artifact)))
      (is (= 2 (count evidence-roots)))
      (is (every? #(re-matches #"[0-9a-f]{64}" %) evidence-roots)))

    (testing "mechanism levels are partitioned and checked deterministically"
      (is (= [:allocation/partial-fill :allocation/shortfall]
             (mapv :mechanism-level (:level-verdicts artifact))))
      (is (= :pass (get-in level-verdicts [:allocation/partial-fill :verdict])))
      (is (= :pass (get-in level-verdicts [:allocation/shortfall :verdict])))
      (is (= [] (:coverage-gaps artifact))))

    (testing "artifact files are emitted and readable"
      (is (= 2 (count output-files)))
      (doseq [path output-files]
        (is (.exists (io/file path)))
        (is (seq (slurp path))))
      (is (= :claim/pro-rata-shortfall-conservation
             (:claim/id (edn/read-string (slurp (first output-files))))))
      (let [json-artifact (json/read-str (slurp (second output-files)))]
        (is (= "game-theoretic-validation"
               (get json-artifact "kind")))
        (is (= "game-theoretic-validation.artifact.v1"
               (get json-artifact "version")))
        (is (= "Pro-rata shortfall conservation"
               (get json-artifact "title")))))))

(deftest strategic-claim-validation-runs-against-real-shortfall-pack
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-validation-real")
        {:keys [exit-code artifact output-files]}
        (binding [resolver-sim.evidence.chain/*allow-dirty* true]
          (sut/run-strategic-claim-validation :out-dir out-dir))
        level-verdicts (into {}
                             (map (juxt :mechanism-level identity))
                             (:level-verdicts artifact))
        evidence-roots (->> (:matched-scenarios artifact)
                            (mapcat :evidence-references)
                            (filter #(= :scenario-evidence-root (:reference/type %)))
                            (map :reference/value))
        matched-scenario-ids (set (map :scenario/id (:matched-scenarios artifact)))]
    (testing "real benchmark artifact reflects the current shortfall pack"
      (is (= 1 exit-code))
      (is (= :game-theoretic-validation (:artifact/kind artifact)))
      (is (= :claim/pro-rata-shortfall-conservation (:claim/id artifact)))
      (is (= :benchmark/prf-shortfall-allocation-v0 (:benchmark/id artifact)))
      (is (= :suite/sew-shortfall-allocation-v0 (:benchmark/scenario-suite artifact)))
      (is (= 2 (get-in artifact [:summary :matched-scenario-count])))
      (is (= 1 (get-in artifact [:summary :passed-level-count])))
      (is (= 0 (get-in artifact [:summary :failed-level-count])))
      (is (= 1 (get-in artifact [:summary :uncovered-level-count])))
      (is (false? (get-in artifact [:summary :valid?]))))

    (testing "real matching and level verdicts remain auditable"
      (is (= #{"S-DR-043-payout-shortfall-deferred"
               "S103_negative-yield-shortfall-cascade"}
             matched-scenario-ids))
      (is (= :uncovered (get-in level-verdicts [:allocation/partial-fill :verdict])))
      (is (= :pass (get-in level-verdicts [:allocation/shortfall :verdict])))
      (is (= [{:mechanism-level :allocation/partial-fill :reason :no-partial-fill-decision-artifacts}]
             (:coverage-gaps artifact)))
      (is (= 2 (count evidence-roots)))
      (is (every? #(re-matches #"[0-9a-f]{64}" %) evidence-roots)))

    (testing "real benchmark artifact files are emitted"
      (is (= 2 (count output-files)))
      (doseq [path output-files]
        (is (.exists (io/file path)))
        (is (seq (slurp path))))
      (let [json-artifact (json/read-str (slurp (second output-files)))]
        (is (= "game-theoretic-validation"
               (get json-artifact "kind")))
        (is (= "game-theoretic-validation.artifact.v1"
               (get json-artifact "version")))))))

(deftest unknown-equilibrium-suite-is-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Unknown game-theory validation suite"
       (sut/run-equilibrium-validation :suite :suites/not-registered
                                       :out-dir (str (System/getProperty "java.io.tmpdir")
                                                     "/prf-game-theory-invalid-suite")))))

(deftest strategic-property-violation-propagates-to-artifact
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-strategic-violation")
        manifest {:benchmark/id :benchmark/prf-shortfall-allocation-v0
                  :benchmark/scenario-suite :suite/sew-shortfall-allocation-v0
                  :benchmark/scenarios [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                                         :dimension :allocation/partial-fill
                                         :claim :allocation-complete}
                                        {:scenario/id "S103_negative-yield-shortfall-cascade"
                                         :dimension :allocation/shortfall
                                         :claim :conservation}]}
        scenario-043 {:scenario-id "s-dr-043-payout-shortfall-deferred"
                      :scenario-title "Payout shortfall deferred"
                      :scenario-purpose "Partial fill should defer the remainder."
                      :threat-tags ["dispute-resolution" "shortfall" "yield"]}
        scenario-103 {:scenario-id "s103-negative-yield-shortfall-cascade"
                      :title "Negative Yield and Liquidity Shortfall Cascade"
                      :purpose "yield-stress"
                      :threat-tags ["negative-yield" "shortfall" "deferred-recovery"]}
        evidence {:results [{:file "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :simulator/scenario-path "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "a"))
                             :partial-fill-decisions [valid-partial-fill-decision]
                             :invariant-results [{:id :inv/a :result :pass}]}
                            {:file "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :simulator/scenario-path "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "b"))
                             :invariant-results [{:id :inv/b :result :pass}]}]}
        {:keys [exit-code artifact]}
        (with-redefs [resolver-sim.benchmark.runner/load-manifest (fn [_] manifest)
                      resolver-sim.benchmark.runner/run-benchmark (fn [_] evidence)
                      resolver-sim.scenario.suites/suite-paths
                      (fn [_]
                        ["scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                         "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"])
                      resolver-sim.io.scenarios/load-scenario-file
                      (fn [path]
                        (case path
                          "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn" scenario-043
                          "scenarios/edn/S103_negative-yield-shortfall-cascade.edn" scenario-103
                          (throw (ex-info "unexpected scenario path" {:path path}))))
                      resolver-sim.yield.strategic-partial-fill/validate-strategic-properties
                      (fn [& _]
                        {:summary {:states-examined 100}
                         :properties
                         [{:property :allocation/exact-merge-invariance
                           :status :violated :verdict :violated
                           :state-count 100 :violation-count 1
                           :counterexample {:claims [1 1 1] :liquidity 1}}]})]
          (sut/run-strategic-claim-validation
           :claim-id :claim/pro-rata-shortfall-conservation
           :out-dir out-dir))
        strategic-results (:strategic-property-results artifact)]
    (testing "a violated strategic property surfaces as a property-violated result"
      (is (some #(and (= :allocation/exact-merge-invariance (:property %))
                      (= :fail (:status %))
                      (= :property-violated (:reason %)))
                strategic-results))
      (is (= :violated (get-in artifact [:gates :strategic :verdict])))
      (is (= :strategic-violated (:gates-summary artifact)))
      (is (false? (get-in artifact [:summary :valid?])))
      (is (= 1 (get-in artifact [:summary :strategic-property-violations])))
      (is (= 1 (get-in artifact [:summary :strategic-property-count]))))))

(deftest strategic-property-verified-keeps-artifact-valid
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-strategic-verified")
        manifest {:benchmark/id :benchmark/prf-shortfall-allocation-v0
                  :benchmark/scenario-suite :suite/sew-shortfall-allocation-v0
                  :benchmark/scenarios [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                                         :dimension :allocation/partial-fill
                                         :claim :allocation-complete}
                                        {:scenario/id "S103_negative-yield-shortfall-cascade"
                                         :dimension :allocation/shortfall
                                         :claim :conservation}]}
        scenario-043 {:scenario-id "s-dr-043-payout-shortfall-deferred"
                      :scenario-title "Payout shortfall deferred"
                      :scenario-purpose "Partial fill should defer the remainder."
                      :threat-tags ["dispute-resolution" "shortfall" "yield"]}
        scenario-103 {:scenario-id "s103-negative-yield-shortfall-cascade"
                      :title "Negative Yield and Liquidity Shortfall Cascade"
                      :purpose "yield-stress"
                      :threat-tags ["negative-yield" "shortfall" "deferred-recovery"]}
        evidence {:results [{:file "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :simulator/scenario-path "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "a"))
                             :partial-fill-decisions [valid-partial-fill-decision]
                             :invariant-results [{:id :inv/a :result :pass}]}
                            {:file "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :simulator/scenario-path "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "b"))
                             :invariant-results [{:id :inv/b :result :pass}]}]}
        {:keys [exit-code artifact]}
        (with-redefs [resolver-sim.benchmark.runner/load-manifest (fn [_] manifest)
                      resolver-sim.benchmark.runner/run-benchmark (fn [_] evidence)
                      resolver-sim.scenario.suites/suite-paths
                      (fn [_]
                        ["scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                         "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"])
                      resolver-sim.io.scenarios/load-scenario-file
                      (fn [path]
                        (case path
                          "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn" scenario-043
                          "scenarios/edn/S103_negative-yield-shortfall-cascade.edn" scenario-103
                          (throw (ex-info "unexpected scenario path" {:path path}))))
                      resolver-sim.yield.strategic-partial-fill/validate-strategic-properties
                      (fn [& _]
                        {:summary {:states-examined 100}
                         :properties
                         [{:property :strategy/split-invariance
                           :status :verified :verdict :verified
                           :state-count 100 :violation-count 0}]})]
          (sut/run-strategic-claim-validation
           :claim-id :claim/pro-rata-shortfall-conservation
           :out-dir out-dir))]
    (testing "a verified strategic property keeps the gate verified and artifact valid"
      (is (some #(and (= :strategy/split-invariance (:property %))
                      (= :pass (:status %)))
                (:strategic-property-results artifact)))
      (is (= :verified (get-in artifact [:gates :strategic :verdict])))
      (is (= :all-pass (:gates-summary artifact)))
      (is (true? (get-in artifact [:summary :valid?])))
      (is (zero? (get-in artifact [:summary :strategic-property-violations]))))))

(deftest catalog-scope-respects-claim-subject
  (let [catalog scv/strategic-claim-catalog
        rounding (get catalog :claim/partial-fill-rounding-integrity)
        fairness (get catalog :claim/pro-rata-fairness-end-to-end)
        default (get catalog :claim/pro-rata-shortfall-conservation)]
    (testing "deviation-resistance is declared only on the flagship claim"
      (is (nil? (:deviation-set-ids rounding))
          "rounding bounds are not a deviation-resistance subject")
      (is (nil? (:deviation-set-ids fairness))
          "pro-rata cross-product fairness is not a deviation-resistance subject")
      (is (some? (:deviation-set-ids default))))))

(deftest claim-without-deviation-sets-has-no-strategic-failure
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-rounding-claim")
        manifest {:benchmark/id :benchmark/prf-shortfall-allocation-v0
                  :benchmark/scenario-suite :suite/sew-shortfall-allocation-v0
                  :benchmark/scenarios [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                                         :dimension :allocation/partial-fill
                                         :claim :allocation-complete}]}
        scenario-043 {:scenario-id "s-dr-043-payout-shortfall-deferred"
                      :scenario-title "Payout shortfall deferred"
                      :scenario-purpose "Partial fill should defer the remainder."
                      :threat-tags ["dispute-resolution" "shortfall" "yield"]}
        evidence {:results [{:file "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :simulator/scenario-path "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "a"))
                             :partial-fill-decisions [valid-partial-fill-decision]
                             :invariant-results [{:id :inv/a :result :pass}]}]}
        {:keys [artifact]}
        (with-redefs [resolver-sim.benchmark.runner/load-manifest (fn [_] manifest)
                      resolver-sim.benchmark.runner/run-benchmark (fn [_] evidence)
                      resolver-sim.scenario.suites/suite-paths
                      (fn [_] ["scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"])
                      resolver-sim.io.scenarios/load-scenario-file
                      (fn [path] scenario-043)]
          (sut/run-strategic-claim-validation
           :claim-id :claim/partial-fill-rounding-integrity
           :out-dir out-dir))]
    (testing "a rounding-scoped claim does not inherit the default claim's strategic violations"
      (is (zero? (get-in artifact [:summary :strategic-property-count])))
      (is (= [] (:strategic-property-results artifact)))
      (is (nil? (:strategic-deviation-scope artifact)))
      (is (not= :strategic-violated (:gates-summary artifact)))
      (is (true? (get-in artifact [:summary :valid?])))
      (is (not (re-find #"declared deviation sets" (:claim/interpretation artifact)))
          "claims without deviation sets must not over-claim deviation coverage"))))

(deftest strategic-property-inconclusive-keeps-artifact-invalid
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-strategic-inconclusive")
        manifest {:benchmark/id :benchmark/prf-shortfall-allocation-v0
                  :benchmark/scenario-suite :suite/sew-shortfall-allocation-v0
                  :benchmark/scenarios [{:scenario/id "S-DR-043-payout-shortfall-deferred"
                                         :dimension :allocation/partial-fill
                                         :claim :allocation-complete}
                                        {:scenario/id "S103_negative-yield-shortfall-cascade"
                                         :dimension :allocation/shortfall
                                         :claim :conservation}]}
        scenario-043 {:scenario-id "s-dr-043-payout-shortfall-deferred"
                      :scenario-title "Payout shortfall deferred"
                      :scenario-purpose "Partial fill should defer the remainder."
                      :threat-tags ["dispute-resolution" "shortfall" "yield"]}
        scenario-103 {:scenario-id "s103-negative-yield-shortfall-cascade"
                      :title "Negative Yield and Liquidity Shortfall Cascade"
                      :purpose "yield-stress"
                      :threat-tags ["negative-yield" "shortfall" "deferred-recovery"]}
        evidence {:results [{:file "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :simulator/scenario-path "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "a"))
                             :partial-fill-decisions [valid-partial-fill-decision]
                             :invariant-results [{:id :inv/a :result :pass}]}
                            {:file "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :simulator/scenario-path "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                             :outcome :pass
                             :halt-reason nil
                             :scenario/evidence-root (apply str (repeat 64 "b"))
                             :invariant-results [{:id :inv/b :result :pass}]}]}
        {:keys [artifact]}
        (with-redefs [resolver-sim.benchmark.runner/load-manifest (fn [_] manifest)
                      resolver-sim.benchmark.runner/run-benchmark (fn [_] evidence)
                      resolver-sim.scenario.suites/suite-paths
                      (fn [_]
                        ["scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                         "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"])
                      resolver-sim.io.scenarios/load-scenario-file
                      (fn [path]
                        (case path
                          "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn" scenario-043
                          "scenarios/edn/S103_negative-yield-shortfall-cascade.edn" scenario-103
                          (throw (ex-info "unexpected scenario path" {:path path}))))
                      resolver-sim.yield.strategic-partial-fill/validate-strategic-properties
                      (fn [& _]
                        {:summary {:states-examined 100}
                         :properties
                         [{:property :strategy/split-invariance
                           :status :pending
                           :state-count 100 :violation-count 0}]})]
          (sut/run-strategic-claim-validation :out-dir out-dir))]
    (testing "an inconclusive strategic property fails the artifact closed, not open"
      (is (= :strategic-inconclusive (:gates-summary artifact)))
      (is (= :inconclusive (get-in artifact [:gates :strategic :verdict])))
      (is (false? (get-in artifact [:summary :valid?]))))))

(deftest real-strategic-properties-propagate-through-adapter-and-gate
  (let [artifact (strategic-partial-fill/validate-strategic-properties
                  :deviations [:split :merge :permute :sybil :inflate]
                  :max-states 500)
        results (spr/strategic-properties->results artifact)
        raw-by-property (into {} (map (juxt :property identity)) (:properties artifact))
        by-property (into {} (map (juxt :property identity)) results)
        gate-result (gate/evaluate-strategic-gate
                     {:gate :economic-model :verdict :pass}
                     (spr/strategic-properties->deviation-results artifact)
                     []
                     :contract-ids [:partial-fill/claimant-monotonicity
                                    :partial-fill/claimant-split-merge-sybil]
                     :scope {:mechanism-levels [:allocation/partial-fill
                                                :allocation/shortfall]
                             :deviation-set-ids [:partial-fill/claimant-monotonicity
                                                 :partial-fill/claimant-split-merge-sybil]
                             :deviations [:inflate :merge :permute :split :sybil]})]
    (testing "coverage counters are precise and consistent"
      (is (= 500 (get-in artifact [:summary :state-policy-evaluations])))
      (is (= 500 (get-in artifact [:validation-scope :max-state-policy-evaluations])))
      (is (= 500 (get-in artifact [:summary :max-state-policy-evaluations])))
      (is (= 2 (count (get-in artifact [:summary :policies]))))
      (is (<= (get-in artifact [:summary :distinct-states-examined])
              (get-in artifact [:summary :state-policy-evaluations]))
          "distinct states never exceed state x policy evaluations"))
    (testing "the deterministic enumeration prefix contains the required witnesses"
      (is (>= (get-in artifact [:summary :distinct-states-examined]) 200)
          "a near-empty enumeration would make the witness assertions vacuous")
      (is (= {:claims [1 1 1] :liquidity 2
              :merged-indices [1 2] :merged-claims [1 2]
              :individual-sum 0 :merged-allocation 1 :error 1}
             (:counterexample (raw-by-property :allocation/exact-merge-invariance))))
      (is (some #(= {:claims [1 1 1] :liquidity 2
                     :merged-indices [1 2] :merged-claims [1 2]
                     :individual-sum 0 :merged-allocation 1 :error 1}
                    %)
                (:offending (by-property :allocation/exact-merge-invariance)))))
    (testing "adapter maps the real verdicts to structured results"
      (is (= :fail (:status (by-property :allocation/exact-merge-invariance))))
      (is (= :property-violated (:reason (by-property :allocation/exact-merge-invariance))))
      (is (= :fail (:status (by-property :strategy/split-invariance))))
      (is (= :pass (:status (by-property :strategy/permutation-invariance))))
      (is (= :pass (:status (by-property :strategy/sybil-invariance))))
      (is (= :pass (:status (by-property :strategy/request-monotonicity)))))
    (testing "gate derives the legacy contract id from contract-ids"
      (is (= :violated (:verdict gate-result)))
      (is (= :partial-fill/claimant-monotonicity (:contract-id gate-result)))
      (is (= [:partial-fill/claimant-monotonicity
              :partial-fill/claimant-split-merge-sybil]
             (:contract-ids gate-result)))
      (is (re-find #"2 property/properties violated" (:blocked-reason gate-result))))
    (testing "the gate records a canonical, sorted deviation scope"
      (is (vector? (get-in gate-result [:scope :deviations])))
      (is (= [:inflate :merge :permute :split :sybil]
             (get-in gate-result [:scope :deviations])))
      (is (= [:partial-fill/claimant-monotonicity
              :partial-fill/claimant-split-merge-sybil]
             (get-in gate-result [:scope :deviation-set-ids]))))))

(deftest folk-theorem-catalogue-accurately-reports-multi-epoch-only-coverage
  (let [concepts (:equilibrium-concepts (sut/list-game-theory-checks))
        canonical (some #(when (= :repeated-game/grim-trigger-deterrence (:id %)) %) concepts)
        legacy (some #(when (= :folk-theorem-cooperation-region (:id %)) %) concepts)]
    (is (some? canonical))
    (is (true? (:catalogued? canonical)))
    (is (true? (:implemented? canonical)))
    (is (false? (:wired? canonical))
        "the terminal-trace dispatcher has no multi-epoch evidence input")
    (is (re-find #"U_honest" (:summary canonical)))
    (is (re-find #"not wired" (:summary canonical)))
    (is (some? legacy)
        "the legacy folk-theorem id is retained as a deprecated alias")
    (is (true? (:deprecated legacy)))
    (is (= :repeated-game/grim-trigger-deterrence (:alias-of legacy)))
    (let [trace-result (get (equilibrium/evaluate-equilibrium-concepts
                             [:repeated-game/grim-trigger-deterrence] {})
                            :repeated-game/grim-trigger-deterrence)]
      (is (= :inconclusive (:status trace-result)))
      (is (= :unsupported-concept (:reason trace-result)))
      (is (= :absent-evidence (:basis trace-result))))))

(deftest held-custody-closed-form-validation-emits-artifact
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-held-custody-game-theory-validation")
        world (-> (sew-types/empty-world)
                  (sew-accounting/add-held :0xUSDC 100 {:action "create-escrow"
                                                        :reason :escrow-principal-deposited
                                                        :extra {:held/workflow-id 0
                                                                :owner/address "0xAlice"
                                                                :held/from "0xAlice"
                                                                :held/to "0xBob"}})
                  (sew-accounting/sub-held :0xUSDC 40 {:action "finalize-released"
                                                       :reason :escrow-settlement-released
                                                       :extra {:held/workflow-id 0
                                                               :owner/address "0xBob"}}))
        held-artifacts (vals (:held-artifacts world))
        {:keys [exit-code artifact output-files]}
        (sut/run-held-custody-closed-form-validation
         :held-artifacts held-artifacts
         :out-dir out-dir)
        level (first (:level-verdicts artifact))]
    (is (= 0 exit-code))
    (is (= :claim/held-custody-conservation (:claim/id artifact)))
    (is (= :benchmark/held-custody-local (:benchmark/id artifact)))
    (is (= :validation.class/algebraic-integrity (:claim/validation-class artifact)))
    (is (string? (:claim/interpretation artifact)))
    (is (= :custody/held-balance (:mechanism-level level)))
    (is (= :pass (:verdict level)))
    (is (= 2 (get-in artifact [:summary :matched-artifact-count])))
    (is (every? #(= :pass (:status %)) (:check-results level)))
    (is (= 2 (count output-files)))
    (doseq [path output-files]
      (is (.exists (io/file path)))
      (is (seq (slurp path))))))

(deftest held-custody-closed-form-validation-fails-on-tampered-artifact
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-held-custody-game-theory-validation-tampered")
        world (-> (sew-types/empty-world)
                  (sew-accounting/add-held :0xUSDC 100 {:action "create-escrow"
                                                        :reason :escrow-principal-deposited
                                                        :extra {:held/workflow-id 0
                                                                :owner/address "0xAlice"
                                                                :held/from "0xAlice"
                                                                :held/to "0xBob"}})
                  (sew-accounting/sub-held :0xUSDC 40 {:action "finalize-released"
                                                       :reason :escrow-settlement-released
                                                       :extra {:held/workflow-id 0
                                                               :owner/address "0xBob"}}))
        tampered-artifacts (->> (:held-artifacts world)
                                vals
                                (mapv (fn [artifact]
                                        (if (= "held-adjustment-1" (:held-adjustment/id artifact))
                                          (assoc artifact :held/after 999)
                                          artifact))))
        {:keys [exit-code artifact]}
        (sut/run-held-custody-closed-form-validation
         :held-artifacts tampered-artifacts
         :out-dir out-dir)
        level (first (:level-verdicts artifact))]
    (is (= 1 exit-code))
    (is (= :fail (:verdict level)))
    (is (some #(= :fail (:status %)) (:check-results level)))))
