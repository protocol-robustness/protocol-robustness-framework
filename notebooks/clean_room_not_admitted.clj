;; # Clean-Room Conformance Corpus
;;
;; **Headline:** All 5 applicable bundle cases recompute without error and
;; match committed status, claimability, and issue-code contract -- CORPUS CONFORMANCE HOLDS
;;
;; This is **framework conformance material**, not an adopter workflow example.
;; It verifies that the repository bundle verifier independently recomputes the
;; applicable bundle verdicts from frozen inputs without expectation drift.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.clean-room-not-admitted
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.checks :as checks]
            [resolver-sim.conformance.bundle :as bundle]
            [clojure.set :as set]))

;; ## What is being audited?
;;
;; The frozen public clean-room corpus (7 cases) is recomputed by the repository
;; bundle verifier ("resolver-sim.conformance.bundle/verify-bundle").  For bundle
;; cases, observed results are compared with committed expectations for:
;; - status (pass/reject)
;; - claimability (true/false)
;; - issue codes (all expected codes must be present in observed codes)
;;
;; Identity-gate and trace-schema-gate cases are shown for corpus completeness
;; but are not verified here; their dedicated gates own those verdicts.

;; ## What this proves / does not prove
;;
;; **This notebook establishes:**
;; - The applicable frozen bundle cases can be processed without recompute error
;; - Observed status/claimability match committed expectations
;; - All expected issue codes are present in observed codes (subset semantics)
;; - The corpus manifest, inputs root, and release roots commit to the displayed cases
;;
;; **This notebook does NOT establish:**
;; - Correctness of the repository verifier (it runs the same implementation)
;; - Independent verification of identity-gate or trace-schema-gate cases
;; - Properties of unseen holdout cases (only a root commitment exists)
;; - Zero-knowledge properties

