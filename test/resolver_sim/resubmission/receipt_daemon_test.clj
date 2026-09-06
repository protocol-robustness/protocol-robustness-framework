(ns resolver-sim.resubmission.receipt-daemon-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.resubmission.receipt :as receipt]
            [resolver-sim.resubmission.receipt-daemon :as daemon]
            [resolver-sim.resubmission.receipt-test]
            [resolver-sim.resubmission.receipt-worker :as worker]
            [resolver-sim.resubmission.store :as store]
            [resolver-sim.resubmission.transition :as transition]
            [resolver-sim.support.ed25519 :as ed]
            [resolver-sim.transaction.protocol :as protocol])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- entries [ids]
  (mapv #(hash-map :receipt-obligation {:receipt-obligation/id %}) ids))

(defn- await! [^CountDownLatch latch]
  (.await latch 5 TimeUnit/SECONDS))

(defn- committed-fixture
  ([] (committed-fixture (ed/keypair :receipt-daemon-integration)))
  ([key]
   (let [family "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
         candidate (receipt/sign-receipt
                    (assoc (#'resolver-sim.resubmission.receipt-test/v1-candidate)
                           :attempt-receipt/chain {:admission-status :admitted
                                                   :family-id family :sequence 1
                                                   :parent-receipt-hash nil})
                    (:private-key key))
         s (store/new-resubmission-store family nil (:public-hex key))
         command {:transaction/action :prf.resubmission/admit-child
                  :transaction/input {:parent-receipt-hash nil
                                      :candidate-attempt-receipt candidate
                                      :candidate-attempt-receipt-id (:attempt-receipt/id candidate)
                                      :idempotency-key "daemon-idempotency"
                                      :content-key "daemon-content"
                                      :sequence 1}}
         result (protocol/transact! s nil nil #(transition/apply-action % command))]
     (assert (= :committed (:status result)))
     {:store s :key key :ordering (:transaction-ordering result)
      :id (get-in (first (store/pending-receipt-obligations s))
                  [:receipt-obligation :receipt-obligation/id])})))

(deftest once-is-bounded-and-sanitized
  (let [calls (atom [])]
    (with-redefs [store/pending-receipt-obligations (fn [_] (entries ["a" "b" "c"]))
                  worker/reconstruct (fn [s id key]
                                       (swap! calls conj [s id key])
                                       {:status :issued :receipt "SECRET"})]
      (let [result (daemon/run-once! :store :key {:batch-size 2})]
        (is (= [[:store "a" :key] [:store "b" :key]] @calls))
        (is (= {:attempted 2 :counts {:success 2} :next-after-id "b"
                :outcomes [{:status :issued :classification :success}
                           {:status :issued :classification :success}]} result))))))

(deftest all-statuses-have-safe-operational-classification
  (let [statuses [:issued :idempotent :unavailable :invalid :invalid-obligation
                  :invalid-state :not-found :receipt-obligation/conflict "SECRET" nil]
        results (atom statuses)]
    (with-redefs [store/pending-receipt-obligations (fn [_] (entries (mapv str (range 10))))
                  worker/reconstruct (fn [& _]
                                       (let [status (first @results)]
                                         (swap! results rest)
                                         {:status status :errors ["SECRET"]}))]
      (let [summary (daemon/run-once! nil "SECRET")]
        (is (= {:success 2 :retryable 1 :semantic 7} (:counts summary)))
        (is (not (.contains (pr-str summary) "SECRET")))
        (is (= [:unknown :unknown] (mapv :status (take-last 2 (:outcomes summary)))))))))

(deftest fair-cursor-survives-failure-and-removal
  (let [pending (atom ["a" "b" "c" "d"])
        calls (atom [])]
    (with-redefs [store/pending-receipt-obligations (fn [_] (entries @pending))
                  worker/reconstruct (fn [_ id _]
                                       (swap! calls conj id)
                                       (if (= id "a")
                                         (throw (ex-info "SECRET" {:key "SECRET"}))
                                         (do (swap! pending #(vec (remove #{id} %)))
                                             {:status :issued})))]
      (let [one (daemon/run-once! nil nil {:batch-size 2})
            two (daemon/run-once! nil nil {:batch-size 2 :after-id (:next-after-id one)})
            three (daemon/run-once! nil nil {:batch-size 2 :after-id (:next-after-id two)})]
        (is (= ["a" "b" "c" "d" "a"] @calls))
        (is (= {:retryable 1} (:counts three)))
        (is (= ["a"] @pending))))))

(deftest startup-discovers-preexisting-work-without-signal
  (let [called (CountDownLatch. 1)]
    (with-redefs [store/pending-receipt-obligations (fn [_] (entries ["old"]))
                  worker/reconstruct (fn [& _] (.countDown called) {:status :issued})]
      (let [h (daemon/start! nil nil {:poll-interval-ms 60000})]
        (try
          (is (await! called))
          (is (= :stopped (daemon/stop! h)))
          (is (= 1 (:attempted @(:summary h))))
          (is (= :stopped (daemon/stop! h)))
          (finally (daemon/stop! h)))))))

(deftest repeated-exceptions-do-not-kill-service
  (let [calls (atom 0)
        retried (CountDownLatch. 1)]
    (with-redefs [store/pending-receipt-obligations (fn [_] (entries ["a"]))
                  worker/reconstruct (fn [& _]
                                       (when (>= (swap! calls inc) 3) (.countDown retried))
                                       (throw (Exception. "SECRET")))]
      (let [h (daemon/start! nil nil {:poll-interval-ms 5})]
        (try
          (is (await! retried))
          (is (= :stopped (daemon/stop! h)))
          (is (= {:retryable 1} (:counts @(:summary h))))
          (is (not (.contains (pr-str @(:summary h)) "SECRET")))
          (finally (daemon/stop! h)))))))

(deftest discovery-exceptions-are-retried
  (let [scans (atom 0)
        called (CountDownLatch. 1)]
    (with-redefs [store/pending-receipt-obligations
                  (fn [_] (if (= 1 (swap! scans inc))
                            (throw (Exception. "SECRET")) (entries ["a"])))
                  worker/reconstruct (fn [& _] (.countDown called) {:status :issued})]
      (let [h (daemon/start! nil nil {:poll-interval-ms 5})]
        (try (is (await! called))
             (finally (daemon/stop! h))))))
  (with-redefs [store/pending-receipt-obligations (fn [_] (throw (Exception. "SECRET")))]
    (is (= :retryable (:scan-error (daemon/run-once! nil nil))))))

(deftest bounded-stop-does-not-interrupt-inflight-or-start-more-work
  (let [entered (CountDownLatch. 1)
        release (CountDownLatch. 1)
        calls (atom [])
        interrupted (atom false)
        pending (entries ["a" "b"])]
    (with-redefs [store/pending-receipt-obligations (fn [_] pending)
                  worker/reconstruct (fn [_ id _]
                                       (swap! calls conj id)
                                       (.countDown entered)
                                       (try (.await release)
                                            (catch InterruptedException _ (reset! interrupted true)))
                                       {:status :unavailable})]
      (let [h (daemon/start! nil nil {:stop-timeout-ms 10})]
        (try
          (is (await! entered))
          (is (= :stopping (daemon/stop! h)))
          (is (false? @interrupted))
          (is (= pending (store/pending-receipt-obligations nil)))
          (.countDown release)
          (is (= :stopped (daemon/stop! h 5000)))
          (is (= ["a"] @calls))
          (finally (.countDown release) (daemon/stop! h 5000)))))))

(deftest interrupted-worker-stops-scan
  (with-redefs [store/pending-receipt-obligations (fn [_] (entries ["a" "b"]))
                worker/reconstruct (fn [& _] (throw (InterruptedException.)))]
    (let [summary (daemon/run-once! nil nil)]
      (is (= 1 (:attempted summary)))
      (is (= {:shutdown 1} (:counts summary))))))

(deftest settings-must-be-positive-and-bounded
  (doseq [value [0 -1 nil 1.5 (inc (bigint Long/MAX_VALUE))]]
    (is (thrown? IllegalArgumentException (daemon/run-once! nil nil {:batch-size value})))
    (doseq [setting [:batch-size :poll-interval-ms :stop-timeout-ms]]
      (is (thrown? IllegalArgumentException (daemon/start! nil nil {setting value}))))))

(deftest actual-worker-issues-and-second-scan-is-empty
  (let [{:keys [store key id ordering]} (committed-fixture)
        before @(.state-atom store)
        result (daemon/run-once! store (:private-key key))
        entry (store/resolve-receipt-obligation store id)
        signed (:receipt-obligation/issued-receipt entry)]
    (is (= {:success 1} (:counts result)))
    (is (= :issued (:receipt-obligation/status entry)))
    (is (:valid? (receipt/verify-receipt-signature-dispatch signed (:public-hex key))))
    (is (= (:transaction-ordering/hash ordering)
           (get-in signed [:attempt-receipt/chain :transaction-ordering-hash])))
    (is (= (dissoc before :receipt-obligations)
           (dissoc @(.state-atom store) :receipt-obligations)))
    (is (= 0 (:attempted (daemon/run-once! store (:private-key key)))))))

(deftest scheduling-settings-do-not-change-receipt-identity
  (let [a (committed-fixture)
        b (committed-fixture (:key a))
        h (daemon/start! (:store b) (get-in b [:key :private-key])
                         {:batch-size 1 :poll-interval-ms 5 :stop-timeout-ms 5000})]
    (try
      (daemon/run-once! (:store a) (get-in a [:key :private-key]) {:batch-size 17})
      ;; Bounded wait for the real daemon, without replacing store or worker.
      (loop [remaining 500]
        (when (and (pos? remaining) (nil? @(:summary h)))
          (Thread/sleep 10)
          (recur (dec remaining))))
      (is (= :stopped (daemon/stop! h)))
      (is (= (:id a) (:id b)))
      (is (= (store/resolve-receipt-obligation (:store a) (:id a))
             (store/resolve-receipt-obligation (:store b) (:id b))))
      (finally (daemon/stop! h)))))

(deftest concurrent-runners-use-real-worker-and-store
  (let [{:keys [store key id]} (committed-fixture)
        entered (CountDownLatch. 2)
        release (CountDownLatch. 1)
        reconstruct worker/reconstruct]
    (with-redefs [worker/reconstruct (fn [s obligation-id private-key]
                                       (.countDown entered)
                                       (when-not (await! release)
                                         (throw (Exception. "barrier timeout")))
                                       (reconstruct s obligation-id private-key))]
      (let [a (future (daemon/run-once! store (:private-key key)))
            b (future (daemon/run-once! store (:private-key key)))]
        (try
          (is (await! entered))
          (.countDown release)
          (let [results [(deref a 5000 :timeout) (deref b 5000 :timeout)]]
            (is (not-any? #{:timeout} results))
            (is (= #{:issued :idempotent}
                   (set (mapcat #(map :status (:outcomes %)) results)))))
          (is (= :issued (:receipt-obligation/status
                          (store/resolve-receipt-obligation store id))))
          (finally (.countDown release)
                   (deref a 5000 nil)
                   (deref b 5000 nil)))))))
