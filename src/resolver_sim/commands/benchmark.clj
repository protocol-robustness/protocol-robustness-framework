(ns resolver-sim.commands.benchmark
  "Benchmark validation commands — comprehensive source-level registry, pack,
   manifest, concept, and lifecycle validation.

   Port of scripts/benchmarks_validate.clj with additional JAR-aware checks."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.benchmark.coverage :as coverage]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.config.paths :as paths]
            [resolver-sim.scenario.suites :as suites]))

;; ───────────────────────────────────────────────────────────────────────
;; Forward declarations
;; ───────────────────────────────────────────────────────────────────────

(declare validate-pack-registry validate-manifest)

;; ───────────────────────────────────────────────────────────────────────
;; Helpers
;; ───────────────────────────────────────────────────────────────────────

(defn- read-edn [path]
  (try (edn/read-string (slurp path))
       (catch Exception _ nil)))

(defn- file-exists? [path]
  (.exists (io/file path)))

(defn- path-str [path]
  (if (instance? java.io.File path) (.getPath ^java.io.File path) (str path)))

;; ───────────────────────────────────────────────────────────────────────
;; Global registry validation
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-global-registry
  [errors registry-data]
  (when (nil? (:registry/spec registry-data))
    (swap! errors conj "Global registry missing :registry/spec"))
  (when (nil? (:domains registry-data))
    (swap! errors conj "Global registry missing :domains"))
  (when (nil? (:packs registry-data))
    (swap! errors conj "Global registry missing :packs"))
  (doseq [domain (:domains registry-data)]
    (when (nil? (:domain/id domain))
      (swap! errors conj "Domain entry missing :domain/id"))
    (when (nil? (:domain/description domain))
      (swap! errors conj (str "Domain " (:domain/id domain) " missing :domain/description"))))
  (let [domain-ids (set (map :domain/id (:domains registry-data)))]
    (doseq [pack (:packs registry-data)]
      (let [pid (:pack/id pack)]
        (doseq [k [:pack/id :pack/description :pack/registry]]
          (when (nil? (get pack k))
            (swap! errors conj (str "Pack " (or pid "<unnamed>") " missing " (name k)))))
        (when-let [reg-path (:pack/registry pack)]
          (let [pf (io/file "benchmarks" reg-path)]
            (if-not (.exists pf)
              (swap! errors conj (str "Pack " (or pid "<unnamed>") " registry not found: " reg-path))
              (validate-pack-registry errors pf domain-ids pid))))))))

;; ───────────────────────────────────────────────────────────────────────
;; Pack registry validation
;; ───────────────────────────────────────────────────────────────────────

(defn scoring-path-for [scoring-id]
  (let [filename (case scoring-id
                   :scoring/robustness-dimensions-v0 "robustness-dimensions-v0.edn"
                   :scoring/binary-claims-v1 "binary-claims-v1.edn"
                   :scoring/severity-weighted-robustness-v1 "severity-weighted-robustness-v1.edn"
                   :scoring/severity-weighted-v1 "severity-weighted-robustness-v1.edn"
                   :scoring/shortfall-allocation-v0 "shortfall-allocation-v0.edn"
                   nil)]
    (when filename (str (paths/benchmarks-scoring-dir) "/" filename))))

