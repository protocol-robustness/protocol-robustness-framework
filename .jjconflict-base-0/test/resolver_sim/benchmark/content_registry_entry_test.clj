(ns resolver-sim.benchmark.content-registry-entry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.content-registry-entry :as cre]
            [resolver-sim.benchmark.research-benchmark-model :as model]))

(deftest build-entry-requires-model-root
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"model-root"
                        (cre/build-entry {:benchmark/id :test/bm}))))

(deftest build-entry-minimal
  (let [entry (cre/build-entry
               {:benchmark/id :test/benchmark
                :benchmark/version 1
                :benchmark/research-question "Test?"
                :benchmark/model-root "sha256:model"
                :benchmark/evaluation-policy-root "sha256:eval"})]
    (is (cre/entry-valid? entry))
    (is (= :test/benchmark (:benchmark/id entry)))
    (is (= "sha256:model" (:benchmark/model-root entry)))
    (is (str/starts-with? (cre/content-root entry) "sha256:"))))

(deftest model-schema-default-is-producer-version
  (testing "the content-registry entry defaults its model-schema to (and validates
            against) the authoritative research-benchmark-model schema version, so
            producer and consumer cannot diverge"
    (let [entry (cre/build-entry
                 {:benchmark/id :test/benchmark
                  :benchmark/version 1
                  :benchmark/research-question "Test?"
                  :benchmark/model-root "sha256:model"
                  :benchmark/evaluation-policy-root "sha256:eval"})]
      (is (= model/schema-version (:benchmark/model-schema entry))
          "default model-schema is the producer's authoritative version")
      (is (cre/entry-valid? entry))
      (is (str/starts-with? (cre/content-root entry) "sha256:")))))

(deftest status-root-not-modelled
  (let [entry (cre/build-entry
               {:benchmark/id :test/bm
                :benchmark/model-root "sha256:m"
                :benchmark/evaluation-policy-root "sha256:e"})]
    (is (= {:status :not-modelled :root nil} (:benchmark/incentive-model-root entry)))
    (is (= {:status :not-modelled :root nil} (:benchmark/adversary-model-root entry)))))

(deftest status-root-modelled
  (let [entry (cre/build-entry
               {:benchmark/id :test/bm
                :benchmark/model-root "sha256:m"
                :benchmark/incentive-model-root "sha256:incentives"
                :benchmark/evaluation-policy-root "sha256:e"})]
    (is (= {:status :modelled :root "sha256:incentives"} (:benchmark/incentive-model-root entry)))))

(deftest status-root-externally-defined
  (let [entry (cre/build-entry
               {:benchmark/id :test/bm
                :benchmark/model-root "sha256:m"
                :benchmark/falsifier-root {:status :externally-defined :root "sha256:ext"}
                :benchmark/evaluation-policy-root "sha256:e"})]
    (is (= {:status :externally-defined :root "sha256:ext"} (:benchmark/falsifier-root entry)))))

(deftest status-root-deferred-with-reason-and-version
  (let [entry (cre/build-entry
               {:benchmark/id :test/bm
                :benchmark/model-root "sha256:m"
                :benchmark/generator-root {:status :deferred :root nil
                                           :reason-code :awaiting-incentive-model
                                           :expected-version 2}
                :benchmark/evaluation-policy-root "sha256:e"})]
    (is (= :deferred (get-in entry [:benchmark/generator-root :status])))
    (is (nil? (get-in entry [:benchmark/generator-root :root])))))

(deftest valid-component-status-catalog
  (is (cre/valid-component-status? :modelled))
  (is (cre/valid-component-status? :not-modelled))
  (is (cre/valid-component-status? :not-applicable))
  (is (cre/valid-component-status? :externally-defined))
  (is (cre/valid-component-status? :deferred))
  (is (not (cre/valid-component-status? :invalid))))

(deftest valid-component-map-modelled-requires-root
  (is (:valid? (cre/valid-component-map? {:status :modelled :root "sha256:ok"})))
  (is (not (:valid? (cre/valid-component-map? {:status :modelled :root nil})))))

