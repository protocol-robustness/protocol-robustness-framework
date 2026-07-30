(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.set :as set]
         '[clojure.string :as str]
         '[resolver-sim.concepts.benchmark :as benchmark-concepts]
         '[resolver-sim.concepts.registry :as concepts-registry]
         '[resolver-sim.benchmark.coverage :as coverage]
         '[resolver-sim.scenario.suites :as suites])

(defn parse-edn [f]
  (try [(edn/read-string (slurp f)) nil]
       (catch Exception e [nil (str (.getMessage e))])))

(defn file-exists? [path]
  (.exists (io/file path)))

(defn read-edn-file [path]
  (when (file-exists? path)
    (edn/read-string (slurp path))))

(defn path-str [path]
  (if (instance? java.io.File path)
    (.getPath ^java.io.File path)
    (str path)))

(defn scoring-path-for [scoring-id]
  (let [filename (case scoring-id
                   :scoring/robustness-dimensions-v0 "robustness-dimensions-v0.edn"
                   :scoring/binary-claims-v1 "binary-claims-v1.edn"
                   :scoring/severity-weighted-robustness-v1 "severity-weighted-robustness-v1.edn"
                   :scoring/severity-weighted-v1 "severity-weighted-robustness-v1.edn"
                   :scoring/shortfall-allocation-v0 "shortfall-allocation-v0.edn"
                   nil)]
    (when filename
      (str "benchmarks/scoring/" filename))))

(defn validate-file-exists! [errors path label]
  (if (file-exists? path)
    (println "    OK" label path)
    (do (swap! errors conj (str label " missing: " path))
        (println "    FAIL" label "missing:" path))))

(defn validate-reference-validation-manifest! [errors]
  (println "  Checking reference-validation manifest...")
  (let [[manifest parse-err] (parse-edn (io/file "suites/reference-validation-v1/manifest.edn"))]
    (if parse-err
      (do (swap! errors conj (str "suites/reference-validation-v1/manifest.edn: " parse-err))
          (println "    FAIL manifest -" parse-err))
      (let [by-id (set (map :id (:scenarios manifest)))
            by-path (set (map :simulator/scenario-path (:scenarios manifest)))]
        (doseq [id ["malicious-resolver-verdict-v1"
                    "dispute-flooding-v1"
                    "autopush-settlement-v1"]]
          (when-not (by-id id)
            (swap! errors conj (str "reference-validation manifest missing public scenario id " id))
            (println "    FAIL public scenario id missing:" id)))
        (doseq [path ["scenarios/edn/S25_profit-maximizer-slash-lifecycle.edn"
                      "scenarios/edn/S62_resolver-throughput-exhaustion.edn"
                      "scenarios/edn/S05_pending-settlement-execute.edn"]]
          (when-not (by-path path)
            (swap! errors conj (str "reference-validation manifest missing simulator path " path))
            (println "    FAIL simulator path missing:" path)))))))

(defn- file-stem
  "Strip known extensions from a file name."
  [name]
  (let [extensions [".json" ".edn"]]
    (reduce (fn [n ext]
              (if (.endsWith n ext)
                (subs n 0 (- (count n) (count ext)))
                n))
            name extensions)))

(defn suite-scenario-ids
  "Return the set of expected scenario IDs for a given suite key.
   For manifest-backed suites (those with a suites/<suite-name>/manifest.edn),
   returns the manifest's public scenario IDs. For path-list suites, derives
   IDs by stripping directory and known extensions from each path."
  [suite-key]
  (let [suite-name (name suite-key)
        manifest-path (str "suites/" suite-name "/manifest.edn")]
    (if (file-exists? manifest-path)
      ;; Manifest-backed suite: use public scenario IDs
      (let [manifest (read-edn-file manifest-path)]
        (set (map :id (:scenarios manifest))))
      ;; Path-list suite: derive from file name stems
      (let [paths (suites/suite-paths suite-key)]
        (set (map (fn [p]
                    (let [f (io/file p)
                          name (.getName f)]
                      (file-stem name)))
                  paths))))))

