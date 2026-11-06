(ns resolver-sim.economics.revenue-report
  "Opt-in, root-bound aggregation of protocol fee and payout observations.

   This report keeps modeled fee estimates, accrued fee deltas, withdrawals,
   and independently verified direct payouts as separate measures. It does not
   infer fiat value or treat validation severity as an economic rating."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.economics.with-bounty.payout-evidence :as payout]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.io.atomic-file :as atomic-file])
  (:import [java.io PushbackReader StringReader]
           [java.time Instant]
           [java.util UUID]))

(def schema :prf/protocol-revenue-report.v1)
(def event-kinds #{:modeled-fee :accrued-fee :fee-withdrawal :verified-payout})
(def amount-fields [:revenue/modeled-fees :revenue/accrued-fees
                    :revenue/fee-withdrawals :revenue/verified-payouts])
(def ^:private payout-binding-fields
  #{:payout/sequence :payout/payable-id :payout/predecessor-root
    :payout/successor-root :payout/backing-root :payout/beneficiary
    :payout/token :payout/amount :payout/operation-root})
(def ^:private report-fields
  #{:report/schema :report/id :report/generated-at :report/root :run/id
    :source/revision :period :revenue/totals :revenue/events :storage})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :report/schema schema))))

(defn- valid-root? [value]
  (hash-ref/valid-sha256-ref? value))

(defn report-root [report]
  (hash-ref/sha256-ref
   (canonical/domain-hash "prf-protocol-revenue-report-v1"
                          (dissoc report :report/root))))

(defn payout-trust-policy-root [trust-policy]
  "Return the content root a publisher can use to identify a payout trust policy."
  (hash-ref/sha256-ref
   (canonical/domain-hash "prf-revenue-payout-trust-policy-v1" trust-policy)))

(defn- token-id? [token]
  (or (and (keyword? token) (some? (namespace token)))
      (and (string? token) (not (str/blank? token)))))

(defn- source-event-problems [event]
  (cond-> []
    (not (and (map? event) (string? (:event/id event))
              (not (str/blank? (:event/id event)))))
    (conj :invalid-event-id)
    (not (event-kinds (:event/kind event))) (conj :invalid-event-kind)
    (not (token-id? (:token/id event))) (conj :invalid-token-id)
    (not (and (integer? (:event/time event)) (not (neg? (:event/time event)))))
    (conj :invalid-event-time)
    (not (and (integer? (:event/amount event)) (not (neg? (:event/amount event)))))
    (conj :invalid-event-amount)
    (not (valid-root? (:evidence/root event))) (conj :invalid-evidence-root)))

(defn- event-in-period? [event period]
  (and (integer? (:period/start period))
       (integer? (:period/end period))
       (integer? (:event/time event))
       (<= (:period/start period) (:event/time event) (:period/end period))))

(defn- normalize-fee-event [event period]
  (let [problems (source-event-problems event)]
    (when (or (seq problems) (= :verified-payout (:event/kind event)))
      (fail! "Invalid protocol revenue source event"
             {:reason :invalid-revenue-event :event/id (:event/id event)
              :problems (cond-> problems
                          (= :verified-payout (:event/kind event))
                          (conj :payout-requires-verification))}))
    (when-not (<= (:period/start period) (:event/time event) (:period/end period))
      (fail! "Revenue event falls outside the declared period"
             {:reason :revenue-event-outside-period :event/id (:event/id event)}))
    (select-keys event [:event/id :event/kind :event/time :token/id
                        :event/amount :evidence/root])))

