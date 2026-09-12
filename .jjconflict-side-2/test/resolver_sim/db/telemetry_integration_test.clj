(ns resolver-sim.db.telemetry-integration-test
  "End-to-end integration tests: run trials → write to live XTDB → query back.

   Requires XTDB running on localhost:5432.
   Run with: clojure -M:test -e \"(require '...)(clojure.test/run-tests '...)\"

   The test batch is written under a unique batch-id and cleaned up via
   resolver-sim.db.store/truncate! at the end of the suite so repeated runs
   don't accumulate rows."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.db.store :as ss]
            [resolver-sim.protocols.sew           :as sew]
            [resolver-sim.protocols.sew.runner    :as runner]
            [resolver-sim.db.sew                  :as sew-db]
            [resolver-sim.db.telemetry :as tel])
  (:import [java.util Date UUID]))

;; ---------------------------------------------------------------------------
;; Fixture — shared datasource + per-suite cleanup
;; ---------------------------------------------------------------------------

(def ^:dynamic *ds* nil)
(def ^:dynamic *batch-id* nil)

(defn xtdb-fixture [f]
  (let [ds       (xtdb/->datasource)
        batch-id (str "integ-test-" (UUID/randomUUID))]
    (binding [*ds* ds *batch-id* batch-id]
      (try
        (f)
        (finally
          ;; Remove all Sew test rows so re-runs start clean
          ;; (resolver-sim.db.store/truncate! clears sim_* tables used here)
          (ss/truncate! ds))))))

