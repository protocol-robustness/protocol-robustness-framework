(ns resolver-sim.benchmark.research-claim-lifecycle
  "Append-only lifecycle transitions and deterministic state reconstruction.

   The lifecycle is an ordered, append-only log of authoritative transitions
   derived from `research-claim-acceptance.v1` artifacts. Each transition binds
   the exact claim, policy, authority and acceptance root of its governing
   acceptance record. Supersession is append-only: an earlier acceptance record
   is never mutated; it remains valid historical evidence while a supersession
   transition records that it is no longer the current authoritative state.

   The reconstruction invariant is:

     genesis state + canonical ordered transitions -> exactly one current state

   and a reconstructed state/root must equal the persisted authoritative
   lifecycle state/root."
  (:require [resolver-sim.benchmark.research-claim-acceptance :as acceptance]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]))

(def ^:const transition-schema-version "research-claim-lifecycle-transition.v1")
(def ^:const state-schema-version "research-claim-lifecycle-state.v1")
(def ^:const states #{:unseen :accepted :rejected :superseded})

(defn- root? [x] (ref/valid-sha256-ref? x))

(defn transition-root [transition]
  (ref/sha256-ref
   (hc/domain-hash :research-claim-lifecycle-transition
                   (dissoc transition :research-claim-lifecycle/root))))

(defn state-root [state]
  (ref/sha256-ref
   (hc/domain-hash :research-claim-lifecycle-state
                   (dissoc state :research-claim-lifecycle/root))))

(defn- allowed-transition? [from to supersession?]
  (or (and (= :unseen from) (contains? #{:accepted :rejected} to) (not supersession?))
      (and (contains? #{:accepted :rejected} from) (= :superseded to) supersession?)))

(defn- target-matches-decision? [transition acceptance]
  (if (:research-claim-lifecycle/supersession? transition)
    (= :superseded (:research-claim-lifecycle/to transition))
    (= (:research-claim-acceptance/decision acceptance)
       (:research-claim-lifecycle/to transition))))

(defn validate-transition
  "Validate a lifecycle transition.

   `acceptance` is the resolved `research-claim-acceptance.v1` artifact this
   transition governs. It may be nil for structural validation only (schema,
   root well-formedness, states, sequence, authorised transition and root
   self-consistency). When supplied, the transition is additionally bound to
   the acceptance artifact: the transition's acceptance/claim/policy/authority
   roots must match the acceptance's canonical roots, and the target state must
   be consistent with the acceptance decision (or be a supersession)."
  [transition acceptance]
  (let [errors (cond-> []
                 (not= transition-schema-version (:schema/version transition))
                 (conj :unsupported-schema)
                 (not (root? (:research-claim-lifecycle/claim-root transition)))
                 (conj :invalid-claim-root)
                 (not (root? (:research-claim-lifecycle/policy-root transition)))
                 (conj :invalid-policy-root)
                 (not (root? (:research-claim-lifecycle/authority-root transition)))
                 (conj :invalid-authority-root)
                 (not (root? (:research-claim-lifecycle/acceptance-root transition)))
                 (conj :invalid-acceptance-root)
                 (not (contains? states (:research-claim-lifecycle/from transition)))
                 (conj :invalid-prior-state)
                 (not (contains? states (:research-claim-lifecycle/to transition)))
                 (conj :invalid-next-state)
                 (not (and (integer? (:research-claim-lifecycle/sequence transition))
                           (not (neg? (:research-claim-lifecycle/sequence transition)))))
                 (conj :invalid-sequence)
                 (not (allowed-transition? (:research-claim-lifecycle/from transition)
                                           (:research-claim-lifecycle/to transition)
                                           (:research-claim-lifecycle/supersession? transition)))
                 (conj :unauthorised-transition)
                 (and (:research-claim-lifecycle/root transition)
                      (not= (:research-claim-lifecycle/root transition)
                            (transition-root transition)))
                 (conj :root-mismatch)
                 (and acceptance
                      (not= acceptance/acceptance-schema-version (:schema/version acceptance)))
                 (conj :invalid-acceptance-artifact)
                 (and acceptance
                      (not= (:research-claim-acceptance/root acceptance)
                            (acceptance/acceptance-root acceptance)))
                 (conj :invalid-acceptance-root)
                 (and acceptance
                      (not= (:research-claim-acceptance/root acceptance)
                            (:research-claim-lifecycle/acceptance-root transition)))
                 (conj :acceptance-root-mismatch)
                 (and acceptance
                      (not= (:research-claim-acceptance/claim-root acceptance)
                            (:research-claim-lifecycle/claim-root transition)))
                 (conj :claim-root-mismatch)
                 (and acceptance
                      (not= (:research-claim-acceptance/policy-root acceptance)
                            (:research-claim-lifecycle/policy-root transition)))
                 (conj :policy-root-mismatch)
                 (and acceptance
                      (not= (:research-claim-acceptance/authority-root acceptance)
                            (:research-claim-lifecycle/authority-root transition)))
                 (conj :authority-root-mismatch)
                 (and acceptance (not (target-matches-decision? transition acceptance)))
                 (conj :decision-state-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-transition
  "Build an append-only lifecycle transition governing the resolved `acceptance`
   artifact. All lineage roots are derived from the acceptance so callers cannot
   substitute an inconsistent claim, policy or authority root."
  [{:keys [acceptance from to sequence supersession?]}]
  (let [transition {:schema/version transition-schema-version
                    :research-claim-lifecycle/claim-root
                    (:research-claim-acceptance/claim-root acceptance)
                    :research-claim-lifecycle/policy-root
                    (:research-claim-acceptance/policy-root acceptance)
                    :research-claim-lifecycle/authority-root
                    (:research-claim-acceptance/authority-root acceptance)
                    :research-claim-lifecycle/acceptance-root
                    (:research-claim-acceptance/root acceptance)
                    :research-claim-lifecycle/from from
                    :research-claim-lifecycle/to to
                    :research-claim-lifecycle/sequence sequence
                    :research-claim-lifecycle/supersession? (boolean supersession?)}
        validation (validate-transition transition acceptance)]
    (when-not (:valid? validation)
      (throw (ex-info "Research claim lifecycle transition is invalid" validation)))
    (assoc transition :research-claim-lifecycle/root (transition-root transition))))

(defn- valid-history? [transitions]
  (let [sequences (map :research-claim-lifecycle/sequence transitions)]
    (and (= sequences (range (count transitions)))
         (= (count sequences) (count (set sequences))))))

(defn reconstruct-research-claim-lifecycle
  "Reconstruct the unique current lifecycle state from an authoritative log.

   `transitions` must form a contiguous, non-repeating, ordered sequence that
   shares a single claim and policy lineage and chains each transition to the
   previous transition's target state. When `acceptances` (a map from sequence
   to the resolved acceptance artifact) is supplied, every transition is also
   bound to its governing acceptance for adversarial lineage verification."
  [{:keys [claim-root policy-root transitions acceptances]}]
  (let [transitions (vec transitions)
        errors (cond-> []
                 (not (root? claim-root)) (conj :invalid-claim-root)
                 (not (root? policy-root)) (conj :invalid-policy-root)
                 (not (valid-history? transitions)) (conj :invalid-history-order)
                 (some (fn [t]
                         (not (:valid?
                               (validate-transition
                                t (get acceptances
                                       (:research-claim-lifecycle/sequence t))))))
                       transitions)
                 (conj :invalid-transition)
                 (some #(or (not= claim-root (:research-claim-lifecycle/claim-root %))
                            (not= policy-root (:research-claim-lifecycle/policy-root %)))
                       transitions)
                 (conj :lineage-mismatch)
                 (some (fn [[prior current]]
                         (not= (:research-claim-lifecycle/to prior)
                               (:research-claim-lifecycle/from current)))
                       (partition 2 1 transitions))
                 (conj :prior-state-mismatch))]
    (when (seq errors)
      (throw (ex-info "Research claim lifecycle cannot be reconstructed"
                      {:valid? false :errors errors})))
    (let [state {:schema/version state-schema-version
                 :research-claim-lifecycle/claim-root claim-root
                 :research-claim-lifecycle/policy-root policy-root
                 :research-claim-lifecycle/state
                 (or (:research-claim-lifecycle/to (last transitions)) :unseen)
                 :research-claim-lifecycle/transition-sequence
                 (if (seq transitions) (dec (count transitions)) -1)
                 :research-claim-lifecycle/transition-roots
                 (mapv :research-claim-lifecycle/root transitions)}]
      (assoc state :research-claim-lifecycle/root (state-root state)))))

(defn validate-persisted-state
  "Verify a persisted lifecycle state exactly matches a freshly reconstructed
   state and that its canonical root is self-consistent."
  [state reconstructed]
  {:valid? (and (= state reconstructed)
                (= (:research-claim-lifecycle/root state)
                   (state-root state)))
   :errors (cond-> []
             (not= state reconstructed) (conj :state-mismatch)
             (not= (:research-claim-lifecycle/root state) (state-root state))
             (conj :state-root-mismatch))})
