(ns resolver-sim.demos.public.validate
  "Fail-closed validation for public-demo.v1 artifacts.

   Two properties are locked here, mechanically:

   1. Single-source provenance — every fact on a page must belong to ONE
      executable result. Cross-field consistency is asserted: for a liquidity
      artifact, the per-request rows, totals, and conservation must agree, so a
      future projection bug that splices rows from run A into run B's
      conservation breaks the arithmetic and is rejected. For narrative
      artifacts, the verdict and the evidence check statuses must agree, so a
      failed result can never present a verified treatment.

   2. Presentation conservatism — status labels and quantities are only ever
      derived from the projected evidence; this validator rejects any artifact
      that would let a page render stronger than its evidence.

   The projection namespaces call `validate-artifact!` before returning, so a
   malformed or internally-inconsistent artifact fails generation. The same
   rules are mirrored in the TypeScript build-time validator and the CI
    verify-demos script."
  (:require [clojure.string :as str]))

(def schema-id "public-demo.v1")

(defn- fail! [demo-id field detail]
  (throw (ex-info (str "public-demo.v1 validation failed for " demo-id
                       " at " field ": " detail)
                  {:demo/id demo-id :field field :detail detail})))

(defn- num? [x]
  (and (number? x) (not (Double/isNaN (double x)))))

(defn- as-num [v field]
  (when-not (num? v)
    (fail! :unknown field (str "expected a number, got " (pr-str v))))
  (long v))

(defn- provenance-consistent?
  "The artifact's committed result root must equal the evidence committed hash,
   and the bound input root must equal the evidence input identity: the page's
   facts are bound to the result they came from and the input it ran on."
  [{:strs [source evidence]}]
  (let [result-root (get-in source ["result-root"])
        committed (get-in evidence ["committed-hash"])
        input-root (get-in source ["input-root"])
        input-identity (or (get evidence "request-hash")
                           (get evidence "input-root"))]
    (and (string? result-root) (= result-root committed)
         (string? input-root) (= input-root input-identity))))

(defn- narrative-consistent?
  "For narrative demos, the verdict must agree with the evidence check statuses:
   a not-admitted outcome implies a failing check exists, and the failed-check
   ids must match exactly the failing evidence checks. A page can never render
   VERIFIED from an artifact whose evidence says FAIL."
  [artifact]
  (let [outcome (get artifact "outcome")
        evidence (get artifact "evidence")
        admitted (get outcome "admitted")
        failed-checks (vec (sort (get outcome "failed-checks")))
        failing-evidence (vec (sort (map #(get % "id")
                                         (filter #(= "fail" (get % "status"))
                                                 (get evidence "checks" [])))))]
    (cond
      (not (boolean? admitted))
      (fail! :narrative "outcome.admitted" (str "expected boolean, got " (pr-str admitted)))

      admitted
      (when (seq failing-evidence)
        (fail! :narrative "outcome.admitted"
               (str "admitted=true but evidence has failing checks: "
                    (str/join ", " failing-evidence))))

      (not admitted)
      (when (empty? failing-evidence)
        (fail! :narrative "outcome.admitted"
               "admitted=false but no evidence check failed"))

      :else nil)
    (when-not (= failed-checks failing-evidence)
      (fail! :narrative "outcome.failed-checks"
             (str "failed-checks " (pr-str failed-checks)
                  " != failing evidence " (pr-str failing-evidence))))
    true))

(defn- liquidity-consistent?
  "For the allocation demo, rows, totals, and conservation must be mutually
   consistent. Sums are recomputed from the projected rows (pure arithmetic, no
   protocol recomputation), so spliced or stale facts are rejected."
  [artifact]
  (let [scenario (get artifact "scenario")
        pool (get scenario "pool")
        requests (get scenario "requests")
        allocation (get artifact "allocation")
        conservation (get artifact "conservation")
        sum-requested (reduce + 0 (map #(as-num (get % "requested") "scenario.requests[].requested") requests))
        sum-allocated (reduce + 0 (map #(as-num (get % "allocated") "scenario.requests[].allocated") requests))
        sum-shortfall (reduce + 0 (map #(as-num (get % "shortfall") "scenario.requests[].shortfall") requests))]
    (when-not (= sum-requested (as-num (get pool "requested") "scenario.pool.requested"))
      (fail! :liquidity-shortfall "conservation"
             (str "sum(requested)=" sum-requested " != pool.requested="
                  (get pool "requested"))))
    (when-not (= sum-allocated (as-num (get allocation "total-allocated") "allocation.total-allocated"))
      (fail! :liquidity-shortfall "conservation"
             (str "sum(allocated)=" sum-allocated " != allocation.total-allocated="
                  (get allocation "total-allocated"))))
    (when-not (= sum-shortfall (as-num (get conservation "shortfall") "conservation.shortfall"))
      (fail! :liquidity-shortfall "conservation"
             (str "sum(shortfall)=" sum-shortfall " != conservation.shortfall="
                  (get conservation "shortfall"))))
    (when-not (= (as-num (get conservation "requested") "conservation.requested")
                 (+ (as-num (get conservation "allocated") "conservation.allocated")
                    (as-num (get conservation "shortfall") "conservation.shortfall")))
      (fail! :liquidity-shortfall "conservation"
             "requested != allocated + shortfall"))
    (when-not (true? (get conservation "holds"))
      (fail! :liquidity-shortfall "conservation.holds" "expected true"))
    true))

(defn- artifact-kind [artifact]
  (get-in artifact ["demo" "id"]))

(defn validate-artifact!
  "Validate a projected public-demo.v1 map. Throws (fail closed) on any
   inconsistency. Returns the artifact when valid."
  [artifact]
  (when-not (map? artifact)
    (fail! :unknown "artifact" (str "expected a map, got " (pr-str artifact))))
  (when-not (= schema-id (get artifact "schema"))
    (fail! :unknown "schema" (str "expected " schema-id ", got " (pr-str (get artifact "schema")))))
  (when-not (provenance-consistent? artifact)
    (fail! (artifact-kind artifact) "source.result-root"
           "must equal evidence.committed-hash"))
  (case (artifact-kind artifact)
    "blocked-decision"    (narrative-consistent? artifact)
    "reordered-evidence"  (narrative-consistent? artifact)
    "liquidity-shortfall" (liquidity-consistent? artifact)
    (fail! (artifact-kind artifact) "demo.id" "unknown demo kind"))
  artifact)
