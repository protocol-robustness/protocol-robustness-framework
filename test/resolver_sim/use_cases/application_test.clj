(ns resolver-sim.use-cases.application-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.use-cases.application :as application]))

(defn- root-ref [value]
  (hash-ref/sha256-ref (canonical/domain-hash :registry value)))

(def capability
  {:capability/kind :economics/allocation
   :capability/id :prf/pro-rata-allocation
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'external.clean-room/pro-rata
   :input-schema :prf/allocation-context.v1
   :output-schema :prf/allocation-result.v1})

(def loaded
  {:use-case-registry/root (root-ref :registry)
   :use-cases [{:concept/id :user/pro-rata-allocation
                :use-case/allowed-capabilities
                [{:capability/kind :economics/allocation
                  :capability/id :prf/pro-rata-allocation
                  :capability/version 1}]}]})

(defn- app [left-output right-input]
  (application/build-application
   {:loaded loaded
    :use-case-id :user/pro-rata-allocation
    :application-id :user/example
    :capability-invocations
    [{:capability capability
      :input-root (root-ref :r0)
      :output-root left-output
      :evidence-roots #{(root-ref :e1)}}
     {:capability capability
      :input-root right-input
      :output-root (root-ref :r2)
      :evidence-roots #{(root-ref :e2)}}]}))

(deftest sequence-framing-and-continuity-are-distinct
  (let [r1 (root-ref :r1)
        valid (app r1 r1)
        broken (app r1 (root-ref :r9))]
    (is (= :consecutive (:application/composition valid)))
    (is (= (mapv :binding/root (:application/capability-bindings valid))
           (get-in valid [:application/sequence-binding :bound-sequence :components])))
    (is (:valid? (application/valid-application loaded [capability capability] valid)))
    (is (= [:application/consecutive-invocation-root-mismatch]
           (mapv :code (:issues (application/valid-application
                                 loaded [capability capability] broken)))))))
