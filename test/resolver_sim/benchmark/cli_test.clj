(ns resolver-sim.benchmark.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.tools.cli :as cli-opts]
            [resolver-sim.benchmark.cli :as cli]
            [resolver-sim.benchmark.integrity :as integrity]
            [resolver-sim.benchmark.registry :as registry]
            [resolver-sim.hash.canonical :as hc]))

(deftest game-theory-strategic-flag-parses-to-strategic
  (testing "the --strategic flag routes the game-theory dispatch to the strategic branch"
    (let [all-opts (into cli/cli-options
                         (get cli/subcommand-options "validate-game-theory"))
          {:keys [options errors]}
          (cli-opts/parse-opts ["--strategic"] all-opts)]
      (is (nil? errors))
      (is (true? (:strategic options))
          "dispatch-game-theory reads :strategic; a mismatch here is the dead-flag regression"))))

(deftest record-history-best-effort-ignores-write-failures
  (testing "History write failures only emit a warning"
    (let [calls (atom [])]
      (with-redefs [registry/record-entry (fn [entry]
                                            (swap! calls conj entry)
                                            (throw (ex-info "boom" {:entry entry})))]
        (is (nil? (#'cli/record-history-best-effort! {:benchmark {:benchmark/id "bm-1"}})))
        (is (= 1 (count @calls)))))))

(deftest benchmark-index-retains-canonical-benchmark-ids
  (let [entries (:benchmarks (#'cli/load-index))
        replay (first (filter #(= :benchmark/prf-deterministic-replay-v1
                                  (:benchmark/id %))
                              entries))]
    (is replay)
    (is (= "prf-core/prf-deterministic-replay-v1" (:id replay)))
    (is (= :active (:status replay)))
    (is (pos? (:claims replay)))))

(deftest legacy-bundle-hashes-remain-verifiable
  (testing "Legacy bundles with explicit version tags remain verifiable"
    (let [computed-hash "3438c8d9df37a645aae36a977c01c727ccb8fa405e4ed7ae076fc8ad5fd86e43"
          legacy {:benchmark {:benchmark/id :benchmark/test}
                  :metrics {:total 1 :passed 1}
                  :run/manifest {:manifest/version "run-manifest.v1"}
                  :benchmark-certification {:certification-hash "later"}
                  :evidence/commitment-version "bundle-root.v1"
                  :evidence/hash computed-hash}]
      (is (= {:hash-ok? true
              :scheme :legacy-v1
              :computed-hash computed-hash
              :reason :ok}
             (integrity/verify-bundle-hash legacy))))))

(deftest non-interactive-runs-suppress-the-post-run-prompt
  (is (#'cli/interactive-run? true {}))
  (is (not (#'cli/interactive-run? true {:non-interactive true})))
  (is (not (#'cli/interactive-run? false {}))))
