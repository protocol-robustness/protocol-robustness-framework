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
            [resolver-sim.protocols.sew.adjudication-snapshot :as adjudication-snapshot]
            [resolver-sim.protocols.sew.adjudication-instance :as adjudication-instance]
            [resolver-sim.protocols.sew.adjudication-case :as adjudication-case]))

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
(def ^:private adjudication-instance-root-key
  (keyword "sew" "adjudication-instance/root"))
(def ^:private evidence-set-root-key
  (keyword "sew" "dispute-evidence-set/root"))

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

(defn- snapshot-chain-errors [world workflow-id]
  (let [snapshots (get-in world [:adjudication-snapshots workflow-id] [])
        roots (mapv :sew/adjudication-snapshot-root snapshots)
        current (get-in world [:adjudication-current-snapshot-root workflow-id])]
    (vec (concat
          (when-not (vector? snapshots) [:invalid-snapshot-history])
          (when (not= (count roots) (count (distinct roots))) [:duplicate-snapshot-root])
          (when (not= (count (map :adjudication/round-id snapshots))
                      (count (distinct (map :adjudication/round-id snapshots))))
            [:duplicate-snapshot-round])
          (mapcat (fn [[i s]]
                    (let [expected-parent (when (pos? i) (nth roots (dec i)))]
                      (concat
                       (when-not (:valid? (adjudication-snapshot/validate-adjudication-snapshot s))
                         [:invalid-authoritative-adjudication-snapshot])
                       (when-not (= workflow-id (:workflow/id s)) [:snapshot-workflow-mismatch])
                       (when-not (= i (:adjudication/round-id s)) [:snapshot-round-mismatch])
                       (when-not (= expected-parent (:adjudication/parent-snapshot-root s))
                         [:snapshot-predecessor-mismatch]))))
                  (map-indexed vector snapshots))
          (when (and (empty? snapshots) current) [:dangling-current-snapshot-pointer])
          (when (and (seq snapshots) (not (some #{current} roots))) [:dangling-current-snapshot-pointer])
          (when (and (seq snapshots) (not= current (last roots))) [:current-snapshot-not-history-tip])))))

(defn- current-instance-errors [world workflow-id]
  (let [root (get-in world [:adjudication-current-instance-root workflow-id])
        instances (get-in world [:adjudication-instances workflow-id] {})
        instance (get instances root)
        chain (loop [current instance seen #{} out []]
                (if-not current
                  out
                  (let [current-root (get current adjudication-instance-root-key)
                        parent (:adjudication/parent-root current)]
                    (if (or (contains? seen current-root)
                            (not (:valid? (adjudication-instance/validate-instance current)))
                            (not= workflow-id (:workflow/id current)))
                      ::invalid
                      (recur (when parent (get instances parent))
                             (conj seen current-root) (conj out current))))))]
    (cond
      (nil? root) [[:missing-authoritative-adjudication-instance] nil]
      (nil? instance) [[:dangling-current-instance-pointer] nil]
      (not (:valid? (adjudication-instance/validate-instance instance)))
      [[:invalid-authoritative-adjudication-instance] nil]
      (not= root (get instance adjudication-instance-root-key))
      [[:instance-root-mismatch] nil]
      (not= workflow-id (:workflow/id instance)) [[:instance-workflow-mismatch] nil]
      (= ::invalid chain) [[:invalid-instance-predecessor-chain] nil]
      (and (seq chain) (not= 0 (:adjudication/round-id (last chain))))
      [[:incomplete-instance-predecessor-chain] nil]
      (some (fn [[newer older]]
              (or (not= (:adjudication/parent-root newer)
                        (get older adjudication-instance-root-key))
                  (not= (dec (:adjudication/round-id newer))
                        (:adjudication/round-id older))))
            (partition 2 1 chain))
      [[:instance-predecessor-mismatch] nil]
      :else [[] instance])))

(defn- reconstruct-live-resolution-basis*
  "Reconstruct the authoritative current SEW basis.

   The default mode requires a committed snapshot and authoritative adjudication
   instance. `external-roots` may repeat snapshot commitments, but every
   overlapping value must agree. Root-only historical reconstruction is
   available only with `{:mode :legacy-non-authoritative}` and is explicitly
   marked non-authoritative.
   Question and evidence roots must denote the frozen adjudication-round inputs
   for which a ruling was issued, not arbitrary live-at-execution collections.
   Missing or malformed authoritative inputs make reconstruction fail closed."
  ([world workflow-id external-roots]
   (reconstruct-live-resolution-basis* world workflow-id external-roots {}))
  ([world workflow-id external-roots {:keys [mode]}]
   (let [snapshots (get-in world [:adjudication-snapshots workflow-id] [])
         snapshot-root (get-in world [:adjudication-current-snapshot-root workflow-id])
         root-errors (snapshot-chain-errors world workflow-id)
         snapshot (when snapshot-root
                    (first (filter #(= snapshot-root (:sew/adjudication-snapshot-root %)) snapshots)))
         legacy? (= mode :legacy-non-authoritative)
         root-conflicts (when snapshot
                          (for [k external-live-root-fields
                                :when (and (contains? (or external-roots {}) k)
                                           (not= (get external-roots k)
                                                 (get (snapshot-roots snapshot) k)))] k))
         effective-roots (if snapshot (snapshot-roots snapshot) external-roots)
         missing (remove #(ref/valid-sha256-ref? (get effective-roots %)) external-live-root-fields)
         [instance-errors instance] (if snapshot (current-instance-errors world workflow-id) [[] nil])
         round-id (:adjudication/round-id instance)
         reconstructed-case (when instance
                              (adjudication-case/reconstruct-authoritative-case world workflow-id round-id))
         published (get-in world [:adjudication-published-cases workflow-id round-id])]
    (cond
      (not (t/valid-workflow-id? world workflow-id))
      {:valid? false :errors [:invalid-workflow-id]}

      (seq root-errors) {:valid? false :errors root-errors}

      (and (nil? snapshot) (or (seq snapshots) snapshot-root))
      {:valid? false :errors [:missing-authoritative-current-snapshot]}

      (and (nil? snapshot) (not legacy?))
      {:valid? false :errors [:authoritative-snapshot-required]}

      (seq instance-errors) {:valid? false :errors instance-errors}

      (and snapshot (not= round-id (:adjudication/round-id snapshot)))
      {:valid? false :errors [:snapshot-round-instance-mismatch]}

      (and snapshot (not= (:adjudication/level snapshot) (t/dispute-level world workflow-id)))
      {:valid? false :errors [:adjudication-snapshot-level-mismatch]}

      (and snapshot (or (nil? reconstructed-case) (nil? published)))
      {:valid? false :errors [:authoritative-case-unavailable]}

      (and snapshot (not= (:case/root published) (:case/root instance)))
      {:valid? false :errors [:instance-case-mismatch]}

      (and snapshot
           (or (not= (:dispute/question-root snapshot)
                     (get-in reconstructed-case [:question :sew/dispute-question-root]))
               (not= (:dispute/evidence-set-root snapshot)
                     (get-in reconstructed-case [:evidence-set evidence-set-root-key]))))
      {:valid? false :errors [:snapshot-case-binding-mismatch]}

      (seq root-conflicts)
      {:valid? false :errors [:conflicting-supplied-root] :conflicting-roots (vec root-conflicts)}

      (seq missing)
      {:valid? false :errors [(if snapshot :missing-snapshot-root :missing-external-live-root)]
       :missing-roots (vec missing)}

      :else
      (let [transfer (t/get-transfer world workflow-id)
            settings (t/get-settings world workflow-id)
            module-snapshot (t/get-snapshot world workflow-id)
            authority-mode (if (state-machine/resolver-response-exceeded? world workflow-id)
                             :fallback
                             :primary)
            basis (build-resolution-basis
                   (merge
                    (select-keys effective-roots external-live-root-fields)
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
                                                         :snapshot module-snapshot
                                                         :resolver (:dispute-resolver transfer)})
                     :resolution-module/root
                     (live-root :resolution-module (:resolution-module module-snapshot))
                     :resolution-module/snapshot-root (live-root :module-snapshot module-snapshot)
                     :resolution/authority-mode authority-mode}))]
        {:valid? true
         :authoritative? (boolean snapshot)
         :basis basis
         :adjudication/round-id round-id})))))

(defn reconstruct-live-resolution-basis
  "Reconstruct the basis and attach a stable typed error identifier on failure.
   Historical root-only reconstruction requires `:legacy-non-authoritative`."
  ([world workflow-id external-roots]
   (reconstruct-live-resolution-basis world workflow-id external-roots {}))
  ([world workflow-id external-roots options]
   (let [result (reconstruct-live-resolution-basis* world workflow-id external-roots options)]
     (if (:valid? result)
       result
       (assoc result :error/type (keyword "sew-resolution-reconstruction"
                                          (name (or (first (:errors result)) :invalid-state))))))))

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
