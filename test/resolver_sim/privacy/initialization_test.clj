(ns resolver-sim.privacy.initialization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.privacy.initialization :as init]
            [resolver-sim.privacy.policy :as policy]))

(def asset-root (str "sha256:" (apply str (repeat 64 "a"))))
(def rule {:rule/id "r" :rule/effect :allow
           :rule/data-categories ["aggregate"] :rule/operations ["publish"]
           :rule/purposes ["reporting"]
           :rule/recipient-ids ["did:fixture:recipient"]
           :rule/recipient-classes ["public"]
           :rule/sinks [:encrypted-bundle] :rule/max-sensitivity :sensitivity/public
           :rule/max-retention-seconds 3600 :rule/required-evidence []})
(def policy-body
  (policy/build-policy
   {:privacy-policy/id "p" :privacy-policy/version 1 :privacy-policy/issuer "issuer-1"
    :privacy-policy/asset-root asset-root :privacy-policy/jurisdictions ["ZZ"]
    :privacy-policy/valid-from 1 :privacy-policy/valid-until 100
    :privacy-policy/rules [rule] :privacy-policy/authority-ref "external-policy-owner"}))
(def bootstrap
  (init/build-initialization {:issuer "issuer-1" :asset-root asset-root :currency "USD"
                              :jurisdiction "ZZ" :policy-root (:privacy-policy/root policy-body)
                              :authority-root (str "sha256:" (apply str (repeat 64 "b")))}))

(deftest initialization-trust-boundary
  (is (= :authority-unverified (:status (init/verify-initialization bootstrap policy-body #{}))))
  (let [verified (init/verify-initialization bootstrap policy-body
                                             #{(:privacy-initialization/bootstrap-root bootstrap)})]
    (is (:valid? verified))
    (is (= :historical-verified (:status verified)))
    (is (= :unknown (:currentness verified))))
  (is (:resolved? (init/resolve-initial-policy
                   bootstrap policy-body #{(:privacy-initialization/bootstrap-root bootstrap)})))
  (is (false? (:resolved? (init/resolve-initial-policy bootstrap policy-body #{}))))
  (is (= :invalid (:status (init/verify-initialization
                            (assoc bootstrap :privacy-initialization/currency "EUR") policy-body
                            #{(:privacy-initialization/bootstrap-root bootstrap)})))))
