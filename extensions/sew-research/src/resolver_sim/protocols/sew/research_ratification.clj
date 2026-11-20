(ns resolver-sim.protocols.sew.research-ratification
  "SEW adapter that binds researcher ratification to one frozen dispute basis.

   Research ratification is evidence about a basis. Only the existing
   authenticated ruling/finality path can authorize settlement."
  (:require [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.protocols.sew.adjudication-snapshot :as snapshot]
            [resolver-sim.protocols.sew.resolution-basis :as basis]
            [resolver-sim.protocols.sew.resolution :as resolution]
            [resolver-sim.research.ratification :as ratification]))

(def target-domain :sew/resolution-basis)
(def consumption-schema :prf/sew-research-ratification-consumption.v1)

(defn- sha256? [value]
  (ref/valid-sha256-ref? value))

(defn- round-root [workflow-id round-id snapshot-root predecessor-root]
  (ref/sha256-ref
   (canonical/domain-hash :sew-research-ratification-round-v1
                         {:workflow/id workflow-id
                          :adjudication/round-id round-id
                          :snapshot/root snapshot-root
                          :snapshot/predecessor-root predecessor-root})))

(defn build-target
  "Build a researcher target from a valid SEW basis and its exact current
   adjudication snapshot. The basis's canonical identity is never rewritten."
  [sew-basis adjudication-snapshot policy-root]
  (let [basis-check (basis/validate-resolution-basis sew-basis)
        snapshot-check (snapshot/validate-adjudication-snapshot adjudication-snapshot)
        workflow-id (:workflow/id sew-basis)
        round-id (:adjudication/round-id adjudication-snapshot)
        snapshot-root (:sew/adjudication-snapshot-root adjudication-snapshot)
        predecessor-root (:adjudication/parent-snapshot-root adjudication-snapshot)
        binding-errors (cond-> []
                         (not (:valid? basis-check)) (conj :invalid-sew-basis)
                         (not (:valid? snapshot-check)) (conj :invalid-adjudication-snapshot)
                         (not= workflow-id (:workflow/id adjudication-snapshot))
                         (conj :snapshot-workflow-mismatch)
                         (not= (:dispute/question-root sew-basis)
                               (:dispute/question-root adjudication-snapshot))
                         (conj :snapshot-question-mismatch)
                         (not= (:dispute/evidence-set-root sew-basis)
                               (:dispute/evidence-set-root adjudication-snapshot))
                         (conj :snapshot-evidence-mismatch)
                         (not (sha256? policy-root)) (conj :invalid-policy-root))]
    (when (seq binding-errors)
      (throw (ex-info "SEW researcher target does not match frozen dispute inputs"
                      {:errors binding-errors})))
    (let [base (ratification/build-target
                {:domain target-domain
                 :subject-root (:escrow/root sew-basis)
                 :evidence-root (:dispute/evidence-set-root sew-basis)
                 :round-root (round-root workflow-id round-id snapshot-root predecessor-root)
                 :policy-root policy-root
                 :schema "sew-resolution-basis.v1"
                 :predecessor-root predecessor-root})
          target (merge base
                        {:sew/workflow-id workflow-id
                         :sew/adjudication-round round-id
                         :sew/basis-root (:sew-resolution-basis/root sew-basis)
                         :sew/question-root (:dispute/question-root sew-basis)
                         :sew/evidence-root (:dispute/evidence-set-root sew-basis)
                         :sew/adjudication-snapshot-root snapshot-root
                         :sew/snapshot-predecessor-root predecessor-root
                         :sew/module-snapshot-root (:resolution-module/snapshot-root sew-basis)})]
      (assoc target :target/root (ratification/target-root target)))))

(defn verify-ratification
  "Recompute a ratification and require exact match to the supplied SEW basis,
   authoritative round, snapshot chain, policy, and signed contributions."
  [{:keys [sew-basis adjudication-snapshot policy contributions key-bindings
           ratification-result]}]
  (try
    (let [target (build-target sew-basis adjudication-snapshot (:policy/root policy))
          reconstructed (ratification/reconstruct-ratification
                         {:target target :policy policy :contributions contributions
                          :key-bindings key-bindings})
          errors (cond-> []
                   (not= reconstructed ratification-result)
                   (conj :ratification-result-mismatch)
                   (not (every? #(= (:target/root target)
                                   (:contribution/target-root %)) contributions))
                   (conj :contribution-target-mismatch))]
      {:valid? (empty? errors) :errors errors
       :target target :ratification reconstructed})
    (catch clojure.lang.ExceptionInfo error
      {:valid? false :errors (or (:errors (ex-data error)) [:invalid-sew-ratification])})))

(defn consumption-root [consumption]
  (ref/sha256-ref
   (canonical/domain-hash :sew-research-ratification-consumption-v1
                         (dissoc consumption :consumption/root))))

