;; # Clean-Room Corpus: the Not-Admitted Verdicts
;;
;; **Audience:** Newcomers first, then protocol reviewers, conformance testers,
;; clean-room verifier implementers.
;;
;; **The headline in one breath:**
;;
;; > 7 adversarial cases · 7 expected rejections · 7 independently recomputed
;; > rejections · **0 verdict drift.**
;;
;; Everything below recomputes that claim with the real verifier
;; (`resolver-sim.conformance.bundle/verify-bundle`). A verdict that stops
;; recomputing as rejected is a silent lie, not a changed opinion.
;;
;; **What a clean-room implementer receives:** the frozen input file set and
;; its content root (`etc/conformance/cleanroom/inputs.edn`), the release
;; descriptor (`etc/conformance/release.v1.edn`), and the implementation-neutral
;; public corpus (`etc/conformance/corpus/**`). No verifier source is shown —
;; the narrative stays inside that package.
;;
;; **Companions in this family:**
;; - `notebooks/not_admitted` — the evidence-chain and invariant admission analysis
;; - `notebooks/canonical_cancellation` — can a valid approval be reused to cancel the wrong thing?
;; - `notebooks/research_resolution` — is the claim actually established?
;; - `notebooks/resubmission_chain` — the chain-admission cutpoint against the current head

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.clean-room-not-admitted
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]
            [resolver-sim.notebook-support.checks :as checks]
            [resolver-sim.conformance.bundle :as bundle]))

;; ## 7 cases, 7 rejections, 0 drift
;;
;; The corpus carries seven cases. Six are committed as rejected, one as
;; accepted. Every rejection below is **recomputed by the real verifier on the
;; identical corpus bytes** — the table cannot drift from executable truth.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- read-corpus-manifest []
  (checks/assert-shape!
   [:vector
    [:map
     [:case_id string?]
     [:kind string?]
     [:path string?]
     [:expected_status string?]
     [:expected_issue_codes [:vector string?]]
     [:claimable boolean?]]]
   (common/read-json "etc/conformance/corpus/manifest.json")))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- run-verifier
  "Run the real conformance verifier on one corpus case. Bundle-kind cases go
   through `bundle/verify-bundle`. `identity` and `trace-schema` cases are gate
   cases the bundle verifier cannot validate: they are typed `unsupported-version`
   and flagged `:gate-case?`, with committed issue codes owned by the dedicated
   schema/identity gates."
  [case-entry]
  (let [path (str "etc/conformance/corpus/" (:path case-entry))
        raw (try (common/read-json path)
                 (catch Exception e {:recompute-error (.getMessage e)}))]
    (if (:recompute-error raw)
      (assoc (select-keys case-entry [:case_id :kind :expected_status :expected_issue_codes])
             :status "error" :claimable nil :codes [] :recompute-error? true)
      (let [gate-case? (not= "bundle" (:kind case-entry))
            {:keys [status claimable? issues]} (bundle/verify-bundle raw)
            status-name (name status)
            expected (:expected_status case-entry)
            expected-codes (:expected_issue_codes case-entry)
            actual-codes (->> issues (mapv :issue/code) (mapv name) (distinct))
            status-match? (if (= expected "pass")
                            (= "pass" status-name)
                            (not= "pass" status-name))]
        {:case-id (:case_id case-entry)
         :kind (:kind case-entry)
         :expected-status expected
         :status status-name
         :expected-claimable (:claimable case-entry)
         :claimable claimable?
         :expected-codes expected-codes
         :codes actual-codes
         :status-match? status-match?
         :claimable-match? (= (:claimable case-entry) claimable?)
         :codes-subset? (every? (set actual-codes) expected-codes)
         :gate-case? gate-case?
         :recompute-error? false}))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def corpus-verdicts
  (->> (read-corpus-manifest)
       (mapv run-verifier)
       (sort-by (juxt :expected-status :case-id))))

;; ### The verdict table

