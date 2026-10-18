(ns resolver-sim.research.repository-boundary-dependency-test
  "A small extraction guard over the current direct namespace requires."
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def boundary-sources
  {'resolver-sim.accounting.reductions
   "packages/accounting-reductions/clj/src/resolver_sim/accounting/reductions.clj"
   'resolver-sim.research.accelerated-accounting
   "src/resolver_sim/research/accelerated_accounting.clj"
   'resolver-sim.research.accounting-semantics
   "src/resolver_sim/research/accounting_semantics.clj"
   'resolver-sim.network.researcher-challenges
   "src/resolver_sim/network/researcher_challenges.clj"
   'resolver-sim.research.researcher-challenges
   "src/resolver_sim/research/researcher_challenges.clj"
   'resolver-sim.research.shortfall-pilot
   "src/resolver_sim/research/shortfall_pilot.clj"
   'resolver-sim.network.shortfall-pilot-adapter
   "src/resolver_sim/network/shortfall_pilot_adapter.clj"
   'resolver-sim.research.native-accounting-work
   "src/resolver_sim/research/native_accounting_work.clj"
   'resolver-sim.research.runner-transport
   "src/resolver_sim/research/runner_transport.clj"
   'resolver-sim.benchmark.research-analysis
   "src/resolver_sim/benchmark/research_analysis.clj"
   'resolver-sim.benchmark.research-claim-acceptance
   "src/resolver_sim/benchmark/research_claim_acceptance.clj"
   'resolver-sim.benchmark.research-claim-lifecycle
   "src/resolver_sim/benchmark/research_claim_lifecycle.clj"
   'resolver-sim.benchmark.researcher-reward
   "src/resolver_sim/benchmark/researcher_reward.clj"
   'resolver-sim.evidence.forensic-graph
   "src/resolver_sim/evidence/forensic_graph.clj"})

(defn- direct-prf-requires [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (let [form (binding [*read-eval* false] (read reader))
          require-clause (some #(when (and (seq? %) (= :require (first %))) %)
                               (drop 2 form))]
      (into #{}
            (keep (fn [spec]
                    (let [required-ns (if (vector? spec) (first spec) spec)]
                      (when (and (symbol? required-ns)
                                 (or (.startsWith (str required-ns) "resolver-sim.")
                                     (.startsWith (str required-ns) "prf.")))
                        required-ns))))
            (rest require-clause)))))

(defn- boundary-graph []
  (into {}
        (map (fn [[namespace path]]
               [namespace (direct-prf-requires path)]))
        boundary-sources))

(defn- ns-dependencies [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (let [form (binding [*read-eval* false] (read reader))
          clauses (filter #(and (seq? %) (contains? #{:require :require-macros :use} (first %)))
                          (drop 2 form))]
      (into #{}
            (mapcat (fn [clause]
                      (keep (fn [spec]
                              (let [required-ns (if (vector? spec) (first spec) spec)]
                                (when (and (symbol? required-ns)
                                           (or (str/starts-with? (str required-ns) "resolver-sim.")
                                               (str/starts-with? (str required-ns) "prf.")))
                                  required-ns)))
                            (rest clause)))
                    clauses)))))

