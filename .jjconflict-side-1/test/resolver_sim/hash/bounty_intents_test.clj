(ns resolver-sim.hash.bounty-intents-test
  "Bounty / with-bounty intent-registry support and canonical-safe hashing.

   Verifies that:
     1. All bounty / with-bounty domain tags are registered as intents (no
        orphan tags), and the registry validates.
     2. hash-with-intent is byte-identical to the economics domain-hash call
        sites (single source of truth in resolver-sim.hash.canonical).
     3. Set-/seq-bearing context fields now hash deterministically instead of
        throwing :canonical/out-of-domain (projection gap fixed)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.economics.bounty-payable :as bp]
            [resolver-sim.economics.bounty-payable-backing :as bpb]
            [resolver-sim.economics.with-bounty.application-plan :as wb-plan]
            [resolver-sim.economics.with-bounty.identity :as wbi]
            [resolver-sim.economics.with-bounty.policy :as wbp]
            [resolver-sim.economics.with-bounty.public-result :as wbpr]
            [resolver-sim.economics.with-bounty.transition-evidence :as wbte]
            [resolver-sim.economics.with-bounty.verification-basis :as wbvb]))

(def bounty-domain-tags
  "Every bounty / with-bounty domain tag that must have a registered intent."
  [:bounty-payable-v1
   :bounty-payable-backing-v1
   :with-bounty-policy-v1
   :with-bounty-invocation-v1
   :with-bounty-obligation-v1
   :with-bounty-effect-v1
   :with-bounty-effect-set-v1
   :with-bounty-application-plan-v1
   :with-bounty-transition-evidence-v1
   :with-bounty-verification-basis-v1
   :with-bounty-public-result-v1])

(deftest test-bounty-domains-registered-as-intents
  (testing "registry validates (load-time gate also covers this)"
    (is (nil? (hc/validate-registry!))))
  (testing "every bounty domain tag has a registered intent (no orphan tags)"
    (doseq [tag bounty-domain-tags]
      (let [contract (hc/resolve-intent tag)]
        (is (= (get hc/domain-tags tag) (:intent/domain-tag contract))
            (str tag " must map to its registered domain tag"))
        (is (string? (:intent/description contract)))
        (is (fn? (:intent/projection-fn contract)))))))

(defn- policy-map []
  {:base {:operation/ref :review/complete :result/schema :review/report}
   :bounty {:id :review-completion
            :eligibility {:capability/ref {:capability/kind :economics/eligibility
                                           :capability/id :review/complete
                                           :capability/version 1}}
            :amount {:capability/ref {:capability/kind :economics/award-amount
                                      :capability/id :review/award
                                      :capability/version 1}}
            :funding {:source :declared-reserve :parameter/address [:bounties :reserve]}
            :recipient {:source :event/actor}
            :effect-contract :prf.effect/obligation-create.v2}})

(defn- policy-root []
  (wbp/with-bounty-policy-root (policy-map)))

(defn- obligation-effect []
  {:effect/type :obligation/create
   :effect/contract :prf.effect/obligation-create.v2
   :obligation/type :bounty-payable
   :obligation/id (wbi/bounty-obligation-id
                   {:operation-root "sha256:op"
                    :bounty-id :review-completion
                    :recipient :researcher/alice
                    :token :token/usdc
                    :amount 500
                    :policy-root (policy-root)})
   :obligation/amount 500
   :obligation/token :token/usdc
   :obligation/owner :researcher/alice
   :obligation/funding {:source :declared-reserve}
   :obligation/subject {:operation-root "sha256:op" :bounty-id :review-completion}
   :effect/provenance {:policy-root (policy-root)}})