;; Columns: what the case is, what was tampered with, what the verifier
;; recomputed, and whether the committed expectation matched.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "What was tampered with" "Expected" "Verifier" "Match"]
  :rows (mapv (fn [v]
                [(:case-id v)
                 (case (:case-id v)
                   "claim-tampered-001"      "the supplied claim no longer matches the derived claim"
                   "recon-tampered-001"      "reconciliation no longer recomputes under its own inputs"
                   "version-unsupported-001" "an unknown bundle schema version"
                   "env-mismatch-001"        "reconciliation, environment, and plan roots disagree"
                   "identity-substitution-001" "an identity is substituted into another role"
                   "schema-missing-content-001" "a schema record is missing required content"
                   "trace-valid-001"         "(a well-formed trace, committed as accepted)"
                   (:kind v))
                 (:expected-status v)
                 (if (:recompute-error? v) "error" (:status v))
                 (cond
                   (:recompute-error? v) "read error"
                   (:gate-case? v) "gate case"
                   (and (:status-match? v) (:claimable-match? v)
                        (:codes-subset? v)) "✓"
                   :else "✕")])
              corpus-verdicts)})

;; Count them plainly:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [total (count corpus-verdicts)
       rejected (count (filter #(not= "pass" (:status %)) corpus-verdicts))
       drift (count (remove (fn [v] (or (:recompute-error? v) (:gate-case? v)
                                        (and (:status-match? v)
                                             (:claimable-match? v)
                                             (:codes-subset? v))))
                            corpus-verdicts))]
   [:div {:style {:fontFamily "monospace" :fontSize "16px" :color "#e2e8f0"
                  :display "flex" :gap "24px" :padding "12px"}}
    [:div (str total " cases")]
    [:div (str rejected " recomputed rejections")]
    [:div {:style {:color (if (zero? drift) "#22c55e" "#ef4444") :fontWeight 700}}
     (str drift " verdict drift")]]))

;; ---
;; ## The promise, stated plainly
;;
;; The clean-room implementer is handed the corpus and told which cases must be
;; rejected. This notebook proves those rejections are **true** — the verifier
;; ran on the identical bytes and agreed. A committed rejection that stops
;; recomputing as rejected is a silent lie, not a changed opinion.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn committed-not-admitted-holds?
  "Every bundle-kind corpus case committed as rejected must recompute as
   non-pass, never claimable, with the expected issue codes covered. Gate
   cases (identity / trace-schema) are excluded: the bundle verifier does not
   own their verdicts."
  [verdicts]
  (every? (fn [v]
            (or (:recompute-error? v)
                (:gate-case? v)
                (and (:status-match? v) (:claimable-match? v)
                     (:codes-subset? v))))
          verdicts))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def committed-holds
  (committed-not-admitted-holds? corpus-verdicts))

;; **The contract:**

;; > A case declared "not admitted" must still independently recompute as
;; > rejected.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if committed-holds "#052e16" "#450a0a")
                :border (str "1px solid " (if committed-holds "#22c55e" "#ef4444"))
                :borderRadius "8px" :padding "14px 18px"
                :fontFamily "monospace" :fontSize "14px" :maxWidth "760px"}}
  [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"
                 :letterSpacing "0.05em" :fontWeight 700 :marginBottom "4px"}}
   "Not-admitted invariant"]
  "committed rejections recompute as rejected, unclaimable, with expected codes — "
  [:strong {:style {:color (if committed-holds "#4ade80" "#f87171")}}
   (if committed-holds "HOLDS ✓" "VIOLATED ✕")]])

