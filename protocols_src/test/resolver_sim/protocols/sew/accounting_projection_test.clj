(ns resolver-sim.protocols.sew.accounting-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.accounting-projection :as projection]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.protocols.sew.projection :as sew-projection]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.protocol :as proto]))

(def asset-a {:schema-version "prf/evm-erc20-asset.v1"
              :chain-id 8453
              :token-address "0x0000000000000000000000000000000000000001"
              :unit :erc20-base-unit
              :asset/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})

(def cutpoint {:mode :simulation :world-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"})
(def sources {:ledger-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"})
(def complete {:status :complete :from "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
               :through "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
               :covered-sources #{:ledger} :missing-sources [] :reason nil})

(defn- row [held]
  {:asset/root (:asset/root asset-a)
   :asset/identity asset-a
   :inflows {:external-funding 1000}
   :outflows {:release-withdrawals 100}
   :held (merge {:active-principal 900} held)})

(deftest projection-is-per-asset-and-rooted
  (let [p (projection/accounting-projection {:cutpoint cutpoint
                                             :source-roots sources
                                             :completeness complete
                                             :assets [(row {})]})]
    (is (= :pass (get-in p [:accounting-projection/summary :conservation-status])))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:accounting-projection/root p)))
    (is (:valid? (projection/accounting-projection-valid? p)))))

(deftest incomplete-projection-cannot-pass
  (let [p (projection/accounting-projection
           {:cutpoint cutpoint
            :source-roots sources
            :completeness (assoc complete :status :incomplete
                                  :missing-sources [:claimable]
                                  :reason :missing-source)
            :assets [(row {})]})]
    (is (= :not-evaluated
           (get-in p [:accounting-projection/summary :conservation-status])))))

(deftest accounting-failure-isolated-to-asset
  (let [p (projection/accounting-projection
           {:cutpoint cutpoint
            :source-roots sources
            :completeness complete
            :assets [(row {:active-principal 899})]})]
    (is (= :fail
           (get-in p [:accounting-projection/summary :conservation-status])))
    (is (= 1 (get-in p [:accounting-projection/summary
                        :difference-by-asset (:asset/root asset-a)])))))

(deftest unknown-bucket-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/accounting-projection
                {:cutpoint cutpoint
                 :source-roots sources
                 :completeness complete
                 :assets [(row {:not-a-bucket 1})]}))))

(deftest projection-root-is-independent-of-covered-source-set-order
  (let [p1 (projection/accounting-projection
            {:cutpoint cutpoint
             :source-roots sources
             :completeness (assoc complete :covered-sources #{:ledger :claimable})
             :assets [(row {})]})
        p2 (projection/accounting-projection
            {:cutpoint cutpoint
             :source-roots sources
             :completeness (assoc complete :covered-sources #{:claimable :ledger})
             :assets [(row {})]})]
    (is (= (:accounting-projection/root p1)
           (:accounting-projection/root p2)))))

(deftest missing-projection-is-not-a-pass
  (let [result (invariants/accounting-projection? {})]
    (is (false? (:holds? result)))
    (is (= :not-evaluated (:status result)))
    (is (= :accounting-projection-missing
           (get-in result [:violations 0 :type])))))

(deftest conservation-and-solvency-remain-separate
  (let [p (projection/accounting-projection
           {:cutpoint cutpoint
            :source-roots sources
            :completeness complete
            :assets [(row {})]})
        accounting (invariants/accounting-projection? {:accounting-projection p})]
    (is (true? (:holds? accounting)))
    (is (= :pass (:conservation-status
                  (projection/accounting-projection-valid? p))))))

(deftest derivation-requires-explicit-asset-mapping
  (let [result (projection/derive-accounting-projection
                {:total-principal-deposited {:USDC 100}})]
    (is (= :not-evaluated (:accounting-projection/status result)))
    (is (= :missing-explicit-asset-mapping
           (:accounting-projection/reason result)))))

(deftest derivation-does-not-guess-incomplete-accounting
  (let [asset (assoc asset-a :asset/root (:asset/root asset-a))
        world {:accounting/assets {:USDC asset}
               :accounting/world-root "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
               :accounting/source-roots {:ledger-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}
               :accounting/from "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
               :accounting/through "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
               :total-principal-deposited {:USDC 100}
               :total-held {:USDC 100}}
        result (projection/derive-accounting-projection world)]
    (is (= :not-evaluated
           (get-in result [:accounting-projection/summary :conservation-status])))
    (is (= :incomplete
           (get-in result [:accounting-projection/completeness :status])))))

(deftest metadata-constructor-is-explicit
  (let [world (projection/with-accounting-metadata
                {}
                {:assets {:USDC asset-a}
                 :source-roots sources
                 :world-root (:world-root cutpoint)
                 :from (:from complete)
                 :through (:through complete)
                 :covered-sources #{:ledger-root}
                 :complete? true})
        result (projection/derive-accounting-projection world)]
    (is (= :pass (get-in result [:accounting-projection/summary :conservation-status])))
    (is (= :complete
           (get-in result [:accounting-projection/completeness :status])))))

(deftest metadata-constructor-rejects-missing-commitments
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata {} {:assets {:USDC asset-a}}))))

