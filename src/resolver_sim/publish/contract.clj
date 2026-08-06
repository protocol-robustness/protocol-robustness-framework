(ns resolver-sim.publish.contract
  "Out-of-process artifact publisher contract.

   Defines the wire request, the signed publish certificate, their domain
   separation tags, and the deterministic commitments that the client and the
   authority both rely on. This is the single source of truth for shapes so the
   two processes cannot drift apart.

   The authority independently re-verifies the artifact set from disk and only
   signs a certificate when the whole declared set is intact (all-or-nothing).
   Caller-supplied values are commitments the authority cross-checks, never
   trusted inputs."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.publish.manifest :as manifest]
            [resolver-sim.signed-external-decision :as sed]))

;; ── Domain separation tags ───────────────────────────────────────────────────

(def request-kind :artifact-publish-check)
(def decision-kind :artifact-publish-certificate)
(def response-kind :artifact-publish-response)

(def ^:const request-domain "PRF_ARTIFACT_PUBLISH_REQUEST_V1")
(def ^:const decision-domain "PRF_ARTIFACT_PUBLISH_DECISION_V1")

(def policy-id "artifact-publish-policy.v1")
(def policy-version "artifact-publish-policy.v1.0")
(def ^:const protocol-version 1)

(defn policy-hash
  "Deterministic hash of the publish policy. Commits to the policy id and the
   all-or-nothing set-check profile it encodes. Pure; no side effects."
  []
  (hc/hash-with-intent {:hash/intent :evidence-record}
                       {:version policy-version
                        :policy/id policy-id
                        :set-check "all-or-nothing: every declared artifact present and hash-verified"
                        :authority-role :artifact-publisher}))

;; ── Request ─────────────────────────────────────────────────────────────────

(defn build-request
  "Build an artifact-publish check request.

   Args:
     id        — caller-chosen request id (string or uuid)
     root      — stage directory path (the authority reads files from here)
     run-id    — run identity string
     manifest  — entries <[{:path str :sha256 hex}]> (the full declared set)
     required  — <[str]> required subset of the manifest paths
     policy-hash-str — committed policy hash (defaults to (policy-hash))

   Returns the request with :publish/declared-commit and :request/hash
   attached."
  [{:keys [id root run-id manifest required policy-hash-str]}]
  (let [entries (mapv (fn [e]
                        {:path (str (:path e))
                         :sha256 (str (:sha256 e))})
                      manifest)
        declared-commit (manifest/manifest-commit run-id entries)
        base {:request/kind request-kind
              :request/version protocol-version
              :request/id id
              :publish/root (str root)
              :publish/run-id (str run-id)
              :publish/manifest entries
              :publish/required (vec (map str required))
              :publish/declared-commit declared-commit
              :policy/id policy-id
              :policy/hash (or policy-hash-str (policy-hash))}]
    (sed/attach-request-hash request-domain base)))

(def request-allowed-top-level
  #{:request/kind :request/version :request/hash :request/id
    :publish/root :publish/run-id :publish/manifest :publish/required
    :publish/declared-commit :policy/id :policy/hash})

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
         (when (and (map? req) (not (string? (:publish/root req))))
           "publish/root must be a string")
         (when (and (map? req) (nil? (:publish/run-id req)))
           "publish/run-id required")
         (when (and (map? req) (not (vector? (:publish/manifest req))))
           "publish/manifest must be a vector")
         (when (and (map? req) (nil? (:publish/required req)))
           "publish/required required")
         (when (and (map? req) (nil? (:publish/declared-commit req)))
           "publish/declared-commit required")
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

;; ── Decision certificate ────────────────────────────────────────────────────

(defn build-decision
  "Assemble the publish certificate for a hash-verified request whose artifact
   set passed all-or-nothing verification. The certificate binds the run
   identity, the stage root, the complete manifest commitment and the required
   subset.

   Returns the unsigned envelope (add :signature by signing via the
   signed-external-decision primitive)."
  [{:keys [request manifest-commit authority-key-id authority-assurance issued-at]}]
  {:artifact/kind decision-kind
   :artifact/version protocol-version
   :publish/request-hash (:request/hash request)
   :publish/run-id (:publish/run-id request)
   :publish/root (:publish/root request)
   :publish/manifest-commit manifest-commit
   :publish/required (vec (sort (:publish/required request)))
   :publish/decision :approve
   :publish/policy-id policy-id
   :publish/policy-hash (:policy/hash request)
   :publish/authority-key-id authority-key-id
   :publish/authority-assurance authority-assurance
   :publish/issued-at issued-at})

;; ── Response ────────────────────────────────────────────────────────────────

(defn build-response
  "Frame a signed certificate for the client, echoing the request id and hash."
  [request decision]
  {:response/kind response-kind
   :response/version protocol-version
   :request/id (:request/id request)
   :request/hash (:request/hash request)
   :decision decision})