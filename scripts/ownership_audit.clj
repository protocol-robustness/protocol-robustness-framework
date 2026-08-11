#!/usr/bin/env clojure
(ns scripts.ownership-audit
  "Content-authority classification audit for governed paths.

  Reads config/architecture/content-authority.edn (a SIDECAR manifest) and
  verifies that every file inside :governed-roots resolves to exactly one
  effective classification. It never writes to any canonical artifact.

  The umbrella category is 'non-framework authority/content', modelled on
  three orthogonal axes:
    :authority       — :prf | :protocol | :benchmark | :user
    :content-kind    — :core-contract | :integration | :example
                       | :benchmark | :research | :demo | :test-support
    :support-status  — :normative | :supported | :illustrative | :experimental

  :mixed? is a boolean debt state (with :reason and :split-intent), reported
  separately and driven toward zero.

  Modes:
    --check      (default) fail (exit non-zero) on any error class.
    --report     print a per-file classification report; never fails.
    --rootzones  print the finer-grained rootzone split for the two rootzone
                 registry files; informational.
    --manifest PATH   explicit manifest path (default config/architecture/
                 content-authority.edn).

  Error classes (all fail in --check):
    - a file inside :governed-roots with no matching rule (unclassified)
    - a rule matching only outside :governed-roots (misplaced rule)
    - a governed root with no classification rule (uncovered root)
    - a default glob that matches nothing (dead rule)
    - overlapping defaults that disagree (ambiguous)
    - a :mixed? rule without :reason + :split-intent
    - an unknown authority/content-kind/support-status value
    - two overrides matching the same file
  Files outside :governed-roots are deliberately not governed yet and are
  never reported as errors.

  A 'suspicious core → non-core' dependency report and a
  :known-missing-extension-points report are emitted as advisory in --check
  and become a debt allowlist gate in P1."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── config / defaults ────────────────────────────────────────────────────────

(def default-manifest "config/architecture/content-authority.edn")
(def ^:private root-dir (System/getProperty "user.dir"))

(defn split-path [p]
  (->> (str/split p #"/")
       (remove str/blank?)))

(defn glob-matches?
  "Segment-based glob matcher. Supports ** as 'zero or more segments'.
   A pattern segment matches a path segment only when equal (no char globs)."
  [pattern path]
  (letfn [(match [ps ss]
            (cond
              (empty? ps) (empty? ss)
              (= "**" (first ps))
              (or (match (rest ps) ss)
                  (and (seq ss) (match ps (rest ss))))
              (empty? ss) false
              (= (first ps) (first ss)) (match (rest ps) (rest ss))
              :else false))]
    (match (split-path pattern) (split-path path))))

(defn within-root?
  "True when path equals a governed root or is nested under one."
  [path roots]
  (let [p (split-path path)]
    (boolean
     (some (fn [root]
             (let [r (split-path root)
                   n (count r)]
               (and (>= (count p) n)
                    (= r (subvec (vec p) 0 n)))))
           roots))))

(defn pattern-base
  "The literal base directory of a rule pattern (before any ** or trailing file
   segment that is not a governed root). Used to check the rule matches within
   a governed root."
  [pattern]
  (let [segs (split-path pattern)]
    (->> (take-while #(not= "**" %) segs)
         (remove str/blank?)
         (str/join "/"))))

;; ── classification resolution ────────────────────────────────────────────────

(defn- rule-classification
  "The three-axis classification of a rule, or nil when :mixed? true."
  [{:keys [authority content-kind support-status]}]
  (when (and authority content-kind support-status)
    {:authority authority :content-kind content-kind :support-status support-status}))

(defn- override-matches
  [manifest file]
  (filter #(glob-matches? (:path %) file) (:overrides manifest)))

(defn- default-matches
  [manifest file]
  (filter #(glob-matches? (:path %) file) (:defaults manifest)))

(defn resolve-classification
  "Resolve the effective classification of a governed file.

   Returns {:status :classified :classification <map> :source <rule>}
        or {:status :mixed :rule <rule>}
        or {:status :unclassified}
        or {:status :ambiguous-override :matches <vec>}
        or {:status :ambiguous-default :matches <vec>}
        or {:status :invalid :reason <str>}
   Overrides take precedence over defaults; a mixed override wins over the
   default classification."
  [manifest file]
  (let [overs (override-matches manifest file)
        mixed-over (first (filter :mixed? overs))]
    (cond
      (< 1 (count overs))
      {:status :ambiguous-override :matches overs}

      mixed-over
      {:status :mixed :rule mixed-over}

      (seq overs)
      (if-let [c (rule-classification (first overs))]
        {:status :classified :classification c :source (first overs)}
        {:status :invalid :reason (str "override for " file " has incomplete classification")})

      :else
      (let [defs (default-matches manifest file)
            classes (distinct (map rule-classification defs))]
        (cond
          (empty? defs)
          {:status :unclassified}

          (< 1 (count classes))
          {:status :ambiguous-default :matches defs}

          (rule-classification (first defs))
          {:status :classified :classification (first classes) :source (first defs)}

          :else
          {:status :invalid :reason (str "default for " file " has incomplete classification")})))))

;; ── validation ───────────────────────────────────────────────────────────────

(defn validate-allowed-values
  "Return error maps for unknown authority/content-kind/support-status values."
  [manifest]
  (let [allowed-authority (:allowed-authorities manifest)
        allowed-kind (:allowed-content-kinds manifest)
        allowed-status (:allowed-support-statuses manifest)]
    (into []
          (comp (mapcat identity)
                (keep (fn [rule]
                        (cond
                          (:mixed? rule)
                          nil
                          (not (contains? allowed-authority (:authority rule)))
                          {:rule (:path rule) :error :unknown-authority :value (:authority rule)}
                          (not (contains? allowed-kind (:content-kind rule)))
                          {:rule (:path rule) :error :unknown-content-kind :value (:content-kind rule)}
                          (not (contains? allowed-status (:support-status rule)))
                          {:rule (:path rule) :error :unknown-support-status :value (:support-status rule)}))))
          [(:defaults manifest) (:overrides manifest)])))

(defn- validate-mixed-rules
  "Return error maps for :mixed? rules missing :reason or :split-intent."
  [manifest]
  (into []
        (keep (fn [rule]
                (when (and (:mixed? rule)
                           (or (str/blank? (str (:reason rule)))
                               (not (contains? #{:planned :intrinsic} (:split-intent rule)))))
                  {:rule (:path rule)
                   :error :mixed-incomplete
                   :has-reason? (not (str/blank? (str (:reason rule))))
                   :split-intent (:split-intent rule)})))
        (:overrides manifest)))

(defn validate-rule-placement
  "Return error maps for rules whose base path matches only outside
   :governed-roots."
  [manifest]
  (let [roots (:governed-roots manifest)]
    (into []
          (comp (mapcat identity)
                (keep (fn [rule]
                        (when-not (within-root? (pattern-base (:path rule)) roots)
                          {:rule (:path rule)
                           :error :rule-outside-governed-roots
                           :base (pattern-base (:path rule))}))))
          [(:defaults manifest) (:overrides manifest)])))

(defn governed-files
  "All files (relative paths) under any governed root."
  [manifest]
  (let [roots (:governed-roots manifest)]
    (into (sorted-set)
          (for [root roots
                :when (.isDirectory (io/file root))
                file (file-seq (io/file root))
                :when (.isFile file)
                :let [rel (.getPath file)]]
            rel))))

(defn validate-root-coverage
  "Return error maps for governed roots that contain files but are covered by
   no default rule. A root is covered when at least one default glob matches at
   least one file under it (overrides cannot be the sole cover, since defaults
   are the fallback that classifies every file in a root)."
  [manifest files]
  (let [roots (:governed-roots manifest)]
    (into []
          (keep (fn [root]
                  (let [under (filter #(within-root? % [root]) files)
                        covered (boolean
                                 (some (fn [f]
                                         (seq (default-matches manifest f)))
                                       under))]
                    (when (and (seq under) (not covered))
                      {:root root :error :governed-root-uncovered}))))
          roots)))

(defn validate-dead-defaults
  "Return error maps for default globs that match no governed file."
  [manifest files]
  (into []
        (keep (fn [default]
                (when-not (some #(glob-matches? (:path default) %) files)
                  {:rule (:path default) :error :dead-default-rule})))
        (:defaults manifest)))

(defn- file-errors
  "Classification errors for a single governed file."
  [manifest file]
  (let [res (resolve-classification manifest file)]
    (case (:status res)
      :unclassified [{:file file :error :unclassified}]
      :ambiguous-override [{:file file :error :ambiguous-override
                            :matches (mapv :path (:matches res))}]
      :ambiguous-default [{:file file :error :ambiguous-default
                           :matches (mapv :path (:matches res))}]
      :invalid [{:file file :error :invalid-classification
                 :reason (:reason res)}]
      [])))

(defn- mixed-files
  "The :mixed? governed files (debt list)."
  [manifest files]
  (into (sorted-map)
        (keep (fn [file]
                (let [res (resolve-classification manifest file)]
                  (when (= :mixed (:status res))
                    [(str file) (:rule res)]))))
        files))

(defn- all-errors
  "All validation errors. Returns a vector of error maps."
  [manifest]
  (let [files (governed-files manifest)
        file-errs (mapcat #(file-errors manifest %) files)]
    (vec (concat
          (validate-allowed-values manifest)
          (validate-mixed-rules manifest)
          (validate-rule-placement manifest)
          (validate-root-coverage manifest files)
          (validate-dead-defaults manifest files)
          file-errs))))

;; ── reports ─────────────────────────────────────────────────────────────────

(defn- outside-core-ns?
  "True when a namespace string resolves outside the core resolver-sim prefix
   (examples/notebooks/extensions)."
  [ns]
  (or (str/starts-with? ns "examples.")
      (str/starts-with? ns "notebooks.")
      (str/starts-with? ns "prf.extensions.")))

(defn- core-dependencies
  "Namespace requires found in src/resolver_sim files that resolve outside the
   core namespace prefix. Advisory in P0 (becomes a gate in P1). Returns a map
   of file → non-empty offender vector for files that have any."
  [manifest]
  (let [root "src/resolver_sim"
        files (for [file (file-seq (io/file root))
                    :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
                file)]
    (into (sorted-map)
          (keep (fn [file]
                  (let [rel (.getPath file)
                        src (slurp file)
                        nss (mapcat (fn [m]
                                      (re-seq #"[a-zA-Z0-9._-]+" m))
                                    (mapcat #(second (re-seq #"\(:require\s+([^\)]*)\)" %))
                                            (re-seq #"\(ns\s+[^\)]*" src)))
                        offenders (sort (distinct (filter outside-core-ns? nss)))]
                    (when (seq offenders)
                      [rel (vec offenders)]))))
          files)))

(defn- rootzone-report
  "Finer-grained split for the two rootzone registry files. Informational —
   does not pretend P0 has symbol-level ownership enforcement. Splits each
   file's embedded registrations into PRF-intrinsic vs protocol-specific
   buckets by keyword shape."
  []
  (let [canonical (slurp "src/resolver_sim/hash/canonical.clj")
        passive (slurp "src/resolver_sim/definitions/passive_registries.clj")
        protocol-ish? (fn [kw]
                        (boolean (re-find #"(?i)pro-rata|slash|bounty|pool-|award|check-set|priority|with-bounty|fail-action|lab-|yield" (str kw))))
        intent-ids (map second (re-seq #":intent/name\s+:([a-z0-9-]+)" canonical))
        tag-ids (map second (re-seq #":([a-z0-9-]+)\s+\"[A-Z0-9_]+\"" canonical))]
    {:report/rootzones true
     :report/note "informational split; file-level :mixed? :planned remains the manifest truth"
     :canonical.clj
     {:intent-contracts {:total (count intent-ids)
                         :protocol-specific (count (filter protocol-ish? intent-ids))
                         :prf-intrinsic (- (count intent-ids) (count (filter protocol-ish? intent-ids)))}
      :domain-tags {:total (count tag-ids)
                    :protocol-specific (count (filter protocol-ish? tag-ids))
                    :prf-intrinsic (- (count tag-ids) (count (filter protocol-ish? tag-ids)))}}
     :passive_registries.clj
     {:registries [:intent-registry :projection-definition-registry
                   :claim-definition-registry :execution-registry
                   :domain-tag-registry :hash-projection-registry]
      :protocol-scoped-entries (->> (re-seq #":scope\s+\{:protocols\s+#\{:([a-z]+)" passive)
                                   (map second)
                                   vec)}}))

;; ── entrypoints ─────────────────────────────────────────────────────────────

(defn load-manifest
  ([] (load-manifest default-manifest))
  ([path]
   (when-not (.exists (io/file path))
     (throw (ex-info (str "content-authority manifest not found: " path)
                     {:path path})))
   (edn/read-string (slurp path))))

(defn audit
  "Run the full audit. Returns {:valid? bool :errors [] :mixed {} :report map}.
   Never throws for classification findings."
  [manifest]
  (let [errors (all-errors manifest)
        files (governed-files manifest)
        mixed (mixed-files manifest files)]
    {:valid? (empty? errors)
     :errors errors
     :mixed mixed
     :file-count (count files)
     :mixed-count (count mixed)
     :report (core-dependencies manifest)}))

(defn report-line
  "Human-readable audit summary."
  [result]
  (str "content-authority: " (:file-count result) " governed files, "
       (count (:errors result)) " errors, " (:mixed-count result) " mixed"))

(defn -main [& args]
  (let [argset (set args)
        manifest-path (or (second (first (filter #(= "--manifest" (first %))
                                                 (partition 2 1 args))))
                          default-manifest)
        manifest (try
                   (load-manifest manifest-path)
                   (catch Exception e
                     (binding [*out* *err*]
                       (println (str "content-authority: ERROR " (.getMessage e))))
                     (shutdown-agents)
                     (System/exit 1)))]
    (cond
      (contains? argset "--rootzones")
      (do (println (pr-str (rootzone-report)))
          (shutdown-agents)
          (System/exit 0))

      (contains? argset "--report")
      (do (doseq [f (governed-files manifest)]
            (let [r (resolve-classification manifest f)]
              (println (str f "  →  " (:status r)
                            (when (= :classified (:status r))
                              (str " " (pr-str (:classification r))))))))
          (shutdown-agents)
          (System/exit 0))

      :else ;; --check (default)
      (let [result (audit manifest)]
        (when (seq (:errors result))
          (doseq [e (:errors result)]
            (println (str "  ✗ " (pr-str e)))))
        (when (seq (:mixed result))
          (println (str "  MIXED AUTHORITY FILES (" (:mixed-count result) ")"))
          (doseq [[f rule] (:mixed result)]
            (println (str "    " f "  [" (:split-intent rule) "]"))))
        (let [suspicious (:report result)]
          (when (seq suspicious)
            (println "  ADVISORY — core → non-core dependency requires:")
            (doseq [[f nss] suspicious]
              (println (str "    " f "  " (pr-str nss))))))
        (println (report-line result))
        (if (:valid? result)
          (do (println "content-authority: PASS")
              (shutdown-agents)
              (System/exit 0))
          (do (println (str "content-authority: FAILED (" (count (:errors result)) " errors)"))
              (shutdown-agents)
              (System/exit 1)))))))
