(ns resolver-sim.pro-rata.effect-compilation-v2-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.effect-compilation-v2 :as sut]
            [resolver-sim.pro-rata.target-map :as target-map]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def allocation {:allocation/hash (root "1") :unallocated-residual 0
                 :rows [{:row/id :claim/alice :allocated 10 :unmet 0}]})
(def target-map-artifact (target-map/build-target-map
                          {:allocation-subjects-root (root "2") :scope-root (root "3")
                           :mapping-profile-root (root "4")
                           :targets [{:allocation/subject-id :allocation/liquidity :mapping/role :available :quantity/root (root "5")}
                                     {:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root (root "6")}
                                     {:allocation/subject-id :claim/alice :mapping/role :outstanding :quantity/root (root "7")}]}))

(deftest v2-explicitly-binds-policy-and-target-map-without-changing-v1
  (let [compiled (sut/compile-all-active {:allocation allocation :target-map target-map-artifact
                                          :allocation-policy-root (root "8")
                                          :effect-compilation-semantics-root (root "9")})]
    (is (= "pro-rata-effect-compilation.v2" (:schema-version compiled)))
    (is (= (:effect-compilation/root compiled) (sut/compilation-root compiled)))
    (is (= (:target-map/root target-map-artifact) (:target-map/root compiled)))
    (is (not= (:effect-compilation/root compiled)
              (:effect-compilation/root
               (sut/compile-all-active {:allocation allocation :target-map target-map-artifact
                                        :allocation-policy-root (root "a")
                                        :effect-compilation-semantics-root (root "9")}))))))