(defn- validate-pack-registry
  [errors pack-file domain-ids pack-id]
  (let [data (read-edn pack-file)]
    (if (nil? data)
      (swap! errors conj (str "Pack registry unreadable: " pack-file))
      (let [pack-dir (.getParent pack-file)]
        (when (nil? (:pack/id data))
          (swap! errors conj (str pack-file " missing :pack/id")))
        (let [pack-domain (:pack/domain data)]
          (when (and domain-ids pack-domain (not (contains? domain-ids pack-domain)))
            (swap! errors conj (str pack-file " pack domain " pack-domain " not registered in benchmarks/registry.edn"))))
        (let [bid-set (atom #{})]
          (doseq [benchmark-ref (:benchmarks data)]
            (let [bid (:benchmark/id benchmark-ref)
                  status (:benchmark/status benchmark-ref)
                  bdomain (:benchmark/domain benchmark-ref)]
              (when (contains? @bid-set bid)
                (swap! errors conj (str "Duplicate benchmark ID " bid " in " pack-file)))
              (swap! bid-set conj bid)
              (when (and (#{:active :experimental} status) bid (not (keyword? bid)))
                (swap! errors conj (str "active/experimental benchmark id " bid " must be a keyword in " pack-file)))
              (when (and domain-ids bdomain (not (contains? domain-ids bdomain)))
                (swap! errors conj (str "benchmark domain " bdomain " for " bid " not registered in benchmarks/registry.edn")))
              (when-not (contains? #{:active :experimental :deprecated} status)
                (swap! errors conj (str "Invalid :benchmark/status " status " for " bid " in " pack-file)))
              (when (= :deprecated status)
                (when-not (:deprecated-on benchmark-ref)
                  (swap! errors conj (str "Deprecated benchmark " bid " missing :deprecated-on in " pack-file)))
                (when-not (:replaced-by benchmark-ref)
                  (swap! errors conj (str "Deprecated benchmark " bid " missing :replaced-by in " pack-file))))
              (let [manifest-path (str pack-dir "/" (:benchmark/file benchmark-ref))]
                (if-not (file-exists? manifest-path)
                  (swap! errors conj (str "Benchmark file not found: " manifest-path " for " bid))
                  (when-let [manifest (read-edn manifest-path)]
                    (validate-manifest errors manifest manifest-path bid status)))))))))))

;; ───────────────────────────────────────────────────────────────────────
;; Concept validation
;; ───────────────────────────────────────────────────────────────────────

(defn- concept-files []
  (sort (keep (fn [f]
                (when (and (.isFile f) (str/ends-with? (.getName f) ".edn"))
                  (.getPath f)))
              (file-seq (io/file (paths/benchmarks-concepts))))))

(defn- validate-concepts
  [errors]
  (let [files (concept-files)]
    (when (empty? files)
      (swap! errors conj "No concept files found in benchmarks/concepts/"))
    (doseq [path files]
      (let [data (read-edn path)]
        (if (nil? data)
          (swap! errors conj (str "Concept file unreadable: " path))
          (let [concepts (:concepts data)]
            (when-not (:concepts/version data)
              (swap! errors conj (str path " missing :concepts/version")))
            (doseq [c concepts]
              (let [cid (:concept/id c)]
                (doseq [k [:concept/title :concept/summary :concept/stakeholder-language
                           :concept/why-it-matters]]
                  (when-not (get c k)
                    (swap! errors conj (str path " concept " cid " missing " (name k)))))
                (let [maps-to (:concept/maps-to c)]
                  (when (and maps-to (not (map? maps-to)))
                    (swap! errors conj (str path " concept " cid " :concept/maps-to must be a map")))
                  (when (map? maps-to)
                    (doseq [[k expected-type] [[:claims vector?] [:invariants vector?] [:evidence vector?]]]
                      (let [v (get maps-to k)]
                        (when (and v (not (expected-type v)))
                          (swap! errors conj (str path " concept " cid " :maps-to " (name k) " must be a vector"))))))))))))
    ;; collision detection between global concepts and local concept files
      (try
        (let [global-registry (requiring-resolve 'resolver-sim.concepts.registry/load-registry)
              global-concepts (:concepts (global-registry))
              global-ids (set (map :concept/id global-concepts))
              local-concepts (mapcat (fn [path]
                                       (let [data (read-edn path)]
                                         (when data (:concepts data))))
                                     files)
              local-ids (set (map :concept/id local-concepts))
              collisions (set/intersection global-ids local-ids)]
          (doseq [cid (sort collisions)]
            (let [shadows? (some (fn [c]
                                   (and (= (:concept/id c) cid)
                                        (:concept/shadows-global? c)))
                                 local-concepts)]
              (when-not shadows?
                (swap! errors conj (str "Concept " cid " shadows global concept without :concept/shadows-global? true"))))))
        (catch Exception _)))))

;; ───────────────────────────────────────────────────────────────────────
;; Scoring validation
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-scoring
  [errors]
  (doseq [path [(str (paths/benchmarks-scoring-dir) "/robustness-dimensions-v0.edn")
                (str (paths/benchmarks-scoring-dir) "/binary-claims-v1.edn")
                (str (paths/benchmarks-scoring-dir) "/severity-weighted-robustness-v1.edn")
                (str (paths/benchmarks-scoring-dir) "/shortfall-allocation-v0.edn")]]
    (when-not (file-exists? path)
      (swap! errors conj (str "Scoring file not found: " path)))))

;; ───────────────────────────────────────────────────────────────────────
;; Claim registry validation
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-claim-registry
  [errors]
  (let [path hash-ref/claim-registry-path]
    (if-not (file-exists? path)
      (swap! errors conj "Claim registry not found: " path)
      (let [data (read-edn path)]
        (if (nil? data)
          (swap! errors conj "Claim registry unreadable: " path)
          (let [claims (:claims data [])]
            (when (empty? claims)
              (swap! errors conj "Claim registry has no claims"))
            (let [id-set (atom #{})]
              (doseq [claim claims]
                (let [cid (:claim/id claim)]
                  (when (contains? @id-set cid)
                    (swap! errors conj (str "Duplicate claim ID " cid " in " path)))
                  (swap! id-set conj cid)
                  (doseq [k [:claim/title :claim/description :claim/property-types :claim/evaluator]]
                    (when-not (get claim k)
                      (swap! errors conj (str "Claim " cid " missing " (name k) " in " path)))))))))))))

;; ───────────────────────────────────────────────────────────────────────
;; Manifest validation
;; ───────────────────────────────────────────────────────────────────────

(defn- suite-scenario-ids
  "Return the set of expected scenario IDs for a given suite key."
  [suite-key]
  (let [paths (suites/suite-paths suite-key)]
    (when paths
      (set (map io-sc/scenario-file->id paths)))))

(defn- claim-registry-map
  [claim-registry-path]
  (let [data (read-edn claim-registry-path)]
    (when data
      (into {} (map (fn [c] [(:claim/id c) c]) (:claims data []))))))

(defn- validate-manifest
  [errors manifest manifest-path bid status-from-registry]
  (let [suite-key (:benchmark/scenario-suite manifest)
        scoring-id (:benchmark/scoring-rule manifest)
        manifest-status (:benchmark/status manifest)]
    ;; Status consistency
    (when (and (= :active status-from-registry) (not= :active manifest-status))
      (swap! errors conj (str "Active benchmark " bid " must declare :benchmark/status :active in its manifest; got " manifest-status)))
    (when (and (= :deprecated status-from-registry) (= :active manifest-status))
      (swap! errors conj (str "Deprecated benchmark " bid " has :active status in manifest")))
    ;; Version
    (let [version (:benchmark/version manifest)]
      (when-not version
        (swap! errors conj (str "Missing :benchmark/version in " manifest-path)))
      (when (and version (not (integer? version)))
        (swap! errors conj (str ":benchmark/version must be an integer in " manifest-path))))
    (let [suite-version (:benchmark/suite-pinned-version manifest)]
      (when (and suite-version (not (string? suite-version)))
        (swap! errors conj (str ":benchmark/suite-pinned-version must be a string in " manifest-path))))
    ;; Suite resolution
    (when (nil? suite-key)
      (swap! errors conj (str "Missing :benchmark/scenario-suite in " manifest-path)))
    (when suite-key
      (when-not (suites/suite-paths suite-key)
        (swap! errors conj (str "Unknown suite " suite-key " in " manifest-path))))
    ;; Scoring rule
    (when scoring-id
      (if-let [scoring-path (scoring-path-for scoring-id)]
        (when-not (file-exists? scoring-path)
          (swap! errors conj (str "Scoring file not found: " scoring-path " for " scoring-id " in " manifest-path)))
        (swap! errors conj (str "Unknown scoring rule " scoring-id " in " manifest-path))))
    (when-not scoring-id
      (swap! errors conj (str "Missing :benchmark/scoring-rule in " manifest-path)))
    ;; Property types
    (let [prop-types (:benchmark/property-types manifest)]
      (when (and prop-types (not (set? prop-types)))
        (swap! errors conj (str ":benchmark/property-types must be a set in " manifest-path)))
      (doseq [pt prop-types]
        (when-not (#{:safety :liveness :integrity :fairness} pt)
          (swap! errors conj (str "Unknown property type " pt " in " manifest-path)))))
    ;; Runner policy existence
    (let [runner (:benchmark/runner-policy manifest)]
      (when runner
        (let [runner-path (str (paths/benchmarks-runners-dir) "/" (name runner) ".edn")]
          (when-not (file-exists? runner-path)
            (swap! errors conj (str "Runner policy file not found: " runner-path " for " bid))))))
    ;; Concepts referenced in manifest — check both benchmark-local and framework registries
    (let [concept-files-list (concept-files)
          local-concept-ids (set (mapcat (fn [f]
                                           (let [d (read-edn f)]
                                             (map :concept/id (:concepts d))))
                                         concept-files-list))
          global-concept-ids (try
                               (let [load-registry (requiring-resolve 'resolver-sim.concepts.registry/load-registry)
                                     global-concepts (:concepts (load-registry))]
                                 (set (map :concept/id global-concepts)))
                               (catch Exception _ #{}))
          all-concept-ids (set/union local-concept-ids global-concept-ids)]
      (doseq [cid (:benchmark/concepts manifest)]
        (when-not (contains? all-concept-ids cid)
          (swap! errors conj (str "Unknown concept " cid " referenced from " manifest-path)))))
    ;; Scenario references in manifest
    (let [scenarios (:benchmark/scenarios manifest)]
      (when (seq scenarios)
        (when suite-key
          (let [known-ids (suite-scenario-ids suite-key)]
            (doseq [scenario scenarios]
              (let [sid (:scenario/id scenario)]
                (when (and known-ids sid (not (contains? known-ids sid)))
                  (swap! errors conj (str "Scenario ID \"" sid "\" not found in suite " suite-key " in " manifest-path))))))
          (doseq [scenario scenarios]
            (let [dim (:dimension scenario)]
              (when dim
                (let [concept-files-list (concept-files)
                      local-concept-ids (set (mapcat (fn [f]
                                                       (let [d (read-edn f)]
                                                         (map :concept/id (:concepts d))))
                                                     concept-files-list))
                      global-concept-ids (try
                                           (let [load-registry (requiring-resolve 'resolver-sim.concepts.registry/load-registry)
                                                 global-concepts (:concepts (load-registry))]
                                             (set (map :concept/id global-concepts)))
                                           (catch Exception _ #{}))
                      all-concept-ids (set/union local-concept-ids global-concept-ids)]
                  (when-not (contains? all-concept-ids dim)
                    (swap! errors conj (str "Unknown scenario dimension " dim " in " manifest-path))))
                (when-not (contains? (set (:benchmark/concepts manifest)) dim)
                  (swap! errors conj (str "Scenario dimension " dim " not declared in :benchmark/concepts in " manifest-path))))))))
    ;; Claim references validate against claim registry
      (let [claim-reg (claim-registry-map hash-ref/claim-registry-path)]
        (when claim-reg
          (let [deferred (or (:benchmark/deferred-scenario-claims manifest) #{})
                all-registered-ids (set (keys claim-reg))
                claim-refs (:benchmark/claims manifest)]
            (doseq [ref claim-refs]
              (let [cid (if (keyword? ref) ref (:claim/id ref))]
                (when (keyword? cid)
                  (cond
                    (contains? all-registered-ids cid) nil
                    (contains? deferred cid) nil
                    :else (swap! errors conj (str "Claim " cid " does not resolve to claim registry or deferred-claims in " manifest-path))))))
          ;; Scenario claim references
            (doseq [scenario (:benchmark/scenarios manifest)
                    :let [sc-claim (:claim scenario)]
                    :when sc-claim]
              (cond
                (contains? all-registered-ids sc-claim) nil
                (contains? deferred sc-claim) nil
                :else (swap! errors conj (str "Scenario claim " sc-claim " does not resolve to claim registry or deferred-claims in " manifest-path))))))
      ;; Active lifecycle via coverage
        (when (= :active status-from-registry)
          (let [known-ids (set (keys claim-reg))]
            (doseq [violation (coverage/active-benchmark-errors manifest known-ids)]
              (swap! errors conj (str "Active benchmark lifecycle violation " violation " in " manifest-path)))))))))

;; ───────────────────────────────────────────────────────────────────────
;; Pack capability validation
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-pack-capabilities
  [errors]
  (doseq [reg-path [hash-ref/prf-core-pack-registry-path
                    hash-ref/sew-pack-registry-path]]
    (when-let [pack (read-edn reg-path)]
      (let [pack-dir (.getParent (io/file reg-path))
            manifests-by-id (into {}
                                  (keep (fn [benchmark-ref]
                                          (when-let [m (read-edn (str pack-dir "/" (:benchmark/file benchmark-ref)))]
                                            [(:benchmark/id benchmark-ref)
                                             (assoc m :benchmark/status (:benchmark/status benchmark-ref))])))
                                  (:benchmarks pack))
            claim-reg (claim-registry-map hash-ref/claim-registry-path)
            known-ids (if claim-reg (set (keys claim-reg)) #{})]
        (doseq [error-id (coverage/pack-capability-errors pack manifests-by-id known-ids)]
          (swap! errors conj (str "Pack capability violation " error-id " in " reg-path)))))))

;; ───────────────────────────────────────────────────────────────────────
;; Duplicate active benchmark detection
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-duplicates
  [errors]
  (let [active-manifests (atom [])]
    (doseq [reg-path [hash-ref/prf-core-pack-registry-path
                      hash-ref/sew-pack-registry-path]]
      (when-let [pack (read-edn reg-path)]
        (let [pack-dir (.getParent (io/file reg-path))]
          (doseq [ref (:benchmarks pack)]
            (when (= :active (:benchmark/status ref))
              (when-let [m (read-edn (str pack-dir "/" (:benchmark/file ref)))]
                (swap! active-manifests conj
                       {:benchmark/id (:benchmark/id ref)
                        :suite (:benchmark/scenario-suite m)
                        :claims (set (keep :claim/id (:benchmark/claims m)))
                        :scoring (:benchmark/scoring-rule m)
                        :runner (:benchmark/runner-policy m)})))))))
    (let [groups (group-by (juxt :suite :claims :scoring :runner) @active-manifests)]
      (doseq [[key entries] (sort-by (comp count second) groups)
              :when (> (count entries) 1)]
        (let [ids (map :benchmark/id entries)]
          (swap! errors conj (str "Duplicate active benchmark structure: " (str/join ", " ids)
                                  " share suite, claims, scoring, and runner")))))))

;; ───────────────────────────────────────────────────────────────────────
;; Filesystem-only path detection (paths that would break in JAR contexts)
;; ───────────────────────────────────────────────────────────────────────

(defn- validate-no-bare-filesystem-paths
  [errors]
  (let [scenario-dir hash-ref/scenarios-edn-dir]
    (doseq [reg-path [hash-ref/prf-core-pack-registry-path
                      hash-ref/sew-pack-registry-path]]
      (when-let [pack (read-edn reg-path)]
        (let [pack-dir (.getParent (io/file reg-path))]
          (doseq [ref (:benchmarks pack)]
            (let [manifest-path (str pack-dir "/" (:benchmark/file ref))]
              (when-let [m (read-edn manifest-path)]
                ;; Check suite path types
                (let [suite-key (:benchmark/scenario-suite m)]
                  (when suite-key
                    (let [paths (suites/suite-paths suite-key)]
                      (doseq [p paths]
                        (when (and (string? p)
                                   (not (str/starts-with? p hash-ref/resource-prefix))
                                   (not (str/starts-with? p "file:"))
                                   (not (str/starts-with? p scenario-dir))
                                   (.exists (io/file p)))
                          (swap! errors conj (str "Bare filesystem path in suite " suite-key
                                                  " of " (:benchmark/id ref) ": " p
                                                  " — use resource: prefix for JAR portability")))))))))))))))

;; ───────────────────────────────────────────────────────────────────────
;; Public API
;; ───────────────────────────────────────────────────────────────────────

(defn validate
  "Validate benchmark pack definitions and referenced resources."
  [_]
  (println "Validating benchmarks...")
  (let [errors (atom [])
        registry-file hash-ref/benchmark-registry-bare-path]
    (if-not (file-exists? registry-file)
      (swap! errors conj "Benchmark registry not found: benchmarks/registry.edn")
      (if-let [registry-data (read-edn registry-file)]
        (do
          (println "  Registry structure...")
          (validate-global-registry errors registry-data)
          (println "  Concept files...")
          (validate-concepts errors)
          (println "  Scoring definitions...")
          (validate-scoring errors)
          (println "  Claim registry...")
          (validate-claim-registry errors)
          (println "  Pack capabilities...")
          (validate-pack-capabilities errors)
          (println "  Duplicate detection...")
          (validate-duplicates errors)
          (println "  Bare filesystem path detection...")
          (validate-no-bare-filesystem-paths errors))
        (swap! errors conj "Global registry unreadable: benchmarks/registry.edn")))
    (let [exit-code (if (empty? @errors) 0 1)]
      (doseq [e @errors] (println (str "  ✗ " e)))
      (println (str "  " (count @errors) " error(s)"))
      {:exit-code exit-code
       :message (if (zero? exit-code)
                  "Benchmark validation passed"
                  "Benchmark validation failed")
       :errors @errors})))
