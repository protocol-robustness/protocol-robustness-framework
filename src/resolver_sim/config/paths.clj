(ns resolver-sim.config.paths
  "Single authority for all filesystem paths used across PRF.

   ════════════════════════════════════════════════════════════════
   CONFIGURATION AUTHORITY — PATHS
   ════════════════════════════════════════════════════════════════

   This namespace is the ONLY place that maps PRF directory/file
   locations to their concrete filesystem paths.  Every other namespace
   MUST resolve a path through here rather than embedding a string
   literal (e.g. \"scenarios/edn\") inline.

   Resolution precedence for every path:
     1. PRF_<KEY> environment variable (when defined)
     2. code-level default (usually a resolver-sim.hash.reference
        constant)

   Overrides are per-path via the :env key below.  Using env overrides
   lets consumers redirect the framework without code changes, mirroring
   the PRF_ARTIFACT_DIR / PRF_RUNS_ROOT pattern in
   resolver-sim.evidence.config.

   ════════════════════════════════════════════════════════════════"
  (:require [resolver-sim.hash.reference :as hash-ref]))

(def path-defs
  "Registry of path keys → {:default <string> :env <PRF_ env var>}.
   :env is optional; when present it takes precedence over :default."
  {:data-dir           {:default hash-ref/data-dir}
   :scenarios-dir      {:default hash-ref/scenarios-edn-dir
                        :env     "PRF_SCENARIOS_DIR"}
   :scenarios-catalog  {:default "scenarios/catalog.edn"
                        :env     "PRF_SCENARIOS_CATALOG"}
   :benchmarks-dir     {:default "benchmarks"
                        :env     "PRF_BENCHMARKS_DIR"}
   :benchmarks-registry {:default hash-ref/benchmark-registry-bare-path
                         :env     "PRF_BENCHMARKS_REGISTRY"}
   :benchmarks-concepts {:default "benchmarks/concepts"
                         :env     "PRF_BENCHMARKS_CONCEPTS_DIR"}
   :benchmarks-claim-registry {:default hash-ref/claim-registry-path
                               :env     "PRF_BENCHMARKS_CLAIM_REGISTRY"}
   :benchmarks-legacy    {:default "benchmarks/BENCHMARKS.edn"}
   :docs-dir             {:default "docs"}
   :stability-manifest   {:default "docs/STABILITY_MANIFEST.edn"
                          :env     "PRF_STABILITY_MANIFEST"}
   :notebooks-dir        {:default "notebooks"
                          :env     "PRF_NOTEBOOKS_DIR"}
   :notebooks-registry   {:default "data/notebooks.edn"
                          :env     "PRF_NOTEBOOKS_REGISTRY"}
   :fixtures-dir         {:default hash-ref/fixtures-dir}
   :traces-dir           {:default hash-ref/traces-dir
                          :env     "PRF_TRACES_DIR"}
   :traces-regression-dir {:default "data/fixtures/traces/regression"}
   :traces-store-dir      {:default "results/traces"
                           :env     "PRF_TRACES_STORE_DIR"}
   :golden-dir           {:default hash-ref/golden-dir
                          :env     "PRF_GOLDEN_DIR"}
   :fixture-manifest     {:default hash-ref/fixture-suite-manifest-path}
   :concepts-registry    {:default hash-ref/concept-registry-path}
   :concepts-dir         {:default "data/concepts"
                          :env     "PRF_CONCEPTS_DIR"}
   :claims-file          {:default "data/claims/sew-claims.edn"
                          :env     "PRF_CLAIMS_FILE"}
   :speds-definitions    {:default "data/speds/definitions.edn"
                          :env     "PRF_SPEDS_DEFINITIONS"}
   :params-baseline      {:default "data/params/baseline.edn"
                          :env     "PRF_PARAMS_BASELINE"}
   :force-authorised-sequence {:default "data/sequences/force-authorised-custody-adjustment.edn"}
   :runs-root            {:default hash-ref/results-runs-dir
                          :env     "PRF_RUNS_ROOT"}
   :runs-pick-dir        {:default "results/runs/pick-"}
   :test-artifacts-dir   {:default hash-ref/test-artifacts-dir
                          :env     "PRF_ARTIFACT_DIR"}
   :evidence-bundle-dir  {:default hash-ref/evidence-bundle-dir
                          :env     "PRF_BUNDLE_DIR"}
   :trace-compare-dir    {:default "results/trace-compare"
                          :env     "PRF_TRACE_COMPARE_DIR"}
   :benchmark-smoke-dir  {:default "results/benchmark-smoke"
                          :env     "PRF_BENCHMARK_SMOKE_DIR"}
   :notebook-focus       {:default hash-ref/notebook-focus-path}
   :evidence-latest      {:default "results/evidence/latest.edn"
                          :env     "PRF_EVIDENCE_LATEST"}
   :protocols-src-dir    {:default "protocols_src"}})

(defn path
  "Resolve a path key to its concrete filesystem path.
   Precedence: PRF_<KEY> env var (when defined) → code default."
  [k]
  (let [{:keys [default env]} (get path-defs k)]
    (or (when env (System/getenv env))
        default)))

(defn data-dir [] (path :data-dir))
(defn scenarios-dir [] (path :scenarios-dir))
(defn scenarios-catalog [] (path :scenarios-catalog))
(defn benchmarks-dir [] (path :benchmarks-dir))
(defn benchmarks-registry [] (path :benchmarks-registry))
(defn benchmarks-concepts [] (path :benchmarks-concepts))
(defn benchmarks-claim-registry [] (path :benchmarks-claim-registry))
(defn benchmarks-legacy [] (path :benchmarks-legacy))
(defn docs-dir [] (path :docs-dir))
(defn stability-manifest [] (path :stability-manifest))
(defn notebooks-dir [] (path :notebooks-dir))
(defn notebooks-registry [] (path :notebooks-registry))
(defn fixtures-dir [] (path :fixtures-dir))
(defn traces-dir [] (path :traces-dir))
(defn traces-regression-dir [] (path :traces-regression-dir))
(defn traces-store-dir [] (path :traces-store-dir))
(defn golden-dir [] (path :golden-dir))
(defn fixture-manifest [] (path :fixture-manifest))
(defn concepts-registry [] (path :concepts-registry))
(defn concepts-dir [] (path :concepts-dir))
(defn claims-file [] (path :claims-file))
(defn speds-definitions [] (path :speds-definitions))
(defn params-baseline [] (path :params-baseline))
(defn force-authorised-sequence [] (path :force-authorised-sequence))
(defn runs-root [] (path :runs-root))
(defn runs-pick-dir [] (path :runs-pick-dir))
(defn test-artifacts-dir [] (path :test-artifacts-dir))
(defn evidence-bundle-dir [] (path :evidence-bundle-dir))
(defn trace-compare-dir [] (path :trace-compare-dir))
(defn benchmark-smoke-dir [] (path :benchmark-smoke-dir))
(defn notebook-focus [] (path :notebook-focus))
(defn evidence-latest [] (path :evidence-latest))
(defn protocols-src-dir [] (path :protocols-src-dir))
