(ns resolver-sim.pro-rata.effect-compilation-binding-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding :as sut]
            [resolver-sim.pro-rata.effect-compilation-v2 :as compilation]
            [resolver-sim.pro-rata.target-map :as target-map]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))

(deftest retained-bodies-must-exactly-reproduce-the-compilation-and-transition-effects
  (let [allocation {:allocation/hash (root "1")
                    :unallocated-residual 0
                    :rows [{:row/id :claim/alice :allocated 10 :unmet 0}]}
        target-map-artifact (target-map/build-target-map
                             {:allocation-subjects-root (root "2")
                              :scope-root (root "3")
                              :mapping-profile-root (root "4")
                              :targets [{:allocation/subject-id :allocation/liquidity
                                         :mapping/role :available
                                         :quantity/root (root "5")}
                                        {:allocation/subject-id :claim/alice
                                         :mapping/role :filled
                                         :quantity/root (root "6")}
                                        {:allocation/subject-id :claim/alice
                                         :mapping/role :outstanding
                                         :quantity/root (root "7")}]})
        artifact (compilation/compile-all-active
                  {:allocation allocation
                   :target-map target-map-artifact
                   :allocation-policy-root (root "8")
                   :effect-compilation-semantics-root (root "9")})
        raw-effects (effects/compile-pro-rata-effects
                     allocation
                     {:liquidity/root (root "5")
                      :claim/alice {:filled/root (root "6")
                                    :outstanding/root (root "7")}})
        canonical (effects/transition {(root "5") 10 (root "7") 10} raw-effects)
        binding (sut/build artifact canonical)
        bodies {(:allocation/hash allocation) allocation
                (:target-map/root target-map-artifact) target-map-artifact}
        resolve-body #(get bodies %)
        attack-effects [(effects/delta (root "6") 10)]
        attack-canonical (effects/transition {} attack-effects)
        attack-base (assoc artifact :effects/root (:effects/root attack-canonical))
        attack (assoc attack-base :effect-compilation/root
                      (compilation/compilation-root attack-base))
        attack-binding (sut/build attack attack-canonical)]
    (is (= artifact (sut/recompute artifact resolve-body)))
    (is (= raw-effects (-> (sut/recompute artifact resolve-body) meta :effects)))
    (is (sut/valid? binding artifact canonical resolve-body))
    (is (not (sut/valid? binding artifact canonical)))
    ;; The forged artifact and transition are individually structural, but its
    ;; effect root cannot be reproduced from the retained source bodies.
    (is (not (sut/valid? attack-binding attack attack-canonical resolve-body)))))

(deftest unknown-retained-target-map-profile-fails-closed
  (let [allocation {:allocation/hash (root "a") :unallocated-residual 0 :rows []}
        target-map-artifact {:schema-version "unknown-profile.v1"
                             :allocation-subjects/root (root "b")
                             :scope/root (root "c")
                             :mapping-profile/root (root "d")
                             :targets []}
        target-map-artifact (assoc target-map-artifact :target-map/root
                                   (target-map/target-map-root target-map-artifact))
        artifact-base {:schema-version compilation/schema-version
                       :realized-allocation/root (:allocation/hash allocation)
                       :allocation-policy/root (root "e")
                       :target-map/root (:target-map/root target-map-artifact)
                       :mapping-profile/root (root "d")
                       :effect-compilation-semantics/root (root "f")
                       :effects/root (effects/effect-root [])}
        artifact (assoc artifact-base :effect-compilation/root
                        (compilation/compilation-root artifact-base))
        canonical (effects/transition {} [])
        binding (sut/build artifact canonical)
        bodies {(:allocation/hash allocation) allocation
                (:target-map/root target-map-artifact) target-map-artifact}]
    (is (not (sut/valid? binding artifact canonical #(get bodies %))))))
