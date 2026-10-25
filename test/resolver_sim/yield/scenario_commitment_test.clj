(ns resolver-sim.yield.scenario-commitment-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [resolver-sim.yield.scenario-commitment :as sut]))

(def ^:private provenance-inputs
  {:generator/id "resolver-sim.generators.yield.scenario/gen-yield-scenario"
   :generator/version "1"
   :generator/source-commitment (str "sha256:" (apply str (repeat 64 "a")))
   :rng/algorithm "java.util.Random/v1"
   :rng/seed 123
   :generation/options {:profile "aave-v3" :token "USDC"}})

(defn- frozen-vector []
  (edn/read-string (slurp (io/resource "test-vectors/gen-yield/materialized-v1.edn"))))

(deftest frozen-scenario-commitment-vector
  (let [{:keys [scenario expected-root expected-bytes]} (frozen-vector)
        commitment (sut/scenario-commitment scenario)]
    (is (= expected-root (:canonical/hash commitment)))
    (is (= expected-bytes (:canonical/bytes commitment)))
    (is (= commitment (sut/scenario-commitment (edn/read-string (pr-str scenario)))))
    (is (= (:yield-scenario/schema (:yield-scenario/body commitment))
           sut/materialized-schema))))

(deftest materialized-identity-excludes-only-generation-identifiers
  (let [scenario (:scenario (frozen-vector))
        base-root (:canonical/hash (sut/scenario-commitment scenario))
        renamed (-> scenario
                    (assoc :scenario-id "new-id" :id "another-id" :seed 999))
        changed (assoc-in scenario [:events 0 :params :amount] 10001)]
    (is (= base-root (:canonical/hash (sut/scenario-commitment renamed))))
    (is (not= base-root (:canonical/hash (sut/scenario-commitment changed))))))

(deftest unsupported-schema-fields-and-ambiguous-numbers-fail-closed
  (let [scenario (:scenario (frozen-vector))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported top-level fields"
                          (sut/scenario-commitment (assoc scenario :mystery true))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema version is not supported"
                          (sut/scenario-commitment (assoc scenario :schema-version "9.0"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collapse under scenario normalization"
                          (sut/scenario-commitment
                           (assoc scenario :options (array-map "1" :string-key 1 :integer-key)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collapse under scenario normalization"
                          (sut/scenario-commitment
                           (assoc scenario "1" :string-key 1 :integer-key))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collapse under scenario normalization"
                          (sut/scenario-commitment
                           (assoc scenario :options (array-map ":x" :string-key :x :keyword-key)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collapse under scenario normalization"
                          (sut/scenario-commitment
                           (assoc scenario :options #{":x" :x}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-finite or unsupported number"
                          (sut/scenario-commitment
                           (assoc-in scenario [:yield-config :modules "aave-v3" :tokens "USDC" :apy]
                                     Double/NaN))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be non-negative integers"
                          (sut/scenario-commitment
                           (assoc-in scenario [:events 0 :time] 1.5))))))

(deftest provenance-commits-to-reproduction-inputs-separately
  (let [scenario (:scenario (frozen-vector))
        provenance (sut/generation-provenance-commitment scenario provenance-inputs)
        changed-seed (assoc provenance-inputs :rng/seed 124)
        changed-option (assoc-in provenance-inputs [:generation/options :profile] "fixed-rate")]
    (is (= (:canonical/hash (sut/scenario-commitment scenario))
           (get-in provenance [:yield-generation/body :yield-scenario/root])))
    (is (= {:valid? true
            :scenario-root (get-in provenance [:yield-generation/body :yield-scenario/root])
            :provenance-root (:canonical/hash provenance)}
           (sut/verify-generation-provenance! scenario provenance)))
    (is (not= (:canonical/hash provenance)
              (:canonical/hash (sut/generation-provenance-commitment scenario changed-option))))
    (is (not= (:canonical/hash provenance)
              (:canonical/hash
               (sut/generation-provenance-commitment
                (assoc scenario :seed 124) changed-seed))))
    (is (= (:canonical/hash (sut/scenario-commitment scenario))
           (:canonical/hash
            (sut/scenario-commitment (assoc scenario :seed 124 :scenario-id "new-seed-id")))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match"
                          (sut/verify-generation-provenance!
                           (assoc-in scenario [:events 0 :params :amount] 2)
                           provenance)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required inputs"
                          (sut/generation-provenance-commitment
                           scenario (dissoc provenance-inputs :rng/algorithm))))))

(deftest provenance-does-not-accept-unbound-or-malformed-source-identity
  (let [scenario (:scenario (frozen-vector))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"seed field"
                          (sut/generation-provenance-commitment
                           scenario (assoc provenance-inputs :rng/seed 124))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lowercase sha256 reference"
                          (sut/generation-provenance-commitment
                           scenario (assoc provenance-inputs :generator/source-commitment "candidate"))))))
