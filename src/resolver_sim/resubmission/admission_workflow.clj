(ns resolver-sim.resubmission.admission-workflow
  "Narrow v1 orchestration for the fenced admission contract.

   This layer does not select winners. `reserve!` is the sole linearization
   point for exclusive attempt authority and `finalize!` is the sole canonical
   mutation point. `sign!` and `verify-signature!` run outside store mutation."
  (:require [resolver-sim.resubmission.admission :as admission]
            [resolver-sim.resubmission.admission-store :as store]))

(defn- release-reservation!
  [admission-store family-id reservation outcome reason]
  (let [released (store/abort! admission-store family-id (:reservation/id reservation)
                               (:reservation/fence reservation))]
    {:concurrency/outcome outcome :reason reason
     :reservation/id (:reservation/id reservation)
     :concurrency/fence (:reservation/fence reservation)
     :reservation/release-outcome (:concurrency/outcome released)
     :reservation/release-reason (:reason released)}))

(defn finalization-request
  [snapshot reservation candidate-root validation-root proposed-ordering-root payload receipt-root]
  {:concurrency/partition-key (:concurrency/partition-key snapshot)
   :concurrency/expected-state-version (:concurrency/expected-state-version snapshot)
   :reservation/id (:reservation/id reservation)
   :concurrency/fence (:reservation/fence reservation)
   :reservation/candidate-root candidate-root
   :reservation/validation-root validation-root
   :reservation/proposed-ordering-root proposed-ordering-root
   :signing/payload-root (:signing/payload-root payload)
   :receipt/root receipt-root
   :authorization/evidence-root receipt-root})

(defn resolve-finalization!
  "Resolve an uncertain finalization only from authoritative store state.  An
   active matching reservation may retry the exact request; no abort occurs on
   a missing/uncertain finalization response."
  [admission-store family-id request]
  (try
    (let [{:keys [reservation finalization concurrency/fence] :as resolved}
          (store/resolve-finalization! admission-store family-id (:reservation/id request))]
      (cond
        (and finalization
             (= (:finalization/request-root finalization)
                (admission/finalization-request-root request)))
        {:concurrency/outcome :finalized :finalization finalization :resolved resolved}

        (and reservation (= :active (:reservation/status reservation))
             (= (:reservation/fence reservation) (:concurrency/fence request))
             (= fence (:concurrency/fence request)))
        (store/finalize! admission-store request)

        :else
        {:concurrency/outcome :finalization-unavailable :resolved resolved}))
    (catch Throwable _
      {:concurrency/outcome :finalization-indeterminate
       :reason :resolution-indeterminate
       :finalization/request request})))

(defn attempt!
  "Run one v1 admission attempt.

   Required callbacks:
   - `sign!` receives the reservation-bound signing payload and returns a map
     containing `:receipt/root` and `:signing/payload-root`.
   - `verify-signature!` receives payload and signer result, returning truthy
     only when the receipt/signature is valid for that exact payload.

   A contention result is operational, not a semantic rejection.  Signer
   completion order cannot influence arbitration because signing begins only
   after the exclusive reservation is returned."
  [{:keys [admission-store family-id candidate-root idempotency-key
           proposed-ordering-root validation sign! verify-signature! snapshot]}]
  (let [snapshot (or snapshot (store/snapshot! admission-store family-id))
        aggregate (admission/build-validation
                   (merge validation
                          {:partition-key (:concurrency/partition-key snapshot)
                           :snapshot-root (:concurrency/snapshot-root snapshot)
                           :snapshot-version (:concurrency/expected-state-version snapshot)
                           :candidate-root candidate-root}))]
    (if-not (:validation/pass? aggregate)
      {:concurrency/outcome :validation-failed :validation aggregate}
      (let [reserved (store/reserve!
                      admission-store
                      {:concurrency/partition-key (:concurrency/partition-key snapshot)
                       :concurrency/snapshot-root (:concurrency/snapshot-root snapshot)
                       :concurrency/expected-state-version (:concurrency/expected-state-version snapshot)
                       :concurrency/idempotency-key idempotency-key
                       :reservation/candidate-root candidate-root
                       :reservation/validation-root (:validation/root aggregate)
                       :reservation/proposed-ordering-root proposed-ordering-root})]
        (if-not (= :reserved (:concurrency/outcome reserved))
          (assoc reserved :validation aggregate)
          (let [reservation (:reservation reserved)
                payload (admission/signing-payload reservation)
                signed-result (try
                                {:value (sign! payload)}
                                (catch Throwable _ {:error :signer-threw}))]
            (cond
              (:error signed-result)
              (release-reservation! admission-store family-id reservation
                                    :signing-failed (:error signed-result))

              (not (map? (:value signed-result)))
              (release-reservation! admission-store family-id reservation
                                    :signing-failed :malformed-signer-result)

              :else
              (let [signed (:value signed-result)]
                (cond
                  (not (and (:receipt/root signed)
                            (= (:signing/payload-root payload)
                               (:signing/payload-root signed))))
                  (release-reservation! admission-store family-id reservation
                                        :signature-invalid :signing-payload-mismatch)

                  :else
                  (let [verified (try
                                   {:value (verify-signature! payload signed)}
                                   (catch Throwable _ {:error :verifier-threw}))]
                    (cond
                      (:error verified)
                      (release-reservation! admission-store family-id reservation
                                            :workflow-failed (:error verified))

                      (not (boolean? (:value verified)))
                      (release-reservation! admission-store family-id reservation
                                            :workflow-failed :malformed-verifier-result)

                      (false? (:value verified))
                      (release-reservation! admission-store family-id reservation
                                            :signature-invalid :signature-verification-failed)

                      :else
                      (let [request (finalization-request snapshot reservation candidate-root
                                                          (:validation/root aggregate)
                                                          proposed-ordering-root payload
                                                          (:receipt/root signed))
                            finalized (try
                                        {:value (store/finalize! admission-store request)}
                                        (catch Throwable _ {:error :finalization-indeterminate}))]
                        (if (:error finalized)
                          {:concurrency/outcome :finalization-indeterminate
                           :reason (:error finalized)
                           :finalization/request request
                           :validation aggregate}
                          (assoc (:value finalized) :validation aggregate))))))))))))))
