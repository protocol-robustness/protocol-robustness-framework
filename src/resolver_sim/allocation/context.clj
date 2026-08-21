(ns resolver-sim.allocation.context
  "Allocation context construction and validation.

   Builds and validates the canonical `allocation-context.v1` artifact that
   commits every identity-defining input of an IEE allocation round: schema,
   kernel version, selection algorithm, policy, claimant records, economic
   owners, amounts, weights, capacity, total eligible weight, exact pro-rata
   denominator, canonical claimant and outcome ordering, and authoritative
   randomness.

   The context is the fixed, committed reference that the PRF reference kernel
   and the independent Rust kernel both consume. All ordering is canonical
   (ascending canonical binary encoding of :claim/id and :outcome/id); no map
   iteration, JSON object ordering, or insertion order is ever relied upon.

   Validation failures raise ex-info carrying a stable :rejection/classification
   so callers can produce structured rejections rather than message-text
   dependence."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "allocation-context.v1")
(def ^:const artifact-kind :allocation-context)
(def ^:const kernel-version "allocation-kernel.v1")
(def ^:const selection-algorithm :domain-hash-rejection-v1)
(def ^:const selection-algorithm-str "domain-hash-rejection-v1")
(def ^:const result-status-allocated :allocated)
(def ^:const result-status-not-allocated :not-allocated)

(def ^:private hex-pattern #"^[0-9a-f]{64}$")

(defn rejection!
  "Raise a structured rejection carrying a stable classification keyword and a
   human-readable reason. Classification is part of the compatibility contract."
  [classification reason & [data]]
  (throw (ex-info (str "Allocation rejection: " (name classification))
                  (merge {:rejection/classification classification
                          :rejection/reason reason}
                         (or data {})))))

(defn bytes->hex
  "Lowercase 0x-prefixed hex encoding of a byte array (for JSON transport)."
  [ba]
  (str "0x" (apply str (map #(format "%02x" (bit-and (int %) 0xFF)) ba))))

(defn hex->bytes
  "Decode a lowercase 0x-prefixed 64-char hex string into a 32-byte array."
  [s]
  (when-not (and (string? s) (str/starts-with? s "0x"))
    (rejection! :malformed-randomness
                (str "Randomness must be 0x-prefixed hex, got: " (pr-str s))))
  (let [hex (subs s 2)]
    (when-not (re-matches hex-pattern hex)
      (rejection! :malformed-randomness
                  (str "Randomness must be exactly 32 bytes (64 lowercase hex chars): " s)))
    (byte-array (map #(Integer/parseInt % 16)
                     (map #(apply str %) (partition 2 hex))))))

(defn bytes->byte-ints
  "Represent 32 raw bytes as a vector of 32 integers (0..255) for canonical
   hashing, which does not support raw byte arrays."
  [ba]
  (mapv #(bit-and (int %) 0xFF) ba))

(defn bigint-string?
  "True when the value is an integer (optionally signed) expressed as a decimal
   string."
  [s]
  (and (string? s)
       (re-matches #"-?[0-9]+" s)))

(defn parse-decimal
  "Parse an integer from a decimal string or integer value into a bigint."
  [field value]
  (cond
    (string? value)
    (if (bigint-string? value)
      (bigint value)
      (rejection! :malformed-integer
                  (str field " must be a decimal string, got: " (pr-str value))))

    (integer? value)
    (bigint value)

    :else
    (rejection! :malformed-integer
                (str field " must be an integer, got: " (pr-str value)))))

(defn parse-non-negative
  [field value]
  (let [n (parse-decimal field value)]
    (when (neg? n)
      (rejection! :negative-amount
                  (str field " must be non-negative, got: " n)))
    n))
(defn canonical-key-bytes
  "Canonical binary encoding used for ordering. Returns a byte array."
  [value]
  (hc/canonical-bytes value))

(defn sort-by-canonical
  "Sort a collection by the canonical binary encoding of a key function.
   Deterministic across languages and runtimes."
  [key-fn coll]
  (sort-by (fn [x] (bytes->hex (canonical-key-bytes (key-fn x)))) coll))

;; ──────────────────────────────────────────────────────────────────────────────
;; Claimants
;; ──────────────────────────────────────────────────────────────────────────────

(defn parse-claimant
  "Parse and validate one claimant record into canonical claimant shape.
   `policy` may forbid duplicate economic owners."
  [raw]
  (let [claim-id (get raw "claim-id")
        owner-id (get raw "economic-owner-id")
        amount   (parse-non-negative "amount" (get raw "amount"))
        weight   (parse-non-negative "weight" (get raw "weight"))]
    (when-not (and (string? claim-id) (not (str/blank? claim-id)))
      (rejection! :malformed-claim-id
                  (str "Claim id must be a non-empty string, got: " (pr-str claim-id))))
    (when-not (and (string? owner-id) (not (str/blank? owner-id)))
      (rejection! :malformed-economic-owner-id
                  (str "Economic owner id must be a non-empty string, got: " (pr-str owner-id))))
    {:claim/id claim-id
     :economic-owner-id owner-id
     :amount amount
     :weight weight}))

(defn canonical-claimants
  "Parse, validate, and canonically order a claimant set.
   Rejects duplicate claim ids and (when policy forbids) duplicate owners."
  [raw-claimants {:keys [forbid-duplicate-owners]}]
  (when-not (sequential? raw-claimants)
    (rejection! :malformed-claimants "Claimants must be a JSON array"))
  (let [claimants (mapv parse-claimant raw-claimants)
        ids (mapv :claim/id claimants)]
    (when (some #(and (some? %) (>= (count (filter (partial = %) ids)) 2)) ids)
      (rejection! :duplicate-claim-id "Duplicate claim ids are forbidden"))
    (when (and forbid-duplicate-owners
               (some #(>= (count (filter (partial = %) (map :economic-owner-id claimants))) 2)
                     (map :economic-owner-id claimants)))
      (rejection! :duplicate-economic-owner "Duplicate economic owner ids are forbidden by policy"))
    (vec (sort-by-canonical :claim/id claimants))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Outcomes
;; ──────────────────────────────────────────────────────────────────────────────

(defn parse-outcome
  "Parse one outcome record. Allocations are kept in claimant canonical order
   later; here we only validate shape."
  [raw]
  (let [outcome-id (get raw "outcome-id")
        raw-allocations (get raw "allocations")]
    (when-not (and (string? outcome-id) (not (str/blank? outcome-id)))
      (rejection! :malformed-outcome-id
                  (str "Outcome id must be a non-empty string, got: " (pr-str outcome-id))))
    (when-not (sequential? raw-allocations)
      (rejection! :malformed-outcome
                  (str "Outcome " outcome-id " allocations must be a JSON array")))
    (let [allocations
          (mapv (fn [entry]
                  (let [claim-id (get entry "claim-id")
                        allocated (parse-non-negative "allocated" (get entry "allocated"))]
                    (when-not (string? claim-id)
                      (rejection! :malformed-outcome-entry
                                  (str "Allocation entry missing claim-id in outcome " outcome-id)))
                    {:claim/id claim-id :allocated allocated}))
                raw-allocations)]
      {:outcome/id outcome-id :allocations allocations})))

(defn canonical-outcomes
  "Parse, validate, and canonically order outcomes.
   Allocation entries within an outcome are re-ordered into ascending canonical
   claim order, preserving every entry (including duplicates and claims not in
   the claimant set) so that eligibility and duplicate checks can be evaluated
   by the kernel. Outcomes are ordered by canonical :outcome/id."
  [raw-outcomes _claimants]
  (when-not (sequential? raw-outcomes)
    (rejection! :malformed-outcomes "Outcomes must be a JSON array"))
  (let [outcomes (mapv parse-outcome raw-outcomes)
        ids (mapv :outcome/id outcomes)]
    (when (some #(>= (count (filter (partial = %) ids)) 2) ids)
      (rejection! :duplicate-outcome-id "Duplicate outcome ids are forbidden"))
    (vec
     (sort-by-canonical
      :outcome/id
      (mapv (fn [outcome]
              {:outcome/id (:outcome/id outcome)
               :allocations (vec (sort-by-canonical :claim/id (:allocations outcome)))})
            outcomes)))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Proposed rates
;; ──────────────────────────────────────────────────────────────────────────────

(defn big-int-gcd
  "Greatest common divisor of two bigint values (positive result)."
  [a b]
  (let [g (.gcd (.toBigInteger (bigint a)) (.toBigInteger (bigint b)))]
    (bigint (if (neg? g) (.negate g) g))))

(defn canonical-rate
  "Normalise a proposed rate to reduced {numerator, denominator} form.
   Zero is represented only as 0/1."
  [{:keys [numerator denominator]}]
  (when-not (and (integer? numerator) (integer? denominator))
    (rejection! :malformed-rate "Rate numerator/denominator must be integers"))
  (let [n (bigint numerator)
        d (bigint denominator)]
    (when (neg? n)
      (rejection! :negative-rate-numerator "Rate numerator must be non-negative"))
    (when (<= d 0)
      (rejection! :non-positive-rate-denominator "Rate denominator must be positive"))
    (if (zero? n)
      {:numerator (bigint 0) :denominator (bigint 1)}
      (let [g (big-int-gcd n d)]
        {:numerator (bigint (/ n g))
         :denominator (bigint (/ d g))}))))

(defn canonical-proposed-rates
  "Parse proposed rates bound to outcomes in outcome canonical order.
   Each rate maps to its outcome via outcome-id. Declared numerator and
   denominator are preserved as-is; canonical-exactness (reducedness) is
   evaluated by the kernel assertion contract, not silently normalised away."
  [raw-rates outcomes]
  (when-not (sequential? raw-rates)
    (rejection! :malformed-rates "Proposed rates must be a JSON array"))
  (let [outcome-ids (mapv :outcome/id outcomes)
        rate-by-outcome (into {}
                              (map (fn [raw]
                                     (let [outcome-id (get raw "outcome-id")]
                                       (when-not (string? outcome-id)
                                         (rejection! :malformed-rate "Rate missing outcome-id"))
                                       (let [numerator (parse-non-negative "numerator" (get raw "numerator"))
                                             denominator (parse-decimal "denominator" (get raw "denominator"))]
                                         (when (<= denominator 0)
                                           (rejection! :non-positive-rate-denominator
                                                       (str "Rate denominator must be positive, got: " denominator)))
                                         [outcome-id {:numerator numerator :denominator denominator}]))))
                              raw-rates)]
    (when-not (= (set outcome-ids) (set (keys rate-by-outcome)))
      (rejection! :rates-outcome-mismatch
                  "Proposed rates must cover exactly the outcome set"))
    (mapv (fn [outcome-id]
            {:outcome/id outcome-id
             :rate (get rate-by-outcome outcome-id)})
          outcome-ids)))

;; ──────────────────────────────────────────────────────────────────────────────
;; Context construction
;; ──────────────────────────────────────────────────────────────────────────────

(defn parse-policy
  "Parse and validate the policy reference block."
  [raw]
  (when-not (map? raw)
    (rejection! :malformed-policy "Policy must be an object"))
  (let [policy-id (get raw "policy-id")
        policy-hash (get raw "policy-hash")]
    (when-not (and (string? policy-id) (not (str/blank? policy-id)))
      (rejection! :malformed-policy (str "Policy id required, got: " (pr-str policy-id))))
    (when-not (and (string? policy-hash) (re-matches #"^0x[0-9a-f]{64}$" policy-hash))
      (rejection! :malformed-policy-hash
                  (str "Policy hash must be 0x-prefixed 32-byte hex: " (pr-str policy-hash))))
    {:policy/id policy-id :policy/hash policy-hash
     :forbid-duplicate-owners (boolean (get raw "forbid-duplicate-owners" false))}))

(defn build-context
  "Construct the canonical allocation-context.v1 from parsed JSON input.

   Returns a map with every identity-defining field. Rejects malformed or
   inconsistent input with a stable classification. Callers must bind
   :allocation/id, :authoritative-randomness, and the declared totals."
  [input]
  (when-not (map? input)
    (rejection! :malformed-input "Input must be a JSON object"))
  (let [allocation-id (get input "allocation-id")
        kernel-version' (get input "kernel-version" kernel-version)
        selection-algorithm' (get input "selection-algorithm" selection-algorithm-str)
        policy (parse-policy (get input "policy"))
        raw-claimants (get input "claimants")
        raw-outcomes (get input "outcomes")
        raw-rates (get input "proposed-rates")
        capacity (parse-non-negative "capacity" (get input "capacity"))
        declared-total-weight (parse-decimal "total-eligible-weight" (get input "total-eligible-weight"))
        declared-denominator (parse-decimal "exact-pro-rata-denominator" (get input "exact-pro-rata-denominator"))
        randomness-str (get input "authoritative-randomness")]
    (when-not (and (string? allocation-id) (not (str/blank? allocation-id)))
      (rejection! :malformed-allocation-id
                  (str "Allocation id required, got: " (pr-str allocation-id))))
    (when-not (= kernel-version kernel-version')
      (rejection! :unsupported-kernel-version
                  (str "Expected kernel version " kernel-version ", got: " (pr-str kernel-version'))))
    (when-not (= selection-algorithm-str selection-algorithm')
      (rejection! :unsupported-selection-algorithm
                  (str "Expected " selection-algorithm-str ", got: " (pr-str selection-algorithm'))))
    (when (neg? capacity)
      (rejection! :non-positive-capacity "Capacity must be positive"))
    (when (zero? capacity)
      (rejection! :non-positive-capacity "Capacity must be positive"))
    (when-not (and (string? randomness-str) (re-matches #"^0x[0-9a-f]{64}$" randomness-str))
      (rejection! :malformed-randomness
                  (str "Authoritative randomness must be 0x-prefixed 32-byte hex: "
                       (pr-str randomness-str))))
    (let [claimants (canonical-claimants raw-claimants {:forbid-duplicate-owners
                                                        (:forbid-duplicate-owners policy)})
          total-weight (reduce + 0 (map :weight claimants))
          outcomes (canonical-outcomes raw-outcomes claimants)]
      (when (empty? claimants)
        (rejection! :empty-claimant-set "Claimant set must not be empty"))
      (when (empty? outcomes)
        (rejection! :empty-outcome-set "Outcome set must not be empty"))
      (when (zero? total-weight)
        (rejection! :zero-total-weight "Total eligible weight must be positive"))
      (when (not= total-weight declared-total-weight)
        (rejection! :inconsistent-total-weight
                    (str "Declared total eligible weight " declared-total-weight
                         " != sum of weights " total-weight)))
      (when (not= total-weight declared-denominator)
        (rejection! :inconsistent-pro-rata-denominator
                    (str "Exact pro-rata denominator " declared-denominator
                         " must equal total eligible weight " total-weight)))
      (let [proposed-rates (canonical-proposed-rates raw-rates outcomes)]
        {:schema-version schema-version
         :artifact-kind artifact-kind
         :allocation/id allocation-id
         :allocation-kernel-version kernel-version'
         :selection-algorithm :domain-hash-rejection-v1
         :policy policy
         :claimants claimants
         :outcomes outcomes
         :proposed-rates proposed-rates
         :capacity capacity
         :total-eligible-weight total-weight
         :exact-pro-rata-denominator total-weight
         :authoritative-randomness (bytes->byte-ints (hex->bytes randomness-str))
         :authoritative-randomness-hex randomness-str}))))

(defn context-preimage
  "The canonical value tree committed by the allocation context hash."
  [context]
  {:schema-version (:schema-version context)
   :artifact-kind (:artifact-kind context)
   :allocation/id (:allocation/id context)
   :allocation-kernel-version (:allocation-kernel-version context)
   :selection-algorithm (:selection-algorithm context)
   :policy (:policy context)
   :claimants (:claimants context)
   :outcomes (:outcomes context)
   :proposed-rates (:proposed-rates context)
   :capacity (:capacity context)
   :total-eligible-weight (:total-eligible-weight context)
   :exact-pro-rata-denominator (:exact-pro-rata-denominator context)
   :authoritative-randomness (:authoritative-randomness context)})

(defn context-hash
  "Domain-separated hash committing the complete allocation context."
  [context]
  (hc/domain-hash :allocation-context (context-preimage context)))
