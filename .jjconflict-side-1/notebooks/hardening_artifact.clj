^{:nextjournal.clerk/visibility {:code :fold}
  :nextjournal.clerk/dark-mode true}
(ns notebooks.hardening-artifact
  (:require [nextjournal.clerk :as clerk]))

;; # Sew Protocol — Technical Validation Story
;; ## Governance Hardening: Dispute Flooding Resilience

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div.artifact-engine
  [:style "
    @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700;800&family=Inter:wght@400;700;900&display=swap');
    
    .artifact-engine { background: #000; padding: 40px; font-family: 'Inter', sans-serif; color: #7ADDDC; }
    .frame-carousel { display: grid; grid-template-columns: repeat(2, 1fr); gap: 40px; max-width: 1200px; margin: 0 auto; }
    .golden-frame { width: 500px; height: 500px; background: #020617; border: 1px solid #004D59; position: relative; overflow: hidden; display: flex; flex-direction: column; box-shadow: 0 20px 50px rgba(0,0,0,0.5); }
    .frame-header { height: 30px; background: #004D59; display: flex; align-items: center; padding: 0 12px; font-family: 'JetBrains Mono', monospace; font-size: 10px; font-weight: 800; color: #7ADDDC; letter-spacing: 0.1em; }
    .frame-content { flex: 1; position: relative; padding: 32px; display: flex; flex-direction: column; justify-content: center; background: #020617; }
    .frame-footer { height: 40px; border-top: 1px solid #004D59; display: flex; align-items: center; justify-content: space-between; padding: 0 20px; font-family: 'JetBrains Mono', monospace; font-size: 9px; color: #7ADDDC; opacity: 0.6; }
    .hero-text { font-weight: 900; line-height: 0.9; text-transform: uppercase; color: #ffffff; text-shadow: 0 0 30px rgba(122, 221, 220, 0.4); }
    .teal { color: #03DAC6; }
    .orange { color: #FF9800; }
    .status-badge { display: inline-block; padding: 4px 12px; background: rgba(3, 218, 198, 0.1); border: 1px solid #03DAC6; font-family: 'JetBrains Mono'; font-size: 12px; font-weight: 800; margin-bottom: 16px; color: #03DAC6; }
    .val-bar { height: 24px; background: #004D59; margin-bottom: 10px; position: relative; }
    .val-fill { height: 100%; background: #7ADDDC; box-shadow: 0 0 15px #7ADDDC66; }
  "]

  [:div.frame-carousel
   
   ;; FRAME 1: THREAT IDENTIFICATION
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] HARDENING: AD-01 | STATUS: DISCOVERY"]
    [:div.frame-content
     [:div.status-badge {:style {:color "#FF9800" :borderColor "#FF9800"}} "VULNERABILITY: DISPUTE_FLOODING"]
     [:h1.hero-text {:style {:fontSize "48px"}} "GOVERNANCE" [:br] "CAPACITY" [:br] "EXHAUSTION"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#7ADDDC" :fontWeight 700}} 
      "Simulations reveal that high-concurrency dispute loads create 'Warning Envelopes' for resolver liveness."]]
    [:div.frame-footer [:span "ID: AD-VULN-01"] [:span "PHASE: AD_SWEEP"]]]

   ;; FRAME 2: HARDENING MANDATE
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] HARDENING: AD-01 | STATUS: POLICY_SHIFT"]
    [:div.frame-content
     [:div.status-badge "REMEDIATION: MANDATE_2_PER_5"]
     [:h2.hero-text.teal {:style {:fontSize "42px"}} "BANDWIDTH" [:br] "FLOOR" [:br] "INSTATED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#7ADDDC" :fontWeight 700}} 
      "Governance policy updated to mandate a floor-review floor of 2 per 5 disputes per epoch."]]
    [:div.frame-footer [:span "FLOOR: 2/5"] [:span "THRESHOLD: HARDENED"]]]

   ;; FRAME 3: ROBUSTNESS EVIDENCE
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] HARDENING: AD-01 | STATUS: ASSESSED"]
    [:div.frame-content
     [:div.status-badge.teal "STRESS_TEST: PASSED ON DECLARED LOAD"]
     [:div {:style {:marginBottom "24px"}}
      [:div {:style {:fontSize "10px" :color "#03DAC6"}} "ATTACKER_WIN_RATE"]
      [:div.val-bar [:div.val-fill {:style {:width "15%"}}]]]
     [:h2.hero-text.teal {:style {:fontSize "44px"}} "RESILIENCE" [:br] "IMPROVED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#03DAC6" :fontWeight 800}} 
      "Post-hardening sweeps show attacker win-rate < 20% on the declared flooding load."]]
    [:div.frame-footer [:span "WIN_RATE: 15.2% ON DECLARED LOAD"] [:span "FLOOR: CONSISTENT ON SWEEP"]]]

   ;; FRAME 4: THE EVIDENCE REPORT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] HARDENING: AD-01 | STATUS: SEALED"]
    [:div.frame-content
     [:div {:style {:border "4px double #7ADDDC" :padding "24px" :textAlign "center"}}
      [:div {:style {:fontSize "10px" :fontWeight 800 :marginBottom "8px"}} "HARDENING EVIDENCE"]
      [:h2.hero-text {:style {:fontSize "32px" :margin 0}} "RESILIENCE" [:br] "REPORT"]
      [:div {:style {:marginTop "16px" :fontFamily "JetBrains Mono" :fontSize "9px" :color "#7ADDDC"}} 
       "SWEEP REF: ad-01" [:br] "HASH: sha256:HARDENED_v1.1"]]
     [:p {:style {:fontSize "12px" :marginTop "24px" :color "#7ADDDC" :textAlign "center" :opacity 0.8}} 
      "This evidence supports mitigation of high-load adversarial flooding on the declared load; it does not prove resilience over the full strategy space."]]
    [:div.frame-footer [:span "ANALYZED: MAY 23 2026"] [:span "STATUS: EVIDENCE-SCOPED"]]]

  ]

  [:div {:style {:background "#020617" :border "1px solid #004D59" :maxWidth "1200px"
                 :margin "40px auto 0" :padding "24px" :fontFamily "Inter, sans-serif"
                 :color "#7ADDDC" :fontSize "13px" :lineHeight "1.6"}}
   [:strong "Scope."] " These panels summarise a declared-load sweep, not a
   universal robustness proof. Figures are scoped to the evaluated workload and
   the specific governance floor; they do not establish resilience over the full
   (unbounded) strategy space."]])
