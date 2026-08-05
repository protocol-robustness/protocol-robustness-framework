^{:nextjournal.clerk/dark-mode true}
(ns notebooks.equilibrium-artifact
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.common :as common]))

;; # Sew Protocol — Technical Validation Story
;; ## Equilibrium Failure Observatory: Multi-Epoch Reputation Dynamics

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div.artifact-engine
  [:style "
    @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;700;800&family=Inter:wght@400;700;900&display=swap');
    
    .artifact-engine { 
      background: #000; 
      padding: 40px; 
      font-family: 'Inter', sans-serif;
      color: #7ADDDC;
    }
    
    .frame-carousel {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 40px;
      max-width: 1200px;
      margin: 0 auto;
    }

    .golden-frame {
      width: 500px;
      height: 500px;
      background: #020617;
      border: 1px solid #004D59;
      position: relative;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      box-shadow: 0 20px 50px rgba(0,0,0,0.5);
    }

    .frame-header {
      height: 30px;
      background: #004D59;
      display: flex;
      align-items: center;
      padding: 0 12px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 10px;
      font-weight: 800;
      color: #7ADDDC;
      letter-spacing: 0.1em;
    }

    .frame-content {
      flex: 1;
      position: relative;
      padding: 32px;
      display: flex;
      flex-direction: column;
      justify-content: center;
      background: #020617;
      background-image: radial-gradient(rgba(0, 77, 89, 0.3) 1px, transparent 1px);
      background-size: 20px 20px;
    }

    .frame-footer {
      height: 40px;
      border-top: 1px solid #004D59;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0 20px;
      font-family: 'JetBrains Mono', monospace;
      font-size: 9px;
      color: #7ADDDC;
      opacity: 0.6;
    }

    .hero-text { font-weight: 900; line-height: 0.9; text-transform: uppercase; color: #fff; }
    .orange { color: #FF9800; }
    .teal { color: #03DAC6; }
    .stat-val { font-family: 'JetBrains Mono'; font-size: 1.5rem; font-weight: 800; }

    .line-chart-viz { height: 150px; background: #0f172a; border: 1px solid #004D59; margin: 20px 0; }
  "]

  [:div.frame-carousel
   
   ;; FRAME 1: ATTRITION THRESHOLD
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] REP_SIM: J-PHASE | STATUS: STOCHASTIC"]
    [:div.frame-content
     [:div.status-badge {:style {:color "#FF9800" :border "1px solid #FF9800" :background "rgba(255,152,0,0.1)"}} "RESOLVER_ATTRITION: CRITICAL"]
     [:h1.hero-text {:style {:fontSize "48px"}} "ATTRITION" [:br] "CASCADE" [:br] "BOUNDARY"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#7ADDDC" :fontWeight 700}} 
      "Resolver exit rate reached 20% in high-load disputes, signaling a systemic fragility."]]
    [:div.frame-footer
     [:span "EXIT_RATE: 20.0%"]
     [:span "THRESHOLD: 20.0%"]]]

   ;; FRAME 2: BUDGET DRIFT
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] REP_SIM: J-PHASE | STATUS: DRIFT"]
    [:div.frame-content
     [:div.status-badge {:style {:color "#FF9800" :border "1px solid #FF9800" :background "rgba(255,152,0,0.1)"}} "INVARIANT_VIOLATION: BUDGET"]
     [:div.line-chart-viz {:style {:display "flex" :alignItems "flex-end" :gap "2px" :padding "10px"}}
      (for [h [20 40 60 80 100 60 40 20]] [:div {:style {:width "10%" :height (str h "%") :background "#FF9800" :opacity 0.6}}])]
     [:h2.hero-text {:style {:fontSize "36px"}} "INCENTIVE" [:br] "DRIFT" [:br] "DETECTED"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#7ADDDC" :fontWeight 700}} 
      "Net budget imbalance (outflow > inflow) identified during high-concurrency epoch simulation."]]
    [:div.frame-footer
     [:span "DRIFT: 46,500 USDC"]
     [:span "INV: BUDGET_BAL"]]]

   ;; FRAME 3: GAME THEORY STRESS
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] REP_SIM: J-PHASE | STATUS: STRESS_TEST"]
    [:div.frame-content
     [:div.status-badge.teal "STRATEGY_DOMINANCE: OBSERVED ON SWEEP"]
     [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "10px" :marginBottom "20px"}}
      [:div {:style {:padding "12px" :background "#004D59"}} "HONESTY" [:div.stat-val "100% ON EVAL EPOCHS"]]
      [:div {:style {:padding "12px" :background "#004D59"}} "MALICE" [:div.stat-val "0% ON EVAL EPOCHS"]]]
     [:h2.hero-text.teal {:style {:fontSize "44px"}} "EQUILIBRIUM" [:br] "CONSISTENT" [:br] "ON SWEEP"]
     [:p {:style {:fontSize "14px" :marginTop "16px" :color "#03DAC6" :fontWeight 800}} 
      "Despite attrition and budget drift, honesty dominates on the evaluated epoch sweep."]]
    [:div.frame-footer
     [:span "WIN_RATE: 100.0% ON EVAL SWEEP"]
     [:span "STRATEGY: EQUILIBRIUM"]]]

   ;; FRAME 4: THE OBSERVATION
   [:div.golden-frame
    [:div.frame-header "[SEW_PROT] REP_SIM: J-PHASE | STATUS: ANALYZED"]
    [:div.frame-content
     [:div {:style {:border "4px double #FF9800" :padding "24px" :textAlign "center"}}
      [:div {:style {:fontSize "10px" :fontWeight 800 :marginBottom "8px"}} "SWEEP OBSERVATION OF"]
      [:h2.hero-text.orange {:style {:fontSize "32px" :margin 0}} "STOCHASTIC" [:br] "EQUILIBRIUM" [:br] "FAILURE"]
      [:div {:style {:marginTop "16px" :fontFamily "JetBrains Mono" :fontSize "9px" :color "#7ADDDC"}} 
       "SWEEP REF: rep-sim-j-phase" [:br] "TYPE: FAILURE_OBSERVATION"]]
     [:p {:style {:fontSize "12px" :marginTop "24px" :color "#7ADDDC" :textAlign "center" :opacity 0.8}} 
      "The sweep identifies a fragility boundary: robustness degrades at ~20% concurrency load on the declared workload."]]
    [:div.frame-footer
     [:span "ANALYZED: MAY 23 2026"]
     [:span "STATUS: VULNERABLE_AT_LOAD"]]

  ]]

  [:div {:style {:background "#020617" :border "1px solid #004D59" :maxWidth "1200px"
                 :margin "40px auto 0" :padding "24px" :fontFamily "Inter, sans-serif"
                 :color "#7ADDDC" :fontSize "13px" :lineHeight "1.6"}}
   [:strong "Scope."] " These panels summarise a stochastic simulation run, not a
   universal equilibrium proof. Figures are scoped to the evaluated epoch sweep
   and declared load; they do not establish dominance or robustness over the full
   (unbounded) strategy space. See `docs/game-theory/SPE_ANALYSIS_SCOPE.md` for
    the claim-strength boundary."]])
