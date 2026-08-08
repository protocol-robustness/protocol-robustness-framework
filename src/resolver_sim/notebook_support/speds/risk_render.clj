(ns resolver-sim.notebook-support.speds.risk-render
  "P1: Renderer for risk-projection.v1 — the scenario risk card.

   Renders ONLY what the projection asserts. A value that the projection marks
   :not-measured (VaR p95/p99, evidence integrity, chain verification, world
   transition verification) renders explicitly as NOT MEASURED — it is never
   dressed up as a checked guarantee. This renderer is presentation-only and
   never participates in the :risk-projection/root commitment."
  (:require [hiccup.core :as hiccup]
            [clojure.string :as str]
            [resolver-sim.notebook-support.speds.tokens :as tokens]))

(defn- palette [k] (get tokens/palette k))

(defn- status-line
  [label value basis]
  (let [color (case basis
                :verified (palette :sys/success)
                :failed (palette :sys/error)
                (palette :sys/alert))
        tag (case basis
              :verified "VERIFIED"
              :failed "FAILED"
              "NOT MEASURED")]
    [:div {:style {:display "flex" :gap "10px" :alignItems "baseline"
                   :justifyContent "space-between" :padding "3px 0"
                   :borderBottom "1px dashed rgba(0,77,89,0.4)"}}
     [:span {:style {:color (palette :sys/primary) :fontSize "12px" :fontWeight 700}} label]
     [:span {:style {:display "flex" :alignItems "baseline" :gap "8px"
                     :textAlign "right"}}
      [:span {:style {:color (if (= basis :verified) "#fff" color)
                      :fontFamily (get tokens/typography :font/mono)
                      :fontSize "12px" :fontWeight 800}} value]
      [:span {:style {:fontFamily (get tokens/typography :font/mono)
                      :fontSize "9px" :fontWeight 800 :letterSpacing "0.05em"
                      :color color}}
       tag]]]))

(defn- section
  [title & rows]
  [:div {:style {:marginBottom "16px"}}
   [:div {:style {:fontFamily (get tokens/typography :font/mono)
                  :fontSize "10px" :fontWeight 800 :color (palette :sys/alert)
                  :letterSpacing "0.1em" :marginBottom "6px"}} title]
   (if (seq rows)
     [:div rows]
     [:div {:style {:color (palette :sys/alert) :fontWeight 800 :fontSize "12px"}}
      "NOT MEASURED"])])

