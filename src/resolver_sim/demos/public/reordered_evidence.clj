(ns resolver-sim.demos.public.reordered-evidence
  "Public-demo projection for the Reordered Evidence demonstration
   (public-demo.v1).

   PRF owns the result; the website owns the explanation. This namespace
   projects the executable reorder-chain demo model
   (`resolver-sim.demos.reorder-chain.demo/run`, which runs the real chain
   verifier `resolver-sim.evidence.chain`) into a presentation-safe artifact.

   The frontend must NOT independently decide that reordering breaks the
   chain; it renders the projected verdict.

   Same integrity rules as the other public projections:
     - presentation-safe facts copied verbatim from the executable model;
     - fail closed on missing required evidence;
     - deterministic; never strengthens the evidence."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [resolver-sim.demos.reorder-chain.demo :as demo]
            [resolver-sim.demos.reorder-chain.scenario :as scenario]
            [resolver-sim.demos.public.validate :as validate]))

(def schema-id "public-demo.v1")

(def demo-id "reordered-evidence")

(def demo-version 1)

(defn- require-field!
  "Fail closed if the demo model is missing a required presentation field."
  [m path]
  (let [v (get-in m path)]
    (when (or (nil? v) (and (string? v) (empty? v)))
      (throw (ex-info (str "public-demo.v1 projection missing required field: "
                           (str/join "." (map name path)))
                      {:demo/id demo-id :field path})))
    v))

(defn- sorted-maps
  "Recursively convert maps to sorted maps so JSON serialisation is
   deterministic regardless of map construction order."
  [x]
  (walk/postwalk (fn [n] (if (map? n) (into (sorted-map) n) n)) x))

(defn- require-boolean!
  "Require a source boolean rather than coercing a missing value to false."
  [m path]
  (let [v (require-field! m path)]
    (when-not (boolean? v)
      (throw (ex-info "public-demo.v1 projection requires a boolean field"
                      {:demo/id demo-id :field path :value v})))
    v))

(defn- project*
  "Project the executable reorder-chain demo model into the public-demo.v1 map.
   Protocol facts (admitted?, failed checks, committed hash, evidence lines)
   are copied verbatim; nothing is recomputed."
  []
  (let [m (demo/run)
        baseline (require-field! m [:demo/baseline])
        action (require-field! m [:demo/action])
        outcome (require-field! m [:demo/outcome])
        evidence (require-field! m [:demo/evidence])
        checks (:after/checks evidence)
        lines (require-field! evidence [:lines])
        committed-hash (require-field! evidence [:committed-hash])]
    (sorted-maps
     {"schema" schema-id
      "demo" {"id" demo-id
              "version" demo-version
              "question" (require-field! m [:demo/question])}
      "scenario"
      {"records" {"order" (:label baseline)
                  "items" (mapv name scenario/baseline-order)}}
      "baseline"
      {"label" (:label baseline)
       "value" (:value baseline)
       "admitted" (require-boolean! baseline [:admitted?])}
      "change"
      {"label" (:label action)
       "from" (:from action)
       "to" (:to action)
       "detail" (:detail action)}
      "outcome"
      {"admitted" (require-boolean! outcome [:admitted?])
       "failed-checks" (mapv #(if (keyword? %) (name %) (name (:reason %)))
                             (or (:failed-checks outcome) []))}
      "why" (require-field! m [:demo/explanation])
      "evidence"
      {"committed-hash" committed-hash
       "input-root" "demo"
       "lines" (mapv (fn [[label value]] [label (str value)]) lines)
       "checks" (mapv (fn [c]
                        (cond-> {"id" (name (:check/id c))
                                 "status" (name (:status c))}
                          (some? (:detail c))
                          (assoc "detail" (:detail c))))
                      checks)}
      "commitments"
      {"baseline" (name (get-in m [:demo/expect :baseline]))
       "after-change" (name (get-in m [:demo/expect :after-action]))}
      "source"
      {"notebook" "not_admitted"
       "demo-notebook" "demo_reorder_chain"
       "cli" "bb demo:reorder-chain"
       "scenario-ns" "resolver-sim.demos.reorder-chain.scenario"
       "projection-ns" "resolver-sim.demos.public.reordered-evidence"
       "schema" schema-id
       "result-root" committed-hash
       "input-root" "demo"}})))

(defn project
  "Project and validate the executable reorder-chain demo model. Returns the
   validated public-demo.v1 map (throws fail-closed on inconsistency)."
  []
  (validate/validate-artifact! (project*)))

(defn json-str
  "Deterministic JSON serialisation of the projected artifact."
  []
  (str (json/write-str (project)) "\n"))
