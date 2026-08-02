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
            [resolver-sim.benchmark.game-theory-validation :as sut]))

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
                          (throw (ex-info "unexpected scenario path" {:path path}))))]
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

(deftest folk-theorem-catalogue-accurately-reports-multi-epoch-only-coverage
  (let [concept (some #(when (= :folk-theorem-cooperation-region (:id %)) %)
                      (:equilibrium-concepts (sut/list-game-theory-checks)))]
    (is (some? concept))
    (is (true? (:catalogued? concept)))
    (is (true? (:implemented? concept)))
    (is (false? (:wired? concept))
        "the terminal-trace dispatcher has no multi-epoch evidence input")
    (is (re-find #"U_honest > 0" (:summary concept)))
    (is (re-find #"not wired" (:summary concept)))
    (let [trace-result (get (equilibrium/evaluate-equilibrium-concepts
                             [:folk-theorem-cooperation-region] {})
                            :folk-theorem-cooperation-region)]
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
