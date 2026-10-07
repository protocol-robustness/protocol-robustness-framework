(ns resolver-sim.research.native-accounting-batch-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.native-accounting-batch :as batch]
            [resolver-sim.research.native-accounting-work :as native]
            [resolver-sim.research.native-accounting-work-test :as fixture]
            [resolver-sim.research.native-accounting-profile :as profile]
            [resolver-sim.research.native-accounting-performance :as performance]
            [resolver-sim.research.runner-transport :as transport])
  (:import [java.util.concurrent CountDownLatch TimeUnit]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- account-fixtures []
  (let [basis (fixture/basis 4 10 987)]
    {:basis basis
     :fixtures (mapv #(fixture/work-fixture (+ 100 %) (+ 301 (mod % 2))
                                          {:account-basis basis}) (range 4))}))

(defn- semantic-results [result]
  (into (sorted-map)
        (map (fn [[account r]] [account (get-in r [:accounting/reconciliation
                                                   :canonical-accounting-result/root])]))
        (:accounting/account-results result)))

(deftest actual-account-dispatch-preserves-parent-coverage-and-semantic-roots
  (let [{:keys [basis fixtures]} (account-fixtures)
        coordinator (transport/start-coordinator!
                     {:coordinator-root fixture/coordinator-root :auth-secret fixture/secret
                      :assignments (mapv :assignment fixtures)})
        runners (mapv #(transport/start-runner!
                        {:runner-participation (:participant %) :auth-secret fixture/secret
                         :coordinator-root fixture/coordinator-root
                         :coordinator-endpoint (transport/coordinator-endpoint coordinator fixture/secret 30000)})
                      (take 2 fixtures))
        dispatches (mapv (fn [i f] {:work (:work f)
                                   :endpoint (transport/coordinator-endpoint (nth runners (mod i 2))
                                                                            fixture/secret 30000)})
                         (range) fixtures)]
    (try
      (let [{result :value timings :runtime/profile}
            (profile/profile-call
             #(batch/dispatch-accounts-and-reconcile!
               coordinator (:request basis) (:contract basis) dispatches
               {:max-concurrent-dispatches 2 :max-outstanding-per-runner 1}))
            replay (batch/dispatch-accounts-and-reconcile!
                    coordinator (:request basis) (:contract basis) (vec (reverse dispatches))
                    {:max-concurrent-dispatches 1})]
        (is (= :complete (:accounting/batch-status result)))
        (is (= 4 (count (:accounting/account-results result))))
        (is (empty? (:accounting/incomplete-account-roots result)))
        (is (= (:accounting-request/root (:request basis)) (:accounting/parent-request-root result)))
        (is (= (semantic-results result) (semantic-results replay)))
        (doseq [[account request] (:accounts basis)]
          (is (= (accounting/serial-values request)
                 (get-in result [:accounting/account-results account :accounting/reconciliation :accounting/values]))))
        (is (pos? (get-in timings [:stages [:coordinator :partition-arithmetic-recomputation] :calls] 0)))
        (is (= 4 (get-in timings [:stages [:executor-delivery :result-delivery-round-trip] :calls])))
        (is (= 4 (get-in timings [:stages [:reconciliation :full-serial-fold] :calls])))
        (is (= :not-observed (:remote-stages timings))))
      (finally
        (doseq [runner runners] (transport/stop-runner! runner))
        (transport/stop-coordinator! coordinator)))))

(deftest malformed-parent-batches-fail-before-dispatch
  (let [{:keys [basis fixtures]} (account-fixtures)
        items (mapv #(hash-map :work (:work %) :endpoint {}) fixtures)
        calls (atom 0)]
    (with-redefs [transport/dispatch-native-accounting! (fn [& _] (swap! calls inc))]
      (doseq [bad [(subvec items 0 3) (conj items (first items))
                   (assoc-in items [0 :work :accounting/request :accounting/cutpoint-root] (fixture/root 99))]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) bad {}))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (batch/dispatch-accounts-and-reconcile! nil (:request (fixture/basis 4 11 987))
                                                         (:contract basis) items {})))
      (doseq [limits [{:max-concurrent-dispatches 0} {:max-outstanding-per-runner 0}]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) items limits))))
      (is (zero? @calls)))))

