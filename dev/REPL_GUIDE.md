# REPL Guide for Agent-C Repository

This guide helps you get familiar with the Agent-C repository through interactive REPL exploration. The repository contains a Clojure codebase for resolver simulations, pro-rata allocations, and evidence systems.

## Table of Contents

- [Getting Started](#getting-started)
- [Common REPL Functions](#common-repl-functions)
- [Running Scenarios](#running-scenarios)
- [Exploring the Codebase](#exploring-the-codebase)
- [Pro-Rata Allocations](#pro-rata-allocations)
- [Portal Integration](#portal-integration)
- [Testing](#testing)
- [Example Workflows](#example-workflows)

## Getting Started

1. Start a REPL session (e.g., using `clj` or your preferred Clojure environment)
2. Load the user namespace:

```clojure
(require '[user :as user])
```

3. Initialize the portal for visualization:

```clojure
(user/ready)  ; Opens portal and sets up tap> integration
```

## Common REPL Functions

### Basic Utilities (from `dev.repl`)

```clojure
;; Pretty print with return value
(pp some-data)

;; Tap data to portal with optional label
(tap> {:data "some value"})
(spy some-value)
(spy :label some-value)

;; Get sorted keys from a map
(keys+ some-map)

;; Select specific keys from a map
(select-keys+ some-map [:key1 :key2])

;; Summarize a data structure
(summarize-map large-collection)

;; Tap with summary
(tap-summary large-data)

;; Preload all framework namespaces
(preload-framework!)
```

### Portal Functions

```clojure
;; Open portal (automatically adds tap> handler)
(user/portal)

;; Close portal
(user/close-portal)

;; Clear portal
(p/clear)
```

### Namespace Reloading

```clojure
;; Refresh changed namespaces
(user/reset)

;; Full refresh of all namespaces
(user/reset-all)
```

## Running Scenarios

The repository includes a comprehensive scenario system for testing different situations.

**Note about scenario names:** Scenarios have names like `"S01 baseline-happy-path"` (with a space and description).

```clojure
;; List all available scenarios (S01-S107)
;; Note: Make sure to require the namespace first
(require '[dev.scenarios :as scenarios] :reload)
(scenarios/list-scenarios)

;; Find scenarios matching a pattern
(scenarios/list-scenarios "103")  ; Find S103 scenarios
(scenarios/list-scenarios "yield")  ; Find yield-related scenarios
(scenarios/list-scenarios "reversal")  ; Find reversal scenarios
(scenarios/list-scenarios "baseline")  ; Find baseline scenarios
```

;; Run a scenario by its registry name (with space and description)
(scenarios/run-scenario "S01 baseline-happy-path")
(scenarios/run-scenario "S103 l2-reversal-slash-ids")

;; Or by scenario ID (will find the first matching scenario)
(scenarios/run-scenario :S01)  ; Finds "S01 baseline-happy-path"
(scenarios/run-scenario :S103)  ; Finds "S103 l2-reversal-slash-ids"

;; Get a summary of a scenario
(scenarios/run-scenario-summary "S01 baseline-happy-path")
(scenarios/run-scenario-summary :S103)

;; Run the yield shortfall demo (scenario S107)
(scenarios/run-yield-shortfall-demo)

;; Run baseline scenarios
(scenarios/run-baseline)  ; Runs S01-S09
```

Scenario results are automatically tapped to portal and include:
- `:outcome` - success/failure
- `:halt-reason` - why the scenario stopped
- `:metrics` - performance and allocation metrics
- `:state` - final simulation state (contains evidence registry)

## Exploring the Codebase

```clojure
;; Find namespaces containing a keyword
(explore/find-project-ns "yield")
(explore/find-project-ns "pro-rata")

;; Find public vars (functions) containing a keyword
(explore/find-project-var "pro-rata")

;; Get all public functions in a namespace
(explore/public-vars 'resolver-sim.economics.payoffs)

;; Comprehensive search for namespaces and vars
(explore/apropos+ "allocation")
```

## Pro-Rata Allocations

The repository has extensive pro-rata allocation functionality:

### Basic Allocations

```clojure
;; Generic pro-rata allocation
(pro-rata/explain-generic-allocation 
  {:amount 100
   :items [{:id :a :weight 100 :cap 50}
           {:id :b :weight 100 :cap 100}]})

;; Sew slash allocation (historical path)
(pro-rata/explain-sew-slash-allocation 
  {:slash-amount 100
   :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}
                    {:id :resolver-b :slashable-stake 100 :available-slashable 100}]})
```

### Projection-Based Allocations

```clojure
;; Build projection artifact
(pro-rata/explain-projection-artifact 
  {:slash-amount 100
   :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}]})

;; Compare direct vs projection-based allocation
(pro-rata/explain-projection-vs-direct 
  {:slash-amount 100
   :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}]})
```

### Claims Evaluation

```clojure
;; Evaluate all 7 pro-rata claims
(pro-rata/explain-claims 
  {:slash-amount 100
   :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}]})
```

### Evidence Building

```clojure
;; Build full evidence node
(pro-rata/explain-evidence 
  {:world {}
   :slash-id :test-slash
   :workflow-id 1
   :epoch 0
   :trigger :manual
   :allocation-input {:slash-amount 100
                      :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}]}
   :allocation-result (pro-rata/explain-sew-slash-allocation 
                        {:slash-amount 100
                         :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}]})
   :transition-dependencies []
   :attribution {}})