(deftest valid-component-map-not-modelled-rejects-root
  (is (:valid? (cre/valid-component-map? {:status :not-modelled :root nil})))
  (is (not (:valid? (cre/valid-component-map? {:status :not-modelled :root "sha256:bad"})))))

(deftest valid-component-map-not-applicable-rejects-root
  (is (:valid? (cre/valid-component-map? {:status :not-applicable :root nil})))
  (is (not (:valid? (cre/valid-component-map? {:status :not-applicable :root "sha256:bad"})))))

(deftest status-root-invalid-map-throws
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid component state"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/incentive-model-root {:status :modelled :root nil}
                          :benchmark/evaluation-policy-root "sha256:e"}))))

(deftest content-root-excludes-provenance
  (let [a (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:m"
            :benchmark/evaluation-policy-root "sha256:e"
            :benchmark/provenance {:authors ["a"]}})
        b (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:m"
            :benchmark/evaluation-policy-root "sha256:e"
            :benchmark/provenance {:authors ["b"]}})]
    (is (cre/same-content? a b))
    (is (not= (:benchmark/registry-entry-hash a) (:benchmark/registry-entry-hash b)))))

(deftest content-root-changes-with-model-root
  (let [a (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:ma"
            :benchmark/evaluation-policy-root "sha256:e"})
        b (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:mb"
            :benchmark/evaluation-policy-root "sha256:e"})]
    (is (not (cre/same-content? a b)))))

(deftest content-root-changes-with-incentive-model
  (let [a (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:m"
            :benchmark/evaluation-policy-root "sha256:e"
            :benchmark/incentive-model-root "sha256:i1"})
        b (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:m"
            :benchmark/evaluation-policy-root "sha256:e"
            :benchmark/incentive-model-root "sha256:i2"})]
    (is (not (cre/same-content? a b)))))

(deftest content-root-rejects-supplied-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"content-root"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/evaluation-policy-root "sha256:e"
                          :benchmark/content-root "sha256:wrong"}))))

(deftest content-root-accepts-supplied-match
  (let [a (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:m"
            :benchmark/evaluation-policy-root "sha256:e"})
        computed-root (:benchmark/content-root a)
        b (cre/build-entry
           {:benchmark/id :test/bm
            :benchmark/model-root "sha256:m"
            :benchmark/evaluation-policy-root "sha256:e"
            :benchmark/content-root computed-root})]
    (is (= (:benchmark/content-root a) (:benchmark/content-root b)))))

(deftest deferred-status-requires-reason-code
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :reason-code"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/generator-root {:status :deferred :root nil}
                          :benchmark/evaluation-policy-root "sha256:e"}))))

(deftest deferred-status-requires-expected-version
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :expected-version"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/generator-root {:status :deferred :root nil
                                                     :reason-code :awaiting-model}
                          :benchmark/evaluation-policy-root "sha256:e"}))))

(deftest deferred-status-with-reason-and-version-valid
  (let [entry (cre/build-entry
               {:benchmark/id :test/bm
                :benchmark/model-root "sha256:m"
                :benchmark/generator-root {:status :deferred :root nil
                                           :reason-code :awaiting-incentive-model
                                           :expected-version 2}
                :benchmark/evaluation-policy-root "sha256:e"})]
    (is (cre/entry-valid? entry))))

(deftest deferred-status-rejects-non-nil-root
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires nil root"
                        (cre/build-entry
                         {:benchmark/id :test/bm
                          :benchmark/model-root "sha256:m"
                          :benchmark/generator-root {:status :deferred :root "sha256:present"
                                                     :reason-code :awaiting
                                                     :expected-version 1}
                          :benchmark/evaluation-policy-root "sha256:e"}))))

(deftest provisional-status-requires-root
  (let [entry (cre/build-entry
               {:benchmark/id :test/bm
                :benchmark/model-root "sha256:m"
                :benchmark/generator-root {:status :provisional :root "sha256:draft"}
                :benchmark/evaluation-policy-root "sha256:e"})]
    (is (cre/entry-valid? entry))))

(deftest validate-entry-rejects-missing-content-root
  (is (not (:valid? (cre/validate-entry {:schema-version "benchmark-content-registry-entry.v1"})))))
