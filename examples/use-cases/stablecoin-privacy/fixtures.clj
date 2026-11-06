(ns stablecoin-privacy-fixtures
  "Build repeatable, synthetic input bundles for the offline privacy CLI.
   Uses the public RFC 8032 Ed25519 test-vector key. Never use that key for
   real authority or production classification."
  (:require [buddy.core.codecs :as codecs]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.privacy.policy :as policy]
            [resolver-sim.signed-external-decision :as signed])
  (:import [java.security KeyFactory]
           [java.security.spec PKCS8EncodedKeySpec]))

(def ^:private seed-hex
  "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
(def ^:private public-key-hex
  "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
(def ^:private private-key
  (let [prefix (byte-array [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03
                            0x2b 0x65 0x70 0x04 0x22 0x04 0x20])
        seed (codecs/hex->bytes seed-hex)
        der (byte-array (concat prefix seed))]
    (.generatePrivate (KeyFactory/getInstance "Ed25519")
                      (PKCS8EncodedKeySpec. der))))

(defn- sha [ch] (str "sha256:" (apply str (repeat 64 ch))))
(def ^:private classifier-key-id :public-rfc8032-fixture)
(def ^:private classifier-trust
  {:trusted-keys [{:key/id classifier-key-id :key/public public-key-hex
                   :key/role :sensitivity-classifier :key/status :active}]})
(def ^:private report-body
  {:schema-version "sensitivity-report.v2" :run-id "synthetic-stablecoin"
   :profile "internal" :decision "allowed" :run-level "public"
   :findings [] :scenario-count 1 :sensitive-scenario-count 0 :scenarios []})
(def ^:private report-hash
  (hc/hash-with-intent {:hash/intent :evidence-record} report-body))
(def ^:private report
  (assoc report-body :report-hash report-hash :report/semantic-hash report-hash
         :evaluated-at "synthetic-time"))
(def ^:private privacy-policy
  (policy/build-policy
   {:privacy-policy/id "synthetic-aggregate-policy" :privacy-policy/version 1
    :privacy-policy/issuer "did:example:issuer" :privacy-policy/asset-root (sha "a")
    :privacy-policy/jurisdictions ["ZZ"] :privacy-policy/valid-from 100
    :privacy-policy/valid-until 1000
    :privacy-policy/authority-ref "synthetic-unapproved-authority-reference"
    :privacy-policy/rules
    [{:rule/id "allow-public-aggregate" :rule/effect :allow
      :rule/data-categories ["aggregate"] :rule/operations ["publish"]
      :rule/purposes ["research"]
      :rule/recipient-ids ["did:example:public-aggregator"]
      :rule/recipient-classes ["public"] :rule/sinks [:encrypted-bundle]
      :rule/max-sensitivity :sensitivity/public :rule/max-retention-seconds 3600
      :rule/required-evidence []}]}))

(defn- build-input [recipient-id recipient-class]
  (let [request (policy/build-request
                 {:request/id "synthetic-aggregate-export"
                  :request/artifact-root (sha "c")
                  :request/classification-report-root (str "sha256:" report-hash)
                  :request/issuer "did:example:issuer"
                  :request/asset-root (sha "a") :request/jurisdiction "ZZ"
                  :request/at 150 :request/data-category "aggregate"
                  :request/operation "publish" :request/purpose "research"
                  :request/recipient-id recipient-id
                  :request/recipient-class recipient-class
                  :request/sink :encrypted-bundle :request/retention-seconds 300
                  :request/evidence []})
        attestation (signed/sign-envelope
                     {:classification/schema "stablecoin-classification-attestation.v1"
                      :classification/artifact-root (:request/artifact-root request)
                      :classification/report-root (:request/classification-report-root request)
                      :classification/level :sensitivity/public}
                     policy/classification-domain private-key classifier-key-id)
        input {:policies [privacy-policy] :request request
               :classification-report report :classification-attestation attestation
               :classification-trust-policy classifier-trust
               :evidence-attestations [] :evidence-trust-policy {:trusted-keys []}}
        evaluation (policy/evaluate (:policies input) request
                                    (dissoc input :policies :request))]
    (assoc input :evaluation evaluation)))

(defn generate! [directory]
  (let [root (io/file directory)
        allow (build-input "did:example:public-aggregator" "public")
        deny (build-input "did:example:unregistered-recipient" "public")]
    (.mkdirs root)
    (doseq [[filename value] [["policy.edn" {:policy privacy-policy}]
                              ["allow.edn" allow]
                              ["deny.edn" deny]
                              ["allow-evaluate.edn" (dissoc allow :evaluation)]
                              ["deny-evaluate.edn" (dissoc deny :evaluation)]]]
      (with-open [writer (io/writer (io/file root filename))]
        (pprint/pprint value writer)))
    {:directory (.getCanonicalPath root)
     :policy-root (:privacy-policy/root privacy-policy)
     :allow-root (get-in allow [:evaluation :privacy-evaluation/root])
     :deny-root (get-in deny [:evaluation :privacy-evaluation/root])}))

(defn -main [& [directory]]
  (when-not directory
    (throw (ex-info "provide a fixture output directory" {})))
  (prn (generate! directory)))
