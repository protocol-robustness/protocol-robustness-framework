(ns resolver-sim.notebook-support.speds.config
  "SPEDS Configuration: Centralized paths and protocol identifiers."
  (:require [clojure.string :as str]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.config.defaults :as defaults]))

(def threat-tag-bar-scale (defaults/default [:speds :threat-tag-bar-scale] 10))

(def success-patterns
  [#"100\.0%"
   #"REPLAY:\s*1\.00\s*MATCH"
   #"Determinism verified at 100%"])

(def artifact-paths
  {:test-summary (evcfg/artifact-path :test-summary)
   :test-run     (evcfg/artifact-path :test-run)
   :coverage     (evcfg/artifact-path :coverage)
   :equivalence  (evcfg/artifact-path :equivalence-summary)
   :findings     (evcfg/artifact-path :findings)
   :issues       (evcfg/artifact-path :issues)
   :manifest     (str (evcfg/artifact-dir) "/evidence-manifest.json")
   :traces-dir   (paths/traces-dir)
   :golden-dir   (paths/golden-dir)})

(def protocol-defaults
  {:id          "dispute-resolution-validation-v1"
   :version     "1.1"
   :run-id      "UNNAMED"
   ;; git-sha / hash-suffix intentionally nil: callers must render honest
   ;; "unset" markers instead of inventing a fake commit or evidence anchor.
   :git-sha     nil
   :hash-suffix nil})

(def sew-profile
  {:protocol-label "SEW_PROT"
   :suite-id "dispute-resolution-validation-v1"
   :finding-category "dispute_resolution"
   :bundle-cert-label "BUNDLE_v1.1"
   :default-theory-falsification-scenario-id (defaults/default [:scenarios :sew-default-theory-falsification] "scenarios/S26_forking-strategist-l1-reversal")
   :severity-rules
   {:invariant-severity-order [:high :medium]
    :tag-severity-map {"reentrancy" :high
                       "solvency" :high
                       "appeal-escalation" :medium
                       "timing-boundary" :medium}
    :default-severity :low}
   :story-family-rules
   {:default :deflection
    :families
    [{:family :theory-falsification
      :purposes #{:theory-falsification}}
     {:family :deadline-boundary
      :id-substrings ["appeal-deadline" "deadline"]
      :tag-substrings ["timing-boundary" "appeal-escalation"]}
     {:family :collusion
      :id-substrings ["collusion" "bribery"]
      :tag-substrings ["collusion"]}
     {:family :economic-solvency
      :id-substrings ["yield" "solvency"]
      :tag-substrings ["solvency" "conservation"]}
     {:family :deflection
      :purposes #{:adversarial-robustness}
      :tag-substrings ["fork" "reorg"]}]}})

(def sample-generic-profile
  "Example profile showing how SPEDS can be retargeted without code changes.
   Not active by default; intended as a copy/template for protocol-specific overrides."
  {:protocol-label "PROTOCOL_X"
   :suite-id "protocol-x-validation-v1"
   :finding-category "protocol_risk"
   :bundle-cert-label "BUNDLE_v1"
   :default-theory-falsification-scenario-id (defaults/default [:scenarios :generic-default-theory-falsification] "scenarios/X01_hypothesis-boundary-check")
   :severity-rules
   {:invariant-severity-order [:high :medium]
    :tag-severity-map {"reentrancy" :high
                       "solvency" :high
                       "liquidity" :medium
                       "timing" :medium
                       "window-boundary" :medium
                       "conservation" :medium}
    :default-severity :low}
   :story-family-rules
   {:default :deflection
    :families
    [{:family :theory-falsification
      :purposes #{:theory-falsification :hypothesis-test}}
     {:family :deadline-boundary
      :id-substrings ["deadline" "timeout" "expiry"]
      :tag-substrings ["timing" "window-boundary"]}
     {:family :collusion
      :id-substrings ["cartel" "coalition" "bribery"]
      :tag-substrings ["collusion" "coordination"]}
     {:family :economic-solvency
      :id-substrings ["liquidity" "solvency" "reserve"]
      :tag-substrings ["conservation" "liability-coverage"]}
     {:family :deflection
      :purposes #{:adversarial-robustness :security-stress}
      :tag-substrings ["reorg" "fork" "adversarial"]}]}})

(def profiles
  {:sew sew-profile
   :generic sample-generic-profile})

(defn selected-profile-key
  "Select active SPEDS profile via env var `SPEDS_PROFILE`.
   Supported values: sew, generic. Defaults to generic."
  []
  (let [raw (some-> (System/getenv "SPEDS_PROFILE") str/lower-case)]
    (case raw
      "generic" :generic
      "sew" :sew
      :generic)))

(defn active-profile []
  (get profiles (selected-profile-key) sample-generic-profile))

(def profile
  ;; Active profile map used by SPEDS modules.
  ;; Resolved lazily on first deref. Override via SPEDS_PROFILE=sew (or generic).
  (delay (active-profile)))
