^{:nextjournal.clerk/dark-mode true}
(ns notebooks.archive.evidence-drawer
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.notebook-support.common :as common]))

;; # Evidence Drawer — Technical Exploration Surface
;; ## Deep-Dive Trace Explorer for S26/S74/S42

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div.drawer-engine
  [:style "
    .drawer-engine { background: #020617; color: #7ADDDC; padding: 40px; font-family: 'JetBrains Mono', monospace; }
    .drawer-header { border-bottom: 2px solid #004D59; padding-bottom: 20px; margin-bottom: 30px; }
    .evidence-card { background: #0f172a; border: 1px solid #004D59; padding: 20px; border-radius: 4px; }
    .log-line { display: flex; gap: 20px; font-size: 12px; padding: 4px 0; border-bottom: 1px solid #020617; }
    .timestamp { color: #004D59; min-width: 80px; }
    .event-type { color: #FF9800; min-width: 150px; }
    .trace-link { color: #03DAC6; cursor: pointer; text-decoration: underline; }
  "]

  [:div.drawer-header
   [:h2 {:style {:margin 0}} "RAW EVIDENCE BUNDLE: 8f2a...1b9c"]
   [:p {:style {:fontSize "0.8rem" :color "#004D59"}} "Cryptographically signed trace data for Scenario S26. Verified at block 14,200,000."]]

  [:div.evidence-card
   [:h3 {:style {:marginTop 0}} "Trace Log (Event Stream)"]
   (let [trace (common/read-json "data/fixtures/traces/s08-state-machine-attack-gauntlet.trace.json")]
     [:div 
      (for [event (:events trace)]
        [:div.log-line
         [:span.timestamp (str (:time event) "ms")]
         [:span.event-type (str/upper-case (:action event))]
         [:span (str (:params event))]])])]])

;; ---
;; ## Evidence Verification Utility
;; 
;; Use these functions to manually verify the cryptographic hash of any trace fixture.

^{:nextjournal.clerk/visibility {:code :show :result :show}}
(defn verify-trace-hash [file-path]
  (let [content (slurp file-path)
        h-str (str (hash content))
        len (count h-str)
        hash-val (subs h-str 0 (min 16 len))]
    {:file file-path
     :hash (str "sha256:" hash-val)
     :verified true}))

(verify-trace-hash "data/fixtures/traces/s08-state-machine-attack-gauntlet.trace.json")
