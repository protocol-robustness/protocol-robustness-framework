Clerk patterns and antipatterns
1. Granularity
Pattern: one visible idea per top-level form

A Clerk notebook reads best when each visible form corresponds to one reader-facing idea:

;; Good visible unit
(clerk/html
  (section-card
    {:title "Happy-path dispute lifecycle"
     :claim "A normal dispute resolves with conserved value."
     :body  (lifecycle-summary @lifecycle-result)}))

The reader sees one coherent result: title, claim, metrics, trace, and interpretation.

Antipattern: many tiny visible helper forms

This is the current failure mode:

(defn badge ...)
(defn notice-box ...)
(defn trace-table ...)
(defonce lifecycle-result ...)
(defonce lifecycle-display ...)

If these forms are visible, the notebook becomes a source file with occasional outputs, not a demo.

Clerk shows code and results by default unless visibility is changed, so helper functions and intermediate values need explicit hiding or folded visibility. Clerk supports document-wide and per-form visibility controls for code/results, with :show, :hide, and :fold for code.

Pattern: group setup into hidden cells
^{::clerk/visibility {:code :hide :result :hide}}
(do
  (defn badge ...)
  (defn notice-box ...)
  (defn trace-table ...)
  (defn metric-card ...))

For the quickstart notebook, this means:

helpers hidden
constants hidden
raw simulations hidden
curated displays shown
Antipattern: leaking implementation granularity into reader granularity

Bad reader path:

Shared constants
Helpers
#object[...]
defonce lifecycle-display
[:div {:style ...}]

Good reader path:

1. Happy-path dispute lifecycle

Claim:
A normal dispute resolves with conserved value.

[Outcome: PASS] [Events: 3] [Invariant failures: 0]

Trace table
What to notice
2. Visibility
Pattern: output-first namespace defaults

Use namespace-level defaults:

(ns notebooks.quickstart
  {:nextjournal.clerk/visibility {:code :fold :result :show}
   :nextjournal.clerk/toc true}
  (:require [nextjournal.clerk :as clerk]))

This makes code inspectable without making it the main reading path. Clerk allows visibility defaults on the namespace and overrides on individual top-level forms.

Antipattern: showing all code by default

For a demo notebook, default visible code creates scroll fatigue. It makes readers parse implementation before they know why the result matters.

Pattern: hide boring forms entirely
^{::clerk/visibility {:code :hide :result :hide}}
(defonce lifecycle-result
  (delay (run-scenario lifecycle-scenario)))

Use this for:

require
constants
style helpers
rendering helpers
raw scenario construction
intermediate delay values
precomputed batches
Antipattern: hiding important evidence

Do not hide everything. The notebook should still expose:

event trace
key metrics
invariant summary
evidence bundle
reproduction command
optionally folded source
3. Result shaping
Pattern: return display objects, not raw data

Reader-facing cells should usually end with:

(clerk/html
  (lifecycle-panel @lifecycle-result))

or a Clerk viewer/table/chart.

Antipattern: returning raw Hiccup

This is what creates visible noise like:

[:div {:style {:maxWidth "900px"}} ...]
[3 more…]

If a Hiccup value is intended as HTML, render it through clerk/html. If it is intended as data, use a table, viewer, or summarized map.

Pattern: curate data before showing it

Instead of showing a whole replay result:

@lifecycle-result

show:

(select-keys @lifecycle-result
  [:outcome :events-processed :trace :metrics])

Better:

(lifecycle-summary @lifecycle-result)

Best:

(clerk/html
  (lifecycle-summary-card @lifecycle-result))
Antipattern: exposing raw world state too early

Raw world state is useful for debugging and reproducibility, but it is too dense for first-time readers. Put it in an appendix.

4. Progressive disclosure
Pattern: three-layer disclosure

Use this structure:

Layer 1 — visible narrative
What happened and why it matters.

Layer 2 — visible result
Cards, trace table, invariant summary, evidence table.

Layer 3 — folded/appendix detail
Scenario input, raw replay, full metrics, full artifacts.
Antipattern: all-or-nothing output

The current notebook has the inverse problem: too much code is visible, while key data is folded/truncated. The result is that the reader sees implementation detail but not the insight.

Pattern: use folded code for trust
^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
  (strategy-comparison-panel honest malicious))

This says: “Here is the result; click if you want to inspect how it was produced.”

5. Expansion and truncation
Pattern: intentionally expand small, important data

For short important structures:

^{::clerk/auto-expand-results? true
  ::clerk/budget nil}
