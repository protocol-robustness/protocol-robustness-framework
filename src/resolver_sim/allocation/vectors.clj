(ns resolver-sim.allocation.vectors
  "Conformance vector generation and serialization for the allocation kernel.

   Vectors bind a canonical JSON input document to the expected public-value
   projection produced by the PRF reference kernel. The independent Rust kernel
   must reproduce every expected public value byte-for-byte, and must reject
   every failing vector with the expected classification.

   Wire rules (JSON is transport only; canonical hashing uses the PRF binary
   ABI):
     - arbitrary-size integers are decimal strings;
     - hashes are lowercase 0x-prefixed 32-byte hexadecimal strings;
     - authoritative randomness is lowercase 0x-prefixed exactly-32-byte hex;
     - ratios contain decimal-string numerator and denominator;
     - ordered collections are JSON arrays;
     - JSON object property ordering has no semantic significance;
     - vector files contain vector_version, vector_id, description, input,
       and expected."
  (:require [clojure.data.json :as json]
            [resolver-sim.allocation.context :as context]
            [resolver-sim.allocation.kernel :as kernel]))

(def vector-version "allocation-kernel-vector.v1")

;; ──────────────────────────────────────────────────────────────────────────────
;; JSON projection (transport layer)
;; ──────────────────────────────────────────────────────────────────────────────

