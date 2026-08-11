(ns resolver-sim.run.release-attestation-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [buddy.core.codecs :as codecs]
            [resolver-sim.run.release-attestation :as release]))

(defn- raw-public-hex [public-key]
  (let [encoded (.getEncoded public-key)
        raw (java.util.Arrays/copyOfRange encoded (- (alength encoded) 32) (alength encoded))]
    (codecs/bytes->hex raw)))

(defn- fixture []
  (let [pair (.generateKeyPair (java.security.KeyPairGenerator/getInstance "Ed25519"))
        payload (release/build-payload {:distribution :prf :implementation {:jar "sha256:jar"}
                                        :release {:build-bundle "sha256:bundle"}})
        policy {:schema-version "prf-release-trust-policy.v1"
                :policy-id :release/test
                :policy/status :active
                :policy-version 1
                :trusted-keys [{:key-id "release-1" :status :active
                                :public-key (raw-public-hex (.getPublic pair))}]
                :requirements {:distribution {:prf {:minimum-valid-signatures 1}}}
                :canonicalization {:payload-profile "prf-release-attestation-payload.v1"}}]
    {:pair pair :payload payload :policy policy}))

(deftest release-signature-authorizes-a-valid-payload
  (let [{:keys [pair payload policy]} (fixture)
        signature (release/sign-payload payload (.getPrivate pair) "release-1")
        result (release/verify-authorization payload [signature] :prf policy)]
    (is (= :authorized (get-in result [:authorization :status])))
    (is (= :release-authorized (:verdict result)))))

(deftest checked-in-template-is-valid-but-never-authorizing
  (let [policy (edn/read-string (slurp (io/resource "prf/release/trust-policy.edn")))]
    (is (:valid? (release/verify-policy policy)))
    (is (= :unconfigured (:policy/status policy)))
    (is (empty? (:trusted-keys policy)))
    (is (pos? (get-in policy [:requirements :distribution :prf :minimum-valid-signatures])))
    (is (pos? (get-in policy [:requirements :distribution :sew :minimum-valid-signatures])))
    (doseq [distribution [:prf :sew]]
      (let [result (release/verify-authorization
                    (release/build-payload {:distribution distribution :implementation {} :release {}})
                    [] distribution policy)]
        (is (= :missing-or-insufficient (get-in result [:authorization :status])))
        (is (= :release-policy-not-active (get-in result [:authorization :reason-code])))))))

(deftest policy-status-dominates-valid-key-and-signature-enrollment
  (let [{:keys [pair payload policy]} (fixture)
        signature (release/sign-payload payload (.getPrivate pair) "release-1")]
    (doseq [status [:unconfigured :retired]]
      (let [result (release/verify-authorization payload [signature] :prf
                                                 (assoc policy :policy/status status))]
        (is (= :missing-or-insufficient (get-in result [:authorization :status])))
        (is (= :release-policy-not-active (get-in result [:authorization :reason-code])))))
    (is (= :authorized
           (get-in (release/verify-authorization payload [signature] :prf policy)
                   [:authorization :status])))))

(deftest release-signature-fails-closed-for-tampering-and-threshold-shortfall
  (let [{:keys [pair payload policy]} (fixture)
        signature (release/sign-payload payload (.getPrivate pair) "release-1")
        tampered (assoc payload :release {:build-bundle "sha256:other"})]
    (is (false? (:valid? (release/verify-signature tampered signature policy))))
    (is (= :missing-or-insufficient
           (get-in (release/verify-authorization payload [] :prf policy)
                   [:authorization :status])))))
