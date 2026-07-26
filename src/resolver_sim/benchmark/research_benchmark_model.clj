(ns resolver-sim.benchmark.research-benchmark-model
  "research-benchmark-model.v1
   
   Content-addressed system model for a research benchmark.
   
   The model artifact contains the system model only — state, actors,
   actions, transitions and invariants. Sibling components (incentives,
   adversary, parameters, generators, cases, claims, falsifiers,
   evaluation policy, evidence contract) are separate artifacts resolved
   by their own registry entry roots.
   
   The relationship is: (= (:benchmark/model-root registry-entry)
                         (:model/hash model))
   
   Use build-model to construct and validate-model for standalone
   verification of loaded artifacts."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "research-benchmark-model.v1")

;; ── Closed vocabularies ───────────────────────────────────────────────────

(def ^:const transition-types
  "Closed vocabulary for :transition/type."
  #{:transition/creation :transition/state-change :transition/escalation
    :transition/resolution :transition/governance :transition/economic
    :transition/maintenance :transition/timeout})

(def ^:const valid-comparison-ops
  "Operators permitted in transition preconditions and postconditions."
  #{:= :> :< :>= :<= :not=})

(def ^:const temporal-operators
  "Temporal operators that are valid in model invariants but NOT
   in transition preconditions/postconditions."
  #{:always :eventually :after :before})

;; ── Known field sets for unknown-key rejection ───────────────────────────

(def ^:const known-top-level-keys
  #{:schema-version :model/id :model/version :model/state
    :model/actors :model/actions :model/transitions
    :model/invariants :model/hash})

(def ^:const known-state-keys
  #{:entities :variables :authority-policies})

(def ^:const known-actor-keys
  #{:actor/id :actor/capabilities})

(def ^:const known-transition-keys
  #{:transition/id :transition/type :action
    :preconditions :postconditions})

(def ^:const known-predicate-keys
  #{:kind :predicate})

;; ── Qualified-keyword validation ──────────────────────────────────────────

(defn- qualified-model-keyword?
  [x]
  (and (keyword? x) (some? (namespace x))))

;; ── Unknown-key helper ────────────────────────────────────────────────────

(defn- unknown-keys
  "Return a sorted vector of keys in m that are not in known-set."
  [m known-set]
  (vec (sort (clojure.set/difference (set (keys m)) known-set))))

;; ── Canonicalisation helpers ──────────────────────────────────────────────

(defn- sort-vector [v] (vec (sort v)))

(defn- sort-by-key [v k] (vec (sort-by k v)))

(defn- canonicalise-model
  [{:keys [model/state model/actors model/actions
           model/transitions model/invariants] :as m}]
  (let [state (when state
                (merge state
                       {:entities (sort-vector (:entities state))
                        :variables (sort-vector (:variables state))
                        :authority-policies (sort-vector (:authority-policies state))}))]
    (cond-> (dissoc m :model/hash)
      state (assoc :model/state state)
      actors (assoc :model/actors
                    (mapv (fn [a] (update a :actor/capabilities sort-vector))
                          (sort-by-key actors :actor/id)))
      actions (assoc :model/actions (sort-vector actions))
      transitions (assoc :model/transitions (sort-by-key transitions :transition/id))
      invariants (assoc :model/invariants (sort-vector invariants)))))

;; ── Predicate validation (narrow profile for transitions) ─────────────────

(defn- valid-transition-predicate?
  [pred depth]
  (when (pos? depth)
    (cond
      (:metric pred)
      (cond
        (not (contains? pred :op)) "missing :op in metric leaf"
        (not (valid-comparison-ops (:op pred)))
        (str "invalid comparison :op " (:op pred) " — must be one of " valid-comparison-ops)
        (nil? (:metric pred)) "nil :metric in metric leaf"
        (seq (unknown-keys pred (into #{:metric :op :value} known-predicate-keys)))
        (str "unknown keys in metric predicate: "
             (unknown-keys pred (into #{:metric :op :value} known-predicate-keys)))
        :else nil)

      (:state pred)
      (let [s (:state pred)]
        (cond
          (not (map? s)) "expected :state to be a map"
          (not (contains? s :query)) "missing :query in :state"
          (not (contains? s :op)) "missing :op in :state"
          (not (valid-comparison-ops (:op s)))
          (str "invalid comparison :op " (:op s) " in :state")
          (seq (unknown-keys pred (conj known-predicate-keys :state)))
          (str "unknown keys in state predicate: "
               (unknown-keys pred (conj known-predicate-keys :state)))
          :else nil))

      (:and pred)
      (first (keep #(valid-transition-predicate? % (dec depth)) (:and pred)))

      (:or pred)
      (first (keep #(valid-transition-predicate? % (dec depth)) (:or pred)))

      (:not pred)
      (valid-transition-predicate? (:not pred) (dec depth))

      (:implies pred)
      (let [iv (:implies pred)]
        (or (valid-transition-predicate? (:if iv) (dec depth))
            (valid-transition-predicate? (:then iv) (dec depth))))

      (some temporal-operators [(first (keys pred))])
      (str "temporal operator " (first (keys pred))
           " is not allowed in transition preconditions/postconditions")

      :else
      (str "unrecognized predicate shape: expected :state, :metric, :and, :or, :not, or :implies"))))

;; ── Builder ───────────────────────────────────────────────────────────────

(defn build-model
  "Build a research-benchmark-model.v1 artifact.
   
   Input is a map with:
     :model/id           — qualified keyword (required)
     :model/version      — positive integer (required)
     :model/state        — {:entities [...] :variables [...] :authority-policies [...]}
     :model/actors       — [{:actor/id <kw> :actor/capabilities [<kw>...]} ...]
     :model/actions      — [<kw>...]
     :model/transitions  — [{:transition/id ... :transition/type ... :action ...
                              :preconditions [...] :postconditions [...]} ...]
     :model/invariants   — [<kw>...]
   
   Returns the complete artifact with :model/hash computed."
  [{:keys [model/id model/version model/state model/actors
           model/actions model/transitions model/invariants]}]
  (when-not id
    (throw (ex-info "Model requires :model/id" {})))
  (when-not (qualified-model-keyword? id)
    (throw (ex-info (str ":model/id must be a qualified keyword, got " (pr-str id)) {})))
  (when-not (and (integer? version) (pos? version))
    (throw (ex-info ":model/version must be a positive integer" {:version version})))
  (let [canonical-input
        {:schema-version schema-version
         :model/id id
         :model/version version
         :model/state state
         :model/actors (vec (or actors []))
         :model/actions (vec (or actions []))
         :model/transitions (vec (or transitions []))
         :model/invariants (vec (or invariants []))}
        preimage (canonicalise-model canonical-input)
        model-hash (str "sha256:" (hc/domain-hash :research-benchmark-model preimage))]
    (assoc preimage :model/hash model-hash)))

;; ── Golden test vectors ───────────────────────────────────────────────────

(def ^:const golden-vectors
  "Canonical model EDN maps with pre-computed hashes.
   Used for serialisation stability tests — the public artifact hash
   must not depend on construction through build-model alone.
   
   Each vector is {:description <str> :model <map> :expected-hash <str>}.
   
   To regenerate hashes after an intentional serialisation change:
     (require '[resolver-sim.benchmark.research-benchmark-model :as rbm])
     (run! #(println (rbm/regenerate-golden-hash! %)) rbm/golden-vectors)
   
   Review the diff carefully: a changed hash means old model roots
   with the previous serialisation become unverifiable."
  [{:description "Minimal model with all optional fields absent"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/minimal
     :model/version 1}
    :expected-hash nil}

   {:description "Full model with canonical ordering"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :model/yield-partial-fill
     :model/version 1
     :model/state
     {:entities [:yield/position :yield/deferred-position]
      :variables [:position/status :position/current-amount]
      :authority-policies [:position/current-amount-precedence]}
     :model/actors
     [{:actor/id :actor/participant
       :actor/capabilities [:action/request-withdrawal]}
      {:actor/id :actor/researcher
       :actor/capabilities [:action/challenge-claim :action/propose-case]}]
     :model/actions
     [:action/request-withdrawal
      :action/apply-allocation]
     :model/transitions
     [{:transition/id :transition/apply
       :transition/type :transition/economic
       :action :action/apply-allocation
       :preconditions
       [{:kind :predicate
         :predicate
         {:state {:query [:yield/position :status] :op := :value :active}}}]
       :postconditions
       [{:kind :predicate
         :predicate
         {:state {:query [:yield/position :status] :op := :value :withdrawn}}}]}]
     :model/invariants [:yield/conservation]}
    :expected-hash nil}])

(def ^:const negative-golden-vectors
  "Malformed model maps that must fail validation.
   Proves that independent loaders fail closed — not only that
   valid models round-trip through build-model.
   
   Each vector is {:description <str> :model <map> :expected-failure <re-pattern>}."
  [{:description "Unknown top-level key"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/unknown-key
     :model/version 1
     :model/unknown-key "should be rejected"}
    :expected-failure #"unknown top-level"}

   {:description "Unqualified actor ID"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/unqualified-actor
     :model/version 1
     :model/actors [{:actor/id :bare-actor :actor/capabilities []}]}
    :expected-failure #"not qualified"}

   {:description "Duplicate action keyword"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/duplicate-action
     :model/version 1
     :model/actions [:action/foo :action/foo]}
    :expected-failure #"duplicate action"}

   {:description "Undeclared action reference in transition"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/undeclared-action
     :model/version 1
     :model/actions [:action/declared]
     :model/transitions
     [{:transition/id :transition/t
       :transition/type :transition/economic
       :action :action/undeclared}]}
    :expected-failure #"undeclared action"}

   {:description "Invalid transition type"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/bad-type
     :model/version 1
     :model/actions [:action/foo]
     :model/transitions
     [{:transition/id :transition/t
       :transition/type :transition/nonexistent
       :action :action/foo}]}
    :expected-failure #"invalid.*transition/type"}

   {:description "Temporal predicate in transition precondition"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/temporal-pred
     :model/version 1
     :model/actions [:action/foo]
     :model/transitions
     [{:transition/id :transition/t
       :transition/type :transition/economic
       :action :action/foo
       :preconditions
       [{:kind :predicate
         :predicate {:always {:state {:query [:x] :op := :value 1}}}}]}]}
    :expected-failure #"temporal"}

   {:description "State variable reference undeclared in :variables"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/undeclared-var
     :model/version 1
     :model/state {:variables [:yield/declared-var]}
     :model/actions [:action/foo]
     :model/transitions
     [{:transition/id :transition/t
       :transition/type :transition/economic
       :action :action/foo
       :preconditions
       [{:kind :predicate
         :predicate
         {:state {:query [:yield/undeclared-var] :op := :value 1}}}]}]}
    :expected-failure #"undeclared variable"}

   {:description "Incorrect declared model hash"
    :model
    {:schema-version "research-benchmark-model.v1"
     :model/id :test/bad-hash
     :model/version 1
     :model/hash "sha256:0000000000000000000000000000000000000000000000000000000000000000"}
    :expected-failure #"hash mismatch"}])

;; ── State-variable closure helper ─────────────────────────────────────────

(defn- check-state-variable-reference
  "Validate that direct state-variable references in a predicate
   exist in the declared :variables set.
   
   A direct reference is a single-element query like [:x].
   Multi-element paths like [:x :y] are structured paths deferred to v2.
   
   Returns nil or an error string."
  [pred declared-vars transition-id label]
  (when (:state pred)
    (let [s (:state pred)
          query (:query s)]
      (when (and (vector? query) (= 1 (count query)))
        (let [var-id (first query)]
          (when (and (qualified-model-keyword? var-id)
                     (not (contains? declared-vars var-id)))
            (str "transition " transition-id " " label
                 " references undeclared variable " var-id
                 " — must be one of " declared-vars)))))))

;; ── Standalone validator ──────────────────────────────────────────────────

(defn validate-model
  "Standalone validator for a loaded or constructed model artifact.
   
   Returns {:valid? bool :errors [string]}.
   
   Performs:
     - Schema version check
     - Unknown top-level key rejection
     - Hash recomputation
     - Required field presence and type checks
     - Qualified keyword validation for all identifiers
     - Unique ID checks (actors, actions, transitions, invariants,
       state entities, variables, authority-policies)
     - Known-field validation for state, actor, transition, predicate maps
     - Referential closure (actions referenced by transitions)
     - Transition predicate structural validation
     - Closed vocabulary enforcement for :transition/type and operators"
  [model]
  (let [errors (atom [])]
    ;; Schema version
    (when-not (= schema-version (:schema-version model))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version model))))
    ;; Unknown top-level keys
    (let [unknown (unknown-keys model known-top-level-keys)]
      (when (seq unknown)
        (swap! errors conj (str "unknown top-level keys: " unknown))))
    ;; Required fields
    (when-not (qualified-model-keyword? (:model/id model))
      (swap! errors conj ":model/id must be a qualified keyword"))
    (let [v (:model/version model)]
      (when-not (and (integer? v) (pos? v))
        (swap! errors conj (str ":model/version must be a positive integer, got " (pr-str v)))))
    ;; State vocabulary
    (when-let [state (:model/state model)]
      (let [unknown-state (unknown-keys state known-state-keys)]
        (when (seq unknown-state)
          (swap! errors conj (str "unknown :model/state keys: " unknown-state))))
      (doseq [ent (:entities state [])]
        (when-not (qualified-model-keyword? ent)
          (swap! errors conj (str "state entity not qualified: " (pr-str ent)))))
      (let [entities (:entities state [])]
        (when-not (= (count entities) (count (set entities)))
          (swap! errors conj "duplicate entity in :model/state :entities")))
      (doseq [v (:variables state [])]
        (when-not (qualified-model-keyword? v)
          (swap! errors conj (str "state variable not qualified: " (pr-str v)))))
      (let [vars (:variables state [])]
        (when-not (= (count vars) (count (set vars)))
          (swap! errors conj "duplicate variable in :model/state :variables")))
      (doseq [ap (:authority-policies state [])]
        (when-not (qualified-model-keyword? ap)
          (swap! errors conj (str "authority policy not qualified: " (pr-str ap)))))
      (let [aps (:authority-policies state [])]
        (when-not (= (count aps) (count (set aps)))
          (swap! errors conj "duplicate authority-policy in :model/state :authority-policies"))))
    ;; Actors
    (let [actors (:model/actors model [])
          actor-ids (map :actor/id actors)]
      (doseq [a actors]
        (let [unknown-actor (unknown-keys a known-actor-keys)]
          (when (seq unknown-actor)
            (swap! errors conj (str "unknown keys in actor " (:actor/id a) ": " unknown-actor))))
        (when-not (qualified-model-keyword? (:actor/id a))
          (swap! errors conj (str "actor/id not qualified: " (pr-str (:actor/id a)))))
        (let [caps (:actor/capabilities a [])]
          (when-not (= (count caps) (count (set caps)))
            (swap! errors conj (str "duplicate capability in actor " (:actor/id a))))))
      (when-not (= (count actor-ids) (count (set actor-ids)))
        (swap! errors conj "duplicate actor/id")))
    ;; Actions
    (let [actions (:model/actions model [])]
      (doseq [a actions]
        (when-not (qualified-model-keyword? a)
          (swap! errors conj (str "action not qualified: " (pr-str a)))))
      (when-not (= (count actions) (count (set actions)))
        (swap! errors conj "duplicate action")))
    ;; Transitions
    (let [transitions (:model/transitions model [])
          declared-actions (set (:model/actions model []))
          declared-vars (set (get-in model [:model/state :variables] []))]
      (doseq [t transitions]
        (let [unknown-trans (unknown-keys t known-transition-keys)]
          (when (seq unknown-trans)
            (swap! errors conj (str "unknown keys in transition " (:transition/id t) ": " unknown-trans))))
        (when-not (qualified-model-keyword? (:transition/id t))
          (swap! errors conj (str "transition/id not qualified: " (pr-str (:transition/id t)))))
        (let [tt (:transition/type t)]
          (when-not (contains? transition-types tt)
            (swap! errors conj (str "invalid :transition/type " tt " — must be one of " transition-types))))
        (let [action (:action t)]
          (when-not (contains? declared-actions action)
            (swap! errors conj (str "transition " (:transition/id t)
                                    " references undeclared action " action))))
        ;; Pre/post condition validation
        (doseq [pc (:preconditions t [])
                :let [pred (:predicate pc)]]
          (let [unknown-pc (unknown-keys pc known-predicate-keys)]
            (when (seq unknown-pc)
              (swap! errors conj (str "transition " (:transition/id t)
                                      " precondition has unknown keys: " unknown-pc))))
          (when-not (= :predicate (:kind pc))
            (swap! errors conj (str "transition " (:transition/id t)
                                    " precondition kind " (:kind pc) " — only :predicate supported")))
          (when-let [e (valid-transition-predicate? pred 10)]
            (swap! errors conj (str "transition " (:transition/id t)
                                    " precondition error: " e)))
          (when-let [e (check-state-variable-reference pred declared-vars (:transition/id t) "precondition")]
            (swap! errors conj e)))
        (doseq [pc (:postconditions t [])
                :let [pred (:predicate pc)]]
          (let [unknown-pc (unknown-keys pc known-predicate-keys)]
            (when (seq unknown-pc)
              (swap! errors conj (str "transition " (:transition/id t)
                                      " postcondition has unknown keys: " unknown-pc))))
          (when-not (= :predicate (:kind pc))
            (swap! errors conj (str "transition " (:transition/id t)
                                    " postcondition kind " (:kind pc) " — only :predicate supported")))
          (when-let [e (valid-transition-predicate? pred 10)]
            (swap! errors conj (str "transition " (:transition/id t)
                                    " postcondition error: " e)))
          (when-let [e (check-state-variable-reference pred declared-vars (:transition/id t) "postcondition")]
            (swap! errors conj e))))
      (when-not (= (count transitions) (count (set (map :transition/id transitions))))
        (swap! errors conj "duplicate transition/id")))
    ;; Invariants
    (let [invariants (:model/invariants model [])]
      (doseq [iv invariants]
        (when-not (qualified-model-keyword? iv)
          (swap! errors conj (str "invariant not qualified: " (pr-str iv)))))
      (when-not (= (count invariants) (count (set invariants)))
        (swap! errors conj "duplicate invariant")))
    ;; Hash recomputation
    (let [declared (:model/hash model)
          preimage (canonicalise-model model)
          computed (str "sha256:" (hc/domain-hash :research-benchmark-model preimage))]
      (when (and declared (not= declared computed))
        (swap! errors conj (str ":model/hash mismatch: declared " declared
                                " computed " computed))))
    {:valid? (empty? @errors) :errors @errors}))

;; ── Lightweight envelope check ────────────────────────────────────────────

(defn model-envelope?
  "Quick structural check for a model artifact.
   
   Checks only the envelope: schema-version, qualified :model/id,
   positive :model/version, and :model/hash presence.
   
   Does NOT recompute the hash or validate semantics.
   Use validate-model for full verification."
  [model]
  (and (= schema-version (:schema-version model))
       (qualified-model-keyword? (:model/id model))
       (and (integer? (:model/version model))
            (pos? (:model/version model)))
       (some? (:model/hash model))))

;; ── Integration validator ─────────────────────────────────────────────────

(defn validate-model-reference
  "Validate that a registry entry correctly references a model artifact.
   
   1. Fully validates the model (recomputing its hash, checking semantics).
   2. Checks that the validated model hash equals the registry entry's
      :benchmark/model-root.
   
   Returns {:status :valid | :invalid-model | :reference-mismatch
            :errors [string]}."
  [registry-entry model]
  (let [validation (validate-model model)]
    (if-not (:valid? validation)
      {:status :invalid-model
       :errors (:errors validation)}
      (let [entry-root (:benchmark/model-root registry-entry)
            model-hash (:model/hash model)]
        (if (= entry-root model-hash)
          {:status :valid}
          {:status :reference-mismatch
           :errors [(str ":benchmark/model-root " entry-root
                         " does not match :model/hash " model-hash)]})))))

;; ── Golden vector stabilisation ───────────────────────────────────────────

(defn compute-golden-hash
  "Compute the model hash for a golden vector, updating its :expected-hash.
   
   Returns the vector with :expected-hash populated.
   Use this to inspect hashes without modifying the source definition."
  [{:keys [model] :as vector}]
  (let [preimage (canonicalise-model model)
        hash (str "sha256:" (hc/domain-hash :research-benchmark-model preimage))]
    (assoc vector :expected-hash hash)))

(defn regenerate-golden-hash!
  "Regenerate the expected hash for one golden vector.
   
   Prints old and new hashes so the change is visible.
   Only intended for intentional serialisation contract changes.
   
   After changing golden-vectors hashes, review whether old model
   roots with the previous serialisation remain verifiable.
   
   Returns the updated vector with :expected-hash recomputed."
  [{:keys [description expected-hash model] :as vector}]
  (let [preimage (canonicalise-model model)
        new-hash (str "sha256:" (hc/domain-hash :research-benchmark-model preimage))]
    (println "Golden vector:" description)
    (println "  Old hash: " (or expected-hash "<nil>"))
    (println "  New hash: " new-hash)
    (assoc vector :expected-hash new-hash)))

(defn golden-vectors-stable?
  "Verify that all golden vectors produce their expected hashes.
   
   Returns {:stable? bool :mismatches [{:description <str>
                                        :expected <str>
                                        :computed <str>}]}."
  []
  (let [results (map (fn [v]
                       (let [computed (compute-golden-hash v)
                             expected (:expected-hash v)]
                         {:description (:description v)
                          :expected expected
                          :computed (:expected-hash computed)
                          :match? (= expected (:expected-hash computed))}))
                     golden-vectors)
        mismatches (vec (remove :match? results))]
    {:stable? (empty? mismatches)
     :mismatches mismatches}))
