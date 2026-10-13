(ns resolver-sim.protocols.sew.resolution-basis
  "Canonical, dispute-bound authority context for SEW resolution.

   This artifact answers under what authority, state, dispute, resolver, and
   immutable module context a decision may be made. It deliberately contains no
   decision outcome or decision-input payload; those belong to the subsequent
   sew-resolution-decision artifact."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
                        [resolver-sim.protocols.adjudication :as adjudication]
                                    [resolver-sim.protocols.sew.types :as t]
                                    [resolver-sim.protocols.sew.authority :as authority]
                                    [resolver-sim.protocols.sew.state-machine :as state-machine]
                                                [resolver-sim.protocols.sew.adjudication-snapshot :as adjudication-snapshot]))

(def ^:const schema "sew-resolution-basis.v1")
(def ^:const domain :sew-resolution-basis-v1)
(def ^:const purpose :sew-dispute-resolution)

(def authority-modes #{:primary :fallback})

(def ^:private required-fields
  #{:artifact/schema
    :resolution/purpose
    :workflow/id
    :escrow/root
    :state-before/root
    :dispute/question-root
    :dispute/root
    :dispute/evidence-set-root
    :authority/resolution-basis-root
    :authority/context-root
    :resolver/root
    :resolver/kind
    :resolver/authorization-root
    :resolution-module/root
    :resolution-module/snapshot-root
    :configuration/root
    :resolution/authority-mode})

(def ^:private root-key :sew-resolution-basis/root)

(declare validate-resolution-basis build-resolution-basis)

(def ^:private root-fields
  (disj required-fields :artifact/schema :resolution/purpose :workflow/id
        :resolver/kind :resolution/authority-mode))

(defn basis-payload
  "Exact, outcome-free projection committed by a SEW resolution basis."
  [basis]
  (dissoc basis root-key))

(defn resolution-basis-root
  "Return the canonical root after validating a complete basis artifact."
  [basis]
  (let [validation (validate-resolution-basis basis)]
    (when-not (:valid? validation)
      (throw (ex-info "sew-resolution-basis.v1 is invalid"
                      {:type :sew-resolution-basis/invalid
                       :errors (:errors validation)})))
    (ref/sha256-ref
     (hc/domain-hash domain
                     (hc/project-canonical-safe (basis-payload basis))))))

(defn basis-errors
  "Return closed-shape, type, and root-integrity errors for a basis.

   External reference membership and authorization proof verification are
   intentionally separate admission steps; this artifact validates the exact
   commitments that those verifiers must resolve."
  [basis]
  (if-not (map? basis)
    [:invalid-basis]
    (let [have (set (keys basis))
          allowed (conj required-fields root-key)
          missing (set/difference required-fields have)
          extra (set/difference have allowed)
          root (:sew-resolution-basis/root basis)]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-basis-field)
        (not= schema (:artifact/schema basis)) (conj :invalid-schema)
        (not= purpose (:resolution/purpose basis)) (conj :invalid-purpose)
        (not (and (integer? (:workflow/id basis))
                  (not (neg? (:workflow/id basis)))))
        (conj :invalid-workflow-id)
        (not (keyword? (:resolver/kind basis))) (conj :invalid-resolver-kind)
        (not (contains? authority-modes (:resolution/authority-mode basis)))
        (conj :invalid-authority-mode)
        (not (every? #(ref/valid-sha256-ref? (get basis %)) root-fields))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? root)) (conj :invalid-basis-root)
        (and (ref/valid-sha256-ref? root)
             (empty? missing)
             (empty? extra)
             (every? #(ref/valid-sha256-ref? (get basis %)) root-fields)
             (not= root
                   (ref/sha256-ref
                    (hc/domain-hash domain
                                    (hc/project-canonical-safe
                                     (basis-payload basis))))))
        (conj :basis-root-mismatch)))))

(defn validate-resolution-basis
  [basis]
  (let [errors (basis-errors basis)]
    {:valid? (empty? errors) :errors errors}))

(defn valid-resolution-basis?
  [basis]
  (:valid? (validate-resolution-basis basis)))

(def ^:private external-live-root-fields
  #{:dispute/question-root :dispute/evidence-set-root
    :authority/resolution-basis-root :authority/context-root :configuration/root})

(defn- canonical-live-value [value]
  (cond
    (float? value) (str value)
    (map? value) (into {} (map (fn [[k v]] [k (canonical-live-value v)]) value))
    (vector? value) (mapv canonical-live-value value)
    (set? value) (set (map canonical-live-value value))
    (seq? value) (mapv canonical-live-value value)
    :else value))

(defn- live-root [kind value]
  (ref/sha256-ref
   (hc/domain-hash :sew-resolution-live-commitment-v1
                   (hc/project-canonical-safe {:commitment/kind kind
                                               :commitment/value (canonical-live-value value)}))))

(defn- snapshot-roots [snapshot]
  (when snapshot
    {:dispute/question-root (:dispute/question-root snapshot)
     :dispute/evidence-set-root (:dispute/evidence-set-root snapshot)
     :authority/resolution-basis-root (:authority/resolution-basis-root snapshot)
     :authority/context-root (:authority/context-root snapshot)
     :configuration/root (:configuration/root snapshot)}))

