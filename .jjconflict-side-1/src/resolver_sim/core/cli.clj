(ns resolver-sim.core.cli
  "CLI option definitions and argument validation.
   Knows nothing about phases or simulation logic."
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [resolver-sim.protocols.registry :as preg]
            [resolver-sim.config.paths :as paths]))

(def cli-options
  [["-p" "--params PATH" "Path to params.edn file"
    :default (paths/params-baseline)]
   ["-o" "--output DIR" "Output directory for results"
    :default "results"]
   ["-s" "--sweep" "Run strategy sweep (honest, lazy, malicious, collusive)"]
   ["-m" "--multi-epoch" "Run Phase J multi-epoch simulation (10+ epochs)"]
   ["-l" "--waterfall" "Run Phase L waterfall stress testing"]
   ["-g" "--governance-impact" "Run Phase M governance response time impact analysis"]
   ["-O" "--market-exit" "Run Phase O market exit cascade modeling"]
   ["-P" "--phase-p-lite" "Run Phase P Lite: falsification test (difficulty, evidence, herding)"]
   ["-Y" "--phase-y" "Run Phase Y: evidence fog and attention budget constraint"]
   ["-Z" "--phase-z" "Run Phase Z: legitimacy and reflexive participation loop"]
   ["-A" "--phase-aa" "Run Phase AA: governance as adversary / selective enforcement gaming"]

   ["-C" "--phase-ac" "Run Phase AC: trust floor and emergency onboarding (Phase Z safeguard)"]
   ["-D" "--phase-ad" "Run Phase AD: governance bandwidth floor (Phase AA safeguard)"]
   ["-E" "--phase-ac-sweep" "Run Phase AC threshold search: min viable trust-floor config"]
   ["-F" "--phase-ad-sweep" "Run Phase AD threshold search: min viable governance floor config"]
   ["-G" "--phase-ac-cap"  "Run Phase AC capacity expansion: validate the 10× capacity rule"]
   ["-H" "--phase-t" "Run Phase T: governance capture via rule drift"]
   ["-Q" "--phase-ae" "Run Phase AE: fair-slashing — capital preservation under false-positive slash"]
   ["-R" "--phase-af" "Run Phase AF: slashing epoch solvency (BM-04) — insurance pool worst-case"]
   ["-T" "--phase-ag" "Run Phase AG: EMA convergence (BM-05) — quality signal and cold-start gap"]
   ["-U" "--phase-ah" "Run Phase AH: equity divergence sweep (honest vs strategic)"]
   ["-V" "--phase-ai" "Run Phase AI: escalation trap — sybil ring forces honest resolver displacement"]
   ["-W" "--phase-f" "Run Phase F: multi-resolver collusion ring deterrence (waterfall slashing)"]
   ["-I" "--phase-p-revised" "Run Phase P Revised: sequential appeal falsification"]
   ["-J" "--phase-q" "Run Phase Q: advanced vulnerability (bribery, evidence spoofing, correlated failures)"]
   ["-K" "--phase-r" "Run Phase R: liveness & participation failure"]
   ["-L" "--phase-u" "Run Phase U: adaptive attacker learning"]
   ["-M" "--phase-v" "Run Phase V: correlated belief cascades"]
   ["-N" "--phase-w" "Run Phase W: dispute type clustering (adversarial category targeting)"]
   ["-X" "--phase-x" "Run Phase X: burst concurrency exploit"]
   ["-a" "--adversarial" "Run adversarial parameter search (falsification)"]
   [nil "--phase-f-dr" "Run Phase F DR: Economic parameter validation sweep"]
   [nil "--phase-c-dr" "Run Phase C DR: Corruption economics sweep"]
   [nil "--phase-e-dr" "Run Phase E DR: Evidence integrity sweep"]
   [nil "--phase-m-dr" "Run Phase M DR: Fairness analysis sweep"]
   ["-S" "--serve" "Start gRPC simulation server (Phase 2 live mode)"]
   [nil "--invariants" "Run deterministic invariant scenarios (in-process, no gRPC)"]
   [nil "--suite SUITE" "Run a named scenario path suite (e.g. yield-provider-scenarios, sew-yield-scenarios; use with --invariants)"]
   [nil "--fixture-suite SUITE" "Run a composed EDN fixture suite (e.g. suites/all-invariants; use with --invariants)"]
   [nil "--scenario PATH" "Path to a single executable scenario file to run (requires --invariants; EDN preferred, JSON deprecated)"]
   [nil "--output-file PATH" "Path to write the replay result JSON (requires --scenario)"]
   [nil "--scenario-filter EDN" "Declarative EDN selector for suite scenarios, e.g. '{:scenario/id-prefix \"Y\" :scenario/tags #{:shortfall}}'; marks run non-canonical"]
   [nil "--diff-traces" "Structural world diff between two replay JSON files"]
   [nil "--baseline PATH" "Baseline replay JSON (use with --diff-traces)"]
   [nil "--candidate PATH" "Candidate replay JSON (use with --diff-traces)"]
   [nil "--protocol ID" (str "Protocol to use for --invariants (default: " preg/default-protocol-id ")")
    :default preg/default-protocol-id]
   [nil "--port PORT" "gRPC server port (used with --serve, default: 7070)"
    :default 7070
    :parse-fn #(Integer/parseInt %)]
   ["-h" "--help" "Show this help"]])

(defn usage [options-summary]
  (->> ["Dispute Resolver Incentive Simulation"
        ""
        "Usage: clojure -M:run [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  clojure -M:run -p data/params/baseline.edn"
        "  clojure -M:run -p data/params/cartel.edn -s  # sweep strategies"
        "  clojure -M:run -S                        # start gRPC server on port 7070"
        "  clojure -M:run -S --port 9090            # start gRPC server on port 9090"
        "  clojure -M:run -- --invariants           # S01–S100 invariant registry"
        "  clojure -M:run -- --invariants --suite yield-provider-scenarios"
        "  clojure -M:run -- --invariants --fixture-suite suites/all-invariants"
        "  clojure -M:run -- --invariants --scenario scenarios/edn/S62_load-gradual-fallback.edn"
        "  clojure -M:run -- --diff-traces --baseline results/a.json --candidate results/b.json"
        "  clojure -M:diff-traces --baseline results/a.json --candidate results/b.json"]
       (str/join "\n")))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join "\n" errors)))

(defn validate-args [args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) {:exit-message (usage summary) :ok? true}
      errors {:exit-message (error-msg errors)}
      :else {:options options})))
