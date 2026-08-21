(ns resolver-sim.composition.obligation
  "Typed assurance obligations for composition plans.

   An obligation is a machine-checkable assurance requirement on an executed
   combination:

     :effect    — inspect what actually happened (realized effects must
                  satisfy a constraint, e.g. a required held action)
     :invariant — prove a property over state/evidence (e.g. ledger-balanced)
     :evidence  — prove that a required artifact/proof exists and verifies

   Each source obligation is resolved against an EXPLICIT kind-aware
   definitions registry into a committed identity (id, definition root,
   input-contract root, satisfaction-contract root) plus its instance data
   (scope, constraint). That resolved identity is committed into the compiled
   plan, so the plan binds the exact obligation semantics resolved at compile
   time — never whatever the same symbolic keyword resolves to later.

   :scope is first-class and required: it selects WHICH subject (combination
   effects/output/evidence/state, or a specific node's output), AT WHICH phase,
   the obligation applies. Without scope, subject-selection rules would be
   hidden inside individual obligation definitions."
  (:require [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version
  "obligation.v1")

(def supported-kinds
  "Phase 1 obligation kinds."
  #{:effect :invariant :evidence})

(def supported-subjects
  "Phase 1 scope subjects."
  #{:combination/effects :combination/input :combination/output
    :combination/state :combination/evidence :node/output})

(def supported-phases
  "Phase 1 scope phases."
  #{:post-execution})

(def scope-fields
  "Permitted keys of an obligation scope map."
  #{:subject :phase :node/id})

(defn- valid-ref?
  [root]
  (and (string? root) (hash-ref/valid-sha256-ref? root)))

;; ── definition validation ──────────────────────────────────────────────────

(defn validate-definition
  "Structural validation for a definitions-registry entry.
   Returns {:valid? bool :violations [<violation-maps>]}.

   A definition must carry a committed identity (id, kind, and the three
   committed roots) and a scope-contract declaring what scope it accepts."
  [entry]
  (let [id (:obligation/id entry)
        kind (:obligation/kind entry)
        root (:obligation/root entry)
        input (:obligation/input-contract-root entry)
        satisfaction (:obligation/satisfaction-contract-root entry)
        scope-contract (:obligation/scope-contract entry)
        v (cond-> []
            (not (map? entry))
            (conj {:violation/id :violation/non-map-obligation-definition
                   :details {:entry entry}})

            (and (map? entry) (not (keyword? id)))
            (conj {:violation/id :violation/invalid-obligation-definition-id
                   :details {:id id}})

            (and (map? entry) (not (contains? supported-kinds kind)))
            (conj {:violation/id :violation/unsupported-obligation-kind
                   :details {:kind kind :supported (vec (sort supported-kinds))}})

            (and (map? entry) (not (valid-ref? root)))
            (conj {:violation/id :violation/invalid-obligation-root
                   :details {:root root}})

            (and (map? entry) (not (valid-ref? input)))
            (conj {:violation/id :violation/invalid-obligation-input-contract-root
                   :details {:input-contract-root input}})

            (and (map? entry) (not (valid-ref? satisfaction)))
            (conj {:violation/id :violation/invalid-obligation-satisfaction-contract-root
                   :details {:satisfaction-contract-root satisfaction}})

            (and (map? entry) (not (map? scope-contract)))
            (conj {:violation/id :violation/invalid-obligation-scope-contract
                   :details {:scope-contract scope-contract}}))]
    {:valid? (empty? v)
     :violations v}))

;; ── scope validation ───────────────────────────────────────────────────────

(defn validate-scope
  "Validate an obligation scope against a definition's scope-contract.
   Returns [<violation-maps>] (empty when the scope is admissible).

   scope-contract — {:subjects #{…} :phases #{…} :node-id-required? bool}"
  [scope scope-contract]
  (let [subjects (:subjects scope-contract #{})
        phases (:phases scope-contract #{})
        node-id-required? (:node-id-required? scope-contract false)]
    (cond-> []
      (not (map? scope))
      (conj {:violation/id :violation/invalid-obligation-scope
             :details {:scope scope}})

      (and (map? scope) (nil? (:subject scope)))
      (conj {:violation/id :violation/missing-obligation-scope-subject
             :details {:scope scope}})

      (and (map? scope) (some? (:subject scope))
           (not (contains? subjects (:subject scope))))
      (conj {:violation/id :violation/unsupported-obligation-scope-subject
             :details {:subject (:subject scope)
                       :supported (vec (sort subjects))}})

      (and (map? scope) (contains? scope :phase)
           (not (contains? phases (:phase scope))))
      (conj {:violation/id :violation/unsupported-obligation-scope-phase
             :details {:phase (:phase scope)
                       :supported (vec (sort phases))}})

      (and (map? scope) node-id-required? (nil? (:node/id scope)))
      (conj {:violation/id :violation/missing-obligation-node-id
             :details {:scope scope}})

      (and (map? scope) (seq (remove scope-fields (keys scope))))
      (conj {:violation/id :violation/unknown-obligation-scope-key
             :details {:unknown (vec (sort (remove scope-fields (keys scope))))
                       :supported (vec (sort scope-fields))}}))))

;; ── constraint validation ──────────────────────────────────────────────────

(defn validate-constraint
  "Validate an obligation constraint against a definition's
   constraint-contract. Returns [<violation-maps>] (empty when the constraint
   is admissible or no contract/constraint is declared)."
  [constraint constraint-contract]
  (if (or (nil? constraint) (nil? constraint-contract))
    []
    (let [allowed (:fields constraint-contract)
          required (:required constraint-contract)]
      (cond-> []
        (and (seq allowed) (seq (remove allowed (keys constraint))))
        (conj {:violation/id :violation/unknown-obligation-constraint-field
               :details {:unknown (vec (sort (remove allowed (keys constraint))))
                         :supported (vec (sort allowed))}})

        (and (seq required) (seq (remove required (keys constraint))))
        (conj {:violation/id :violation/missing-obligation-constraint-field
               :details {:missing (vec (sort (remove required (keys constraint))))
                         :required (vec (sort required))}})))))

;; ── source obligation validation ───────────────────────────────────────────

(defn validate-obligation
  "Structural validation of a source obligation
   {:obligation/kind :effect|:invariant|:evidence
    :obligation/ref <kw>
    :obligation/scope {...}
    :obligation/constraint {...}}.
   Returns {:valid? bool :violations [<violation-maps>]}."
  [obligation]
  (let [kind (:obligation/kind obligation)
        ref (:obligation/ref obligation)
        v (cond-> []
            (not (map? obligation))
            (conj {:violation/id :violation/non-map-obligation
                   :details {:obligation obligation}})

            (and (map? obligation) (not (contains? supported-kinds kind)))
            (conj {:violation/id :violation/unsupported-obligation-kind
                   :details {:kind kind
                             :supported (vec (sort supported-kinds))}})

            (and (map? obligation) (not (keyword? ref)))
            (conj {:violation/id :violation/invalid-obligation-ref
                   :details {:ref ref}})

            (and (map? obligation) (nil? (:obligation/scope obligation)))
            (conj {:violation/id :violation/missing-obligation-scope
                   :details {:obligation obligation}})

            (and (map? obligation) (contains? obligation :obligation/constraint)
                 (not (map? (:obligation/constraint obligation))))
            (conj {:violation/id :violation/invalid-obligation-constraint
                   :details {:constraint (:obligation/constraint obligation)}}))]
    {:valid? (empty? v)
     :violations v}))

;; ── resolution ─────────────────────────────────────────────────────────────

(defn resolve-obligation
  "Resolve a source obligation against an explicit definitions registry.

   registry — {<ref-id> {:obligation/id <kw>
                         :obligation/kind <kw>
                         :obligation/root \"sha256:…\"
                         :obligation/input-contract-root \"sha256:…\"
                         :obligation/satisfaction-contract-root \"sha256:…\"
                         :obligation/scope-contract {…}}}

   Returns {:resolved? true :obligation <resolved identity>} or
           {:resolved? false :violation/id <kw> :ref <ref> ...}.

   Fail-closed on: an unknown ref, a ref resolving to a different kind, a
   definition lacking its committed identity, a scope not admissible for the
   definition, or a constraint violating the definition's constraint-contract.
   A ref that cannot be resolved is never silently dropped."
  [registry obligation]
  (let [validation (validate-obligation obligation)]
    (if-not (:valid? validation)
      {:resolved? false
       :violation/id :violation/invalid-obligation
       :ref (:obligation/ref obligation)
       :violations (:violations validation)}
      (let [ref (:obligation/ref obligation)
            entry (get registry ref)]
        (if (nil? entry)
          {:resolved? false
           :violation/id :violation/unresolved-obligation
           :ref ref}
          (let [def-validation (validate-definition entry)
                kind-mismatch? (not= (:obligation/kind obligation)
                                     (:obligation/kind entry))]
            (cond
              (not (:valid? def-validation))
              {:resolved? false
               :violation/id :violation/malformed-obligation-definition
               :ref ref
               :violations (:violations def-validation)}

              kind-mismatch?
              {:resolved? false
               :violation/id :violation/obligation-wrong-kind
               :ref ref
               :kind (:obligation/kind entry)}

              :else
              (let [scope-violations (validate-scope (:obligation/scope obligation)
                                                     (:obligation/scope-contract entry))
                    constraint-violations (validate-constraint (:obligation/constraint obligation)
                                                               (:obligation/constraint-contract entry))
                    all (into scope-violations constraint-violations)]
                (if (seq all)
                  {:resolved? false
                   :violation/id :violation/invalid-obligation-scope-or-constraint
                   :ref ref
                   :violations all}
                  (let [identity (select-keys entry
                                              [:obligation/id
                                               :obligation/root
                                               :obligation/input-contract-root
                                               :obligation/satisfaction-contract-root])
                        resolved (-> identity
                                     (assoc :obligation/kind (:obligation/kind obligation)
                                            :obligation/scope (:obligation/scope obligation)))
                        resolved (if (contains? obligation :obligation/constraint)
                                   (assoc resolved :obligation/constraint
                                          (:obligation/constraint obligation))
                                   resolved)]
                    {:resolved? true
                     :obligation resolved}))))))))))
