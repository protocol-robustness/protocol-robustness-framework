(ns resolver-sim.notebook-support.demo-views
  "Visitor-facing renderers for self-contained demonstrations.

   Clerk-free: these return plain hiccup data or plain text, so the same
   functions can be wrapped by clerk/html in a notebook, embedded in a static
   page, or printed by a CLI walkthrough. The first surface uses ordinary
   language; framework vocabulary only appears under the progressively
   disclosed technical proof.

   Both renderers consume the same demo model produced by a demo namespace:

     {:demo/id ...
      :demo/question \"...\"
      :demo/baseline {:label \"...\" :value <number|string> :unit \"...\" :admitted? bool}
      :demo/action   {:label \"...\" :from <number|string> :to <number|string>
                      :unit \"...\" :detail \"...\"}
      :demo/outcome  {:admitted? bool :failed-checks [...]}
      :demo/expect   {:baseline :admitted :after-action :not-admitted}
      :demo/explanation \"...\"
      :demo/evidence {:committed-hash \"sha256:...\"
                      :lines [[\"label\" \"value\"] ...]
                      :after/checks [{:check/id kw :status :pass|:fail :detail <optional>}]}}"
  (:require [clojure.string :as str]))

(defn- fmt-value [v unit]
  (if (number? v)
    (str (format "%,d" (long v)) (when (seq unit) (str " " unit)))
    (str v)))

(defn- verdict-text [admitted?]
  (if admitted? "✓ ADMITTED" "✕ NOT ADMITTED"))

(defn- verdict-chip [admitted?]
  (if admitted?
    [:span {:style {:background "#052e16" :color "#4ade80"
                    :border "1px solid #22c55e" :borderRadius "4px"
                    :padding "2px 10px" :fontWeight "700" :fontSize "12px"}}
     "✓ ADMITTED"]
    [:span {:style {:background "#450a0a" :color "#f87171"
                    :border "1px solid #ef4444" :borderRadius "4px"
                    :padding "2px 10px" :fontWeight "700" :fontSize "12px"}}
     "✕ NOT ADMITTED"]))

(defn- panel [label color bg border children]
  [:div {:style {:border (str "1px solid " border)
                 :borderRadius "8px" :padding "14px 16px"
                 :margin "12px 0" :background bg}}
   [:div {:style {:color color :fontSize "11px"
                  :textTransform "uppercase" :letterSpacing "0.05em"
                  :fontWeight "700" :marginBottom "8px"}}
    label]
   children])

(defn demo-surface
  "The plain-language demonstration: question, original, change, same check
   again, and why — in that order, nothing else."
  [m]
  (let [question (:demo/question m)
        baseline (:demo/baseline m)
        action (:demo/action m)
        outcome (:demo/outcome m)
        explanation (:demo/explanation m)]
    [:div {:style {:fontFamily "sans-serif" :maxWidth "760px"}}
     [:h2 {:style {:color "#0f172a" :fontSize "22px" :margin "0 0 4px"}}
      question]
     (panel "Original result" "#166534" "#f0fdf4" "#22c55e"
            [:div {:style {:display "flex" :justifyContent "space-between"
                           :alignItems "center"}}
             [:span {:style {:fontSize "20px" :fontWeight "700" :color "#052e16"}}
              (:label baseline) ": " (fmt-value (:value baseline) (:unit baseline))]
             (verdict-chip (:admitted? baseline))])
     (panel "Change" "#92400e" "#fffbeb" "#f59e0b"
            [:<>
             [:div {:style {:fontSize "15px" :color "#451a03"}}
              (:label action)
              [:span {:style {:fontWeight "700" :color "#b45309" :margin "0 4px"}}
               (fmt-value (:from action) (:unit action))
               " → "
               (fmt-value (:to action) (:unit action))]]
             [:div {:style {:color "#92400e" :fontSize "12px" :marginTop "6px"}}
              (:detail action)]])
     (panel "Same check again" "#991b1b" "#fff1f2" "#ef4444"
            [:div {:style {:display "flex" :justifyContent "space-between"
                           :alignItems "center"}}
             [:span {:style {:fontSize "16px" :color "#450a0a"}}
              "The exact same check runs on the changed record."]
             (verdict-chip (:admitted? outcome))])
     [:div {:style {:background "#f8fafc" :border "1px solid #cbd5e1"
                    :borderRadius "8px" :padding "14px 16px" :margin "12px 0"}}
      [:div {:style {:color "#64748b" :fontSize "11px" :fontWeight "700"
                     :textTransform "uppercase" :letterSpacing "0.05em"
                     :marginBottom "6px"}}
       "Why"
       [:p {:style {:margin "0" :color "#1e293b" :fontSize "14px" :lineHeight "1.6"}}
        explanation]]]]))

(defn story-text
  "The plain-language demonstration as text, for terminal walkthroughs."
  [m]
  (let [question (:demo/question m)
        baseline (:demo/baseline m)
        action (:demo/action m)
        outcome (:demo/outcome m)
        explanation (:demo/explanation m)]
    (str/join "\n"
              ["" (str "Question: " question)
               "Original result"
               (str "  " (:label baseline) ": "
                    (fmt-value (:value baseline) (:unit baseline))
                    " " (verdict-text (:admitted? baseline)))
               ""
               "Change"
               (str "  " (:label action) ": "
                    (fmt-value (:from action) (:unit action))
                    " -> " (fmt-value (:to action) (:unit action)))
               "Same check again"
               (str "  " (verdict-text (:admitted? outcome)))
               ""
               "Why"
               (str "  " explanation)])))

(defn technical-proof-text
  "The technical evidence as text: committed signature, evidence lines, and the
   check results."
  [m]
  (let [evidence (:demo/evidence m)
        lines (:lines evidence [])
        checks (:after/checks evidence [])
        failing (filter #(= :fail (:status %)) checks)]
    (str/join "\n"
              (concat [""
                       "Technical proof"
                       (str "  Committed signature: " (:committed-hash evidence))]
                      (mapv (fn [[label value]]
                              (str "  " label ": " value))
                            lines)
                      ["  Checks run against the changed evidence:"]
                      (mapv (fn [{:keys [check/id status detail]}]
                              (str "    " (name id) ": " (str/upper-case (name status))
                                   (when (some? detail)
                                     (str "  (position " detail ")"))))
                            checks)
                      [(str "  Failing check(s): "
                            (str/join ", " (map (fn [c] (name (:check/id c))) failing)))]))))

(defn technical-proof
  "Progressive disclosure: the real verifier output, hidden until asked for."
  [m]
  (let [evidence (:demo/evidence m)
        lines (:lines evidence [])
        checks (:after/checks evidence [])
        failing (filter #(= :fail (:status %)) checks)]
    [:details {:style {:background "#f1f5f9" :border "1px solid #cbd5e1"
                       :borderRadius "8px" :padding "10px 14px" :margin "16px 0"
                       :maxWidth "760px"}}
     [:summary {:style {:cursor "pointer" :fontWeight "700" :color "#475569"
                        :fontFamily "monospace"}}
      "Technical proof ▸"]
     [:div {:style {:marginTop "10px" :fontFamily "monospace" :fontSize "12px"}}
      [:div {:style {:color "#64748b"}}
       "committed signature: "
       [:span {:style {:color "#22c55e"}} (:committed-hash evidence)]]
      (for [[label value] lines]
        [:div {:key label :style {:color "#64748b" :marginTop "4px"}}
         (str label ": ")
         [:span {:style {:color "#22c55e"}} value]])
      [:table {:style {:width "100%" :borderCollapse "collapse" :marginTop "12px"
                       :fontSize "12px"}}
       [:thead
        [:tr {:style {:borderBottom "1px solid #cbd5e1" :color "#64748b"
                      :textAlign "left"}}
         [:th {:style {:padding "4px 8px"}} "Check"]
         [:th {:style {:padding "4px 8px"}} "Status"]
         (when (some :detail checks)
           [:th {:style {:padding "4px 8px"}} "Detail"])]]
       [:tbody
        (for [{:keys [check/id status detail]} checks]
          [:tr {:key (name id) :style {:borderBottom "1px solid #e2e8f0"}}
           [:td {:style {:padding "4px 8px" :color "#475569"}} (name id)]
           [:td {:style {:padding "4px 8px" :color (if (= :pass status) "#166534" "#dc2626")
                         :fontWeight "700"}}
            (str/upper-case (name status))]
           (when (some? detail)
             [:td {:style {:padding "4px 8px" :color "#64748b"}} (str "position " detail)])])]]
      (when (seq failing)
        [:div {:style {:marginTop "10px" :color "#b91c1c"}}
         "The failing check(s): "
         [:strong (str/join ", " (map #(name (:check/id %)) failing))]])]]))
