(ns resolver-sim.pro-rata.invocation-publication-binding-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.executable-distribution :as distribution]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.evm :as evm]
            [resolver-sim.pro-rata.invocation-publication-binding :as sut]
            [resolver-sim.pro-rata.protocol-transaction-realization :as realization]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.use-cases.application :as use-case]))

(defn- root [tag]
  (str "sha256:" (hc/domain-hash :registry tag)))

(def descriptor
  {:capability/kind :economics/allocation
   :capability/id :prf/pro-rata-allocation
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'external.clean-room/pro-rata
   :input-schema :prf/allocation-context.v1
   :output-schema :prf/allocation-result.v1})

(defn- fixture []
  (let [before {(root :liquidity) 10}
        canonical (effects/transition before [(effects/delta (root :liquidity) -1)])
        allocation {:allocation/hash (root :allocation)}
        app (evm/build-application {:state-before-root (:state-before/root canonical)
                                    :allocation-root (:allocation/hash allocation)
                                    :application-policy-root (root :policy)
                                    :state-after-root (:state-after/root canonical)
                                    :applications []})
        transition (assoc (evm/build-transition
                           {:state-before-root (:state-before/root canonical)
                            :allocation-root (:allocation/hash allocation)
                            :application-policy-root (:application-policy/root app)
                            :application-root (:application/root app)
                            :state-after-root (:state-after/root canonical)})
                          :application app)
        output (sut/build-output {:allocation allocation :pro-rata-application app
                                  :pro-rata-transition transition
                                  :canonical-transition canonical})
        invocation (use-case/capability-invocation-binding
                    {:capability descriptor :input-root (root :input)
                     :output-root (:pro-rata-output/root output) :evidence-roots #{}})
        use-case-application-base
        {:application/schema :prf/use-case-application.v1
         :application/id :test/pro-rata
         :application/use-case {:id :test/pro-rata
                                :registry-root (root :registry)
                                :definition-root (root :definition)}
         :application/composition :consecutive
         :application/capability-bindings
         [{:application.step-id :allocate :capability-binding-root (:binding/root invocation)}]
         :application/sequence-binding {:bound-sequence {:purpose :use-case-application/capability-bindings
                                                         :expected-component-count 1}
                                        :bound-sequence-root (root :sequence)}}
        use-case-application
        (assoc use-case-application-base :application/root
               (use-case/application-root use-case-application-base))
        distribution (distribution/build-distribution
                      {:executable-artifact/root (root :executable)
                       :semantic-claimant-options
                       {:execution/claimant-parallelism 8
                        :execution/claimant-parallel-threshold 1
                        :execution/quiescence-timeout-seconds 2}})
        transition-binding {:canonical-transition/root (:canonical-effect-transition/root canonical)
                            :transition-binding/root (root :transition-binding)
                            :binding/mode :effect-exact}
        protocol-realization {:canonical-transition/root (:canonical-effect-transition/root canonical)
                              :protocol-effect-realization/root (root :protocol-realization)}
        realized (realization/build {:canonical-transition-root (:canonical-effect-transition/root canonical)
                                     :transition-binding transition-binding
                                     :protocol-effect-realization protocol-realization})
        receipt-base {:schema-version "applied-effect-receipt.v1"
                      :authorization/root (root :authorization)
                      :protocol-effect-set/root (:effects/root canonical)
                      :executed-effect-set/root (:effects/root canonical)
                      :applied-adjustment-refinement/root (root :refinement)
                      :applied-adjustments/root (root :adjustments)
                      :state-before/root (:state-before/root canonical)
                      :state-after/root (:state-after/root canonical)
                      :ledger-before/root (root :ledger-before)
                      :ledger-after/root (root :ledger-after)
                      :application/status :applied}
        receipt (assoc receipt-base :applied-effect-receipt/root
                       (hc/domain-hash :applied-effect-receipt receipt-base))
        ordering (publication/build-publication-ordering
                  {:receipt receipt :realization realized :canonical-transition canonical
                   :action :pro-rata/apply :scope :test :conflict-key [:test]
                   :commit-index 1 :previous-transaction-hash nil :input-root (root :command)})
        store (publication/new-head-store)
        _ (publication/publish! store ordering nil)]
    {:use-case-application use-case-application
     :capability-binding invocation :capability-descriptor descriptor
     :executable-distribution distribution :output output :allocation allocation
     :pro-rata-application app :pro-rata-transition transition
     :canonical-transition canonical :receipt receipt
     :protocol-transaction-realization realized
     :transition-binding transition-binding
     :protocol-effect-realization protocol-realization
     :publication-ordering ordering :publication-store store}))

(deftest rooted-correspondence-requires-exact-joined-identities
  (let [resolved (fixture)
        binding (sut/build-binding resolved)
        other-distribution (distribution/build-distribution
                            {:executable-artifact/root (root :other-executable)})]
    (is (sut/binding-valid? binding resolved))
    (is (= binding (sut/build-binding resolved)))
    (is (not (sut/binding-valid? binding
                                 (assoc resolved :executable-distribution other-distribution)))
        "an existing invocation/publication binding rejects executable substitution")
    (is (not (sut/binding-valid? binding
                                 (assoc-in resolved [:capability-binding :invocation/output-root]
                                           (root :unrelated-output))))
        "the invocation output root is the exact rooted pro-rata adapter")))

(deftest correspondence-fails-closed-on-unadopted-or-tampered-inputs
  (let [resolved (fixture)
        binding (sut/build-binding resolved)
        runtime-only (distribution/build-distribution
                      {:executable-artifact/root (get-in resolved [:executable-distribution :executable-artifact/root])
                       :semantic-claimant-options
                       {:execution/claimant-parallelism 1
                        :execution/claimant-parallel-threshold 99
                        :execution/quiescence-timeout-seconds 99}})]
    (is (= (:executable-distribution/root (:executable-distribution resolved))
           (:executable-distribution/root runtime-only))
        "runtime claimant controls do not affect correspondence identity")
    (is (not (sut/binding-valid? binding
                                 (assoc resolved :publication-store (publication/new-head-store))))
        "a computed ordering is not authoritative without head adoption")
    (is (not (sut/binding-valid? (assoc binding :unknown true) resolved))
        "the correspondence shape is closed")
    (is (not (sut/binding-valid?
              (assoc binding :publication-ordering/root (root :other-ordering)) resolved))
        "each joined root is integrity checked")
    (is (not (sut/binding-valid? binding
                                 (assoc-in resolved [:output :application-policy/root]
                                           (root :other-policy))))
        "the rooted output adapter rejects an unrelated pro-rata application")
    (is (not (sut/binding-valid? binding
                                 (assoc-in resolved [:capability-descriptor :entrypoint]
                                           'other.runtime/pro-rata)))
        "an entrypoint symbol cannot substitute for the committed descriptor root")))

(deftest application-bound-publication-retains-binding-atomically
  (let [resolved (fixture)
        binding (sut/build-binding resolved)
        ordering (:publication-ordering resolved)
        store (publication/new-application-head-store)
        result (publication/publish-application-bound! store ordering binding resolved nil)]
    (is (= :committed (:status result)))
    (is (= (:pro-rata-invocation-publication-binding/root binding)
           (get-in result [:publication/head :publication/application-binding-root])))
    (is (= binding (publication/retained-application-binding store)))
    (is (sut/binding-valid? binding
                            (assoc resolved :publication-store store)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (publication/publish-application-bound!
                  (publication/new-application-head-store) ordering
                  (assoc binding :publication-ordering/root (root :other-ordering))
                  resolved nil)))
    (is (nil? (publication/retained-application-binding
               (publication/new-application-head-store))))))
