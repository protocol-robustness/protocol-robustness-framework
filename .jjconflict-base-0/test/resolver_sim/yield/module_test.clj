(ns resolver-sim.yield.module-test
  (:require [clojure.test :refer :all]
            [resolver-sim.yield.module :as ymod]
            [resolver-sim.yield.registry :as reg]
            [resolver-sim.yield.modules.liquid-lending :as liquid]
            [resolver-sim.yield.modules.fixed :as fixed]
            [resolver-sim.yield.modules.none :as none]
            [resolver-sim.yield.modules.adversarial :as adversarial]))

(deftest test-validate-module-accepts-liquid-lending
  (is (= :yield.provider/liquid-lending
         (:module/id (ymod/validate-module liquid/liquid-lending-module)))))

(deftest test-validate-module-rejects-missing-op
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"without op fn"
                        (ymod/validate-module
                         (assoc liquid/liquid-lending-module
                                :ops (dissoc (:ops liquid/liquid-lending-module) :yield/withdraw))))))

(deftest test-archetype-constructors-validate
  (doseq [m [(liquid/make-liquid-lending-module :yield.provider/liquid-lending)
             (liquid/make-liquid-lending-module :aave-v3 :yield.profile/aave-v3-like)
             (fixed/make-fixed-module :fixed-rate)
             (none/make-none-module :none)
             (adversarial/make-adversarial-module :adversarial)]]
    (is (= m (ymod/validate-module m)))))

(deftest test-default-modules-built-by-constructors
  (doseq [[mid m] reg/default-modules]
    (is (= mid (:module/id m))
        (str "default module key " mid))
    (is (= m (ymod/validate-module m)))))

(deftest test-init-yield-modules-registers-valid-modules
  (let [world (reg/init-yield-modules {})]
    (is (fn? (get-in world [:yield/modules :aave-v3 :ops :yield/deposit])))
    (is (ymod/module-capable?
         (get-in world [:yield/modules :fixed-rate])
         :accrue))
    (is (= (count reg/default-modules)
           (count (:yield/modules world))))))

(deftest test-resolve-module-id
  (let [world {:yield/module-aliases {:aave-v3 :yield.provider/liquid-lending}}]
    (is (= :yield.provider/liquid-lending
           (ymod/resolve-module-id world :aave-v3)))
    (is (= :yield.provider/liquid-lending
           (ymod/resolve-module-id world "aave-v3")))))
