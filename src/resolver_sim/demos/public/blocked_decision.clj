(ns resolver-sim.demos.public.blocked-decision
  "Public-demo projection for the Blocked Decision demonstration (public-demo.v1).

   PRF owns the result; the website owns the explanation. This namespace is the
   only boundary between the two. It takes the executable demo model produced by
   the real not-admitted demo (`resolver-sim.demos.not-admitted.demo/run`) and
   projects a presentation-safe, versioned artifact that the frontend may render
   verbatim.

   The frontend must NOT independently reproduce the admission logic (e.g. it
   must not decide that editing a recorded amount causes the same check to
   fail). It renders the projected result. This projection therefore:

     - exposes only presentation-safe facts needed by the demo page;
     - fails closed when a required field is absent (never invents a
       successful-looking presentation);
     - is deterministic (sorted map keys, stable JSON serialisation);
     - never strengthens the evidence it carries.

   The public narrative may simplify evidence; it must never strengthen it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [resolver-sim.demos.not-admitted.demo :as demo]
            [resolver-sim.demos.public.validate :as validate]))

(def schema-id "public-demo.v1")

(def demo-id "blocked-decision")

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
  "Project the executable not-admitted demo model into the public-demo.v1 map.

   Every protocol fact (admitted?, failed checks, committed hash, evidence
   lines) is copied from the executable model; nothing is recomputed here."
  []
  (let [m (demo/run)
        baseline (require-field! m [:demo/baseline])
        action (require-field! m [:demo/action])
        outcome (require-field! m [:demo/outcome])
        evidence (require-field! m [:demo/evidence])
        checks (:after/checks evidence)
        lines (require-field! evidence [:lines])
        committed-hash (require-field! evidence [:committed-hash])
        ledger-root (second (first lines))]
    (sorted-maps
     {"schema" schema-id
      "demo" {"id" demo-id
              "version" demo-version
              "question" (require-field! m [:demo/question])}
      "scenario"
      {"escrow" {"held" (:value baseline)
                 "unit" (:unit baseline)
                 "label" (:label baseline)}}
      "baseline"
      {"label" (:label baseline)
       "value" (:value baseline)
       "unit" (:unit baseline)
       "admitted" (require-boolean! baseline [:admitted?])}
      "change"
      {"label" (:label action)
       "from" (:from action)
       "to" (:to action)
       "unit" (:unit action)
       "detail" (:detail action)}
      "outcome"
      {"admitted" (require-boolean! outcome [:admitted?])
       "failed-checks" (mapv name (or (:failed-checks outcome) []))}
      "why" (require-field! m [:demo/explanation])
      "evidence"
      {"committed-hash" committed-hash
       "input-root" (str ledger-root)
       "lines" (mapv (fn [[label value]] [label (str value)]) lines)
       "checks" (mapv (fn [c]
                        (cond-> {"id" (name (:check/id c))
                                 "status" (name (:status c))}
                          (some? (:details c))
                          (assoc "details" (:details c))))
                      checks)}
      "commitments"
      {"baseline" (name (get-in m [:demo/expect :baseline]))
       "after-change" (name (get-in m [:demo/expect :after-action]))}
      "source"
      {"notebook" "not_admitted"
       "demo-notebook" "demo_not_admitted"
       "cli" "bb demo:not-admitted"
       "scenario-ns" "resolver-sim.demos.not-admitted.scenario"
       "projection-ns" "resolver-sim.demos.public.blocked-decision"
       "schema" schema-id
       "result-root" committed-hash
       "input-root" (str ledger-root)}})))

(defn project
  "Project and validate the executable not-admitted demo model. Returns the
   validated public-demo.v1 map (throws fail-closed on inconsistency)."
  []
  (validate/validate-artifact! (project*)))

(defn json-str
  "Deterministic JSON serialisation of the projected artifact."
  []
  (str (json/write-str (project)) "\n"))
