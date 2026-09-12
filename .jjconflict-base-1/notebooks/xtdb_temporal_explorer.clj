;; # XTDB Temporal Explorer
;;
;; **Purpose:** Show what an immutable, bitemporal database makes *unusually easy*
;; for the PRF execution index:
;;
;;   - what is effective now (valid time);
;;   - what the index knew at some point in time (system time);
;;   - how indexed knowledge changed without mutating authoritative artifacts;
;;   - how explicitly comparable executions relate through rooted results.
;;
;; This notebook queries XTDB only. It does not depend on the seed namespace.
;;
;; **To load the dataset:**
;; ```bash
;; make xtdb
;; bb explorer:seed
;; ```
;;
;; If XTDB is unreachable or empty, each section shows a notice instead of failing.
;;
;; ## The core distinction
;;
;; | | |
;; |---|---|
;; | **VALID TIME** | *What was effective then?* (as best known now, effective at T) |
;; | **SYSTEM TIME** | *What did we know then?* (immutable history of the index) |
;;
;; ```text
;; Derived index
;; -------------
;; These rows are query projections over completed/rooted PRF artifacts.
;; XTDB does not confer authority or replace artifact verification.
;; ```

(ns xtdb-temporal-explorer
  {:nextjournal.clerk/visibility {:code :fold :result :show}}
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.db.execution-projection :as ep]
            [resolver-sim.db.store :as store]
            [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.notebook-support.xtdb :as nx]))

(def ds
  (try (xtdb/->datasource) (catch Throwable _ nil)))

(defn- live? []
  (and ds (seq (ep/execution-runs-valid-at ds (java.util.Date.)))))

(defn- notice [msg]
  (clerk/html
   [:div {:style {:padding "12px 16px" :border-radius "8px"
                  :border "1px solid #b58900" :background "#3a2f0a"
                  :color "#f5d782" :font-family "monospace"}} msg]))

(def ^:private th-style
  {:font-family "monospace" :font-size "11px" :text-align "left"
   :padding "6px 10px" :border-bottom "2px solid #0d7a8a" :color "#0d7a8a"})

(def ^:private td-style
  {:font-family "monospace" :font-size "11px" :padding "6px 10px"
   :border-bottom "1px solid #eee"})

(defn- htable
  "Render a Hiccup HTML table."
  [headers rows]
  [:table {:style {:border-collapse "collapse" :width "100%" :margin-bottom "8px"}}
   [:thead [:tr (for [h headers] [:th th-style h])]]
   [:tbody (for [r rows] [:tr (for [c r] [:td td-style (str c)])])]])

(defn- panel [title & body]
  (clerk/html
   (into [:div {:style {:margin-bottom "28px"}}]
         (concat
          [[:h2 {:style {:font-family "monospace" :font-size "14px"
                         :letter-spacing "0.08em" :color "#0d7a8a"
                         :margin "0 0 12px"}} (str/upper-case title)]]
          body))))

;; ---------------------------------------------------------------------------
;; 1. Current state
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :hide}}
(panel
 "1 · Current state"
 (if-not (live?)
   (notice "XTDB unreachable or empty. Run `bb explorer:seed` first.")
   (let [runs  (ep/execution-runs-valid-at ds (java.util.Date.))
         bench (ep/benchmark-executions-history ds nil)
         invs  (store/temporal-invariants-for-run ds "temporal-failure")
         cards [["Completed runs" (count runs)]
                ["Benchmark cases" (count bench)]
                ["Distinct result roots" (count (distinct (map :execution/bundle_root runs)))]
                ["Invariant failures" (count (remove :temporal/holds? invs))]]]
     (clerk/html
      [:div {:style {:display "flex" :gap "16px" :flex-wrap "wrap"}}
       (for [[label value] cards]
         [:div {:style {:border "1px solid #ddd" :border-radius "8px" :padding "14px 20px"
                        :min-width "150px" :text-align "center"}}
          [:div {:style {:font-size "30px" :font-weight 800 :color "#0d7a8a"}} (str value)]
          [:div {:style {:font-size "11px" :color "#666" :font-family "monospace"}} label]])]))))

