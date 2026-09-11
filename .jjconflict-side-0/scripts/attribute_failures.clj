#!/usr/bin/env bb
;; Attribute failing test targets to the merge-parent changes that most likely
;; caused them.
;;
;; Usage:
;;   bb triage:attribute [artifact-dir] [--at REV] [--baseline REV] [--json]
;;   bb triage:attribute [artifact-dir] --rerun-failed [--only t1,t2] [--max-pairs N]
;;                       [--baseline REV]
;;   bb triage:guard [--baseline REV]
;;
;; Evidence model:
;;   candidate contribution = candidate tree - common integration baseline tree
;;   (NOT candidate - its immediate parent, so multi-commit agent branches are
;;   attributed in full). The selected baseline is printed with every report.
;;
;; Confidence tiers per failing target x candidate:
;;   HIGH   failing test/source path directly changed by candidate
;;   MEDIUM failing namespace or scenario id maps into dirs/files touched
;;   LOW    weak token overlap only (suite names etc.)
;;   NONE   no candidate-specific evidence
;;
;; Verdicts: HIGH-CONFIDENCE CONTRIBUTOR (HIGH only), MULTIPLE PLAUSIBLE
;; CONTRIBUTORS, PLAUSIBLE CONTRIBUTOR (MEDIUM), UNATTRIBUTED.
;; Attribution applies to recorded candidate TREES, never to authorship or blame.
;;
;; Divergence labels: a merged file differing from every parent is reported as
;; COMPOSITE-MERGE (legitimate combined edits also look like this);
;; -CONFLICT-MARKERS is appended when unresolved-conflict markers are present.
;;
;; --rerun-failed replays failed targets against: stage 0 the integration
;; baseline, stage 1 each parent tip, stage 2 pairwise merges, inside throwaway
;; jj workspaces. Verdict taxonomy:
;;   BASELINE_FAILURE / SINGLE_PARENT_FAILURE / PAIRWISE_INTERACTION /
;;   INTEGRATION_RESOLUTION_SUSPECT / HIGHER_ORDER_INTERACTION /
;;   UNREPRODUCED_OR_NONDETERMINISTIC / MERGE_CONFLICT_UNTESTED /
;;   INFRASTRUCTURE_FAILURE.
(ns attribute-failures
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def repo-dir (System/getProperty "user.dir"))

(def default-config
  {:bookmark-glob "agent-*"
   :impl-roots ["src" "test" "protocols_src"]
   :tmp-root "/tmp/opencode"
   :baseline "integration@origin"})

(def config
  (merge default-config
         (try (let [f (io/file repo-dir ".triage.edn")]
                (when (.exists f) (edn/read-string (slurp f))))
              (catch Exception _ {}))))

(def tmp-root
  (or (System/getenv "TRIAGE_TMP_ROOT") (:tmp-root config)))

(def default-max-pairs 15)

(defn sh* [dir & args]
  (try
    (apply sh/sh "jj" (concat args [:dir dir]))
    (catch Exception e
      {:exit -1 :err (.getMessage e) :out ""})))

(defn pairs [xs]
  (when (> (count xs) 1)
    (vec (for [i (range (count xs))
               j (range (inc i) (count xs))]
           [(nth xs i) (nth xs j)]))))

(defn jj-out
  ([dir & args]
   (let [{:keys [exit out]} (apply sh* dir args)]
     (when (zero? exit) out))))

(defn jj [& args]
  (apply jj-out repo-dir args))

(defn usage []
  (str/join "\n"
            ["usage:"
             "  bb triage:attribute [artifact-dir] [--at REV] [--baseline REV] [--json]"
             "  bb triage:attribute [artifact-dir] --rerun-failed [--only unit,suites]"
             "                      [--max-pairs N] [--repetitions N] [--require-reproductions N]"
             "  bb triage:guard [--baseline REV] [--bookmark-glob GLOB] [--impl-roots a,b,c]"
             "                  [--require-nonempty-tip]"
             ""
             "  explicit candidates (overrides provenance/bookmarks):"
             "  bb triage:attribute --candidate api=REV --candidate storage=REV ..."
             ""
             "  artifact-dir defaults to newest results/test-artifacts-* containing test-summary.json"
             "  --at REV        revision considered the integrated/tested tree (default:"
             "                  provenance tested-revision, else current @)"
             "  --baseline REV  contribution/descent baseline; default fork_point(candidates),"
             "                  then :baseline from .triage.edn ('integration@origin')"
             "  --only t1,t2    restrict --rerun-failed to these targets"
             ""
             "  configuration: .triage.edn at repo root may set :bookmark-glob, :impl-roots,"
             "  :tmp-root, :baseline. Env TRIAGE_TMP_ROOT overrides the temp workspace root."
             ""
             "  exit codes: 0 attribution/guard completed · 1 failures found ·"
             "              nonzero from assertions = invalid input"]))

