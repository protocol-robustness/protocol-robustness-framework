(ns resolver-sim.pro-rata.target-map-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.pro-rata.target-map :as sut]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))

(defn- target-map []
  (sut/build-target-map
   {:allocation-subjects-root (root "1")
    :scope-root (root "2")
    :mapping-profile-root (root "3")
    :targets [{:allocation/subject-id :allocation/liquidity :mapping/role :available :quantity/root (root "4")}
              {:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root (root "5")}
              {:allocation/subject-id :claim/alice :mapping/role :outstanding :quantity/root (root "6")}]}))

(defn- location-map [targets]
  (sut/build-location-map
   {:scope-root (:scope/root targets)
    :adapter-descriptor-root (root "7")
    :locations [{:quantity/root (root "4") :native/path [:pool :available]}
                {:quantity/root (root "5") :native/path [:claims :alice :filled]}
                {:quantity/root (root "6") :native/path [:claims :alice :outstanding]}]}))

(deftest one-to-one-target-map-commits-roles-and-quantities
  (let [map (target-map)]
    (is (= "allocation-quantity-target-map.v1" (:schema-version map)))
    (is (= (:target-map/root map) (sut/target-map-root map)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-target-map
                  {:allocation-subjects-root (root "1") :scope-root (root "2")
                   :mapping-profile-root (root "3")
                   :targets [{:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root (root "4")}
                             {:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root (root "5")}]})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-target-map
                  {:allocation-subjects-root (root "1") :scope-root (root "2")
                   :mapping-profile-root (root "3")
                   :targets [{:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root (root "4")}
                             {:allocation/subject-id :claim/bob :mapping/role :filled :quantity/root (root "4")}]})))))

(deftest validation-binds-all-required-dependencies-and-location-coverage
  (let [map (target-map)
        locations (location-map map)
        validation (sut/validate-target-map
                    {:target-map map
                     :realized-allocation-root (root "8")
                     :scope-root (:scope/root map)
                     :adapter-descriptor-root (root "7")
                     :mapping-profile-root (:mapping-profile/root map)
                     :native-state-before-root (root "9")
                     :native-location-map locations})]
    (is (= (:target-map-validation/root validation) (sut/validation-root validation)))
    (is (= (set [:target-map/root :realized-allocation/root :scope/root :adapter/descriptor-root
                 :mapping-profile/root :native-state-before/root :native-location-map/root])
           (disj (set (keys validation)) :schema-version :target-map-validation/root)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/validate-target-map
                  {:target-map map :realized-allocation-root (root "8")
                   :scope-root (:scope/root map) :adapter-descriptor-root (root "a")
                   :mapping-profile-root (:mapping-profile/root map)
                   :native-state-before-root (root "9") :native-location-map locations})))))
