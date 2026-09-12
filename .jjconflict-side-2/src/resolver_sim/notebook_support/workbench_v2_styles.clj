(ns resolver-sim.notebook-support.workbench-v2-styles
  "Shared workbench v2 visual theme (teal-on-slate mission control).")

(def workbench-css
  "/* Full-width Clerk overrides */
   .clerk-view, .viewer-notebook, .prose, .max-w-prose, .max-w-5xl, .mx-auto {
     max-width: none !important;
     width: 100% !important;
     margin-left: 0 !important;
     margin-right: 0 !important;
   }
   .workbench-container {
     font-family: 'JetBrains Mono', 'Inter', sans-serif;
     background: #020617;
     color: #7ADDDC;
     padding: 40px;
   }
   .wb-hero {
     margin-bottom: 8px;
   }
   .wb-hero h1 {
     margin: 0 0 10px;
     font-size: 1.75rem;
     font-weight: 900;
     letter-spacing: 0.04em;
     color: #e2e8f0;
   }
   .wb-hero p {
     margin: 0;
     font-size: 0.95rem;
     color: #94a3b8;
     max-width: 720px;
     line-height: 1.5;
   }
   .hero-strip {
     display: grid;
     grid-template-columns: repeat(4, 1fr);
     gap: 20px;
     margin: 28px 0 30px;
   }
   .metric-panel {
     background: #0f172a;
     border: 1px solid #004D59;
     padding: 20px;
     border-radius: 4px;
   }
   .metric-panel .label {
     font-size: 0.7rem;
     text-transform: uppercase;
     letter-spacing: 0.12em;
     color: #64748b;
     margin-bottom: 8px;
   }
   .metric-panel .value {
     font-size: 1.35rem;
     font-weight: 800;
     color: #7ADDDC;
   }
   .grid-layout {
     display: grid;
     grid-template-columns: repeat(12, 1fr);
     gap: 24px;
   }
   .card {
     background: #0f172a;
     border: 1px solid #004D59;
     padding: 24px;
     border-radius: 4px;
   }
   .card-title {
     font-weight: 900;
     font-size: 0.8rem;
     text-transform: uppercase;
     letter-spacing: 0.1em;
     color: #7ADDDC;
     margin-bottom: 20px;
     display: flex;
     align-items: center;
     gap: 10px;
   }
   .card-title::before {
     content: '';
     width: 4px;
     height: 16px;
     background: #7ADDDC;
   }
   .scenario-card {
     background: #0b1220;
     border: 1px solid #134e4a;
     border-radius: 6px;
     padding: 18px;
     height: 100%;
   }
   .scenario-card.pass { border-left: 4px solid #03DAC6; }
   .scenario-card.fail { border-left: 4px solid #f87171; }
   .tag-pill {
     display: inline-block;
     font-size: 10px;
     padding: 2px 8px;
     border-radius: 999px;
     border: 1px solid #334155;
     color: #cbd5e1;
     margin: 2px 4px 2px 0;
   }
   .flow-pill {
     display: inline-block;
     font-size: 10px;
     padding: 4px 10px;
     border-radius: 4px;
     background: #020617;
     border: 1px solid #004D59;
     color: #e2e8f0;
     margin: 3px 6px 3px 0;
   }
   .flow-pill.ok { border-color: #03DAC6; color: #03DAC6; }
   .flow-pill.rejected { border-color: #fbbf24; color: #fbbf24; }
   .metric-chip {
     display: inline-block;
     font-size: 11px;
     padding: 6px 10px;
     background: #020617;
     border: 1px solid #004D59;
     border-radius: 4px;
     margin: 4px 8px 4px 0;
     color: #e2e8f0;
   }
   .metric-chip strong { color: #7ADDDC; margin-right: 6px; }
   .select-row select {
     background: #0f172a;
     color: #e2e8f0;
     border: 1px solid #004D59;
     padding: 8px 12px;
     border-radius: 4px;
     font-family: inherit;
     font-size: 12px;
     min-width: 320px;
   }
   .trace-block {
     margin-top: 12px;
     max-height: 280px;
     overflow-y: auto;
     background: #020617;
     padding: 14px;
     border: 1px solid #004D59;
     border-radius: 4px;
   }")

(defn workbench-shell
  "Wrap hiccup body in the v2 container + global CSS."
  [body]
  [:div.workbench-container
   [:style workbench-css]
   body])

(defn metric-panel [label value & [{:keys [style]}]]
  [:div.metric-panel
   [:div.label label]
   [:div.value (merge {:style style} {}) value]])

(defn card
  "Grid card with title bar."
  [title body-hiccup & [{:keys [span style]}]]
  [:div.card {:style (merge {:grid-column (or span "span 12")} style {})}
   [:div.card-title title]
   body-hiccup])
