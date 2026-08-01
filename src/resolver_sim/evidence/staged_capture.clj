(ns resolver-sim.evidence.staged-capture
  "Attempt-local event evidence preparation.

   Staged records retain their finalized evidence content byte-for-byte. They
   never receive global chain sequence/predecessor fields, so publishing an
   attempt later cannot rebase or alter hashes sealed before terminal CAS."
  (:require [resolver-sim.evidence.capture :as cap]
            [resolver-sim.io.event-evidence :as event-evidence]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema-version 1)
(def ^:const artifact-type :staged-event-evidence)
(def ^:private allowed-keys
  #{:artifact/type :artifact/version :capture/attempt-id :capture/base-chain-head
    :staged-evidence/records :staged-evidence/count :staged-evidence/root
    :staged-evidence/head})

(defn- root-payload [capture]
  (dissoc capture :staged-evidence/root))

(defn staged-root
  [capture]
  (hash-ref/sha256-ref
   (hc/domain-hash :staged-event-evidence (root-payload capture))))

(defn begin-capture-attempt
  "Create an isolated mutable capture session. `:base-chain-head` is recorded
   for audit context only; it is not used to mutate the global chain."
  [{:keys [attempt-id base-chain-head]}]
  (when-not (some? attempt-id)
    (throw (ex-info "Staged capture requires an attempt id" {:reason :missing-attempt-id})))
  (atom {:capture/attempt-id attempt-id
         :capture/base-chain-head base-chain-head
         :staged-evidence/records []
         :sealed? false
         :aborted? false}))

(defn- finalized-evidence? [evidence]
  (and (map? evidence) (some? (:evidence/hash evidence))))

(defn stage-event!
  "Add finalized evidence to an open attempt. The content hash must already be
   finalized; staging never modifies the evidence map itself."
  [session evidence]
  (when-not (finalized-evidence? evidence)
    (throw (ex-info "Staged event evidence must be finalized" {:reason :invalid-evidence})))
  (let [result (swap! session
                      (fn [state]
                        (when (:sealed? state)
                          (throw (ex-info "Cannot append to sealed staged capture"
                                          {:reason :capture-already-sealed})))
                        (when (:aborted? state)
                          (throw (ex-info "Cannot append to aborted staged capture"
                                          {:reason :capture-aborted})))
                        (update state :staged-evidence/records conj evidence)))]
    evidence))

(defn staged-capture-fn
  "Return a variadic replacement for `capture/*capture-event-evidence!*`.
   Positional protocol capture calls are prepared using the same public pure
   event-evidence builder; pre-built evidence maps are accepted only when their
   existing content hash is finalized."
  [session]
  (fn [& args]
    (let [evidence (if (and (= 1 (count args)) (map? (first args)))
                     (let [candidate (first args)]
                       (if (:evidence/hash candidate)
                         candidate
                         (cap/finalize-evidence candidate)))
                     (let [[reason pre post inputs calc ctx-or-opts] args]
                       (event-evidence/prepare-event-evidence reason pre post inputs calc ctx-or-opts)))]
      (stage-event! session evidence))))

(defn seal-capture!
  "Freeze an attempt and return an immutable content-addressed staged artifact.
   Sealing is idempotent; subsequent stage attempts fail."
  [session]
  (let [state @session]
    (if-let [sealed (:sealed-artifact state)]
      sealed
      (let [records (:staged-evidence/records state)
            base {:artifact/type artifact-type
                  :artifact/version schema-version
                  :capture/attempt-id (:capture/attempt-id state)
                  :capture/base-chain-head (:capture/base-chain-head state)
                  :staged-evidence/records records
                  :staged-evidence/count (count records)
                  :staged-evidence/head (some-> records last :evidence/hash)}
            sealed (assoc base :staged-evidence/root (staged-root base))]
        (swap! session assoc :sealed? true :sealed-artifact sealed)
        sealed))))

(defn abort-capture!
  "Discard an unsealed session. Already-persisted immutable objects are not
   deleted; they are harmless orphans until a terminal record makes them reachable."
  [session]
  (swap! session assoc :aborted? true :staged-evidence/records [])
  nil)

(defn valid-staged-capture?
  [capture]
  (and (map? capture)
       (every? allowed-keys (keys capture))
       (= artifact-type (:artifact/type capture))
       (= schema-version (:artifact/version capture))
       (some? (:capture/attempt-id capture))
       (vector? (:staged-evidence/records capture))
       (= (:staged-evidence/count capture) (count (:staged-evidence/records capture)))
       (= (:staged-evidence/head capture)
          (some-> (:staged-evidence/records capture) last :evidence/hash))
       (every? finalized-evidence? (:staged-evidence/records capture))
       (= (:staged-evidence/root capture) (staged-root capture))))

(defn prepare-capture!
  "Seal, verify, and persist a staged artifact in an unlinked store."
  [session store put-if-absent!]
  (let [capture (seal-capture! session)]
    (when-not (valid-staged-capture? capture)
      (throw (ex-info "Sealed staged capture is invalid" {:reason :invalid-staged-capture})))
    (put-if-absent! store {:hash-reference (:staged-evidence/root capture)
                           :artifact capture
                           :verify valid-staged-capture?})))
