;; # Clean-Room Conformance Corpus: Expected Verdicts
;;
;; **Audience:** Protocol reviewers, conformance testers, and clean-room
;; verifier implementers.
;;
;; **The headline in one breath:**
;;
;; > 7 frozen corpus cases · 4 bundle non-pass expectations · 1 bundle pass
;; > expectation · 2 separately owned gate cases.
;;
;; This is framework conformance material, not an adopter workflow example. It
;; checks whether the repository bundle verifier
;; (`resolver-sim.conformance.bundle/verify-bundle`) still agrees with committed
;; expectations for frozen public bundle inputs. A bundle verdict that no longer
;; recomputes is a verification failure, not a changed opinion.
;;
;; Identity and trace-schema cases are deliberately not claimed as independently
;; verified here: their dedicated gates own those verdicts. The notebook labels
;; them separately instead of counting them as bundle-verifier evidence.
;;
;; **What a clean-room implementer receives:** the frozen input file set and
;; its content root (`etc/conformance/cleanroom/inputs.edn`), the release
;; descriptor (`etc/conformance/release.v1.edn`), and the implementation-neutral
;; public corpus (`etc/conformance/corpus/**`). No verifier source is shown —
;; the narrative stays inside that package.
;;
;; **Companions in this family:**
;; - `notebooks/not_admitted` — generic admission boundaries and adopter ownership
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

;; ## Frozen bundle cases and separately owned gate cases
;;
;; The corpus carries seven cases: four bundle cases committed as non-pass, one
;; bundle case committed as pass, and two gate cases. The bundle rows below are
;; recomputed by the repository verifier on identical corpus bytes. The identity
;; and trace-schema rows remain visible but are explicitly not verified here.

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
  "Run the repository bundle verifier on one bundle case. `identity` and
   `trace-schema` cases are separately owned gate cases, so this notebook does
   not substitute the bundle verifier for their dedicated verification."
  [case-entry]
  (let [gate-case? (not= "bundle" (:kind case-entry))
        base {:case-id (:case_id case-entry)
              :kind (:kind case-entry)
              :expected-status (:expected_status case-entry)
              :expected-claimable (:claimable case-entry)
              :expected-codes (:expected_issue_codes case-entry)
              :gate-case? gate-case?}]
    (if gate-case?
      (assoc base :verification "not-run-here" :recompute-error? false)
      (let [path (str "etc/conformance/corpus/" (:path case-entry))
            raw (try (common/read-json path)
                     (catch Exception e {:recompute-error (.getMessage e)}))]
        (if (:recompute-error raw)
          (assoc base :verification "error" :status "error" :claimable nil :codes []
                 :recompute-error? true)
          (let [{:keys [status claimable? issues]} (bundle/verify-bundle raw)
                status-name (name status)
                actual-codes (->> issues (mapv :issue/code) (mapv name) distinct vec)
                expected (:expected_status case-entry)]
            (assoc base
                   :verification "recomputed"
                   :status status-name
                   :claimable claimable?
                   :codes actual-codes
                   :status-match? (if (= expected "pass")
                                    (= "pass" status-name)
                                    (not= "pass" status-name))
                   :claimable-match? (= (:claimable case-entry) claimable?)
                   :codes-subset? (every? (set actual-codes) (:expected_issue_codes case-entry))
                   :recompute-error? false)))))))

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
 {:head ["Case" "What was tampered with" "Expected" "Verification boundary" "Match"]
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
                 (if (:gate-case? v) "separate gate" (if (:recompute-error? v) "error" (:status v)))
                 (cond
                   (:recompute-error? v) "read error"
                   (:gate-case? v) "not run here"
                   (and (:status-match? v) (:claimable-match? v)
                        (:codes-subset? v)) "✓"
                   :else "✕")])
              corpus-verdicts)})

;; Count them plainly:

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [total (count corpus-verdicts)
       bundle-verdicts (remove :gate-case? corpus-verdicts)
       non-pass (count (filter #(not= "pass" (:status %)) bundle-verdicts))
       drift (count (remove #(and (not (:recompute-error? %))
                                  (:status-match? %)
                                  (:claimable-match? %)
                                  (:codes-subset? %))
                            bundle-verdicts))
       gate-cases (count (filter :gate-case? corpus-verdicts))]
   [:div {:style {:fontFamily "monospace" :fontSize "16px" :color "#e2e8f0"
                  :display "flex" :gap "24px" :padding "12px"}}
    [:div (str total " cases")]
    [:div (str non-pass " bundle non-pass outcomes")]
    [:div (str gate-cases " separately owned gate cases")]
    [:div {:style {:color (if (zero? drift) "#22c55e" "#ef4444") :fontWeight 700}}
     (str drift " bundle-verifier expectation drift")]]))

;; ---
;; ## The promise, stated plainly
;;
;; The clean-room implementer is handed the corpus and told the expected bundle
;; verdicts. This notebook checks that the repository verifier agrees on
;; identical bytes. A committed bundle expectation that stops recomputing is a
;; verification failure, not a changed opinion.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn committed-not-admitted-holds?
  "Every bundle-kind corpus case must recompute without error, match its
   committed pass/non-pass and claimable expectations, and cover expected issue
   codes. Gate cases are excluded because their dedicated verifiers do not run
   in this notebook."
  [verdicts]
  (every? (fn [v]
            (or (:gate-case? v)
                (and (not (:recompute-error? v))
                     (:status-match? v)
                     (:claimable-match? v)
                     (:codes-subset? v))))
          verdicts))

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(def committed-holds
  (committed-not-admitted-holds? corpus-verdicts))

;; **The contract:**

;; > Every bundle case must recompute to its committed expected verdict.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background (if committed-holds "#052e16" "#450a0a")
                :border (str "1px solid " (if committed-holds "#22c55e" "#ef4444"))
                :borderRadius "8px" :padding "14px 18px"
                :fontFamily "monospace" :fontSize "14px" :maxWidth "760px"}}
  [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"
                 :letterSpacing "0.05em" :fontWeight 700 :marginBottom "4px"}}
   "Not-admitted invariant"]
  "bundle expectations recompute without error, with committed status, claimability, and codes — "
  [:strong {:style {:color (if committed-holds "#4ade80" "#f87171")}}
   (if committed-holds "HOLDS ✓" "VIOLATED ✕")]])

;; ---
;; ## One worked example
;;
;; Take `claim-tampered-001`. The corpus supplies a claim that no longer matches
;; the derived claim. The repository verifier agrees: the case is non-pass with
;; `derived-claim-mismatch`. That one row is the whole story in miniature — a
;; tampered artifact, the same check, and a matching committed expectation.

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
;; envelopes. Their dedicated schema and identity gates own their committed
;; issue codes (`missing-invariant-profile`, `inconsistent-canonical-root`).
;; This notebook deliberately does not run or count them as bundle-verifier
;; evidence.

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
;; - Expected: 4 bundle non-pass and 1 bundle pass verdict recompute with their
;;   committed claimability and codes. Two separate gate cases are not verified
;;   by this notebook; their schema/identity gates own those verdicts.
