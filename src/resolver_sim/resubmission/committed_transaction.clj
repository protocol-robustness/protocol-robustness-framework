(ns resolver-sim.resubmission.committed-transaction
  "Store-owned replay record for one committed resubmission transaction.

   The transaction ordering hash remains the transaction's lookup identity. This
   record retains only(ns resolver-sim.resubmission.committed-transaction) the irreducible bodies needed to independently replay the
   committed transition: the exact pre-state, closed command, and ordering.
   It deliberately has no separate root or duplicate state-after/effects body."
  (:require [clojure.set :as set]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.transaction.ordering :as ordering]))

(def ^:const schema "resubmission-committed-transaction.v1")

(def ^:private fields
  #{:transaction-record/schema
    :transaction-record/state-before
    :transaction-record/command
    :transaction-record/ordering})

(defn build-record
  "Construct the minimal replay record retained atomically by the store."
  [state-before command transaction-ordering]
  {:transaction-record/schema schema
   :transaction-record/state-before state-before
   :transaction-record/command command
   :transaction-record/ordering transaction-ordering})

(defn ordering-hash
  "The existing transaction identity used to resolve this record."
  [record]
  (get-in record [:transaction-record/ordering :transaction-ordering/hash]))

(defn validate-record
  "Validate that a record replays to the exact transaction ordering it retains.
   Returns {:valid? boolean :errors [...]} and never treats retained derived
   facts as independently authoritative."
  [record]
  (let [errors (atom [])
        error! #(swap! errors conj %)
        have (if (map? record) (set (keys record)) #{})
        state-before (:transaction-record/state-before record)
        command (:transaction-record/command record)
        transaction-ordering (:transaction-record/ordering record)]
    (when-not (map? record)
      (error! :record-not-map))
    (when (map? record)
      (when-not (= schema (:transaction-record/schema record))
        (error! :unsupported-record-schema))
      (when-let [extra (seq (set/difference have fields))]
        (error! [:unknown-record-keys (vec (sort-by pr-str extra))]))
      (when-let [missing (seq (set/difference fields have))]
        (error! [:missing-record-keys (vec (sort-by pr-str missing))]))
      (when-not (map? state-before) (error! :state-before-not-map))
      (when-not (and (map? command)
                     (contains? command :transaction/action)
                     (contains? command :transaction/input)
                     (map? (:transaction/input command)))
        (error! :command-not-closed-transition-map))
      (when-not (map? transaction-ordering) (error! :ordering-not-map)))
    (when (and (map? transaction-ordering)
               (not (:valid? (ordering/verify-ordering transaction-ordering))))
      (error! :ordering-invalid))
    (when (and (map? state-before) (map? transaction-ordering)
               (not= (transition/state-root state-before)
                     (:transaction/state-before-root transaction-ordering)))
      (error! :state-before-root-mismatch))
    (when (and (map? command) (map? (:transaction/input command))
               (map? transaction-ordering)
               (ordering/v2? transaction-ordering))
      (try
        (when-not (= (transition/command-input-root (:transaction/action command)
                                                    (:transaction/input command))
                     (:transaction/input-root transaction-ordering))
          (error! :input-root-mismatch))
        (catch Exception _
          (error! :command-input-invalid))))
    (when (and (empty? @errors) (map? state-before) (map? command))
      (let [result (transition/apply-action state-before command)]
        (when-not (= :committed (:status result))
          (error! :replay-not-committed))
        (when (= :committed (:status result))
          (let [ordering-input (:ordering-input result)]
            (when-not (= (transition/state-root (:state result))
                         (:transaction/state-after-root transaction-ordering))
              (error! :state-after-root-mismatch))
            (when-not (= (transition/effects-root (:effects result))
                         (:transaction/effects-root transaction-ordering))
              (error! :effects-root-mismatch))
            (when-not (= (:transaction/expected ordering-input)
                         (:transaction/expected transaction-ordering))
              (error! :expected-mismatch))
            (when-not (= (:transaction/observed ordering-input)
                         (:transaction/observed transaction-ordering))
              (error! :observed-mismatch))
            (when-not (= (:transaction/action ordering-input)
                         (:transaction/action transaction-ordering))
              (error! :action-mismatch))))))
    {:valid? (empty? @errors)
     :errors (vec @errors)}))
