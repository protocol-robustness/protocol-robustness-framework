(ns resolver-sim.pro-rata.quantity-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.quantity :as quantity]
            [resolver-sim.pro-rata.canonical-effects :as effects]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))

(deftest quantity-identity-is-globally-scoped
  (let [base {:protocol-instance-root (root "1") :state-domain-root (root "2")
              :subject-root (root "3") :quantity-kind :outstanding
              :asset-root (root "4") :scope-root (root "5")}
        a (quantity/build-identity base)
        other-instance (quantity/build-identity (assoc base :protocol-instance-root (root "6")))
        other-asset (quantity/build-identity (assoc base :asset-root (root "7")))]
    (is (quantity/valid-identity? a))
    (is (not= (:quantity/root a) (:quantity/root other-instance)))
    (is (not= (:quantity/root a) (:quantity/root other-asset)))))

(deftest compilation-commits-exact-effects-derived-from-allocation
  (let [allocation {:allocation/hash (root "1")
                    :rows [{:row/id :a :allocated 40}]}
        targets {:liquidity/root (root "2")
                 :a {:filled/root (root "3") :outstanding/root (root "4")}}
        compilation (effects/build-pro-rata-effect-compilation allocation targets (root "5"))]
    (is (effects/compilation-valid? allocation targets compilation))
    (is (not (effects/compilation-valid?
              (assoc-in allocation [:rows 0 :allocated] 39) targets compilation)))))
