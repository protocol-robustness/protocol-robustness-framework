(ns resolver-sim.support.ed25519
  "Shared Ed25519 fixtures for sentinel signing tests."
  (:require [buddy.core.codecs :as codecs]))

(defn keypair
  "Generate an Ed25519 keypair. Returns
   {:key/id kw :private-key java.security.PrivateKey :public-hex <raw 32-byte hex>}."
  ([] (keypair :sentinel-test-key))
  ([id]
   (let [kg (java.security.KeyPairGenerator/getInstance "Ed25519")
         kp (.generateKeyPair kg)
         encoded (vec (.getEncoded (.getPublic kp)))
         raw-hex (codecs/bytes->hex (byte-array (take-last 32 encoded)))]
     {:key/id id
      :private-key (.getPrivate kp)
      :public-hex raw-hex})))

(defn trust-policy
  "Build a sentinel trust policy map from keypair fixtures.
   role defaults to :sensitivity-sentinel; status to :active."
  ([kp] (trust-policy kp :sensitivity-sentinel :active))
  ([kp role status]
   {:trusted-keys [{:key/id (:key/id kp)
                    :key/public (:public-hex kp)
                    :key/role role
                    :key/status status}]}))
