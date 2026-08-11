(ns resolver-sim.resubmission.admission-store
  "Adapters for `resolver-sim.resubmission.admission`.

   The in-memory adapter is an executable reference model, not durable
   authority: it provides per-family JVM-local CAS and never claims restart or
   multi-process fence guarantees."
  (:require [resolver-sim.resubmission.admission :as admission])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.function Function]))

(defprotocol ResubmissionAdmissionStore
  (snapshot! [store family-id])
  (reserve! [store request])
  (finalize! [store request])
  (abort! [store family-id reservation-id fence])
  (expire! [store family-id reservation-id fence])
  (compact! [store family-id])
  (resolve-finalization! [store family-id reservation-id])
  (concurrency-capabilities [store]))

(defn- family-atom
  [^ConcurrentHashMap partitions family-id]
  (.computeIfAbsent partitions family-id
                    (reify Function
                      (apply [_ id] (atom (admission/empty-partition id))))))

(defn- instant [clock]
  (let [value (clock)]
    (cond
      (instance? java.time.Instant value) value
      (integer? value) (java.time.Instant/ofEpochMilli value)
      :else (throw (ex-info "admission store clock must return Instant or epoch milliseconds"
                            {:reason :invalid-store-clock :value value})))))

(defn- apply-transition!
  "CAS loop intentionally invokes only pure admission transitions. `now` is
   read once by the store before this operation and reused throughout."
  [state-atom now transition & args]
  (loop []
    (let [raw @state-atom
          before (admission/terminalize-expired (admission/normalize-state raw) now)
          outcome (apply transition before args)
          after (:state outcome)]
      (if (= raw after)
        (dissoc outcome :state)
        (if (compare-and-set! state-atom raw after)
          (dissoc outcome :state)
          (recur))))))

(defn- read-state!
  [state-atom now]
  (loop []
    (let [raw @state-atom
          state (admission/terminalize-expired (admission/normalize-state raw) now)]
      (if (= raw state)
        state
        (if (compare-and-set! state-atom raw state) state (recur))))))

(deftype InMemoryAdmissionStore [^ConcurrentHashMap partitions clock]
  ResubmissionAdmissionStore
  (snapshot! [_ family-id]
    (admission/snapshot (read-state! (family-atom partitions family-id) (instant clock))))
  (reserve! [_ request]
    (let [key (:concurrency/partition-key request)]
      (if-not (admission/valid-partition-key? key)
        {:concurrency/outcome :rejected :reason :partition-mismatch}
        (let [now (instant clock)]
          (apply-transition! (family-atom partitions (second key)) now
                             admission/reserve-transition
                             (assoc request :reservation/issued-at (str now)
                                            :reservation/expires-at
                                            (str (.plusSeconds now admission/reservation-lease-seconds))))))))
  (finalize! [_ request]
    (let [key (:concurrency/partition-key request)]
      (if-not (admission/valid-partition-key? key)
        {:concurrency/outcome :rejected :reason :partition-mismatch}
        (apply-transition! (family-atom partitions (second key)) (instant clock)
                           admission/finalize-transition request))))
  (abort! [_ family-id reservation-id fence]
    (apply-transition! (family-atom partitions family-id) (instant clock)
                       admission/withdraw-transition reservation-id fence :aborted))
  (expire! [_ family-id reservation-id fence]
    (let [now (instant clock)]
      (apply-transition! (family-atom partitions family-id) now
                         admission/expire-transition reservation-id fence now)))
  (compact! [_ family-id]
    (apply-transition! (family-atom partitions family-id) (instant clock)
                       admission/compact-transition))
  (resolve-finalization! [_ family-id reservation-id]
    (let [state (read-state! (family-atom partitions family-id) (instant clock))
          reservation (get-in state [:admission/reservations reservation-id])
          finalization (get-in state [:admission/finalizations reservation-id])]
      {:concurrency/partition-key (:concurrency/partition-key state)
       :concurrency/fence (:concurrency/fence state)
       :family/version (:family/version state)
       :reservation reservation
       :finalization finalization}))
  (concurrency-capabilities [_]
    {:concurrency/adapter :in-memory-reference
     :concurrency/per-family-cas? true
     :concurrency/cross-family-parallel? true
     :concurrency/durable? false
     :concurrency/multi-process-linearizable? false
     :concurrency/restart-safe-fences? false}))

(defn in-memory-store
  "Create the reference adapter. Optional `:clock` is store-owned test
   infrastructure returning an Instant or epoch milliseconds; protocol callers
   cannot provide per-operation authority time."
  ([] (in-memory-store {}))
  ([{:keys [clock] :or {clock #(java.time.Instant/now)}}]
   (InMemoryAdmissionStore. (ConcurrentHashMap.) clock)))
