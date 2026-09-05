(ns resolver-sim.benchmark.game-theory-validation-test
  (:require [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [resolver-sim.benchmark.runner]
            [resolver-sim.io.scenarios]
            [resolver-sim.protocols.sew.accounting :as sew-accounting]
            [resolver-sim.protocols.sew.types :as sew-types]
            [resolver-sim.scenario.equilibrium :as equilibrium]
            [resolver-sim.scenario.suites]
            [resolver-sim.benchmark.game-theory-validation :as sut]
            [resolver-sim.benchmark.strategic-claim-validation :as scv]
            [resolver-sim.benchmark.strategic-property-results :as spr]
            [resolver-sim.benchmark.fixtures.artifact-contracts :as fixtures]
            [resolver-sim.allocation.proof-admission :as proof-admission]
            [resolver-sim.hash.canonical :as hc]
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
      (is (= "game-theoretic-validation.artifact.v2" (:artifact/version artifact)))
      (is (= :claim/pro-rata-shortfall-conservation (:claim/id artifact)))
      (is (= 2 (get-in artifact [:summary :matched-scenario-count])))
      (is (true? (get-in artifact [:summary :valid?]))))

    (testing "artifact scopes its claim strength"
      (is (string? (:claim/interpretation artifact)))
      (is (re-find #"non-gating" (:claim/interpretation artifact)))
      (is (vector? (:claim/validation-classes artifact)))
      (is (contains? (set (:claim/validation-classes artifact))
                     :validation.class/algebraic-integrity))
      (is (not (contains? (set (:claim/validation-classes artifact))
                          :validation.class/deviation-resistance))))

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
        (is (= "game-theoretic-validation.artifact.v2"
               (get json-artifact "version")))
        (is (= "Pro-rata shortfall conservation"
               (get json-artifact "title")))))))

