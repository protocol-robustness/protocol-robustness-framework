(ns resolver-sim.allocation.persisted-statement-admission
  "Gate-A reconstruction from a persisted canonical realized-statement input.

   The input is not recovered from a proof artifact. It is an independently
   persisted canonical scenario allocation input whose bytes are content-bound
   into the verifier receipt before cryptographic admission."
  (:require [clojure.data.json :as json]
            [buddy.core.codecs :as codecs]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.proof-admission :as admission]
            [resolver-sim.allocation.round-state :as round-state]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.yield.partial-fill :as partial-fill])
  (:import [java.security MessageDigest]
           [java.nio.file Files]))

(def input-file-name "realized-statement-input.json")

(defn sha256-bytes-ref [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest bytes)
    (str "sha256:" (codecs/bytes->hex (.digest digest)))))

(defn input-file [artifact-path]
  (java.io.File. (.getParentFile (java.io.File. artifact-path)) input-file-name))

(defn- parse-amount [field value]
  (try
    (let [n (cond (string? value) (bigint value)
                  (integer? value) (bigint value)
                  :else nil)]
      (when (or (nil? n) (neg? n))
        (throw (ex-info "invalid amount" {:field field :value value})))
      n)
    (catch Exception _
      (throw (ex-info "invalid persisted realization input amount"
                      {:field field :value value})))))

(defn- keyword-values [m]
  (when-not (map? m)
    (throw (ex-info "policy must be an object" {:value m})))
  (into {} (map (fn [[k v]]
                  [(keyword k) (if (string? v) (keyword v) v)])) m))

(defn reconstruct-input
  "Reconstruct the existing Clojure realized statement from the canonical
   realized-statement input JSON shape consumed by Rust/SP1. Unsupported or
   malformed data throws; callers convert that to a fail-closed result."
  [raw]
  (let [input (json/read-str raw :key-fn identity)
        raw-context (get input "allocation-context")
        available (parse-amount :available (get input "available"))
        requested (get input "requested")
        _ (when-not (map? requested)
            (throw (ex-info "requested must be an object" {})))
        requested (into {} (map (fn [[claim amount]]
                                  [claim (parse-amount [:requested claim] amount)])) requested)
        policy (keyword-values (get input "policy"))
        fail-policy (some-> (get input "fail-action-policy") keyword-values)
        decision (partial-fill/calculate-fulfillment-pro-rata
                  available requested (cond-> policy fail-policy
                                        (assoc :fail-action-policy fail-policy)))
        lifecycle (round-state/round-lifecycle {} (get input "round-state"))
        ctx (context/build-context raw-context)
        statement (admission/recompute-statement
                   {:allocation-context ctx :decision decision :round-lifecycle lifecycle})]
    {:allocation-context ctx
     :decision decision
     :round-lifecycle lifecycle
     :statement statement}))

(defn verify-persisted-input
  "Read the sibling canonical input, recompute it with the production Clojure
   statement builder, and require its digest and statement root to match the
   supplied artifact. This is the persisted input side of Gate A."
  [artifact-path artifact]
  (let [file (input-file artifact-path)]
    (try
      (let [bytes (Files/readAllBytes (.toPath file))
            raw (String. bytes "UTF-8")
            rebuilt (reconstruct-input raw)
            input-sha256 (sha256-bytes-ref bytes)
            statement (:statement rebuilt)]
        {:valid? (and (= (:statement/root statement) (:statement/root artifact))
                      (admission/public-values-match? artifact statement))
         :reason (cond
                   (not= (:statement/root statement) (:statement/root artifact)) :statement-root-mismatch
                   (not (admission/public-values-match? artifact statement)) :public-values-statement-mismatch)
         :input-sha256 input-sha256
         :input-file (.getName file)
         :rebuilt rebuilt})
      (catch Exception e
        {:valid? false
         :reason :invalid-persisted-realization-input
         :detail (.getMessage e)}))))