;; One-shot evidence building from input
(pro-rata/explain-evidence-from-input 
  {:slash-amount 100
   :liable-parties [{:id :resolver-a :slashable-stake 100 :available-slashable 50}]}
  {:some-world-state true})
```

### Quick Demos

```clojure
;; Run all pro-rata demos
(pro-rata/demo-all)

;; Individual demos
(pro-rata/demo-generic)
(pro-rata/demo-sew)
(pro-rata/demo-projection)
(pro-rata/demo-vs)
(pro-rata/demo-claims)
```

## Monitoring Dashboard

The repository includes a comprehensive monitoring system for observing system behavior during scenario execution.

### Starting the Monitoring System

```clojure
;; 1. Initialize monitoring
(require '[resolver-sim.monitoring :as monitoring])
(monitoring/init-monitoring!)

;; 2. Check if monitoring is enabled
(monitoring/monitoring-enabled?)  ; Should return true

;; 3. Shutdown monitoring when done
(monitoring/shutdown-monitoring!)
```

### Accessing Monitoring Data

The monitoring system provides multiple access methods:

#### ✅ Working Components (Core Monitoring)
- **JMX Console**: Connect to port `1099` (always available)
- **Programmatic Access**: `(monitoring/get-monitoring-data)` (always available)
- **Metrics System**: Tracking and timing metrics (always available)
- **Scenario Monitoring**: Scenario execution tracking (always available)

#### ⚠️ Web Dashboard (May Require Ring Dependency)
- **Web Dashboard**: `http://localhost:8090/monitoring` (requires Ring)
- **JSON API**: `http://localhost:8090/api/monitoring` (requires Ring)

**Note**: The web dashboard requires the Ring HTTP library. If you see compilation errors, the core monitoring (JMX, metrics, programmatic access) still works perfectly.

### Dashboard Features

The dashboard shows:
- **Scenario Monitoring**: Active scenarios, completion rates, error rates
- **Thread Pool Monitoring**: Utilization, bottlenecks, critical issues
- **Metrics**: Processing rates, timing data, custom metrics
- **Alerts**: High utilization, error rate thresholds

### Configuration

Monitoring is configured in `config/monitoring.edn`:
```clojure
{:enabled true
 :dashboard-port 8090
 :jmx-port 1099
 :metrics-enabled true
 :thread-pool-monitoring true
 :alert-thresholds {...}}
```

### Using the Dashboard with Scenarios

```clojure
;; 1. Start monitoring
(monitoring/init-monitoring!)

;; 2. Run scenarios while monitoring
(scenarios/run-scenario "S103 l2-reversal-slash-ids")

;; 3. Observe real-time metrics in the dashboard
;;    - Open http://localhost:8090/monitoring in your browser
;;    - Watch scenario processing, thread pool utilization
;;    - Monitor for bottlenecks and errors

;; 4. Clean up when done
(monitoring/shutdown-monitoring!)
```

### Monitoring Functions

```clojure
;; Add custom metrics
(monitoring/increment-metric [:my-module :operations])
(monitoring/timing-metric 150 [:operation :duration])

;; Monitor thread pools
(monitoring/monitor-thread-pool "my-pool" executor)

;; Get current metrics programmatically
(def counters (metrics/get-all-metric-names))
(def timing-stats (metrics/get-histogram-stats [:operation :duration]))
(tap> {:counters counters :timing timing-stats})

;; Get specific metrics
(def scenario-count (metrics/get-counter [:scenario :completed]))
(def avg-duration (metrics/get-gauge [:operation :avg-duration]))
```

## Validation and Testing

### Code Validation

```clojure
;; Format code (run after making changes)
;; bb fmt

;; Lint code
;; bb lint

;; Run full validation pipeline
;; bb validate
```

### Testing

```clojure
;; Run all tests
(tests/run-all-tests)

;; Run tests matching a pattern
(tests/run-tests-matching "partial-fill")
(tests/run-tests-matching "pro-rata")

;; Get list of test namespaces
(tests/test-namespaces)

;; Load all test namespaces
(tests/require-test-namespaces!)
```

## Example Workflows

### Workflow 1: Running the Baseline Scenario (S01)

```clojure
;; 1. List available baseline scenarios
(scenarios/list-scenarios "baseline")

;; 2. Run the S01 baseline scenario
(def result (scenarios/run-scenario "S01 baseline-happy-path"))
;; Or by ID:
;; (def result (scenarios/run-scenario :S01))

;; 3. Explore the result structure
(tap> (keys result))
(tap> (select-keys result [:outcome :halt-reason :metrics]))

;; 4. Access the evidence registry
(def evidence-registry (get-in result [:state :evidence-registry]))
(when (nil? evidence-registry)
  (println "Note: Baseline scenario S01 may not create evidence nodes.")
  (println "Try a more complex scenario like S103 for evidence exploration."))
(tap> (keys evidence-registry))

;; 5. Examine evidence nodes
(def evidence-nodes (vals evidence-registry))
(def first-node (first evidence-nodes))
(tap> (keys first-node))
(tap> (:subject first-node))

;; 6. Explore evidence hash chains
(defn print-hash-chain [node]
  (println "Evidence:" (:evidence/hash node))
  (println "Subject:" (:subject node))
  (when-let [projection (get-in node [:result :projection])]
    (println "Projection:" (:projection-hash projection)))
  (when-let [allocation (get-in node [:result :pro-rata])]
    (println "Allocation:" (:allocation-hash allocation))
    (println "Claims:" (count (:claim-results allocation))))
  (println "---"))

;; Apply to all evidence
(doseq [node evidence-nodes]
  (print-hash-chain node))
```

### Workflow 2: Running S103 (Reversal Scenario) with Evidence

```clojure
;; 1. List available S103 scenarios
(scenarios/list-scenarios "103")

;; 2. Run the S103 scenario (should create evidence)
(def result (scenarios/run-scenario "S103 l2-reversal-slash-ids"))
;; Or by ID:
;; (def result (scenarios/run-scenario :S103))

;; 3. Explore the result
(tap> (keys result))
(tap> (select-keys result [:outcome :halt-reason :metrics]))

;; 4. Access and explore the evidence registry
(def evidence-registry (get-in result [:state :evidence-registry]))
(when evidence-registry
  (println "Found" (count evidence-registry) "evidence nodes")
  (def evidence-nodes (vals evidence-registry))
  (def first-node (first evidence-nodes))
  
  ;; 5. Explore the first evidence node
  (tap> (keys first-node))
  (tap> (:subject first-node))
  (tap> (:evidence/hash first-node))
  
  ;; 6. Explore pro-rata allocation data
  (tap> (get-in first-node [:result :projection]))
  (tap> (get-in first-node [:result :pro-rata]))
  (tap> (get-in first-node [:result :pro-rata :claim-results])))

;; 7. Use the helper function to explore all evidence
(explore-all-evidence result)
```

### Workflow 3: Exploring a New Concept

```clojure
;; 1. Find relevant namespaces
(explore/find-project-ns "yield")

;; 2. Explore specific namespace
(explore/public-vars 'resolver-sim.yield.core)

;; 3. Run related scenarios
(scenarios/run-scenario-summary :S107)  ; Yield shortfall demo

;; 4. Tap results to portal for visualization
(tap> (scenarios/run-scenario :S107))
```

### Workflow 2: Testing Pro-Rata Changes

```clojure
;; 1. Run baseline scenarios
(scenarios/run-baseline)

;; 2. Test specific pro-rata functionality
(pro-rata/demo-all)

;; 3. Compare direct vs projection approaches
(pro-rata/explain-projection-vs-direct sample-input)

;; 4. Evaluate claims
(pro-rata/explain-claims sample-input)

;; 5. Run related tests
(tests/run-tests-matching "pro-rata")
```

### Workflow 3: Debugging with Portal

```clojure
;; 1. Open portal
(user/portal)

;; 2. Run scenario with tap>
(scenarios/run-scenario :S103)

;; 3. Add spy points in code
(spy :after-allocation (calculate-allocation input))

;; 4. Use tap-summary for large data
(tap-summary large-result)

;; 5. Clear portal when done
(p/clear)
```

## Quick Reference: Connecting to Monitoring Dashboard

### 1. Start the monitoring system
```clojure
(require '[resolver-sim.monitoring :as monitoring])
(monitoring/init-monitoring!)
```

### 2. Access monitoring data

**✅ Always Available (Core Monitoring):**
- **JMX Console**: Connect to port `1099`
- **Programmatic Access**:
  ```clojure
  (def data (monitoring/get-monitoring-data))
  (tap> data)  ; View in portal
  ```

**⚠️ Web Dashboard (Requires Ring Dependency):**
- **Web URL**: [http://localhost:8090/monitoring](http://localhost:8090/monitoring)
- **JSON API**: [http://localhost:8090/api/monitoring](http://localhost:8090/api/monitoring)

### 3. Run scenarios with monitoring
```clojure
;; Require the metrics namespace for detailed access
(require '[resolver-sim.monitoring.metrics :as metrics])

;; Run a scenario while monitoring
(scenarios/run-scenario "S103 l2-reversal-slash-ids")

;; Access monitoring data:
;; - Use JMX console (port 1099) - always available
;; - Use programmatic access with correct functions:
(def all-metrics (metrics/get-all-metric-names))
(def timing-stats (metrics/get-histogram-stats [:scenario :duration]))
(tap> {:metrics all-metrics :timing timing-stats})

;; Get specific metrics:
(def completed (metrics/get-counter [:scenario :completed]))
(def failed (metrics/get-counter [:scenario :failed]))
(println "Completed:" completed "Failed:" failed)

;; If Ring is available: Use web dashboard at http://localhost:8090/monitoring
```

### 4. Shutdown when done
```clojure
(monitoring/shutdown-monitoring!)
```

## Tips and Best Practices

1. **Use Portal for Complex Data**: Always tap complex results to portal for better visualization
2. **Small Steps**: Break exploration into small, focused REPL evaluations
3. **Reset Often**: Use `(user/reset)` after making code changes
4. **Leverage Scenarios**: The scenario system covers most edge cases
5. **Check Claims**: Use the claims system to validate your allocations
6. **Compare Approaches**: Use `explain-projection-vs-direct` to ensure consistency
7. **Validate Changes**: After making changes, run `bb validate` to ensure formatting and linting pass
8. **Reload Namespaces**: When functions aren't found, use `:reload` to ensure you have the latest versions

## Troubleshooting

### "No such var" errors
If you get `No such var: scenarios/list-scenarios`, make sure to:
1. Require the namespace: `(require '[dev.scenarios :as scenarios] :reload)`
2. Check you're in the correct namespace: `(ns user)`
3. Reset if needed: `(user/reset)`

### Scenario not found
If `(scenarios/run-scenario :S103)` fails:
1. List available scenarios: `(scenarios/list-scenarios "103")`
2. Use the exact name with space: `(scenarios/run-scenario "S103 l2-reversal-slash-ids")`
3. Check the registry directly:
   ```clojure
   (require '[resolver-sim.protocols.sew.invariant-scenarios :as inv-sc])
   (filter #(re-find #"103" %) (keys @inv-sc/all-scenarios))
   ```
4. Remember scenario names have the format: `"S01 baseline-happy-path"` (with space and description)

### Monitoring dashboard errors
If you see `Syntax error compiling at (resolver_sim/monitoring/dashboard.clj:1:1)`:
1. **Missing Ring dependency**: The dashboard requires Ring HTTP library which may not be loaded
2. **Core monitoring still works**: Metrics, scenario tracking, and JMX monitoring continue to function
3. **Alternative access**: Use JMX console or programmatic access instead of web dashboard

#### Solutions:
1. **Add Ring dependency** (if needed):
   ```clojure
   ;; Add to deps.edn if missing:
   {:deps {ring/ring-core {:mvn/version "1.12.0"}}}
   ```

2. **Use JMX monitoring instead**:
   ```clojure
   ;; Connect JMX console to port 1099
   ;; Provides access to all monitoring metrics
   ```

3. **Programmatic access**:
   ```clojure
   ;; Get monitoring data directly
   (def data (monitoring/get-monitoring-data))
   (tap> data)  ; Send to portal for inspection
   ```

4. **Restart monitoring**:
   ```clojure
   (monitoring/shutdown-monitoring!)
   (monitoring/init-monitoring!)
   ```

### Portal not showing data
1. Initialize portal: `(user/portal)`
2. Check tap> is added: `(add-tap #'portal.api/submit)`
3. Clear and retry: `(portal.api/clear)`

### Portal not showing data
1. Initialize portal: `(user/portal)`
2. Check tap> is added: `(add-tap #'portal.api/submit)`
3. Clear and retry: `(portal.api/clear)`

## Common Data Shapes

### Generic Allocation Input
```clojure
{:amount Number
 :items [{:id Keyword
          :weight Number
          :cap Number}]
 :rounding :floor-with-largest-remainder  ; or other rounding policy
 :remainder-policy :unallocated           ; or :distribute
 :ordering-policy :input-order}            ; or :weight-desc
```

### Sew Slash Input
```clojure
{:slash-amount Number
 :liable-parties [{:id Keyword
                   :slashable-stake Number
                   :available-slashable Number}]}
```

### Scenario Result
```clojure
{:outcome :success|:failure
 :halt-reason Keyword
 :metrics {performance and allocation metrics}
 :state {final simulation state}}
```

## Advanced Usage

```clojure
;; Preload all framework namespaces for faster exploration
(preload-framework!)

;; Create custom scenarios by examining existing ones
;; in resolver-sim.protocols.sew.invariant-scenarios

;; Build custom evidence chains using the functions
;; in resolver-sim.protocols.sew.evidence.slashing
```

This REPL guide should help you navigate and understand the Agent-C repository effectively. The interactive nature of Clojure's REPL combined with the portal visualization makes it easy to explore complex systems like pro-rata allocations and evidence chains.