(defn keyword-string
  "Portable string representation of a keyword."
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(declare project-json)

(defn- project-json-map [m]
  (into {} (map (fn [[k v]]
                  [(if (keyword? k) (keyword-string k) (str k))
                   (project-json v)]))
        m))

(def ^:private bare-hex-32 #"^[0-9a-f]{64}$")

(defn- normalize-hash
  "Normalize a 32-byte hex string to lowercase 0x-prefixed form. The wire
   format requires hashes to be lowercase 0x-prefixed 32-byte hex."
  [s]
  (cond
    (and (string? s) (re-matches #"^0x[0-9a-f]{64}$" s)) s
    (and (string? s) (re-matches bare-hex-32 s)) (str "0x" s)
    :else s))

(defn project-json
  "Project a canonical value tree into JSON-safe data: keywords become strings,
   integers and bigints become decimal strings, and bare 32-byte hex hashes are
   normalized to lowercase 0x-prefixed form."
  [v]
  (cond
    (keyword? v) (keyword-string v)
    (integer? v) (str v)
    (instance? clojure.lang.BigInt v) (str v)
    (instance? java.math.BigInteger v) (str v)
    (string? v) (normalize-hash v)
    (map? v) (project-json-map v)
    (sequential? v) (mapv project-json v)
    (coll? v) (mapv project-json v)
    :else v))

(defn write-json
  "Serialize a value tree to JSON with deterministic key ordering."
  [v]
  (json/write-str (project-json v)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Public-value projection
;; ──────────────────────────────────────────────────────────────────────────────

(def public-value-keys
  "The complete declared public-value projection that PRF and Rust must match.
   Any missing or extra field is a conformance failure."
  [:result/status
   :allocation-context-hash
   :claimant-set-root
   :outcome-set-root
   :proposed-rates-root
   :rate-derived-summary-hash
   :assertions
   :selection-receipt
   :selected-outcome-id
   :selected-outcome-index
   :selected-outcome-hash
   :result-root
   :total-allocated
   :residual-capacity
   :round-lifecycle
   :certificate-assertions-digest
   :allocation-kernel-version
   :selection-algorithm])

(def rejection-keys
  "Rejection fields included in the projection when present."
  [:rejection/classification])

(defn public-value-projection
  "Extract the declared public-value projection from a kernel result. Includes
   the rejection classification when present; diagnostics such as the reason
   text are excluded."
  [result]
  (merge (select-keys result public-value-keys)
         (when-let [classification (:rejection/classification result)]
           {:rejection/classification classification})))

;; ──────────────────────────────────────────────────────────────────────────────
;; Base fixture
;; ──────────────────────────────────────────────────────────────────────────────

(defn base-input
  "The fixed a-vs-b-plus-c scenario as a transport JSON input document."
  []
  {"allocation-id" "a-vs-b-plus-c"
   "kernel-version" context/kernel-version
   "selection-algorithm" "domain-hash-rejection-v1"
   "policy" {"policy-id" "policy-a-vs-b-plus-c"
             "policy-hash" (str "0x" (apply str (repeat 32 "ab")))
             "forbid-duplicate-owners" false}
   "claimants"
   [{"claim-id" "A" "economic-owner-id" "owner-A" "amount" "50" "weight" "50"}
    {"claim-id" "B" "economic-owner-id" "owner-B" "amount" "30" "weight" "30"}
    {"claim-id" "C" "economic-owner-id" "owner-C" "amount" "20" "weight" "20"}]
   "outcomes"
   [{"outcome-id" "O1"
     "allocations" [{"claim-id" "A" "allocated" "50"}
                    {"claim-id" "B" "allocated" "0"}
                    {"claim-id" "C" "allocated" "0"}]}
    {"outcome-id" "O2"
     "allocations" [{"claim-id" "A" "allocated" "0"}
                    {"claim-id" "B" "allocated" "30"}
                    {"claim-id" "C" "allocated" "20"}]}]
   "proposed-rates"
   [{"outcome-id" "O1" "numerator" "1" "denominator" "2"}
    {"outcome-id" "O2" "numerator" "1" "denominator" "2"}]
   "capacity" "50"
   "total-eligible-weight" "100"
   "exact-pro-rata-denominator" "100"
   "authoritative-randomness" "0x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"})

(defn base-committed
  "Committed-roots block derived from the happy-path kernel run."
  [input]
  (let [result (kernel/run-kernel input)]
    {"claimant-set-root" (normalize-hash (:claimant-set-root result))
     "outcome-set-root" (normalize-hash (:outcome-set-root result))
     "proposed-rates-root" (normalize-hash (:proposed-rates-root result))
     "result-root" (normalize-hash (:result-root result))
     "selected-outcome-id" (:selected-outcome-id result)
     "selected-outcome-index" (str (:selected-outcome-index result))}))

;; ──────────────────────────────────────────────────────────────────────────────
;; Vector construction
;; ──────────────────────────────────────────────────────────────────────────────

(defn build-vector
  "Construct a conformance vector from a vector id, description, and input
   document. The expected projection is produced by running the PRF kernel."
  [vector-id description input]
  (let [result (kernel/run-kernel input)]
    {:vector_version vector-version
     :vector_id vector-id
     :description description
     :input input
     :expected (public-value-projection result)}))

(defn all-vectors
  "Generate the full conformance vector suite."
  []
  (let [happy (base-input)
        committed (base-committed happy)
        happy-with-committed (assoc happy "committed" committed)

        permute-claimants
        (fn [coll order] (mapv (fn [id] (first (filter #(= id (get % "claim-id")) coll))) order))

        claimant-perm
        (assoc happy "claimants"
               (permute-claimants (get happy "claimants") ["C" "A" "B"]))

        outcome-perm
        (assoc happy "outcomes" [(get-in happy ["outcomes" 1]) (get-in happy ["outcomes" 0])])

        malformed-rate-total
        (assoc happy "proposed-rates"
               [{"outcome-id" "O1" "numerator" "1" "denominator" "3"}
                {"outcome-id" "O2" "numerator" "1" "denominator" "3"}])

        non-reduced
        (assoc happy "proposed-rates"
               [{"outcome-id" "O1" "numerator" "2" "denominator" "4"}
                {"outcome-id" "O2" "numerator" "1" "denominator" "2"}])

        partial-allocation
        (assoc happy "outcomes"
               [{"outcome-id" "O1"
                 "allocations" [{"claim-id" "A" "allocated" "25"}
                                {"claim-id" "B" "allocated" "0"}
                                {"claim-id" "C" "allocated" "0"}]}
                (get-in happy ["outcomes" 1])])

        over-capacity
        (assoc happy "outcomes"
               [{"outcome-id" "O1"
                 "allocations" [{"claim-id" "A" "allocated" "50"}
                                {"claim-id" "B" "allocated" "30"}
                                {"claim-id" "C" "allocated" "0"}]}
                (get-in happy ["outcomes" 1])])

        under-capacity
        (assoc happy "outcomes"
               [{"outcome-id" "O1"
                 "allocations" [{"claim-id" "A" "allocated" "0"}
                                {"claim-id" "B" "allocated" "0"}
                                {"claim-id" "C" "allocated" "0"}]}
                (get-in happy ["outcomes" 1])])

        ineligible
        (assoc happy "outcomes"
               [{"outcome-id" "O1"
                 "allocations" [{"claim-id" "A" "allocated" "50"}
                                {"claim-id" "B" "allocated" "0"}
                                {"claim-id" "C" "allocated" "0"}
                                {"claim-id" "D" "allocated" "0"}]}
                (get-in happy ["outcomes" 1])])

        duplicate-claim
        (assoc happy "outcomes"
               [{"outcome-id" "O1"
                 "allocations" [{"claim-id" "A" "allocated" "50"}
                                {"claim-id" "A" "allocated" "0"}
                                {"claim-id" "B" "allocated" "0"}
                                {"claim-id" "C" "allocated" "0"}]}
                (get-in happy ["outcomes" 1])])

        proportionality-failure
        (assoc happy "proposed-rates"
               [{"outcome-id" "O1" "numerator" "1" "denominator" "4"}
                {"outcome-id" "O2" "numerator" "3" "denominator" "4"}])

        changed-randomness
        (assoc happy-with-committed
               "authoritative-randomness"
               "0x0000000000000000000000000000000000000000000000000000000000000001")

        forged-root
        (assoc happy "committed"
               (assoc committed
                      "claimant-set-root"
                      (str "0x" (apply str (repeat 32 "00")))))

        empty-outcomes (assoc happy "outcomes" [])

        lifecycle-variants
        (fn [token]
          (assoc happy-with-committed "round-state" token))]
    [(build-vector "a-vs-b-plus-c-happy-path"
                   "Happy path: A versus B plus C, all 14 assertions pass."
                   happy-with-committed)

     (build-vector "a-vs-b-plus-c-claimant-order-permutation"
                   "Same scenario with claimants supplied in non-canonical order."
                   claimant-perm)

     (build-vector "a-vs-b-plus-c-outcome-order-permutation"
                   "Same scenario with outcomes supplied in non-canonical order."
                   outcome-perm)

     (build-vector "a-vs-b-plus-c-malformed-rate-total"
                   "Proposed rates do not sum to one."
                   malformed-rate-total)

     (build-vector "a-vs-b-plus-c-non-reduced-ratio"
                   "A proposed rate is not a reduced exact ratio."
                   non-reduced)

     (build-vector "a-vs-b-plus-c-partial-claimant-allocation"
                   "A claimant receives a partial (non-all-or-nothing) amount."
                   partial-allocation)

     (build-vector "a-vs-b-plus-c-over-capacity-outcome"
                   "An outcome allocates more than capacity."
                   over-capacity)

     (build-vector "a-vs-b-plus-c-under-capacity-outcome"
                   "An outcome allocates less than capacity."
                   under-capacity)

     (build-vector "a-vs-b-plus-c-ineligible-claimant"
                   "An outcome references a claim not in the claimant set."
                   ineligible)

     (build-vector "a-vs-b-plus-c-duplicate-claim-in-outcome"
                   "An outcome contains the same claim twice."
                   duplicate-claim)

     (build-vector "a-vs-b-plus-c-proportionality-failure"
                   "Rates sum to one but break exact pro-rata proportionality."
                   proportionality-failure)

     (build-vector "a-vs-b-plus-c-changed-authoritative-randomness"
                   "Different committed randomness changes the selection."
                   changed-randomness)

     (build-vector "a-vs-b-plus-c-forged-expected-root"
                   "A committed claimant-set-root does not recompute."
                   forged-root)

     (build-vector "a-vs-b-plus-c-empty-outcome-set"
                   "The outcome set is empty."
                   empty-outcomes)

     (build-vector "a-vs-b-plus-c-lifecycle-pre-cutpoint-open"
                   "Round at allocation-committed: still inside the cancellable window."
                   (lifecycle-variants "allocation-committed"))

     (build-vector "a-vs-b-plus-c-lifecycle-cutpoint-randomness-requested"
                   "Round at randomness-requested: the authoritative randomness cutpoint, first closed state."
                   (lifecycle-variants "randomness-requested"))

     (build-vector "a-vs-b-plus-c-lifecycle-post-cutpoint-fulfilled"
                   "Round at randomness-fulfilled: closed, cancellation blocked."
                   (lifecycle-variants "randomness-fulfilled"))

     (build-vector "a-vs-b-plus-c-lifecycle-post-cutpoint-proposed"
                   "Round at result-proposed: closed, cancellation blocked."
                   (lifecycle-variants "result-proposed"))

     (build-vector "a-vs-b-plus-c-lifecycle-post-cutpoint-accepted"
                   "Round at result-accepted: closed, cancellation blocked."
                   (lifecycle-variants "result-accepted"))

     (build-vector "a-vs-b-plus-c-lifecycle-post-cutpoint-consumption"
                   "Round at claim-consumption-started: closed, cancellation blocked."
                   (lifecycle-variants "claim-consumption-started"))

     (build-vector "a-vs-b-plus-c-lifecycle-unknown-token"
                   "Unknown round-state token: fail closed to invalid, unknown target state."
                   (lifecycle-variants "no-such-round-state"))

     (build-vector "a-vs-b-plus-c-lifecycle-missing-token"
                   "Explicit null round-state: fail closed to invalid, missing target state."
                   (assoc happy-with-committed "round-state" nil))

     (build-vector "a-vs-b-plus-c-lifecycle-malformed-token"
                   "Non-string round-state: fail closed to invalid, malformed round state."
                   (assoc happy-with-committed "round-state" {"not" "a-token"}))]))