(defn parse-args [args]
  (loop [xs args, acc {:dirs [] :flags {}}]
    (if-let [x (first xs)]
      (cond
        (= "--rerun-failed" x)         (recur (rest xs) (assoc-in acc [:flags :rerun-failed] true))
        (= "--guard" x)                (recur (rest xs) (assoc-in acc [:flags :guard] true))
        (= "--json" x)                 (recur (rest xs) (assoc-in acc [:flags :json] true))
        (= "--help" x)                 (recur (rest xs) (assoc-in acc [:flags :help] true))
        (= "--require-nonempty-tip" x) (recur (rest xs) (assoc-in acc [:flags :require-nonempty-tip] true))
        (= "--at" x)                   (recur (drop 2 xs) (assoc-in acc [:flags :at] (second xs)))
        (= "--baseline" x)             (recur (drop 2 xs) (assoc-in acc [:flags :baseline] (second xs)))
        (= "--max-pairs" x)            (recur (drop 2 xs) (assoc-in acc [:flags :max-pairs] (second xs)))
        (= "--only" x)                 (recur (drop 2 xs) (assoc-in acc [:flags :only] (second xs)))
        (= "--bookmark-glob" x)        (recur (drop 2 xs) (assoc-in acc [:flags :bookmark-glob] (second xs)))
        (= "--impl-roots" x)           (recur (drop 2 xs) (assoc-in acc [:flags :impl-roots]
                                                                      (vec (str/split (second xs) #","))))
        (= "--repetitions" x)          (recur (drop 2 xs) (assoc-in acc [:flags :repetitions]
                                                                     (some-> (second xs) parse-long)))
        (= "--require-reproductions" x) (recur (drop 2 xs) (assoc-in acc [:flags :require-reproductions]
                                                                      (some-> (second xs) parse-long)))
        (= "--candidate" x)
        (let [[label rev] (str/split (second xs) #"=" 2)]
          (if (and label rev)
            (recur (drop 2 xs) (update-in acc [:flags :candidates] assoc label rev))
            (throw (ex-info "--candidate expects label=rev" {}))))
        :else                          (recur (rest xs) (update acc :dirs conj x)))
      acc)))

;; ---------------------------------------------------------------------------
;; Artifact discovery + summary

(defn latest-artifact-dir []
  (let [root (io/file repo-dir "results")]
    (when (.isDirectory root)
      (->> (file-seq root)
           (filter #(and (.isDirectory %)
                         (re-matches #"test-artifacts-.*" (.getName %))))
           (filter #(.exists (io/file % "test-summary.json")))
           (sort-by #(.getName %))
           last
           .getPath))))

(defn load-summary [dir]
  (json/parse-string (slurp (io/file dir "test-summary.json")) true))

(defn failing-targets [summary only]
  (let [failed (->> (:targets summary)
                    (remove #(= "pass" (:status %)))
                    (mapv :target))]
    (if only
      (let [whitelist (set (str/split only #","))]
        (filterv whitelist failed))
      failed)))

(defn log-path-for [dir summary target]
  (let [lf (:log_file (some #(when (= target (:target %)) %) (:targets summary)))]
    (when lf
      (let [f     (io/file lf)
            base  (.getParentFile (.getParentFile (io/file dir)))
            cands (cond-> [(if (.isAbsolute f) f (io/file repo-dir f))
                           (io/file dir (.getName f))]
                    base (conj (io/file base lf)))]
        (some->> cands
                 (filter #(.exists %))
                 first
                 .getPath)))))

;; ---------------------------------------------------------------------------
;; Failure-token extraction from logs

(def ^:private token-patterns
  [#"FAIL in \(([^)]+)\) \(([^):]+):\d+\)"
   #"^\s{2}([a-z][a-z0-9.-]*(?:-test))\s*$"
   #"(\S+) \[:fail\]"
   #":scenario-id \"([^\"]+)\""
   #"([A-Za-z0-9_/.-]+\.(?:clj|cljc|edn|py|sh))"])

(defn extract-tokens [text]
  (->> token-patterns
       (mapcat (fn [pat]
                 (->> (re-seq pat text)
                      (mapcat rest))))
       (map str)
       set))

(defn normalize-token [s]
  (-> s str/lower-case (str/replace #"[-_]" "-")))

(defn token-variants [s]
  (let [low (str/lower-case s)]
    (distinct [low
               (normalize-token low)
               (-> low normalize-token (str/replace "." "/"))
               (str/replace low "." "/")])))

(defn path-suffix-match? [token path]
  (let [p (normalize-token path)]
    (some #(or (= p %)
               (str/ends-with? p (str "/" %)))
          (token-variants token))))

(defn token-matches-path? [token path]
  (let [p (normalize-token path)]
    (some #(str/includes? p %) (token-variants token))))

(defn ns-dir-prefix [token]
  (when (and (str/includes? token ".")
             (not (re-find #"\.[a-z]+$" token)))
    (-> token
        (str/replace #"^(resolver[_-]sim|prf)\." "")
        (str/replace "." "/")
        normalize-token)))

(defn classify-match [token path]
  (cond
    (and (re-find #"\.(clj|cljc|edn|py|sh)$" (str/lower-case token))
         (path-suffix-match? token path))
    :high

    (when-let [prefix (ns-dir-prefix token)]
      (some #(str/starts-with? % prefix)
            (map normalize-token (conj [] path))))
    :medium

    (re-matches #"(?i)s\d+.*" token)
    :low

    :else :low))

;; ---------------------------------------------------------------------------
;; Provenance + candidate changes

(defn load-provenance [artifact-dir]
  (let [f (io/file artifact-dir ".provenance.json")]
    (when (.exists f)
      (json/parse-string (slurp f) true))))

(defn candidates-from-provenance [artifact-dir]
  (when-let [{:keys [parents]} (load-provenance artifact-dir)]
    (seq (->> parents
              (remove :empty?)
              (mapv (fn [c]
                      {:label      (or (first (:bookmarks c)) (:change_id c))
                       :change-id  (:change_id c)
                       :commit-id  (:commit_id c)
                       :empty?     false}))))))

(defn glob->bookmark-regex [glob]
  (let [parts (str/split glob #"\*" -1)]
    (re-pattern (str "^\\s*("
                     (str/join "[^:\\s]+" (map java.util.regex.Pattern/quote parts))
                     "):\\s+([a-z0-9]+)"))))

(defn candidates-from-bookmarks
  ([] (candidates-from-bookmarks (:bookmark-glob config)))
  ([bookmark-glob]
   (let [rx (glob->bookmark-regex bookmark-glob)]
     (letfn [(parse [line]
               (when-let [[_ name cid] (re-find rx line)]
                 {:label name
                  :change-id cid
                  :empty? (= "true"
                             (str/trim (or (jj "log" "-r" cid "--no-graph"
                                            "-T" "if(empty, \"true\", \"false\")")
                                           "")))}))]
       (when-let [out (jj "bookmark" "list")]
         (seq (doall (keep parse (str/split-lines out)))))))))

(defn resolve-candidates [artifact-dir flags]
  (let [prov?    (boolean (load-provenance artifact-dir))
        explicit (seq (:candidates flags))
        cands    (cond
                   explicit (mapv (fn [[label rev]]
                                    {:label label :change-id rev :empty? false})
                                  explicit)
                   :else (or (candidates-from-provenance artifact-dir)
                             (candidates-from-bookmarks
                              (or (:bookmark-glob flags)
                                  (:bookmark-glob config)))))]
    {:source-tag (cond
                   explicit "explicit --candidate list"
                   prov?    ".provenance.json parents"
                   cands    (str "bookmark fallback (" (:bookmark-glob config) ")"))
     :candidates (vec cands)}))

(defn tested-revision [artifact-dir flags]
  (or (:at flags)
      (:commit_id (:tested_revision (load-provenance artifact-dir)))
      "@"))

(defn resolve-baseline [flags candidates]
  (or (when-let [b (:baseline flags)]
        {:ref b :id b})
      ;; fork_point of the candidates is the precise common baseline
      (let [revs (str/join "|" (map :change-id candidates))]
        (when (seq revs)
          (let [out (str/trim (or (jj "log" "-r" (str "fork_point(" revs ")")
                                      "--no-graph"
                                      "-T" "commit_id.short()")
                                  ""))
                lines (str/split-lines out)]
            (when (= 1 (count lines))
              (when-let [id (not-empty (first lines))]
                {:ref (str "fork_point(" revs ")") :id id})))))
      ;; configured fallback only when fork_point is ambiguous/unresolvable
      (when-let [b (:baseline config)]
        {:ref b :id b})))

(defn commit-short [c]
  (or (:commit-id c)
      (str/trim (or (jj "log" "-r" (:change-id c) "--no-graph"
                     "-T" "commit_id.short()")
                    (:change-id c)))))

(defn contribution-diff
  "Paths changed by candidate relative to baseline (falls back to the
  candidate-vs-immediate-parent diff when no baseline can be resolved).
  Returns {:base-used ... :changes ({:op :path})}"
  [baseline cand]
  (let [[args base-used]
        (if baseline
          [["--from" (:id baseline) "--to" (:change-id cand)] (:id baseline)]
          [["-r" (:change-id cand)] :immediate-parent])
        out (apply jj (concat ["diff"] args ["--summary" "--no-pager"]))
        changes (keep (fn [line]
                        (when-let [[_ op path] (re-find #"^(A|M|D|R|CC|DD)\s+(.+)$" line)]
                          {:op op :path path}))
                      (str/split-lines (or out "")))]
    {:base-used base-used :changes (vec changes)}))

(defn blob-info [rev path]
  (try
    (let [r (sh/sh "jj" "file" "show" "-r" rev path :dir repo-dir)]
      (when (zero? (:exit r))
        (let [content ^String (:out r)
              digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                              (.getBytes content "UTF-8"))]
          {:sha256  (->> digest (map #(format "%02x" %)) (apply str))
           :markers? (str/includes? content "<<<<<<<")})))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Static report

(defn dedupe-evidence [evs]
  (reduce (fn [acc e]
            (if (some #(and (= (:path %) (:path e)) (= (:token %) (:token e))) acc)
              acc (conj acc e)))
          [] evs))

(defn ambiguous-basename? [all-hits tok]
  (and (not (str/includes? tok "/"))
       (> (->> all-hits
               (filter #(= tok (:token %)))
               (map #(-> % :path fs/parent str))
               distinct
               count)
          1)))

(defn analyze-target [tokens-by-target contributions]
  (into (sorted-map)
        (for [[t toks] tokens-by-target]
          (let [per-cand
                (into {}
                      (map (fn [{:keys [label changes]}]
                             (let [evs (dedupe-evidence
                                        (for [e changes
                                              :let [tok (some #(when (token-matches-path? % (:path e)) %) toks)]
                                              :when tok]
                                          {:path (:path e) :op (:op e) :token tok
                                           :level (classify-match tok (:path e))}))]
                               [label evs]))
                           contributions))
                all-hits (flatten (vals per-cand))
                per-cand'
                (into {}
                      (map (fn [[lbl evs]]
                             [lbl (mapv (fn [e]
                                          (if (ambiguous-basename? all-hits (:token e))
                                            (update e :level #(if (= :high %) :medium %))
                                            e))
                                        evs)])
                           per-cand))]
            [t {:tokens toks :per-candidate per-cand'}]))))

(defn print-static [{:keys [source-tag tested-rev baseline failed candidates
                            contributions matrix tokens]} divs]
  (println "=== triage:attribute ===")
  (println "note: attribution applies to recorded candidate trees, not authorship or blame.")
  (println (format "candidate source : %s" source-tag))
  (println (format "tested revision  : %s" tested-rev))
  (println (if baseline
             (format "baseline         : %s (%s)" (:id baseline) (:ref baseline))
             "baseline         : UNRESOLVED — using candidate-vs-immediate-parent diffs (weaker evidence)"))
  (println (format "failing targets  : %s" (str/join ", " failed)))
  (doseq [{:keys [label commit-id base-used changes]} contributions]
    (println (format "%n-- contribution of %-12s %s (%d paths, vs %s)"
                     label (or commit-id "?") (count changes) base-used))
    (doseq [e (sort-by :path changes)]
      (println (str "   " (:op e) " " (:path e)))))
  (println "\n=== failure -> candidate matrix ===")
  (doseq [[t {:keys [tokens per-candidate]}] matrix]
    (println (format "%n%s  [%d failure tokens]" t (count tokens)))
    (doseq [lbl candidates]
      (let [evs (get per-candidate lbl)
            by-level (group-by :level evs)]
        (println (format "   %-12s HIGH:%-2d MEDIUM:%-2d LOW:%-2d"
                         lbl (count (get by-level :high))
                         (count (get by-level :medium))
                         (count (get by-level :low))))
        (doseq [e (take 3 (get by-level :high))]
          (println (format "      HIGH  %-4s %-28s %s" (:op e) (:token e) (:path e))))
        (doseq [e (take 2 (get by-level :medium))]
          (println (format "      MED   %-4s %-28s %s" (:op e) (:token e) (:path e))))))
    (let [with-high (for [[lbl evs] per-candidate
                          :when (some #(= :high (:level %)) evs)]
                      lbl)
          with-med  (for [[lbl evs] per-candidate
                          :when (and (not-any? #(= :high (:level %)) evs)
                                     (some #(= :medium (:level %)) evs))]
                      lbl)]
      (cond
        (= 1 (count with-high))
        (println (format "   => HIGH-CONFIDENCE CONTRIBUTOR: %s" (first with-high)))
        (> (count with-high) 1)
        (println (format "   => MULTIPLE PLAUSIBLE CONTRIBUTORS (HIGH): %s"
                         (str/join ", " with-high)))
        (= 1 (count with-med))
        (println (format "   => PLAUSIBLE CONTRIBUTOR (MEDIUM): %s" (first with-med)))
        (> (count with-med) 1)
        (println (format "   => MULTIPLE PLAUSIBLE CONTRIBUTORS (MEDIUM): %s"
                         (str/join ", " with-med)))
        :else
        (println "   => UNATTRIBUTED at file/namespace level"))))
  (println "\n=== multi-parent file divergence (vs tested tree) ===")
  (if (empty? divs)
    (println "(no files touched by multiple parents)")
    (do
      (println "note: COMPOSITE-MERGE includes legitimate merges combining edits")
      (println "      from several parents; -CONFLICT-MARKERS indicates committed")
      (println "      unresolved conflict markers.")
      (doseq [{:keys [path label matching-parents ops markers?]} divs]
        (println (format "%-28s %-52s matches:%-16s ops:[%s]%s"
                         label path
                         (if (seq matching-parents) (str/join "," matching-parents) "<none>")
                         (str/join "," (for [[lbl op] (sort-by first ops)]
                                         (str (subs (str lbl) 0 1) ":" op)))
                         (if markers? " !!CONFLICT-MARKERS" "")))))))

(defn divergence-report [contributions tested-rev]
  (let [op-maps (into {}
                      (for [{:keys [label changes]} contributions]
                        [label (into {} (map (juxt :path :op) changes))]))
        counts  (apply merge-with + (for [[_ m] op-maps] (frequencies (keys m))))
        shared  (->> counts (filter #(> (val %) 1)) (map first) sort)]
    (for [path shared]
      (let [hashes (into {} (for [[lbl _] op-maps]
                              [lbl (blob-info (first (for [{:keys [label change-id]} contributions
                                                           :when (= label lbl)]
                                                       change-id)) path)]))
            tested-hash (blob-info tested-rev path)
            matches (->> hashes
                         (filter (fn [[_ i]] (and i (:sha256 i) (= (:sha256 i) tested-hash))))
                         (mapv first))
            markers? (boolean (some-> tested-hash :markers?))]
        {:path path
         :label (cond
                  (nil? tested-hash) "ABSENT-IN-TREE"
                  markers?           "COMPOSITE-MERGE-CONFLICT-MARKERS"
                  (empty? matches)   "COMPOSITE-MERGE"
                  :else              "MATCHES-PARENT(S)")
         :matching-parents matches
         :ops (into (sorted-map) (for [[lbl m] op-maps] [lbl (get m path "ABSENT")]))
         :markers? markers?}))))

;; ---------------------------------------------------------------------------
;; Rerun-failed (dynamic disambiguation)

(def cleanup-registry (atom #{}))

(defn sanitize-name [s]
  (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9._-]" "-")))

(defn run-target-in [ws-dir target]
  (let [env (merge (into {} (System/getenv))
                   {"PRF_ARTIFACT_DIR" (str ws-dir "/results/artifacts")})]
    (:exit (sh/sh "bash" "./scripts/test.sh" target :dir ws-dir :env env))))

(defn make-workspace! [prefix label change-ref stamp]
  (let [name  (format "%s-%s-%s-%04x" prefix (sanitize-name label) stamp (rand-int 0xffff))
        path  (str tmp-root "/" name)]
    (fs/create-dirs tmp-root)
    (when (fs/exists? path) (fs/delete-tree path))
    (let [{:keys [exit err]} (sh* repo-dir "workspace" "add" path "-r" change-ref)]
      (when-not (zero? exit)
        (throw (ex-info (str "workspace add failed: " err) {}))))
    (swap! cleanup-registry conj name)
    path))

(defn drop-workspace! [name]
  (sh* repo-dir "workspace" "forget" name)
  (let [path (str tmp-root "/" name)]
    (when (fs/exists? path)
      (let [canon (try (str (fs/canonicalize path)) (catch Exception _ ""))]
        (when (str/starts-with? canon tmp-root)
          (fs/delete-tree path))))
    (swap! cleanup-registry disj name)))

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn []
                             (doseq [w @cleanup-registry]
                               (try (drop-workspace! w) (catch Exception _))))))

(defn ws-conflict? [ws-dir]
  (= "true" (str/trim (or (apply jj-out ws-dir
                                ["log" "-r" "@" "--no-graph"
                                 "-T" "if(conflict, \"true\", \"false\")"])
                          ""))))

(defn rerun-one!
  "Runs one (candidate-set x target) cell --repetitions times.
   Records {:exits [..] :conflict b}."
  [results key prefix label change-refs targets stamp repetitions]
  (let [path (make-workspace! prefix label (first change-refs) stamp)]
    (try
      (when (> (count change-refs) 1)
        (let [{:keys [exit err]} (apply sh* path "new" change-refs)]
          (when-not (zero? exit)
            (throw (ex-info (str "jj new merge failed: " err) {})))))
      (let [conflict (ws-conflict? path)]
        (doseq [t targets]
          (if conflict
            (do
              (print (format "[%s @%s] %-24s ... " label (last change-refs) t))
              (flush)
              (swap! results assoc-in [key t] {:exits [] :conflict true})
              (println "MERGE_CONFLICT_UNTESTED"))
            (let [exits (vec (for [rep (range repetitions)]
                               (do
                                 (print (format "[%s @%s] %-24s%s ... "
                                                label (last change-refs) t
                                                (if (> repetitions 1)
                                                  (format " rep %d/%d" (inc rep) repetitions)
                                                  "")))
                                 (flush)
                                 (:exit (run-target-in path t)))))]
              (swap! results assoc-in [key t] {:exits exits :conflict false})
              (doseq [[i code] (map-indexed vector exits)]
                (println
                 (let [rs (case code
                            0 "PASS"
                            127 "FAIL(exit=127 INFRASTRUCTURE?)"
                            (str "FAIL(exit=" code ")"))]
                   (if (> repetitions 1)
                     (format "  rep %d/%d: %s" (inc i) repetitions rs)
                     rs))))))))
      (finally (drop-workspace! (fs/file-name path))))))

(defn outcome-kind [cell]
  (cond
    (nil? cell) :not-run
    (:conflict cell) :conflict
    (some #(or (nil? %) (= 127 %)) (:exits cell)) :infrastructure
    (pos? (count (filter pos? (:exits cell)))) :failure
    :else :pass))

(defn cell-fail-count [cell]
  (count (filter pos? (:exits cell))))

(defn rerun-failed [artifact-dir flags]
  (let [summary    (load-summary artifact-dir)
        targets    (failing-targets summary (:only flags))
        {:keys [candidates]} (resolve-candidates artifact-dir flags)
        candidates (->> candidates (remove :empty?) vec)
        baseline   (resolve-baseline flags candidates)
        max-pairs  (or (some-> (:max-pairs flags) parse-long) default-max-pairs)
        repetitions (max 1 (or (:repetitions flags) 1))
        min-repro  (max 1 (min repetitions (or (:require-reproductions flags) 1)))
        results    (atom {})
        stamp      (format "%x" (System/currentTimeMillis))]
    (assert (seq candidates) "no candidate parents found")
    (assert (seq targets) "no failing targets found")
    (println "=== triage:attribute --rerun-failed ===")
    (println (format "targets   : %s" (str/join ", " targets)))
    (println (format "parents   : %s" (str/join ", " (mapv :label candidates))))
    (println (format "baseline  : %s"
                     (or (some-> baseline :id) "UNRESOLVED (stage 0 skipped)")))
    (when (> repetitions 1)
      (println (format "repetitions: %d per cell (failure requires >=%d failing run(s))"
                       repetitions min-repro)))
    (when baseline
      (println "\n--- stage 0: integration baseline control ---")
      (rerun-one! results [:s0] "tri0" "baseline" [(:id baseline)] targets stamp repetitions))
    (println "\n--- stage 1: individual parent tips ---")
    (doseq [c candidates]
      (rerun-one! results [:s1 (:label c)] "tri1" (:label c)
                  [(:change-id c)] targets stamp repetitions))
    (let [all-pairs (pairs candidates)
          pairs     (take max-pairs all-pairs)]
      (println (format "\n--- stage 2: pairwise merges (%d of %d)%s ---"
                       (count pairs) (count all-pairs)
                       (if (< (count pairs) (count all-pairs))
                         " TRUNCATED — higher-order interactions untested" "")))
      (doseq [[a b] pairs]
        (rerun-one! results [:s2 (str/join "+" (mapv :label [a b]))]
                    "tri2" (str/join "+" (mapv :label [a b]))
                    (mapv :change-id [a b]) targets stamp repetitions)))
    ;; resolution-suspect hint: composite-merge files detected statically
    (let [contributions (vec (for [c candidates]
                               (merge c {:commit-id (commit-short c)}
                                      (contribution-diff baseline c))))
          divs (divergence-report contributions
                                 (or (:at flags) "@"))
          composite-files (keep :path
                                (filter #(str/starts-with? (or (:label %) "") "COMPOSITE-MERGE")
                                        divs))]
      (println "\n=== rerun verdict ===")
      (println "note: attribution applies to recorded candidate trees, not authorship.")
      (println "note: pairwise testing cannot prove absence of three-way interactions.")
      (doseq [t targets]
        (let [s0       (get-in @results [:s0 t])
              s0-kind  (outcome-kind s0)
              s0-fails (cell-fail-count s0)
              s1-fail  (for [[lbl m] (:s1 @results)
                             :when (and (= (outcome-kind (get m t)) :failure)
                                        (>= (cell-fail-count (get m t)) min-repro))]
                         lbl)
              s2-fail  (for [[pair m] (:s2 @results)
                             :when (and (= (outcome-kind (get m t)) :failure)
                                        (>= (cell-fail-count (get m t)) min-repro))]
                         pair)
              conflicts (concat
                         (for [[lbl m] (:s1 @results)
                               :when (= (outcome-kind (get m t)) :conflict)]
                           lbl)
                         (for [[pair m] (:s2 @results)
                               :when (= (outcome-kind (get m t)) :conflict)]
                           pair))
              infra     (concat
                         (for [[lbl m] (:s1 @results)
                               :when (= (outcome-kind (get m t)) :infrastructure)]
                           lbl)
                         (for [[pair m] (:s2 @results)
                               :when (= (outcome-kind (get m t)) :infrastructure)]
                           pair))]
          (println (format "%s:" t))
          (cond
            (seq infra)
            (println (format "  INFRASTRUCTURE_FAILURE on: %s" (str/join ", " infra)))
            (and s0 (= s0-kind :failure) (>= s0-fails min-repro))
            (println (format "  BASELINE_FAILURE — preexisting/environmental (%d/%d runs failed); attribution suppressed"
                             s0-fails repetitions))
            (seq s1-fail)
            (do (println (format "  SINGLE_PARENT_FAILURE — REPRODUCED WITH: %s"
                                 (str/join ", " s1-fail)))
                (doseq [lbl s1-fail]
                  (let [k (cell-fail-count (get-in @results [:s1 lbl t]))]
                    (when (and (> repetitions 1) (< k repetitions))
                      (println (format "    FLAKY_REPRODUCTION: %s matched %d/%d" lbl k repetitions))))))
            (seq s2-fail)
            (do (println (format "  PAIRWISE_INTERACTION — REPRODUCED ON MERGE(S): %s"
                                 (str/join ", " s2-fail)))
                (doseq [p s2-fail]
                  (let [k (cell-fail-count (get-in @results [:s2 p t]))]
                    (when (and (> repetitions 1) (< k repetitions))
                      (println (format "    FLAKY_REPRODUCTION: %s matched %d/%d" p k repetitions))))))
            (seq conflicts)
            (println (format "  MERGE_CONFLICT_UNTESTED on: %s" (str/join ", " conflicts)))
            (seq composite-files)
            (do (println "  INTEGRATION_RESOLUTION_SUSPECT — all tested tips/pairs pass, but the")
                (println "  integrated tree contains COMPOSITE-MERGE files differing from every parent:")
                (doseq [f (take 5 composite-files)]
                  (println (str "    " f)))
                (println "  => suspect the merge resolution itself (auto-merge delta check is deferred)."))
            :else
            (println "  HIGHER_ORDER_INTERACTION / UNREPRODUCED_OR_NONDETERMINISTIC (all tested cells pass)")))))))

;; ---------------------------------------------------------------------------
;; Guard

(defn guard-problem [{:keys [label change-id baseline-desc strict-baseline?
                             require-nonempty-tip impl-roots]}]
  (let [cid    (str/trim (or (jj "log" "-r" change-id "--no-graph"
                             "-T" "commit_id.short()") ""))
        state  (str/trim (or (jj "log" "-r" change-id "--no-graph"
                              "-T" (str "if(empty, \"EMPTY\", \"\")"
                                        " ++ \";\" ++"
                                        " if(description, \"DESCRIBED\", \"NO-DESCRIPTION\")"
                                        " ++ \";\" ++"
                                        " if(conflict, \"CONFLICTS\", \"CLEAN\")"))
                            ""))
        descends (let [out (jj "log" "-r"
                               (str "(" baseline-desc ") & ::(" change-id ")")
                               "--no-graph" "-T" "commit_id.short()")]
                   (boolean (seq (str/trim (or out "")))))
        contributes (let [out (jj "diff" "--from" baseline-desc "--to" change-id
                                  "--summary" "--no-pager")]
                      (boolean (seq (str/trim (or out "")))))
        ws-dir (io/file (.getParentFile (io/file repo-dir)) label)
        roots-rx (re-pattern
                  (str "(^|\\? )(" (str/join "|" (map java.util.regex.Pattern/quote impl-roots)) ")/"))
        untracked (when (.isDirectory ws-dir)
                    (let [out (jj-out (.getPath ws-dir) "status" "--no-pager")]
                      (->> (str/split-lines (or out ""))
                           (filter #(str/starts-with? % "? "))
                           (filterv #(re-find roots-rx %))
                           vec)))
        empty-tip?  (str/includes? state "EMPTY")
        undescribed? (str/includes? state "NO-DESCRIPTION")
        conflicts?  (str/includes? state "CONFLICTS")]
    (cond
      (str/blank? cid)
      [{:severity :error
        :msg (format "%s: bookmark does not resolve uniquely (%s)" label change-id)}]

      conflicts?
      [{:severity :error
        :msg (format "%s (%s): CONFLICTS — resolve before merging" label cid)}]

      undescribed?
      [{:severity :error
        :msg (format "%s (%s): NO-DESCRIPTION" label cid)}]

      :else
      (concat
       (when (and empty-tip? require-nonempty-tip)
         [{:severity :error
           :msg (format "%s (%s): EMPTY tip (--require-nonempty-tip policy)" label cid)}])
       (when (and (not descends) strict-baseline?)
         [{:severity :error
           :msg (format "%s (%s): does NOT descend from explicit baseline '%s'"
                        label cid baseline-desc)}])
       (when (seq untracked)
         [{:severity :error
           :msg (format "%s (%s): workspace has untracked implementation files: %s"
                        label cid (str/join ", " (take 3 untracked)))}])
       (when empty-tip?
         [{:severity :warn
           :msg (format "%s (%s): EMPTY tip commit (legitimate in jj when ancestry carries the work)"
                        label cid)}])
       (when (and (not descends) (not strict-baseline?))
         [{:severity :warn
           :msg (format "%s (%s): does not descend from '%s' (normal for merge-based agent branches; pass --baseline to enforce)"
                        label cid baseline-desc)}])
       (when-not contributes
         [{:severity :warn
           :msg (format "%s (%s): NO tree changes relative to baseline '%s'"
                        label cid baseline-desc)}])))))

(defn guard [flags]
  (let [glob   (or (:bookmark-glob flags) (:bookmark-glob config))
        tips   (candidates-from-bookmarks glob)]
    (assert (seq tips) (str "no bookmarks matching glob '" glob "' found"))
    (let [ctx {:baseline-desc    (or (:baseline flags) (:baseline config))
               :strict-baseline?  (boolean (:baseline flags))
               :require-nonempty-tip (boolean (:require-nonempty-tip flags))
               :impl-roots        (or (:impl-roots flags) (:impl-roots config))}
          results (mapcat #(guard-problem (merge % (select-keys ctx [:baseline-desc :strict-baseline? :require-nonempty-tip :impl-roots]))) tips)
          errors  (keep #(when (= :error (:severity %)) (:msg %)) results)
          warns   (keep #(when (= :warn  (:severity %)) (:msg %)) results)]
      (doseq [w warns]
        (println (str "WARN: " w)))
      (if (seq errors)
        (do (println "triage:guard FAILED — reject these tips before merging:")
            (doseq [p errors]
              (println "  " p))
            (System/exit 1))
        (do (println (format "triage:guard OK — tips described, conflict-free, no untracked impl files%s"
                             (if (seq warns)
                               "" (format ", non-empty and descending from '%s'" (:baseline-desc ctx)))))
            (System/exit 0))))))

;; ---------------------------------------------------------------------------
;; Main

(defn -main [& args]
  (let [{:keys [dirs flags]} (parse-args args)]
    (cond
      (:help flags)         (println (usage))
      (:guard flags)        (guard flags)
      (:rerun-failed flags) (let [dir (or (first dirs) (latest-artifact-dir))]
                              (assert dir "no artifact dir found")
                              (rerun-failed dir flags))
      :else                 (let [dir (or (first dirs) (latest-artifact-dir))]
                              (assert dir "no artifact dir found")
                              (let [{:keys [source-tag candidates]} (resolve-candidates dir flags)
                                    tested-rev (tested-revision dir flags)
                                    baseline   (resolve-baseline flags candidates)
                                    summary    (load-summary dir)
                                    failed     (failing-targets summary (:only flags))
                                    tokens     (into {}
                                                     (for [t failed]
                                                       [t (some-> (log-path-for dir summary t)
                                                                  slurp extract-tokens)]))
                                    contributions (vec (for [c candidates]
                                                         (merge c
                                                                {:commit-id (commit-short c)}
                                                                (contribution-diff baseline c))))
                                    matrix     (analyze-target tokens contributions)
                                    rep {:source-tag source-tag
                                         :tested-rev tested-rev
                                         :baseline   baseline
                                         :failed     failed
                                         :candidates (mapv :label candidates)
                                         :contributions contributions
                                         :matrix     matrix}
                                    divs (divergence-report contributions tested-rev)]
                                (if (:json flags)
                                  (println (json/generate-string rep))
                                  (print-static rep divs)))))))

(when *command-line-args*
  (apply -main *command-line-args*))
