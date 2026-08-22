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
;; Verdicts: LIKELY CULPRIT (HIGH only), MULTIPLE PLAUSIBLE CONTRIBUTORS,
;; PLAUSIBLE CONTRIBUTOR (MEDIUM), UNATTRIBUTED.
;;
;; Divergence labels: a merged file differing from every parent is reported as
;; COMPOSITE-MERGE (legitimate combined edits also look like this);
;; -CONFLICT-MARKERS is appended when unresolved-conflict markers are present.
;;
;; --rerun-failed replays failed targets against: stage 0 the integration
;; baseline, stage 1 each parent tip, stage 2 pairwise merges, inside throwaway
;; jj workspaces under /tmp/opencode. Verdict taxonomy:
;;   BASELINE_FAILURE / SINGLE_PARENT_FAILURE / PAIRWISE_INTERACTION /
;;   HIGHER_ORDER_OR_UNREPRODUCED / INFRASTRUCTURE_FAILURE.
(ns attribute-failures
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def tmp-root "/tmp/opencode")
(def default-max-pairs 15)

(defn sh* [dir & args]
  (try
    (apply sh/sh "jj" (concat args [:dir dir]))
    (catch Exception e
      {:exit -1 :err (.getMessage e) :out ""})))

(def repo-dir (System/getProperty "user.dir"))

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
             "  bb triage:attribute [artifact-dir] --rerun-failed [--only unit,suites] [--max-pairs N]"
             "  bb triage:guard [--baseline REV]"
             ""
             "  artifact-dir defaults to newest results/test-artifacts-* containing test-summary.json"
             "  --at REV        revision considered the integrated/tested tree (default:"
             "                  provenance tested-revision, else current @)"
             "  --baseline REV  common ancestor contributions are measured against;"
             "                  default: fork_point(candidates), else immediate-parent fallback"
             "  --only t1,t2    restrict --rerun-failed to these targets"]))

