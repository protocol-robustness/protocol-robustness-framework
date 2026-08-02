(ns resolver-sim.yield.merge-policy-test
  "Merge policy: multi-origin deferred lineages are forbidden. Every deferred
   lineage must commit a single, consistent origin identifier across all of its
   active and archived records."
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.modules.liquid-lending :as ll]))

(def test-mod
  (ll/make-liquid-lending-module :test-mod))

(def base-world
  {:yield/indices {:test-mod {"USDC" 1.0}}
   :yield/rates   {:test-mod {"USDC" 0.05}}
   :yield/risk    {:test-mod {"USDC" {:liquidity-mode :available
                                      :loss-mode :none}}}
   :yield/held-balances {"USDC" 1000000}
   :yield/module-status {:test-mod :active}
   :block-time 1000
   :run/id "test-run"
   :execution/id "test-execution"
   :params {:scenario-id "test-scenario"}})

(defn- two-round-world []
  (-> (ll/deposit base-world test-mod {:owner/id "alice" :amount 100 :token "USDC"})
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})
      (assoc-in [:total-held :USDC] 30)
      (ll/withdraw-shared test-mod {:owner-ids ["alice"]
                                    :token "USDC"
                                    :allocation-mode :pro-rata})))

(deftest normal-lineage-is-single-origin
  (let [position (get-in (two-round-world) [:yield/positions "alice"])]
    (is (true? (ll/single-origin-lineage? position)))
    (is (= ["alice"] (ll/lineage-origin-ids position)))))

(deftest base-position-without-deferred-is-single-origin
  (let [position (get-in (ll/deposit base-world test-mod
                                     {:owner/id "alice" :amount 100 :token "USDC"})
                         [:yield/positions "alice"])]
    (is (true? (ll/single-origin-lineage? position)))
    (is (empty? (ll/lineage-origin-ids position)))))

(deftest forged-multi-origin-lineage-is-rejected
  (testing "tampering a lineage record to a second origin fails before allocation"
    (let [world (two-round-world)
          tampered (assoc-in world
                             [:yield/positions "alice" :deferred-position
                              :position/root-obligation-id]
                             "forged-origin")
          position (get-in tampered [:yield/positions "alice"])]
      (is (false? (ll/single-origin-lineage? position)))
      (is (= #{"alice" "forged-origin"}
             (set (ll/lineage-origin-ids position))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"merges more than one origin"
                            (ll/withdraw-shared tampered test-mod
                                                {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))))))

(deftest origin-position-id-divergence-is-not-a-merge
  (testing "the canonical merge discriminator is root-obligation-id, not a
            mislabeled origin position id; the latter is caught by the
            origin-reference continuity check"
    (let [world (two-round-world)
          tampered (assoc-in world
                             [:yield/positions "alice" :deferred-position
                              :deferred/original-position :position-id]
                             "forged-origin")
          position (get-in tampered [:yield/positions "alice"])]
      (is (true? (ll/single-origin-lineage? position))
          "a divergent origin position id with a single root obligation is not a merge")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"origin does not match its base position"
                            (ll/withdraw-shared tampered test-mod
                                                {:owner-ids ["alice"]
                                                 :token "USDC"
                                                 :allocation-mode :pro-rata}))))))
