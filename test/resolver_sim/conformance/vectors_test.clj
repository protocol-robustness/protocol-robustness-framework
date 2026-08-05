(ns resolver-sim.conformance.vectors-test
  "G9a: committed canonicalisation and cryptographic test vectors must remain
   stable.  This locks the exact bytes/roots an independent implementer hashes
   and signs, so future language implementations cannot agree semantically while
   hashing different bytes."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [resolver-sim.conformance.canonical :as canonical]
            [resolver-sim.conformance.crypto :as crypto]))

(defn- read-vector [rel] (json/read-str (slurp (str "etc/conformance/vectors/" rel)) :key-fn keyword))

(defn- unhex [h]
  (byte-array (map #(Integer/parseInt (str %) 16) (re-seq #"[0-9a-f]{2}" h))))

(deftest canonical-root-vectors-stable
  (let [v (read-vector "canonical-roots.json")]
    (is (= (:claim-preimage v) (canonical/canonical-json-str
                                {:evaluation/mode "attested" :claim/class "attested" :claim/status "pass"
                                 :reconciliation/root "7f8cfabb3775d17180180daaf0767b6dd8bd0dc9145a02e80712ae3c77c86b41"
                                 :environment/root "7774073f7671b4d44d813c1c09915238f59af2038e734da2c374b91dfb621b6c"})))
    (is (= (:claim-root v)
           (canonical/root {:evaluation/mode "attested" :claim/class "attested" :claim/status "pass"
                            :reconciliation/root "7f8cfabb3775d17180180daaf0767b6dd8bd0dc9145a02e80712ae3c77c86b41"
                            :environment/root "7774073f7671b4d44d813c1c09915238f59af2038e734da2c374b91dfb621b6c"})))
    (is (= (:registry-root v) "569918738a7a48439d17c73ffdb505d437ea8e4769438c560d7408694f2d09ac"))))

(deftest ed25519-crypto-vectors-stable
  (let [v (read-vector "crypto.json")
        pk (unhex (:public-key v))
        sig (unhex (:valid-signature-hex v))
        preimage (unhex (:preimage-hex v))
        valid (crypto/verify-signature
               {:signature/algorithm :ed25519 :signature/value sig
                :signature/preimage preimage
                :signature/domain :prf-evidence-package.v1
                :signer/id :signer-a :signer/public-key pk
                :trust-policy/root "sha256:policy"
                :trust-policy/keys {:signer-a {:key/id :key-1 :key/status :active
                                               :key/authorised-kinds #{:evidence-package}}}
                :valid-at 1000 :artifact-kind :evidence-package
                :verification/implementation-root "sha256:impl"})]
    (is (= :pass (:verification/status valid)))
    (let [wrong-preimage (crypto/verify-signature
                          (assoc (select-keys
                                  {:signature/algorithm :ed25519 :signature/value sig
                                   :signature/preimage preimage
                                   :signature/domain :prf-evidence-package.v1
                                   :signer/id :signer-a :signer/public-key pk
                                   :trust-policy/root "sha256:policy"
                                   :trust-policy/keys {:signer-a {:key/id :key-1 :key/status :active
                                                                  :key/authorised-kinds #{:evidence-package}}}
                                   :valid-at 1000 :artifact-kind :evidence-package
                                   :verification/implementation-root "sha256:impl"}
                                  [:signature/algorithm :signature/value :signature/preimage
                                   :signature/domain :signer/id :signer/public-key
                                   :trust-policy/root :trust-policy/keys :valid-at
                                   :artifact-kind :verification/implementation-root])
                                 :signature/preimage (byte-array (map byte "tampered-preimage"))))]
      (is (= :fail (:verification/status wrong-preimage))))
    (is (= #{"valid" "wrong-preimage" "wrong-domain" "unauthorised-kind" "revoked-key" "unknown-algorithm"}
           (set (map name (keys (:decisions v))))))))