(use-fixtures :once xtdb-fixture)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- run-n-trials
  "Run n trials with a fixed rng-fn and record each one to XTDB.
   Returns the vector of outcome records."
  [n strategy]
  (let [rng-fn (fn [] 0.9)]              ; deterministic – always "high"
    (vec
     (for [i (range n)]
       (let [trial-id (str *batch-id* "-" i)
             params   {:block-time                       (+ 1000 (* i 3600))
                       :escrow-size                      100000
                       :strategy                         strategy
                       :resolver-fee-bps                 50
                       :appeal-bond-bps                  200
                       :appeal-probability-if-correct    0.0
                       :appeal-probability-if-wrong      0.0
                       :slashing-detection-probability   0.0}
             result   (runner/run-trial rng-fn params)]
         (tel/record-trial! *ds* sew/protocol *batch-id* trial-id params result))))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-write-and-read-batch
  (testing "Writing 5 honest trials and reading them back"
    (let [outcomes (run-n-trials 5 :honest)
          stored   (sew-db/sew-trial-outcomes *ds* {:batch-id *batch-id*})]
      (is (= 5 (count stored)) "5 rows written")
      (is (every? #(= :honest (:trial/strategy %)) stored) "all strategy=honest")
      (is (every? #(= :released (:trial/outcome %)) stored)
          "honest resolver releases funds → :released final state")
      (is (every? #(pos? (:trial/profit-honest %)) stored) "honest fee always positive")
      (is (every? :trial/invariants-ok? stored) "all invariants satisfied"))))

(deftest test-batch-roundtrip-strategy-variety
  (testing "Mixed batch: honest + malicious; counts and strategies preserved"
    (let [batch2 (str *batch-id* "-mix")
          _h (dotimes [i 3]
               (let [tid    (str batch2 "-h-" i)
                     params {:block-time 2000 :escrow-size 50000
                             :strategy :honest :appeal-bond-bps 0
                             :appeal-probability-if-correct 0.0
                             :appeal-probability-if-wrong   0.0
                             :slashing-detection-probability 0.0}
                     res    (runner/run-trial (fn [] 0.9) params)]
                 (tel/record-trial! *ds* sew/protocol batch2 tid params res)))
          _m (dotimes [i 2]
               (let [tid    (str batch2 "-m-" i)
                     params {:block-time 3000 :escrow-size 50000
                             :strategy :malicious :appeal-bond-bps 500
                             :appeal-probability-if-correct 0.0
                             :appeal-probability-if-wrong   0.0
                             :slashing-detection-probability 0.0}
                     res    (runner/run-trial (fn [] 0.9) params)]
                 (tel/record-trial! *ds* sew/protocol batch2 tid params res)))
          all    (sew-db/sew-trial-outcomes *ds* {:batch-id batch2})
          honest (sew-db/sew-trial-outcomes *ds* {:batch-id batch2 :strategy :honest})]
      (is (= 5 (count all)) "5 total rows for mixed batch")
      (is (= 3 (count honest)) "3 honest rows found by strategy filter"))))

(deftest test-escrow-events-written
  (testing "Escrow event timeline is written for each trial"
    (let [stored-outcomes (sew-db/sew-trial-outcomes *ds* {:batch-id *batch-id*})
          first-trial-id  (:trial/id (first stored-outcomes))
          events          (sew-db/sew-escrow-events-for-trial *ds* first-trial-id)]
      (is (= 3 (count events)) "3 events per trial: created / dispute-raised / finalized")
      (is (= :sew/escrow-created   (:event/type (nth events 0))) "first event is creation")
      (is (= :sew/dispute-raised   (:event/type (nth events 1))) "second event is dispute")
      (is (= :sew/escrow-finalized (:event/type (nth events 2))) "third event is finalization")
      (is (apply < (map :event/block-time events)) "events are ordered by block-time"))))

(deftest test-bitemporal-query
  (testing "FOR VALID_TIME AS OF returns rows visible at a given block timestamp"
    ;; The honest batch was written with block-times 1000, 4600, 8200, 11800, 15400
    ;; Each row's _valid_from = Date(block-time * 1000 ms)
    ;; Querying AS OF epoch should see nothing for our batch (valid_from > epoch).
    ;; Querying far in the future should see all our rows.
    (let [our-kw     (keyword *batch-id*)   ; row->trial-outcome does (keyword batch_id)
          before-all (sew-db/sew-trial-outcomes-at *ds* (Date. 0))
          after-all  (sew-db/sew-trial-outcomes-at *ds* (Date. (* 999999 1000)))]
      (let [our-before (filter #(= our-kw (:trial/batch-id %)) before-all)]
        (is (empty? our-before) "no rows visible before their valid-from time"))
      (let [our-after (filter #(= our-kw (:trial/batch-id %)) after-all)]
        (is (= 5 (count our-after)) "all 5 rows visible far after their valid-from")))))

(deftest test-bitemporal-summary-ordering
  (testing "batch-summary-at respects t0 < t1 < t2 snapshot growth"
    (let [t0 (Date. 0)
          t1 (Date. (* 1000 1000))
          t2 (Date. (* 1000 5000))
          s0 (tel/batch-summary-at *ds* sew/protocol *batch-id* t0)
          s1 (tel/batch-summary-at *ds* sew/protocol *batch-id* t1)
          s2 (tel/batch-summary-at *ds* sew/protocol *batch-id* t2)]
      (is (= 0 (:n s0 0)) "t0 before all valid-times => 0 rows")
      (is (= 1 (:n s1 0)) "t1 should include first trial snapshot")
      (is (= 2 (:n s2 0)) "t2 should include first two trial snapshots")
      (is (= :as-of (:temporal-query-mode s2)))
      (is (= :valid-time (get-in s2 [:temporal-confidence :time-basis]))))))

(deftest test-bitemporal-entity-events-query
  (testing "Entity events FOR VALID_TIME AS OF support boundary snapshots (t-1 / t / t+1)"
    (let [stored-outcomes (sew-db/sew-trial-outcomes *ds* {:batch-id *batch-id*})
          first-trial-id  (:trial/id (first stored-outcomes))
          all-events      (sew-db/sew-escrow-events-for-trial *ds* first-trial-id)
          first-event-ts  (:event/block-time (first all-events))
          t-1             (Date. (* 1000 (dec first-event-ts)))
          t               (Date. (* 1000 first-event-ts))
          t+1             (Date. (* 1000 (inc first-event-ts)))
          asof-t-1        (sew-db/sew-escrow-events-for-trial-at *ds* first-trial-id t-1)
          asof-t          (sew-db/sew-escrow-events-for-trial-at *ds* first-trial-id t)
          asof-t+1        (sew-db/sew-escrow-events-for-trial-at *ds* first-trial-id t+1)]
      (is (empty? asof-t-1) "no events visible before first event valid-time")
      (is (= 1 (count asof-t)) "exact boundary includes the first event")
      (is (<= 1 (count asof-t+1)) "t+1 should include at least the first event")
      (is (= :sew/escrow-created (get-in asof-t [0 :event/type]))
          "first visible event should be escrow creation"))))

(deftest test-summarise-batch
  (testing "batch-summary returns aggregate statistics matching raw data"
    (let [summary (tel/batch-summary *ds* sew/protocol *batch-id*)]
      (is (= 5 (:n summary)) "5 trials in batch")
      (is (= {:released 5} (:by-outcome summary)) "all released by honest resolver")
      (is (= 5 (get-in summary [:by-strategy :honest :n])) "5 honest trials")
      (is (zero? (get-in summary [:by-strategy :honest :invariant-failures]))
          "no invariant failures")
      (is (number? (get-in summary [:profit-honest :mean])) "mean profit-honest is numeric")
      (is (pos? (get-in summary [:profit-honest :mean])) "mean profit-honest > 0"))))

(deftest test-params-edn-roundtrip
  (testing "params map survives EDN serialization through XTDB and back"
    (let [stored   (sew-db/sew-trial-outcomes *ds* {:batch-id *batch-id* :limit 1})
          row      (first stored)
          params   (:trial/params row)]
      (is (map? params) "params deserialized as map")
      (is (= 50 (:resolver-fee-bps params)) "resolver-fee-bps preserved")
      (is (= :honest (:strategy params)) "strategy keyword preserved through EDN"))))
