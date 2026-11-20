(ns resolver-sim.protocols.sew.evidence-admission
  "Authoritative, round-scoped SEW dispute evidence admission.

   Admission state is mutable protocol state; `sew/dispute-evidence-set(ns resolver-sim.protocols.sew.evidence-admission).v1` is a
   deterministic projection of a closed admission state. Evidence roots are
   located in the workflow's registered candidate population, never trusted from
   the admission request."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.time.context :as time-ctx]))

(def ^:const schema "sew/dispute-evidence-set.v1")
(def ^:const domain :sew-dispute-evidence-set-v1)
(def roles #{:claimant :respondent :protocol-observation})
(def ^:private root-key (keyword "sew" "dispute-evidence-set/root"))

(defn- member-order [member]
  [(:evidence/root member) (name (:evidence/role member))])

(defn- effective-members [world workflow-id round-id]
  (let [admission (get-in world [:dispute-evidence-admissions workflow-id round-id])
        parent-round (:parent-round admission)]
    (vec (sort-by member-order
                  (concat (when (some? parent-round)
                            (effective-members world workflow-id parent-round))
                          (:members admission))))))

(defn evidence-set-payload [artifact]
  (dissoc artifact root-key))

(defn validate-evidence-set [artifact]
  (let [required #{:artifact/schema :workflow/id :adjudication/round-id
                   :parent-evidence-set/root :members}
        have (set (keys artifact))
        missing (set/difference required have)
        extra (set/difference have (conj required root-key))
        parent (:parent-evidence-set/root artifact)
        members (:members artifact)
        expected (when (and (empty? missing) (empty? extra))
                   (ref/sha256-ref
                    (hc/domain-hash domain
                                    (hc/project-canonical-safe
                                     (evidence-set-payload artifact)))))]
    {:valid? (and (empty? missing) (empty? extra)
                  (= schema (:artifact/schema artifact))
                  (integer? (:workflow/id artifact))
                  (not (neg? (:workflow/id artifact)))
                  (integer? (:adjudication/round-id artifact))
                  (not (neg? (:adjudication/round-id artifact)))
                  (or (nil? parent) (ref/valid-sha256-ref? parent))
                  (vector? members)
                  (= members (vec (sort-by member-order members)))
                  (= (count members) (count (distinct (map :evidence/root members))))
                  (every? #(and (ref/valid-sha256-ref? (:evidence/root %))
                                 (contains? roles (:evidence/role %))) members)
                  (= expected (get artifact root-key)))
     :errors (cond-> []
               (seq missing) (conj :missing-required-field)
               (seq extra) (conj :unknown-evidence-set-field)
               (not= schema (:artifact/schema artifact)) (conj :invalid-schema)
               (and (some? parent) (not (ref/valid-sha256-ref? parent))) (conj :invalid-parent-evidence-set-root)
               (not (vector? members)) (conj :invalid-members)
               (and (vector? members) (not= members (vec (sort-by member-order members)))) (conj :noncanonical-member-order)
               (and (vector? members) (not= (count members) (count (distinct (map :evidence/root members))))) (conj :duplicate-evidence-member)
               (not= expected (get artifact root-key)) (conj :evidence-set-root-mismatch))}))

(defn reconstruct-evidence-set
  "Purely reconstruct the canonical evidence-set artifact from closed admission
   state. Returns nil until the round has been closed."
  [world workflow-id round-id]
  (let [admission (get-in world [:dispute-evidence-admissions workflow-id round-id])]
    (when (= :closed (:status admission))
      (let [parent (:parent-evidence-set/root admission)
            base {:artifact/schema schema
                  :workflow/id workflow-id
                  :adjudication/round-id round-id
                  :parent-evidence-set/root parent
                  :members (effective-members world workflow-id round-id)}]
        (assoc base root-key
               (ref/sha256-ref
                (hc/domain-hash domain
                                (hc/project-canonical-safe
                                 (evidence-set-payload base)))))))))