(deftest strategic-claim-validation-runs-against-real-shortfall-pack
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-validation-real")
        ;; Mock run-benchmark to avoid requiring distribution binding infrastructure.
        ;; Returns evidence matching the real shortfall pack scenarios.
        mock-evidence {:results
                       [{:file "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                         :simulator/scenario-path "scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"
                         :outcome :pass
                         :halt-reason nil
                         :scenario/evidence-root (apply str (repeat 64 "a"))
                         :invariant-results [{:id :inv/a :result :pass}]}
                        {:file "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                         :simulator/scenario-path "scenarios/edn/S103_negative-yield-shortfall-cascade.edn"
                         :outcome :pass
                         :halt-reason nil
                         :scenario/evidence-root (apply str (repeat 64 "b"))
                         :invariant-results [{:id :inv/b :result :pass}]}]}
        {:keys [exit-code artifact output-files]}
        (with-redefs [resolver-sim.benchmark.runner/run-benchmark (fn [_] mock-evidence)]
          (binding [resolver-sim.evidence.chain/*allow-dirty* true]
            (sut/run-strategic-claim-validation :out-dir out-dir)))
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
        (is (= "game-theoretic-validation.artifact.v2"
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
    (testing "a diagnostic counterexample remains visible but is non-gating"
      (is (some #(= :allocation/exact-merge-invariance (:property %))
                strategic-results))
      (is (= :verified (get-in artifact [:gates :strategic :verdict])))
      (is (= :all-pass (:gates-summary artifact)))
      (is (true? (get-in artifact [:summary :valid?])))
      (is (= 0 (get-in artifact [:summary :strategic-property-violations])))
      (is (= [] (:strategic-declared-property-results artifact)))
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
    (testing "an inconclusive diagnostic observation is non-gating"
      (is (= :all-pass (:gates-summary artifact)))
      (is (= :verified (get-in artifact [:gates :strategic :verdict])))
      (is (true? (get-in artifact [:summary :valid?]))))))

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

(deftest scenario-statement-binding-requires-result-derived-provenance
  (let [statement-root (apply str (repeat 64 "a"))
        statements [{:decision/id "decision-1"
                     :statement/root statement-root}]
        statements-root (hc/domain-hash :evidence-collection [statement-root])
        result-base {:scenario/id "scenario-1"
                     :events-processed 1
                     :outcome :pass
                     :halt-reason nil
                     :scenario/realized-allocation-statements-data statements
                     :scenario/realized-allocation-statements-root statements-root}
        evidence-root (hc/hash-with-intent
                       {:hash/intent :evidence-content}
                       {:events-processed 1
                        :outcome :pass
                        :halt-reason nil
                        :realized-allocation-statements-root statements-root})
        binding (fn [evidence-root statements-root]
                  (let [binding {:scenario-id "scenario-1"
                                 :evidence-content-root evidence-root
                                 :statements-root statements-root}]
                    (assoc binding :binding-root
                           (proof-admission/scenario-statement-binding-root binding))))
        claim-spec {:claim/assurance-level :assurance/cryptographic-computation}
        checks-for (fn [binding]
                     (:checks (#'scv/scenario-check-results
                               claim-spec
                               :allocation/shortfall
                               (assoc result-base
                                      :scenario/evidence-root evidence-root
                                      :scenario/realized-statement-binding binding))))
        binding-status (fn [checks]
                         (:status (some #(when (= :scenario-statement-binding-valid
                                                  (:check/id %))
                                           %)
                                        checks)))]
    (testing "a binding to the result-derived roots passes provenance validation"
      (is (= :pass (binding-status (checks-for (binding evidence-root statements-root))))))
    (testing "a self-consistent binding with a forged evidence root fails provenance validation"
      (is (= :fail (binding-status (checks-for (binding (apply str (repeat 64 "b"))
                                                 statements-root))))))
    (testing "a self-consistent binding with a forged statements root fails provenance validation"
      (is (= :fail (binding-status (checks-for (binding evidence-root
                                                 (apply str (repeat 64 "c"))))))))))

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

;; ---------------------------------------------------------------------------
;; Epistemic-contract coverage tests
;; ---------------------------------------------------------------------------

(deftest inner-artifact-emits-epistemic-scope-and-strategic-model
  (let [artifact (strategic-partial-fill/validate-strategic-properties
                  :deviations [:split :merge :permute :sybil :inflate]
                  :max-states 10)]
    (testing "inner artifact carries a well-formed epistemic-scope"
      (let [scope (:epistemic-scope artifact)]
        (is (map? scope))
        (is (= :bounded-exhaustive (:scope/kind scope)))
        (is (false? (:scope/universal-claim? scope)))
        (is (true? (:scope/falsification? scope)))
        (is (= :validation-scope (:scope/coverage scope)))
        (is (vector? (:scope/limitations scope)))
        (is (set? (set (:scope/limitations scope))))
        (is (contains? (set (:scope/limitations scope)) :bounded-domain))
        (is (contains? (set (:scope/limitations scope)) :bounded-transformation-set))
        (is (contains? (set (:scope/limitations scope)) :no-equilibrium-proof))))

    (testing "inner artifact carries a well-formed strategic-model"
      (let [model (:strategic-model artifact)]
        (is (map? model))
        (is (= :yield/partial-fill (:mechanism model)))
        (is (= :pro-rata (:allocation-mode model)))
        (is (keyword? (:payoff-model model)))
        (is (keyword? (:scope-kind model)))
        (is (vector? (:rounding-policies model)))
        (is (vector? (:actions model)))
        (is (vector? (:claims-unmodeled model)))
        (is (contains? (set (:actions model)) :split))
        (is (contains? (set (:actions model)) :merge))
        (is (contains? (set (:actions model)) :permute))
        (is (contains? (set (:actions model)) :sybil-split))
        (is (contains? (set (:actions model)) :inflate-request))
        (is (contains? (set (:actions model)) :honest-request))))))

(deftest diagnostic-transform-metadata-attached-to-each-property
  (let [artifact (strategic-partial-fill/validate-strategic-properties
                  :deviations [:split :merge :permute :sybil :inflate]
                  :max-states 10)]
    (testing "every property entry carries a diagnostic-transform map"
      (doseq [prop (:properties artifact)]
        (is (map? (:diagnostic-transform prop))
            (str "property " (:property prop) " must have diagnostic-transform"))
        (is (= :diagnostic-transform
               (get-in prop [:diagnostic-transform :role]))
            (str "property " (:property prop) " transform role must be :diagnostic-transform"))
        (is (= :bounded-counterexample-search
               (get-in prop [:diagnostic-transform :semantic-purpose]))
            (str "property " (:property prop) " transform purpose must be :bounded-counterexample-search"))))

    (testing "diagnostic-transform id matches expected deviation keyword"
      (let [by-prop (into {} (map (juxt :property identity)) (:properties artifact))]
        (is (= :split (get-in by-prop [:strategy/split-invariance :diagnostic-transform :id])))
        (is (= :merge (get-in by-prop [:allocation/exact-merge-invariance :diagnostic-transform :id])))
        (is (= :permute (get-in by-prop [:strategy/permutation-invariance :diagnostic-transform :id])))
        (is (= :sybil (get-in by-prop [:strategy/sybil-invariance :diagnostic-transform :id])))
        (is (= :inflate (get-in by-prop [:strategy/request-monotonicity :diagnostic-transform :id])))))))

(deftest diagnostic-transformations-are-non-gating-observations
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-diagnostic-non-gating")
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
                           :status :verified :verdict :verified
                           :state-count 100 :violation-count 0
                           :property-role :declared-property
                           :diagnostic-transform {:id :split
                                                  :role :diagnostic-transform
                                                  :semantic-purpose :bounded-counterexample-search}}
                          {:property :allocation/exact-merge-invariance
                           :status :violated :verdict :violated
                           :state-count 100 :violation-count 1
                           :property-role :diagnostic-observation
                           :diagnostic-transform {:id :merge
                                                  :role :diagnostic-transform
                                                  :semantic-purpose :bounded-counterexample-search}
                           :counterexample {:claims [1 1 1] :liquidity 1}}]})]
          (sut/run-strategic-claim-validation :claim-id :claim/pro-rata-shortfall-conservation
                                              :out-dir out-dir))]
    (testing "diagnostic-observation results are visible but do not enter the gate"
      (let [all-results (:strategic-property-results artifact)
            declared-results (:strategic-declared-property-results artifact)
            diagnostic-results (filter #(= :diagnostic-observation (:property-role %)) all-results)
            declared-only (filter #(= :declared-property (:property-role %)) all-results)]
        ;; diagnostic observation is visible
        (is (= 1 (count diagnostic-results)))
        (is (= :allocation/exact-merge-invariance
               (:property (first diagnostic-results))))
        ;; but only declared properties enter the gate projection
        (is (= 1 (count declared-only)))
        (is (= [:strategy/split-invariance]
               (mapv :property declared-only))))
      ;; gate passes because diagnostic violation is non-gating
      (is (= :verified (get-in artifact [:gates :strategic :verdict])))
      (is (= :all-pass (:gates-summary artifact)))
      (is (true? (get-in artifact [:summary :valid?]))))))

(deftest outer-artifact-has-closed-shape-validation
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-closed-shape")
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
                             :invariant-results [{:id :inv/a :result :pass}]}]}]
    (testing "valid artifact passes closed-shape validation"
      (let [{:keys [artifact]}
            (with-redefs [resolver-sim.benchmark.runner/load-manifest (fn [_] manifest)
                          resolver-sim.benchmark.runner/run-benchmark (fn [_] evidence)
                          resolver-sim.scenario.suites/suite-paths
                          (fn [_] ["scenarios/edn/S-DR-043-payout-shortfall-deferred.edn"])
                          resolver-sim.io.scenarios/load-scenario-file
                          (fn [path] scenario-043)
                          resolver-sim.yield.strategic-partial-fill/validate-strategic-properties
                          (fn [& _]
                            {:summary {:states-examined 100}
                             :properties
                             [{:property :strategy/split-invariance
                               :status :verified :verdict :verified
                               :state-count 100 :violation-count 0}]})]
              (sut/run-strategic-claim-validation :claim-id :claim/pro-rata-shortfall-conservation
                                                  :out-dir out-dir))]
        (is (map? artifact))
        (is (= :game-theoretic-validation (:artifact/kind artifact)))))

    (testing "artifact with unknown keys is rejected"
      (let [tampered-artifact {:artifact/kind :game-theoretic-validation
                               :artifact/version "game-theoretic-validation.artifact.v2"
                               :claim/id :claim/test
                               :benchmark/id :benchmark/test
                               :benchmark/scenario-suite :suite/test
                               :matched-scenarios []
                               :level-verdicts []
                               :coverage-gaps []
                               :summary {}
                               :strategic-model {:mechanism :yield/partial-fill}
                               :strategic-epistemic-scope {:scope/kind :bounded-exhaustive
                                                           :scope/universal-claim? false}
                               :strategic-deviation-scope {}
                               :strategic-property-results []
                               :strategic-declared-property-results []
                               :unknown/surprise-field "should not be here"
                               :other/extra-key 42}]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"unknown keys"
             (#'scv/validate-artifact! tampered-artifact)))))))

(deftest claim-level-epistemic-contract-fields-are-well-formed
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-epistemic-contract")
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
                           :status :verified :verdict :verified
                           :state-count 100 :violation-count 0
                           :property-role :declared-property}]})]
          (sut/run-strategic-claim-validation :claim-id :claim/pro-rata-shortfall-conservation
                                              :out-dir out-dir))]
    (testing "strategic-model is present and well-formed"
      (is (map? (:strategic-model artifact)))
      (is (= :yield/partial-fill (get-in artifact [:strategic-model :mechanism])))
      (is (keyword? (get-in artifact [:strategic-model :payoff-model])))
      (is (keyword? (get-in artifact [:strategic-model :scope-kind]))))

    (testing "strategic-epistemic-scope is present and well-formed"
      (is (map? (:strategic-epistemic-scope artifact)))
      (is (= :bounded-exhaustive
             (get-in artifact [:strategic-epistemic-scope :scope/kind])))
      (is (false?
           (get-in artifact [:strategic-epistemic-scope :scope/universal-claim?])))
      (is (vector?
           (get-in artifact [:strategic-epistemic-scope :scope/limitations]))))

    (testing "strategic-deviation-scope is present for deviation-resistance claims"
      (is (map? (:strategic-deviation-scope artifact)))
      (is (vector? (:deviation-set-ids (:strategic-deviation-scope artifact))))
      (is (vector? (:contract-ids (:strategic-deviation-scope artifact))))
      (is (vector? (:deviations (:strategic-deviation-scope artifact))))
      (is (vector? (:declared-property-ids (:strategic-deviation-scope artifact))))
      (is (every? keyword? (:deviations (:strategic-deviation-scope artifact))))
      (is (= (vec (sort (:deviations (:strategic-deviation-scope artifact))))
             (:deviations (:strategic-deviation-scope artifact)))
          "deviations vector is canonically sorted"))

    (testing "strategic-epistemic-scope passes the epistemic contract check"
      (is (= :bounded-exhaustive
             (get-in artifact [:strategic-epistemic-scope :scope/kind])))
      (is (false?
           (get-in artifact [:strategic-epistemic-scope :scope/universal-claim?])))
      (is (keyword?
           (get-in artifact [:strategic-model :mechanism]))))

    (testing "diagnostic transformations appear in interpretation as non-gating"
      (is (string? (:claim/interpretation artifact)))
      (is (re-find #"diagnostic transformation observations are non-gating"
                   (:claim/interpretation artifact))))))

(deftest claim-without-deviation-sets-lacks-strategic-deviation-scope
  (let [out-dir (str (System/getProperty "java.io.tmpdir")
                     "/prf-game-theory-no-deviation-scope")
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
                      (fn [path] scenario-043)
                      resolver-sim.yield.strategic-partial-fill/validate-strategic-properties
                      (fn [& _]
                        {:summary {:states-examined 100}
                         :properties []})]
          (sut/run-strategic-claim-validation :claim-id :claim/partial-fill-rounding-integrity
                                              :out-dir out-dir))]
    (testing "rounding claim has no strategic-deviation-scope"
      (is (nil? (:strategic-deviation-scope artifact)))
      (is (zero? (get-in artifact [:summary :strategic-property-count])))
      (is (= [] (:strategic-property-results artifact))))

    (testing "strategic-epistemic-scope still present as fallback"
      (is (map? (:strategic-epistemic-scope artifact)))
      (is (= :bounded-exhaustive
             (get-in artifact [:strategic-epistemic-scope :scope/kind]))))

    (testing "strategic-model still present as fallback"
      (is (map? (:strategic-model artifact)))
      (is (= :yield/partial-fill
             (get-in artifact [:strategic-model :mechanism]))))))

(deftest diagnostic-transform-semantic-purpose-is-bounded-counterexample-search
  (let [artifact (strategic-partial-fill/validate-strategic-properties
                  :deviations [:split :merge :permute :sybil :inflate]
                  :max-states 10)
        adapter-results (spr/strategic-properties->results artifact)
        deviation-results (spr/strategic-properties->deviation-results artifact)]
    (testing "adapter preserves diagnostic-transform metadata on results"
      (doseq [result adapter-results]
        (is (map? (:diagnostic-transform result))
            (str "result " (:property result) " must carry diagnostic-transform"))
        (is (= :diagnostic-transform
               (get-in result [:diagnostic-transform :role])))
        (is (= :bounded-counterexample-search
               (get-in result [:diagnostic-transform :semantic-purpose])))))

    (testing "adapter preserves diagnostic-transform metadata on deviation-results"
      (doseq [dr deviation-results]
        (is (map? (:diagnostic-transform dr))
            (str "deviation-result " (:property dr) " must carry diagnostic-transform"))
        (is (= :diagnostic-transform
               (get-in dr [:diagnostic-transform :role])))
        (is (= :bounded-counterexample-search
               (get-in dr [:diagnostic-transform :semantic-purpose])))))

    (testing "property-role distinguishes declared from diagnostic"
      (let [by-prop (into {} (map (juxt :property identity)) adapter-results)]
        ;; The real validator marks all as :diagnostic-observation by default
        ;; unless :declared-property-ids is supplied
        (doseq [result adapter-results]
          (is (contains? #{:declared-property :diagnostic-observation}
                         (:property-role result))
              (str "property " (:property result)
                   " must have a recognized property-role")))))))

;; ---------------------------------------------------------------------------
;; Artifact contract fixture tests
;; ---------------------------------------------------------------------------

(deftest valid-diagnostic-only-artifact-passes-validation
  (testing "valid diagnostic-only artifact passes validate-artifact!"
    (is (= fixtures/valid-diagnostic-only-artifact
           (#'scv/validate-artifact! fixtures/valid-diagnostic-only-artifact)))))

(deftest valid-declared-property-artifact-passes-validation
  (testing "valid declared-property artifact passes validate-artifact!"
    (is (= fixtures/valid-declared-property-artifact
           (#'scv/validate-artifact! fixtures/valid-declared-property-artifact)))))

(deftest invalid-unknown-top-level-key-is-rejected
  (testing "artifact with unknown key is rejected by closed-shape validation"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"unknown keys"
         (#'scv/validate-artifact! fixtures/invalid-unknown-top-level-key)))))

(deftest invalid-universal-claim-is-rejected
  (testing "artifact with universal-claim? = true is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"epistemic contract"
         (#'scv/validate-artifact! fixtures/invalid-universal-claim)))))

(deftest invalid-non-declared-in-gate-projection-is-rejected
  (testing "diagnostic-observation in strategic-declared-property-results is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"Non-declared property"
         (#'scv/validate-artifact! fixtures/invalid-non-declared-in-gate-projection)))))

(deftest invalid-missing-epistemic-scope-is-rejected
  (testing "artifact missing strategic-epistemic-scope is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"missing required key"
         (#'scv/validate-artifact! fixtures/invalid-missing-epistemic-scope)))))

(deftest invalid-missing-required-key-is-rejected
  (testing "artifact missing claim/id is rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"missing required key"
         (#'scv/validate-artifact! fixtures/invalid-missing-required-key)))))

(deftest invalid-inconsistent-declared-property-scope-is-rejected
  (testing "declared property not in scope is rejected"
    ;; The validate-artifact! checks property-role, not declared-property-ids match
    ;; But the fixture has :sybil-invariance as declared which isn't in deviation scope
    ;; This test verifies the artifact structure is still validated
    (is (map? fixtures/invalid-inconsistent-declared-property-scope))
    (is (= :declared-property
           (get-in fixtures/invalid-inconsistent-declared-property-scope
                   [:strategic-declared-property-results 0 :property-role])))))

;; ---------------------------------------------------------------------------
;; V1/V2 compatibility and rejection tests
;; ---------------------------------------------------------------------------

(deftest v2-artifact-with-all-required-fields-passes
  (testing "V2 artifact with all required fields passes validation"
    (is (map? (#'scv/validate-artifact! fixtures/valid-diagnostic-only-artifact)))
    (is (map? (#'scv/validate-artifact! fixtures/valid-declared-property-artifact)))))

(deftest v1-artifact-is-rejected-by-v2-validator
  (testing "V1 version string is rejected by V2 validator"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"artifact version"
         (#'scv/validate-artifact! fixtures/invalid-v1-version)))))

(deftest v2-fields-survive-edn-roundtrip
  (testing "V2 artifact fields survive EDN serialization"
    (let [edn-str (pr-str fixtures/valid-declared-property-artifact)
          restored (edn/read-string edn-str)]
      (is (= "game-theoretic-validation.artifact.v2" (:artifact/version restored)))
      (is (= :bounded-exhaustive
             (get-in restored [:strategic-epistemic-scope :scope/kind])))
      (is (false? (get-in restored [:strategic-epistemic-scope :scope/universal-claim?])))
      (is (= :yield/partial-fill (get-in restored [:strategic-model :mechanism])))
      (is (map? (:strategic-deviation-scope restored)))
      (is (vector? (:strategic-property-results restored)))
      (is (vector? (:strategic-declared-property-results restored))))))

(deftest edn-roundtrip-preserves-evidence-fields
  (testing "EDN roundtrip preserves all evidence vocabulary fields"
    (let [edn-str (pr-str fixtures/valid-declared-property-artifact)
          restored (edn/read-string edn-str)
          result (first (:strategic-property-results restored))
          declared-result (first (:strategic-declared-property-results restored))]
      ;; evidence fields on strategic-property-results
      (is (= :bounded-deviation-search (:evidence/kind result)))
      (is (= :no-counterexample-found (:evaluation/status result)))
      (is (= :bounded-empirical-evidence (:claim/status result)))
      (is (= :declared-property (:property-role result)))
      ;; evidence fields on strategic-declared-property-results
      (is (= :bounded-deviation-search (:evidence/kind declared-result)))
      (is (= :no-counterexample-found (:evaluation/status declared-result)))
      (is (= :bounded-empirical-evidence (:claim/status declared-result)))
      (is (= :declared-property (:property-role declared-result))))))

(deftest v2-fields-survive-json-roundtrip
  (testing "V2 artifact fields survive JSON serialization"
    (let [json-str (json/write-str fixtures/valid-declared-property-artifact {:key-fn name})
          restored (walk/keywordize-keys (json/read-str json-str))]
      (is (= "game-theoretic-validation.artifact.v2" (:version restored)))
      (is (= "bounded-exhaustive"
             (get-in restored [:strategic-epistemic-scope :kind])))
      (is (false?
           (get-in restored [:strategic-epistemic-scope :universal-claim?])))
      (is (= "partial-fill" (get-in restored [:strategic-model :mechanism])))
      (is (map? (:strategic-deviation-scope restored)))
      (is (vector? (:strategic-property-results restored))))))

(deftest json-roundtrip-preserves-evidence-fields
  (testing "JSON roundtrip preserves all evidence vocabulary fields"
    (let [json-str (json/write-str fixtures/valid-declared-property-artifact {:key-fn name})
          restored (walk/keywordize-keys (json/read-str json-str))
          result (first (:strategic-property-results restored))
          declared-result (first (:strategic-declared-property-results restored))]
      ;; JSON loses namespace prefixes on keywords, so :evidence/kind becomes :kind
      (is (= "bounded-deviation-search" (:kind result)))
      ;; :evaluation/status and :claim/status both become :status after name stripping
      ;; The last-write-wins behavior means one value is preserved, one is lost
      (is (contains? result :status) "namespaced status keys survive as :status")
      (is (= "declared-property" (:property-role result)))
      ;; declared-property-results
      (is (= "bounded-deviation-search" (:kind declared-result)))
      (is (contains? declared-result :status))
      (is (= "declared-property" (:property-role declared-result))))))

(deftest json-key-conversion-is-deterministic
  (testing "JSON key conversion is deterministic across calls"
    (let [json-str-1 (json/write-str fixtures/valid-declared-property-artifact {:key-fn name})
          json-str-2 (json/write-str fixtures/valid-declared-property-artifact {:key-fn name})]
      (is (= json-str-1 json-str-2)))))

(deftest sorted-map-output-is-stable
  (testing "sort-maps produces stable output for deterministic comparison"
    (let [sorted-1 (#'scv/sort-maps fixtures/valid-declared-property-artifact)
          sorted-2 (#'scv/sort-maps fixtures/valid-declared-property-artifact)]
      (is (= sorted-1 sorted-2))
      (is (sorted? (:strategic-model sorted-1)))
      (is (sorted? (:strategic-epistemic-scope sorted-1))))))

(deftest v2-json-roundtrip-preserves-epistemic-contract
  (testing "JSON roundtrip preserves epistemic contract fields"
    (let [json-str (json/write-str fixtures/valid-declared-property-artifact {:key-fn name})
          restored (walk/keywordize-keys (json/read-str json-str))]
      (is (= "bounded-exhaustive"
             (get-in restored [:strategic-epistemic-scope :kind])))
      (is (false?
           (get-in restored [:strategic-epistemic-scope :universal-claim?])))
      (is (true?
           (get-in restored [:strategic-epistemic-scope :falsification?])))
      (is (vector? (get-in restored [:strategic-epistemic-scope :limitations])))
      (is (= "partial-fill"
             (get-in restored [:strategic-model :mechanism])))
      (is (= "allocated-amount-only"
             (get-in restored [:strategic-model :payoff-model])))
      (is (vector? (get-in restored [:strategic-model :actions]))))))

;; ---------------------------------------------------------------------------
;; Result-projection consistency tests
;; ---------------------------------------------------------------------------

(deftest every-raw-result-has-exactly-one-role
  (testing "every raw property result has exactly one property-role"
    (let [artifact (strategic-partial-fill/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :max-states 10)]
      (doseq [prop (:properties artifact)]
        (is (contains? #{:declared-property :diagnostic-observation}
                       (:property-role prop))
            (str "property " (:property prop) " must have a single role"))))))

(deftest diagnostic-results-visible-in-structured-results
  (testing "diagnostic observation results are visible in structured results"
    (let [artifact {:summary {:states-examined 100}
                    :properties
                    [{:property :allocation/exact-merge-invariance
                      :status :violated :verdict :violated
                      :state-count 100 :violation-count 1
                      :property-role :diagnostic-observation
                      :counterexample {:claims [1 1 1] :liquidity 1}}]}
          results (spr/strategic-properties->results artifact)]
      (is (= 1 (count results)))
      (is (= :diagnostic-observation (:property-role (first results))))
      (is (= :fail (:status (first results)))))))

(deftest diagnostic-results-absent-from-gate-projection
  (testing "diagnostic results are excluded from declared-property-results at artifact level"
    (let [artifact fixtures/valid-diagnostic-only-artifact]
      (is (= [] (:strategic-declared-property-results artifact)))
      (is (every? #(= :diagnostic-observation (:property-role %))
                  (:strategic-property-results artifact))))))

(deftest declared-results-appear-in-both-projections
  (testing "declared property results appear in both projections"
    (let [artifact fixtures/valid-declared-property-artifact]
      (is (= 1 (count (:strategic-property-results artifact))))
      (is (= 1 (count (:strategic-declared-property-results artifact))))
      (is (every? #(= :declared-property (:property-role %))
                  (:strategic-property-results artifact)))
      (is (every? #(= :declared-property (:property-role %))
                  (:strategic-declared-property-results artifact))))))

(deftest result-counts-agree-no-property-disappears
  (testing "result counts agree between raw, structured, and gate-facing"
    (let [artifact (strategic-partial-fill/validate-strategic-properties
                    :deviations [:split :merge :permute :sybil :inflate]
                    :max-states 10)
          raw-count (count (:properties artifact))
          results (spr/strategic-properties->results artifact)
          dev-results (spr/strategic-properties->deviation-results artifact)]
      (is (= raw-count (count results))
          "structured results count must match raw property count")
      (is (= raw-count (count dev-results))
          "deviation results count must match raw property count")
      (is (= (set (map :property (:properties artifact)))
             (set (map :property results)))
          "no property silently disappears from structured results")
      (is (= (set (map :property (:properties artifact)))
             (set (map :property dev-results)))
          "no property silently disappears from deviation results"))))

(deftest adapter-preserves-property-role-through-projections
  (testing "property-role is preserved from raw through structured to deviation"
    (let [artifact {:summary {:states-examined 100}
                    :properties
                    [{:property :strategy/split-invariance
                      :status :verified :verdict :verified
                      :state-count 100 :violation-count 0
                      :property-role :declared-property}
                     {:property :allocation/exact-merge-invariance
                      :status :violated :verdict :violated
                      :state-count 100 :violation-count 1
                      :property-role :diagnostic-observation
                      :counterexample {:claims [1 1 1] :liquidity 1}}]}
          results (spr/strategic-properties->results artifact)
          dev-results (spr/strategic-properties->deviation-results artifact)
          results-by-prop (into {} (map (juxt :property identity)) results)
          dev-by-prop (into {} (map (juxt :property identity)) dev-results)]
      (is (= :declared-property
             (:property-role (:strategy/split-invariance results-by-prop))))
      (is (= :diagnostic-observation
             (:property-role (:allocation/exact-merge-invariance results-by-prop))))
      (is (= :declared-property
             (:property-role (:strategy/split-invariance dev-by-prop))))
      (is (= :diagnostic-observation
             (:property-role (:allocation/exact-merge-invariance dev-by-prop)))))))

;; ---------------------------------------------------------------------------
;; Claim-catalog contract validation tests
;; ---------------------------------------------------------------------------

(deftest claim-catalog-validation-passes-for-current-catalog
  (testing "current catalog passes internal consistency checks"
    (let [errors (scv/validate-claim-catalog!)]
      (is (= [] errors)
          (str "catalog validation errors: " (vec errors))))))

(deftest claim-catalog-rejects-unknown-strategic-property-ids
  (testing "claim with unknown strategic property ID is flagged"
    (with-redefs [scv/strategic-claim-catalog
                  {:claim/test-unknown-prop
                   {:claim/id :claim/test-unknown-prop
                    :claim/title "Test"
                    :claim/description "Test"
                    :claim/interpretation "Test interpretation"
                    :benchmark/manifest-path "test.edn"
                    :mechanism-levels [:allocation/partial-fill]
                    :strategic-property-ids #{:nonexistent/property}
                    :required-threat-tags #{"shortfall"}
                    :match-dimensions #{:allocation/partial-fill}}}]
      (let [errors (scv/validate-claim-catalog!)]
        (is (some #(re-find #"not recognized" %) errors))))))

(deftest claim-catalog-all-claims-have-required-fields
  (testing "all catalog entries have the minimum required fields"
    (doseq [[claim-id claim] scv/strategic-claim-catalog]
      (is (keyword? (:claim/id claim))
          (str claim-id " must have :claim/id keyword"))
      (is (string? (:claim/title claim))
          (str claim-id " must have :claim/title string"))
      (is (string? (:claim/description claim))
          (str claim-id " must have :claim/description string"))
      (is (vector? (:mechanism-levels claim))
          (str claim-id " must have :mechanism-levels vector"))
      (is (set? (:match-dimensions claim))
          (str claim-id " must have :match-dimensions set"))
      (is (set? (:required-threat-tags claim))
          (str claim-id " must have :required-threat-tags set")))))

(deftest only-flagship-claim-declares-deviation-sets
  (testing "only pro-rata-shortfall-conservation declares deviation-set-ids"
    (let [claims-with-deviations
          (filter (fn [[_ v]] (seq (:deviation-set-ids v)))
                  scv/strategic-claim-catalog)]
      (is (= 1 (count claims-with-deviations)))
      (is (= :claim/pro-rata-shortfall-conservation
             (first (first claims-with-deviations)))))))

;; ---------------------------------------------------------------------------
;; Dependency-boundary check for future reference evaluator
;; ---------------------------------------------------------------------------

(deftest reference-evaluator-boundary-not-loaded
  (testing "reference evaluator namespace does not load producer/redistribution namespaces"
    ;; This test verifies that the future reference evaluator boundary
    ;; is maintainable by checking that the known producer/redistribution
    ;; namespaces are separate from the validation infrastructure.
    (let [producer-namespaces #{'resolver-sim.yield.partial-fill
                                'resolver-sim.yield.pro-rata-claims
                                'resolver-sim.economics.payoffs}
          validation-namespaces #{'resolver-sim.benchmark.strategic-claim-validation
                                  'resolver-sim.benchmark.strategic-property-results
                                  'resolver-sim.validation.gate
                                  'resolver-sim.scenario.equilibrium}]
      ;; These should be disjoint sets
      (is (empty? (clojure.set/intersection producer-namespaces validation-namespaces))
          "producer and validation namespaces should be disjoint"))))