(deftest public-target-derives-from-explicitly-configured-empty-world
  (let [world (-> (types/empty-world 1000)
                  (assoc :total-principal-deposited {:USDC 1000}
                         :total-held {:USDC 900}
                         :total-withdrawn {:USDC 100})
                  (projection/with-accounting-metadata
                   {:assets {:USDC asset-a}
                    :source-roots sources
                    :world-root (:world-root cutpoint)
                    :from (:from complete)
                    :through (:through complete)
                    :covered-sources #{:ledger-root}
                    :complete? true}))
        result (sew-projection/accounting-projection-v1 world)]
    (is (= :pass (get-in result [:accounting-projection/summary :conservation-status])))
    (is (:valid? (:accounting-projection/validation result)))))

(deftest scenario-accounting-metadata-is-attached-during-init
  (let [world (proto/init-world
               sew/protocol
               {:initial-block-time 1000
                :accounting-metadata {:assets {:USDC asset-a}
                                      :source-roots sources
                                      :world-root (:world-root cutpoint)
                                      :from (:from complete)
                                      :through (:through complete)
                                      :covered-sources #{:ledger-root}
                                      :complete? true}})
        result (sew-projection/accounting-projection-v1 world)]
    (is (= asset-a (get-in world [:accounting/assets :USDC])))
    (is (= :pass (get-in result [:accounting-projection/summary :conservation-status])))))

(deftest scenario-rejects-malformed-accounting-metadata
  (is (thrown? clojure.lang.ExceptionInfo
               (proto/init-world
                sew/protocol
                {:initial-block-time 1000
                 :accounting-metadata {:assets {:USDC asset-a}}}))))

(deftest scenario-rejects-non-map-accounting-metadata
  (is (thrown? clojure.lang.ExceptionInfo
               (proto/init-world
                sew/protocol
                {:initial-block-time 1000
                 :accounting-metadata :invalid}))))

(deftest scenario-metadata-adapter-does-not-infer-from-token-params
  (let [world (sew-projection/attach-scenario-accounting-metadata
               {}
               {:token-params {:token "0xUSDC"}})]
    (is (= {} world))))

(deftest check-all-exposes-accounting-projection-status
  (let [legacy (invariants/check-all {})
        configured (-> (types/empty-world 1000)
                       (projection/with-accounting-metadata
                        {:assets {:USDC asset-a}
                         :source-roots sources
                         :world-root (:world-root cutpoint)
                         :from (:from complete)
                         :through (:through complete)
                         :covered-sources #{:ledger-root}
                         :complete? true})
                       invariants/check-all)]
    (is (= :not-evaluated
           (get-in legacy [:results :accounting-projection :status])))
    (is (= :evaluated
           (get-in configured [:results :accounting-projection :status])))))

(deftest metadata-rejects-asset-without-valid-root
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC (dissoc asset-a :asset/root)}
                 :source-roots sources
                 :world-root (:world-root cutpoint)}))))

(deftest metadata-rejects-asset-with-unknown-field
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC (assoc asset-a :symbol "USDC")}
                 :source-roots sources
                 :world-root (:world-root cutpoint)}))))

(deftest metadata-rejects-asset-with-wrong-unit
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC (assoc asset-a :unit :decimal)}
                 :source-roots sources
                 :world-root (:world-root cutpoint)}))))

(deftest metadata-rejects-duplicate-asset-roots
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC asset-a
                          :USDT (assoc asset-a :chain-id 1)}
                 :source-roots sources
                 :world-root (:world-root cutpoint)}))))

(deftest metadata-rejects-malformed-source-root
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC asset-a}
                 :source-roots {:ledger-root "not-a-root"}
                 :world-root (:world-root cutpoint)}))))

(deftest metadata-allows-explicit-empty-source
  (is (map? (projection/with-accounting-metadata
             {}
             {:assets {:USDC asset-a}
              :source-roots {:ledger-root :empty}
              :world-root (:world-root cutpoint)}))))

(deftest metadata-rejects-malformed-cutpoint
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC asset-a}
                 :source-roots sources
                 :world-root (:world-root cutpoint)
                 :cutpoint {:mode :simulation :world-root "bad"}}))))

(deftest metadata-rejects-cutpoint-without-mode
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC asset-a}
                 :source-roots sources
                 :world-root (:world-root cutpoint)
                 :cutpoint {:world-root (:world-root cutpoint)}}))))

(deftest complete-metadata-requires-range-and-sources
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/with-accounting-metadata
                {}
                {:assets {:USDC asset-a}
                 :source-roots sources
                 :world-root (:world-root cutpoint)
                 :covered-sources #{:ledger-root}
                 :complete? true}))))

(deftest incomplete-metadata-may-omit-range
  (is (map? (projection/with-accounting-metadata
             {}
             {:assets {:USDC asset-a}
              :source-roots sources
              :world-root (:world-root cutpoint)
              :complete? false}))))