important-summary

Clerk has support for opt-in auto-expansion of results via :nextjournal.clerk/auto-expand-results?, and result budgets can be controlled with Clerk settings.

Good candidates:

short event traces
invariant summary
pro-rata allocation rows
artifact index
final balance changes
Antipattern: globally expanding everything

Do not globally expand:

full replay results
full world snapshots
full metrics maps
nested artifact registries
traces with hundreds of events

That turns the notebook into a JSON browser.

Pattern: create “summary + raw” pairs
;; Visible
(clerk/html (metrics-summary-panel metrics))

;; Appendix / folded
^{::clerk/visibility {:code :fold :result :hide}}
metrics
6. Custom viewers
Pattern: create domain viewers

For your framework, the best Clerk upgrade is not more prose; it is better viewers.

Useful viewers:

event-trace-viewer
transition-diff-viewer
metric-card-viewer
invariant-report-viewer
evidence-bundle-viewer
allocation-table-viewer

Clerk is designed around viewers, including built-in data viewers and custom viewer workflows. Clerk examples and documentation discuss built-in viewers for tables/data and using custom/nested viewers for richer representations.

Antipattern: styling every section manually

This becomes hard to maintain:

[:div {:style {:background "#0f172a"
               :padding "14px"
               :borderRadius "8px"
               :border "1px solid #134e4a"}}
 ...]

Repeated everywhere.

Better:

(metric-card {:label "Recovered" :value "500 USDC" :status :good})

or:

(section
  {:title "Pro-rata slash allocation"
   :claim "Liability is allocated by slashable stake."
   :body  (allocation-table prorata-alloc)})
Pattern: separate semantic data from rendering

Good:

(def prorata-summary
  {:obligation 500
   :paid 500
   :unmet 0
   :allocations [...]})

(clerk/html
  (allocation-panel prorata-summary))

Bad:

(def prorata-display
  [:div {:style ...}
   [:table ...]])

The semantic value is reusable in tests, artifacts, and viewers. The display is just one representation.

7. Narrative rhythm
Pattern: claim → evidence → interpretation

Each section should read like:

Claim
The protocol conserves value during happy-path dispute resolution.

Evidence
[Outcome PASS] [3 events] [0 invariant failures]
Trace table

Interpretation
The escrow moves from funded → disputed → finalized without balance violation.
Antipattern: code → output → reader guesses meaning

Avoid:

(defonce lifecycle-display ...)
[:div ...]

The reader should not infer the lesson from the data structure.

Pattern: add “What to notice”

You already have notice-box; make it mandatory per section.

Examples:

What to notice
- The event trace is replayable.
- The resolver action changes both dispute status and balances.
- The invariant check turns the run into auditable evidence.
Antipattern: generic explanations disconnected from outputs

Avoid vague commentary like:

This demonstrates robustness.

Prefer specific commentary:

The recovered amount equals the slash obligation, so there is no unmet liability in this run.
8. Stable demo outputs
Pattern: deterministic presentation mode

For demos, seed or fix randomness:

{:mode :presentation
 :random-seed 42
 :n-trials 500
 :detection-probability 0.25}
Antipattern: stochastic demo with weak visible outcome

In the current notebook, the malicious resolver section says detection is 25%, but the visible result shows Fraud slashed: 0. The detection sweep also appears to show zero slashes across detection probabilities. For a first-time viewer, that looks like either a bug or an unintuitive result unless explained.

Use one of two approaches:

Presentation mode:
Force/seed a visible slash so the mechanism is clear.

Research mode:
Keep stochastic realism, but add confidence intervals and explain variance.
Pattern: flag surprising results
(when (and (pos? detection-prob)
           (zero? fraud-slashed-count))
  (warning-box
    "No fraud was slashed in this sample"
    "This can happen with small trial counts. Increase trials or use presentation mode for a deterministic walkthrough."))
9. Tables and charts
Pattern: tables for exact audit data

Use tables for:

event trace
allocation rows
artifact lists
invariant lists
Pattern: charts for trends

Use charts for:

detection probability sweep
escaped harm
slash rate
strategy payoff curves

Clerk supports data visualization workflows through viewers such as table and Vega-style visualizations in the broader Clerk ecosystem.

Antipattern: using maps for comparative data

Bad:

{:honest-mean 100
 :malice-mean 70
 :slash-rate 0.25
 :escaped-harm 3000}

Better:

Metric	Honest	Malicious	Delta
Avg profit	100	70	-30
Slash rate	0%	25%	+25pp
Escaped harm	0	3,000	+3,000
10. Notebook structure
Pattern: main path + appendix

