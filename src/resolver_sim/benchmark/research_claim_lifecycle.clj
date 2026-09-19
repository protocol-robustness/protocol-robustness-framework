(ns resolver-sim.benchmark.research-claim-lifecycle
  "Append-only lifecycle transitions and deterministic state reconstruction."
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
                   (dissoc transition :research-claim-lifecycle-transition/root))))

(defn state-root [state]
  (ref/sha256-ref
   (hc/domain-hash :research-claim-lifecycle-state
                   (dissoc state :research-claim-lifecycle-state/root))))

(defn- allowed-transition? [from to supersession?]
  (or (and (= :unseen from) (contains? #{:accepted :rejected} to) (not supersession?))
      (and (contains? #{:accepted :rejected} from) (= :superseded to) supersession?)))

(defn validate-transition [transition]
  (let [errors (cond-> []
                 (not= transition-schema-version (:schema/version transition)) (conj :unsupported-schema)
                 (not (root? (:research-claim-lifecycle/claim-root transition))) (conj :invalid-claim-root)
                 (not (root? (:research-claim-lifecycle/policy-root transition))) (conj :invalid-policy-root)
                 (not (contains? states (:research-claim-lifecycle/from transition))) (conj :invalid-prior-state)
                 (not (contains? states (:research-claim-lifecycle/to transition))) (conj :invalid-next-state)
                 (not (root? (:research-claim-lifecycle/acceptance-root transition))) (conj :invalid-acceptance-root)
                 (not (and (integer? (:research-claim-lifecycle/sequence transition))
                           (not (neg? (:research-claim-lifecycle/sequence transition))))) (conj :invalid-sequence)
                 (not (allowed-transition? (:research-claim-lifecycle/from transition)
                                           (:research-claim-lifecycle/to transition)
                                           (:research-claim-lifecycle/supersession? transition)))
                 (conj :unauthorised-transition)
                 (and (:research-claim-lifecycle/root transition)
                      (not= (:research-claim-lifecycle/root transition) (transition-root transition)))
                 (conj :root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn build-transition [{:keys [claim-root policy-root acceptance-root from to sequence supersession?]}]
  (let [transition {:schema/version transition-schema-version
                    :research-claim-lifecycle/claim-root claim-root
                    :research-claim-lifecycle/policy-root policy-root
                    :research-claim-lifecycle/acceptance-root acceptance-root
                    :research-claim-lifecycle/from from
                    :research-claim-lifecycle/to to
                    :research-claim-lifecycle/sequence sequence
                    :research-claim-lifecycle/supersession? (boolean supersession?)}
        validation (validate-transition transition)]
    (when-not (:valid? validation)
      (throw (ex-info "Research claim lifecycle transition is invalid" validation)))
    (assoc transition :research-claim-lifecycle/root (transition-root transition))))

(defn- valid-history? [transitions]
  (let [sequences (map :research-claim-lifecycle/sequence transitions)]
    (and (= sequences (range (count transitions)))
         (= (count sequences) (count (set sequences))))))

(defn reconstruct-research-claim-lifecycle
  "Reconstruct the unique current lifecycle state from an authoritative log."
  [{:keys [claim-root policy-root transitions]}]
  (let [transitions (vec transitions)
        errors (cond-> []
                 (not (root? claim-root)) (conj :invalid-claim-root)
                 (not (root? policy-root)) (conj :invalid-policy-root)
                 (not (valid-history? transitions)) (conj :invalid-history-order)
                 (some #(not (:valid? (validate-transition %))) transitions) (conj :invalid-transition)
                 (some #(or (not= claim-root (:research-claim-lifecycle/claim-root %))
                            (not= policy-root (:research-claim-lifecycle/policy-root %))) transitions)
                 (conj :lineage-mismatch)
                 (some (fn [[prior current]]
                         (not= (:research-claim-lifecycle/to prior)
                               (:research-claim-lifecycle/from current)))
                       (partition 2 1 transitions))
                 (conj :prior-state-mismatch))]
    (when (seq errors)
      (throw (ex-info "Research claim lifecycle cannot be reconstructed" {:valid? false :errors errors})))
    (let [state {:schema/version state-schema-version
                 :research-claim-lifecycle/claim-root claim-root
                 :research-claim-lifecycle/policy-root policy-root
                 :research-claim-lifecycle/state (or (:research-claim-lifecycle/to (last transitions)) :unseen)
                 :research-claim-lifecycle/transition-sequence (if (seq transitions) (dec (count transitions)) -1)
                 :research-claim-lifecycle/transition-roots (mapv :research-claim-lifecycle/root transitions)}]
      (assoc state :research-claim-lifecycle/root (state-root state)))))

(defn validate-persisted-state [state reconstructed]
  {:valid? (and (= state reconstructed)
                (= (:research-claim-lifecycle/root state)
                   (state-root state)))
   :errors (cond-> []
             (not= state reconstructed) (conj :state-mismatch)
             (not= (:research-claim-lifecycle/root state) (state-root state))
             (conj :state-root-mismatch))})
