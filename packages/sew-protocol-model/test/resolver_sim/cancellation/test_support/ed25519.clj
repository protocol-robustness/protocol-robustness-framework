(ns resolver-sim.cancellation.test-support.ed25519
  "Shared Ed25519 key fixtures for Sew cancellation contract tests."
  (:require [buddy.core.codecs :as codecs]))

(defn keypair
  ([] (keypair :cancellation-test-key))
  ([id]
   (let [generator (java.security.KeyPairGenerator/getInstance "Ed25519")
         pair (.generateKeyPair generator)
         encoded (.getEncoded (.getPublic pair))]
     {:key/id id
      :private-key (.getPrivate pair)
      :public-hex (codecs/bytes->hex
                   (byte-array (take-last 32 encoded)))})))

(defn trust-policy
  ([pair] (trust-policy pair :protocol-party :active))
  ([pair role status]
   {:trusted-keys [{:key/id (:key/id pair)
                    :key/public (:public-hex pair)
                    :key/role role
                    :key/status status}]}))