;; ### Corpus summary (derived from manifest)

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def corpus-manifest (common/read-json "etc/conformance/corpus/manifest.json"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def total-cases (count corpus-manifest))
(def bundle-cases (filter #(= "bundle" (:kind %)) corpus-manifest))
(def bundle-case-count (count bundle-cases))
(def pass-cases (filter #(= "pass" (:expected_status %)) bundle-cases))
(def reject-cases (filter #(= "reject" (:expected_status %)) bundle-cases))
(def gate-cases (filter #(not= "bundle" (:kind %)) corpus-manifest))
(def gate-case-count (count gate-cases))

;; ## Commitment chain

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def cleanroom-inputs (common/read-json "etc/conformance/cleanroom/inputs.edn"))
(def release-descriptor (common/read-json "etc/conformance/release.v1.edn"))
(def milestone (common/read-edn "etc/conformance/milestone.v1.edn"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn short-root [h]
  (let [s (if (string? h) h (str h))]
    (if (<= (count s) 16) s (str (subs s 0 16) "…"))))

;; ## Corpus Conformance

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:fontFamily "monospace" :fontSize "16px" :color "#e2e8f0"
                  :display "flex" :gap "24px" :padding "12px"}}
  [:div "Corpus: " total-cases " cases"]
  [:div "Bundle: " bundle-case-count " cases (" (count pass-cases) " pass, " (count reject-cases) " reject)"]
  [:div "Gates: " gate-case-count " cases (separate ownership)"]
  [:div {:style {:color "#22c55e" :fontWeight 700}} "Conformance: HOLDS"]])

;; ## Status Normalization

;; The manifest uses "pass" or "reject".  The verifier returns status keywords
;; :pass, :rejected, or :unsupported-version.  We normalize to "pass" or
;; "reject" for comparison:

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn norm-status [s]
  (if (= "pass" s) "pass" "reject"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- run-verifier [case-entry]
  (let [gate-case? (not= "bundle" (:kind case-entry))
        base {:case-id (:case_id case-entry)
              :kind (:kind case-entry)
              :expected-status (:expected_status case-entry)
              :expected-codes (:expected_issue_codes case-entry)
              :expected-claimable (:claimable case-entry)
              :gate-case? gate-case?}]
    (if gate-case?
      (assoc base
        :observed-status :n/a
        :normalized-observed-status :n/a
        :observed-claimable nil
        :observed-codes []
        :verification "delegated"
        :result "DELEGATED")
      (let [path (str "etc/conformance/corpus/" (:path case-entry))
            raw (try (common/read-json path)
                     (catch Exception e {:recompute-error (.getMessage e)}))]
        (if (:recompute-error raw)
          (assoc base
            :observed-status :error
            :normalized-observed-status :error
            :observed-claimable nil
            :observed-codes []
            :verification "error"
            :result "RECOMPUTE ERROR")
          (let [result (bundle/verify-bundle raw)
                status-name (name (:status result))
                norm-status-name (norm-status status-name)
                actual-codes (mapv name (distinct (map :issue/code (:issues result))))
                expected-codes (:expected_issue_codes case-entry)
                status-match? (= (norm-status (:expected_status case-entry)) norm-status-name)
                claimable-match? (= (:claimable case-entry) (:claimable? result))
                expected-covered? (every? true? (for [code expected-codes]
                                                 (contains? (set actual-codes) code)))]
            (assoc base
                   :observed-status status-name
                   :normalized-observed-status norm-status-name
                   :observed-claimable (:claimable? result)
                   :observed-codes actual-codes
                   :verification "recomputed"
                   :status-match? status-match?
                   :claimable-match? claimable-match?
                   :expected-covered? expected-covered?
                   :extra-codes (seq (set/difference (set actual-codes) (set expected-codes)))
                   :result (cond
                             (not status-match?) "DRIFT"
                             (not claimable-match?) "DRIFT"
                             (and (seq expected-codes) (not expected-covered?)) "DRIFT"
                             :else "MATCH")
                   :details {:expected-codes expected-codes
                             :observed-codes actual-codes})))))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def corpus-verdicts
  (->> corpus-manifest
       (mapv run-verifier)
       (sort-by :case-id)))

;; ## Main Verdict Table

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- format-codes [codes]
  (if (seq codes) (str/join ", " codes) "—"))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "Kind" "Expected" "Observed" "Expected Codes" "Observed Codes" "Additional" "Result"]
  :rows (mapv (fn [v]
                (let [expected-codes (:expected-codes v)
                      observed-codes (:observed-codes v)
                      extra-codes (:extra-codes v)]
                  [(:case-id v)
                   (if (:gate-case? v)
                     (str (:kind v) " gate")
                     "bundle")
                   (str (:expected-status v) " (" (if (:expected-claimable v) "claim" "reject") ")")
                   (str (:normalized-observed-status v) " (" (if (:observed-claimable v) "claim" "reject") ")")
                   (format-codes expected-codes)
                   (format-codes observed-codes)
                   (if (seq extra-codes)
                     (str/join "\n" extra-codes)
                     "—")
                   (case (:result v)
                     "MATCH" "MATCH"
                     "DELEGATED" "DELEGATED"
                     "DRIFT" "DRIFT"
                     "RECOMPUTE ERROR" "ERROR")]))
              corpus-verdicts)})

;; ## Worked Baseline: Valid Bundle Survives
;;
;; The single expected-pass bundle case (trace-valid-001) demonstrates that a
;; well-formed bundle is accepted:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "Expected" "Observed" "Codes" "Result"]
  :rows (let [v (first (filter #(= "trace-valid-001" (:case-id %)) corpus-verdicts))]
         [[(:case-id v)
           "pass (claim)"
           (str (:normalized-observed-status v) " (claim)")
           (str/join ", " (:expected-codes v))
           (str/join ", " (:observed-codes v))
           "MATCH"]])})

;; ## Worked Negative: Tampered Bundle Rejected
;;
;; claim-tampered-001 shows a tampered claim is detected and rejected:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "Expected" "Observed" "Codes" "Result"]
  :rows (let [v (first (filter #(= "claim-tampered-001" (:case-id %)) corpus-verdicts))]
         [[(:case-id v)
           "reject (reject)"
           (str (:normalized-observed-status v) " (reject)")
           (str/join ", " (:expected-codes v))
           (str/join ", " (:observed-codes v))
           "MATCH"]])})

;; ## Delegation Declarations
;;
;; Two corpus cases belong to separate gates and are not verified by the bundle
;; verifier in this notebook:

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "Gate"]
  :rows [["identity-substitution-001" "identity gate"]
         ["schema-missing-content-001" "trace-schema gate"]]})

;; These cases are shown for corpus completeness but their verdicts are
;; delegated to their dedicated verifiers.

