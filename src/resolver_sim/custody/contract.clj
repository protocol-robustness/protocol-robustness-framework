(ns resolver-sim.custody.contract
  "AUTH-K4 custody contract.

   K4 is the custody/signing-execution boundary. The ordinary PRF process must
   never load or receive the governed private key on the authoritative path;
   the key lives in a separate local custody process that signs only a closed,
   frozen governed-authority-signing-request.v1.

   This namespace owns the wire framing, the structured refusal taxonomy, and
   the assurance-only custody receipt. It does NOT change K1/K2/K3 semantics or
   byte vectors."
  (:require [resolver-sim.benchmark.governed-authority-signing-request :as k3]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const custody-request-kind k3/signing-request-schema)
(def ^:const custody-response-kind "custody-signing-response.v1")
(def ^:const custody-receipt-kind "custody-signing-receipt.v1")

(def refusal-reasons
  "Closed structured failure taxonomy. Every authoritative K4 path fails closed
   with one of these; there is no fallback to raw-file signing."
  #{:daemon/unavailable
    :authority/state-unavailable
    :authority/state-stale
    :authority/material-mismatch
    :key/not-held
    :key/not-provisioned
    :key/disabled
    :key/public-mismatch
    :key/not-governance-eligible
    :principal/not-constituted
    :purpose/mismatch
    :request/malformed
    :request/root-mismatch
    :provider/timeout
    :signing/failed})

(defn valid-refusal? [r]
  (contains? refusal-reasons r))

(defn custody-request?
  "True when `m` is a well-shaped custody signing request (the closed frozen
   governed-authority-signing-request.v1 plus the custody framing)."
  [m]
  (and (map? m)
       (= custody-request-kind (:artifact/kind m))
       (ref/valid-sha256-ref? (:authority-state/root m))
       (ref/valid-sha256-ref? (:review-governance/root m))
       (ref/valid-sha256-ref? (:signer-key-set/root m))
       (ref/valid-sha256-ref? (:review-round/root m))
       (string? (:principal/id m))
       (string? (:signing-key/id m))
       (keyword? (:purpose m))
       (= :researcher-decision (:subject/kind m))
       (ref/valid-sha256-ref? (:subject/root m))))

(defn signing-response
  "Build a custody signing response."
  ([result req-root signature-hex key-id]
   {:artifact/kind custody-response-kind
    :custody/result result
    :signing-request/root req-root
    :signature-hex signature-hex
    :key-id key-id})
  ([result reason]
   {:artifact/kind custody-response-kind
    :custody/result result
    :reason reason}))

(defn refused-response [reason]
  {:artifact/kind custody-response-kind
   :custody/result :refused
   :reason reason})

;; ── Assurance-only custody receipt ──────────────────────────────────────────

(def receipt-fields
  "Assurance-only custody receipt. Protocol-semantic fields are limited to
   :signing-request/root, :signing-key/id, :subject/root (already committed in
   the K3 request/signature). Provider identity enters only as a kind + a hash
   of the operational handle — never as protocol authority."
  #{:artifact/kind
    :signing-request/root
    :signing-key/id
    :subject/root
    :custody/result
    :custody/provider-kind
    :custody/key-handle-hash
    :signature/hash
    :observed-at})

(defn signing-receipt
  "Build an assurance-only custody receipt. Does not conflate the frozen
   signing digest with the signature hash — callers must set both explicitly."
  [m]
  (let [body {:artifact/kind custody-receipt-kind
              :signing-request/root (:signing-request/root m)
              :signing-key/id (:signing-key/id m)
              :subject/root (:subject/root m)
              :custody/result (:custody/result m)
              :custody/provider-kind (:custody/provider-kind m)
              :custody/key-handle-hash (:custody/key-handle-hash m)
              :signature/hash (:signature/hash m)
              :observed-at (:observed-at m)}
        candidate (assoc body :receipt/root
                         (ref/sha256-ref
                          (hc/domain-hash :custody-signing-receipt-v1
                                          (hc/project-canonical-safe body))))]
    candidate))

(defn valid-receipt? [r]
  (and (map? r)
       (= custody-receipt-kind (:artifact/kind r))
       (ref/valid-sha256-ref? (:receipt/root r))))