(defn validate-consumption [consumption]
  (let [errors (cond-> []
                 (not= consumption-schema (:artifact/schema consumption))
                 (conj :unsupported-consumption-schema)
                 (not (and (integer? (:workflow/id consumption))
                           (not (neg? (:workflow/id consumption)))))
                 (conj :invalid-workflow-id)
                 (not (and (integer? (:workflow/round consumption))
                           (not (neg? (:workflow/round consumption)))))
                 (conj :invalid-workflow-round)
                 (not (every? sha256? (map consumption
                                           [:fence/root :target/root :basis/root
                                            :policy/root :ratification/root
                                            :snapshot/root :state-before/root
                                            :state-after/root :ruling/root])))
                 (conj :invalid-consumption-root-reference)
                 (not (or (nil? (:snapshot/predecessor-root consumption))
                          (sha256? (:snapshot/predecessor-root consumption))))
                 (conj :invalid-snapshot-predecessor-root)
                 (not= (:consumption/root consumption) (consumption-root consumption))
                 (conj :consumption-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn execute-resolution-with-ratification
  "Verify and consume one ratification under the SAME authoritative state
   fence as an authenticated ruling transition.

   `state-fence` atomically acquires the workflow's expected state root and
   returns its fenced world plus the current ratification/policy roots and a
   fence root. The caller must retain that fence until it commits the returned
   world. Missing fence bindings fail closed. This model adapter does not claim
   EVM execution or settlement authority from researcher agreement."
  [world workflow-id caller sew-basis dispute-ruling finality external-root-resolver
   ruling-verifier resolution-module-fn state-fence policy contributions
   ratification-result key-bindings]
  (let [expected-state-root (:state-before/root sew-basis)
        fenced (when state-fence
                 (try (state-fence world workflow-id expected-state-root)
                      (catch Exception _ nil)))]
    (cond
      (nil? state-fence)
      {:ok false :error :missing-resolution-state-fence :world world}

      (not (and (map? fenced) (true? (:fenced? fenced)) (map? (:world fenced))
                (sha256? (:fence/root fenced))))
      {:ok false :error :resolution-state-fence-rejected :world world}

      :else
      (let [fenced-world (:world fenced)
            current-snapshot-root
            (get-in fenced-world [:adjudication-current-snapshot-root workflow-id])
            current-snapshot
            (some #(when (= current-snapshot-root (:sew/adjudication-snapshot-root %)) %)
                  (get-in fenced-world [:adjudication-snapshots workflow-id] []))
            reconstructed-basis
            (basis/reconstruct-live-resolution-basis
             fenced-world workflow-id
             (when external-root-resolver
               (try (external-root-resolver fenced-world workflow-id)
                    (catch Exception _ nil))))
            binding (verify-ratification
                     {:sew-basis sew-basis
                      :adjudication-snapshot current-snapshot
                      :policy policy :contributions contributions
                      :key-bindings key-bindings
                      :ratification-result ratification-result})]
        (cond
          (not (:valid? reconstructed-basis))
          {:ok false :error :live-resolution-basis-unavailable
           :errors (:errors reconstructed-basis) :world world}

          (not= (:sew-resolution-basis/root sew-basis)
                (get-in reconstructed-basis [:basis :sew-resolution-basis/root]))
          {:ok false :error :resolution-basis-not-current :world world}

          (not= (:adjudication/round-id reconstructed-basis)
                (:adjudication/round-id current-snapshot))
          {:ok false :error :adjudication-round-not-current :world world}

          (not (:valid? binding))
          {:ok false :error :invalid-sew-research-ratification
           :errors (:errors binding) :world world}

          (not= (:ratification/root ratification-result)
                (:research-ratification/current-root fenced))
          {:ok false :error :ratification-not-current :world world}

          (not= (:policy/root policy)
                (:research-ratification/policy-root fenced))
          {:ok false :error :ratification-policy-not-current :world world}

          (not (contains? #{:ratified :ratified-with-dissent}
                           (:ratification/status ratification-result)))
          {:ok false :error :research-ratification-not-accepted :world world}

          (not= current-snapshot-root
                (:sew/adjudication-snapshot-root current-snapshot))
          {:ok false :error :adjudication-snapshot-not-current :world world}

          :else
          (let [result (resolution/execute-resolution-with-adjudicated-ruling
                        fenced-world workflow-id caller sew-basis dispute-ruling finality
                        external-root-resolver ruling-verifier resolution-module-fn)]
            (if-not (:ok result)
              result
              (let [state-after (:world result)
                    consumption-base
                    {:artifact/schema consumption-schema
                     :workflow/id workflow-id
                     :workflow/round (:adjudication/round-id current-snapshot)
                     :fence/root (:fence/root fenced)
                     :target/root (get-in binding [:target :target/root])
                     :basis/root (:sew-resolution-basis/root sew-basis)
                     :policy/root (:policy/root policy)
                     :ratification/root (:ratification/root ratification-result)
                     :snapshot/root current-snapshot-root
                     :snapshot/predecessor-root
                     (:adjudication/parent-snapshot-root current-snapshot)
                     :state-before/root expected-state-root
                     :state-after/root
                     (ref/sha256-ref
                      (canonical/domain-hash :sew-resolution-live-commitment-v1
                                            (canonical/project-canonical-safe
                                             {:commitment/kind :state-after
                                              :commitment/value
                                              (canonical/project-world-to-structure-view
                                               state-after :world-structure)})))
                     :ruling/root (:ruling/root dispute-ruling)}
                    consumption (assoc consumption-base :consumption/root
                                        (consumption-root consumption-base))]
                (assoc result :research-ratification/consumption consumption)))))))))