(deftest bounded-capacity-scans-past-busy-runners-and-preserves-completed-accounts
  (let [{:keys [basis fixtures]} (account-fixtures)
        ;; Group the queue by runner deliberately: B must start despite A's
        ;; second pending account occupying an earlier queue position.
        fixtures (sort-by #(get-in % [:participant :runner/reference]) fixtures)
        items (mapv #(hash-map :work (:work %) :endpoint {}) fixtures)
        executions (into {} (map #(vector (native/work-root (:work %)) (native/execute-work! (:work %))) fixtures))
        state (atom {}) active (atom {}) peak (atom 0) per-runner-peak (atom {})
        entered (CountDownLatch. 2) release (CountDownLatch. 1)]
    (with-redefs [transport/coordinator-state (fn [_] @state)
                  transport/dispatch-native-accounting!
                  (fn [_ {:keys [accounting-work]}]
                    (let [runner (get-in accounting-work [:work/assignment :runner/reference])
                          counts (swap! active update runner (fnil inc 0))]
                      (swap! peak max (reduce + (vals counts)))
                      (swap! per-runner-peak update runner (fnil max 0) (get counts runner))
                      (.countDown entered)
                      (try
                        (when-not (.await release 10 TimeUnit/SECONDS)
                          (throw (ex-info "Test dispatch barrier timed out" {})))
                        (if (= (fixture/root 103) (native/account-root (:accounting/request accounting-work)
                                                                     (:accounting/work-unit accounting-work)))
                          (throw (ex-info "Unavailable runner for final account" {}))
                          (let [execution (get executions (native/work-root accounting-work))
                                plan (get-in accounting-work [:accounting/partition-plan :accounting-partition-plan/root])]
                            (swap! state update-in [:accounting-partials plan] (fnil accounting/ingest-partial {})
                                   (:accounting-partial execution))
                            {:delivery {:delivery/status :accepted} :native-accounting-execution execution}))
                        (finally (swap! active update runner dec))))) ]
      (let [task (future (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) items
                                                              {:max-concurrent-dispatches 2
                                                               :max-outstanding-per-runner 1}))]
        (try
          (is (.await entered 10 TimeUnit/SECONDS))
          (.countDown release)
          (let [result (deref task 20000 ::timeout)]
            (is (not= ::timeout result))
            (is (= 2 @peak))
            (is (every? #(= 1 %) (vals @per-runner-peak)))
            (is (= :incomplete (:accounting/batch-status result)))
            (is (= [(fixture/root 103)] (:accounting/incomplete-account-roots result)))
            (is (= 3 (count (filter #(= :complete (:accounting/status %))
                                   (vals (:accounting/account-results result))))))
            (is (nil? (get-in result [:accounting/account-results (fixture/root 103) :accounting/reconciliation]))))
          (finally (.countDown release) (future-cancel task)))))))

(deftest profiling-keeps-execution-identities-and-restores-functions
  (let [work (:work (fixture/work-fixture))
        expected (native/execute-work! work)
        original native/execute-work!
        measured (profile/profile-call #(native/execute-work! work))]
    (is (= expected (:value measured)))
    (is (identical? original native/execute-work!))
    (is (= 1 (get-in measured [:runtime/profile :stages [:executor :execution] :calls])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (profile/profile-call #(throw (ex-info "Expected failure" {})))))
    (is (identical? original native/execute-work!))))

(deftest cancelled-account-cannot-become-zero-or-complete
  (let [{:keys [basis fixtures]} (account-fixtures)
        partials (into {} (map (fn [f]
                                [(get-in f [:plan :accounting-partition-plan/root])
                                 (accounting/ingest-partial {} (:accounting-partial (native/execute-work! (:work f))))])
                              (take 3 fixtures)))
        items (mapv #(hash-map :work (:work %) :endpoint {}) fixtures)]
    (with-redefs [transport/coordinator-state (fn [_] {:accounting-partials partials})
                  transport/dispatch-native-accounting!
                  (fn [_ {:keys [accounting-work]}]
                    {:delivery {:delivery/status
                                (if (= (fixture/root 103)
                                       (native/account-root (:accounting/request accounting-work)
                                                            (:accounting/work-unit accounting-work)))
                                  :cancelled :already-completed)}})]
      (let [result (batch/dispatch-accounts-and-reconcile! nil (:request basis) (:contract basis) items {})]
        (is (= :incomplete (:accounting/batch-status result)))
        (is (= [(fixture/root 103)] (:accounting/incomplete-account-roots result)))
        (is (= 3 (count (remove nil? (vals (semantic-results result))))))
        (is (nil? (get-in result [:accounting/account-results (fixture/root 103) :accounting/reconciliation])))
        (is (= 3 (count partials)))))))

(deftest performance-startup-failure-removes-owned-journal-directory
  (let [directory (.toFile (Files/createTempDirectory "accounting-startup-failure-" (make-array FileAttribute 0)))
        scratch-var (ns-resolve 'resolver-sim.research.native-accounting-performance 'scratch-directory)]
    (with-redefs-fn {scratch-var (constantly directory)
                    #'transport/start-coordinator! (fn [& _] (throw (ex-info "Socket unavailable" {})))}
      #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"Socket unavailable"
                            (performance/-main "1" "10" "1" "1" "1"))))
    (is (not (.exists directory)))))