;; ---------------------------------------------------------------------------
;; 2. Execution explorer
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :hide}}
(panel
 "2 · Execution explorer"
 (if-not (live?)
   (notice "XTDB unreachable or empty. Run `bb explorer:seed` first.")
   (let [row (first (filter #(= "run-failure" (:execution/_id %))
                            (ep/execution-runs-valid-at ds (java.util.Date.))))]
     (if row
       (clerk/html
        [:div
         [:div {:style {:font-family "monospace" :font-size "11px" :color "#888" :margin-bottom "10px"}}
          "This row was derived only after completion validation — a projection over a completed/rooted PRF package."]
         (htable ["field" "value"]
                 (map (fn [[k v]] [(name k) v])
                      (select-keys row [:execution/_id :execution/scenario_id
                                        :execution/bundle_root :execution/package_index_root
                                        :execution/semantic_status :execution/status])))])
       (notice "run-failure not found. Run `bb explorer:seed` first.")))))

;; ---------------------------------------------------------------------------
;; 3. Failure Archaeology (HERO)
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :hide}}
(panel
 "3 · Failure archaeology"
 (if-not (live?)
   (notice "XTDB unreachable or empty. Run `bb explorer:seed` first.")
   (let [run   (first (filter #(= "temporal-failure" (:temporal/id %))
                              (store/temporal-runs-history ds)))
         steps (store/temporal-steps-for-run ds "temporal-failure")
         invs  (store/temporal-invariants-for-run ds "temporal-failure")
         bad   (first (remove :temporal/holds? invs))]
     (if run
       (clerk/html
        [:div
         [:div {:style {:font-family "monospace" :font-size "11px" :color "#888" :margin-bottom "10px"}}
          (str "RUN temporal-failure · scenario " (:temporal/scenario-id run)
               " · outcome " (:temporal/outcome run))]
         (htable ["step" "action" "result" "simulated time"]
                 (map (fn [s] [(:temporal/step-index s) (:temporal/action s)
                               (:temporal/result s) (:temporal/valid-from s)])
                      steps))
         [:div {:style {:font-family "monospace" :font-size "11px" :color "#0d7a8a"
                        :margin "12px 0 6px"}} "INVARIANT EVALUATIONS"]
         (htable ["step" "invariant" "holds" "severity"]
                 (map (fn [i] [(:temporal/step-index i) (:temporal/invariant i)
                               (:temporal/holds? i) (:temporal/severity i)])
                      invs))
         (when bad
           [:div {:style {:margin-top "12px" :padding "12px" :border-radius "8px"
                          :border "1px solid #a33" :background "#3a1111" :color "#f2b8b8"
                          :font-family "monospace" :font-size "11px"}}
            (str "✗ step " (:temporal/step-index bad) " violates invariant "
                 (:temporal/invariant bad) " → halt / outcome " (:temporal/outcome run)
                 " → root / evidence checksum")])])
       (notice "temporal-failure not found. Run `bb explorer:seed` first.")))))

