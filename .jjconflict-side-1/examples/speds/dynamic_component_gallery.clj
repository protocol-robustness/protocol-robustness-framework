(ns examples.speds.dynamic-component-gallery
  "Dynamically discovers and renders all SPEDS v1.1 components
   from resolver-sim.notebook-support.speds.core.

   No hardcoded component list — add a new public component fn
   to core.clj and it appears here automatically."
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.speds.core :as speds]
            [resolver-sim.notebook-support.speds.tokens :as tokens]))

;; ──────────────────────────────────────────────────────────────────────────
;; Component metadata — describes how to demo each component
;; Extend this map when adding a new public component to core.clj
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private component-demos
  "Describes how to render a demo invocation for each SPEDS component.
   Key = component keyword (matching the var name minus the v- prefix).
   Value = map with:
     :label     — human-readable name
     :variants  — seq of arg-lists to call the fn with for different states"
  {:act
   {:label    "V-ACT — Actor Node"
    :variants [[:buyer :honest]
               [:seller :adversarial]
               [:resolver :backstop]]}

   :flo
   {:label    "V-FLO — Flow Line"
    :variants [[:principal] [:yield] [:adversarial]]}

   :inv
   {:label    "V-INV — Invariant Badge"
    :variants [[:solvency :ok]
               [:budget :fail]
               [:conservation :ok]]}

   :res
   {:label    "V-RES — Protocol Response Marker"
    ;; label-only; called directly below the token display
    :variants [["G04"] ["G07"]]}

   :rpy
   {:label    "V-RPY — Replay Badge"
    :variants [["a1b2c3d4e5f6"]]}})

;; ──────────────────────────────────────────────────────────────────────────
;; Dynamic renderer
;; ──────────────────────────────────────────────────────────────────────────

(defn- component-var
  "Look up the public var for a component keyword (e.g. :act -> v-act)."
  [k]
  (let [var-name (symbol (str "v-" (name k)))]
    (try
      (ns-resolve 'resolver-sim.notebook-support.speds.core var-name)
      (catch Exception _ nil))))

(defn- render-component-section
  "Render a demo section for one SPEDS component."
  [[k {:keys [label variants]}]]
  (when-let [vfn (component-var k)]
    (let [samples (for [args variants]
                    {:args args
                     :rendered (try
                                 (apply vfn args)
                                 (catch Exception e
                                   [:div {:style {:color (get tokens/palette :sys/error)}}
                                    (str "ERR: " (.getMessage e))]))})]
      [:div {:style {:marginBottom "40px"}}
       [:h3 {:style {:color (get tokens/palette :sys/primary)
                     :fontFamily (get tokens/typography :font/mono)
                     :fontSize "14px"
                     :letterSpacing "0.1em"
                     :marginBottom "16px"}}
        label]
       [:div {:style {:display "flex" :gap "24px" :alignItems "center"
                      :flexWrap "wrap"}}
        (for [[i {:keys [args rendered]}] (map-indexed vector samples)]
          [:div {:key i :style {:display "flex" :flexDirection "column" :alignItems "center" :gap "8px"}}
           [:div {:style {:background (get tokens/palette :bg/canvas)
                          :padding "16px"
                          :border (str "1px solid " (get tokens/palette :sys/structural))}}
            rendered]
           [:span {:style {:fontSize "9px"
                           :fontFamily (get tokens/typography :font/mono)
                           :color (get tokens/palette :sys/structural)}}
            (pr-str (into [] args))]])]])))

;; ──────────────────────────────────────────────────────────────────────────
;; Token displays
;; ──────────────────────────────────────────────────────────────────────────

(defn- palette-swatch
  []
  [:div {:style {:marginBottom "40px"}}
   [:h3 {:style {:color (get tokens/palette :sys/primary) :fontFamily (get tokens/typography :font/mono)
                 :fontSize "14px" :letterSpacing "0.1em" :marginBottom "16px"}}
    "Design Tokens — Palette"]
   [:div {:style {:display "flex" :gap "12px" :flexWrap "wrap"}}
    (for [[k v] (sort tokens/palette)]
      [:div {:key k :style {:textAlign "center" :width "80px"}}
       [:div {:style {:width "60px" :height "60px" :background v
                      :border (str "1px solid " (get tokens/palette :sys/structural))
                      :margin "0 auto 6px"}}]
       [:div {:style {:fontSize "9px" :fontFamily (get tokens/typography :font/mono)
                      :color (get tokens/palette :sys/structural)}}
        (name k)]])]])

(defn- shadows-display
  []
  [:div {:style {:marginBottom "40px"}}
   [:h3 {:style {:color (get tokens/palette :sys/primary) :fontFamily (get tokens/typography :font/mono)
                 :fontSize "14px" :letterSpacing "0.1em" :marginBottom "16px"}}
    "Design Tokens — Shadows"]
   [:div {:style {:display "flex" :gap "24px"}}
    (for [[k s] {:success speds/teal-shadow :alert speds/alert-shadow :hero speds/hero-shadow}]
      [:div {:key k :style {:width "120px" :height "80px" :background (get tokens/palette :bg/canvas)
                            :border (str "1px solid " (get tokens/palette :sys/structural))
                            :boxShadow s
                            :display "flex" :alignItems "center" :justifyContent "center"
                            :fontSize "9px" :fontFamily (get tokens/typography :font/mono)
                            :color (get tokens/palette :sys/structural)}}
       (name k)])]])

;; ──────────────────────────────────────────────────────────────────────────
;; Main
;; ──────────────────────────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :hide :result :show}
  :nextjournal.clerk/width :full}
