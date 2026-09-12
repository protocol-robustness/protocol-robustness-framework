(ns resolver-sim.protocols.sew.adjudication-snapshot
  "Immutable, append-only adjudication-round snapshots for SEW.

   A snapshot is one coherently observed epistemic object: the frozen question,
   admitted evidence, governed authority, and configuration for one dispute
   round. Callers never provide constituent roots to the constructor."
  (:require [clojure.set :as set]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.time.context :as time-ctx]))

(def ^:const schema "sew/adjudication-snapshot.v1")
(def ^:const domain :sew-adjudication-snapshot-v1)
(def ^:private root-key :sew/adjudication-snapshot-root)
(def ^:private source-fields
  #{:dispute/question-root :dispute/evidence-set-root
    :authority/resolution-basis-root :authority/context-root :configuration/root})
(def ^:private required-fields
  (into #{:artifact/schema :workflow/id :adjudication/round-id :adjudication/level
          :adjudication/parent-snapshot-root :created-from-state-root :created-at}
        source-fields))

(defn snapshot-payload [snapshot] (dissoc snapshot root-key))

(defn snapshot-errors [snapshot]
  (if-not (map? snapshot)
    [:invalid-adjudication-snapshot]
    (let [have (set (keys snapshot))
          missing (set/difference required-fields have)
          extra (set/difference have (conj required-fields root-key))
          parent (:adjudication/parent-snapshot-root snapshot)]
      (cond-> []
        (seq missing) (conj :missing-required-field)
        (seq extra) (conj :unknown-adjudication-snapshot-field)
        (not= schema (:artifact/schema snapshot)) (conj :invalid-schema)
        (not (and (integer? (:workflow/id snapshot)) (not (neg? (:workflow/id snapshot)))))
        (conj :invalid-workflow-id)
        (not (and (integer? (:adjudication/round-id snapshot))
                  (not (neg? (:adjudication/round-id snapshot)))) )
        (conj :invalid-round-id)
        (not (and (integer? (:adjudication/level snapshot))
                  (not (neg? (:adjudication/level snapshot)))) )
        (conj :invalid-level)
        (not (or (nil? parent) (ref/valid-sha256-ref? parent)))
        (conj :invalid-parent-snapshot-root)
        (not (and (integer? (:created-at snapshot)) (not (neg? (:created-at snapshot)))))
        (conj :invalid-created-at)
        (not (every? #(ref/valid-sha256-ref? (get snapshot %))
                     (conj source-fields :created-from-state-root)))
        (conj :invalid-committed-root)
        (not (ref/valid-sha256-ref? (get snapshot root-key)))
        (conj :invalid-adjudication-snapshot-root)
        (and (empty? missing) (empty? extra)
             (ref/valid-sha256-ref? (get snapshot root-key))
             (not= (get snapshot root-key)
                   (ref/sha256-ref (hc/domain-hash domain
                                                   (hc/project-canonical-safe
                                                    (snapshot-payload snapshot))))))
        (conj :adjudication-snapshot-root-mismatch)))))

(defn validate-adjudication-snapshot [snapshot]
  (let [errors (snapshot-errors snapshot)] {:valid? (empty? errors) :errors errors}))

(defn build-adjudication-snapshot [snapshot]
  (let [base (assoc snapshot :artifact/schema schema)
        artifact (assoc base root-key
                        (ref/sha256-ref (hc/domain-hash domain
                                                        (hc/project-canonical-safe
                                                         (snapshot-payload base)))))
        validation (validate-adjudication-snapshot artifact)]
    (when-not (:valid? validation)
      (throw (ex-info "Cannot build invalid sew/adjudication-snapshot.v1"
                      {:type :sew-adjudication-snapshot/invalid
                       :errors (:errors validation)})))
    artifact))

(defn freeze-adjudication!
  "Append one immutable snapshot from a coherently fenced authoritative source.

   `source` must be produced by the authority/store transaction which checked
   `expected-state-root`; it supplies the five frozen roots, the same predecessor
   root, and optional canonical `:created-at`. It is intentionally not accepted
   as loose caller arguments."
  [world workflow-id expected-state-root source]
  (let [prior (get-in world [:adjudication-snapshots workflow-id] [])
        current-root (get-in world [:adjudication-current-snapshot-root workflow-id])
        current (some #(when (= current-root (:sew/adjudication-snapshot-root %)) %) prior)
        round-id (if current (inc (:adjudication/round-id current)) (count prior))
        parent (:sew/adjudication-snapshot-root current)]
    (cond
      (not= expected-state-root (:created-from-state-root source))
      {:ok false :error :adjudication-source-fence-mismatch :world world}

      (not (every? #(contains? source %) source-fields))
      {:ok false :error :incomplete-adjudication-source :world world}

      :else
      (let [snapshot (build-adjudication-snapshot
                      (merge (select-keys source source-fields)
                             {:workflow/id workflow-id
                              :adjudication/round-id round-id
                              :adjudication/level (get-in world [:dispute-levels workflow-id] 0)
                              :adjudication/parent-snapshot-root parent
                              :created-from-state-root expected-state-root
                              :created-at (or (:created-at source)
                                              (time-ctx/block-ts world))}))
                existing (some #(when (= round-id (:adjudication/round-id %)) %) prior)]
        (cond
          (and existing (= (:sew/adjudication-snapshot-root existing)
                           (:sew/adjudication-snapshot-root snapshot)))
          {:ok true :world world :snapshot existing :idempotent? true}

          existing
          {:ok false :error :adjudication-round-already-frozen :world world}

          :else
          {:ok true
           :world (-> world
                       (update-in [:adjudication-snapshots workflow-id] (fnil conj []) snapshot)
                       (assoc-in [:adjudication-current-snapshot-root workflow-id]
                                 (:sew/adjudication-snapshot-root snapshot)))
           :snapshot snapshot})))))
