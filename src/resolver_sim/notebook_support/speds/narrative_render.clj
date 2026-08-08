(ns resolver-sim.notebook-support.speds.narrative-render
  "P1: renderer for the canonical narrative frame model.

   Consumes a frame map produced by speds.narrative/build-frame and turns it
   into Hiccup. It performs NO inference: it only renders the claims already
   in the model. A claim with :claim/basis :not-measured renders explicitly
   as NOT MEASURED — it is never dressed up as a passed check."
  (:require [resolver-sim.notebook-support.speds.tokens :as tokens]
            [clojure.string :as str]))

(defn- palette [k] (get tokens/palette k))

(defn- value-str
  [v]
  (cond
    (nil? v) ""
    (keyword? v) (name v)
    (coll? v) (str/join ", "
                        (map (fn [x] (if (keyword? x) (name x) (str x))) v))
    :else (str v)))

(defn- claim-value
  "Renders a claim's value, or NOT MEASURED when basis is :not-measured."
  [{:keys [claim/basis claim/value]}]
  (if (= basis :not-measured)
    [:span {:style {:color (palette :sys/alert) :fontWeight 800}} "NOT MEASURED"]
    [:span {:style {:color "#fff"}} (value-str value)]))

(defn- row
  "A single label + value line with a tiny basis tag."
  [label claim & [value-override]]
  (let [basis (:claim/basis claim)
        shown (if value-override value-override claim)]
    [:div {:style {:display "flex" :gap "10px" :alignItems "baseline"
                   :justifyContent "space-between" :padding "3px 0"
                   :borderBottom "1px dashed rgba(0,77,89,0.4)"}}
     [:span {:style {:color "#7ADDDC" :fontSize "12px" :fontWeight 700}} label]
     [:span {:style {:display "flex" :alignItems "baseline" :gap "8px"
                     :textAlign "right"}}
      (claim-value shown)
      [:span {:style {:fontFamily "JetBrains Mono" :fontSize "9px"
                      :color (if (= basis :not-measured)
                               (palette :sys/alert)
                               "#004D59")}}
       (case basis
         :observed "OBS"
         :derived "DERIVED"
         :not-measured "NOT MEASURED"
         (str/upper-case (str (or basis "UNKNOWN"))))]]]))

(defn- section
  "A titled block of rows. When empty, shows a single NOT MEASURED line."
  [title & rows]
  [:div {:style {:marginBottom "16px"}}
   [:div {:style {:fontFamily "JetBrains Mono" :fontSize "10px"
                  :fontWeight 800 :color "#FF9800" :letterSpacing "0.1em"
                  :marginBottom "6px"}} title]
   (if (seq rows)
     [:div rows]
     [:div {:style {:color (palette :sys/alert) :fontWeight 800 :fontSize "12px"}}
      "NOT MEASURED"])])

(defn- threat-section
  [threat]
  (let [tags (:threat/tags threat)
        purpose (:threat/purpose threat)]
    (apply section "THREAT"
           (cond-> []
             (not= :not-measured (:claim/basis tags))
             (conj (row "Threat tags" tags))
             (not= :not-measured (:claim/basis purpose))
             (conj (row "Purpose" purpose))))))

(defn- response-section
  [response]
  (let [guards (:response/guards response)]
    (apply section "PROTOCOL RESPONSE"
           (for [g guards
                 :let [ex (:guard/exercised g)]]
             (row (str "Guard " (:guard/id g) " protects " (:guard/transition g))
                  (assoc ex :claim/value
                         (if (= :not-measured (:claim/basis ex))
                           nil
                           (if (:claim/value ex) "EXERCISED" "NOT EXERCISED"))))))))

(defn- fmt-terminal
  "Formats an expectation terminal spec {:path [...] :equals ...} legibly."
  [v]
  (if (map? v)
    (let [path-str (str/join "."
                             (map #(if (keyword? %) (name %) (str %))
                                  (or (:path v) [])))]
      (str (if (seq path-str) (str path-str " ") "") "= " (value-str (:equals v))))
    (value-str v)))

(defn- fmt-metric
  "Formats an expectation metric spec {:name ... :op ... :value ...} legibly."
  [v]
  (if (map? v)
    (str (str/upper-case (str (or (:name v) "?"))) " " (:op v) " "
         (value-str (:value v)))
    (value-str v)))

