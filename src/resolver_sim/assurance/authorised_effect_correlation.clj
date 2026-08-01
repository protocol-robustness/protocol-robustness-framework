(ns resolver-sim.assurance.authorised-effect-correlation
  "Protocol-neutral binding from a researcher authorisation lifecycle to a
   concrete public protocol effect and its authenticated effect evidence."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version 1)
(def ^:const artifact-type :authorised-effect-correlation)
(def ^:const supported-effect-types #{:held-adjustment})
(def ^:private allowed-keys
  #{:artifact/type :artifact/version :protocol/id
    :research-assignment/hash :researcher-force-authorisation/hash
    :reservation/hash :reservation/execution-attempt-id
    :public-authorisation/id :public-authorisation/scope-hash
    :effect/type :effect/id :effect/artifact-hash :correlation/hash})

(def ^:private required-hash-keys
  [:research-assignment/hash
   :researcher-force-authorisation/hash
   :reservation/hash
   :public-authorisation/scope-hash
   :effect/artifact-hash])

(defn- valid-ref? [value]
  (hash-ref/valid-sha256-ref? value))

(defn- canonical-id?
  "Stable scalar identity used at protocol boundaries. Collections, objects,
   UUID instances, and runtime values are deliberately excluded."
  [value]
  (or (and (string? value) (not (empty? value)))
      (keyword? value)
      (integer? value)))

(defn- namespaced-keyword?
  [value]
  (and (keyword? value) (some? (namespace value))))

(defn- preimage [correlation]
  (dissoc correlation :correlation/hash))

(defn correlation-hash
  "Return the domain-separated canonical hash reference for a correlation."
  [correlation]
  (str "sha256:" (hc/domain-hash :authorised-effect-correlation
                                  (preimage correlation))))

(defn correlation-error
  "Return a stable structural error keyword, or nil for a valid correlation."
  [correlation]
  (cond
    (not (every? allowed-keys (keys correlation))) :unknown-artifact-key
    (not= artifact-type (:artifact/type correlation)) :invalid-artifact-type
    (not= schema-version (:artifact/version correlation)) :unsupported-artifact-version
    (not (keyword? (:protocol/id correlation))) :invalid-protocol-id
    (some #(not (valid-ref? (get correlation %))) required-hash-keys)
    :invalid-hash-reference
    (not (namespaced-keyword? (:reservation/execution-attempt-id correlation)))
    :invalid-execution-attempt-id
    (not (canonical-id? (:public-authorisation/id correlation)))
    :invalid-public-authorisation-id
    (not (contains? supported-effect-types (:effect/type correlation)))
    :unsupported-effect-type
    (not (canonical-id? (:effect/id correlation))) :invalid-effect-id
    (and (contains? correlation :correlation/hash)
         (not= (:correlation/hash correlation) (correlation-hash correlation)))
    :correlation-hash-mismatch
    :else nil))

(defn build-correlation
  "Build an immutable authorised-effect-correlation.v1. The Sew adapter
   supplies held-adjustment values, while this generic artifact only commits
   public-authorisation and effect identities."
  [fields]
  (let [correlation (assoc fields :artifact/type artifact-type
                                  :artifact/version schema-version)
        error (correlation-error correlation)]
    (when (and error (not= error :correlation-hash-mismatch))
      (throw (ex-info "Authorised effect correlation build failed" {:error error})))
    (let [computed (correlation-hash correlation)]
      (when (and (:correlation/hash fields)
                 (not= (:correlation/hash fields) computed))
        (throw (ex-info "Authorised effect correlation hash mismatch"
                        {:declared (:correlation/hash fields) :computed computed})))
      (assoc correlation :correlation/hash computed))))

(defn valid-correlation?
  [correlation]
  (nil? (correlation-error correlation)))

(defn validate-correlation
  [correlation]
  (let [error (correlation-error correlation)]
    {:valid? (nil? error) :error error}))