(defn validate-scenario-ids!
  "Check that every :scenario/id in the benchmark's scenario list
   is a known scenario ID in the referenced suite."
  [errors suite-key benchmark-path scenarios]
  (let [known-ids (suite-scenario-ids suite-key)]
    (doseq [scenario scenarios]
      (let [id (:scenario/id scenario)]
        (when-not (contains? known-ids id)
          (swap! errors conj (str "scenario id \"" id "\" not found in suite "
                                  suite-key " in " benchmark-path))
          (println "    FAIL unknown scenario id \"" id "\" in suite" suite-key))))))

(defn- normalize-claim-ref
  "Normalize a single claim ref: keyword → {:claim/id <keyword>}, map kept as-is."
  [c]
  (cond
    (keyword? c) {:claim/id c}
    (map? c) c
    :else (throw (ex-info "Invalid claim ref" {:claim-ref c}))))

(defn- claim-ref-id [c]
  (or (:claim/id c) (when (keyword? c) c)))

(defn validate-benchmark-file! [errors concept-idx claim-registry benchmark-path benchmark]
  (println "    Checking benchmark..." benchmark-path)
  (validate-file-exists! errors benchmark-path "benchmark")

  (let [suite-key (:benchmark/scenario-suite benchmark)
        scoring-id (:benchmark/scoring-rule benchmark)]
    (when-not (suites/suite-paths suite-key)
      (swap! errors conj (str "unknown suite " suite-key " in " benchmark-path))
      (println "    FAIL unknown suite" suite-key))

    (when scoring-id
      (if-let [scoring-path (scoring-path-for scoring-id)]
        (validate-file-exists! errors scoring-path "scoring")
        (do (swap! errors conj (str "unknown scoring rule " scoring-id " in " benchmark-path))
            (println "    FAIL unknown scoring rule" scoring-id))))

    (when-not scoring-id
      (swap! errors conj (str "missing scoring rule in " benchmark-path))
      (println "    FAIL missing scoring rule"))

    (doseq [concept-id (:benchmark/concepts benchmark)]
      (when-not (get concept-idx concept-id)
        (swap! errors conj (str "missing concept definition " concept-id " in " benchmark-path))
        (println "    FAIL missing concept definition" concept-id)))

    (doseq [scenario (:benchmark/scenarios benchmark)]
      (let [dimension (:dimension scenario)]
        (when-not (get concept-idx dimension)
          (swap! errors conj (str "missing concept definition for scenario dimension "
                                  dimension " in " benchmark-path))
          (println "    FAIL missing scenario dimension concept" dimension))
        (when-not (contains? (set (:benchmark/concepts benchmark)) dimension)
          (swap! errors conj (str "scenario dimension " dimension
                                  " is not declared in :benchmark/concepts in " benchmark-path))
          (println "    FAIL scenario dimension not declared in :benchmark/concepts" dimension))))

    (let [scenarios (:benchmark/scenarios benchmark)]
      (when (seq scenarios)
        (validate-scenario-ids! errors suite-key benchmark-path scenarios)))

    ;; ── Scenario claim reference validation ──────────────────────
    ;; :benchmark/scenarios[*].:claim must resolve to the claim registry
    ;; or be explicitly listed as a deferred semantic claim.
    (let [deferred-claims (or (:benchmark/deferred-scenario-claims benchmark)
                              #{})
          all-registered-ids (set (keys claim-registry))
          all-scenario-claims (set (keep :claim (:benchmark/scenarios benchmark)))]
      (doseq [scenario (:benchmark/scenarios benchmark)
              :let [scenario-claim (:claim scenario)]
              :when scenario-claim]
        (cond
          (contains? all-registered-ids scenario-claim)
          (println "    OK scenario claim" scenario-claim "resolves to claim registry")

          (contains? deferred-claims scenario-claim)
          (println "    OK scenario claim" scenario-claim "is explicitly deferred (semantic claim, not evaluated)")

          :else
          (do (swap! errors conj (str "scenario claim " scenario-claim " does not resolve to claim registry or deferred-claims in " benchmark-path))
              (println "    FAIL scenario claim" scenario-claim "does not resolve — add to claim-registry.edn or :benchmark/deferred-scenario-claims")))))

    ;; ── Version and lifecycle checks ──────────────────────────────
    (let [version (:benchmark/version benchmark)]
      (when-not version
        (swap! errors conj (str "missing :benchmark/version in " benchmark-path))
        (println "    FAIL missing :benchmark/version in" benchmark-path))
      (when (and version (not (integer? version)))
        (swap! errors conj (str ":benchmark/version must be an integer in " benchmark-path))
        (println "    FAIL :benchmark/version must be an integer in" benchmark-path)))

    (let [suite-version (:benchmark/suite-pinned-version benchmark)]
      (when suite-version
        (when-not (string? suite-version)
          (swap! errors conj (str ":benchmark/suite-pinned-version must be a string in " benchmark-path))
          (println "    FAIL :benchmark/suite-pinned-version must be a string in" benchmark-path))))

    ;; ── Claim ref validation ──────────────────────────────────
    (let [claim-refs (:benchmark/claims benchmark)]
      (when (seq claim-refs)
        (doseq [ref claim-refs]
          (try
            (let [normalized (normalize-claim-ref ref)
                  id (claim-ref-id ref)]
              ;; Check claim exists in registry
              (when (and claim-registry (not (get claim-registry id)))
                (swap! errors conj (str "unknown claim " id " in " benchmark-path))
                (println "    FAIL unknown claim" id))
              ;; Warn if map ref is missing rationale
              (when (map? ref)
                (when-not (:claim/rationale ref)
                  (println "    WARN claim" id "in" benchmark-path "missing :claim/rationale"))
                (when-not (:claim/failure-meaning ref)
                  (println "    WARN claim" id "in" benchmark-path "missing :claim/failure-meaning"))))
            (catch Exception e
              (swap! errors conj (str "invalid claim ref " (pr-str ref) " in " benchmark-path ": " (.getMessage e)))
              (println "    FAIL invalid claim ref" (pr-str ref)))))))

    (when (= :active (:benchmark/status benchmark))
      (doseq [error-id (coverage/active-benchmark-errors benchmark (set (keys claim-registry)))]
        (swap! errors conj (str "active benchmark lifecycle violation " error-id " in " benchmark-path))
        (println "    FAIL active benchmark lifecycle violation" error-id)))

    ;; ── Property types ─────────────────────────────────────────
    (let [prop-types (:benchmark/property-types benchmark)]
      (when prop-types
        (doseq [pt prop-types]
          (when-not (#{:safety :liveness :integrity :fairness} pt)
            (swap! errors conj (str "unknown property type " pt " in " benchmark-path))
            (println "    FAIL unknown property type" pt)))))))

(defn- registered-domain-ids
  "Return the set of domain IDs from the global registry."
  [registry-path]
  (let [[registry _] (parse-edn (io/file registry-path))]
    (set (map :domain/id (:domains registry)))))

(defn validate-pack-registry! [errors concept-idx claim-registry registry-path domain-ids]
  (let [[data parse-err] (parse-edn (io/file registry-path))]
    (if parse-err
      (do (swap! errors conj (str registry-path ": " parse-err))
          (println "    FAIL" registry-path "-" parse-err))
      (let [pack-dir (.getParent (io/file registry-path))]
        (println "  Checking pack registry..." registry-path)
        (when (nil? (:pack/id data))
          (swap! errors conj (str registry-path " missing :pack/id"))
          (println "    FAIL" registry-path "missing :pack/id"))
        (let [pack-domain (:pack/domain data)]
          (when (and domain-ids pack-domain (not (contains? domain-ids pack-domain)))
            (swap! errors conj (str registry-path " pack domain " pack-domain " is not registered in benchmarks/registry.edn"))
            (println "    FAIL pack domain" pack-domain "not registered in benchmarks/registry.edn")))

        ;; ── Check active benchmark IDs are keywords ──────────────
        (doseq [benchmark-ref (:benchmarks data)]
          (let [bid (:benchmark/id benchmark-ref)
                status (:benchmark/status benchmark-ref)]
            (when (and (#{:active :experimental} status) bid (not (keyword? bid)))
              (swap! errors conj (str "active/experimental benchmark id " bid " must be a keyword, got " (type bid) " in " registry-path))
              (println "    FAIL benchmark id" bid "must be a keyword"))
            ;; ── Check benchmark domain resolves ──────────────
            (let [bdomain (:benchmark/domain benchmark-ref)]
              (when (and domain-ids bdomain (not (contains? domain-ids bdomain)))
                (swap! errors conj (str "benchmark domain " bdomain " for " bid " is not registered in benchmarks/registry.edn"))
                (println "    FAIL benchmark domain" bdomain "for" bid "not registered")))))
        (doseq [benchmark-ref (:benchmarks data)]
            (let [status (:benchmark/status benchmark-ref)]
            (when-not (contains? #{:active :experimental :deprecated :alias} status)
              (swap! errors conj (str "invalid :benchmark/status " status " for " (:benchmark/id benchmark-ref)))
              (println "    FAIL invalid :benchmark/status" status "for" (:benchmark/id benchmark-ref)))
            (when (= :deprecated status)
              (when-not (:deprecated-on benchmark-ref)
                (swap! errors conj (str "deprecated benchmark " (:benchmark/id benchmark-ref) " missing :deprecated-on"))
                (println "    FAIL deprecated benchmark" (:benchmark/id benchmark-ref) "missing :deprecated-on"))
              (when-not (:replaced-by benchmark-ref)
                (swap! errors conj (str "deprecated benchmark " (:benchmark/id benchmark-ref) " missing :replaced-by"))
                (println "    FAIL deprecated benchmark" (:benchmark/id benchmark-ref) "missing :replaced-by"))))
          (when (= :alias (:benchmark/status benchmark-ref))
            (println "    OK alias" (:benchmark/id benchmark-ref) "->" (:benchmark/alias-of benchmark-ref)))
          (when-not (= :alias (:benchmark/status benchmark-ref))
            (let [benchmark-path (str pack-dir "/" (:benchmark/file benchmark-ref))]
              (validate-file-exists! errors benchmark-path "benchmark file")
              (when-let [benchmark (read-edn-file benchmark-path)]
                 (validate-benchmark-file! errors concept-idx claim-registry benchmark-path
                                            (assoc benchmark :benchmark/status (:benchmark/status benchmark-ref)))))))))))

(defn validate-pack-capabilities! [errors claim-registry registry-path]
  (when-let [pack (read-edn-file registry-path)]
    (let [pack-dir (.getParent (io/file registry-path))
          manifests-by-id (into {}
                                (keep (fn [benchmark-ref]
                                        (when-let [manifest (read-edn-file
                                                            (str pack-dir "/" (:benchmark/file benchmark-ref)))]
                                          [(:benchmark/id benchmark-ref)
                                           (assoc manifest :benchmark/status (:benchmark/status benchmark-ref))])))
                                (:benchmarks pack))
          known-claim-ids (if (:claims claim-registry)
                            (set (map :claim/id (:claims claim-registry)))
                            (set (keys claim-registry)))]
      (doseq [error-id (coverage/pack-capability-errors pack manifests-by-id known-claim-ids)]
        (swap! errors conj (str "pack capability violation " error-id " in " registry-path))
        (println "    FAIL pack capability violation" error-id)))))
 
(defn check-benchmark-out-of-scope!
  "Validate :concept/out-of-scope parity for benchmark concepts.
   Enforces the same rules as data/concepts: required, vector of strings,
   non-empty, no duplicates."
  [errors path id c]
  (let [oos (:concept/out-of-scope c)]
    (cond
      (nil? oos)
      (do (swap! errors conj (str (path-str path) " concept " id " :concept/out-of-scope is required"))
          (println "    FAIL" (path-str path) "concept" id "missing :concept/out-of-scope"))

      (not (vector? oos))
      (do (swap! errors conj (str (path-str path) " concept " id " :concept/out-of-scope must be a vector"))
          (println "    FAIL" (path-str path) "concept" id ":concept/out-of-scope must be a vector"))

      (empty? oos)
      (do (swap! errors conj (str (path-str path) " concept " id " :concept/out-of-scope must be non-empty for production concepts"))
          (println "    FAIL" (path-str path) "concept" id ":concept/out-of-scope must be non-empty"))

      :else
      (do (doseq [s oos]
            (when-not (string? s)
              (swap! errors conj (str (path-str path) " concept " id " :concept/out-of-scope entry " (pr-str s) " is not a string"))
              (println "    FAIL" (path-str path) "concept" id "out-of-scope entry not a string")))
          (let [dups (set (for [[v f] (frequencies oos) :when (> f 1)] v))]
            (doseq [d dups]
              (swap! errors conj (str (path-str path) " concept " id " :concept/out-of-scope has duplicate entry: " (pr-str d)))
              (println "    FAIL" (path-str path) "concept" id "duplicate out-of-scope entry" (pr-str d))))
          (let [normalised (mapv (fn [s] [(-> s clojure.string/lower-case
                                              (clojure.string/replace #"[^a-z0-9]+" " ")
                                              clojure.string/trim) s])
                                 (filter string? oos))
                by-norm (group-by first normalised)
                overlaps (keep (fn [[_ vs]] (when (> (count vs) 1) (mapv second vs))) by-norm)]
            (doseq [vs overlaps]
              (swap! errors conj (str (path-str path) " concept " id " :concept/out-of-scope has semantically overlapping entries: " (pr-str vs)))
              (println "    FAIL" (path-str path) "concept" id "semantically overlapping out-of-scope entries" (pr-str vs))))))))

(defn run-validation []
  (println "▶ benchmarks:validate\n")
  (let [errors (atom [])]
    (println "  Checking benchmark registry...")
    (let [[registry parse-err] (parse-edn (io/file "benchmarks/registry.edn"))]
      (if parse-err
        (do (swap! errors conj (str "benchmarks/registry.edn: " parse-err))
            (println "    FAIL benchmarks/registry.edn -" parse-err))
        (doseq [pack (:packs registry)]
          (validate-file-exists! errors (str "benchmarks/" (:pack/registry pack)) "pack registry"))))

    (println "  Checking benchmark concepts...")
    (let [concept-files (vec (benchmark-concepts/benchmark-concept-files))
          global-concepts (try
                            (:concepts (concepts-registry/load-registry))
                            (catch Exception _ nil))
          local-concepts (benchmark-concepts/load-benchmark-local-concepts concept-files)
          concept-idx (merge (concepts-registry/concept-index global-concepts)
                             (concepts-registry/concept-index local-concepts))]

      ;; ── Concept ID collision detection ─────────────────────────────
      (let [global-ids (set (map :concept/id global-concepts))
            local-ids (set (map :concept/id local-concepts))
            collisions (set/intersection global-ids local-ids)]
        (doseq [cid (sort collisions)]
          (let [local-file (first (for [path concept-files
                                        :let [[data _] (parse-edn path)]
                                        :when data
                                        :let [concept (first (filter #(= (:concept/id %) cid) (:concepts data)))]
                                        :when concept]
                                    (path-str path)))
                shadows-global? (some (fn [c]
                                        (and (= (:concept/id c) cid)
                                             (:concept/shadows-global? c)))
                                      local-concepts)]
            (if shadows-global?
              (println "    OK concept" cid "explicitly shadows global concept (in" local-file ")")
              (do (swap! errors conj (str "concept " cid " in " local-file " shadows global concept without :concept/shadows-global? true"))
                  (println "    FAIL concept" cid "shadows global concept — add :concept/shadows-global? true to" local-file))))))

      (doseq [path concept-files]
        (validate-file-exists! errors (path-str path) "concept file")
        (let [[data parse-err] (parse-edn path)]
          (if parse-err
            (do (swap! errors conj (str (path-str path) ": " parse-err))
                (println "    FAIL" (path-str path) "-" parse-err))
            (let [concepts (:concepts data)]
              (when-not (:concepts/version data)
                (swap! errors conj (str (path-str path) " missing :concepts/version"))
                (println "    FAIL" (path-str path) "missing :concepts/version"))
              (doseq [c concepts]
                (let [id (:concept/id c)]
                  (doseq [k [:concept/title :concept/summary :concept/stakeholder-language
                             :concept/why-it-matters]]
                    (when-not (get c k)
                      (swap! errors conj (str (path-str path) " concept " id " missing " k))
                      (println "    FAIL" (path-str path) "concept" id "missing" k)))
                  ;; Out-of-scope parity with data/concepts validation
                  (check-benchmark-out-of-scope! errors path id c)
                  (let [maps-to (:concept/maps-to c)]
                    (when-not (map? maps-to)
                      (swap! errors conj (str (path-str path) " concept " id " :concept/maps-to must be a map"))
                      (println "    FAIL" (path-str path) "concept" id ":concept/maps-to must be a map"))
                    (when (map? maps-to)
                      (let [scenarios (:scenarios maps-to)]
                        (when-not (vector? scenarios)
                          (swap! errors conj (str (path-str path) " concept " id " :maps-to :scenarios must be a vector"))
                          (println "    FAIL" (path-str path) "concept" id ":maps-to :scenarios must be a vector"))
                        (doseq [[k expected-type] [[:claims vector?] [:invariants vector?] [:evidence vector?]]]
                          (let [v (get maps-to k)]
                            (when (and v (not (expected-type v)))
                              (swap! errors conj (str (path-str path) " concept " id " :maps-to " k " must be a vector"))
                              (println "    FAIL" (path-str path) "concept" id ":maps-to" k "must be a vector"))))))))))))
      )

       (println "  Checking scoring definitions...")
      (doseq [path ["benchmarks/scoring/robustness-dimensions-v0.edn"
                    "benchmarks/scoring/binary-claims-v1.edn"
                    "benchmarks/scoring/severity-weighted-robustness-v1.edn"
                    "benchmarks/scoring/shortfall-allocation-v0.edn"]]
        (validate-file-exists! errors path "scoring"))

      (validate-reference-validation-manifest! errors)

      (println "  Checking claim registry...")
      (let [[claim-registry-data parse-err] (parse-edn (io/file "benchmarks/claim-registry.edn"))]
        (if parse-err
          (do (swap! errors conj (str "benchmarks/claim-registry.edn: " parse-err))
              (println "    FAIL claim-registry.edn -" parse-err))
          (println "    OK" (count (:claims claim-registry-data)) "claims registered"))
        (let [claim-registry (when-not parse-err
                               (into {} (map (fn [c] [(:claim/id c) c]) (:claims claim-registry-data))))]
          (let [domain-ids (registered-domain-ids "benchmarks/registry.edn")]
            (doseq [registry-path ["benchmarks/packs/prf-core/registry.edn"
                                   "benchmarks/packs/sew/registry.edn"]]
              (validate-pack-registry! errors concept-idx claim-registry registry-path domain-ids)
              (validate-pack-capabilities! errors claim-registry registry-path)))

          ;; ── Duplicate active benchmark detection ───────────────────
          (println "  Checking for duplicate active benchmark structures...")
          (let [active-manifests (atom [])]
            (doseq [registry-path ["benchmarks/packs/prf-core/registry.edn"
                                   "benchmarks/packs/sew/registry.edn"]]
              (when-let [data (read-edn-file registry-path)]
                (let [pack-dir (.getParent (io/file registry-path))]
                  (doseq [benchmark-ref (:benchmarks data)]
                    (when (= :active (:benchmark/status benchmark-ref))
                      (when-let [benchmark (read-edn-file (str pack-dir "/" (:benchmark/file benchmark-ref)))]
                        (swap! active-manifests conj
                               {:benchmark/id (:benchmark/id benchmark-ref)
                                :suite (:benchmark/scenario-suite benchmark)
                                :claims (set (keep :claim/id (:benchmark/claims benchmark)))
                                :scoring (:benchmark/scoring-rule benchmark)
                                :runner (:benchmark/runner-policy benchmark)
                                :pack-domain (:pack/domain data)})))))))
            (let [groups (group-by (juxt :suite :claims :scoring :runner) @active-manifests)]
              (doseq [[key entries] (sort-by (comp count second) groups)
                      :when (> (count entries) 1)]
                (let [ids (map :benchmark/id entries)]
                  (println "    WARN active benchmarks" (str/join ", " ids)
                           "share suite, claims, scoring, and runner —"
                           "possible structural duplication")))))))
      )

    (println)
    (if (empty? @errors)
      (println "  OK all checks passed\n\nBENCHMARK VALIDATION PASSED")
      (do (println "  ERRORS:" (count @errors))
          (doseq [e @errors] (println "    -" e))
          (println "\nBENCHMARK VALIDATION FAILED")
          (System/exit 1)))))

(run-validation)
