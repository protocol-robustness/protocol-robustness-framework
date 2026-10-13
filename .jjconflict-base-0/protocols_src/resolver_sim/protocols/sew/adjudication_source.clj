(ns resolver-sim.protocols.sew.adjudication-source
  "Authoritative, append-only source contexts for SEW adjudication epochs.

   An envelope is assembled only from subsystem-owned world commitments observed
   under one state fence. It never accepts substitute roots from an action caller
   or integration callback. Lifecycle code will later publish an envelope and its
   successor snapshot in one fenced transition."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.time.context :as time-ctx]))

(def ^:const schema "sew-adjudication-source-envelope.v1")
(def ^:const domain :sew-adjudication-source-envelope-v1)
(def ^:private root-key :sew/adjudication-source-root)
(def ^:private component-fields
  #{:dispute/question-root
    :dispute/evidence-set-root
    :authority/resolution-basis-root
    :authority/context-root
    :configuration/root})
(def ^:private required-fields
  (into #{:artifact/schema
          :workflow/id
          :adjudication/round-id
          :adjudication/level
          :source/parent-root
          :source/state-fence-root
          :source/created-at}
        component-fields))

(def component-paths
  "Canonical SEW state locations(ns resolver-sim.protocols.sew.adjudication-source) owned by the respective source subsystems.
   The envelope assembler reads these locations under the supplied state fence;
   it never accepts replacement roots as arguments."
  {:dispute/question-root [:dispute-question-roots]
   :dispute/evidence-set-root [:dispute-evidence-set-roots]
   :authority/resolution-basis-root [:governed-resolution-basis-roots]
   :authority/context-root [:governed-authority-context-roots]
   :configuration/root [:configuration-roots]})

(defn source-payload [envelope] (dissoc envelope root-key))

(defn source-errors [envelope]
  (if-not (map? envelope)
    [:invalid-adjudication-source-envelope]
    (let [have (set (keys envelope))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))
          parent (:source/parent-root envelope)]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-adjudication-source-field)
        (not= schema (:artifact/schema envelope)) (conj :invalid-schema)
        (not (and (integer? (:workflow/id envelope))
                  (not (neg? (:workflow/id envelope)))))
        (conj :invalid-workflow-id)
        (not (and (integer? (:adjudication/round-id envelope))
                  (not (neg? (:adjudication/round-id envelope)))))
        (conj :invalid-round-id)
        (not (and (integer? (:adjudication/level envelope))
                  (not (neg? (:adjudication/level envelope)))))
        (conj :invalid-level)
        (not (or (nil? parent) (ref/valid-sha256-ref? parent)))
        (conj :invalid-source-parent-root)
        (not (and (integer? (:source/created-at envelope))
                  (not (neg? (:source/created-at envelope)))))
        (conj :invalid-source-created-at)
        (not (every? #(ref/valid-sha256-ref? (get envelope %))
                     (conj component-fields :source/state-fence-root)))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get envelope root-key)))
        (conj :invalid-adjudication-source-root)
        (and (empty? missing) (empty? extra)
             (ref/valid-sha256-ref? (get envelope root-key))
             (not= (get envelope root-key)
                   (ref/sha256-ref
                    (hc/domain-hash domain
                                    (hc/project-canonical-safe
                                     (source-payload envelope))))))
        (conj :adjudication-source-root-mismatch)))))

(defn validate-adjudication-source [envelope]
  (let [errors (source-errors envelope)]
    {:valid? (empty? errors) :errors errors}))

(defn build-adjudication-source [envelope]
  (let [base (assoc envelope :artifact/schema schema)
        artifact (assoc base root-key
                        (ref/sha256-ref
                         (hc/domain-hash domain
                                         (hc/project-canonical-safe
                                          (source-payload base)))))
        validation (validate-adjudication-source artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid sew-adjudication-source-envelope.v1"
                      {:type :sew-adjudication-source/invalid
                       :errors (:errors validation)})))
    artifact))

(defn current-adjudication-source
  "Resolve and validate the current authoritative envelope. A dangling or
   malformed current pointer is terminal; callers must not fall back to another
   source of adjudicative truth."
  [world workflow-id]
  (let [root (get-in world [:adjudication-current-source-root workflow-id])
        envelope (get-in world [:adjudication-source-envelopes workflow-id root])]
    (cond
      (nil? root) {:present? false}
      (nil? envelope) {:present? true :valid? false :errors [:missing-current-adjudication-source]}
      :else (assoc (validate-adjudication-source envelope)
                   :present? true
                   :source envelope))))

(defn publish-current-adjudication-source!
  "Build and append the next source envelope from canonical world state.

   `expected-state-fence` must be the authoritative predecessor root. Every
   component is read from `component-paths` under that same world observation.
   No source root is accepted from the caller. The function is pure despite the
   historical `!` naming convention used for world transitions."
  [world workflow-id expected-state-fence]
  (let [current-result (current-adjudication-source world workflow-id)
        current (:source current-result)
        components (into {}
                         (map (fn [[field path]]
                                [field (get-in world (conj path workflow-id))]))
                         component-paths)
        missing (->> component-fields
                     (remove #(ref/valid-sha256-ref? (get components %)))
                     vec)]
    (cond
      (and (:present? current-result) (not (:valid? current-result)))
      {:ok false :error :invalid-current-adjudication-source
       :errors (:errors current-result) :world world}

      (not (ref/valid-sha256-ref? expected-state-fence))
      {:ok false :error :invalid-adjudication-source-fence :world world}

      (seq missing)
      {:ok false :error :missing-authoritative-adjudication-component
       :missing-components missing :world world}

      :else
      (let [round-id (if current (inc (:adjudication/round-id current)) 0)
            envelope (build-adjudication-source
                      (merge components
                             {:workflow/id workflow-id
                              :adjudication/round-id round-id
                              :adjudication/level (get-in world [:dispute-levels workflow-id] 0)
                              :source/parent-root (:sew/adjudication-source-root current)
                              :source/state-fence-root expected-state-fence
                              :source/created-at (time-ctx/block-ts world)}))
            root (:sew/adjudication-source-root envelope)
            existing (get-in world [:adjudication-source-envelopes workflow-id root])]
        (if existing
          {:ok true :world world :source existing :idempotent? true}
          {:ok true
           :world (-> world
                      (assoc-in [:adjudication-source-envelopes workflow-id root] envelope)
                      (assoc-in [:adjudication-current-source-root workflow-id] root))
           :source envelope})))))
