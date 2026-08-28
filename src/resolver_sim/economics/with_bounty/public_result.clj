(ns resolver-sim.economics.with-bounty.public-result
  "Canonical public-result projection for verifier comparison (ADR-0006 R5).

   Verifier outputs are compared on a frozen public result, not a coarse
   boolean. The projection includes only the committed public roots of an
   evaluation; diagnostics, replay inputs, and implementation-specific metadata
   are excluded. Public-result equality is canonical-byte equality over the
   projection; missing or extra public fields are failures."
  (:require [resolver-sim.hash.canonical :as hc]))

(def public-result-domain-tag
  :with-bounty-public-result-v1)

(def public-result-statuses
  "Closed evaluator status vocabulary for with-bounty public results."
  #{:invalid-policy :resolution-failed :skipped :failed :applied})

(def public-result-fields
  [:status
   :composition/policy-root
   :composition/base-operation-root
   :extensions/resolution-root
   :bounty/obligation-id
   :bounty/effect-root
   :bounty/application-plan-root])

(defn public-result-projection
  "The canonical public projection of an evaluation result. Excludes
   :replay/inputs, invocation evidence, and the plan/effect payloads — only
   the committed public roots and the classification remain.  Single source of
   truth: resolver-sim.hash.canonical/project-with-bounty-public-result."
  [result]
  (hc/project-with-bounty-public-result result nil))

(defn public-result-root
  "Content-addressed root of the canonical public result."
  [result]
  (hc/domain-hash public-result-domain-tag
                  (hc/project-with-bounty-public-result result nil)))

(defn validate-public-result
  "Structural check that a public-result projection carries exactly the
   committed public fields (missing and extra fields are failures). Roots
   always present: policy, base-operation, resolution. For an :applied result,
   obligation id, effect root, and application-plan root are required strings;
   for a :skipped result those three are nil by design."
  [projection]
  (let [extra (vec (sort (remove (set public-result-fields) (keys projection))))
        missing (vec (remove #(contains? projection %) public-result-fields))
        always-roots [:composition/policy-root
                      :composition/base-operation-root
                      :extensions/resolution-root]
        non-strings (vec (filter #(not (string? (get projection %))) always-roots))
        applied-roots [:bounty/obligation-id
                       :bounty/effect-root
                       :bounty/application-plan-root]
        non-strings-applied (when (= :applied (:status projection))
                              (vec (filter #(not (string? (get projection %)))
                                           applied-roots)))
        errors (cond-> []
                 (not (contains? public-result-statuses (:status projection)))
                 (conj [:invalid-status (:status projection)])
                 (seq extra) (conj [:extra-fields extra])
                 (seq missing) (conj [:missing-fields missing])
                 (seq non-strings) (conj [:non-string-roots non-strings])
                 (seq non-strings-applied) (conj [:non-string-roots non-strings-applied]))]
    (if (seq errors)
      {:valid? false :errors errors}
      {:valid? true})))
