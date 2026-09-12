(ns resolver-sim.protocols.sew.terminal-state-snapshot
  "Pure, durable terminal-state projection for resolving a Sew held-custody
   effect after a terminal CAS decision. This is intentionally not a replacement
   for the historical :world-structure root: it commits only the state required
   to re-verify held custody and force-authorisation closure."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.accounting.held-ledger-index :as held-index]))

(def ^:const artifact-type :sew-terminal-state-snapshot)
(def ^:const schema-version 1)

(def ^:private state-keys
  [:held-adjustments :held-artifacts :held-ledger/index :total-held :held/positions
   :force-authorisations :force-authorisations/consumed
   :force-authorisations/consumption-records :params])

(def ^:private allowed-keys
  (into #{:artifact/type :artifact/version :snapshot/hash
          :held-adjustments/complete? :held-ledger/origin}
        state-keys))

(defn- pure-data?
  [value]
  (cond
    (or (nil? value) (boolean? value) (string? value) (keyword? value) (integer? value)) true
    (vector? value) (every? pure-data? value)
    (set? value) (every? pure-data? value)
    (map? value) (and (every? pure-data? (keys value))
                      (every? pure-data? (vals value)))
    :else false))

(defn snapshot-payload
  "The exact projection committed by a terminal-state snapshot."
  [snapshot]
  (dissoc snapshot :snapshot/hash))

(defn snapshot-hash
  [snapshot]
  (hash-ref/sha256-ref
   (hc/domain-hash :sew-terminal-state-snapshot (snapshot-payload snapshot))))

(defn build-terminal-state-snapshot
  "Project a candidate Sew world into a strict, pure-data terminal snapshot.
   Runtime values are rejected rather than normalized so the snapshot is an
   independently reproducible state surface."
  [world]
  (let [state (select-keys world state-keys)]
    (when-not (pure-data? state)
      (throw (ex-info "Sew terminal state contains non-persistable runtime data"
                      {:reason :terminal-state-runtime-value})))
    (let [snapshot (assoc state
                          :artifact/type artifact-type
                          :artifact/version schema-version
                          :held-adjustments/complete?
                          (true? (get-in world [:params :held-adjustments/complete?]))
                          :held-ledger/origin (get-in world [:params :held-ledger/origin]))]
      (assoc snapshot :snapshot/hash (snapshot-hash snapshot)))))

(defn resolve-held-effect
  "Resolve an authoritative held adjustment and its custody artifact from a
   verified terminal-state snapshot."
  [snapshot {:held-adjustment/keys [id] :keys [held-custody/artifact-hash]}]
  (let [adjustment (some #(when (= id (:held-adjustment/id %)) %) (:held-adjustments snapshot))
        artifact (get-in snapshot [:held-artifacts id])]
    {:adjustment adjustment
     :artifact artifact
     :effect-present? (some? adjustment)
     :artifact-present? (some? artifact)
     :artifact-hash-matches? (= artifact-hash (:artifact/hash artifact))}))

(defn snapshot-errors
  "Return all structural/reconciliation errors for a terminal snapshot.
   Resolution of external references remains the terminal verifier's job."
  [snapshot]
  (let [held-adjustments (:held-adjustments snapshot)
        held-artifacts (vals (:held-artifacts snapshot))
        zero-origin? (and (true? (:held-adjustments/complete? snapshot))
                          (= :zero (:held-ledger/origin snapshot))
                          (vector? held-adjustments)
                          (custody/held-history-zero-origin? held-adjustments))
        ;; Never reconstruct an incomplete/imported ledger from zero. A future
        ;; snapshot version may define an authenticated non-zero opening state.
        replay (when zero-origin?
                 (try (custody/replay-held-adjustment-state held-adjustments)
                      (catch Exception _ nil)))
        artifact-checks (when (seq held-artifacts)
                          (try
                            (custody/held-custody-closed-form-checks held-artifacts)
                            (catch Exception _ ::invalid-artifacts)))]
    (cond-> []
      (not (map? snapshot)) (conj :invalid-snapshot)
      (and (map? snapshot) (not (every? allowed-keys (keys snapshot))))
      (conj :unknown-snapshot-key)
      (not= artifact-type (:artifact/type snapshot)) (conj :invalid-artifact-type)
      (not= schema-version (:artifact/version snapshot)) (conj :unsupported-artifact-version)
      (not (pure-data? (snapshot-payload snapshot))) (conj :terminal-state-runtime-value)
      (not= (:snapshot/hash snapshot) (snapshot-hash snapshot)) (conj :snapshot-hash-mismatch)
      (not (true? (:held-adjustments/complete? snapshot)))
      (conj :incomplete-held-history)
      (not= :zero (:held-ledger/origin snapshot))
      (conj :unsupported-held-ledger-origin)
      (and (true? (:held-adjustments/complete? snapshot))
           (= :zero (:held-ledger/origin snapshot))
           (vector? held-adjustments)
           (not (custody/held-history-zero-origin? held-adjustments)))
      (conj :non-zero-held-history)
      (not (vector? held-adjustments)) (conj :invalid-held-adjustments)
      (not (map? (:held-artifacts snapshot))) (conj :invalid-held-artifacts)
      (and zero-origin? (nil? replay)) (conj :held-replay-failed)
      (and replay (not= (:total-held snapshot) (:total-held replay))) (conj :total-held-replay-mismatch)
      (and replay (not= (:held/positions snapshot) (:held/positions replay))) (conj :positions-replay-mismatch)
      (and replay (not= (:held-ledger/index snapshot) (:held-ledger/index replay))) (conj :held-index-replay-mismatch)
      (= ::invalid-artifacts artifact-checks)
      (conj :held-artifact-verification-failed)
      (and (coll? artifact-checks)
           (seq artifact-checks)
           (not (every? #(= :pass (:status %)) artifact-checks)))
      (conj :held-artifact-verification-failed)
      (not (held-index/valid-held-custody-state?
            (select-keys snapshot [:held-ledger/index :total-held :held/positions])))
      (conj :held-index-reconciliation-failed))))

(defn valid-terminal-state-snapshot?
  [snapshot]
  (empty? (snapshot-errors snapshot)))

(defn validate-terminal-state-snapshot
  [snapshot]
  (let [errors (snapshot-errors snapshot)]
    {:valid? (empty? errors) :errors errors}))
