(ns resolver-sim.notebook-support.speds.tokens)

;; VENS Design Tokens v1.1
;; "Mission Control" Specification

(def palette
  {:sys/primary    "#7ADDDC" ;; Integrity / Active State (Teal)
   :sys/success    "#03DAC6" ;; Verified / Refunded (Material Teal)
   :sys/structural "#004D59" ;; Deep Architecture / Borders (Dark Teal)
   :sys/alert       "#FF9800" ;; Adversarial / Disputed (Material Orange)
   :sys/error       "#EF4444" ;; Invariant Violation (Emergency Red)
   :bg/canvas       "#020617" ;; Base Background (Midnight Slate)
   :bg/surface      "#0F172A" ;; Component Background (Deep Navy)
   :text/high-vis   "#FFFFFF" ;; High Contrast Labels
   :text/dim        "#7ADDDC"}) ;; Dimmed teal for secondary data

(def typography
  {:font/mono      "'JetBrains Mono', monospace"
   :font/sans      "'Inter', sans-serif"
   :size/h1        "48px"
   :size/h2        "32px"
   :size/body      "16px"
   :size/caption   "10px"
   :weight/hero    "900"
   :weight/bold    "700"
   :weight/normal  "400"})

(def grid
  {:base    "8px"
   :frame   "500px"
   :border  "1px solid #004D59"})

(def shadows
  {:hero    "0 0 30px rgba(122, 221, 220, 0.4)"
   :alert   "0 0 30px rgba(255, 152, 0, 0.6)"
   :success "0 0 30px rgba(3, 218, 198, 0.6)"})

(def frame-styles
  {:badge {:color (:sys/alert palette)
           :border (str "1px solid " (:sys/alert palette))
           :background "rgba(255,152,0,0.1)"
           :padding "4px 12px"
           :fontFamily (:font/mono typography)
           :fontSize (:size/caption typography)
           :fontWeight 800
           :marginBottom "20px"}
   :hero-title {:fontSize "42px"
                :fontWeight (:weight/hero typography)
                :lineHeight 0.9
                :color (:text/high-vis typography)
                :textShadow (:hero shadows)}
   :caption {:fontSize (:size/body typography)
             :marginTop "24px"
             :color (:sys/primary palette)
             :fontWeight (:weight/bold typography)}})