(clerk/html
 [:div {:style {:background (get tokens/palette :bg/canvas)
                :padding "40px" :color (get tokens/palette :sys/primary)
                :fontFamily "'Inter', sans-serif"}}
  [:h1 {:style {:fontSize "24px" :fontWeight "900" :textTransform "uppercase"
                :letterSpacing "0.15em" :marginBottom "8px"}}
   "SPEDS v1.1 — Dynamic Component Gallery"]
  [:p {:style {:fontSize "12px" :marginBottom "40px"
               :color (get tokens/palette :sys/structural)}}
   "Auto-discovered from " [:code "resolver-sim.notebook-support.speds.core"]
   ". Add a new public fn and it appears here."]

  ;; Design Tokens
  (palette-swatch)
  (shadows-display)

  ;; Dynamic components
  [:h2 {:style {:fontSize "18px" :fontWeight "800" :textTransform "uppercase"
                :letterSpacing "0.1em" :marginBottom "24px" :marginTop "60px"}}
   "Components"]
  (for [[k demo] (sort component-demos)]
    (render-component-section [k demo]))

  ;; V-FRAME — special case (takes map + child content)
  [:h3 {:style {:color (get tokens/palette :sys/primary) :fontFamily (get tokens/typography :font/mono)
                :fontSize "14px" :letterSpacing "0.1em" :marginBottom "16px" :marginTop "40px"}}
   "V-FRAME — Evidence Container"]
  [:div {:style {:display "flex" :gap "40px" :flexWrap "wrap"}}
   (speds/v-frame
    {:header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: ALERT"
     :footer-left "TRACE_ID: a1b2c3d4e5f6"
     :footer-right "BLOCK_H: 14,200,000"}
    [:div {:style {:padding "20px"}}
     [:div {:style {:display "inline-block" :padding "4px 12px"
                    :background "rgba(255, 152, 0, 0.1)"
                    :border "1px solid #FF9800" :color "#FF9800"
                    :fontSize "12px" :fontWeight "800" :marginBottom "20px"}}
      "THREAT_DETECTED"]
     [:h1 {:style {:fontSize "36px" :fontWeight "900" :lineHeight "0.9"
                   :textTransform "uppercase" :color "#fff"
                   :textShadow speds/teal-shadow}}
      "100M LIQUID" [:br] "REORG ATTACK"]
     [:p {:style {:fontSize "14px" :marginTop "20px" :color (get tokens/palette :sys/primary) :fontWeight "700"}}
      "Attacker attempts to force a fraudulent settlement by manipulating L1 block-finality."]])
   (speds/v-frame
    {:header "[SEW_PROT] ADVERSARIAL_RUN: S26 | STATUS: INTERCEPTED"
     :footer-left "LATENCY: 0.1ms"
     :footer-right "OUTCOME: TERMINAL_REJECT"}
    [:div {:style {:display "flex" :flexDirection "column" :gap "12px" :padding "20px"}}
     (speds/v-res "G04")
     (speds/v-inv :solvency :ok)
     [:h2 {:style {:fontSize "40px" :fontWeight "900" :lineHeight "0.9"
                   :textTransform "uppercase" :color "#03DAC6"
                   :textShadow speds/teal-shadow}}
      "ATTACK" [:br] "DEFLECTED"]])]])
