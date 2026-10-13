(ns resolver-sim.research.repository-boundary-golden-test
  "Cross-boundary root vectors. These pin current schema/domain encodings before
   any package or repository extraction; they do not broaden artifact meaning."
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.accounting.reductions :as accounting]
            [resolver-sim.research.accelerated-accounting :as legacy-accounting]
            [resolver-sim.network.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenges :as legacy-challenges]
            [resolver-sim.research.semantic-accounting-test :as accounting-fixture]))

(deftest accounting-reduction-v1-golden-roots
  (let [{:keys [contract request plan]} (accounting-fixture/accounting-fixture)
        [unit-a unit-b] (:partition/work-units plan)
        partial-a (accounting-fixture/accounting-partial
                   request unit-a (:implementation-a accounting-fixture/roots)
                   (:runner-a accounting-fixture/roots) (:run-a accounting-fixture/roots))
        partial-b (accounting-fixture/accounting-partial
                   request unit-b (:implementation-b accounting-fixture/roots)
                   (:runner-b accounting-fixture/roots) (:run-b accounting-fixture/roots))
        reconciliation
        (accounting/reconcile request contract plan
                              (-> {} (accounting/ingest-partial partial-a)
                                  (accounting/ingest-partial partial-b)))]
    (is (= "sha256:5a0f00177c21e8cb399c724937f51fe221fdb2decb2c24aaf1db1d087b002a76"
           (:accounting-reduction-contract/root contract)))
    (is (= "sha256:c262c3a763acd433fa7af9c28483fc35390876af17a8638eb84cd23d3732e595"
           (:accounting-request/root request)))
    (is (= "sha256:ff55162a5d26824cf24daaa71b379bbd95c9ae454f963549741067c84f0e8eb2"
           (:accounting-partition-plan/root plan)))
    (is (= ["sha256:57631f9eaa2f458cdd544ee2bc00ef8a83b61897a2290b13c65ca4342d8a80b6"
            "sha256:2c76ee93504160f4a3049fda3cc4e0f537dcc5cb12b0717ec1ab2a8162e216d3"]
           (mapv :accounting-work-unit/root (:partition/work-units plan))))
    (is (= ["sha256:1db86af584f39566f04cacc3048731638fb9ac4bf9dce568b59e736c460bac49"
            "sha256:943abab383092b8a0010fbc3a1863d75b73f267f49b6fae5b2285cd4bd0ebc73"]
           (mapv :accounting-partial-result/root [partial-a partial-b])))
    (is (= ["sha256:54746c5068c976d2d2f60ffb5cb230507c0e34a9e23eefffb406a3d7a1cccf6b"
            "sha256:ad94e0aa1d2b937a7b0e8f5947e991e9a727a0636c4fe159e4b780cffd5276ca"]
           (mapv accounting/partial-semantic-root [partial-a partial-b])))
    (is (= "sha256:abd44762c15aaf139b553769fa87083024f463bbff11c04e49e97196d8fa3dbb"
           (:accounting-reconciliation/root reconciliation)))
    (is (= "sha256:a531bb7d1333947e628bbdb5e43be2c44fa2c861d96f396f890927a048d31721"
           (:canonical-accounting-result/root reconciliation)))
    ;; Compatibility aliases preserve the old API without redefining roots.
    (is (= (:accounting-request/root request)
           (:accounting-request/root
            (legacy-accounting/build-request
             {:accounting-domain (:accounting/domain request)
              :cutpoint-root (:accounting/cutpoint-root request)
              :accounting-definition-root (:accounting-definition/root request)
              :accounting-policy-root (:accounting-policy/root request)
              :reduction-contract contract
              :sources (:accounting/sources request)}))))))

(deftest challenge-boundary-golden-roots
  (let [root (fn [digit] (str "sha256:" (apply str (repeat 64 digit))))
        network (challenges/network-root :network/golden)
        policy (challenges/build-reward-policy "TEST-POINTS")
        challenge (challenges/build-challenge
                   {:id :challenge/golden
                    :subject-root (root "8")
                    :subject-revision (root "6")
                    :contribution-type :adversarial-finding
                    :acceptance-criteria
                    {:proposition-id :claim/golden
                     :metric-id :metric/golden
                     :result-classifications #{:counterexample-found}}
                    :base-reward 100 :capacity 6
                    :reward-policy-root (:artifact/root policy)})
        action (challenges/action
                {:network/root network} (root "2") (root "e") :claim
                (:artifact/root challenge) "golden-nonce-1"
                {:challenge/root (:artifact/root challenge)})]
    (is (= "sha256:02c368061addd8069ad269f63ff05f56c93a8089ad5fe892ae65ed03843b3dbb"
           network))
    (is (= "sha256:02c0e614cd6e23da0a2df92e88dd2a51d243014cf5afaa023c7bd11c90f40a68"
           (:artifact/root policy)))
    (is (= "sha256:5770a193515193edf077effd1dfd9fee852ed1660c4a45288147466ad2b3d6b9"
           (:artifact/root challenge)))
    (is (= "sha256:a795554d6ce522d2c38e90b68d34195c471378a45e48e51c59574bdd5ae70a65"
           (:action/root action)))
    (is (= network (legacy-challenges/network-root :network/golden)))))
