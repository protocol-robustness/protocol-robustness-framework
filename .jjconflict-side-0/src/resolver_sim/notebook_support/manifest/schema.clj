(ns resolver-sim.notebook-support.manifest.schema
  "Malli schemas for protocol-provenance manifest objects.

  Covers:
    - run-summary   : lightweight index entry derived from test-run.json
    - manifest      : full test-run.v1 shape
    - claim         : falsifiable claim registry entry
    - check-result  : individual reproducibility panel check"
  (:require [malli.core :as m]))

;; ── run-summary ───────────────────────────────────────────────────────────────

(def RunSummary
  [:map
   [:run-id      :string]
   [:slug        :string]
   [:status      [:enum "pass" "fail" "unknown"]]
   [:suite-id    :string]
   [:scenario    {:optional true} :string]
   [:duration-ms {:optional true} [:maybe :int]]
   [:created-at  :string]
   [:git-commit  {:optional true} [:maybe :string]]
   [:dir         :string]])

;; ── manifest (test-run.v1) ────────────────────────────────────────────────────

(def ArtifactsMap
  [:map-of :keyword :string])

(def SuiteBlock
  [:map
   [:id      :string]
   [:version :string]
   [:scenario    {:optional true} [:maybe :string]]
   [:selector    {:optional true} [:maybe :string]]
   [:scenario_count {:optional true} [:maybe :int]]])

(def Manifest
  [:map
   [:schema_version   :string]
   [:contract_version :string]
   [:run_id           :string]
   [:created_at       :string]
   [:triggered_by     {:optional true} [:maybe :string]]
   [:duration_ms      {:optional true} [:maybe :int]]
   [:suite            SuiteBlock]
   [:artifacts        ArtifactsMap]
   [:framework
    [:map
     [:name       :string]
     [:version    :string]
     [:git_commit {:optional true} [:maybe :string]]]]
   [:model
    [:map
     [:id         :string]
     [:version    :string]
     [:git_commit {:optional true} [:maybe :string]]]]])

;; ── claim ─────────────────────────────────────────────────────────────────────

(def Claim
  [:map
   [:claim/id          :keyword]
   [:description       :string]
   [:assumptions       [:vector :string]]
   [:validated-by      [:vector :string]]
   [:falsified-if      :string]
   [:confidence        [:enum :high :medium :bounded :low]]
   [:status            [:enum :not-falsified :falsified :inconclusive :not-evaluated]]
   [:last-validated    {:optional true} [:maybe :string]]
   [:counterexamples   {:optional true} [:maybe [:vector :map]]]])

;; ── reproducibility check ─────────────────────────────────────────────────────

(def CheckResult
  [:map
   [:label   :string]
   [:status  [:enum :pass :fail :warn :unknown]]
   [:note    {:optional true} [:maybe :string]]])

;; ── status indicator ──────────────────────────────────────────────────────────

(def StatusIndicator
  [:enum :verified :stale :divergent :unsigned :unknown])

;; ── validation helpers ────────────────────────────────────────────────────────

(defn valid-manifest? [m]
  (m/validate Manifest m))

(defn valid-claim? [c]
  (m/validate Claim c))

(defn explain-manifest [m]
  (m/explain Manifest m))