Recommended structure:

1. At a glance
2. Scenario setup
3. Happy-path lifecycle
4. Malicious resolver attempt
5. Slashing distribution
6. Pro-rata allocation
7. Strategy comparison
8. Detection sweep
9. Invariant evidence
10. Evidence bundle
11. Appendix: raw inputs and full outputs
Antipattern: mixing setup, execution, display, and raw artifacts

The current notebook interleaves helpers, raw maps, display values, and explanation. That makes the page hard to skim.

Pattern: move raw details into appendix

Appendix should contain:

full scenario input
raw replay result
full metrics map
full invariant report
full evidence registry
reproduction commands
11. Naming and reader affordances
Pattern: name cells by reader meaning

Good section labels:

Happy-path lifecycle
Malicious resolver attempt
Who covers the loss?
Pro-rata liability allocation
Invariant evidence
Evidence bundle
Antipattern: implementation labels as reader labels

Avoid visible headings like:

Shared constants
Helpers
defonce lifecycle-display

Those are author concerns, not reader concerns.

Pattern: use stable labels in cards

For metrics, use consistent labels:

Outcome
Events processed
Recovered
Unmet
Invariant failures
Escaped harm

Avoid switching between fraud-slashed-count, fraud detected, slashed, and slash rate without explanation.

12. Reproducibility
Pattern: visible reproduction command

Keep this visible near the end:

bb notebook
open http://localhost:7777/notebooks/not_governance
Pattern: show artifact provenance

For evidence sections, show:

Artifact	Purpose	Reproducible?
Scenario input	Event sequence	yes
Event trace	Step-by-step replay	yes
Metrics	Economic outcomes	yes
Invariant report	Safety checks	yes
Evidence bundle	Audit trail	yes
Antipattern: hiding provenance behind polished visuals

A research-grade notebook can be polished, but it should not feel like a dashboard detached from source artifacts.

13. Error and limitation display
Pattern: make limitations explicit

Use callouts:

Limitation
This demo uses a simplified slashing distribution and does not yet show world-state content hashes for targeted evidence.
Antipattern: silently smoothing over gaps

If a section’s numbers do not demonstrate the intended claim, either fix the run or call it out. Silent mismatch reduces trust.

14. Caching and evaluation
Pattern: cache expensive runs, show summaries

For expensive simulations:

^{::clerk/visibility {:code :hide :result :hide}}
(defonce detection-sweep
  (delay (run-detection-sweep params)))

Then show:

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
  (detection-sweep-panel @detection-sweep))
Antipattern: recomputing expensive or stochastic cells unpredictably

For demos, unstable outputs are bad. For research, unstable outputs need metadata:

seed
trial count
parameter set
commit SHA
scenario ID
15. Specific pattern set for your quickstart
Granularity rule

Use one top-level visible form per section:

(clerk/html
  (quickstart-section
    {:title "Pro-rata slash allocation"
     :claim "Slash obligations are allocated by slashable stake."
     :result (allocation-summary prorata-alloc)
     :notice ["500 obligation = 500 paid + 0 unmet"
              "Each resolver pays according to stake share."]}))
Hide these
^{::clerk/visibility {:code :hide :result :hide}}
(defn badge ...)

^{::clerk/visibility {:code :hide :result :hide}}
(def const ...)

^{::clerk/visibility {:code :hide :result :hide}}
(defonce malice-batch ...)
Fold these
^{::clerk/visibility {:code :fold :result :show}}
(clerk/html (scenario-card lifecycle-scenario))

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html (lifecycle-panel @lifecycle-result))
Appendix these
^{::clerk/visibility {:code :fold :result :show}
  ::clerk/auto-expand-results? true}
(select-keys @lifecycle-result [:trace :metrics])
Summary table
Area	Pattern	Antipattern
Granularity	One visible idea per top-level form	Helper functions as visible cells
Visibility	Code folded, results shown	All code shown by default
Setup	Hidden setup cells	Constants/helpers in reader path
Results	Curated cards/tables	Raw maps, Hiccup, Delay objects
Disclosure	Summary first, raw appendix later	Raw data before interpretation
Expansion	Expand small important results	Global expansion or global truncation
Viewers	Domain-specific viewers	Inline styling everywhere
Narrative	Claim → evidence → interpretation	Code → output → reader guesses
Randomness	Seeded presentation mode	Stochastic demo with weak output
Evidence	Provenance visible	Polished visuals with hidden source
