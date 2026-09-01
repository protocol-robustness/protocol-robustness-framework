(ns resolver-sim.execution.runtime-profile-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.execution.context :as execution]
            [resolver-sim.execution.runtime-profile :as profile]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.allocation.context :as allocation-context]
            [resolver-sim.allocation.kernel :as allocation-kernel]
            [resolver-sim.allocation.roots :as allocation-roots]
            [resolver-sim.allocation.test-fixtures :as allocation-fixtures]))

(def options {:execution/claimant-parallelism 4
              :execution/claimant-parallel-threshold 1
              :execution/quiescence-timeout-seconds 30})

(defn read-profile [name]
  (edn/read-string (slurp (io/file "profiles" (str name ".edn")))))

(def profile-by-id
  (into {} (map (fn [name]
                  (let [p (read-profile name)] [(:profile/id p) p]))
                ["pro-rata" "pro-rata-bounty" "pro-rata-claimant-options" "pro-rata-full"])))

(defn resolved-profile [name]
  (letfn [(resolve [p]
            (if-let [parent-id (:profile/extends p)]
              (let [parent (resolve (get profile-by-id parent-id))]
                (-> (merge parent p)
                    (update :profile/components #(vec (distinct (concat (:profile/components parent) %))))
                    (update :profile/semantic-features #(vec (distinct (concat (:profile/semantic-features parent) %))))))
              p))]
    (resolve (read-profile name))))

(defn profile-components [name]
  (set (:profile/components (resolved-profile name))))

(deftest runtime-profile-resolver-preserves-builder-parity
  (is (= (profile/build options)
         (profile/resolve-runtime-profile options))))

(deftest claimant-runtime-profile-is-canonical-and-closed
  (let [p (profile/build options)]
    (is (profile/valid? p))
    (is (= :prf/claimant-options-v1 (:runtime-profile/type p)))
    (is (= options (:runtime-profile/requested p)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:runtime-profile/root p)))
    (is (= (:runtime-profile/root p) (:runtime-profile/root (profile/build options))))))

(deftest claimant-runtime-profile-preserves-semantics-and-realizes-parallelism
  (let [options {:execution/claimant-parallelism 2
                 :execution/claimant-parallel-threshold 1
                 :execution/quiescence-timeout-seconds 30}
        items (mapv (fn [i] {:id (keyword (str "claim-" i)) :weight 1}) (range 16))
        serial (payoffs/allocate-pro-rata {:amount 101 :items items
                                           :ordering-policy :canonical-id
                                           :rounding :floor-with-largest-remainder})
        worker-threads (atom #{})
        parallel (binding [execution/*context* options]
                   (execution/with-claimant-options
                     (payoffs/allocate-pro-rata
                      {:amount 101 :items items
                       :ordering-policy :canonical-id
                       :rounding :floor-with-largest-remainder
                       :weight-fn (fn [item]
                                    (swap! worker-threads conj (.getName (Thread/currentThread)))
                                    (:weight item))})))]
    (is (= serial parallel))
    (is (> (count @worker-threads) 1)
        (str "Expected detached claimant workers, got " @worker-threads))
    (is (= (hc/hash-with-intent {:hash/intent :projection-artifact} serial)
           (hc/hash-with-intent {:hash/intent :projection-artifact} parallel)))))

(deftest claimant-runtime-profile-does-not-change-semantic-allocation-identity
  (let [input (allocation-fixtures/happy-input)
        context (allocation-context/build-context input)
        serial (allocation-kernel/run-kernel input)
        options (profile/build {:execution/claimant-parallelism 4
                                :execution/claimant-parallel-threshold 1
                                :execution/quiescence-timeout-seconds 30})
        parallel (binding [execution/*context* (:runtime-profile/requested options)]
                   (execution/with-claimant-options
                     (allocation-kernel/run-kernel input)))]
    (doseq [field [:claimant-set-root :outcome-set-root :proposed-rates-root :result-root]]
      (is (= (get serial field) (get parallel field)) (str field " must be semantic-stable")))
    (is (= (allocation-context/context-hash context)
           (:allocation-context-hash serial)
           (:allocation-context-hash parallel)))
    (is (not= (:runtime-profile/root (profile/build {:execution/claimant-parallelism 1
                                                     :execution/claimant-parallel-threshold 16
                                                     :execution/quiescence-timeout-seconds 30}))
              (:runtime-profile/root options)))))

(deftest profile-orthogonality-declaration-gate
  (let [baseline (read-profile "pro-rata")
        runtime (read-profile "pro-rata-claimant-options")
        bounty (read-profile "pro-rata-bounty")
        full (read-profile "pro-rata-full")]
    (testing "runtime-only claimant options do not alter implementation closure"
      (is (= (profile-components "pro-rata")
             (profile-components "pro-rata-claimant-options")))
      (is (nil? (:profile/semantic-features runtime)))
      (is (= :prf/claimant-options-v1
             (get-in runtime [:profile/runtime :runtime/type]))))
    (testing "bounty extends the semantic plane"
      (is (= #{:prf/ideal-pro-rata-v1}
             (set (:profile/semantic-features baseline))))
      (is (= #{:prf/bounty-v1}
             (set (:profile/semantic-features bounty)))))
    (testing "full profile declares all three planes"
      (is (= #{:prf/bounty-v1 :prf/ideal-pro-rata-v1}
             (set (:profile/semantic-features (resolved-profile "pro-rata-full")))))
      (is (= [:prf/claimant-options-v1] (:profile/runtime-features full)))
      (is (= [:prf/pro-rata-verifier-v1] (:profile/assurance-features full)))
      (is (= #{:prf/ideal-pro-rata-v1
               :prf/bounty-v1
               :prf/pro-rata-verifier-v1}
             (profile-components "pro-rata-full"))))))

(deftest claimant-runtime-profile-rejects-tampering
  (let [p (profile/build options)
        tampered (assoc-in p [:runtime-profile/requested :execution/claimant-parallelism] 8)]
    (is (false? (profile/valid? tampered)))
    (is (some #{:runtime-profile-root-mismatch :invalid-claimant-options}
              (:errors (profile/validate tampered))))))
