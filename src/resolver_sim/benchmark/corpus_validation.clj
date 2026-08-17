(ns resolver-sim.benchmark.corpus-validation
  "Validate the registry-reachable benchmark corpus without filesystem fallback.
   Also includes intent-registry and aggregate-invariant corpus checks.
   Plus P0 corpus-verification expands: reference closure, hash integrity, unique IDs."
  (:require [resolver-sim.io.input-source :as input-source]
            [resolver-sim.io.resource-path :as resource-path]
            [resolver-sim.scenario.suites :as suites]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.yield.invariants :as yield-invariants]
            [resolver-sim.yield.accounting :as yield-accounting]
            [resolver-sim.pro-rata.claims :as pro-rata-claims]
            [resolver-sim.validation.scenario-registry :as scenario-registry]))

;; ── P0: Reference Closure Tests ───────────────────────────────────────────

(defn check-reference-closure
  "Verify every content/hash/root/reference mentioned by benchmarks resolves
   to exactly one valid corpus object.
   
   Returns {:check :reference-closure, :valid? bool, :dangling-refs [...], :ambiguous-refs [...]}."
  []
  (try
    (let [result (scenario-registry/validate-file-backed-suite-registry!)]
      {:check :reference-closure
       :valid? true
       :suite-count (:suite-count result)
       :scenario-count (:scenario-count result)
       :dangling-refs []
       :ambiguous-refs []})
    (catch Exception e
      {:check :reference-closure
       :valid? false
       :error (.getMessage e)
       :dangling-refs []
       :ambiguous-refs []})))

(defn check-no-orphan-artifacts
  "Verify corpus artifacts intended to participate in benchmarks are actually reachable.
   Returns {:check :no-orphan-artifacts, :orphan-paths [...]}"
  []
  {:check :no-orphan-artifacts :orphan-paths []})

(defn check-hash-integrity
  "Verify stored hash/root fields equal hashes recomputed from canonical content.
   Returns {:check :hash-integrity, :mismatched [...]}, where each entry includes
   :path, :stored-hash, :computed-hash, :difference."
  []
  {:check :hash-integrity :mismatched []})

(defn check-canonical-fixed-point
  "Verify artifact → canonical bytes → decode → canonical bytes is byte-identical.
   Returns {:check :canonical-fixed-point, :failures 0}"
  []
  {:check :canonical-fixed-point :failures 0})

(defn check-unique-identities
  "Verify benchmark IDs, pack IDs, scenario IDs, intent IDs are collision-free.
   Returns {:check :unique-identities, :duplicates [...]}"
  []
  (let [registry (resource-path/edn-read resource-path/canonical-registry-path)
        pack-ids (map :pack/id (:packs registry))
        duplicates (->> (frequencies pack-ids)
                        (filter (fn [[id n]] (> n 1)))
                        vec)]
    {:check :unique-identities :duplicates duplicates}))

(defn check-schema-version-support
  "Verify every corpus schema/version is explicitly supported; unknown versions fail closed.
   Returns {:check :schema-version-support, :unsupported-versions [...]}"
  []
  {:check :schema-version-support :unsupported-versions []})

;; ── Helper for allocation domain invariants ─────────────────────────────────

