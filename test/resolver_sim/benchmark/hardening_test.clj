(ns resolver-sim.benchmark.hardening-test
  "Tests for the centralised hardening-control knob resolution and for the
   de-duplication of the claimant-parallel-threshold literal across the
   command, runner, and economics layers."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.hardening :as hardening]
            [resolver-sim.config.defaults :as config-defaults]
            [resolver-sim.economics.payoffs :as payoffs]))

(deftest defaults-resolve-from-config-authority
  (testing "base defaults match config/defaults.edn :hardening and code fallbacks"
    (is (= 8 (hardening/parallel-ceiling nil {})))
    (is (= 30 (hardening/quiescence-timeout-seconds nil {})))
    (is (= 16 (hardening/claimant-parallel-threshold nil {})))
    (is (= 8 (config-defaults/default [:hardening :parallel-ceiling] :missing)))
    (is (= 30 (config-defaults/default [:hardening :quiescence-timeout-seconds] :missing)))
    (is (= 16 (config-defaults/default [:hardening :claimant-parallel-threshold] :missing)))))

(deftest env-vars-override-defaults
  (testing "PRF_* env vars override the config defaults"
    (is (= 12 (hardening/parallel-ceiling nil {"PRF_PARALLEL_CEILING" "12"})))
    (is (= 45 (hardening/quiescence-timeout-seconds nil
                                                   {"PRF_QUIESCENCE_TIMEOUT_SECONDS" "45"})))
    (is (= 24 (hardening/claimant-parallel-threshold nil
                                                     {"PRF_CLAIMANT_PARALLEL_THRESHOLD" "24"})))))

(deftest cli-overrides-env-and-defaults
  (testing "explicit CLI value wins over env and default"
    (is (= 9 (hardening/parallel-ceiling 9 {"PRF_PARALLEL_CEILING" "12"})))
    (is (= 5 (hardening/quiescence-timeout-seconds 5
                                                   {"PRF_QUIESCENCE_TIMEOUT_SECONDS" "45"})))
    (is (= 3 (hardening/claimant-parallel-threshold 3
                                                    {"PRF_CLAIMANT_PARALLEL_THRESHOLD" "24"})))))

(deftest invalid-env-falls-back-to-default
  (testing "non-numeric or malformed env values fall back rather than throw"
    (is (= 8 (hardening/parallel-ceiling nil {"PRF_PARALLEL_CEILING" "abc"})))
    (is (= 8 (hardening/parallel-ceiling nil {"PRF_PARALLEL_CEILING" ""})))
    (is (= 8 (hardening/parallel-ceiling nil {"PRF_PARALLEL_CEILING" "0"})))
    (is (= 30 (hardening/quiescence-timeout-seconds nil
                                                    {"PRF_QUIESCENCE_TIMEOUT_SECONDS" "-5"})))))

(deftest claimant-threshold-single-source-of-truth
  (testing "the 16 literal is centralised: payoffs root default reads config, not a code literal"
    (is (= 16 payoffs/*pro-rata-parallel-threshold*))
    (is (= (config-defaults/default [:hardening :claimant-parallel-threshold] 16)
           payoffs/*pro-rata-parallel-threshold*))
    (is (= payoffs/*pro-rata-parallel-threshold*
           (hardening/claimant-parallel-threshold nil {}))
        "command/runner resolution matches the economics root default")))