(defn- repository-source-graph []
  (let [canonical-source (io/resource "prf/canonical/codec.clj")
        canonical-root (when canonical-source
                         (-> canonical-source .toURI io/file .getParentFile
                             .getParentFile .getParentFile))
        roots (cond-> ["src" "packages/accounting-reductions/clj/src"
               "packages/pro-rata-allocation/clj/src"]
                canonical-root (conj canonical-root))
        files (filter #(and (.isFile ^java.io.File %)
                            (str/ends-with? (.getName ^java.io.File %) ".clj"))
                      (mapcat #(file-seq (io/file %)) roots))]
    (into {}
          (keep (fn [file]
                  (try
                    (with-open [reader (java.io.PushbackReader. (io/reader file))]
                      (let [form (binding [*read-eval* false] (read reader))]
                        (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                          [(second form) (ns-dependencies file)])))
                    (catch Exception _ nil)))
                files))))

(defonce ^:private cached-repository-source-graph (delay (repository-source-graph)))

(defn- module-contract []
  (with-open [reader (java.io.PushbackReader.
                      (io/reader "packages/module-boundaries.edn"))]
    (binding [*read-eval* false] (read reader))))

(defn- read-edn-file [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (binding [*read-eval* false] (read reader))))

(defn- module-owners [contract]
  (into {'resolver-sim.accounting.reductions :accounting
         'resolver-sim.network.researcher-challenges :researcher-network
         'resolver-sim.hash.reference :prf-canonical}
        (map (fn [{:keys [namespace owner]}] [namespace owner]))
        (:researcher-network-source-closure contract)))

(defn- namespace-source [namespace]
  (let [segments (map #(str/replace % "-" "_") (str/split (str namespace) #"\."))
        relative (str (str/join "/" segments) ".clj")]
    (cond
      (= namespace 'resolver-sim.accounting.reductions)
      (str "packages/accounting-reductions/clj/src/" relative)
      (= "prf" (first segments))
      (some-> (io/resource relative) .toURI io/file str)
      :else (str "src/" relative))))

(defn- transitive-dependencies [graph root]
  (loop [pending (seq (get graph root)) seen #{}]
    (if-let [node (first pending)]
      (if (contains? seen node)
        (recur (next pending) seen)
        (recur (concat (get graph node) (next pending)) (conj seen node)))
      seen)))

(defn- has-cycle? [graph]
  (let [visiting (atom #{})
        visited (atom #{})
        graph-nodes (set (keys graph))]
    (letfn [(visit [node]
              (cond
                (contains? @visiting node) true
                (contains? @visited node) false
                :else
                (do
                  (swap! visiting conj node)
                  (let [cycle? (boolean
                                (some visit
                                      (filter graph-nodes (get graph node))))]
                    (swap! visiting disj node)
                    (swap! visited conj node)
                    cycle?))))]
      (boolean (some visit graph-nodes)))))

(deftest semantic-boundaries-do-not-import-application-or-transport
  (let [graph (boundary-graph)
        accounting-deps (graph 'resolver-sim.accounting.reductions)
        economic-deps (graph 'resolver-sim.research.accounting-semantics)
        challenge-deps (graph 'resolver-sim.network.researcher-challenges)
        analysis-deps (graph 'resolver-sim.benchmark.research-analysis)
        runner-deps (graph 'resolver-sim.research.runner-transport)]
    (is (not-any? accounting-deps
                  ['resolver-sim.network.researcher-challenges
                   'resolver-sim.benchmark.research-analysis
                   'resolver-sim.research.runner-transport]))
    (is (= #{'prf.canonical.hash 'prf.canonical.reference}
           accounting-deps))
    (is (contains? (graph 'resolver-sim.research.accelerated-accounting)
                   'resolver-sim.accounting.reductions))
    (is (contains? (graph 'resolver-sim.research.researcher-challenges)
                   'resolver-sim.network.researcher-challenges))
    (is (not-any? challenge-deps
                  ['resolver-sim.research.shortfall-pilot
                   'resolver-sim.research.runner-transport]))
    (is (not-any? economic-deps
                  ['resolver-sim.network.researcher-challenges
                   'resolver-sim.accounting.reductions
                   'resolver-sim.research.runner-transport]))
    (is (not-any? analysis-deps
                  ['resolver-sim.network.researcher-challenges
                   'resolver-sim.research.runner-transport]))
    (is (not-any? runner-deps
                  ['resolver-sim.network.researcher-challenges]))
    (is (every? challenge-deps
                ['resolver-sim.research.api
                 'resolver-sim.incentives.api
                 'resolver-sim.evidence.forensic-graph]))
    (is (contains? (graph 'resolver-sim.network.shortfall-pilot-adapter)
                   'resolver-sim.research.shortfall-pilot))
    (is (contains? (graph 'resolver-sim.network.shortfall-pilot-adapter)
                   'resolver-sim.network.researcher-challenges))
    (is (not-any? (graph 'resolver-sim.research.shortfall-pilot)
                  ['resolver-sim.network.researcher-challenges
                   'resolver-sim.network.shortfall-pilot-adapter]))
    (is (not (has-cycle? graph)))))

(deftest adapter-dependencies-are-visible-for-extraction-planning
  (let [graph (boundary-graph)]
    (is (contains? (graph 'resolver-sim.research.native-accounting-work)
                   'resolver-sim.accounting.reductions))
    (is (contains? (graph 'resolver-sim.research.runner-transport)
                   'resolver-sim.research.native-accounting-work))
    (is (not (contains? (graph 'resolver-sim.network.researcher-challenges)
                        'resolver-sim.research.shortfall-pilot)))))

(deftest transitive-package-closures-preserve-dependency-direction
  (let [graph @cached-repository-source-graph
        accounting (transitive-dependencies graph 'resolver-sim.accounting.reductions)
        network (transitive-dependencies graph 'resolver-sim.network.researcher-challenges)
        pilot (transitive-dependencies graph 'resolver-sim.research.shortfall-pilot)
        adapter (transitive-dependencies graph 'resolver-sim.network.shortfall-pilot-adapter)]
    (is (contains? graph 'resolver-sim.accounting.reductions))
    (is (contains? graph 'resolver-sim.network.researcher-challenges))
    (is (not-any? accounting ['resolver-sim.network.researcher-challenges
                              'resolver-sim.benchmark.research-analysis
                              'resolver-sim.research.runner-transport]))
    (is (contains? accounting 'prf.canonical.hash))
    (is (contains? accounting 'prf.canonical.reference))
    (is (not-any? network ['resolver-sim.research.shortfall-pilot
                           'resolver-sim.network.shortfall-pilot-adapter
                           'resolver-sim.research.runner-transport]))
    (is (not-any? pilot ['resolver-sim.network.researcher-challenges
                         'resolver-sim.network.shortfall-pilot-adapter]))
    (is (contains? adapter 'resolver-sim.research.shortfall-pilot))
    (is (contains? adapter 'resolver-sim.network.researcher-challenges))))

(deftest researcher-network-closure-is-classified-and-direction-checked
  (let [contract (module-contract)
        source-closure (set (map :namespace (:researcher-network-source-closure contract)))
        graph @cached-repository-source-graph
        actual-closure (transitive-dependencies graph
                                                'resolver-sim.network.researcher-challenges)
        owners (module-owners contract)
        allowed (:dependency-rules contract)
        package-graph (into {} (map (fn [[package descriptor]]
                                      [package (:depends-on descriptor)]))
                            (:package-contracts contract))]
    (is (= "prf-module-boundaries.v1" (:schema contract)))
    (is (= 40 (count source-closure)) "the audited transitive source closure is explicit")
    (is (= actual-closure source-closure)
        "closure changes require ownership/classification review")
    (is (every? #(contains? graph %) source-closure)
        "every classified namespace exists in source")
    (is (every? #(.isFile (io/file (namespace-source %))) source-closure)
        "the reviewed namespace-to-source mapping resolves in-tree")
    (is (every? #(and (:owner %) (:kind %) (contains? % :required?) (:blocker %))
                (:researcher-network-source-closure contract)))
    (let [classes (vals (:dependency-classes contract))
          classified (apply set/union #{} classes)]
      (is (= source-closure classified)
          "every transitive namespace has exactly one explicit dependency class")
      (is (= (count source-closure)
             (count (mapcat identity classes)))
          "dependency classes are disjoint"))
    (is (every? (graph 'resolver-sim.network.researcher-challenges)
                ['resolver-sim.research.api 'resolver-sim.incentives.api]))
    (is (= :prf-research (get owners 'resolver-sim.research.api)))
    (is (= :prf-incentives (get owners 'resolver-sim.incentives.api)))
    (is (= :researcher-network (get owners 'resolver-sim.network.researcher-challenges)))
    (is (= :prf-research (get owners 'resolver-sim.benchmark.research-claim-acceptance)))
    (is (= :prf-research (get owners 'resolver-sim.benchmark.research-claim-lifecycle)))
    (is (= :prf-incentives (get owners 'resolver-sim.benchmark.researcher-reward)))
    (is (= :accounting (get owners 'resolver-sim.accounting.reductions)))
    (is (not (has-cycle? package-graph)) "the package contract graph must remain acyclic")
    (doseq [[source deps] graph
            :let [source-owner (get owners source)]
            :when source-owner
            dep deps
            :let [dep-owner (get owners dep)]
            :when dep-owner]
      (is (contains? (get allowed source-owner) dep-owner)
          (str "forbidden module dependency: " source " (" source-owner ") -> "
               dep " (" dep-owner ")")))
    (is (not (has-cycle? graph)) "the source namespace graph must remain acyclic")))

(deftest research-authority-and-accounting-remain-one-way-separated
  (let [graph @cached-repository-source-graph
        closure (fn [root] (transitive-dependencies graph root))
        forbidden #{'resolver-sim.network.researcher-challenges
                    'resolver-sim.benchmark.researcher-reward
                    'resolver-sim.accounting.reductions
                    'resolver-sim.research.runner-transport}
        acceptance (closure 'resolver-sim.benchmark.research-claim-acceptance)
        lifecycle (closure 'resolver-sim.benchmark.research-claim-lifecycle)
        analysis (closure 'resolver-sim.benchmark.research-analysis)
        accounting (closure 'resolver-sim.accounting.reductions)
        incentives (closure 'resolver-sim.benchmark.researcher-reward)]
    (is (empty? (set/intersection forbidden acceptance))
        "claim acceptance stays independent of incentives, accounting and network availability")
    (is (empty? (set/intersection forbidden lifecycle)))
    (is (empty? (set/intersection forbidden analysis)))
    (is (not-any? accounting
                  ['resolver-sim.benchmark.research-claim-acceptance
                   'resolver-sim.benchmark.research-claim-lifecycle
                   'resolver-sim.benchmark.researcher-reward
                   'resolver-sim.network.researcher-challenges])
        "accounting reduces authoritative inputs; it does not accept claims or issue obligations")
    (is (contains? incentives 'resolver-sim.benchmark.research-analysis)
        "incentive eligibility consumes authoritative research results")))

(deftest package-candidates-have-explicit-public-api-and-dependency-manifests
  (let [contract (module-contract)
        canonical-request (:canonical-api-requests (module-contract))
        graph @cached-repository-source-graph
        closure (fn [root] (conj (transitive-dependencies graph root) root))
        research (read-edn-file "packages/prf-research-contracts/DEPENDENCIES.edn")
        incentives (read-edn-file "packages/prf-incentives/DEPENDENCIES.edn")
        accounting (read-edn-file "packages/accounting-reductions/RELEASE.edn")]
    (is (= 'resolver-sim.research.api
           (get-in research [:public-api :namespace])))
    (is (= 'resolver-sim.incentives.api
           (get-in incentives [:public-api :namespace])))
    (is (= :in-tree-source-candidate (:package/status research)))
    (is (= :in-tree-source-candidate (:package/status incentives)))
    (is (= (set (:current-transitive-source-closure research))
           (closure 'resolver-sim.research.api))
        "research dependency manifest tracks the actual in-tree source closure")
    (is (= (set (:current-transitive-source-closure incentives))
           (closure 'resolver-sim.incentives.api))
        "incentive dependency manifest tracks the actual in-tree source closure")
    (is (= 23 (count (:current-transitive-source-closure research))))
    (is (= 26 (count (:current-transitive-source-closure incentives))))
    (doseq [[manifest actual] [[research (closure 'resolver-sim.research.api)]
                               [incentives (closure 'resolver-sim.incentives.api)]]
            :let [owned (mapcat identity (vals (:closure-ownership manifest)))]]
      (is (= actual (set owned)) "closure ownership covers every dependency")
      (is (= (count owned) (count (set owned)))
          "each dependency has exactly one semantic owner")
      (is (set/subset? (set (:direct-authoritative-source-namespaces manifest))
                       actual)))
    (is (false? (get-in research [:qualification :independently-released?])))
    (is (false? (get-in incentives [:qualification :independently-released?])))
    (is (= #{:prf-canonical}
           (set (map :id (get-in research [:runtime-dependencies :packages])))))
    (is (= #{:prf-canonical :prf-research-contracts}
           (set (map :id (get-in incentives [:runtime-dependencies :packages])))))
    (is (every? nil? (map :immutable-coordinate
                          (get-in research [:runtime-dependencies :packages]))))
    (is (every? nil? (map :immutable-coordinate
                          (get-in incentives [:runtime-dependencies :packages]))))
    (doseq [manifest [research incentives]]
      (is (= :c2 (get-in manifest [:canonical-api-contract :request-owner])))
      (is (set/subset? (get-in manifest [:canonical-api-contract
                                         :required-functions])
                       (:required-functions canonical-request)))
      (is (string? (get-in manifest [:canonical-api-contract :compatibility]))))
    (is (= "packages/prf-research-contracts/DEPENDENCIES.edn"
           (get-in contract [:package-contracts :prf-research-contracts
                             :dependency-manifest])))
    (is (= "packages/prf-incentives/DEPENDENCIES.edn"
           (get-in contract [:package-contracts :prf-incentives
                             :dependency-manifest])))
    (is (= :in-tree-source-candidate (:release/status accounting)))))
