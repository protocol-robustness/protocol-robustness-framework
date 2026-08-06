(ns resolver-sim.sensitivity.contract
  "Out-of-process sensitivity sentinel contract.

   Defines the wire request, the signed decision envelope, their domain
   separation tags, and the deterministic commitments the client and the
   authority both rely on. This is the single source of truth for shapes so
   that the two processes cannot drift apart.

   The authority makes its decision from `:artifact/content` it inspects and
   hash-verifies itself; caller-supplied values are commitments the authority
   cross-checks, never trusted inputs."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.signed-external-decision :as sed]))

;; ── Domain separation tags ──────────────────────────────────────────────────

(def request-kind :sensitivity-sentinel-check)
(def decision-kind :sensitivity-sentinel-decision)
(def response-kind :sensitivity-sentinel-decision-response)

(def ^:const request-domain "PRF_SENSITIVITY_SENTINEL_REQUEST_V1")
(def ^:const projection-domain "PRF_SENSITIVITY_SENTINEL_PROJECTION_V1")
(def ^:const decision-domain "PRF_SENSITIVITY_SENTINEL_DECISION_V1")

(def policy-id "sensitivity-sentinel-policy.v1")

(def ^:const protocol-version 1)

;; ── Projection commitment ───────────────────────────────────────────────────

(defn projection-hash
  "Canonical, domain-separated hash of an artifact disclosure projection.
   Both the client and the authority compute this identically so the
   authority can verify the caller's :artifact/declared-hash."
  [content]
  (str "sha256:" (hc/domain-hash projection-domain content)))

;; ── Request ─────────────────────────────────────────────────────────────────

(defn build-request
  "Build a sentinel-check request.

   Args:
     artifact-id — caller-chosen request id (string or uuid)
     content     — the disclosure projection the authority will hash and scan
     sink        — requested sink keyword
     declared-level — optional caller-declared :sensitivity/level floor
     risk-meta   — optional caller risk metadata map
     policy-hash-str — the committed policy hash the authority must honor

   Returns the request with :artifact/declared-hash and :request/hash
   attached. Caller-supplied findings are intentionally absent; the authority
   computes its own."
  [{:keys [artifact-id content sink declared-level risk-meta policy-hash-str]}]
  (let [base {:request/kind request-kind
              :request/version protocol-version
              :request/id artifact-id
              :artifact/content content
              :artifact/declared-hash (projection-hash content)
              :sink sink
              :declared-level declared-level
              :risk-meta risk-meta
              :policy/id policy-id
              :policy/hash policy-hash-str}]
    (sed/attach-request-hash request-domain base)))

(def request-allowed-top-level
  #{:request/kind :request/version :request/hash :request/id
    :artifact/content :artifact/declared-hash
    :sink :declared-level :risk-meta :policy/id :policy/hash})

(defn request-errors
  "Return a vector of human-readable errors for a request, or [] if valid."
  [req]
  (into []
        (remove nil?)
        [(when-not (map? req) "request must be a map")
         (when (and (map? req) (not= request-kind (:request/kind req)))
           (str "unexpected request/kind: " (:request/kind req)))
         (when (and (map? req) (not= protocol-version (:request/version req)))
           (str "unsupported request/version: " (:request/version req)))
         (when (and (map? req) (nil? (:request/id req)))
           "request/id required")
         (when (and (map? req) (nil? (:artifact/content req)))
           "artifact/content required")
         (when (and (map? req) (nil? (:artifact/declared-hash req)))
           "artifact/declared-hash required")
         (when (and (map? req) (not (keyword? (:sink req))))
           "sink must be a keyword")
         (when (and (map? req) (not= policy-id (:policy/id req)))
           (str "unexpected policy/id: " (:policy/id req)))
         (when (and (map? req) (not (string? (:policy/hash req))))
           "policy/hash required")
         (when (and (map? req)
                    (seq (remove request-allowed-top-level (keys req))))
           (str "unexpected top-level keys: "
                (pr-str (vec (sort-by pr-str (remove request-allowed-top-level (keys req)))))))]))

(defn valid-request?
  "True when a request is structurally valid and its self-committed hash matches."
  [req]
  (let [errors (request-errors req)]
    (and (empty? errors)
         (= (:request/hash req)
            (sed/request-hash request-domain req)))))

;; ── Decision envelope ───────────────────────────────────────────────────────

(defn report-commitment
  "Deterministic commitment to the decision-relevant fields of a sentinel
   report. Excludes non-deterministic fields (:sentinel/evaluated-at, salted
   evidence-findings) so both the authority and the bundle verifier can
   recompute the same value from the embedded report body."
  [report]
  (hc/hash-with-intent
   {:hash/intent :evidence-record}
   {:version (:sentinel/version report)
    :level (:sentinel/level report)
    :structural-level (:sentinel/structural-level report)
    :declared-level (:sentinel/declared-level report)
    :decision (:sentinel/decision report)
    :reasons (vec (sort (:sentinel/reasons report)))
    :allowed-sinks (vec (sort (:sentinel/allowed-sinks report)))
    :redaction-required? (:sentinel/redaction-required? report)
    :override-required? (get-in report [:sentinel/override-required? :required?])
    :override-mode (get-in report [:sentinel/override-required? :mode])}))

(defn build-decision
  "Assemble the decision envelope for a sentinel report produced against a
   hash-verified request. The envelope binds the complete authorization
   context: artifact, sink, policy, report, levels, reasons and override state.

   Returns the unsigned envelope (add :signature by signing via the
   signed-external-decision primitive)."
  [{:keys [request report sink artifact-hash authority-key-id authority-assurance issued-at]}]
  {:artifact/kind decision-kind
   :artifact/version protocol-version
   :sentinel/request-hash (:request/hash request)
   :sentinel/artifact-hash artifact-hash
   :sentinel/sink sink
   :sentinel/policy-id policy-id
   :sentinel/policy-hash (:policy/hash request)
   :sentinel/report-hash (report-commitment report)
   :sentinel/decision (case (:sentinel/decision report)
                        :allowed :allow
                        :blocked :block
                        (:sentinel/decision report))
   :sentinel/level (:sentinel/level report)
   :sentinel/structural-level (:sentinel/structural-level report)
   :sentinel/reasons (vec (sort (:sentinel/reasons report)))
   :sentinel/override-required? (get-in report [:sentinel/override-required? :required?])
   :sentinel/authority-key-id authority-key-id
   :sentinel/authority-assurance authority-assurance
   :sentinel/issued-at issued-at})

;; ── Response ────────────────────────────────────────────────────────────────

(defn build-response
  "Frame a signed decision for the client, echoing the request id and hash."
  [request decision]
  {:response/kind response-kind
   :response/version protocol-version
   :request/id (:request/id request)
   :request/hash (:request/hash request)
   :decision decision})