(defn parse-args [args]
  (loop [xs args, acc {:dirs [] :flags {}}]
    (if-let [x (first xs)]
      (cond
        (= "--rerun-failed" x) (recur (rest xs) (assoc-in acc [:flags :rerun-failed] true))
        (= "--guard" x)        (recur (rest xs) (assoc-in acc [:flags :guard] true))
        (= "--json" x)         (recur (rest xs) (assoc-in acc [:flags :json] true))
        (= "--help" x)         (recur (rest xs) (assoc-in acc [:flags :help] true))
        (= "--at" x)           (recur (drop 2 xs) (assoc-in acc [:flags :at] (second xs)))
        (= "--baseline" x)     (recur (drop 2 xs) (assoc-in acc [:flags :baseline] (second xs)))
        (= "--max-pairs" x)    (recur (drop 2 xs) (assoc-in acc [:flags :max-pairs] (second xs)))
        (= "--only" x)         (recur (drop 2 xs) (assoc-in acc [:flags :only] (second xs)))
        :else                  (recur (rest xs) (update acc :dirs conj x)))
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

(defn candidates-from-bookmarks []
  (letfn [(parse [line]
            (when-let [[_ name cid] (re-find #"^\s*(agent-[^:]+):\s+([a-z0-9]+)" line)]
              {:label name
               :change-id cid
               :empty? (= "true"
                          (str/trim (or (jj "log" "-r" cid "--no-graph"
                                         "-T" "if(empty, \"true\", \"false\")")
                                        "")))}))]
    (when-let [out (jj "bookmark" "list")]
      (seq (doall (keep parse (str/split-lines out)))))))

(defn resolve-candidates [artifact-dir]
  (let [prov?   (boolean (load-provenance artifact-dir))
        cands   (or (candidates-from-provenance artifact-dir)
                    (candidates-from-bookmarks))]
    {:source-tag (cond
                   prov? ".provenance.json parents"
                   cands "agent-* bookmark fallback")
     :candidates (vec cands)}))

(defn tested-revision [artifact-dir flags]
  (or (:at flags)
      (:commit_id (:tested_revision (load-provenance artifact-dir)))
      "@"))

(defn resolve-baseline [flags candidates]
  (or (when-let [b (:baseline flags)]
        {:ref b :id b})
      (let [revs (str/join "|" (map :change-id candidates))]
        (when (seq revs)
          (let [out (str/trim (or (jj "log" "-r" (str "fork_point(" revs ")")
                                      "--no-graph"
                                      "-T" "commit_id.short()")
                                  ""))
                lines (str/split-lines out)]
            (when (= 1 (count lines))
              (when-let [id (not-empty (first lines))]
                {:ref (str "fork_point(" revs ")") :id id})))))))

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
        (println (format "   => LIKELY CULPRIT (HIGH): %s" (first with-high)))
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
  "Runs one (candidate-set x target) cell. Records {:exit n :conflict b}."
  [results key prefix label change-refs targets stamp]
  (let [path (make-workspace! prefix label (first change-refs) stamp)]
    (try
      (when (> (count change-refs) 1)
        (let [{:keys [exit err]} (apply sh* path "new" change-refs)]
          (when-not (zero? exit)
            (throw (ex-info (str "jj new merge failed: " err) {})))))
      (let [conflict (ws-conflict? path)]
        (doseq [t targets]
          (print (format "[%s @%s] %-24s ... " label (last change-refs) t))
          (flush)
          (if conflict
            (do (swap! results assoc-in [key t] {:exit nil :conflict true})
                (println "SKIPPED (unresolved conflicts)"))
            (let [code (run-target-in path t)]
              (swap! results assoc-in [key t] {:exit code :conflict false})
              (println (case code
                         0 "PASS"
                         127 "FAIL(exit=127 INFRASTRUCTURE?)"
                         (str "FAIL(exit=" code ")")))))))
      (finally (drop-workspace! (fs/file-name path))))))

(defn outcome-kind [cell]
  (cond
    (nil? cell) :not-run
    (:conflict cell) :infrastructure
    (nil? (:exit cell)) :infrastructure
    (= 127 (:exit cell)) :infrastructure
    (pos? (:exit cell)) :failure
    :else :pass))

(defn rerun-failed [artifact-dir flags]
  (let [summary    (load-summary artifact-dir)
        targets    (failing-targets summary (:only flags))
        {:keys [candidates]} (resolve-candidates artifact-dir)
        candidates (->> candidates (remove :empty?) vec)
        baseline   (resolve-baseline flags candidates)
        max-pairs  (or (some-> (:max-pairs flags) parse-long) default-max-pairs)
        results    (atom {})
        stamp      (format "%x" (System/currentTimeMillis))]
    (assert (seq candidates) "no candidate parents found")
    (assert (seq targets) "no failing targets found")
    (println "=== triage:attribute --rerun-failed ===")
    (println (format "targets   : %s" (str/join ", " targets)))
    (println (format "parents   : %s" (str/join ", " (mapv :label candidates))))
    (println (format "baseline  : %s"
                     (or (some-> baseline :id) "UNRESOLVED (stage 0 skipped)")))
    (when baseline
      (println "\n--- stage 0: integration baseline control ---")
      (rerun-one! results [:s0] "tri0" "baseline" [(:id baseline)] targets stamp))
    (println "\n--- stage 1: individual parent tips ---")
    (doseq [c candidates]
      (rerun-one! results [:s1 (:label c)] "tri1" (:label c)
                  [(:change-id c)] targets stamp))
    (let [all-pairs (pairs candidates)
          pairs     (take max-pairs all-pairs)]
      (println (format "\n--- stage 2: pairwise merges (%d of %d)%s ---"
                       (count pairs) (count all-pairs)
                       (if (< (count pairs) (count all-pairs))
                         " TRUNCATED — higher-order interactions untested" "")))
      (doseq [[a b] pairs]
        (rerun-one! results [:s2 (str/join "+" (mapv :label [a b]))]
                    "tri2" (str/join "+" (mapv :label [a b]))
                    (mapv :change-id [a b]) targets stamp)))
    (println "\n=== rerun verdict ===")
    (println "note: pairwise testing cannot prove absence of three-way interactions.")
    (doseq [t targets]
      (let [s0      (get-in @results [:s0 t])
            s0-fail (= (outcome-kind s0) :failure)
            s1-fail (for [[lbl m] (:s1 @results)
                          :when (= (outcome-kind (get m t)) :failure)]
                      lbl)
            s2-fail (for [[pair m] (:s2 @results)
                          :when (= (outcome-kind (get m t)) :failure)]
                      pair)
            infra   (concat
                     (for [[lbl m] (:s1 @results)
                           :when (= (outcome-kind (get m t)) :infrastructure)]
                       lbl)
                     (for [[pair m] (:s2 @results)
                           :when (= (outcome-kind (get m t)) :infrastructure)]
                       pair))]
        (println (format "%s:" t))
        (cond
          (seq infra)
          (println (format "  INFRASTRUCTURE_FAILURE/SKIPPED on: %s" (str/join ", " infra)))
          s0-fail
          (println (format "  BASELINE_FAILURE — preexisting/environmental (baseline exit=%s); attribution suppressed"
                           (:exit s0)))
          (seq s1-fail)
          (println (format "  SINGLE_PARENT_FAILURE reproduced on: %s" (str/join ", " s1-fail)))
          (seq s2-fail)
          (do (println (format "  PAIRWISE_INTERACTION reproduced on merge(s): %s"
                               (str/join ", " s2-fail)))
              (println "  => interaction failure between the pairs above"))
          :else
          (println "  HIGHER_ORDER_OR_UNREPRODUCED (all tested tips/pairs pass)"))))))

;; ---------------------------------------------------------------------------
;; Guard

(defn guard-problem [{:keys [label change-id]} baseline-desc strict-baseline?]
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
        ws-dir (io/file (.getParentFile (io/file repo-dir)) label)
        untracked (when (.isDirectory ws-dir)
                    (let [out (jj-out (.getPath ws-dir) "status" "--no-pager")]
                      (->> (str/split-lines (or out ""))
                           (filter #(str/starts-with? % "? "))
                           (filterv #(re-find #"(^|\? )(src|test|protocols_src)/" %))
                           vec)))]
    (cond
      (str/blank? cid)
      {:severity :error
       :msg (format "%s: bookmark does not resolve uniquely (%s)" label change-id)}

      (not= ";DESCRIBED;CLEAN" state)
      {:severity :error
       :msg (format "%s (%s): %s" label cid state)}

      (and strict-baseline? (not descends))
      {:severity :error
       :msg (format "%s (%s): does NOT descend from explicit baseline '%s'"
                    label cid baseline-desc)}

      (seq untracked)
      {:severity :error
       :msg (format "%s (%s): workspace has untracked implementation files: %s"
                    label cid (str/join ", " (take 3 untracked)))}

      (not descends)
      {:severity :warn
       :msg (format "%s (%s): does not descend from '%s' (normal for merge-based agent branches; pass --baseline to enforce)"
                    label cid baseline-desc)})))

(defn guard [flags]
  (let [tips (candidates-from-bookmarks)]
    (assert (seq tips) "no agent-* bookmarks found")
    (let [baseline-desc (or (:baseline flags) "integration@origin")
          results (map #(guard-problem % baseline-desc (boolean (:baseline flags))) tips)
          errors  (keep #(when (= :error (:severity %)) (:msg %)) results)
          warns   (keep #(when (= :warn  (:severity %)) (:msg %)) results)]
      (doseq [w warns]
        (println (str "WARN: " w)))
      (if (seq errors)
        (do (println "triage:guard FAILED — reject these tips before merging:")
            (doseq [p errors]
              (println "  " p))
            (System/exit 1))
        (do (println (format "triage:guard OK — tips non-empty, described, conflict-free, no untracked impl files%s"
                             (if (seq warns)
                               "" (format ", descend from '%s'" baseline-desc))))
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
                              (let [{:keys [source-tag candidates]} (resolve-candidates dir)
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
