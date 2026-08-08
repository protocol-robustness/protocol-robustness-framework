(ns resolver-sim.notebook-support.speds.core
  "Sew Protocol Evidence Design System (SPEDS) v1.1
   Core Visual Primitives for Protocol Storytelling."
  (:require [resolver-sim.notebook-support.speds.tokens :as tokens]
            [clojure.string :as str]))

;; ---
;; Internal Helpers

(defn- get-color [k] (get tokens/palette k))
(defn- get-typo [k] (get tokens/typography k))

(def teal-shadow (get tokens/shadows :success))
(def alert-shadow (get tokens/shadows :alert))
(def hero-shadow (get tokens/shadows :hero))

;; ---
;; P1: Actor Node (V-ACT)

(defn v-act
  "Renders a technical actor node (circle/square with monospaced label).
   Types: :honest (Teal), :adversarial (Orange), :backstop (Dark Teal)."
  ([id] (v-act id :honest))
  ([id type]
   (let [color (case type
                 :honest      (get-color :sys/primary)
                 :adversarial (get-color :sys/alert)
                 :backstop    (get-color :sys/structural)
                 (get-color :sys/primary))]
     [:div {:style {:width "64px"
                    :height "64px"
                    :background (get-color :bg/canvas)
                    :border (str "2px solid " color)
                    :display "flex"
                    :alignItems "center"
                    :justifyContent "center"
                    :fontFamily (get-typo :font/mono)
                    :fontSize "12px"
                    :fontWeight "800"
                    :color color
                    :boxShadow (str "0 0 15px " color "44")}}
      (str/upper-case (str id))])))

;; ---
;; P2: Escrow Flow Line (V-FLO)

(defn v-flo
  "Renders a causal path for value and state movement.
   Types: :principal (Solid Teal), :yield (Dashed Teal), :adversarial (Thick Orange)."
  [type]
  (let [color (if (= type :adversarial) (get-color :sys/alert) (get-color :sys/primary))
        dashed? (= type :yield)
        thickness (if (= type :adversarial) "4px" "2px")]
    [:div {:style {:height thickness
                   :background (if dashed? "transparent" color)
                   :backgroundImage (when dashed? (str "linear-gradient(to right, " color " 50%, transparent 50%)"))
                   :backgroundSize (when dashed? "10px 100%")
                   :width "100%"
                   :position "relative"
                   :boxShadow (str "0 0 10px " color "66")}}
     [:div {:style {:position "absolute" :right "-6px" :top (if (= type :adversarial) "-4px" "-5px")
                    :width "12px" :height "12px" :background color :borderRadius "50%"}}]]))

;; ---
;; P5: Invariant Status Marker (V-INV)

(defn v-inv
  "Renders a heartbeat badge verifying protocol health.
   Status: :ok (Teal), :fail (Red)."
  [id status]
  (let [pass? (= status :ok)
        color (if pass? (get-color :sys/success) (get-color :sys/error))]
    [:div {:style {:display "inline-flex"
                   :alignItems "center"
                   :gap "8px"
                   :padding "4px 12px"
                   :background (str color "11")
                   :border (str "1px solid " color)
                   :fontFamily (get-typo :font/mono)
                   :fontSize "11px"
                   :fontWeight "800"
                   :color color}}
     (if pass? "✔" "✘") " " (str/upper-case (str id)) ": " (if pass? "OK" "FAIL")]))

;; ---
;; P7: Protocol Response Marker (V-RES)

(defn v-res
  "Renders the 'Intercept Shield' wall."
  [label]
  [:div {:style {:display "flex" :alignItems "center" :gap "15px"}}
   [:div {:style {:width "12px" :height "120px"
                  :background (get-color :sys/primary)
                  :boxShadow (str "0 0 30px " (get-color :sys/primary) "99")}}]
   [:div {:style {:fontFamily (get-typo :font/mono) :fontSize "10px" :color (get-color :sys/primary)}}
    (str "× " (str/upper-case label)) [:br] "× SHATTERED"]])

;; ---
;; P8: Replay Badge (V-RPY)

(defn v-rpy
  "Renders the cryptographic evidence anchor. When no replay hash is
   supplied, shows an explicit 'N/A' rather than claiming verification."
  [hash-val]
  (let [base {:fontFamily (get-typo :font/mono)
              :fontSize "9px"
              :color (get-color :sys/structural)
              :letterSpacing "0.05em"}]
    (if (seq hash-val)
      [:div {:style base}
       "TRACE_ID: " [:span {:style {:color (get-color :sys/primary)}} (subs hash-val 0 (min 12 (count hash-val)))] " | REPLAY: VERIFIED"]
      [:div {:style base}
       "REPLAY: N/A"])))

;; ---
;; Main Component: V-FRAME

(defn v-frame
  "Standardized 1080x1080 (scaled to 500px) evidence container."
  [{:keys [title header status-badge footer-left footer-right content-class]} & content]
  [:div.golden-frame {:style {:width (get tokens/grid :frame)
                              :height (get tokens/grid :frame)
                              :background (get-color :bg/canvas)
                              :border (get tokens/grid :border)
                              :display "flex"
                              :flexDirection "column"
                              :overflow "hidden"
                              :position "relative"}}
   ;; Header Strip
   [:div {:style {:height "30px" :background (get-color :sys/structural)
                  :display "flex" :alignItems "center" :padding "0 12px"
                  :fontFamily (get-typo :font/mono) :fontSize "10px" :fontWeight "800"
                  :color (get-color :sys/primary) :letterSpacing "0.1em"}}
    header]

   ;; Content
   [:div {:style {:flex 1 :padding "32px" :display "flex" :flexDirection "column" :justifyContent "center"
                  :backgroundImage (str "radial-gradient(" (get-color :sys/structural) " 1px, transparent 1px)")
                  :backgroundSize "20px 20px"}}
    content]

   ;; Footer Strip
   [:div {:style {:height "40px" :borderTop (get tokens/grid :border)
                  :display "flex" :alignItems "center" :justifyContent "space-between"
                  :padding "0 20px" :fontFamily (get-typo :font/mono) :fontSize "9px"
                  :color (get-color :sys/primary) :opacity 0.6}}
    [:span footer-left]
    [:span footer-right]]])

(defn render-carousel
  "Renders story frames. Supports :layout option:
   - :grid (default) — multi-column grid
   - :single — centered single frame (first frame only, for deep-dive)
   - :row — horizontal scrollable row"
  [content-fn frame-specs {:keys [columns gap layout] :or {columns 2 gap "40px" layout :grid}}]
  (if (empty? frame-specs)
    [:div "No frames to display"]
    (case layout
      :single
      (if-let [spec (first frame-specs)]
        [:div.single-frame {:style {:display "flex" :justifyContent "center"}}
         (content-fn 1 1 spec)]
        [:div "No frames to display"])
      :row
      [:div.frame-carousel {:style {:display "flex" :gap gap :overflowX "auto" :paddingBottom "12px"}}
       (for [[idx specs] (map-indexed vector frame-specs)]
         (content-fn (inc idx) (count frame-specs) specs))]
      [:div.frame-carousel {:style {:display "grid" :gridTemplateColumns (str "repeat(" columns ", 1fr)") :gap gap}}
       (for [[idx specs] (map-indexed vector frame-specs)]
         (content-fn (inc idx) (count frame-specs) specs))])))