(defn- normalize-payout-event [{id :event/id time :event/time receipt :receipt
                                trust-policy :trust-policy expected :expected
                                policy-root :trust-policy/root}
                               period]
  (when-not (and (string? id) (not (str/blank? id)))
    (fail! "Payout observation requires a nonblank event id"
           {:reason :invalid-payout-event-id}))
  (when-not (and (integer? time) (<= (:period/start period) time (:period/end period)))
    (fail! "Payout observation falls outside the declared period"
           {:reason :payout-event-outside-period :event/id id}))
  (when-not (and (map? trust-policy)
                 (valid-root? policy-root)
                 (= policy-root (payout-trust-policy-root trust-policy)))
    (fail! "Payout observations require a content-rooted trust policy"
           {:reason :invalid-payout-trust-policy :event/id id}))
  (when-not (and (map? receipt) (map? expected)
                 (every? #(contains? expected %) payout-binding-fields))
    (fail! "Payout observations require a receipt and complete expected bindings"
           {:reason :invalid-payout-binding-context :event/id id}))
  (let [verified (payout/verify-payout receipt trust-policy expected)]
    (when-not (:valid? verified)
      (fail! "Payout receipt did not verify"
             {:reason :invalid-payout-receipt :event/id id :verification verified}))
    (let [token (:payout/token receipt)
          amount (:payout/amount receipt)
          root (:event-root verified)]
      (when-not (and (token-id? token) (integer? amount) (pos? amount) (valid-root? root))
        (fail! "Verified payout receipt has invalid report fields"
               {:reason :invalid-verified-payout :event/id id}))
      {:event/id id :event/kind :verified-payout :event/time time
       :token/id token :event/amount amount :evidence/root root
       :payout/receipt receipt :payout/expected expected
       :payout/trust-policy trust-policy :payout/trust-policy-root policy-root})))

(defn- payout-event-valid? [event]
  (let [trust-policy (:payout/trust-policy event)
        expected (:payout/expected event)
        receipt (:payout/receipt event)
        verified (when (and (map? trust-policy) (map? expected) (map? receipt))
                   (payout/verify-payout receipt trust-policy expected))]
    (and (:valid? verified)
         (valid-root? (:payout/trust-policy-root event))
         (= (:payout/trust-policy-root event) (payout-trust-policy-root trust-policy))
         (= (:event-root verified) (:evidence/root event))
         (= (:payout/token receipt) (:token/id event))
         (= (:payout/amount receipt) (:event/amount event)))))

(defn- totals-by-token [events]
  (->> events
       (group-by :token/id)
       (map (fn [[token token-events]]
              (assoc (reduce (fn [totals event]
                               (let [field (case (:event/kind event)
                                             :modeled-fee :revenue/modeled-fees
                                             :accrued-fee :revenue/accrued-fees
                                             :fee-withdrawal :revenue/fee-withdrawals
                                             :verified-payout :revenue/verified-payouts)]
                                 (update totals field + (:event/amount event))))
                             (zipmap amount-fields (repeat 0))
                             token-events)
                     :token/id token)))
       (sort-by (comp str :token/id))
       vec))

(defn build-report
  "Build a protocol revenue report from explicit source observations.

   Required options: :run/id, :source/revision (full commit id), :period with
   nonnegative inclusive :period/start and :period/end integer timestamps,
   :period/unit, and :fee-events. Optional :payout-events carry a receipt,
   trust policy, its :trust-policy/root, expected binding map, event id, and
   observed time. Fee events use :event/kind :modeled-fee, :accrued-fee, or
   :fee-withdrawal and carry a canonical :evidence/root. No currency conversion
   is performed."
  [{:keys [fee-events payout-events generated-at] :as opts}]
  (let [run-id (:run/id opts)
        revision (:source/revision opts)
        period (:period opts)]
    (when-not (and (string? run-id) (not (str/blank? run-id)))
      (fail! "Revenue report requires a run id" {:reason :missing-run-id}))
    (when-not (and (string? revision)
                   (re-matches #"(?:[0-9a-f]{40}|[0-9a-f]{64})" revision))
      (fail! "Revenue report requires a full source commit id"
             {:reason :invalid-source-revision}))
    (when-not (and (map? period)
                   (integer? (:period/start period)) (not (neg? (:period/start period)))
                   (integer? (:period/end period))
                   (<= (:period/start period) (:period/end period))
                   (string? (:period/unit period)) (not (str/blank? (:period/unit period))))
      (fail! "Revenue report requires an explicit inclusive period and unit"
             {:reason :invalid-period}))
    (when-not (vector? fee-events)
      (fail! "Revenue report fee events must be a vector" {:reason :invalid-fee-events}))
    (when-not (vector? (or payout-events []))
      (fail! "Revenue report payout events must be a vector" {:reason :invalid-payout-events}))
    (when (and generated-at
               (not (and (string? generated-at) (not (str/blank? generated-at)))))
      (fail! "Revenue report generated-at must be a nonblank string"
             {:reason :invalid-generated-at}))
    (let [events (vec (concat (map #(normalize-fee-event % period) fee-events)
                              (map #(normalize-payout-event % period) (or payout-events []))))
          ids (map :event/id events)]
      (when-not (= (count ids) (count (set ids)))
        (fail! "Revenue source event ids must be unique" {:reason :duplicate-event-id}))
      (let [payout-roots (->> events
                              (filter #(= :verified-payout (:event/kind %)))
                              (map :evidence/root))]
        (when-not (= (count payout-roots) (count (set payout-roots)))
          (fail! "A verified payout receipt may be counted only once"
                 {:reason :duplicate-payout-receipt})))
      (let [base {:report/schema schema
                  :report/id (str (UUID/randomUUID))
                  :report/generated-at (or generated-at (str (Instant/now)))
                  :run/id run-id
                  :source/revision revision
                  :period period
                  :revenue/totals (totals-by-token events)
                  :revenue/events (vec (sort-by :event/id events))
                  :storage {:format :edn :encryption :none}}]
        (assoc base :report/root (report-root base))))))

(defn validate-report [report]
  (let [events (:revenue/events report)
        ids (when (vector? events) (map :event/id events))
        period (:period report)
        events-valid? (and (vector? events)
                           (every? #(and (map? %)
                                         (empty? (source-event-problems %))
                                         (map? period)
                                         (event-in-period? % period)
                                         (or (not= :verified-payout (:event/kind %))
                                             (payout-event-valid? %)))
                                   events))
        errors (cond-> []
                 (not (map? report)) (conj :not-a-map)
                 (not= schema (:report/schema report)) (conj :unsupported-schema)
                 (and (map? report) (not= report-fields (set (keys report))))
                 (conj :invalid-report-fields)
                 (not (and (string? (:report/id report)) (not (str/blank? (:report/id report)))))
                 (conj :invalid-report-id)
                 (not (and (string? (:report/generated-at report))
                           (not (str/blank? (:report/generated-at report)))))
                 (conj :invalid-generated-at)
                 (not (and (string? (:run/id report)) (not (str/blank? (:run/id report)))))
                 (conj :invalid-run-id)
                 (not (and (string? (:source/revision report))
                           (re-matches #"(?:[0-9a-f]{40}|[0-9a-f]{64})" (:source/revision report))))
                 (conj :invalid-source-revision)
                 (not (and (map? (:period report))
                           (integer? (get-in report [:period :period/start]))
                           (not (neg? (get-in report [:period :period/start] -1)))
                           (integer? (get-in report [:period :period/end]))
                           (<= (get-in report [:period :period/start] 1)
                               (get-in report [:period :period/end] 0))
                           (string? (get-in report [:period :period/unit]))
                           (not (str/blank? (get-in report [:period :period/unit] "")))))
                 (conj :invalid-period)
                 (not (and (vector? events) (every? map? events))) (conj :invalid-events)
                 (and (vector? events) (not events-valid?)) (conj :invalid-events)
                 (and (vector? events) (not= (count ids) (count (set ids))))
                 (conj :duplicate-event-id)
                 (and events-valid?
                      (not= (count (map :evidence/root
                                        (filter #(= :verified-payout (:event/kind %)) events)))
                            (count (set (map :evidence/root
                                             (filter #(= :verified-payout (:event/kind %)) events))))))
                 (conj :duplicate-payout-receipt)
                 (and events-valid?
                      (not= (:revenue/totals report) (totals-by-token events)))
                 (conj :totals-mismatch)
                 (not= {:format :edn :encryption :none} (:storage report))
                 (conj :unsupported-storage-profile)
                 (not (valid-root? (:report/root report))) (conj :invalid-report-root)
                 (not= (:report/root report)
                       (try (report-root report) (catch Exception _ nil)))
                 (conj :report-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))

(defn write-report! [path report]
  (let [{:keys [valid? errors]} (validate-report report)]
    (when-not valid?
      (fail! "Refusing to persist invalid protocol revenue report"
             {:reason :invalid-report :errors errors}))
    (str (atomic-file/write-text! path (str (pr-str report) "\n") {:replace? false}))))

(defn read-report [path]
  (let [report (try
                 (with-open [reader (PushbackReader. (StringReader. (slurp (io/file (str path)))))]
                   (let [eof (Object.)
                         value (edn/read {:eof eof} reader)
                         trailing (edn/read {:eof eof} reader)]
                     (when (identical? eof value)
                       (fail! "Revenue report file is empty" {:reason :invalid-report-edn}))
                     (when-not (identical? eof trailing)
                       (fail! "Revenue report file contains trailing EDN values"
                              {:reason :trailing-report-data}))
                     value))
                 (catch Exception error
                   (if (= :trailing-report-data (:reason (ex-data error)))
                     (throw error)
                     (fail! "Revenue report file is not valid EDN"
                            {:reason :invalid-report-edn :cause (.getMessage error)}))))
        validation (validate-report report)]
    (when-not (:valid? validation)
      (fail! "Persisted revenue report failed validation"
             {:reason :invalid-report :errors (:errors validation)}))
    report))
