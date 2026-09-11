(ns resolver-sim.demo.export
  "SVG export for asciicast demo recordings.
   Generates README-embeddable animated SVG from .cast files via svg-term-cli."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [resolver-sim.demo.spec :as demo-spec]))

;; ── Helpers ─────────────────────────────────────────────────────────────────

(defn- timestamp []
  (str (java.time.Instant/now)))

(defn- tool-version [cmd]
  (try
    (let [{:keys [exit out]} (shell/sh "bash" "-c" (str cmd " --version 2>/dev/null"))]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

;; ── SVG from cast ───────────────────────────────────────────────────────────

(defn svg-exists?
  "Check if a demo's SVG artifact exists and is non-empty."
  [demo-id]
  (let [svg-path (str "demos/" demo-id "/generated/recordings/" demo-id ".svg")]
    (and (.exists (io/file svg-path))
         (pos? (.length (io/file svg-path))))))

(defn cast-exists?
  "Check if a demo's cast recording exists."
  [demo-id]
  (let [cast-path (str "demos/" demo-id "/generated/recordings/" demo-id ".cast")]
    (.exists (io/file cast-path))))

(defn find-cast-path
  "Find the canonical cast file for a demo.
   Returns the path string or nil."
  [demo-id]
  (let [base "demos/%s/generated/recordings/%s"
        cast (str (format base demo-id demo-id) ".cast")]
    (when (.exists (io/file cast)) cast)))

;; ── SVG post-processing ─────────────────────────────────────────────────────

(defn fix-svg!
  "Post-process SVG for sharp rendering:
   - Fix SVG element height to match viewBox aspect ratio exactly
   - Add crispEdges / geometricPrecision rendering hints
   Does NOT change text coordinates or viewBox."
  [svg-path]
  (try
    (let [content (slurp svg-path)
          w-str (re-find #"(?<=width=\")[\d.]+(?=\")" content)
          h-str (re-find #"(?<=height=\")[\d.]+(?=\")" content)
          vb-str (re-find #"(?<=viewBox=\")[^\"]+(?=\")" content)
          vb-parts (when vb-str (mapv #(Float/parseFloat %) (clojure.string/split vb-str #"\s+")))
          vb-w (when (>= (count vb-parts) 3) (nth vb-parts 2))
          vb-h (when (>= (count vb-parts) 4) (nth vb-parts 3))]
      (if (and w-str vb-w vb-h (pos? vb-w))
        (let [w (Float/parseFloat w-str)
              corrected-h (long (Math/round (/ (* w (float vb-h)) (float vb-w))))
              fixed (-> content
                        (str/replace (re-pattern (str "height=\"" h-str "\""))
                                     (str "height=\"" corrected-h "\""))
                        (str/replace
                         "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                         (str "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
                              " style=\"shape-rendering:crispEdges;text-rendering:geometricPrecision\"")))]
          (spit svg-path fixed)
          (println (str "    Fixed SVG for sharp rendering (" (int w) "x" corrected-h ")")))
        (println (str "    Warning: could not fix SVG dimensions"))))
    (catch Exception e
      (println (str "    Warning: SVG post-processing skipped: " (.getMessage e))))))

;; ── SVG generation ──────────────────────────────────────────────────────────

(defn cast->svg
  "Generate an animated SVG from an asciicast recording.
   Uses svg-term-cli via stdin (the --cast flag doesn't support local files reliably).
   Returns {:svg-path :ok? :error}."
  [cast-path svg-path cols rows]
  (let [svg-dir (.getParentFile (io/file svg-path))]
    (.mkdirs svg-dir)
    (println (str "  Generating SVG: " svg-path))
    (try
      (let [result (shell/sh "bash" "-c"
                             (str "cat '" cast-path "'"
                                  " | npx -y svg-term-cli --out '" svg-path "'"
                                  " --window --width " (int (or cols 120))
                                  " --height " (int (or rows 36))
                                  " 2>/dev/null"))
            exit (:exit result)
            ok? (zero? exit)
            svg-size (when ok? (.length (io/file svg-path)))
            valid? (and ok? (pos? svg-size))]
        (if valid?
          (do
            (println (str "    Wrote " svg-size " bytes"))
            ;; Post-process for sharp rendering
            (fix-svg! svg-path))
          (println (str "    SVG generation failed (exit " exit ")")))
        {:svg-path svg-path :ok? valid?
         :svg-size svg-size
         :error (when-not valid? (or (:stderr result) "unknown"))})
      (catch Exception e
        {:svg-path svg-path :ok? false :error (.getMessage e)}))))

;; ── Export ──────────────────────────────────────────────────────────────────

(defn export-svg
  "Export a demo's recording to SVG.
   Updates demo-run.json with SVG metadata.
   Returns true if export succeeded."
  [demo-id]
  (let [id-str (name demo-id)
        cast-path (find-cast-path id-str)]
    (if-not cast-path
      (do (println (str "No recording found for: " id-str
                        ". Run bb demo:record " id-str " first."))
          false)
      (let [;; Read cols/rows from demo spec playback config, default 96x36
            spec-path (str "demos/" id-str "/demo.edn")
            spec (try (demo-spec/find-spec id-str) (catch Exception _ nil))
            pb (when spec (demo-spec/playback-config spec))
            cols (get-in pb [:terminal :cols] 120)
            rows (get-in pb [:terminal :rows] 36)
            svg-path (str (.getParentFile (io/file cast-path)) "/" id-str ".svg")
            result (cast->svg cast-path svg-path cols rows)
            ok? (:ok? result)]
        ;; Update demo-run.json with SVG metadata
        (let [run-json-path (str "demos/" id-str "/generated/demo-run.json")]
          (when (.exists (io/file run-json-path))
            (try
              (let [data (json/read-str (slurp run-json-path) :key-fn keyword)
                    json-key (fn [k] (if (keyword? k)
                                       (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
                                       (str k)))
                    updated (assoc data
                                   :svg (assoc (:svg data)
                                               :format "animated-svg"
                                               :path svg-path
                                               :size (:svg-size result)
                                               :generated-at (timestamp)
                                               :source-cast cast-path
                                               :terminal (str cols "x" rows)
                                               :command "svg-term-cli --window"
                                               :tool-version (tool-version "npx svg-term-cli"))
                                   :readme/embed (str "![" id-str "](" svg-path ")"))]
                (spit run-json-path
                      (json/write-str updated {:key-fn json-key :indent true})))
              (catch Exception e
                (println (str "  Warning: could not update demo-run.json: "
                              (.getMessage e)))))))
        ok?))))

(defn export-all
  "Export SVG for all demos found under demos/."
  []
  (let [demo-dirs (filter #(.isDirectory (io/file %))
                          (map #(str "demos/" %)
                               (.list (io/file "demos"))))]
    (run! (fn [d]
            (let [id (last (str/split d #"/"))]
              (println (str "Exporting: " id))
              (export-svg (keyword id))))
          demo-dirs)))

(defn -main [& args]
  (let [id (first args)]
    (when-not id
      (println "Usage: bb demo:export-svg <demo-id> | bb demo:export-svg --all")
      (System/exit 1))
    (if (= id "--all")
      (export-all)
      (let [ok? (export-svg (keyword id))]
        (System/exit (if ok? 0 1))))))