(defn reconstruct-live-resolution-basis
  "Reconstruct the authoritative current SEW basis.

   `external-roots` may supply only commitments which are not retained by the
   SEW world. It is a temporary root source, never a yes/no admission callback.
   Question and evidence roots must denote the frozen adjudication-round inputs
   for which a ruling was issued, not arbitrary live-at-execution collections.
   Missing or malformed external roots make reconstruction fail closed."
  [world workflow-id external-roots]
  (let [snapshot-root (get-in world [:adjudication-current-snapshot-root workflow-id])
          snapshot (some #(when (= snapshot-root (:sew/adjudication-snapshot-root %)) %)
                         (get-in world [:adjudication-snapshots workflow-id] []))
        ;; A persisted adjudication snapshot is authoritative.  Compatibility
        ;; root sources may fill only when no snapshot exists; they must never
        ;; override frozen round inputs.
        external-roots (merge external-roots (snapshot-roots snapshot))
        missing (remove #(ref/valid-sha256-ref? (get external-roots %))
                        external-live-root-fields)]
    (cond
      (not (t/valid-workflow-id? world workflow-id))
      {:valid? false :errors [:invalid-workflow-id]}

      (and snapshot (not (:valid? (adjudication-snapshot/validate-adjudication-snapshot snapshot))))
      {:valid? false :errors [:invalid-authoritative-adjudication-snapshot]}

      (and snapshot
           (not= (t/dispute-level world workflow-id)
                 (:adjudication/level snapshot)))
      {:valid? false :errors [:adjudication-snapshot-level-mismatch]}

      (seq missing)
      {:valid? false :errors [:missing-external-live-root]
       :missing-roots (vec missing)}

      :else
      (let [transfer (t/get-transfer world workflow-id)
            settings (t/get-settings world workflow-id)
            snapshot (t/get-snapshot world workflow-id)
            authority-mode (if (state-machine/resolver-response-exceeded? world workflow-id)
                             :fallback
                             :primary)
            basis (build-resolution-basis
                   (merge
                    (select-keys external-roots external-live-root-fields)
                    {:workflow/id workflow-id
                     :escrow/root (live-root :escrow transfer)
                     :state-before/root
                     (live-root :state-before
                                (hc/project-world-to-structure-view world :world-structure))
                     :dispute/root
                     (live-root :dispute {:transfer transfer
                                          :level (t/dispute-level world workflow-id)
                                          :raised-at (get-in world [:dispute-timestamps workflow-id])})
                     :resolver/root
                     (live-root :resolver {:resolver (:dispute-resolver transfer)
                                           :mode (authority/resolution-mode world workflow-id)})
                     :resolver/kind (authority/resolution-mode world workflow-id)
                     :resolver/authorization-root
                     (live-root :resolver-authorization {:settings settings
                                                         :snapshot snapshot
                                                         :resolver (:dispute-resolver transfer)})
                     :resolution-module/root
                     (live-root :resolution-module (:resolution-module snapshot))
                     :resolution-module/snapshot-root (live-root :module-snapshot snapshot)
                     :resolution/authority-mode authority-mode}))]
        {:valid? true
         :basis basis
         :adjudication/round-id (:adjudication/round-id
                                 (some #(when (= snapshot-root
                                                (:sew/adjudication-snapshot-root %)) %)
                                       (get-in world [:adjudication-snapshots workflow-id] [])))}))))

(defn adjudicator-binding-root
  "Stable identity binding for the current SEW adjudicator contract.

   It commits only existing immutable resolver/module/snapshot commitments. It
   identifies declared adjudicator semantics; it neither simulates nor trusts a
   mutable adjudicator registry."
  [basis]
  (ref/sha256-ref
   (hc/domain-hash :sew-adjudicator-binding-v1
                   (hc/project-canonical-safe
                    (select-keys basis [:resolver/root
                                        :resolver/authorization-root
                                        :resolution-module/root
                                        :resolution-module/snapshot-root])))))

(defn project-adjudication-basis
  "Deterministically project a validated SEW basis into the protocol-neutral
   adjudication basis. No independently supplied projection fields are accepted."
  [basis]
  (let [validation (validate-resolution-basis basis)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot project invalid sew-resolution-basis.v1"
                      {:type :sew-resolution-basis/invalid
                       :errors (:errors validation)})))
    (adjudication/build-adjudication-basis
     {:adjudication/subject-root (:escrow/root basis)
      :adjudication/state-root (:state-before/root basis)
      :adjudication/question-root (:dispute/question-root basis)
      :adjudication/evidence-set-root (:dispute/evidence-set-root basis)
      :adjudication/authority-root (:authority/context-root basis)
      :adjudication/adjudicator-root (adjudicator-binding-root basis)
      :adjudication/policy-root (:configuration/root basis)
      :adjudication/authority-mode (:resolution/authority-mode basis)
      :adjudication/protocol-basis-root (:sew-resolution-basis/root basis)})))

(defn build-resolution-basis
  "Build a closed, canonical `sew-resolution-basis.v1` artifact.

   The caller supplies all authority and dispute bindings. Outcome fields are
   not accepted because they are intentionally outside basis identity."
  [basis]
  (let [base (assoc basis
                    :artifact/schema schema
                    :resolution/purpose purpose)
        root (ref/sha256-ref
              (hc/domain-hash domain
                              (hc/project-canonical-safe (basis-payload base))))
        artifact (assoc base root-key root)
        validation (validate-resolution-basis artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid sew-resolution-basis.v1"
                      {:type :sew-resolution-basis/invalid
                       :errors (:errors validation)})))
    artifact))