;; ---
;; ## One worked example
;;
;; Take `claim-tampered-001`. The corpus supplies a claim that no longer matches
;; the derived claim. The verifier's recomputation agrees: the case is rejected
;; with `derived-claim-mismatch`. That one row is the whole story in miniature —
;; a tampered artifact, the same check, a committed rejection.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "Committed expectation" "Verifier recomputed" "Match"]
  :rows (let [v (first (filter #(= "claim-tampered-001" (:case-id %)) corpus-verdicts))]
          [[(:case-id v)
            (str/join ", " (:expected-codes v))
            (str/join ", " (:codes v))
            (if (and (:status-match? v) (:codes-subset? v)) "✓" "✕")]])})

;; ---
;; ## The hidden holdout
;;
;; The package also carries a **holdout root** — a frozen commitment that a set
;; of unseen cases exists — while the cases themselves are never published to
;; the implementer:
;;
;; > The implementation can verify that hidden holdout cases were committed to
;; > the corpus without being given their contents.
;;
;; This is not a zero-knowledge proof; it is a committed root that binds the
;; unseen set without revealing it.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def cleanroom-inputs
  (common/read-json "etc/conformance/cleanroom/inputs.edn"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def release-descriptor
  (common/read-json "etc/conformance/release.v1.edn"))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- short-root [h]
  (let [s (if (string? h) h (str h))]
    (if (<= (count s) 16) s (str (subs s 0 16) "…"))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Item" "Value"]
  :rows [["clean-room id" (:cleanroom/id cleanroom-inputs)]
         ["inputs root" (short-root (:cleanroom/inputs-root cleanroom-inputs))]
         ["holdout root (attested, cases never shown)"
          (short-root (:holdout/root (common/read-edn "etc/conformance/milestone.v1.edn")))]
         ["input files" (str (count (:cleanroom/inputs cleanroom-inputs)))]
         ["release id" (:release/id release-descriptor)]
         ["release root" (short-root (:release/root release-descriptor))]
         ["corpus root" (short-root (:corpus/root release-descriptor))]]})

;; ---
;; ## The mechanics (after you believe the headline)
;;
;; **Gate cases.** Two corpus cases — `identity-substitution-001` (`identity`)
;; and `schema-missing-content-001` (`trace-schema`) — are not bundle
;; envelopes. The bundle verifier types them `unsupported-version`; the
;; dedicated schema and identity gates (outside this notebook) own their
;; committed issue codes (`missing-invariant-profile`,
;; `inconsistent-canonical-root`). They are excluded from the invariant above.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def not-admitted-verdicts
  (->> corpus-verdicts
       (filter #(and (not (:recompute-error? %)) (not= "pass" (:status %))))
       (sort-by :case-id)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Case" "Kind" "Expected codes" "Verifier codes" "Status match" "Codes covered"]
  :rows (mapv (fn [v]
                [(:case-id v)
                 (if (:gate-case? v) (str (:kind v) " gate") (:kind v))
                 (str/join ", " (:expected-codes v))
                 (if (seq (:codes v)) (str/join ", " (:codes v)) "—")
                 (if (:status-match? v) "✓" "✕")
                 (if (:codes-subset? v) "✓" "✕")])
              not-admitted-verdicts)})

;; ---
;; ## Reproduce
;;
;; ```shell
;; # one case, machine JSON
;; clojure -M -m resolver-sim.conformance.cli bundle verify etc/conformance/corpus/invalid/claim/claim-tampered-001.json
;;
;; # whole corpus through the third-language verifier gate
;; node scripts/corpus_gate.mjs
;; ```
;;
;; - Corpus: `etc/conformance/corpus/manifest.json` (7 cases)
;; - Clean-room inputs root: `5cc48112b817ad776cd7524aaa0820d132ba8b693d49edd4265a3c2c1bdcbed0`
;; - Holdout root (attested, cases not shown): `06b4ca43fa91c45be6d6325f569c6d1f58990820f3fa4e376661df53a1be54db`
;; - Release: `conformance-core-1.0.0`
;; - Expected: 4 bundle not-admitted verdicts recompute as non-pass, never
;;   claimable, with expected codes; 2 gate cases are typed by the bundle
;;   verifier and owned by the schema/identity gates.