(defn- authorized-role? [world workflow-id caller role]
  (let [transfer (t/get-transfer world workflow-id)]
    (case role
      :claimant (= caller (:from transfer))
      :respondent (= caller (:to transfer))
      :protocol-observation (= caller (:resolver
                                       (get-in world [:dispute-evidence-admissions workflow-id
                                                      (t/dispute-level world workflow-id)])))
      false)))

(defn- open-admission [world workflow-id round-id]
  (let [previous (when (pos? round-id)
                   (reconstruct-evidence-set world workflow-id (dec round-id)))]
    (cond
      (and (pos? round-id) (nil? previous)) {:ok false :error :missing-closed-parent-evidence-set}
      :else {:ok true
             :admission {:status :open
                         :resolver (get-in world [:escrow-transfers workflow-id :dispute-resolver])
                         :parent-round (when (pos? round-id) (dec round-id))
                         :parent-evidence-set/root (get previous root-key)
                         :members []}})))

(defn admit-workflow-evidence!
  "Admit already registered, provenance-verified evidence to the authoritative
   current SEW round. `evidence-ref` locates a candidate; its raw value is never
   accepted as proof of membership."
  [world {:keys [workflow-id round-id caller role evidence-ref]}]
  (let [round-id (or round-id (t/dispute-level world workflow-id))
        candidate-root (:evidence/root evidence-ref)
        current-round (t/dispute-level world workflow-id)
        existing (get-in world [:dispute-evidence-admissions workflow-id round-id])
        open-result (or (when existing {:ok true :admission existing})
                        (open-admission world workflow-id round-id))]
    (cond
      (not (t/valid-workflow-id? world workflow-id)) {:ok false :error :invalid-workflow-id :world world}
      (not= round-id current-round) {:ok false :error :noncurrent-adjudication-round :world world}
      (not (contains? roles role)) {:ok false :error :invalid-evidence-role :world world}
      (not (authorized-role? world workflow-id caller role)) {:ok false :error :unauthorized-evidence-admission :world world}
      (not (:ok open-result)) (assoc open-result :world world)
      (not= :open (get-in open-result [:admission :status])) {:ok false :error :evidence-admission-closed :world world}
      (not (contains? (set (get-in world [:evidence-hashes workflow-id] [])) candidate-root)) {:ok false :error :unregistered-workflow-evidence :world world}
      (not= :verified (get-in world [:evidence-provenance-verified workflow-id candidate-root])) {:ok false :error :unverified-evidence-provenance :world world}
      :else
      (let [members (:members (:admission open-result))
            prior (some #(when (= candidate-root (:evidence/root %)) %) members)
            member {:evidence/root candidate-root :evidence/role role}]
        (cond
          (= prior member) {:ok true :world world :idempotent? true}
          prior {:ok false :error :conflicting-evidence-role :world world}
          :else {:ok true
                 :world (assoc-in world [:dispute-evidence-admissions workflow-id round-id]
                                  (assoc (:admission open-result)
                                         :members (conj members member)))})))))

(defn close-evidence-admission!
  "Close the current round's admission state. Close authority is the current
   dispute resolver; repeated close returns the same reconstructed set."
  [world workflow-id round-id caller]
  (let [round-id (or round-id (t/dispute-level world workflow-id))
        admission (get-in world [:dispute-evidence-admissions workflow-id round-id])]
    (cond
      (not (t/valid-workflow-id? world workflow-id)) {:ok false :error :invalid-workflow-id :world world}
      (not= round-id (t/dispute-level world workflow-id)) {:ok false :error :noncurrent-adjudication-round :world world}
      (not= caller (:resolver admission)) {:ok false :error :unauthorized-evidence-close :world world}
      (nil? admission) {:ok false :error :missing-evidence-admission :world world}
      (= :closed (:status admission)) {:ok true :world world :evidence-set (reconstruct-evidence-set world workflow-id round-id) :idempotent? true}
      :else (let [world' (assoc-in world [:dispute-evidence-admissions workflow-id round-id :status] :closed)]
              {:ok true :world world' :evidence-set (reconstruct-evidence-set world' workflow-id round-id)}))))
