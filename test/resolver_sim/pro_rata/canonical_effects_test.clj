(ns resolver-sim.pro-rata.canonical-effects-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.pro-rata.canonical-effects :as sut]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))

(deftest normalization-is-order-independent-and-composes-deltas
  (let [a (root "1")
        b (root "2")
        effects [(sut/delta a 20) (sut/delta a -7) (sut/delta a 3)
                 (sut/delta b 4)]]
    (is (= [(sut/delta a 16) (sut/delta b 4)]
           (sut/normalize-effects effects)))
    (is (= (sut/effect-root (sut/normalize-effects effects))
           (sut/effect-root (sut/normalize-effects (reverse effects)))))))

(deftest generic-kernel-derives-not-accepts-state-after
  (let [liquidity (root "1")
        filled-a (root "2")
        outstanding-a (root "3")
        before {liquidity 100 filled-a 0 outstanding-a 60}
        tx (sut/transition before [(sut/delta liquidity -50)
                                   (sut/delta filled-a 50)
                                   (sut/delta outstanding-a -50)])]
    (is (= 50 (get (:state-after tx) liquidity)))
    (is (= 10 (get (:state-after tx) outstanding-a)))
    (is (= (sut/state-root (:state-after tx)) (:state-after/root tx)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/apply-effects before [(sut/delta outstanding-a -61)])))))

(deftest pro-rata-compilation-is-protocol-neutral
  (let [liquidity (root "1")
        filled (root "2")
        outstanding (root "3")
        allocation {:rows [{:row/id :claim-a :allocated 50}]}
        effects (sut/compile-pro-rata-effects allocation
                                              {:liquidity/root liquidity
                                               :claim-a {:filled/root filled
                                                         :outstanding/root outstanding}})]
    (is (= [(sut/delta liquidity -50)
            (sut/delta filled 50)
            (sut/delta outstanding -50)]
           effects))))