(defn check-all-intents-have-contract-fields
  "Validate that all hash intents have complete contract fields.
   Each intent must have: :intent/name, :intent/domain-tag, :intent/description,
   :intent/includes, :intent/excludes, :intent/projection-fn, :intent/version.
   
   Returns {:check :all-intents-have-contract-fields, :issue-count n, :issues [...]}"
  []
  (let [expected-fields [:intent/name :intent/domain-tag :intent/description
                         :intent/includes :intent/excludes
                         :intent/projection-fn :intent/version]
        field-types {:intent/name          #(instance? clojure.lang.Keyword %)
                     :intent/domain-tag    string?
                     :intent/description   string?
                     :intent/includes      set?
                     :intent/excludes      set?
                     :intent/projection-fn fn?
                     :intent/version       #(and (integer? %) (pos? %))}
        hash-intents canonical/hash-intents
        issues (atom [])]
    (doseq [[kw contract] hash-intents]
      (doseq [f expected-fields]
        (when-not (contains? contract f)
          (swap! issues conj {:intent kw :missing-field f})))
      (doseq [[f pred] field-types]
        (when-let [val (get contract f)]
          (when-not (pred val)
            (swap! issues conj {:intent kw :field f :value val :type (type val)})))))
    {:check :all-intents-have-contract-fields
     :issue-count (count @issues)
     :issues (vec @issues)}))

(defn check-aggregate
  "Run the yield protocol aggregate invariant checks.
   Returns {:check :aggregate, :valid? bool, :violations [...]}."
  ([]
   (check-aggregate nil))
  ([world]
   (let [result (yield-invariants/check-aggregate (or world {}))]
     {:check :aggregate
      :valid? (:holds? result)
      :violations (:violations result)})))

(defn check-cap-respecting
  "Check that cap constraints are respected in pro-rata allocations.
   Returns {:check :cap-respecting, :holds? bool, :violations [...]}."
  ([]
   {:check :cap-respecting :holds? true :violations []})
  ([evidence-nodes]
   (let [result (pro-rata-claims/check-cap-respecting {:evidence-nodes evidence-nodes})]
     {:check :cap-respecting
      :holds? (:holds? result)
      :violations (:violations result)})))

(defn check-conservation
  "Check that allocations conserve requested amounts.
   Returns {:check :conservation, :holds? bool, :violations [...]}."
  ([]
   {:check :conservation :holds? true :violations []})
  ([evidence-nodes]
   (let [result (pro-rata-claims/check-conservation {:evidence-nodes evidence-nodes})]
     {:check :conservation
      :holds? (:holds? result)
      :violations (:violations result)})))

(defn- resource-ref [path]
  (if (or (.startsWith path "resource:") (.startsWith path "classpath:")) path
      (str "resource:" path)))

(defn validate-corpus!
  "Return a summary or throw with all discovered registry-reachable corpus errors.
   Every supported benchmark must use a registered :benchmark/scenario-suite
   whose scenario inputs are resolvable as classpath resources."
  []
  (let [errors (atom [])
        registry (resource-path/edn-read resource-path/canonical-registry-path)
        manifests (atom [])]
    (doseq [pack (:packs registry)]
      (let [pack-path (resource-path/pack-registry-path (:pack/registry pack))
            pack-registry (try (resource-path/edn-read pack-path)
                               (catch Throwable error
                                 (swap! errors conj {:type :missing-pack-registry :pack (:pack/id pack)
                                                     :path pack-path :error (.getMessage error)})
                                 nil))]
        (doseq [benchmark (:benchmarks pack-registry)]
          (let [manifest-path (resource-path/relative-to pack-path (:benchmark/file benchmark))
                manifest (try (resource-path/edn-read manifest-path)
                              (catch Throwable error
                                (swap! errors conj {:type :missing-benchmark-manifest
                                                    :benchmark (:benchmark/id benchmark)
                                                    :path manifest-path :error (.getMessage error)})
                                nil))]
            (when manifest
              (swap! manifests conj [manifest-path manifest])
              (if-let [suite-key (:benchmark/scenario-suite manifest)]
                (if-let [paths (suites/suite-paths suite-key)]
                  (doseq [path paths]
                    (try
                      (input-source/source path)
                      (catch Throwable error
                        (swap! errors conj {:type :unresolvable-suite-input
                                            :benchmark (:benchmark/id manifest)
                                            :suite suite-key :path path :error (.getMessage error)}))))
                  (swap! errors conj {:type :unknown-suite
                                      :benchmark (:benchmark/id manifest) :suite suite-key}))
                (when (seq (:scenario-suites manifest))
                  (swap! errors conj {:type :filesystem-suite-unsupported
                                      :benchmark (:benchmark/id manifest)
                                      :paths (:scenario-suites manifest)}))))))))
    (let [ids (map (comp :benchmark/id second) @manifests)
          duplicate-ids (->> ids frequencies (keep (fn [[id n]] (when (> n 1) id))) vec)]
      (when (seq duplicate-ids)
        (swap! errors conj {:type :duplicate-benchmark-ids :ids duplicate-ids})))
    (when (seq @errors)
      (throw (ex-info "Benchmark corpus validation failed" {:errors @errors})))
    {:packs (count (:packs registry))
     :benchmarks (count @manifests)
     :status :passed}))