(defn- metric-line
  [[k v]]
  (status-line (-> (name k) (str/replace #"^" "") (str/replace "-" " ") str/upper-case)
               (str v) :verified))

(defn render-card
  "Render a risk-projection.v1 map as a Hiccup risk card."
  [{:keys [schema projection-id context source projection coverage
           aggregation-policy distribution-policy metrics evidence
           risk-projection/root]}]
  (let [cov coverage
        per-scenario (:per-scenario metrics)
        worst (:worst-observed-scenario metrics)]
    [:div {:style {:border "1px solid #004D59" :background (palette :bg/canvas)
                   :padding "24px" :maxWidth "760px"
                   :fontFamily (get tokens/typography :font/sans)}}
     [:div {:style {:display "flex" :justifyContent "space-between"
                    :alignItems "center" :marginBottom "18px"}}
      [:h2 {:style {:margin 0 :color "#fff" :fontSize "20px"}}
       "SCENARIO RISK PROJECTION"]
      [:div {:style {:fontFamily (get tokens/typography :font/mono)
                     :fontSize "10px" :color (palette :sys/structural)}}
       (str schema " · " projection-id)]]

     (section "QUANTITY"
              (status-line "Observed quantity" (:quantity projection) :verified))

     (section "COVERAGE"
              (status-line "Scenarios in corpus" (:scenario-count cov) :verified)
              (status-line "Measured (rows ≥ 1)" (:measured-scenario-count cov) :verified)
              (status-line "Not measured" (:not-measured-scenario-count cov) :verified)
              (status-line "Rows" (:row-count cov) :verified)
              (when (seq (:not-measured-scenarios cov))
                [:div {:style {:fontFamily (get tokens/typography :font/mono)
                               :fontSize "9px" :color (palette :sys/structural)
                               :marginTop "4px"}}
                 "unmeasured: " (str/join ", " (take 6 (:not-measured-scenarios cov)))
                 (when (> (count (:not-measured-scenarios cov)) 6)
                   (str " … (+" (- (count (:not-measured-scenarios cov)) 6) ")"))]))

     (section "AGGREGATION"
              (status-line "Mode" (str/upper-case (name (or (:mode aggregation-policy) :scenario-separated)))
                           :verified)
              (status-line "Cross-scenario addition" "DISALLOWED" :verified))

     (apply section "OBSERVED METRICS (derived, scenario-local)"
            (concat
             (map metric-line (dissoc metrics :per-scenario :worst-observed-scenario))
             (when worst
               (status-line "Worst observed scenario" (:scenario/id worst)
                            :verified))))

     (section "PER-SCENARIO EXPOSURE"
              (for [{:keys [scenario/id row-count peak-observed-exposure
                            max-observed-event-loss peak-drawdown]} per-scenario]
                [:div {:style {:fontFamily (get tokens/typography :font/mono)
                               :fontSize "10px" :color "#cbd5e1" :padding "2px 0"
                               :borderBottom "1px dashed rgba(0,77,89,0.4)"}}
                 (str id "  ·  rows " row-count
                      "  ·  peak " peak-observed-exposure
                      "  ·  max-decrease " (or max-observed-event-loss "—")
                      "  ·  drawdown " peak-drawdown)]))

     (section "VALUE-AT-RISK — NOT COMPUTED"
              (status-line "VaR p95" "NOT MEASURED" :not-measured)
              (status-line "VaR p99" "NOT MEASURED" :not-measured)
              [:div {:style {:fontFamily (get tokens/typography :font/mono)
                             :fontSize "9px" :color (palette :sys/structural)
                             :marginTop "4px"}}
               (str "distribution: " (name (or (:status distribution-policy) :not-measured))
                    " — corpus statistics are not a probability distribution")])

     (section "EVIDENCE"
              (status-line "Traceability (row → evidence object + field)"
                           "AVAILABLE" (:traceability evidence))
              (status-line "Chain verification (link-v1 self/prev, contiguous)"
                           (if (= :verified (:chain-verification evidence)) "VERIFIED" "FAILED")
                           (:chain-verification evidence))
              (status-line "Integrity (evidence/hash recomputed)"
                           "NOT MEASURED" :not-measured)
              (status-line "World transition (before → after recomputed)"
                           "NOT MEASURED" :not-measured)
              (status-line "World hash fields (well-formed, projection evidence)"
                           (if (= :verified (:world-hash-fields evidence)) "VERIFIED" "FAILED")
                           (:world-hash-fields evidence))
              [:div {:style {:fontFamily (get tokens/typography :font/mono)
                             :fontSize "9px" :color (palette :sys/structural)
                             :marginTop "4px"}}
               (str "evidence roots: " (count (:evidence-roots source)) " · "
                    "scenario roots: " (count (:scenario-roots source)))
               (when-let [detail (:chain-verification-detail evidence)]
                 (str " · chains verified: "
                      (:verified-scenario-count detail) "/" (:scenario-count detail)))
               (when-let [root (:verification-root evidence)]
                 (str " · verification root: " root))])

     [:div {:style {:marginTop "12px" :paddingTop "10px"
                    :borderTop "1px solid #004D59"
                    :fontFamily (get tokens/typography :font/mono)
                    :fontSize "9px" :color (palette :sys/structural)}}
      (str "bundle: " (:bundle-dir context) " · run: " (:run-id context))
      [:br]
      (str "root sha256: " (:canonical/hash root))]]))

(defn render-card-html
  "Render the risk card as a standalone HTML string."
  [artifact]
  (str "<!doctype html><html><head><meta charset=utf-8>"
       "<style>body{font-family:Inter,Arial}</style>"
       "</head><body style=background:#0b1220;color:#e2e8f0;padding:24px>"
       (hiccup/html (render-card artifact))
       "</body></html>"))
