(ns resolver-sim.notebook-support.manifest.diff
  "Manifest diff engine — compare two run manifests and produce a
  structured diff report.

  Covers: suite params, git-commit, capabilities-resolved,
  artifact hashes, status, duration."
  (:require [resolver-sim.notebook-support.manifest.hash :as mhash]
            [resolver-sim.hash.canonical :as hc]
            [clojure.set :as set]))

;; ── field extractors ──────────────────────────────────────────────────────────

(defn- comparable-fields [run-data]
  (let [{:keys [manifest summary registry]} run-data]
    {:git-commit        (get-in manifest [:framework :git_commit])
     :suite-id          (get-in manifest [:suite :id])
     :scenario          (or (get-in manifest [:suite :scenario])
                            (get-in manifest [:suite :selector]))
     :status            (get summary :overall_status)
     :duration-ms       (get manifest :duration_ms)
     :triggered-by      (get manifest :triggered_by)
     :capabilities      (get manifest :capabilities_resolved {})
     :artifact-count    (count (get registry :artifacts []))
     :artifact-hashes   (mhash/artifact-hashes registry)
     :canonical-hash    (when manifest (mhash/canonical-hash manifest))}))

;; ── scalar diff ───────────────────────────────────────────────────────────────

(defn- diff-scalars [a b fields]
  (->> fields
       (keep (fn [k]
               (let [av (get a k) bv (get b k)]
                 (when (not= av bv)
                   {:field k :before av :after bv}))))
       vec))

;; ── map diff ─────────────────────────────────────────────────────────────────

(defn- diff-map [a b prefix]
  (let [all-keys (set/union (set (keys a)) (set (keys b)))]
    (->> all-keys
         (keep (fn [k]
                 (let [av (get a k) bv (get b k)]
                   (when (not= av bv)
                     {:field (keyword (str (name prefix) "/" (name k)))
                      :before av :after bv}))))
         (sort-by (comp name :field))
         vec)))

;; ── public API ────────────────────────────────────────────────────────────────

(defn diff-runs
  "Compare two loaded run data maps (each {:manifest ... :summary ... :registry ...}).
  Returns {:scalar-changes [...] :capability-changes [...] :hash-changes [...]
           :hash-match bool :status-changed bool}."
  [run-a run-b]
  (let [fa (comparable-fields run-a)
        fb (comparable-fields run-b)
        scalar-keys [:git-commit :suite-id :scenario :status :duration-ms :triggered-by :artifact-count]
        scalars  (diff-scalars fa fb scalar-keys)
        caps     (diff-map (:capabilities fa) (:capabilities fb) :capability)
        hashes   (diff-map (:artifact-hashes fa) (:artifact-hashes fb) :artifact)]
    {:scalar-changes    scalars
     :capability-changes caps
     :hash-changes      hashes
     :hash-match        (hc/intent-hash= (:canonical-hash fa) (:canonical-hash fb))
     :status-changed    (not= (:status fa) (:status fb))
     :run-a-id          (get-in run-a [:manifest :run_id])
     :run-b-id          (get-in run-b [:manifest :run_id])}))

(defn diff-summary
  "Return a human-readable one-line summary of a diff result."
  [{:keys [scalar-changes capability-changes hash-changes hash-match]}]
  (let [total (+ (count scalar-changes) (count capability-changes) (count hash-changes))]
    (if (zero? total)
      (str "No differences" (if hash-match " — hashes match" " (structural hash mismatch)"))
      (str total " change(s): "
           (count scalar-changes) " scalar, "
           (count capability-changes) " capability, "
           (count hash-changes) " artifact hash"
           (if hash-match "; canonical hashes match" "; canonical hashes differ")))))

(defn render-diff
  "Return a hiccup table of diff results for Clerk rendering."
  [{:keys [scalar-changes capability-changes hash-changes
           hash-match run-a-id run-b-id]}]
  (let [all-changes (concat scalar-changes capability-changes hash-changes)
        row (fn [{:keys [field before after]}]
              [:tr
               [:td {:style {:fontFamily "monospace" :padding "4px 8px" :color "#94a3b8"}}
                (name field)]
               [:td {:style {:padding "4px 8px" :color "#ef4444" :fontFamily "monospace"}}
                (str before)]
               [:td {:style {:padding "4px 8px" :color "#22c55e" :fontFamily "monospace"}}
                (str after)]])]
    [:div {:style {:fontFamily "'JetBrains Mono', monospace" :fontSize "0.85em"}}
     [:div {:style {:marginBottom "8px" :color "#94a3b8"}}
      (str run-a-id " → " run-b-id)]
     (if (empty? all-changes)
       [:div {:style {:color "#22c55e"}} "✅ No differences detected"
        (when hash-match " — canonical hashes match")]
       [:table {:style {:width "100%" :borderCollapse "collapse"}}
        [:thead
         [:tr
          [:th {:style {:textAlign "left" :padding "4px 8px" :borderBottom "1px solid #334155"}} "Field"]
          [:th {:style {:textAlign "left" :padding "4px 8px" :borderBottom "1px solid #334155" :color "#ef4444"}} "Before"]
          [:th {:style {:textAlign "left" :padding "4px 8px" :borderBottom "1px solid #334155" :color "#22c55e"}} "After"]]]
        [:tbody (map row all-changes)]])]))
