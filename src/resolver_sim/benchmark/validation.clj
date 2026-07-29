(ns resolver-sim.benchmark.validation
  "Static validation of all built-in benchmark resources.
   All checks use explicit resource: URIs — no CWD-relative filesystem access.
   Each check returns {:check <keyword> :passed? <bool> :details <string>}."
  (:require [resolver-sim.concepts.registry :as concepts]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.hash.reference :as hash-ref]))

;; ── Helpers ─────────────────────────────────────────────────────────────────

(def ^:private check-template
  {:check nil :passed? false :details ""})

(defn- pass [check details]
  (assoc check-template :check check :passed? true :details details))

(defn- fail [check details]
  (assoc check-template :check check :passed? false :details details))

(defn- resource-exists? [path]
  (rp/path-exists? path))

;; ── Individual checks ───────────────────────────────────────────────────────

(defn check-concept-registry
  "Load concept registry and verify all referenced concept files exist."
  []
  (try
    (let [{:keys [registry concepts]} (concepts/load-registry)
          concept-files (map :concept/file (:concepts registry))
          missing (remove #(resource-exists? (str hash-ref/resource-prefix %)) concept-files)]
      (if (seq missing)
        (fail :concept-registry (str "Missing concept files: " (pr-str missing)))
        (pass :concept-registry (str (count concepts) " concepts loaded, all files present"))))
    (catch Exception e
      (fail :concept-registry (str "Failed to load concept registry: " (.getMessage e))))))

(defn check-benchmark-registry
  "Load benchmark registry and verify all pack registries exist."
  []
  (try
    (let [registry (rp/edn-read rp/canonical-registry-path)
          packs (:packs registry)
          missing (remove (fn [p]
                            (resource-exists?
                             (rp/pack-registry-path (:pack/registry p))))
                          packs)]
      (if (seq missing)
        (fail :benchmark-registry (str "Missing pack registries: "
                                       (pr-str (mapv :pack/id missing))))
        (pass :benchmark-registry (str (count packs) " packs registered, all registries present"))))
    (catch Exception e
      (fail :benchmark-registry (str "Failed to load benchmark registry: " (.getMessage e))))))

(defn check-benchmark-packs
  "Verify every benchmark manifest file in every pack exists."
  []
  (try
    (let [registry (rp/edn-read rp/canonical-registry-path)
          packs (:packs registry)
          all-manifests (mapcat (fn [p]
                                  (let [pack-reg-path (rp/pack-registry-path (:pack/registry p))
                                        pack-reg (rp/edn-read pack-reg-path)]
                                    (map (fn [b]
                                           (rp/relative-to pack-reg-path (:benchmark/file b)))
                                         (:benchmarks pack-reg))))
                                packs)
          missing (remove resource-exists? all-manifests)]
      (if (seq missing)
        (fail :benchmark-packs (str "Missing benchmark manifests: " (pr-str missing)))
        (pass :benchmark-packs (str (count all-manifests) " benchmark manifests present"))))
    (catch Exception e
      (fail :benchmark-packs (str "Failed to check benchmark packs: " (.getMessage e))))))

(defn check-scoring-rules
  "Verify all scoring rule files exist."
  []
  (let [scoring-files [hash-ref/scoring-robustness-dimensions-path
                       hash-ref/scoring-binary-claims-path
                       hash-ref/scoring-severity-weighted-path
                       hash-ref/scoring-shortfall-allocation-path]
        missing (remove resource-exists? scoring-files)]
    (if (seq missing)
      (fail :scoring-rules (str "Missing scoring rules: " (pr-str missing)))
      (pass :scoring-rules (str (count scoring-files) " scoring rules present")))))

(defn check-fixture-suites
  "Load fixture suite manifest and verify all suite files exist."
  []
  (try
    (let [manifest (rp/edn-read (str hash-ref/resource-prefix hash-ref/fixture-suite-manifest-path))
          suite-files (keep :file (vals manifest))
          missing (remove #(resource-exists? (str hash-ref/resource-prefix hash-ref/fixtures-dir "/suites/" %))
                          suite-files)]
      (if (seq missing)
        (fail :fixture-suites (str "Missing fixture suites: " (pr-str missing)))
        (pass :fixture-suites (str (count suite-files) " fixture suites present"))))
    (catch Exception e
      (fail :fixture-suites (str "Failed to load fixture suite manifest: " (.getMessage e))))))

(defn check-concept-references
  "Verify no stale :concept/related references to unknown concept IDs."
  []
  (try
    (let [{:keys [concepts]} (concepts/load-registry)
          unknown-refs (concepts/missing-related-concepts concepts)]
      (if (seq unknown-refs)
        (fail :concept-references (str "Stale concept references: " (pr-str unknown-refs)))
        (pass :concept-references "All concept references resolve")))
    (catch Exception e
      (fail :concept-references (str "Failed to check concept references: " (.getMessage e))))))

(defn check-duplicate-ids
  "Verify no duplicate IDs across concept and benchmark registries."
  []
  (try
    (let [{:keys [concepts]} (concepts/load-registry)
          concept-ids (mapv :concept/id concepts)
          concept-dupes (keys (filter #(> (val %) 1) (frequencies concept-ids)))
          registry (rp/edn-read rp/canonical-registry-path)
          pack-benchmark-ids (mapcat (fn [p]
                                       (let [pack-reg (rp/edn-read (rp/pack-registry-path (:pack/registry p)))]
                                         (map :benchmark/id (:benchmarks pack-reg))))
                                     (:packs registry))
          bench-dupes (keys (filter #(> (val %) 1) (frequencies pack-benchmark-ids)))
          all-dupes (into concept-dupes bench-dupes)]
      (if (seq all-dupes)
        (fail :duplicate-ids (str "Duplicate IDs found: " (pr-str all-dupes)))
        (pass :duplicate-ids "No duplicate IDs detected")))
    (catch Exception e
      (fail :duplicate-ids (str "Failed to check duplicate IDs: " (.getMessage e))))))

(defn check-resource-availability
  "Verify all built-in resource: URIs resolve from the classpath.
   This check does NOT require CWD to be the repo root."
  []
  (let [required-resources
        [hash-ref/benchmark-registry-path
         (str hash-ref/resource-prefix hash-ref/concept-registry-path)
         (str hash-ref/resource-prefix hash-ref/fixture-suite-manifest-path)
         (str hash-ref/resource-prefix hash-ref/evidence-config-path)]
        results (mapv (fn [path]
                        {:path path :present? (resource-exists? path)})
                      required-resources)
        missing (filterv #(not (:present? %)) results)]
    (if (seq missing)
      (fail :resource-availability (str "Missing embedded resources: "
                                        (pr-str (mapv :path missing))))
      (pass :resource-availability (str (count required-resources)
                                        " embedded resources verified")))))

;; ── Composite checks ────────────────────────────────────────────────────────

(def all-checks
  "Vector of [check-name check-fn] for all validation checks.
   Order matters: earlier checks load shared state cached by later ones."
  [[:concept-registry check-concept-registry]
   [:benchmark-registry check-benchmark-registry]
   [:benchmark-packs check-benchmark-packs]
   [:scoring-rules check-scoring-rules]
   [:fixture-suites check-fixture-suites]
   [:concept-references check-concept-references]
   [:duplicate-ids check-duplicate-ids]
   [:resource-availability check-resource-availability]])

(defn validate-all
  "Run all static validation checks.
   Returns {:valid? <bool> :checks [map] :summary <string>}."
  []
  (let [results (mapv (fn [[_name f]] (f)) all-checks)
        total (count results)
        passed (count (filter :passed? results))
        failed (remove :passed? results)]
    {:valid? (empty? failed)
     :checks results
     :summary (str passed "/" total " checks passed")}))

(defn validate-resources
  "Run only the resource-availability check."
  []
  (let [result (check-resource-availability)]
    {:valid? (:passed? result)
     :checks [result]
     :summary (if (:passed? result) "All resources available" "Missing resources")}))
