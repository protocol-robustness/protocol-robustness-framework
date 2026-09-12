(ns resolver-sim.benchmark.research-theorem-outcome
  "Research theorem outcome: a canonical artifact for an individual theorem
   outcome within a benchmark evaluation.

   Each theorem outcome commits to the structured statement, premises,
   evidence references, inference policy, falsifiers, and conclusion —
   so two researchers only share the same theorem hash when they reached
   the same canonical theorem results over the same execution scope.

   The theorem statement uses a declarative :if/:then DSL:

     {:if
      [:and
       {:claim :partial-fill-calculated}
       {:claim :propagation-applied}
       {:claim :precondition-valid}]
      :then
      {:claim :successor-current-amount-equals-deferred-residual}}"
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version "research-theorem-outcome.v1")

(def ^:const valid-theorem-types
  "Controlled vocabulary for theorem type classification."
  #{:state-transition :conservation :boundedness :strategy-dominance
    :incentive-compatibility :invariant-maintenance :adversary-resistance
    :evidence-continuity :claim-entailment})

(def ^:const valid-theorem-statuses
  "Controlled vocabulary for theorem conclusion statuses."
  #{:established :supported-within-domain :contingent :not-supported
    :unverifiable :contested})

(def ^:const valid-premise-statuses
  "Controlled vocabulary for individual premise statuses."
  #{:established :assumed :not-established :contingent})

(def ^:const valid-falsifier-statuses
  "Controlled vocabulary for falsifier observation statuses."
  #{:observed :not-observed :untested})

(def ^:const valid-inference-rules
  "Controlled vocabulary for inference rule types."
  #{:conjunctive-entailment :material-implication :inductive-generalisation
    :counterexample-refutation :statistical-inference})

(defn valid-theorem-type?
  [t]
  (contains? valid-theorem-types t))

(defn valid-theorem-status?
  [s]
  (contains? valid-theorem-statuses s))

(defn build-theorem-outcome
  "Build a canonical research theorem outcome artifact.

   Required:
     theorem/id              — qualified keyword identifying the theorem
     theorem/type            — :state-transition | :conservation | ...
     theorem/statement       — {:if predicate :then {:claim claim-id}}
     theorem/scope           — {:benchmark/content-root sha256 :model/root sha256 ...}
     theorem/conclusion      — {:status kw :claim-id kw}

   Optional:
     theorem/premises        — [{:premise/id kw :status kw :evidence-hash sha256}]
     theorem/inference       — {:rule kw :policy-root sha256}
     theorem/falsifiers      — [{:falsifier/id kw :status kw}]
     theorem/limitations     — [kw ...]
     theorem/rationale       — free-form explanatory prose (not canonicalised)
     theorem/hash            — pre-computed hash (rejected on mismatch)

   Returns the theorem-outcome map with :theorem/hash computed.
   Throws on invalid inputs."
  [{:keys [theorem/id
           theorem/type
           theorem/statement
           theorem/scope
           theorem/premises
           theorem/inference
           theorem/conclusion
           theorem/falsifiers
           theorem/limitations
           theorem/rationale
           theorem/hash]}]
  (let [errors (atom [])]
    (when-not (some? id)
      (swap! errors conj "missing :theorem/id"))
    (when (and (some? id) (not (keyword? id)))
      (swap! errors conj ":theorem/id must be a keyword"))
    (when-not (and (some? type) (valid-theorem-type? type))
      (swap! errors conj "missing or invalid :theorem/type"))
    (when-not (some? statement)
      (swap! errors conj "missing :theorem/statement"))
    (when-not (some? scope)
      (swap! errors conj "missing :theorem/scope"))
    (when-not (some? conclusion)
      (swap! errors conj "missing :theorem/conclusion"))
    (let [conc-status (:status conclusion)]
      (when-not (valid-theorem-status? conc-status)
        (swap! errors conj (str "invalid theorem/conclusion status: " conc-status))))
    (when (seq @errors)
      (throw (ex-info (str "Theorem outcome build failed: " (str/join "; " @errors))
                      {:errors @errors})))
    (let [base {:schema-version schema-version
                :theorem/id id
                :theorem/type type
                :theorem/statement statement
                :theorem/scope scope
                :theorem/premises (vec (or premises []))
                :theorem/inference (or inference {})
                :theorem/conclusion conclusion
                :theorem/falsifiers (vec (or falsifiers []))
                :theorem/limitations (vec (or limitations []))
                :theorem/rationale rationale}
          computed-hash (hash-ref/sha256-ref
                         (hc/domain-hash :research-theorem-outcome
                                         (dissoc base :theorem/rationale)))]
      (when (and (some? hash) (not= hash computed-hash))
        (throw (ex-info "Declared theorem/hash does not match computed value"
                        {:declared hash :computed computed-hash})))
      (assoc base :theorem/hash computed-hash))))

(defn theorem-hash
  "Return the content-addressed hash of a theorem outcome."
  [theorem-outcome]
  (:theorem/hash theorem-outcome))

(defn theorem-valid?
  "Structural validity check for a research theorem outcome."
  [theorem-outcome]
  (and (= schema-version (:schema-version theorem-outcome))
       (some? (:theorem/id theorem-outcome))
       (some? (:theorem/type theorem-outcome))
       (some? (:theorem/hash theorem-outcome))
       (some? (:theorem/statement theorem-outcome))))

(defn validate-theorem-outcome
  "Standalone validator for a loaded research theorem outcome.
   Recomputes the theorem hash and checks structural integrity.

   Returns {:valid? bool :errors [string]}."
  [theorem-outcome]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version theorem-outcome))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version theorem-outcome))))
    (when-not (some? (:theorem/id theorem-outcome))
      (swap! errors conj "missing :theorem/id"))
    (when-not (some? (:theorem/type theorem-outcome))
      (swap! errors conj "missing :theorem/type"))
    (when-not (and (some? (:theorem/type theorem-outcome))
                   (valid-theorem-type? (:theorem/type theorem-outcome)))
      (swap! errors conj (str "invalid :theorem/type: " (:theorem/type theorem-outcome))))
    (when (some? (:theorem/hash theorem-outcome))
      (let [without-hash (dissoc theorem-outcome :theorem/hash :theorem/rationale)
            computed (hash-ref/sha256-ref (hc/domain-hash :research-theorem-outcome without-hash))]
        (when-not (= computed (:theorem/hash theorem-outcome))
          (swap! errors conj (str "theorem/hash mismatch: declared "
                                  (:theorem/hash theorem-outcome)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn theorem-references
  "Extract the set of evidence hashes referenced by theorem premises.
   Returns a sorted set of hex strings."
  [theorem-outcome]
  (into (sorted-set)
        (keep :evidence-hash)
        (:theorem/premises theorem-outcome)))

(defn theorem-outcome-collective-hash
  "Compute the collective hash for a set of theorem outcomes.
   Used to produce the :theorem-root in outcome-hashes.

   The hash commits to the sorted set of theorem hashes —
   ordering is deterministic and independent of submission order."
  [theorem-outcomes]
  (let [hashes (sort (map :theorem/hash theorem-outcomes))]
    (hash-ref/sha256-ref
     (hc/domain-hash :evidence-collection
                     {:type :theorem-outcome-collection
                      :theorem-hashes (vec hashes)}))))
