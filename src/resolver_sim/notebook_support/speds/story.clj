(ns resolver-sim.notebook-support.speds.story
  "SPEDS Phase 3: Narrative Story Engines.
   Templates for automated 4-frame validation stories."
  (:require [resolver-sim.logging :as log]
            [resolver-sim.notebook-support.speds.core :as speds]
            [resolver-sim.notebook-support.speds.config :as config]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.findings :as findings]
            [resolver-sim.notebook-support.speds.tokens :as tokens]
            [resolver-sim.notebook-support.speds.story-data :as story-data]
            [resolver-sim.scenario.outcome-semantics :as ose]
            [clojure.string :as str]))

(declare generate-story-by-family)

;; ---
;; Shared Frame Partials
;; Reusable building blocks that eliminate duplication across story families.

(defn- frame-badge [label color-hex]
  [:div.status-badge
   {:style {:color color-hex :borderColor color-hex
            :background (str color-hex "1a")
            :padding "4px 12px" :border (str "1px solid " color-hex)
            :fontFamily (:font/mono tokens/typography)
            :fontSize "12px" :fontWeight 800 :marginBottom "20px"}}
   label])

(defn- verified-closing-frame
  "Standard VERIFIED closing frame shared by all story families.
   h2-content is the per-family heading (Hiccup children, e.g. 'RESEARCH EVIDENCE BUNDLE').
   Pass :claim-id to include a replay-alignment claim, :cta for an optional CTA button."
  [{:keys [replay-match-label hash]} h2-content & {:keys [claim-id cta]}]
  {:header "STATUS: VERIFIED"
   :footer-left (str "REPLAY: " replay-match-label)
   :footer-right "CERT: BUNDLE_v1.1"
   :claims (if claim-id
             [{:claim-id claim-id :value replay-match-label
               :source-artifact "summary" :source-path [:summary :replay_match_pct]}]
             [])
   :content
   (cond-> [[:div {:style {:marginBottom "20px"}}
             (speds/v-inv :finality :ok)]
            [:div {:style {:fontFamily "JetBrains Mono" :fontSize "12px" :opacity 0.8 :wordBreak "break-all" :marginBottom "20px"}}
             hash]
            [:h2 {:style {:fontSize "30px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}}
             h2-content]]
     cta (conj [:div {:style {:marginTop "24px" :padding "12px" :background "#03DAC6" :color "#020617" :textAlign "center" :fontWeight 900 :fontSize "14px"}}
                cta]))})

(defn- inv-status
  "Returns the invariant status from context data, defaulting to :ok."
  [ctx simple-id]
  (let [results (or (:invariant-results ctx) {})
        full-key (keyword "invariant" (name simple-id))]
    (or (get results full-key)
        (get results simple-id)
        :ok)))

;; ---
;; Internal Narrative Helpers

(defn- render-story-frame [frame-idx total-frames header footer-left footer-right & content]
  (apply speds/v-frame
         {:header (str "[" (:protocol-label @config/profile) "] FRAME: " frame-idx "/" total-frames " | " header)
          :footer-left footer-left
          :footer-right footer-right}
         content))

(defn- render-frame-specs
  "Renders a vector of frame specs with keys:
   :header :footer-left :footer-right :content
   options: passed through to render-carousel
   (:layout :grid|:single|:row, :columns n, :gap size)"
  ([frame-specs] (render-frame-specs frame-specs {}))
  ([frame-specs options]
   (speds/render-carousel
    (fn [idx total-frames {:keys [header footer-left footer-right content]}]
      (apply render-story-frame idx total-frames header footer-left footer-right content))
    frame-specs
    options)))

(defn- story-family
  [scenario-id scenario]
  (let [id-lc (str/lower-case (or scenario-id ""))
        purpose (ose/normalize-purpose (:purpose scenario))
        tag-text (->> (or (:threat-tags scenario) []) (map name) (str/join " ") str/lower-case)
        {:keys [families default]} (:story-family-rules @config/profile)
        matches? (fn [{:keys [purposes id-substrings tag-substrings]}]
                   (or (and (seq purposes) (contains? purposes purpose))
                       (some #(str/includes? id-lc %) (or id-substrings []))
                       (some #(str/includes? tag-text %) (or tag-substrings []))))
        matched (first (filter matches? families))
        result (or (:family matched) default :deflection)]
    (when-not matched
      (log/warn! "No story-family rules matched; using fallback"
                 {:scenario-id scenario-id :purpose purpose
                  :threat-tags (:threat-tags scenario) :fallback result}))
    result))

;; ──────────────────────────────────────────────────────────────────────────
;; Story-family frame specs.
;;
;; NOTE ON NARRATIVE COPY (read before editing):
;; The headline strings, badges and footers below (e.g. "THREAT_DETECTED",
;; "ATTACK DEFLECTED", "LATENCY: 0.1ms", "ACCURACY: 1e-18", "MARGIN: 100%",
;; "CERT: BUNDLE_v1.1") are ILLUSTRATIVE EXAMPLE COPY, not values measured
;; from the run. They exist so a rendered story reads clearly; they are NOT
;; derived from trace/coverage data and must NOT be treated as evidence.
;;
;; The frame-spec functions receive only a small `ctx` (see story-data/
;; build-story-data). Deriving these claims from real data would require
;; threading raw artifacts/traces into each family — a deliberate refactor
;; that has NOT been done. Do not "fix" a typo here and assume it reflects
;; the system. If a frame must assert a measured fact, add the value to the
;; `:claims` vector (which the closing frame / findings layer DO derive from
;; data) rather than to the decorative copy strings.

(defn- deflection-frame-specs
  [{:keys [trace-id git-sha hash title replay-match-label] :as ctx}]
  [{:header "STATUS: ALERT"
    :footer-left trace-id
    :footer-right (str "GIT:" git-sha)
    :claims [{:claim-id :threat-detected :value "THREAT_DETECTED" :source-artifact "coverage" :source-path [:coverage :scenarios]}]
    :content
    [(frame-badge "THREAT_DETECTED" (get tokens/palette :sys/alert))
     [:h1 {:style {:fontSize "42px" :fontWeight 900 :lineHeight 0.9 :color "#fff" :textShadow speds/hero-shadow}}       (str/upper-case (str title))]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}}
      "Adversarial sweep identified a critical state-space vulnerability."]]}
   {:header "STATUS: INJECTING"
    :footer-left "VECTOR: MALICIOUS"
    :footer-right "ENGINE: gRPC"
    :claims [{:claim-id :adversarial-flow :value "MALICIOUS_SEQ" :source-artifact "scenario" :source-path [:coverage :scenarios]}]
    :content
    [[:div {:style {:marginBottom "40px"}}
      [:div {:style {:fontSize "12px" :fontWeight 800 :marginBottom "12px" :color "#FF9800"}} "ADVERSARIAL INJECTION"]
      (speds/v-flo :adversarial)]
     [:h2 {:style {:fontSize "32px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}} "UNAUTHORIZED" [:br] "EXTRACTION" [:br] "ATTEMPTED"]
     [:div {:style {:marginTop "32px" :display "flex" :gap "10px"}}
      (speds/v-act "atk" :adversarial)
      [:div {:style {:flex 1 :padding "10px" :border "1px solid #004D59" :fontSize "10px"}}
       "MALICIOUS_SEQ: detected" [:br] "BLOCK_LOCK: bypass_attempt"]]]}
   {:header "STATUS: INTERCEPTED"
    :footer-left "LATENCY: 0.1ms"
    :footer-right "GUARD: ACTIVE"
    :claims [{:claim-id :intercept-guard :value "ACTIVE" :source-artifact "story-model" :source-path [:core :v-res]}]
    :content
    [(speds/v-res "Protocol Guard")
     [:div {:style {:marginTop "20px"}}
      (speds/v-inv :solvency (inv-status ctx :solvency))]
     [:h2 {:style {:fontSize "52px" :fontWeight 900 :lineHeight 0.9 :color "#03DAC6" :marginTop "20px" :textShadow speds/teal-shadow}} "ATTACK" [:br] "DEFLECTED"]]}
   (verified-closing-frame {:replay-match-label replay-match-label :hash hash}
                           ["SIGNED" [:br] "DETERMINISTIC" [:br] "EVIDENCE"]
                           :claim-id :replay-alignment
                           :cta "VERIFY DETERMINISTIC REPLAY")])

(defn- deadline-frame-specs
  [{:keys [trace-id git-sha hash title replay-match-label] :as ctx}]
  [{:header "STATUS: DEADLINE_WINDOW"
    :footer-left trace-id
    :footer-right (str "GIT:" git-sha)
    :claims [{:claim-id :deadline-scenario :value title :source-artifact "coverage" :source-path [:coverage :scenarios]}]
    :content
    [(frame-badge "BOUNDARY_TEST" (get tokens/palette :sys/alert))
     [:h1 {:style {:fontSize "42px" :fontWeight 900 :lineHeight 0.9 :color "#fff" :textShadow speds/hero-shadow}}       (str/upper-case (str title))]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}}
      "Temporal boundary checks verify post-deadline actions are deterministically rejected."]]}
   {:header "STATUS: WINDOW_OPEN"
    :footer-left "RULE: T <= DEADLINE"
    :footer-right "ACTION: APPEAL"
    :claims [{:claim-id :window-open-rule :value "T <= deadline" :source-artifact "scenario" :source-path [:coverage :scenarios]}]
    :content
    [[:div {:style {:marginBottom "20px"}} (speds/v-inv :deadline (inv-status ctx :deadline))]
     [:h2 {:style {:fontSize "30px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}} "WITHIN WINDOW" [:br] "ACTION ACCEPTED"]]}
   {:header "STATUS: WINDOW_CLOSED"
    :footer-left "RULE: T > DEADLINE"
    :footer-right "ACTION: APPEAL"
    :claims [{:claim-id :window-closed-reject :value "ERR_WINDOW_CLOSED" :source-artifact "scenario" :source-path [:coverage :scenarios]}]
    :content
    [[:div {:style {:marginBottom "20px"}} (speds/v-inv :deadline (inv-status ctx :deadline))]
     [:h2 {:style {:fontSize "40px" :fontWeight 900 :lineHeight 0.9 :color "#FF9800"}} "LATE APPEAL" [:br] "REJECTED"]]}
   (verified-closing-frame {:replay-match-label replay-match-label :hash hash}
                           ["DETERMINISTIC" [:br] "DEADLINE" [:br] "ENFORCEMENT"]
                           :claim-id :deadline-replay-alignment)])

(defn- falsification-frame-specs
  [{:keys [trace-id git-sha hash title replay-match-label] :as ctx}]
  [{:header "STATUS: HYPOTHESIS"
    :footer-left trace-id
    :footer-right (str "GIT:" git-sha)
    :claims [{:claim-id :hypothesis :value title :source-artifact "coverage" :source-path [:coverage :scenarios]}]
    :content
    [(frame-badge "THEORY_FALSIFICATION" (get tokens/palette :sys/alert))
     [:h1 {:style {:fontSize "40px" :fontWeight 900 :lineHeight 0.9 :color "#fff" :textShadow speds/hero-shadow}} "HYPOTHESIS" [:br] "UNDER TEST"]
     [:p {:style {:fontSize "15px" :marginTop "20px" :color "#7ADDDC" :fontWeight 700}}
      "Assumption is stress-tested under adversarial conditions to detect model boundaries."]]}
   {:header "STATUS: EXPERIMENT"
    :footer-left "METHOD: ADVERSARIAL_TRACE"
    :footer-right "MODE: REPLAY"
    :claims [{:claim-id :experiment-method :value "adversarial-trace" :source-artifact "scenario" :source-path [:coverage :scenarios]}]
    :content
    [[:div {:style {:marginBottom "24px"}} (speds/v-flo :adversarial)]
     [:h2 {:style {:fontSize "32px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}} "COUNTEREXAMPLE" [:br] "SEARCH"]
     [:div {:style {:marginTop "20px"}} (speds/v-act "exp" :adversarial)]]}
   {:header "STATUS: OBSERVED"
    :footer-left "RESULT: EXPECTED_NEGATIVE"
    :footer-right "CLASS: RESEARCH"
    :claims [{:claim-id :observed-outcome :value "expected-negative" :source-artifact "report" :source-path [:report :status-kind]}]
    :content
    [[:div {:style {:marginBottom "20px"}} (speds/v-inv :boundary (inv-status ctx :boundary))]
     [:h2 {:style {:fontSize "42px" :fontWeight 900 :lineHeight 0.9 :color "#FF9800"}} "MODEL LIMIT" [:br] "LOCATED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#FF9800" :fontWeight 700}}
      "Negative result is treated as falsification evidence, not protocol marketing output."]]}
   (verified-closing-frame {:replay-match-label replay-match-label :hash hash}
                           ["RESEARCH" [:br] "EVIDENCE" [:br] "BUNDLE"]
                           :claim-id :falsification-replay)])

(defn- collusion-frame-specs
  [{:keys [trace-id git-sha hash title replay-match-label] :as ctx}]
  [{:header "STATUS: ALERT"
    :footer-left trace-id
    :footer-right (str "GIT:" git-sha)
    :content
    [(frame-badge "COALITION_FORMATION" (get tokens/palette :sys/alert))
     [:h1 {:style {:fontSize "42px" :fontWeight 900 :lineHeight 0.9 :color "#fff" :textShadow speds/hero-shadow}}       (str/upper-case (str title))]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}}
      "Adversarial actors form a coalition to siphon funds via fraudulent verdicts."]]}
   {:header "STATUS: FRAUD_EMITTED"
    :footer-left "VECTOR: COLLUSIVE"
    :footer-right "WINDOW: CHALLENGE_OPEN"
    :content
    [[:div {:style {:marginBottom "40px" :display "flex" :justifyContent "center"}}
      [:div {:style {:width "100px" :height "100px" :border "2px dashed #FF9800" :borderRadius "50%" :display "flex" :alignItems "center" :justifyContent "center"}}
       [:div {:style {:fontSize "20px" :color "#FF9800"}} "↺"]]]
     [:h2 {:style {:fontSize "32px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}} "FRAUDULENT" [:br] "VERDICT" [:br] "EMITTED"]
     [:div {:style {:marginTop "24px" :fontFamily "JetBrains Mono" :fontSize "10px" :color "#FF9800"}}
      "STATUS: PENDING_EXECUTION"]]}
   {:header "STATUS: INTERCEPTED"
    :footer-left "GUARD: ACTIVE"
    :footer-right "SLASHER: TRIGGERED"
    :content
    [(speds/v-res "Collusion Guard")
     [:div {:style {:marginTop "20px"}} (speds/v-inv :solvency (inv-status ctx :solvency))]
     [:h2 {:style {:fontSize "50px" :fontWeight 900 :lineHeight 0.9 :color "#03DAC6" :marginTop "20px" :textShadow speds/teal-shadow}} "BRIBERY" [:br] "DEFLECTED"]]}
   (verified-closing-frame {:replay-match-label replay-match-label :hash hash}
                           ["EQUILIBRIUM" [:br] "RESTORED"])])

(defn- economic-solvency-frame-specs
  [{:keys [trace-id git-sha hash title replay-match-label] :as ctx}]
  [{:header "STATUS: MONITORING"
    :footer-left trace-id
    :footer-right (str "GIT:" git-sha)
    :content
    [(frame-badge "ECONOMIC_ROBUSTNESS" (get tokens/palette :sys/primary))
     [:h1 {:style {:fontSize "42px" :fontWeight 900 :lineHeight 0.9 :color "#fff" :textShadow speds/hero-shadow}}       (str/upper-case (str title))]
     [:p {:style {:fontSize "16px" :marginTop "24px" :color "#7ADDDC" :fontWeight 700}}
      "Yield conservation and solvency invariants are stress-tested under volatility."]]}
   {:header "STATUS: STRESS_TEST"
    :footer-left "FLOW: CONSERVATION"
    :footer-right "ACCURACY: 1e-18"
    :content
    [[:div {:style {:marginBottom "40px"}}
      (speds/v-flo :yield)]
     [:h2 {:style {:fontSize "32px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}} "YIELD" [:br] "ACCUMULATION" [:br] "VERIFIED"]
     [:div {:style {:marginTop "24px" :height "8px" :background "#004D59" :borderRadius "4px" :overflow "hidden"}}
      [:div {:style {:width "100%" :height "100%" :background "#03DAC6"}}]]]}
   {:header "STATUS: INVARIANT_HOLD"
    :footer-left "CORE: SOLVENCY"
    :footer-right "MARGIN: 100%"
    :content
    [[:div {:style {:display "flex" :gap "20px" :marginBottom "20px"}}
      (speds/v-inv :solvency (inv-status ctx :solvency))
      (speds/v-inv :conservation (inv-status ctx :conservation))]
     [:h2 {:style {:fontSize "40px" :fontWeight 900 :lineHeight 0.9 :color "#fff"}} "SOLVENCY" [:br] "GUARANTEED"]]}
   (verified-closing-frame {:replay-match-label replay-match-label :hash hash}
                           ["DETERMINISTIC" [:br] "SOLVENCY" [:br] "BUNDLE"])])

;; ---
;; 1. The "Deflection" Story Engine
;; Optimized for proving resistance to specific attacks (S26, S42, etc.)

(defn generate-theory-falsification-story
  "Renders a theory-falsification multi-frame story from a scenario id,
   defaulting to the first theory-falsification scenario in coverage."
  ([artifacts]
   (let [scn (some #(when (= :theory-falsification (ose/normalize-purpose (:purpose %))) %) (or (get-in artifacts [:coverage :scenarios]) []))
         scenario-id (or (:id scn) (:default-theory-falsification-scenario-id @config/profile))]
     (generate-story-by-family scenario-id artifacts)))
  ([scenario-id artifacts]
   (let [sid (or scenario-id (:default-theory-falsification-scenario-id @config/profile))]
     (generate-story-by-family sid artifacts))))

(defn generate-run-overview
  "Overview panel designed for reviewer/public status context."
  [artifacts]
  (let [{:keys [summary coverage]} artifacts
        {:keys [replay-match-label scenario-count determinism-text coverage-text]} (data/narrative-metrics artifacts)]
    [:div {:style {:padding "24px" :border "1px solid #004D59" :background "#020617"}}
     [:h2 {:style {:marginTop 0 :color "#fff"}} "Validation Run Overview"]
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, 1fr)" :gap "12px" :marginBottom "16px"}}
      [:div [:div {:style {:fontSize "10px" :color "#004D59"}} "OVERALL"] [:div {:style {:color "#7ADDDC"}} (str/upper-case (str (or (:overall_status summary) "unknown")))]]
      [:div [:div {:style {:fontSize "10px" :color "#004D59"}} "SCENARIOS"] [:div {:style {:color "#7ADDDC"}} scenario-count]]
      [:div [:div {:style {:fontSize "10px" :color "#004D59"}} "REPLAY"] [:div {:style {:color "#7ADDDC"}} replay-match-label]]
      [:div [:div {:style {:fontSize "10px" :color "#004D59"}} "RUN"] [:div {:style {:color "#7ADDDC"}} (or (:run_id summary) (:run-id config/protocol-defaults))]]]
     [:p {:style {:fontSize "12px" :color "#cbd5e1"}} determinism-text]
     [:p {:style {:fontSize "12px" :color "#cbd5e1"}} coverage-text]
     [:p {:style {:fontSize "11px" :color "#94a3b8" :marginTop "12px"}}
      "This summarizes run status; it does not by itself prove system safety."]]))

(defn- family->scenario-id
  [issues family fallback]
  (or (:scenario/id (first (filter #(= family (:story/family %)) issues)))
      (:scenario/id (first issues))
      fallback))

(defn generate-issue-gallery
  "Primary issue-driven story mode.
   opts: {:issues-path string :limit n}"
  ([artifacts] (generate-issue-gallery artifacts {}))
  ([artifacts {:keys [issues-path limit] :or {limit 3}}]
   (let [findings-bundle (or (findings/load-findings)
                             (findings/generate-findings-bundle artifacts))
         finding-list (take limit (sort-by (juxt (comp - :priority :story) :scenario_id)
                                           (or (:findings findings-bundle) [])))]
     [:div {:style {:display "grid" :gap "30px"}}
      (for [f finding-list]
        (let [spec (or (:story_artifact_spec f) {})
              outcome (:outcome f)
              class-label (or (some-> outcome :outcome :class name)
                              (get-in spec [:classification :label])
                              (some-> (:classification f) :label)
                              "unclassified")
              class-status (or (some-> outcome :outcome :status name)
                               (get-in spec [:classification :status])
                               (some-> (:classification f) :status)
                               "unknown")
              baseline-note (or (get-in outcome [:outcome :comparison :narrative])
                                (get-in spec [:baseline_comparison :delta_summary :narrative]))]
          [:div {:style {:border "1px solid #004D59" :padding "16px" :background "#020617"}}
           [:div {:style {:fontSize "10px" :color "#FF9800"}} (str "FINDING " (:finding_id f) " · " (str/upper-case (str (:severity f))))]
           [:div {:style {:fontSize "10px" :color "#7ADDDC" :marginTop "4px"}}
            (str "CLASS: " (str/upper-case (str class-label)) " / " (str/upper-case (str class-status)))]
           [:h3 {:style {:color "#fff" :margin "8px 0"}} (:title f)]
           [:p {:style {:fontSize "12px" :color "#cbd5e1"}} (:summary f)]
           (when (seq baseline-note)
             [:p {:style {:fontSize "11px" :color "#94a3b8" :marginBottom "10px"}}
              baseline-note])
           (generate-story-by-family (or (:scenario_id f) (:default-theory-falsification-scenario-id @config/profile)) artifacts f)]))])))

(defn generate-scenario-deep-dive
  "Deep-dive mode, typically selected from issue list."
  [scenario-id artifacts]
  (let [sid (or scenario-id (:default-theory-falsification-scenario-id @config/profile))]
    (generate-story-by-family sid artifacts)))

(defn generate-story
  "Unified renderer entrypoint.
   {:mode :run-overview|:issue-gallery|:scenario-deep-dive :input {...}}"
  [artifacts {:keys [mode input] :or {mode :issue-gallery input {}}}]
  (case mode
    :run-overview (generate-run-overview artifacts)
    :scenario-deep-dive (generate-scenario-deep-dive (or (:scenario-id input) (:default-theory-falsification-scenario-id @config/profile)) artifacts)
    :issue-gallery (generate-issue-gallery artifacts input)
    (throw (ex-info (str "Unknown story mode: " (pr-str mode) ". Supported modes: :run-overview, :issue-gallery, :scenario-deep-dive")
                    {:mode mode :supported-modes [:run-overview :issue-gallery :scenario-deep-dive]}))))

;; ---
;; 2. The "Atlas" View Engine
;; Generalized high-density overview

(defn generate-atlas-view
  "Generates a generalized Protocol Atlas from simulation coverage artifacts."
  [artifacts]
  (let [{:keys [coverage summary]} artifacts
        scenarios (sort-by :id (:scenarios coverage))
        threat-tags (sort-by val > (:threat-tag-freq coverage))
        {:keys [determinism-text coverage-text]} (data/narrative-metrics artifacts)]
    [:div {:style {:padding "40px"
                   :background (get tokens/palette :bg/canvas)
                   :border "1px solid #004D59"}}
     [:div {:style {:display "flex"
                    :justifyContent "space-between"
                    :alignItems "center"
                    :marginBottom "40px"}}
      [:h1 {:style {:fontSize "3rem" :fontWeight 900 :margin 0 :color "#fff"}} "PROTOCOL ATLAS"]
      [:div.stat-item
       [:div {:style {:fontSize "2rem" :fontWeight 800 :fontFamily "JetBrains Mono"}} (count scenarios)]
       [:div {:style {:fontSize "10px" :color "#004D59"}} "SCENARIOS"]]]

     [:div {:style {:display "grid" :gridTemplateColumns "repeat(20, 1fr)" :gap "8px" :marginBottom "40px"}}
      (for [s scenarios]
        (let [adv? (some #(str/includes? (str/lower-case (name %)) "adversarial")
                         (or (:threat-tags s) []))]
          [:div {:style {:aspectRatio "1"
                         :background (if adv? "#FF9800" "#7ADDDC")
                         :borderRadius "1px"
                         :boxShadow (str "0 0 5px " (if adv? "#FF9800" "#7ADDDC") "44")}}]))]

     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "40px"}}
      [:div
       [:h3 {:style {:fontSize "12px" :color "#004D59"}} "TOP THREAT CATEGORIES"]
       [:div {:style {:display "flex" :flexDirection "column" :gap "10px"}}
        (for [[tag freq] (take 10 threat-tags)]
          [:div {:style {:display "flex" :alignItems "center" :gap "10px"}}
           [:div {:style {:width "150px" :fontSize "10px" :fontFamily "JetBrains Mono"}} (name tag)]
           [:div {:style {:flex 1 :height "8px" :background "#004D59"}}
            [:div {:style {:width (str (min 100 (* freq config/threat-tag-bar-scale)) "%")
                           :height "100%"
                           :background "#7ADDDC"}}]]])]]

      [:div {:style {:padding "20px" :background "rgba(0, 77, 89, 0.1)" :border "1px dashed #004D59"}}
       [:h4 {:style {:margin "0 0 10px 0"}} "Scientific Summary"]
       [:p {:style {:fontSize "12px" :opacity 0.8 :lineHeight 1.6}} determinism-text]
       [:p {:style {:fontSize "12px" :opacity 0.8 :lineHeight 1.6 :marginTop "8px"}} coverage-text]]]]))

(defmulti frame-specs-for-family
  "Returns a vector of frame spec maps for the given story family.
   New families can be added from any namespace via defmethod."
  (fn [family _frame-ctx] family))

(defmethod frame-specs-for-family :deflection [_ frame-ctx]
  (deflection-frame-specs frame-ctx))

(defmethod frame-specs-for-family :deadline-boundary [_ frame-ctx]
  (deadline-frame-specs frame-ctx))

(defmethod frame-specs-for-family :theory-falsification [_ frame-ctx]
  (falsification-frame-specs frame-ctx))

(defmethod frame-specs-for-family :collusion [_ frame-ctx]
  (collusion-frame-specs frame-ctx))

(defmethod frame-specs-for-family :economic-solvency [_ frame-ctx]
  (economic-solvency-frame-specs frame-ctx))

(defmethod frame-specs-for-family :default [_ frame-ctx]
  (deflection-frame-specs frame-ctx))

(defn generate-story-by-family
  "Generates a multi-frame narrative for a scenario, dispatching on
   the story family determined from scenario metadata.
   This is the canonical entry point for SPEDS story generation."
  ([scenario-id artifacts] (generate-story-by-family scenario-id artifacts nil))
  ([scenario-id artifacts finding]
   (let [ctx (story-data/build-story-data artifacts scenario-id finding)
         family (story-family scenario-id (:scenario ctx))
         frame-specs (frame-specs-for-family family ctx)]
     (render-frame-specs frame-specs))))

(defn generate-deflection-story
  "Deprecated alias for generate-story-by-family. Retained for
   backward compatibility with existing notebooks."
  [scenario-id artifacts]
  (generate-story-by-family scenario-id artifacts))