(deftest test-intent-hash-equals-domain-hash
  (testing "hash-with-intent is byte-identical to the economics root functions"
    (let [payable (bp/build-bounty-payable
                   {:distribution-root "sha256:dist" :award-id "a1"
                    :beneficiary "researcher/alice" :amount 500
                    :context {:origin :stage-b}})
          backing (bpb/build-bounty-payable-backing
                   {:payable-root (:payable/hash payable)
                    :payable-id (:payable/id payable)
                    :distribution-root "sha256:dist" :amount 500
                    :source-allocations {"src-1" 500}
                    :context {:origin :stage-b}})
          effect (obligation-effect)
          plan (wb-plan/build-with-bounty-plan
                {:policy-root (policy-root)
                 :base-operation-root "sha256:op"
                 :base-result-root "sha256:op"
                 :extensions-resolution-root "sha256:res"
                 :adapter {:adapter/id :sew :adapter/version 1}
                 :effects [effect]
                 :effect-schema-roots {}
                 :declared-maximum 1000
                 :funding-available 500})
          transition (wbte/build-transition-evidence
                      {:plan (:plan plan)
                       :effect-root (wb-plan/effect-root effect)
                       :world-before-root "sha256:w1"
                       :world-after-root "sha256:w2"
                       :payable-roots [(:payable/hash payable)]
                       :backing-roots [(:backing/hash backing)]
                       :custody-adjustment-roots []
                       :idempotent? true
                       :context {:origin :stage-b}})
          basis (wbvb/build-verification-basis
                 {:subject-root "sha256:sub" :package-root "sha256:pkg"
                  :artifact-root "sha256:art"
                  :verification-contract :with-bounty.verify
                  :verification-contract-version 1
                  :entrypoint :verify-with-bounty
                  :dependency-lockfile-root "sha256:lock"
                  :runtime-root "sha256:runtime"
                  :vector-set-root "sha256:vec"
                  :expected-public-result-schema :with-bounty-public-result.v1
                  :classification-policy-root "sha256:policy"})
          result {:status :applied
                  :receipt {:composition/policy-root (policy-root)
                            :composition/base-operation-root "sha256:op"
                            :extensions/resolution-root "sha256:res"
                            :bounty/obligation-id (:obligation/id effect)
                            :bounty/effect-root (wb-plan/effect-root effect)
                            :bounty/application-plan-root (:plan/hash (:plan plan))}}]
      (is (= (hc/hash-with-intent {:hash/intent :bounty-payable-v1} payable)
             (bp/payable-hash payable)))
      (is (= (hc/hash-with-intent {:hash/intent :bounty-payable-backing-v1} backing)
             (bpb/backing-hash backing)))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-policy-v1} (policy-map))
             (wbp/with-bounty-policy-root (policy-map))))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-obligation-v1}
                                  {:operation-root "sha256:op"
                                   :bounty-id :review-completion
                                   :recipient :researcher/alice
                                   :token :token/usdc
                                   :amount 500
                                   :policy-root (policy-root)})
             (wbi/bounty-obligation-id
              {:operation-root "sha256:op"
               :bounty-id :review-completion
               :recipient :researcher/alice
               :token :token/usdc
               :amount 500
               :policy-root (policy-root)})))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-effect-v1} effect)
             (wb-plan/effect-root effect)))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-application-plan-v1}
                                  (:plan plan))
             (wb-plan/plan-hash (:plan plan))))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-transition-evidence-v1}
                                  transition)
             (wbte/transition-hash transition)))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-verification-basis-v1}
                                  basis)
             (wbvb/verification-basis-root basis)))
      (is (= (hc/hash-with-intent {:hash/intent :with-bounty-public-result-v1} result)
             (wbpr/public-result-root result))))))

(deftest test-set-bearing-context-hashes-deterministically
  (testing "payable / backing / effect context with sets no longer throws and is
            deterministic (set -> sorted-vector projection)"
    (let [payable-a (bp/build-bounty-payable
                     {:distribution-root "sha256:dist" :award-id "a1"
                      :beneficiary "researcher/alice" :amount 500
                      :context {:tags #{"x" "y" "z"} :kinds #{:a :b}}})
          payable-b (bp/build-bounty-payable
                     {:distribution-root "sha256:dist" :award-id "a1"
                      :beneficiary "researcher/alice" :amount 500
                      :context {:tags #{"x" "y" "z"} :kinds #{:a :b}}})
          backing-a (bpb/build-bounty-payable-backing
                     {:payable-root (:payable/hash payable-a)
                      :payable-id (:payable/id payable-a)
                      :distribution-root "sha256:dist" :amount 500
                      :source-allocations {"src-1" 500}
                      :context {:tags #{"x" "y"}}})
          backing-b (bpb/build-bounty-payable-backing
                     {:payable-root (:payable/hash payable-b)
                      :payable-id (:payable/id payable-b)
                      :distribution-root "sha256:dist" :amount 500
                      :source-allocations {"src-1" 500}
                      :context {:tags #{"x" "y"}}})
          effect (assoc (obligation-effect) :obligation/context {:tags #{"a" "b"}})
          effect-b (assoc (obligation-effect) :obligation/context {:tags #{"a" "b"}})]
      (is (string? (:payable/hash payable-a)))
      (is (= (:payable/hash payable-a) (:payable/hash payable-b))
          "set-bearing payable context hashes deterministically")
      (is (string? (:backing/hash backing-a)))
      (is (= (:backing/hash backing-a) (:backing/hash backing-b))
          "set-bearing backing context hashes deterministically")
      (is (string? (wb-plan/effect-root effect)))
      (is (= (wb-plan/effect-root effect) (wb-plan/effect-root effect-b))
          "set-bearing effect hashes deterministically")
      (testing "a set and its sorted-vector projection hash identically"
        (is (= (bp/payable-hash (assoc payable-a :payable/context {:tags #{"x"}}))
               (bp/payable-hash (assoc payable-a :payable/context {:tags ["x"]}))))))))

(deftest test-bounty-projections-are-canonical-safe
  (testing "every bounty intent projection is canonical-safe and deterministic"
    (doseq [intent bounty-domain-tags]
      (let [contract (hc/resolve-intent intent)
            sample (case intent
                     :with-bounty-effect-set-v1 ["plan-root" ["er1" "er2"]]
                     :with-bounty-obligation-v1 {:operation-root "r" :bounty-id :b
                                                 :recipient :a :token :t :amount 1
                                                 :policy-root "p"}
                     :with-bounty-invocation-v1 {:policy-root "p" :step/id :s
                                                 :index 1 :capability/ref {:c 1}}
                     {:sample [:a :b] :n 1})
            a ((:intent/projection-fn contract) sample intent)
            b ((:intent/projection-fn contract) sample intent)]
        (is (nil? (hc/validate-canonical-value! a)) (str intent))
        (is (= a b) (str intent " projection must be deterministic"))))))
