(ns resolver-sim.allocation.cleanroom-holdout
  "Private reference-side generator for realized partial-fill clean-room holdouts.
   It reconstructs only through persisted-statement-admission's Clojure
   production path; it does not invoke the Rust kernel."
  (:require [resolver-sim.allocation.persisted-statement-admission :as persisted]
            [resolver-sim.allocation.realized-statement :as statement]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.security MessageDigest]))

(def schema-version "realized-partial-fill-holdout-expected-output.v1")
(def expected-output-domain "REALIZED_PARTIAL_FILL_HOLDOUT_EXPECTED_OUTPUTS_V1")

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- realized-rows [{:keys [requested filled deferred haircut unrealized]}]
  (mapv (fn [claim]
          (let [row {:claim/id claim
                     :requested (get requested claim 0)
                     :filled (get filled claim 0)
                     :deferred (get deferred claim 0)
                     :haircut (get haircut claim 0)
                     :unrealized (get unrealized claim 0)}]
            (assoc row :disposition (statement/disposition-of row))))
        (sort (keys requested))))

(defn- fail-action-policy [policy]
  (or (:fail-action-policy policy)
      {:mode :pro-rata-treatment
       :deferred-policy :same-ratio
       :haircut-policy :same-ratio}))

(defn- body-bytes [bodies]
  (into {} (map (fn [[id body]] [id (hex (hc/canonical-bytes body))]) bodies)))

(defn expected-output
  "Reconstruct one holdout from its fixed JSON bytes using the normative Clojure
   persisted-input production path, then expose all root bodies and canonical
   bytes needed for an eventual external comparison."
  [raw]
  (let [{:keys [allocation-context decision round-lifecycle statement]}
        (persisted/reconstruct-input raw)
        bodies {:allocation-context allocation-context
                :request-set (into (sorted-map) (:requested decision))
                :allocation-policy (dissoc (:policy decision) :fail-action-policy)
                :fail-action-policy (fail-action-policy (:policy decision))
                :realized-results (realized-rows decision)
                :round-lifecycle round-lifecycle
                :statement-preimage (statement/statement-preimage statement)}
        roots (select-keys statement [:allocation-context-root :request-set-root
                                      :allocation-policy-root :realized-results-root
                                      :fail-action-policy-root :round-lifecycle-root
                                      :statement/root])
        base {:schema-version schema-version
              :input/sha256 (persisted/sha256-bytes-ref (.getBytes raw "UTF-8"))
              :provenance {:reference/namespace "resolver-sim.allocation.persisted-statement-admission"
                           :reference/function "reconstruct-input"
                           :projection/rules "realized-partial-fill-projections-v1"
                           :profile "largest-remainder-deferred-pro-rata.v1"}
              :bodies bodies
              :canonical-bytes (body-bytes bodies)
              :roots roots}]
    (assoc base :expected-output/root (hc/domain-hash expected-output-domain base))))

(defn expected-output-root [outputs]
  (hc/domain-hash expected-output-domain outputs))

(defn- sha256-hex [bytes]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bytes)))

(defn expected-output-set-commitment
  "Commit generated private output files by sorted file name and SHA-256(pr-str
   output) lines. This mirrors the sealed staging commitment convention."
  [named-outputs]
  (let [lines (->> named-outputs
                   (sort-by key)
                   (map (fn [[name output]]
                          (str name " " (sha256-hex (.getBytes (pr-str output) "UTF-8")) "\n")))
                   (apply str))]
    (sha256-hex (.getBytes lines "UTF-8"))))