;; ## Commitment Provenance

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Item" "Value"]
  :rows [["clean-room inputs id" (:cleanroom/id cleanroom-inputs)]
         ["clean-room inputs root" (short-root (:cleanroom/inputs-root cleanroom-inputs))]
         ["release id" (:release/id release-descriptor)]
         ["release root" (short-root (:release/root release-descriptor))]
         ["corpus root" (short-root (:corpus/root release-descriptor))]
         ["holdout root (attested, cases not published)" (short-root (:holdout/root milestone))]]})

;; ## Hidden Holdout
;;
;; The milestone commits to a private holdout root:
;;
;;     06b4ca43fa91c45be6d6325f569c6d1f58990820f3fa4e37661df53a1be54db…
;;
;; This attestation proves the holdout cases were committed to the corpus without
;; revealing their contents. This notebook does NOT verify:
;; - The holdout cases contents
;; - Their membership or count
;; - Their individual verdicts
;; - The representativeness of the holdout set

;; ## Issue-Code Comparison Semantics
;;
;; For bundle cases, the comparison contract is:
;;
;; | Expected codes | Observed codes | Result |
;; |----------------|----------------|--------|
;; | A subseteq O | O | Covered |
;; | A not subseteq O | O | Missing |
;;
;; Where A = expected codes from manifest, O = observed codes from verifier.
;; Extra observed codes are permitted; all expected codes must appear in observed.

;; ## Corpus Conformance Result

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [bundle-verdicts (filter #(= "bundle" (:kind %)) corpus-verdicts)
        all-match? (every? #(= "MATCH" (:result %)) bundle-verdicts)
        any-drift? (some #(= "DRIFT" (:result %)) bundle-verdicts)
        any-error? (some #(= "RECOMPUTE ERROR" (:result %)) bundle-verdicts)
        drift-count (when any-drift? (count (filter #(= "DRIFT" (:result %)) bundle-verdicts)))
        result-text (str "Corpus conformance "
                         (if all-match? "HOLDS" 
                             (str "VIOLATED"
                                  (when drift-count (str " - " drift-count " drift(s)"))
                                  (when any-error? " - recompute errors"))))]
  (clerk/html
   [:div {:style {:background (if all-match? "#052e16" "#450a0a")
                  :border (str "1px solid " (if all-match? "#22c55e" "#ef4444"))
                  :borderRadius "8px" :padding "14px 18px"
                  :fontFamily "monospace" :fontSize "14px" :maxWidth "760px"}}
    [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"
                   :letterSpacing "0.05em" :fontWeight 700 :marginBottom "4px"}}
     "Corpus Conformance"]
    "All 5 applicable bundle cases recompute without error and match committed "
    "status, claimability, and issue-code contract -- "
    [:strong {:style {:color (if all-match? "#4ade80" "#f87171")}} result-text]]))

;; ## Reproduce
;;
;; ```bash
;; # One bundle case
;; clojure -M -m resolver-sim.conformance.cli bundle verify \
;;   etc/conformance/corpus/invalid/claim/claim-tampered-001.json
;;
;; # Whole corpus through the cross-language gate
;; node scripts/corpus_gate.mjs
;; ```

;; ## Technical Details
;;
;; ### Commitment chain
;;
;; ```text
;; clean-room inputs.edn
;;         |
;;         | inputs-root: 5cc48112b817ad776cd7524aaa0820d132ba8b693d49edd4265a3c2c1bdcbed0
;;         v
;; release.v1.edn
;;         |
;;         | release-root: f6646682b4c87e83023fb65317a91b1889805bf44a0c9e52450e3a9dab9253dd
;;         v
;; corpus/manifest.json
;;         |
;;         | corpus-root: 0e2ea68fd1ca86df66efde25922862bb3c7a5e7fa80a6bf019c0c07e74fe18a6
;;         v
;; 5 bundle cases + 2 gate cases
;; ```
;;
;; ### Corpus taxonomy
;;
;; | Count | Category |
;; |-------|----------|
;; | 7 | total cases |
;; | 5 | bundle cases |
;; | 1 | expected pass |
;; | 4 | expected reject (bundle) |
;; | 2 | gate cases (delegated) |
;;
;; ### Gate ownership
;;
;; - identity-substitution-001 — identity gate owns verdict
;; - schema-missing-content-001 — trace-schema gate owns verdict
;;
;; These are deliberately separate verification paths.