(defn- outcome-section
  [{:keys [outcome/events outcome/expected outcome/actual]}]
  (concat
   (apply section "OBSERVED OUTCOME — EVENT SEQUENCE"
          (map (fn [{:keys [event/seq event/agent event/action event/time]}]
                 [:div {:style {:fontFamily "JetBrains Mono" :fontSize "11px"
                                :color "#cbd5e1" :padding "2px 0"}}
                  (str "#" seq "  " (str/upper-case (str action))
                       "  ·  " agent "  @t" time)])
               events))
   [(apply section "OBSERVED OUTCOME — EXPECTED (scenario asserts)"
           (let [term (:terminal expected) mets (:metrics expected)]
             (cond-> []
               (not= :not-measured (:claim/basis term))
               (conj (row "Expected terminal" term
                          (fmt-terminal (:claim/value term))))
               (not= :not-measured (:claim/basis mets))
               (into (mapv (fn [m]
                             [:div {:style {:fontFamily "JetBrains Mono"
                                            :fontSize "11px"
                                            :color "#cbd5e1" :padding "2px 0"}}
                              (fmt-metric m)])
                           (:claim/value mets))))))
    (apply section "OBSERVED OUTCOME — ACTUAL (golden report)"
           (let [{:keys [outcome theory final-state-hash metrics]} actual
                 theory (or theory {})
                 mrows (for [[_ m] (sort-by (comp name key) metrics)]
                         (row (-> (:claim/id m) name (str/replace #"^outcome/" "")
                                  (str/replace "-" " ") str/upper-case)
                              m))]
             (cond-> []
               (not= :not-measured (:claim/basis outcome))
               (conj (row "Replay outcome (completed)" outcome))
               (not= :not-measured (:claim/basis (:result/theory-status theory)))
               (conj (row "Theory status" (:result/theory-status theory)))
               (not= :not-measured (:claim/basis (:result/falsified? theory)))
               (conj (row "Falsified" (:result/falsified? theory)))
               (not= :not-measured (:claim/basis (:result/mechanism-status theory)))
               (conj (row "Mechanism check" (:result/mechanism-status theory)))
               (not= :not-measured (:claim/basis (:result/mechanism-reason theory)))
               (conj (row "Mechanism reason" (:result/mechanism-reason theory)))
               (not= :not-measured (:claim/basis (:result/display-label theory)))
               (conj (row "Headline" (:result/display-label theory)))
               (not= :not-measured (:claim/basis final-state-hash))
               (conj (row "Final state hash" final-state-hash))
               (seq mrows) (into mrows))))]))

(defn- evidence-section
  [evidence]
  (apply section "EVIDENCE"
         (for [{:keys [evidence/artifact evidence/ref evidence/file
                       evidence/digest]} evidence]
           [:div {:style {:fontFamily "JetBrains Mono" :fontSize "11px"
                          :color "#cbd5e1" :padding "2px 0"}}
            [:span {:style {:color "#7ADDDC"}} (str/upper-case artifact)]
            " " ref
            (when file (str "  (" file ")"))
            (when digest [:span {:style {:color "#004D59"}} (str "  sha256:" digest)])])))

(defn- guarantees-section
  [guarantees]
  (apply section "GUARANTEES"
         (for [g guarantees]
           (row (-> (:claim/id g) name (str/replace #"^guarantee/" "")
                    (str/replace "-" " ") str/upper-case)
                g))))

(defn render-frame
  "Render a canonical narrative frame map to Hiccup."
  [{:keys [scenario/title threat response outcome evidence guarantees provenance]
    :as frame}]
  [:div {:style {:border "1px solid #004D59" :background "#020617"
                 :padding "24px" :maxWidth "760px"
                 :fontFamily (get tokens/typography :font/sans)}}
   [:div {:style {:display "flex" :justifyContent "space-between"
                  :alignItems "center" :marginBottom "18px"}}
    [:h2 {:style {:margin 0 :color "#fff" :fontSize "20px"}} title]
    [:div {:style {:fontFamily "JetBrains Mono" :fontSize "10px"
                   :color "#004D59"}} (:frame/id frame)]]
   (threat-section threat)
   (response-section response)
   (outcome-section outcome)
   (evidence-section evidence)
   (guarantees-section guarantees)
   [:div {:style {:marginTop "12px" :paddingTop "10px"
                  :borderTop "1px solid #004D59"
                  :fontFamily "JetBrains Mono" :fontSize "9px"
                  :color "#004D59"}}
    (str "scenario: " (:provenance/scenario-id provenance)
         " · run: " (or (:provenance/run-id provenance) "UNSET")
         " · git: " (or (:provenance/git-sha provenance) "UNSET")
         " · trace: " (:provenance/trace-file provenance)
         " · basis tag: OBS=observed  DERIVED=deterministically derived  NOT MEASURED=unavailable")]])
