(ns resolver-sim.evidence.attestation-policy
  "Attestation policy: rules governing which attestors may attest which
   subjects, claims, and claim results.

   A policy is a named set of rules. Each rule specifies the allowed
   attestors, subject kinds, claim IDs, and claim results. The evaluator
   checks an attestation against a policy and returns structured results.

   Usage:
     (require '[resolver-sim.evidence.attestation-policy :as apol])

     ;; Register a default policy
     (apol/register-policy!
       {:policy-id :default
        :description \"Default attestation policy\"
        :rules [...]})

     ;; Evaluate an attestation against the default policy
     (apol/evaluate-attestation attestation)

     ;; Check compliance directly
     (apol/check-attestation attestation {:policy-id :default})"
  (:require [resolver-sim.evidence.attestation-registry :as ar]))

;; ── Registry Atom ────────────────────────────────────────────────────────────

(def ^:dynamic *policy-registry*
  "In-memory registry of attestation policies keyed by :policy-id."
  (atom {}))

(defmacro with-fresh-registry
  "Execute body with a fresh policy registry.
   The outer registry is restored when body exits.
   Uses dynamic binding for thread-safe test isolation."
  [& body]
  `(let [fresh-atom# (atom {})]
     (binding [*policy-registry* fresh-atom#]
       ~@body)))

(defn clear-policies!
  "Reset the policy registry to empty."
  []
  (reset! *policy-registry* {})
  nil)

;; ── Policy Registration ─────────────────────────────────────────────────────

(defn register-policy!
  "Register an attestation policy.

   A policy map must have:
     :policy-id    — unique keyword identifier
     :description  — human-readable description
     :rules        — vector of rule maps

   Each rule map may have:
     :attestors     — set of allowed attestor-ids (default: all)
     :subject-kinds — set of allowed subject kinds (default: all)
     :claim-ids     — set of allowed claim ids (default: all)
     :claim-results — set of allowed claim result keywords (default: all)

   Returns the policy map."
  [policy]
  (swap! *policy-registry* assoc (:policy-id policy) policy)
  policy)

(defn find-policy
  "Look up a policy by its :policy-id.
   Returns the policy map or nil if not found."
  [policy-id]
  (get @*policy-registry* policy-id))

(defn all-policies
  "Return all registered policies as a vector."
  []
  (->> @*policy-registry* vals vec))

;; ── Rule Matching ────────────────────────────────────────────────────────────

(defn- rule-matches?
  "Check if an attestation matches a single rule.

   A rule matches when ALL of the attestation's fields are within the
   rule's allowed sets. A nil/missing set means 'allow all' for that
   field.

   Returns {:matches? true} or {:matches? false :reason \"...\"}."
  [attestation rule]
  (let [;; Allowed sets (nil = unrestricted)
        allowed-attestors (:attestors rule)
        allowed-subject-kinds (:subject-kinds rule)
        allowed-claim-ids (:claim-ids rule)
        allowed-claim-results (:claim-results rule)

        ;; Actual values
        attestor (:attestation/attestor-id attestation)
        subject-kind (:attestation/subject-kind attestation)
        claim-id (:attestation/claim-id attestation)
        claim-result (:attestation/claim-result attestation)]

    (cond
      (and allowed-attestors (not (contains? allowed-attestors attestor)))
      {:matches? false
       :reason (str "Attestor " attestor " not in allowed set " allowed-attestors)}

      (and allowed-subject-kinds (not (contains? allowed-subject-kinds subject-kind)))
      {:matches? false
       :reason (str "Subject kind " subject-kind " not in allowed set " allowed-subject-kinds)}

      (and allowed-claim-ids (not (contains? allowed-claim-ids claim-id)))
      {:matches? false
       :reason (str "Claim id " claim-id " not in allowed set " allowed-claim-ids)}

      (and allowed-claim-results (not (contains? allowed-claim-results claim-result)))
      {:matches? false
       :reason (str "Claim result " claim-result " not in allowed set " allowed-claim-results)}

      :else
      {:matches? true})))

;; ── Policy Evaluation ────────────────────────────────────────────────────────

(defn evaluate-attestation
  "Evaluate an attestation against a policy.

   Checks each rule in the policy. The attestation complies if ANY rule
   matches (OR semantics across rules).

   Arguments:
     attestation — attestation record
     policy-id   — keyword identifying the policy (default: :default)
                   If policy-id is nil or policy not found, returns
                   {:compliant? :policy-not-found :policy-id policy-id}.

   Returns:
     {:compliant? true
      :policy-id <id>
      :matched-rule <rule>}

     {:compliant? false
      :policy-id <id>
      :reasons [<str> ...]}

     {:compliant? :policy-not-found
      :policy-id <id>}"
  [attestation & [policy-id]]
  (let [pid (or policy-id :default)
        policy (find-policy pid)]
    (if (nil? policy)
      {:compliant? :policy-not-found :policy-id pid}
      (let [rules (:rules policy)
            results (mapv (partial rule-matches? attestation) rules)
            matched-idx (first (keep-indexed (fn [i r] (when (:matches? r) i)) results))]
        (if matched-idx
          {:compliant? true
           :policy-id pid
           :matched-rule (nth rules matched-idx)}
          {:compliant? false
           :policy-id pid
           :reasons (mapv :reason (remove :matches? results))})))))

;; ── Direct Compliance Check ─────────────────────────────────────────────────

(defn check-attestation
  "Directly check if an attestation complies with policy rules.

   Unlike evaluate-attestation which uses a registered policy, this
   function accepts inline rules.

   Arguments:
     attestation — attestation record
     rules       — vector of rule maps (same structure as policy :rules)

   Returns {:compliant? true <details>} or {:compliant? false :reasons [...]}."
  [attestation rules]
  (let [results (mapv (partial rule-matches? attestation) rules)
        matched-idx (first (keep-indexed (fn [i r] (when (:matches? r) i)) results))]
    (if matched-idx
      {:compliant? true
       :matched-rule (nth rules matched-idx)}
      {:compliant? false
       :reasons (mapv :reason (remove :matches? results))})))

;; ── Registry-wide Check ─────────────────────────────────────────────────────

(defn check-registry
  "Run a policy check against all attestations currently in the
   attestation registry.

   Arguments:
     policy-id — keyword identifying the policy

   Returns a map with summary of compliant/non-compliant attestations."
  [policy-id]
  (let [all (ar/all-attestations)
        results (mapv (fn [a]
                        {:attestation/id (:attestation/id a)
                         :result (evaluate-attestation a policy-id)})
                      all)
        compliant (filter #(true? (:compliant? (:result %))) results)
        non-compliant (filter #(false? (:compliant? (:result %))) results)]
    {:policy-id policy-id
     :total-checked (count all)
     :compliant-count (count compliant)
     :non-compliant-count (count non-compliant)
     :non-compliant (mapv (fn [r]
                            {:attestation/id (:attestation/id r)
                             :reasons (:reasons (:result r))})
                          non-compliant)}))
