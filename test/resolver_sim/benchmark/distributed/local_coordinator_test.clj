(ns resolver-sim.benchmark.distributed.local-coordinator-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.distributed.chunk-result :as result]
            [resolver-sim.benchmark.distributed.fixed-chunks :as fixed]
            [resolver-sim.benchmark.distributed.local-coordinator :as sut]))

(defn- root [digit] (str "sha256:" (apply str (repeat 64 digit))))
(def plan [{:execution/ordinal 1 :execution/id "execution-a" :execution/descriptor {:id "a"} :scenario/input-root (root "a")}
           {:execution/ordinal 2 :execution/id "execution-b" :execution/descriptor {:id "b"} :scenario/input-root (root "b")}])
(def fixed-set (fixed/derive-fixed-chunk-set plan {:chunk-size 1 :sensitivity-root (root "c") :executable-distribution-root (root "d")}))

(defn- manifest [claim]
  (result/build-manifest {:chunk/id (:chunk/id claim)
                          :run-plan/root (:run-plan/root claim)
                          :execution-plan/root (:execution-plan/root claim)
                          :chunk/input-root (:chunk/expected-input-root claim)
                          :chunk/work-root (:chunk/expected-work-root claim)
                          :executable-distribution/root (:chunk/expected-executable-distribution-root claim)
                          :chunk/execution-ids (:chunk/execution-ids claim)
                          :chunk/staged-artifact-manifest-roots [(root "d")]
                          :sensitivity/root (:chunk/expected-sensitivity-root claim)}))

(defn- completion [claim now]
  {:chunk-id (:chunk/id claim) :lease-token (:lease/token claim) :fence (:fence claim)
   :now-ms now :detached-chunk-result (manifest claim)})

(deftest lifecycle-is-registered-claimed-fenced-completed-and-recoverable
  (let [coordinator (sut/local-coordinator)]
    (is (= :registered (:outcome (sut/register-run! coordinator "run-1" fixed-set))))
    (let [claim (sut/claim-chunk! coordinator "run-1" {:now-ms 10 :lease-ms 100})
          complete (sut/complete-chunk! coordinator "run-1" (completion claim 20))]
      (is (= "chunk-0001" (:chunk/id claim)))
      (is (= :completed (:outcome complete)))
      (is (= :idempotent-completion (:outcome (sut/complete-chunk! coordinator "run-1" (completion claim 20)))))
      (is (= (:detached-chunk-result/root (:detached-chunk-result (completion claim 20)))
             (:detached-chunk-result/root (sut/resolve-chunk-completion! coordinator "run-1" (:chunk/id claim))))))))

(deftest incomplete-run-cannot-be-terminalized
  (let [coordinator (sut/local-coordinator)]
    (sut/register-run! coordinator "run-incomplete" fixed-set)
    (is (= :incomplete-run
           (:reason (sut/mark-run-execution-complete! coordinator "run-incomplete"))))
    (let [claim (sut/claim-chunk! coordinator "run-incomplete" {:now-ms 10 :lease-ms 100})]
      (is (= :incomplete-run
             (:reason (sut/mark-run-execution-complete! coordinator "run-incomplete"))))
      (is (= :completed
             (:outcome (sut/complete-chunk! coordinator "run-incomplete" (completion claim 20)))))
      (let [second-claim (sut/claim-chunk! coordinator "run-incomplete" {:now-ms 20 :lease-ms 100})]
        (is (= :completed
               (:outcome (sut/complete-chunk! coordinator "run-incomplete"
                                              (completion second-claim 30))))))
      (is (= :execution-complete
             (:outcome (sut/mark-run-execution-complete! coordinator "run-incomplete")))))))

(deftest rejects-lease-token-mismatch-and-expired-lease
  (let [coordinator (sut/local-coordinator)]
    (sut/register-run! coordinator "run-lease" fixed-set)
    (let [claim (sut/claim-chunk! coordinator "run-lease" {:now-ms 10 :lease-ms 5})]
      (is (= :lease-token-mismatch
             (:reason (sut/complete-chunk! coordinator "run-lease"
                                           (assoc (completion claim 12)
                                                  :lease-token "wrong-token")))))
      (is (= :lease-expired
             (:reason (sut/complete-chunk! coordinator "run-lease"
                                           (completion claim 15))))))))

(deftest retry-with-same-semantic-result-is-idempotent-after-reclaim
  (let [coordinator (sut/local-coordinator)]
    (sut/register-run! coordinator "run-retry" fixed-set)
    (let [first-claim (sut/claim-chunk! coordinator "run-retry" {:now-ms 10 :lease-ms 5})
          second-claim (sut/claim-chunk! coordinator "run-retry" {:now-ms 15 :lease-ms 100})
          first-result (completion first-claim 20)
          second-result (completion second-claim 20)]
      (is (= :stale-fence (:reason (sut/complete-chunk! coordinator "run-retry" first-result))))
      (is (= (:detached-chunk-result/root first-result)
             (:detached-chunk-result/root second-result)))
      (is (= :completed (:outcome (sut/complete-chunk! coordinator "run-retry" second-result))))
      (is (= :idempotent-completion
             (:outcome (sut/complete-chunk! coordinator "run-retry" second-result)))))))

(deftest local-key-capacity-refusal-is-a-normal-unleased-claim-result
  (let [coordinator (sut/local-coordinator)
        request {:now-ms 10 :lease-ms 100
                 :local-key/id "worker-a" :local-key/capacity 2 :local-key/budget 1}]
    (sut/register-run! coordinator "run-local-capacity" fixed-set)
    (let [lease (sut/claim-chunk! coordinator "run-local-capacity" request)
          refusal (sut/claim-chunk! coordinator "run-local-capacity" request)]
      (is (= "chunk-0001" (:chunk/id lease)))
      (is (= :unavailable (:claim/status refusal)))
      (is (= :execution/local-capacity-exceeded (:reason refusal)))
      (is (= "worker-a" (:local-key/id refusal)))
      (is (= 1 (:local-key/in-use refusal)))
      (is (not (contains? refusal :lease/token)))
      (is (not (contains? refusal :fence)))
      (sut/complete-chunk! coordinator "run-local-capacity" (completion lease 20))
      (is (= "chunk-0002"
             (:chunk/id (sut/claim-chunk! coordinator "run-local-capacity"
                                          (assoc request :now-ms 20))))))))

(deftest rejects-descriptor-substitution-and-stale-leases
  (let [coordinator (sut/local-coordinator)]
    (sut/register-run! coordinator "run-2" fixed-set)
    (let [first-claim (sut/claim-chunk! coordinator "run-2" {:now-ms 10 :lease-ms 5})
          replacement (sut/claim-chunk! coordinator "run-2" {:now-ms 15 :lease-ms 100})]
      (is (= 2 (:fence replacement)))
      (is (= :stale-fence (:reason (sut/complete-chunk! coordinator "run-2" (completion first-claim 16)))))
      (is (= :manifest-descriptor-mismatch
             (:reason (sut/complete-chunk! coordinator "run-2"
                                           (assoc (completion replacement 16)
                                                  :detached-chunk-result
                                                  (assoc (manifest replacement) :chunk/input-root (root "e"))))))))))
