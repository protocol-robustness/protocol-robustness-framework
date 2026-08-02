(ns resolver-sim.demo.recorder
  "Demo recorder: generates a single asciicast recording for the entire demo
   with automatic pacing, section headers, and visible commands.

   Usage:
     bb demo:record <demo-id>                # auto-pacing (default)
     bb demo:record <demo-id> --mode manual  # manual step-through
     bb demo:validate <demo-id>

   Output under demos/<demo-id>/generated/:
     recordings/<demo-id>.cast  — single continuous asciicast
     outputs/<sid>.stdout.txt   — per-section stdout
     outputs/<sid>.stderr.txt   — per-section stderr
     artifacts/                 — canonical artifacts
     demo-run.json              — machine-readable metadata"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [resolver-sim.demo.spec :as demo-spec]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.vcs :as vcs]))

;; ── Helpers ─────────────────────────────────────────────────────────────────

(defn- timestamp []
  (str (java.time.Instant/now)))

(defn- git-commit []
  (vcs/commit-sha))

(defn- ensure-dir! [path]
  (let [f (io/file path)] (.mkdirs f) f))

(defn- shell-escape [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- write-file! [path s]
  (ensure-dir! (.getParentFile (io/file path)))
  (spit path s))

(defn- playback-cols [pb]
  (get-in pb [:terminal :cols] 120))

(defn- playback-rows [pb]
  (get-in pb [:terminal :rows] 36))

;; ── Unified shell script generation ─────────────────────────────────────────

(defn generate-demo-script
  "Generate one shell script for the entire demo.
   Returns {:path path :sections [{:sid :stdout-path :stderr-path :exit-code-fn}...]}."
  [spec out-dir]
  (let [id-str (name (demo-spec/demo-id spec))
        sections (demo-spec/sections spec)
        pb (demo-spec/playback-config spec)
        mode (:mode pb :auto)
        cols (playback-cols pb)
        rows (playback-rows pb)
        section-infos (atom [])]
    (ensure-dir! (str out-dir "/tmp"))
    (ensure-dir! (str out-dir "/outputs"))
    (let [lines (atom [])
          emit (fn [& xs] (swap! lines conj (apply str xs)))
          emit-pause (fn [ms] (if (= mode :manual)
                                (emit "read -r dummy_pause <<< ''  2>/dev/null || true")
                                (emit "sleep " (format "%.1f" (double (/ ms 1000.0))))))]
      (emit "#!/usr/bin/env bash")
      (emit "set +e")
      (emit "")
      (emit "# Terminal dimensions for stable recording")
      (emit "stty cols " cols " rows " rows " 2>/dev/null")
      (emit "")
      (emit "demo_exit=0")
      (emit "")
      ;; Iterate sections, build the script
      (doseq [section sections]
        (let [sid (name (:id section))
              title (:title section)
              explain (:explain section)
              cmd (:command section)
              text (:text section)
              cwd (:cwd section ".")
              disp (:display section :section-header)
              pause-ms (demo-spec/pause-ms section pb)
              stdout-path (str out-dir "/outputs/" sid ".stdout.txt")
              stderr-path (str out-dir "/outputs/" sid ".stderr.txt")]
          (swap! section-infos conj {:sid sid :title title :command cmd
                                     :stdout-path stdout-path
                                     :stderr-path stderr-path
                                     :expected-artifacts (:expected-artifacts section [])})
          ;; Section header / title card
          (emit)
          (emit "# ── " sid " ──")
          (emit "clear")
          (case disp
            :title-card
            (do (emit "echo")
                (emit "echo \"  ╔══════════════════════════════════════════════╗\"")
                (emit "echo \"  ║                                              ║\"")
                (emit "echo \"  ║  " title "  ║\"")
                (emit "echo \"  ║                                              ║\"")
                (emit "echo \"  ╚══════════════════════════════════════════════╝\"")
                (emit "echo")
                (when explain (emit "echo \"" explain "\""))
                (emit-pause pause-ms))
            :section-header
            (do (emit "echo")
                (emit "echo \"── " title " ──\"")
                (when explain (emit "echo \"" explain "\""))
                (emit-pause (:header-pause-ms pb 2000))))
          ;; Text-only section (no command to run)
          (when text
            (emit "echo")
            (emit "printf '%s\\n' " (shell-escape text))
            (emit-pause pause-ms))
          ;; Command section: show command, run, capture exit
          (when cmd
            (emit "echo")
            (emit "printf '$ %s\\n' " (shell-escape cmd))
            (emit "echo")
            (emit-pause (:command-pause-ms pb 2000))
            (emit "cd " (shell-escape cwd) " 2>/dev/null")
            ;; Run command with tee
            (emit "(" cmd ") > >(tee " stdout-path ")"
                  " 2> >(tee " stderr-path " >&2)")
            (emit "exit_code=$?")
            (emit "echo")
            (emit "printf '[exit code: %s]\\n' \"$exit_code\"")
            ;; If failed, print message but keep going
            (emit "if [ \"$exit_code\" -ne 0 ]; then")
            (emit "  echo \"[section " sid " failed — continuing recording]\"")
            (emit "  demo_exit=1")
            (emit "fi")
            (emit-pause pause-ms))))
      ;; After all sections: reproduction command + write script file
      (let [repro-cmd (str "bb demo:record " id-str)]
        (emit)
        (emit "# ── reproduce ──")
        (emit "echo")
        (emit "echo \"═══════════════════════════════════════════════\"")
        (emit "echo \"  Demo complete. Reproduce:\"")
        (emit "echo \"    " repro-cmd "\"")
        (emit "echo \"═══════════════════════════════════════════════\"")
        (emit "echo")
        (emit-pause (:final-pause-ms pb 6000)))
      ;; Write script file
      (let [script-path (str out-dir "/tmp/demo.sh")
            content (str/join "\n" @lines)]
        (write-file! script-path content)
        (.setExecutable (io/file script-path) true)
        {:path script-path :content content
         :sections @section-infos}))))

;; ── Artifact collection ─────────────────────────────────────────────────────

(def canonical-artifacts
  #{:test-summary :claimable-classification :test-run
    :validation-root :coverage})

(defn collect-artifacts [out-dir]
  (let [dst (ensure-dir! (str out-dir "/artifacts"))
        adir (evcfg/artifact-dir)
        names (into []
                    (keep (fn [aid]
                            (let [fname (evcfg/artifact-file aid)
                                  src (io/file adir fname)]
                              (when (and fname (.exists src))
                                (io/copy src (io/file dst fname))
                                fname))))
                    canonical-artifacts)
        ta (io/file adir "test-artifacts.json")]
    (when (.exists ta)
      (io/copy ta (io/file dst "test-artifacts.json")))
    (if (.exists ta) (conj names "test-artifacts.json") names)))

;; ── Asciinema recording ─────────────────────────────────────────────────────

(defn record-demo
  "Record the full demo as a single asciicast."
  [spec mode]
  (let [id-str (name (demo-spec/demo-id spec))
        demo-dir (str "demos/" id-str)
        out-dir (str demo-dir "/generated")
        pb (assoc (demo-spec/playback-config spec) :mode mode)
        cols (playback-cols pb)
        rows (playback-rows pb)
        cast-path (str out-dir "/recordings/" id-str ".cast")]
    (println (str "Recording demo: " (demo-spec/demo-title spec)
                  " (" (name mode) " mode, " cols "x" rows ")"))
    ;; Generate the unified script
    (let [script (generate-demo-script (assoc spec :playback pb) out-dir)
          script-path (:path script)
          section-infos (:sections script)]
      (ensure-dir! (str out-dir "/recordings"))
      ;; Record entire demo as one asciicast
      (let [{:keys [exit]} (shell/sh "asciinema" "rec" cast-path
                                     "--command" script-path
                                     "--cols" (str cols) "--rows" (str rows)
                                     "--overwrite" :dir ".")]
        (println (str "  Recorded: " cast-path))
        ;; Load per-section output content
        (let [sections (mapv (fn [si]
                               (let [stdout-content (try (slurp (:stdout-path si))
                                                         (catch Exception _ ""))
                                     stderr-content (try (slurp (:stderr-path si))
                                                         (catch Exception _ ""))]
                                 (assoc si
                                        :exit-code exit
                                        :stdout stdout-content
                                        :stderr stderr-content
                                        :recording-path cast-path)))
                             section-infos)
              any-fail? (some #(not (zero? (:exit-code %))) sections)
              artifacts (collect-artifacts out-dir)
              run-data {:demo/id id-str
                        :demo/title (demo-spec/demo-title spec)
                        :demo/spec spec
                        :run-id (timestamp)
                        :git-commit (git-commit)
                        :playback-mode (name mode)
                        :terminal (str cols "x" rows)
                        :recording cast-path
                        :sections (mapv #(select-keys %
                                                      [:sid :title :command :exit-code
                                                       :stdout-path :stderr-path
                                                       :recording-path :expected-artifacts])
                                        sections)
                        :artifacts artifacts}
              json-key (fn [k] (if (keyword? k)
                                 (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
                                 (str k)))]
          (write-file! (str out-dir "/demo-run.json")
                       (json/write-str run-data {:key-fn json-key :indent true}))
          (println (str "Wrote " out-dir "/demo-run.json"))
          (println (str "Demo complete." (if any-fail? " (some sections failed)" "")))
          any-fail?)))))

;; ── CLI ─────────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [mode-idx (.indexOf (vec args) "--mode")
        mode-val (if (>= mode-idx 0) (nth args (inc mode-idx) "auto") "auto")
        mode-kw (keyword mode-val)
        filtered (keep-indexed (fn [i x] (when-not (or (= x "--mode")
                                                       (= x mode-val))
                                           x)) args)
        args' (vec filtered)
        validate-only? (= (first args') "--validate")
        id (if validate-only? (second args') (first args'))]
    (when-not id
      (println "Usage: bb demo:record <id> [--mode auto|manual]")
      (System/exit 1))
    (let [spec (demo-spec/find-spec id)]
      (if-not spec
        (do (println (str "Demo spec not found: " id))
            (System/exit 1))
        (let [v (demo-spec/validate spec)]
          (if-not (:valid v)
            (do (println "Demo spec validation failed:")
                (doseq [e (:errors v)] (println (str "  - " e)))
                (System/exit 1))
            (if validate-only?
              (println "Demo spec is valid.")
              (let [failed? (record-demo spec mode-kw)]
                (System/exit (if failed? 1 0))))))))))
