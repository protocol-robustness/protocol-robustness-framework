(ns resolver-sim.research.distributed-failure
  "Canonical non-result receipts for distributed research attempts.

   The requested `:distribution-dependency-failure` category means exactly:
   a selected distributed task attempt cannot obtain one or more declared
   dependencies under its frozen profile and execution state. It does not mean
   task failure, incorrect output, speculative misprediction, or verification
   failure."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const schema "distributed-execution-failure-receipt.v1")
(def causes #{:distribution-dependency-failure
              :remote-worker-unreachable
              :transfer-timeout
              :distribution-preflight-mismatch})

(defn receipt-root [receipt]
  (hash-ref/sha256-ref
   (hc/domain-hash :distributed-execution-failure-receipt-v1
                   (dissoc receipt :distributed-execution-failure-receipt/root))))

(defn valid-receipt? [receipt]
  (and (map? receipt)
       (= schema (:artifact/schema receipt))
       (= :failed-non-result (:execution/outcome receipt))
       (contains? causes (:failure/cause receipt))
       (= (:distributed-execution-failure-receipt/root receipt)
          (try (receipt-root receipt) (catch Exception _ nil)))))

(defn build-receipt [facts]
  (when-not (contains? causes (:failure/cause facts))
    (throw (ex-info "Unsupported distributed research failure cause"
                    {:failure/cause (:failure/cause facts)})))
  (let [required [:distributed-experiment-profile/root :workload/root
                  :distributed-scheduler-policy/root :execution/attempt-id
                  :failure/cause]
        missing (remove #(contains? facts %) required)]
    (when (seq missing)
      (throw (ex-info "Distributed failure receipt lacks provenance"
                      {:missing-fields (vec missing)})))
    (let [body (assoc facts :artifact/schema schema
                      :execution/outcome :failed-non-result)
          rooted (assoc body :distributed-execution-failure-receipt/root
                        (receipt-root body))]
      rooted)))

(defrecord FailureStore [state])

(defn new-store [] (->FailureStore (atom {})))

(defn put-receipt! [store receipt]
  (let [root (:distributed-execution-failure-receipt/root receipt)]
    (when-not (valid-receipt? receipt)
      (throw (ex-info "Invalid distributed failure receipt root" {})))
    (swap! (:state store) assoc root receipt)
    root))

(defn receipts [store]
  (mapv val (sort-by key @(:state store))))

(defn receipt-by-root [store root]
  (get @(:state store) root))