;; ---------------------------------------------------------------------------
;; 4. Time Travel (HERO)
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :hide}}
(panel
 "4 · Time travel"
 (if-not (live?)
   (notice "XTDB unreachable or empty. Run `bb explorer:seed` first.")
   (let [id    "run-synthetic-correction"
         wrong (first (sort-by :execution/_system_from
                               (filter #(= id (:execution/_id %))
                                       (ep/execution-runs-history ds))))
         diff  (nx/execution-run-knowledge-diff
                ds {:execution-run/id id
                    :valid-at (java.util.Date.)
                    :known-at (:execution/_system_from wrong)})]
     (if (and wrong (:then diff) (:now diff))
       (clerk/html
        [:div
         [:div {:style {:font-family "monospace" :font-size "11px" :color "#888" :margin-bottom "10px"}}
          "Same run identity, same completed/rooted PRF artifact. Only the *derived index observation* changed. "
          "XTDB system time records the correction; the authoritative artifact never changed."]
         (htable ["field" "as known then" "best known now"]
                 [["result root" (get-in diff [:then :execution/bundle_root]) (get-in diff [:now :execution/bundle_root])]
                  ["outcome" (get-in diff [:then :execution/status]) (get-in diff [:now :execution/status])]])
         [:div {:style {:margin-top "12px" :font-family "monospace" :font-size "11px" :color "#0d7a8a"}}
          (str "Changed indexed fields: "
               (clojure.string/join ", "
                                    (map (fn [k] (last (clojure.string/split (name k) #"/")))
                                         (:changed-keys diff))))]
         [:div {:style {:margin-top "4px" :font-family "monospace" :font-size "11px" :color "#888"}}
          (str "System-time interval: " (:execution/_system_from (:then diff))
               " → " (:execution/_system_from (:now diff)))]
         [:div {:style {:margin-top "12px" :padding "12px" :border-radius "8px"
                        :border "1px solid #b58900" :background "#3a2f0a" :color "#f5d782"
                        :font-family "monospace" :font-size "11px"}}
          "Synthetic index-correction fixture: this history demonstrates XTDB system time. "
          "It does NOT represent mutation of the underlying authoritative artifact."]])
       (notice (str id " history not found. Run `bb explorer:seed` first."))))))

;; ---------------------------------------------------------------------------
;; 5. Bitemporal history (supporting proof)
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :hide}}
(panel
 "5 · Bitemporal history"
 (if-not (live?)
   (notice "XTDB unreachable or empty. Run `bb explorer:seed` first.")
   (let [hist (sort-by :execution/_system_from
                       (filter #(= "run-synthetic-correction" (:execution/_id %))
                               (ep/execution-runs-history ds)))]
     (if (seq hist)
       (htable ["_valid_from" "_valid_to" "_system_from" "_system_to" "outcome" "root"]
               (map (fn [r] [(:execution/_valid_from r) (:execution/_valid_to r)
                             (:execution/_system_from r) (:execution/_system_to r)
                             (:execution/status r) (:execution/bundle_root r)])
                    hist))
       (notice "correction run not found. Run `bb explorer:seed` first.")))))

;; ---------------------------------------------------------------------------
;; 6. Convergence / divergence
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :hide}}
(panel
 "6 · Comparable execution results"
 (if-not (live?)
   (notice "XTDB unreachable or empty. Run `bb explorer:seed` first.")
   (let [conv (nx/comparable-executions ds {:scenario-id "scenario-convergent"})
         div  (nx/comparable-executions ds {:scenario-id "scenario-divergent"})]
     (clerk/html
      [:div
       [:div {:style {:font-family "monospace" :font-size "11px" :color "#888" :margin-bottom "10px"}}
        "Comparison scope supplied explicitly. No semantic equivalence was inferred by XTDB."]
       (htable ["scope" "result roots" "verdict"]
               [["scenario-convergent" (str/join ", " (:result-roots conv))
                 (if (= 1 (count (:result-roots conv))) "CONVERGENT" "DIVERGENT")]
                ["scenario-divergent" (str/join ", " (:result-roots div))
                 (if (= 1 (count (:result-roots div))) "CONVERGENT" "DIVERGENT")]])
       [:div {:style {:margin-top "12px" :font-family "monospace" :font-size "11px" :color "#888"}}
        "XTDB does not prove the result. It makes already-rooted evidence conveniently discoverable and comparable."]]))))

;; ---------------------------------------------------------------------------
;; 7. Standing disclaimer
;; ---------------------------------------------------------------------------

^{::clerk/visibility {:code :show}}
(clerk/md
 "```text
Derived index
-------------
These rows are query projections over completed/rooted PRF artifacts.
XTDB does not replace artifact verification or confer authority.
```")