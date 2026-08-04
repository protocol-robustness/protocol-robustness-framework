(ns resolver-sim.notebook-support.common
  "Backward-compatibility wrapper. New code should use resolver-sim.manifest.common."
  (:require [resolver-sim.manifest.common :as mc]
            [resolver-sim.notebook-support.theme :refer [notebook-theme]]))

(defn safe-slurp [& args] (apply mc/safe-slurp args))
(defn read-json [& args] (apply mc/read-json args))
(defn read-edn [& args] (apply mc/read-edn args))

(defn safe-sort
  "Sort a collection, coercing non-String keys/values to strings first.
   Defensive against mixed-type data (e.g. JSON numbers + strings)."
  [coll]
  (sort (map str) coll))

(defn safe-sort-by
  "Sort a collection by key-fn, coercing non-String keys to strings first.
   Defensive against mixed-type data."
  [key-fn coll]
  (sort-by (fn [x] (str (key-fn x))) coll))

(defn safe-sorted-map
  "Create a sorted-map with a comparator that falls back to string comparison
   on ClassCastException. Defensive against mixed-type keys."
  [& kvs]
  (into (sorted-map-by (fn [a b]
                         (try
                           (compare a b)
                           (catch ClassCastException _
                             (compare (str a) (str b))))))
        (if (seq kvs) (apply hash-map kvs) {})))

(defn safe-render
  "Wraps a panel render fn in try/catch. Returns a hiccup error callout on failure.
   Notebook-only helper — not in manifest.common."
  [label f]
  (try
    (f)
    (catch Exception e
      [:div {:style {:background (:tone/red-row-bg notebook-theme) :border (str "1px solid " (:alert/red-border notebook-theme))
                     :borderRadius "4px" :padding "12px" :margin "8px 0"}}
       [:strong (str "⚠ " label " render error: ")]
       [:code (.getMessage e)]])))
