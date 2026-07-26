# Familiarizing Yourself with the Repository Through the REPL

## Common Functions to Run at the REPL

### Running Scenarios

To run a scenario and explore the evidence created:

```clojure
;; First, require the necessary namespaces
(require '[dev.scenarios :as scenarios]
         '[resolver-sim.evidence.portal :as portal])

;; List available scenarios
(scenarios/list-scenarios)

;; Run a specific scenario
(def result (scenarios/run-scenario "S103 negative-yield-shortfall-cascade"))

;; Tap the results to the portal for visualization
(portal/tap result)
```

### Exploring Evidence

To programmatically explore the evidence created in a scenario run:

```clojure
;; Require the evidence namespace
(require '[resolver-sim.evidence.core :as evidence])

;; Get evidence for a specific run
(def evidence-data (evidence/get-evidence-for-run (:run-id result)))

;; Explore the evidence structure
(keys evidence-data)
```

### Monitoring Dashboard

To connect to the monitoring dashboard:

```clojure
;; Start the monitoring system
(require '[resolver-sim.monitoring :as monitoring])
(monitoring/start-monitoring-system)

;; Access monitoring data
(require '[resolver-sim.monitoring.data :as monitoring-data])
(def monitoring-stats (monitoring-data/get-monitoring-stats))
```

### Test Optimization

To view interesting metrics on test run times:

```clojure
;; Require the test metrics namespace
(require '[resolver-sim.test.metrics :as test-metrics])

;; Get test run times
(def test-times (test-metrics/get-test-run-times))

;; Sort by duration to find slow tests
(sort-by :duration test-times)
```

### Sew Protocol References

To find references to the Sew protocol:

```clojure
;; Search for Sew protocol references
(require '[resolver-sim.protocol.sew :as sew-protocol])
(sew-protocol/find-sew-references)
```

## Test Commands

### Fast Test Mode

To run only fast tests (target: < 90 seconds):

```bash
bb test:fast
```

This will:
1. Run unit tests
2. Run generator tests
3. Run contract checks
4. Run invariant scenarios
5. Run suite tests
6. Run reference validation

The output will show progress updates and a summary at the end.

### Slow Test Mode

To run only slow tests (long-running tests):

```bash
bb test:slow
```

This will run tests that take longer than 60 seconds each, such as:
- Monte Carlo tests
- Long horizon scenarios
- Other computationally intensive tests

### Full Test Suite

To run the complete test suite:

```bash
bb test:all
```

This will run all tests including both fast and slow tests.

## Troubleshooting

### Scenario Execution Issues

If you're having trouble running scenarios:

1. Verify the scenario name is correct (case-sensitive)
2. Ensure the scenario file exists in the correct location
3. Check for any required preconditions in the scenario
4. Use the exact scenario name as listed by `list-scenarios`

### Monitoring Dashboard Problems

If the monitoring dashboard isn't starting:

1. Verify there are no syntax errors in the dashboard file
2. Check the logs for any specific error messages
3. Ensure all required dependencies are properly loaded
4. The dashboard should be available at http://localhost:8090/monitoring

### Test Performance Issues

To address slow tests:

1. Identify which tests are consistently slow
2. Consider adding them to the slow test suite
3. Use `bb test:fast` for quick feedback during development
4. Use `bb test:slow` for comprehensive testing including long-running tests
5. Use `bb test:all` for complete test coverage

## Common REPL Commands

### Starting the REPL

```bash
clojure -M:repl/terminal:with-sew
```

### Running a Scenario

```clojure
(require '[dev.scenarios :as scenarios])
(def result (scenarios/run-scenario "S103 negative-yield-shortfall-cascade"))
```

### Exploring Results

```clojure
;; Pretty print the result
(pp result)

;; Tap to portal for visualization
(require '[portal.api :as p])
(add-tap #'p/submit)
(tap> result)
```

### Running Tests

```clojure
;; Run specific tests
(require '[clojure.test :as t])
(require '[resolver-sim.core-tests])
(t/run-tests 'resolver-sim.core-tests)
```

## Test Performance Optimization

The test suite has been optimized with:

1. **Fast mode**: Skips slow tests (monte-carlo, long-horizon) for quick feedback
2. **Slow mode**: Runs only long-running tests for comprehensive coverage
3. **Progress tracking**: Shows real-time progress with timestamps and counters
4. **Parallel execution**: Suite tests run in parallel for faster completion
5. **Test categorization**: Tests are categorized by expected duration

### Identifying Slow Tests

To identify slow tests and potentially move them to the slow test suite:

1. Run the full test suite and observe which tests take the longest
2. Check the test output for duration information
3. Consider moving tests that consistently take > 60 seconds to the slow suite
4. Update the test script to categorize tests appropriately

### Adding New Tests

When adding new tests:

1. Place fast tests (< 60 seconds) in the regular test functions
2. Place slow tests (> 60 seconds) in the slow test section
3. Update the test script to include new test categories as needed
4. Ensure new tests have appropriate progress tracking