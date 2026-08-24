(ns resolver-sim.pro-rata.proposed-realization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.execution-context :as context]
            [resolver-sim.pro-rata.proposed-realization :as sut]
            [resolver-sim.pro-rata.target-map :as target-map]))

(defn- root [n] (str "sha256:" (apply str (repeat 63 "0")) n))
(def q-liquidity (root "1"))
(def q-filled (root "2"))
(def q-outstanding (root "3"))
(def before {:pool {:available 10} :claim {:filled 0 :outstanding 10} :optional nil})
(def descriptor (context/build-descriptor {:adapter-id :reference/held-credit :adapter-version 1
                                           :projection-profile :reference/project.v1
                                           :reconstruction-profile :reference/reconstruct.v1
                                           :frame-profile :exact-native-leaf-paths.v1}))
(def execution-context (context/build-context {:adapter-source :core
                                               :adapter-descriptor-root (:adapter/descriptor-root descriptor)}))
(def targets (target-map/build-target-map
              {:allocation-subjects-root (root "4") :scope-root (root "5")
               :mapping-profile-root (root "6")
               :targets [{:allocation/subject-id :allocation/liquidity :mapping/role :available :quantity/root q-liquidity}
                         {:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root q-filled}
                         {:allocation/subject-id :claim/alice :mapping/role :outstanding :quantity/root q-outstanding}]}))
(def locations (target-map/build-location-map
                {:scope-root (:scope/root targets)
                 :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                 :locations [{:quantity/root q-liquidity :native/path [:pool :available]}
                             {:quantity/root q-filled :native/path [:claim :filled]}
                             {:quantity/root q-outstanding :native/path [:claim :outstanding]}]}))
(def validation (target-map/validate-target-map
                 {:target-map targets :realized-allocation-root (root "7")
                  :scope-root (:scope/root targets) :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                  :mapping-profile-root (:mapping-profile/root targets)
                  :native-state-before-root (hc/domain-hash :world-state before)
                  :native-location-map locations}))
(def canonical (effects/transition {q-liquidity 10 q-filled 0 q-outstanding 10}
                                   [(effects/delta q-liquidity -10)
                                    (effects/delta q-filled 10)
                                    (effects/delta q-outstanding -10)]))

(deftest execution-context-is-explicit-and-extension-mode-is-structural-only
  (is (= :core (:adapter/source execution-context)))
  (is (thrown? clojure.lang.ExceptionInfo
               (context/build-context {:adapter-source :extension
                                       :adapter-descriptor-root (:adapter/descriptor-root descriptor)})))
  (is (thrown? clojure.lang.ExceptionInfo
               (context/build-context {:adapter-source :core
                                       :adapter-descriptor-root (:adapter/descriptor-root descriptor)
                                       :extension-resolution-root (root "8")}))))

(deftest core-derives-exact-leaf-writes-and-rejects-unrelated-proposed-mutation
  (let [result (sut/build
                {:execution-context execution-context
                 :target-map-validation validation
                 :native-location-map locations
                 :canonical-transition canonical
                 :native-before before
                 :native-state-root #(hc/domain-hash :world-state %)
                 :propose-native-after (fn [state after]
                                         (-> state
                                             (assoc-in [:pool :available] (get after q-liquidity))
                                             (assoc-in [:claim :filled] (get after q-filled))
                                             (assoc-in [:claim :outstanding] (get after q-outstanding))))})]
    (is (= 0 (get-in (:proposed-native-after result) [:pool :available])))
    (is (= 3 (count (sut/derive-authorized-write-set canonical locations))))
    (is (string? (:core-authorized-proposed-realization/root result)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build
                  {:execution-context execution-context :target-map-validation validation
                   :native-location-map locations :canonical-transition canonical
                   :native-before before :native-state-root #(hc/domain-hash :world-state %)
                   :propose-native-after (fn [state _] (assoc state :admin {:key :mutated}))})))
    (is (= [[:optional]] (sut/changed-leaf-paths {} {:optional nil})))))
