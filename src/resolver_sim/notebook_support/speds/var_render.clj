(ns resolver-sim.notebook-support.speds.var-render
  "P1/P2 renderer for var-projection.v1 — the VaR card.

   Renders ONLY what the projection asserts. VaR numbers render with their
   DERIVED basis; a missing expected shortfall (empty tail) renders NOT
   MEASURED. The :interpretation statement — corpus-relative, not a
   probabilistic forecast — is rendered verbatim and cannot be dropped. This
   renderer is presentation-only and never participates in the :var/root
   commitment."
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

(defn- section [title & rows]
  [:div {:style {:marginBottom "16px"}}
   [:div {:style {:fontFamily (get tokens/typography :font/mono)
                  :fontSize "10px" :fontWeight 800 :color (palette :sys/alert)
                  :letterSpacing "0.1em" :marginBottom "6px"}} title]
   (if (seq rows)
     [:div rows]
     [:div {:style {:color (palette :sys/alert) :fontWeight 800 :fontSize "12px"}}
      "NOT MEASURED"])])

(defn- es-line
  "Render an exact expected-shortfall pair as a value, or NOT MEASURED."
  [[k v]]
  (let [label (-> (name k) (str/replace "-" " ") str/upper-case)]
    (if (= :not-measured (:basis v))
      (status-line label "NOT MEASURED" :not-measured)
      (status-line label
                   (str (:numerator v) "/" (:denominator v)
                        " ≈ " (format "%.2f" (double (/ (:numerator v) (:denominator v)))))
                   :verified))))

(defn render-card
  "Render a var-projection.v1 map as a Hiccup card."
  [{:keys [schema var/id outcome quantity distribution source coverage
           method interpretation metrics var/root]}]
  (let [m metrics
        attr (:tail-attribution/p99 m)]
    [:div {:style {:border "1px solid #004D59" :background (palette :bg/canvas)
                   :padding "24px" :maxWidth "760px"
                   :fontFamily (get tokens/typography :font/sans)}}
     [:div {:style {:display "flex" :justifyContent "space-between"
                    :alignItems "center" :marginBottom "18px"}}
      [:h2 {:style {:margin 0 :color "#fff" :fontSize "20px"}}
       "VAR PROJECTION"]
      [:div {:style {:fontFamily (get tokens/typography :font/mono)
                     :fontSize "10px" :color (palette :sys/structural)}}
       (str schema " · " id)]]

     (section "OUTCOME"
              (status-line "Outcome variable" (-> outcome name (str/replace "-" " ") str/upper-case)
                           :verified)
              (status-line "Quantity" quantity :verified))

     (section "DISTRIBUTION"
              (status-line "Model" (:model distribution) :verified)
              (status-line "Weights" (str "uniform, " (:weighted-scenario-count coverage)
                                          " measured scenarios") :verified)
              (status-line "Weighted / distribution scenarios"
                           (str (:weighted-scenario-count coverage) " / "
                                (:distribution-scenario-count coverage)) :verified))

     (section "VALUE-AT-RISK (derived)"
              (status-line "VaR p95" (str (get-in m [:var/p95 :value])) (:basis (:var/p95 m)))
              (status-line "VaR p99" (str (get-in m [:var/p99 :value])) (:basis (:var/p99 m)))
              (es-line [:expected-shortfall/p95 (:expected-shortfall/p95 m)])
              (es-line [:expected-shortfall/p99 (:expected-shortfall/p99 m)]))

     (section "TAIL ATTRIBUTION — P99"
              (if (= :derived (:basis attr))
                (for [{:keys [scenario/id value weight]} (:scenarios attr)]
                  [:div {:style {:fontFamily (get tokens/typography :font/mono)
                                 :fontSize "10px" :color "#cbd5e1" :padding "2px 0"
                                 :borderBottom "1px dashed rgba(0,77,89,0.4)"}}
                   (str id "  ·  outcome " value "  ·  weight " weight)])
                [:div {:style {:color (palette :sys/alert) :fontWeight 800 :fontSize "12px"}}
                 "NOT MEASURED"]))

     (section "INTERPRETATION"
              [:div {:style {:fontFamily (get tokens/typography :font/mono)
                             :fontSize "9px" :color "#cbd5e1" :lineHeight "1.5"}}
               interpretation])

     (section "METHOD"
              (status-line "Quantile" (:quantile method) :verified)
              [:div {:style {:fontFamily (get tokens/typography :font/mono)
                             :fontSize "9px" :color (palette :sys/structural)}}
               (:var-definition method)])

     [:div {:style {:marginTop "12px" :paddingTop "10px"
                    :borderTop "1px solid #004D59"
                    :fontFamily (get tokens/typography :font/mono)
                    :fontSize "9px" :color (palette :sys/structural)}}
      (str "risk-projection: " (:risk-projection-id source) " · "
           "distribution: " (:distribution-id source) " · "
           "var root: " (:canonical/hash root))]]))

(defn render-card-html
  "Render the VaR card as a standalone HTML string."
  [v]
  (str "<!doctype html><html><head><meta charset=utf-8>"
       "<style>body{font-family:Inter,Arial}</style>"
       "</head><body style=background:#0b1220;color:#e2e8f0;padding:24px>"
       (hiccup/html (render-card v))
       "</body></html>"))
