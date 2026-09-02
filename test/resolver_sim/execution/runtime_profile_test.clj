(ns resolver-sim.execution.runtime-profile-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.economics.payoffs :as payoffs]
            [resolver-sim.execution.context :as execution]
            [resolver-sim.execution.observation :as observation]
            [resolver-sim.execution.realization :as realization]
            [resolver-sim.execution.runtime-profile :as profile]
            [resolver-sim.hash.canonical :as hc]))

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

(defn closure-report [name]
  (let [{:keys [exit out err]} (shell/sh "bb" "scripts/profile_view.clj" "describe" name)]
    (when-not (zero? exit)
      (throw (ex-info "Profile closure report failed" {:profile name :stderr err})))
    (edn/read-string out)))

(defn runtime-profile-for [name]
  (profile/build (get-in (read-profile name) [:profile/runtime :runtime/options])))

(defn observe-allocation [runtime allocation]
  (let [emitted (atom [])]
    (binding [execution/*context* (:runtime-profile/requested runtime)
              realization/*claimant-execution-runtime-profile-root* (:runtime-profile/root runtime)
              realization/*claimant-execution-observation-sink* #(swap! emitted conj %)]
      {:result (execution/with-claimant-options
                 (payoffs/allocate-pro-rata allocation))
       :observation (do
                      (is (= 1 (count @emitted)) "one observation per allocation")
                      (first @emitted))})))

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

(deftest pro-rata-runtime-profile-orthogonality-gate
  (let [items (mapv (fn [i] {:id (keyword (str "claim-" i)) :weight 1}) (range 16))
        allocation {:amount 101 :items items
                    :ordering-policy :canonical-id
                    :rounding :floor-with-largest-remainder}
        baseline-profile (runtime-profile-for "pro-rata")
        claimant-profile (runtime-profile-for "pro-rata-claimant-options")
        baseline (observe-allocation baseline-profile allocation)
        claimant (observe-allocation claimant-profile allocation)
        threshold-profile (profile/build {:execution/claimant-parallelism 4
                                          :execution/claimant-parallel-threshold 17
                                          :execution/quiescence-timeout-seconds 30})
        threshold (observe-allocation threshold-profile allocation)]
    (testing "formal profiles preserve semantic output and roots"
      (is (= (:result baseline) (:result claimant) (:result threshold)))
      (is (= (hc/hash-with-intent {:hash/intent :projection-artifact} (:result baseline))
             (hc/hash-with-intent {:hash/intent :projection-artifact} (:result claimant))
             (hc/hash-with-intent {:hash/intent :projection-artifact} (:result threshold))))
      (testing "runtime roots and actual allocator observations distinguish profiles"
        (is (not= (:runtime-profile/root baseline-profile)
                  (:runtime-profile/root claimant-profile)))
        (is (not= (get-in baseline [:observation :execution-observation/root])
                  (get-in claimant [:observation :execution-observation/root])))
        (is (:valid? (observation/verify-against-profile baseline-profile (:observation baseline))))
        (is (:valid? (observation/verify-against-profile claimant-profile (:observation claimant)))))
      (testing "baseline is serial and claimant options actually dispatch in parallel"
        (is (= :serial (get-in baseline [:observation :execution-observation/effective :execution/path])))
        (is (= :serial-requested (get-in baseline [:observation :execution-observation/effective :execution/reason])))
        (is (= :parallel (get-in claimant [:observation :execution-observation/effective :execution/path])))
        (is (= :parallel (get-in claimant [:observation :execution-observation/effective :execution/reason])))
        (is (true? (get-in claimant [:observation :execution-observation/effective :execution/parallel-work-observed?]))))
      (testing "requested parallelism alone is not a parallelism claim"
        (is (= (:result baseline) (:result threshold)))
        (is (= :serial (get-in threshold [:observation :execution-observation/effective :execution/path])))
        (is (= :serial-threshold (get-in threshold [:observation :execution-observation/effective :execution/reason])))
        (is (false? (get-in threshold [:observation :execution-observation/effective :execution/parallel-work-observed?])))))))

(deftest profile-orthogonality-declaration-gate
  (let [baseline (read-profile "pro-rata")
        runtime (read-profile "pro-rata-claimant-options")
        bounty (read-profile "pro-rata-bounty")
        full (read-profile "pro-rata-full")]
    (testing "runtime-only claimant options do not alter implementation closure"
      (is (= (profile-components "pro-rata")
             (profile-components "pro-rata-claimant-options")))
      (let [baseline-closure (closure-report "pro-rata")
            claimant-closure (closure-report "pro-rata-claimant-options")]
        (is (= (:component-ids baseline-closure) (:component-ids claimant-closure)))
        (is (= (:transitive-source-files baseline-closure)
               (:transitive-source-files claimant-closure))))
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
