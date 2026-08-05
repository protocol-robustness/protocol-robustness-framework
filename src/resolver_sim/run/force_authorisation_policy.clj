(ns resolver-sim.run.force-authorisation-policy
  "Canonical, self-validating force-authorisation policy artifact.
   Defines membership, threshold, scope and lifecycle rules for
   force-authorisation operations across policy families.

   Referenced by hash from verdict-policy, propagation-policy, etc.
   When verifying a supersession, the consumer MUST verify that the
   committed force_authorisation_policy_hash matches the resolved
   policy artifact — never trust an unverified registry lookup."
  (:require [clojure.java.io :as io]
            [resolver-sim.commands.run-lifecycle :as lifecycle]
            [resolver-sim.hash.canonical :as canonical]))

(def schema-version "force-authorisation-policy.v1")
(def domain "PRF_FORCE_AUTHORISATION_POLICY_V1")

;;; ============================================================
;;; Low-level helpers
;;; ============================================================

(defn- check [pred err]
  (if pred [] [err]))

(defn policy-hash
  "Compute the self-committing hash of a force-authorisation policy."
  [policy]
  (str "sha256:" (canonical/domain-hash domain (dissoc policy "policy_sha256"))))

;;; ============================================================
;;; Validation
;;; ============================================================

(defn validate
  "Validate a force-authorisation policy map.
   Returns the policy map on success, throws on failure."
  [policy]
  (let [errors
        (into []
              cat
              [(check (= schema-version (get policy "schema_version"))
                      (str "schema_version mismatch: expected " schema-version ", got " (pr-str (get policy "schema_version"))))
               (check (string? (get policy "policy_id"))
                      (str "policy_id must be a string, got " (pr-str (get policy "policy_id"))))
               (check (integer? (get policy "policy_version"))
                      (str "policy_version must be an integer, got " (pr-str (get policy "policy_version"))))
               (check (integer? (get policy "member_count"))
                      (str "member_count must be an integer, got " (pr-str (get policy "member_count"))))
               (check (integer? (get policy "threshold"))
                      (str "threshold must be an integer, got " (pr-str (get policy "threshold"))))
               (check (<= 1 (get policy "threshold" 0) (get policy "member_count" 1))
                      (str "threshold must be between 1 and member_count (" (get policy "member_count") "), got " (get policy "threshold")))
               (check (true? (get policy "scope_required?" true))
                      (str "scope_required? must be true, got " (pr-str (get policy "scope_required?"))))
               (check (true? (get policy "single_use?" true))
                      (str "single_use? must be true, got " (pr-str (get policy "single_use?"))))
               (check (true? (get policy "preserve_dissent?" true))
                      (str "preserve_dissent? must be true, got " (pr-str (get policy "preserve_dissent?"))))])]
    (when (seq errors)
      (throw (ex-info "Force-authorisation policy validation failed" {:errors errors})))
    policy))

;;; ============================================================
;;; Build
;;; ============================================================

(defn build
  "Build a force-authorisation policy artifact with self-committing hash.
   Accepts keyword-keyed options map and returns a string-keyed artifact.
   Required: :policy-id, :member-count, :threshold."
  [{:keys [policy-id policy-version member-count threshold
           scope-required? single-use? preserve-dissent? expiry-required?
           allowed-reasons]}]
  (let [fields {"schema_version" schema-version
                "policy_id" policy-id
                "policy_version" (or policy-version 1)
                "member_count" member-count
                "threshold" threshold
                "scope_required?" (boolean (if (some? scope-required?) scope-required? true))
                "single_use?" (boolean (if (some? single-use?) single-use? true))
                "preserve_dissent?" (boolean (if (some? preserve-dissent?) preserve-dissent? true))
                "expiry_required?" (boolean (if (some? expiry-required?) expiry-required? false))
                "allowed_reasons" (or allowed-reasons
                                      ["policy-migration" "registry-update"
                                       "evaluator-update" "governance-mandated"])}
        validated (validate fields)]
    (assoc validated "policy_sha256" (policy-hash validated))))

;;; ============================================================
;;; Verify artifact integrity
;;; ============================================================

(defn verify-artifact
  "Validate a parsed force-authorisation policy artifact's internal consistency.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [artifact]
  (try
    (validate artifact)
    (let [expected (policy-hash artifact)
          actual (get artifact "policy_sha256")]
      (if (= expected actual)
        {:valid? true}
        {:valid? false
         :errors [(str "policy_sha256 self-commitment does not match: expected " expected ", got " actual)]}))
    (catch Exception e
      {:valid? false :errors [(.getMessage e)]})))

;;; ============================================================
;;; Default policies
;;; ============================================================

(def default-research-policy
  "Default three-member force-authorisation policy for research operations."
  (delay
    (build {:policy-id "research-policy-update-v1"
            :policy-version 1
            :member-count 3
            :threshold 2
            :scope-required? true
            :single-use? true
            :preserve-dissent? true
            :expiry-required? true
            :allowed-reasons ["policy-migration" "registry-update"
                              "evaluator-update" "governance-mandated"]})))
