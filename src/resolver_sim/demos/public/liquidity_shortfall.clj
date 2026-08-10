(ns resolver-sim.demos.public.liquidity-shortfall
  "Public-demo projection for the Liquidity Shortfall demonstration
   (public-demo.v1).

   PRF owns the result; the website owns the explanation. This namespace is the
   only boundary between the two for the allocation story. It takes the
   executable demo model produced by `resolver-sim.demos.liquidity-shortfall.
   demo/run` (which runs the real pro-rata allocation engine) and projects a
   presentation-safe artifact the frontend may render verbatim.

   The frontend must NOT independently recompute the allocation (it must not
   decide that 70 against 100 yields 35/21/14). It renders the projected result.

   The same integrity rules as the blocked-decision projection apply:
     - only presentation-safe facts, copied from the executable model;
     - fail closed on missing required evidence;
     - deterministic JSON; never strengthens the evidence."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [resolver-sim.demos.liquidity-shortfall.demo :as demo]
            [resolver-sim.demos.public.validate :as validate]))

(def schema-id "public-demo.v1")

(def demo-id "liquidity-shortfall")

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

(defn- project*
  "Project the executable liquidity-shortfall demo model into the
   public-demo.v1 map. Every allocation fact is copied from the executable
   result; nothing is recomputed here."
  []
  (let [m (demo/run)
        pool (require-field! m [:demo/pool])
        requests (require-field! m [:demo/requests])
        allocation (require-field! m [:demo/allocation])
        conservation (require-field! m [:demo/conservation])
        evidence (require-field! m [:demo/evidence])
        lines (require-field! evidence [:lines])
        committed-hash (require-field! evidence [:committed-hash])]
    (sorted-maps
     {"schema" schema-id
      "demo" {"id" demo-id
              "version" demo-version
              "question" (require-field! m [:demo/question])}
      "scenario"
      {"pool" {"available" (long (:available pool))
               "unit" (:unit pool)
               "requested" (long (:requested pool))}
       "requests" (mapv (fn [r]
                          {"id" (name (:request/id r))
                           "requested" (long (:requested r))
                           "allocated" (long (:allocated r))
                           "shortfall" (long (:shortfall r))})
                        requests)}
      "allocation"
      {"total-allocated" (long (:total-allocated allocation))
       "unallocated-residual" (long (:unallocated-residual allocation))}
      "conservation"
      {"requested" (long (:requested conservation))
       "allocated" (long (:allocated conservation))
       "shortfall" (long (:shortfall conservation))
       "holds" (boolean (:holds? conservation))}
      "why" (require-field! m [:demo/explanation])
      "evidence"
      {"committed-hash" committed-hash
       "request-hash" (require-field! evidence [:request/hash])
       "lines" (mapv (fn [[label value]] [label (str value)]) lines)
       "checks" (mapv (fn [c]
                        {"id" (name (:check/id c))
                         "status" (name (:status c))
                         "detail" (:detail c)})
                      (:after/checks evidence))}
      "commitments"
      {"pool-fully-allocated" (boolean
                               (get-in m [:demo/expect :pool-fully-allocated?]))}
      "source"
      {"notebook" "pro_rata_allocation_result"
       "demo-notebook" "evaluate_pro_rata_allocation"
       "cli" "bb demo:liquidity-shortfall"
       "scenario-ns" "resolver-sim.demos.liquidity-shortfall.scenario"
       "projection-ns" "resolver-sim.demos.public.liquidity-shortfall"
       "schema" schema-id
       "result-root" committed-hash
       "input-root" (require-field! evidence [:request/hash])}})))

(defn project
  "Project and validate the executable liquidity-shortfall demo model.
   Returns the validated public-demo.v1 map (throws fail-closed on
   inconsistency, including cross-field conservation arithmetic)."
  []
  (validate/validate-artifact! (project*)))

(defn json-str
  "Deterministic JSON serialisation of the projected artifact."
  []
  (str (json/write-str (project)) "\n"))
