(ns scripts.check-notebook-visibility
  "CI guard for notebook presentation consistency.

   Enforces the collapsed-by-default visibility policy:
   - Every notebook must declare a namespace-level visibility default
     (:nextjournal.clerk/visibility on the ns form, or as the first map arg).
     Clerk otherwise defaults to showing all code (:show).
   - The namespace-level code default must NOT be :show (it must be :fold or :hide).
   - Namespace-level :hide is warned against (:fold preserves auditability).

   Usage: clojure -M -m check-notebook-visibility [dir]
   Exits non-zero if any hard violation is found."
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:gen-class))

(def notebooks-dir "notebooks")

(defn- ns-form-block
  "Return the ns form plus any ^{...} metadata block immediately preceding it."
  [text]
  (let [ns-idx (str/index-of text "(ns ")
        _ (assert ns-idx (str "no (ns form found"))
        ;; metadata block immediately before (ns : find the ^{ ... } that ends
        ;; (ignoring whitespace) exactly where (ns begins.
        meta-start (loop [i (dec ns-idx)]
                     (cond
                       (< i 0) nil
                       (Character/isWhitespace (nth text i)) (recur (dec i))
                       (= (nth text i) \}) i
                       :else (recur (dec i))))
        meta-open (when meta-start (str/last-index-of text "^{" meta-start))
        meta-block (if (and meta-open (>= meta-open 0))
                     (subs text meta-open ns-idx)
                     "")
        open-idx (str/index-of text "(" ns-idx)
        loop (fn loop [i depth]
               (if (>= i (count text))
                 (inc (count text))
                 (case (nth text i)
                   \( (loop (inc i) (inc depth))
                   \) (if (= depth 1) (inc i) (loop (inc i) (dec depth)))
                   (loop (inc i) depth))))
        close-idx (loop open-idx 0)]
    (str meta-block (subs text ns-idx close-idx))))

(defn- ns-visibility
  "Return {:code <kw> :result <kw>} parsed from the ns visibility metadata, or nil."
  [ns-text]
  (let [m (re-find #"(?:nextjournal\.clerk|::clerk)/visibility\s*\{([^}]*)\}"
                   ns-text)]
    (when m
      (let [body (second m)
            code (some-> (re-find #"\bcode\s*:\s*([^\s,]+)" body) second)
            result (some-> (re-find #"\bresult\s*:\s*([^\s,]+)" body) second)]
        {:code (keyword code) :result (keyword result)}))))

(defn- check-notebook [path]
  (let [text (slurp path)
        ns-text (ns-form-block text)
        vis (ns-visibility ns-text)
        filename (.getName (io/file path))]
    (cond
      (nil? vis)
      {:file filename :level :error :msg "no namespace-level visibility metadata (Clerk will show all code by default); add {:nextjournal.clerk/visibility {:code :fold}} to the ns form"}

      (= :show (:code vis))
      {:file filename :level :error :msg (str "namespace-level code default is :show; must be :fold or :hide")}

      (= :hide (:code vis))
      {:file filename :level :warn :msg "namespace-level code default is :hide (code not expandable); prefer :fold for auditability"}

      :else
      {:file filename :level :ok :msg (str "ns code default = " (:code vis))})))

(defn -main [& [dir]]
  (let [dir (or dir notebooks-dir)
        files (->> (io/file dir) (file-seq) (filter #(.isFile %))
                   (map #(.getPath %))
                   (filter #(str/ends-with? % ".clj"))
                   ;; only top-level notebooks; skip support subdirectories (util/ etc.)
                   (filter #(= (.getParent (io/file %)) dir))
                   sort)
        results (keep #(when-not (= :ok (:level %)) %) (map check-notebook files))
        errors (filter #(= :error (:level %)) results)
        warns (filter #(= :warn (:level %)) results)]
    (doseq [{:keys [file level msg]} (concat errors warns)]
      (println (str (case level :error "ERROR" :warn "WARN") "\t" file "\t" msg)))
    (println (str "Checked " (count files) " notebooks; "
                  (count errors) " error(s), " (count warns) " warning(s)."))
    (System/exit (if (seq errors) 1 0))))

(set! *warn-on-reflection* true)
