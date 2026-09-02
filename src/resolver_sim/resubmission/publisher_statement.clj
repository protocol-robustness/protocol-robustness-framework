(ns resolver-sim.resubmission.publisher-statement
  "Canonical publisher statement and authenticated envelope for an attempt.
   The statement signs the final submitted-bundle root; the envelope carries the
   existing repository signature convention separately."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.signed-external-decision :as sed]))

(def statement-schema "attempt-publisher-statement.v1")
(def envelope-schema "attempt-publisher-envelope.v1")
(def statement-domain :prf-attempt-publisher-statement-v1)
(def envelope-domain :prf-attempt-publisher-envelope-v1)

(defn statement-projection [statement]
  (select-keys statement [:artifact/schema :publisher/principal-id
                          :publisher/key-id :publisher/submitted-bundle-root]))

(defn statement-root [statement]
  (ref/sha256-ref (hc/domain-hash statement-domain (statement-projection statement))))

(defn valid-statement? [statement]
  (and (map? statement)
       (= #{:artifact/schema :publisher/principal-id :publisher/key-id
            :publisher/submitted-bundle-root :publisher/statement-root}
          (set (keys statement)))
       (= statement-schema (:artifact/schema statement))
       (string? (:publisher/principal-id statement))
       (string? (:publisher/key-id statement))
       (ref/valid-sha256-ref? (:publisher/submitted-bundle-root statement))
       (= (:publisher/statement-root statement) (statement-root statement))))

(defn build-statement [principal-id key-id submitted-bundle-root]
  (let [statement {:artifact/schema statement-schema
                   :publisher/principal-id principal-id
                   :publisher/key-id key-id
                   :publisher/submitted-bundle-root submitted-bundle-root}]
    (assoc statement :publisher/statement-root (statement-root statement))))

(defn envelope-projection [envelope]
  (select-keys envelope [:artifact/schema :publisher-envelope/statement-root
                         :publisher-envelope/key-id :publisher-envelope/signature]))

(defn envelope-root [envelope]
  (ref/sha256-ref (hc/domain-hash envelope-domain (envelope-projection envelope))))

(defn valid-envelope? [envelope]
  (and (map? envelope)
       (= #{:artifact/schema :publisher-envelope/statement-root
            :publisher-envelope/key-id :publisher-envelope/signature
            :publisher-envelope/root}
          (set (keys envelope)))
       (= envelope-schema (:artifact/schema envelope))
       (ref/valid-sha256-ref? (:publisher-envelope/statement-root envelope))
       (string? (:publisher-envelope/key-id envelope))
       (map? (:publisher-envelope/signature envelope))
       (= (:publisher-envelope/root envelope) (envelope-root envelope))))

(defn sign-statement [statement private-key]
  (assoc statement :publisher/signature
         {:signature/algorithm :ed25519
          :signature (sed/ed25519-sign-bytes
                      (hc/canonical-bytes (statement-projection statement))
                      private-key)}))

(defn verify-statement-signature [statement public-key-hex]
  (let [signature (:publisher/signature statement)]
    (and (valid-statement? (dissoc statement :publisher/signature))
         (= :ed25519 (:signature/algorithm signature))
         (sed/ed25519-verify-bytes
          (hc/canonical-bytes (statement-projection statement))
          (:signature signature)
          public-key-hex))))

(defn build-envelope [statement-root key-id signature]
  (let [envelope {:artifact/schema envelope-schema
                  :publisher-envelope/statement-root statement-root
                  :publisher-envelope/key-id key-id
                  :publisher-envelope/signature signature}]
    (assoc envelope :publisher-envelope/root (envelope-root envelope))))

(defn statement-binds-bundle?
  [statement submitted-bundle-root]
  (and (valid-statement? (dissoc statement :publisher/signature))
       (= submitted-bundle-root (:publisher/submitted-bundle-root statement))))

(defn envelope-binds-statement?
  [envelope statement]
  (and (valid-envelope? envelope)
       (valid-statement? (dissoc statement :publisher/signature))
       (= (:publisher/statement-root statement)
          (:publisher-envelope/statement-root envelope))
       (= (:publisher/key-id statement)
          (:publisher-envelope/key-id envelope))))
