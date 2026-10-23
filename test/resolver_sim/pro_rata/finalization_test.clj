(ns resolver-sim.pro-rata.finalization-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.execution.finalization-lane :as lane]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.finalization :as sut]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.invocation-publication-binding-test :as fixture]
            [resolver-sim.pro-rata.postgres-publication-store :as postgres]
            [resolver-sim.pro-rata.publication :as publication])
  (:import [java.util.concurrent.atomic AtomicInteger]))

(defn- prepared []
  (let [resolved (@#'fixture/fixture)
        publication-binding (binding/build-binding resolved)
        store (postgres/postgres-store nil {:resolve-durable-artifact (constantly nil)})]
    {:resolved resolved
     :publication-binding publication-binding
     :store store}))

(defn- valid-commitments [resolved publication-binding]
  {:closed-result-set-root (get-in resolved [:allocation :allocation/hash])
   :basis-root (:pro-rata-invocation-publication-binding/root publication-binding)
   :valid-basis? true})

(defn- initial-head []
  (publication/initial-application-head))

(defn- lane-for [head]
  (lane/open {:expected-parent-root (:publication/head-root head)
              :requires-quiescence? false}))

(deftest allocation-hash-is-the-validated-closed-result-set-root
  (let [allocation (allocation/allocate
                    {:allocation/id :finalization-proof
                     :available 10
                     :rows [{:row/id :a :obligation/id :a :requested 10 :weight 1}]})
        tampered (assoc allocation :allocated-total 9N)
        {:keys [resolved publication-binding store]} (prepared)
        root-only (assoc resolved :allocation
                         {:allocation/hash (get-in resolved [:allocation :allocation/hash])})
        commitments (sut/finalization-commitments store root-only publication-binding)]
    (is (allocation/allocation-hash-valid? allocation))
    (is (false? (allocation/allocation-hash-valid? tampered)))
    (is (= (get-in resolved [:allocation :allocation/hash])
           (:closed-result-set-root commitments)))
    (is (= (:pro-rata-invocation-publication-binding/root publication-binding)
           (:basis-root commitments)))
    (is (false? (:valid-basis? commitments))
        "A root-only fixture is rejected; authority finalization requires the full allocation envelope.")))

(deftest authority-commit-precedes-local-realization-projection
  (let [{:keys [resolved publication-binding store]} (prepared)
        head (initial-head)
        r (realization/open)
        l (lane-for head)
        events (atom [])
        emitted (AtomicInteger. 0)]
    (with-redefs [sut/finalization-commitments (fn [_ _ _]
                                                 (valid-commitments resolved publication-binding))
                  postgres/current-head-and-version
                  (fn [_ _] {:publication/head head :publication/store-version 0})
                  postgres/publish-application-bound!
                  (fn [& _]
                    (swap! events conj :authority-commit)
                    {:status :committed :publication/head :committed-head})]
      (binding [realization/*claimant-execution-runtime-profile-root* "test-runtime-root"
                realization/*claimant-execution-observation-sink*
                (fn [_]
                  (swap! events conj :emit)
                  (.incrementAndGet emitted))]
        (let [result (sut/finalize-application-bound!
                      {:realization r :lane l :publication-store store :resolved resolved
                       :publication-binding publication-binding})]
          (is (= :committed (:status result)))
          (is (= :finalized (:lifecycle @r)))
          (is (lane/finalized? l)))))
    (is (= [:authority-commit :emit] @events))
    (is (= 1 (.get emitted))))

  (deftest invalid-basis-never-enters-authoritative-write
    (let [{:keys [resolved publication-binding store]} (prepared)
          head (initial-head)
          r (realization/open)
          l (lane-for head)
          writes (AtomicInteger. 0)]
      (with-redefs [postgres/current-head-and-version
                    (fn [_ _] {:publication/head head :publication/store-version 0})
                    postgres/publish-application-bound!
                    (fn [& _] (.incrementAndGet writes) {:status :committed})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (sut/finalize-application-bound!
                      {:realization r :lane l :publication-store store
                       :resolved (assoc-in resolved [:allocation :rows 0 :allocated] 999)
                       :publication-binding publication-binding}))))
      (is (= 0 (.get writes)))
      (is (= :open (:lifecycle @r)))
      (is (= :open (lane/state l))))))

(deftest idempotent-authority-suppresses-local-projection-and-emission
  (let [{:keys [resolved publication-binding store]} (prepared)
        head (initial-head)
        r (realization/open)
        l (lane-for head)
        emitted (AtomicInteger. 0)]
    (with-redefs [sut/finalization-commitments (fn [_ _ _]
                                                 (valid-commitments resolved publication-binding))
                  postgres/current-head-and-version
                  (fn [_ _] {:publication/head head :publication/store-version 0})
                  postgres/publish-application-bound!
                  (fn [& _] {:status :idempotent :publication/head :current})]
      (binding [realization/*claimant-execution-runtime-profile-root* "test-runtime-root"
                realization/*claimant-execution-observation-sink*
                (fn [_] (.incrementAndGet emitted))]
        (let [result (sut/finalize-application-bound!
                      {:realization r :lane l :publication-store store :resolved resolved
                       :publication-binding publication-binding})]
          (is (= :suppressed (:status result)))
          (is (= :already-authoritatively-published (:reason result))))))
    (is (= :frozen (:lifecycle @r)))
    (is (lane/finalized? l))
    (is (= 0 (.get emitted)))))

(deftest reconciles-frozen-local-realization-from-committed-authority
  (let [r (realization/open)
        authority {:publication/head {:publication/last-ordering-root "order-root"
                                      :publication/application-binding-root "binding-root"}
                   :publication/ordering {:transaction-ordering/hash "order-root"}
                   :publication/binding
                   {:pro-rata-invocation-publication-binding/root "binding-root"}}]
    (realization/freeze! r)
    (with-redefs [postgres/resolve-authoritative-application-publication
                  (fn [_ _] authority)]
      (is (= :reconciled
             (:status (sut/reconcile-local-projection!
                       {:realization r :publication-store :store :conflict-key [:k]
                        :expected-ordering-root "order-root"
                        :expected-binding-root "binding-root"}))))
      (is (= :finalized (:lifecycle @r))))))

(deftest reconciliation-rejects-authority-identity-mismatch
  (let [r (realization/open)
        authority {:publication/head {:publication/last-ordering-root "other-order"
                                      :publication/application-binding-root "binding-root"}
                   :publication/ordering {:transaction-ordering/hash "other-order"}
                   :publication/binding
                   {:pro-rata-invocation-publication-binding/root "binding-root"}}]
    (realization/freeze! r)
    (with-redefs [postgres/resolve-authoritative-application-publication
                  (fn [_ _] authority)]
      (is (= :authoritative-publication-identity-mismatch
             (:reason (try
                        (sut/reconcile-local-projection!
                         {:realization r :publication-store :store :conflict-key [:k]
                          :expected-ordering-root "order-root"
                          :expected-binding-root "binding-root"})
                        nil
                        (catch clojure.lang.ExceptionInfo error
                          (ex-data error)))))))))

(deftest authority-finalization-fences-the-postgres-store-version-not-head-sequence
  (let [{:keys [resolved publication-binding store]} (prepared)
        head (assoc (initial-head) :publication/sequence 9)
        r (realization/open)
        l (lane-for head)
        published-with (atom nil)]
    (with-redefs [sut/finalization-commitments (fn [_ _ _]
                                                 (valid-commitments resolved publication-binding))
                  postgres/current-head-and-version
                  (fn [_ _] {:publication/head head :publication/store-version 41})
                  postgres/publish-application-bound!
                  (fn [& args]
                    (reset! published-with args)
                    {:status :committed :publication/head :committed-head})]
      (is (= :committed
             (:status (sut/finalize-application-bound!
                       {:realization r :lane l :publication-store store :resolved resolved
                        :publication-binding publication-binding}))))
      (is (= 41 (nth @published-with 5)))
      (is (not= (:publication/sequence head) (nth @published-with 5))))))
