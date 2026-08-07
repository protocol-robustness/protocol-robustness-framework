(ns resolver-sim.lab.experiments.withdrawal
  "Withdrawal under constrained liquidity experiment.

   Three holders request withdrawals from a shared pool that cannot cover every
   request. Two mechanisms are available:

     - :pro-rata — canonical pro-rata allocation
       (resolver-sim.pro-rata.allocation/allocate); the party rows are adapted
       the same way the shared-pool yield module adapts withdrawal rows
       (allocate-shared-withdrawal-rows). Findings come from the engine's own
       invariant validators.

     - :fcfs — first-come-first-served sequential fill in deterministic
       arrival order (alice, bob, carol), mirroring the batch-withdrawal
       semantics of liquid-lending withdraw-many (each row draws at most the
       pool remaining after earlier rows). The FCFS witness is bound with the
       repository's canonical hashing; its structural assertions are labelled
       :lab-consistency, NOT PRF claim results.

   Shortfall is treated as deferred (recoverable) by default: unmet amounts
   are deferred rather than haircut."
  (:require [clojure.string :as str]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.invariants :as invariants]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private party-order
  [{:party/id :alice :parameter/id :alice-requested}
   {:party/id :bob :parameter/id :bob-requested}
   {:party/id :carol :parameter/id :carol-requested}])

(defn- party-parameter-id
  [party]
  (:parameter/id party))

(defn- party-id
  [party]
  (:party/id party))

(defn- request-amount
  [parameters party]
  (long (get parameters (party-parameter-id party))))

(defn- mechanism-key
  [parameters]
  (keyword (:mechanism parameters)))

(defn- fill-pro-rata
  [parameters available]
  (let [rows (mapv (fn [party]
                     (let [pid (party-parameter-id party)]
                       {:row/id (party-id party)
                        :obligation/id :withdrawal
                        :requested (long (get parameters pid))
                        :weight (long (get parameters pid))
                        :cap (long (get parameters pid))}))
                   party-order)
        result (allocation/allocate
                {:schema-version "pro-rata-allocation-request.v1"
                 :mechanism/version 1
                 :allocation/id [:lab/withdrawal
                                 (mapv :row/id rows)]
                 :available available
                 :rows rows
                 :rounding-policy (keyword (:rounding-policy parameters))
                 :tie-break-policy :canonical-row-id
                 :redistribution-policy :unallocated})
        by-id (into {} (map (juxt :row/id identity)) (:rows result))
        violations (invariants/result-violations result)
        finding-groups
        [{:id :pro-rata/request-hash :label "Request-set binding"}
         {:id :pro-rata/allocation-hash :label "Allocation root binding"}
         {:id :pro-rata/cap-respecting :label "Cap respecting"}
         {:id :pro-rata/quota-bounded :label "Quota bounded"}
         {:id :pro-rata/residual-reason :label "Residual declared"}
         {:id :pro-rata/round-trace :label "Round trace conservation"}
         {:id :pro-rata/fractional-remainder :label "Fractional remainder total"}
         {:id :pro-rata/canonical-remainder-assignment :label "Canonical remainder assignment"}]
        findings (mapv (fn [{:keys [id label]}]
                         (let [bad (->> violations
                                        (filter #(str/starts-with? (name (:reason %)) (name id)))
                                        first)]
                           {:findings/id id
                            :findings/status (if bad :fail :pass)
                            :findings/label label
                            :findings/origin :prf
                            :findings/detail (when bad (pr-str bad))}))
                       finding-groups)]
    {:mechanism :pro-rata
     :rows (mapv (fn [party]
                   (let [pid (party-id party)
                         row (get by-id pid)]
                     {:party/id pid
                      :requested (long (:requested row))
                      :filled (long (:allocated row))
                      :deferred (long (:unmet row))
                      :haircut 0}))
                 party-order)
     :evidence
     {:roots {:allocation-hash (:allocation/hash result)
              :request-hash (:request/hash result)}
      :artifacts [{:artifact/id :pro-rata-allocation-result
                   :artifact/ref (:allocation/hash result)}]}
     :findings findings}))

(defn- fill-fcfs
  [parameters available]
  (let [remaining (atom available)
        rows (mapv (fn [party]
                     (let [requested (request-amount parameters party)
                           filled (min requested @remaining)
                           deferred (- requested filled)]
                       (swap! remaining - filled)
                       {:party/id (party-id party)
                        :requested requested
                        :filled filled
                        :deferred deferred
                        :haircut 0}))
                   party-order)
        requested-total (reduce + 0 (map :requested rows))
        filled-total (reduce + 0 (map :filled rows))
        deferred-total (reduce + 0 (map :deferred rows))
        witness {:mechanism :fcfs
                 :available available
                 :requested-total requested-total
                 :filled-total filled-total
                 :deferred-total deferred-total
                 :rows rows}
        root (hc/hash-with-intent {:hash/intent :lab-withdrawal-fcfs} witness)
        conservation-ok? (= requested-total (+ filled-total deferred-total))
        capacity-ok? (<= filled-total available)]
    {:mechanism :fcfs
     :rows rows
     :evidence
     {:roots {:withdrawal-root root}
      :artifacts [{:artifact/id :lab-withdrawal-fcfs-witness
                   :artifact/ref root}]}
     :findings
     [{:findings/id :lab/aggregate-conservation
       :findings/status (if conservation-ok? :pass :fail)
       :findings/origin :lab-consistency
       :findings/label "Aggregate conservation (lab assertion)"}
      {:findings/id :lab/capacity-bound
       :findings/status (if capacity-ok? :pass :fail)
       :findings/origin :lab-consistency
       :findings/label "Filled amount within available liquidity (lab assertion)"}]}))

(defn- classify-party
  [{:keys [requested filled deferred]}]
  (cond
    (and (zero? requested) (zero? filled)) :no-request
    (and (= requested filled) (zero? deferred)) :served
    (and (pos? filled) (pos? deferred)) :partially-served
    (zero? filled) :deferred
    :else :partially-served))

(defn- settlement-assessment
  [available total-requested filled-total]
  (if (<= total-requested available)
    {:assessment/status :fully-served
     :assessment/label "Every request was served in full."}
    {:assessment/status :shortfall
     :assessment/label (str "Demand exceeds available liquidity by "
                            (- total-requested available)
                            " USDC; " filled-total " USDC served.")}))

(defn run
  "Run the withdrawal experiment. parameters := validated registry parameters."
  [parameters]
  (let [available (long (:available-liquidity parameters))
        mechanism (mechanism-key parameters)
        {:keys [rows evidence findings mechanism]}
        (case mechanism
          :pro-rata (fill-pro-rata parameters available)
          :fcfs (fill-fcfs parameters available))
        requested-total (reduce + 0 (map :requested rows))
        filled-total (reduce + 0 (map :filled rows))
        deferred-total (reduce + 0 (map :deferred rows))
        haircut-total (reduce + 0 (map :haircut rows))
        assessment (settlement-assessment available requested-total filled-total)]
    {:outcome
     {:mechanism mechanism
      :available available
      :total-requested requested-total
      :total-filled filled-total
      :total-deferred deferred-total
      :total-haircut haircut-total
      :shortfall (max 0 (- requested-total available))
      :rows (mapv (fn [row]
                    (assoc row
                           :party/status (classify-party row)))
                  rows)
      :order (mapv :party/id rows)}
     :assessment assessment
     :findings findings
     :evidence evidence}))
