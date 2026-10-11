(ns resolver-sim.research.repository-boundary-dependency-test
  "A small extraction guard over the current direct namespace requires."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]))

(def boundary-sources
  {'resolver-sim.accounting.reductions
   "src/resolver_sim/accounting/reductions.clj"
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
                                 (.startsWith (str required-ns) "resolver-sim."))
                        required-ns))))
            (rest require-clause)))))

(defn- boundary-graph []
  (into {}
        (map (fn [[namespace path]]
               [namespace (direct-prf-requires path)]))
        boundary-sources))

(defn- has-cycle? [graph]
  (letfn [(visit [node visiting visited]
            (cond
              (contains? visiting node) true
              (contains? visited node) false
              :else (let [next-visiting (conj visiting node)]
                      (boolean
                       (some #(visit % next-visiting (conj visited node))
                             (filter (set (keys graph)) (get graph node)))))))]
    (boolean (some #(visit % #{} #{}) (keys graph)))))

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
    (is (= #{'resolver-sim.hash.canonical 'resolver-sim.hash.reference}
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
                ['resolver-sim.benchmark.research-analysis
                 'resolver-sim.benchmark.research-claim-acceptance
                 'resolver-sim.benchmark.research-claim-lifecycle
                 'resolver-sim.benchmark.researcher-reward
                 'resolver-sim.evidence.forensic-graph]))
    (is (not (has-cycle? graph)))))

(deftest adapter-dependencies-are-visible-for-extraction-planning
  (let [graph (boundary-graph)]
    (is (contains? (graph 'resolver-sim.research.native-accounting-work)
                   'resolver-sim.accounting.reductions))
    (is (contains? (graph 'resolver-sim.research.runner-transport)
                   'resolver-sim.research.native-accounting-work))
    ;; The Challenge 001 adapter currently depends on the network application.
    ;; If the network moves, move this adapter with it or invert this edge;
    ;; PRF analysis itself must not acquire a reverse dependency.
    (is (contains? (graph 'resolver-sim.research.shortfall-pilot)
                   'resolver-sim.network.researcher-challenges))
    (is (not (contains? (graph 'resolver-sim.network.researcher-challenges)
                        'resolver-sim.research.shortfall-pilot)))))
