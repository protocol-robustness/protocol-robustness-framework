(ns resolver-sim.notebooks.speds.var-test
  "P4: Scenario Distribution + VaR Projection.
   Verifies: the distribution is an explicit, uniform, empirical weighting
   artifact; VaR claims exist ONLY in var-projection.v1; quantiles and
   expected shortfall are exact and correctly ordered; unmeasured scenarios
   are excluded from the distribution; roots re-verify; the pipeline works
   over a real bundle."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.notebook-support.speds.risk :as risk]
            [resolver-sim.notebook-support.speds.var :as var]))

(defn- fake-proj
  "A minimal risk-projection-shaped map for model tests."
  [per-scenario]
  {:projection-id "fake-proj"
   :risk-projection/root {:canonical/hash "sha256:fake"}
   :coverage {:scenario-count (count per-scenario)
              :measured-scenario-count (count per-scenario)
              :not-measured-scenario-count 0
              :not-measured-scenarios []}
   :distribution-policy {:status :not-measured}
   :metrics {:per-scenario per-scenario}})

(defn- scenario [id exposure loss]
  {:scenario/id id
   :row-count 1
   :peak-observed-exposure exposure
   :max-observed-event-loss loss})

(deftest weighted-quantile-is-exact
  (testing "weighted empirical inverse CDF definition"
    (let [w (mapv (fn [v] {:value v :weight 1}) [10 20 30 40 50 60 70 80 90 100])]
      (is (= {:value 50 :cumulative-weight 5} (var/weighted-quantile 0.5 w)))
      (is (= {:value 100 :cumulative-weight 10} (var/weighted-quantile 0.95 w)))
      (is (= {:value 100 :cumulative-weight 10} (var/weighted-quantile 0.99 w))))))

(deftest distribution-is-uniform-empirical
  (testing "explicit weighting artifact with model, weights, normalization root, basis"
    (let [proj (fake-proj [(scenario "s1" 100 0)
                           (scenario "s2" 200 50)
                           (scenario "s3" 300 100)])
          d (var/build-distribution proj :per-scenario-peak-exposure)]
      (is (= "scenario-distribution.v1" (:schema d)))
      (is (= "empirical-scenario-distribution.v1" (:model d)))
      (is (= :per-scenario-peak-exposure (:outcome d)))
      (is (= [{:scenario/id "s1" :weight 1}
              {:scenario/id "s2" :weight 1}
              {:scenario/id "s3" :weight 1}]
             (:scenario-weights d)))
      (is (= 3 (get-in d [:normalization-root :sum-weights])))
      (is (= 3 (:weighted-scenario-count (:coverage d))))
      (is (= 0 (:excluded-scenario-count (:coverage d))))
      (is (= (:distribution/root d) (var/verify-distribution-root d))))))

(deftest distribution-excludes-unmeasured-scenarios
  (testing "scenarios without the outcome value get no weight and are counted"
    (let [proj (fake-proj [(scenario "s1" 100 0)
                           (scenario "s2" 200 50)
                           (assoc (scenario "s3" nil 100) :peak-observed-exposure nil)])
          d (var/build-distribution proj :per-scenario-peak-exposure)]
      (is (= 2 (:weighted-scenario-count (:coverage d))))
      (is (= 1 (:excluded-scenario-count (:coverage d))))
      (is (= ["s3"] (:excluded-scenarios (:coverage d))))
      (is (= 2 (count (:scenario-weights d)))))))

(deftest var-claims-exist-only-in-var-projection
  (testing "risk-projection keeps distribution :not-measured; VaR appears only downstream"
    (let [proj (fake-proj [(scenario "s1" 100 0)])
          d (var/build-distribution proj :per-scenario-peak-exposure)
          v (var/build-var-projection proj d)]
      (is (= :not-measured (get-in proj [:distribution-policy :status])))
      (is (contains? (:metrics v) :var/p95))
      (is (not (contains? (:metrics proj) :var/p95)))
      (is (contains? (:metrics proj) :per-scenario)))))

(deftest var-projection-orders-and-tail
  (testing "p99 >= p95, expected shortfall over the strict tail, tail attribution"
    (let [per (mapv (fn [i] (scenario (str "s" i) (* i 100) (* i 10)))
                    (range 1 101))
          proj (fake-proj per)
          d (var/build-distribution proj :per-scenario-peak-exposure)
          v (var/build-var-projection proj d)]
      (is (= "var-projection.v1" (:schema v)))
      (is (>= (get-in v [:metrics :var/p99 :value])
              (get-in v [:metrics :var/p95 :value])))
      ;; N=100 uniform: p95 -> 9500, strict tail = {9600..10000}
      (is (= 9500 (get-in v [:metrics :var/p95 :value])))
      (is (= 49000 (get-in v [:metrics :expected-shortfall/p95 :numerator]))
          "ES = sum of strict tail above VaR p95")
      (is (= 5 (get-in v [:metrics :expected-shortfall/p95 :denominator])))
      (is (= 9900 (get-in v [:metrics :var/p99 :value])))
      (is (= 10000 (get-in v [:metrics :expected-shortfall/p99 :numerator])))
      (is (= {:scenario/id "s100" :value 10000 :weight 1}
             (first (get-in v [:metrics :tail-attribution/p99 :scenarios]))))
      (is (= (:var/root v) (var/verify-var-root v))))))

(deftest var-and-distribution-are-deterministic
  (testing "identical inputs yield identical artifacts"
    (let [proj (fake-proj [(scenario "s1" 100 0) (scenario "s2" 200 50)])
          d (var/build-distribution proj :per-scenario-peak-exposure)
          v (var/build-var-projection proj d)]
      (is (= d (var/build-distribution proj :per-scenario-peak-exposure)))
      (is (= v (var/build-var-projection proj d))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Integration over a real (temp) event-evidence bundle
;; ──────────────────────────────────────────────────────────────────────────

(defn- write-json! [dir file m]
  (let [f (io/file dir file)]
    (.mkdirs (.getParentFile f))
    (spit f (json/write-str m))
    f))

(defn- node [scenario ev-seq chain-seq type hash total]
  {"scenario/id" scenario
   "evidence/type" type
   "evidence/hash" hash
   "evidence/chain-seq" chain-seq
   "event/seq" ev-seq
   "world/before-hash" (str (subs hash 32) (subs hash 0 32))
   "world/after-hash" hash
   "inputs" {"escrow/token" "USDC"}
   "post-state" {"escrow/after" {"total-held" total}}})

(defn- chain-link! [nodes]
  (loop [prev nil, rs (sort-by #(get % "evidence/chain-seq") nodes), out []]
    (if (empty? rs)
      out
      (let [n (first rs)
            self (chain/chain-link-hash (get n "evidence/hash")
                                        (get n "evidence/chain-seq") prev)
            linked (assoc n
                          "evidence/chain-hash-scheme" "link-v1"
                          "evidence/chain-prev-hash" prev
                          "evidence/chain-self-hash" self)]
        (recur self (rest rs) (conj out linked))))))

(defn- bundle-dirs []
  (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                     (str "var-test-" (System/nanoTime)))
        ev (io/file tmp "event-evidence")
        tr (io/file tmp "traces")]
    (.mkdirs ev)
    (.mkdirs tr)
    (let [files [["s1-1.json" (node "s1" 0 1 "escrow-created" (apply str (repeat 32 "a1")) 1000)]
                 ["s1-2.json" (node "s1" 1 2 "escrow-released" (apply str (repeat 32 "a2")) 0)]
                 ["s2-1.json" (node "s2" 0 1 "escrow-created" (apply str (repeat 32 "b1")) 2000)]
                 ["s3-1.json" (node "s3" 0 1 "escrow-created" (apply str (repeat 32 "c1")) 3000)]]]
      (doseq [[file m] (->> files
                            (map (fn [[f m]] (assoc m "_file" f)))
                            (group-by #(get % "scenario/id"))
                            (mapcat (fn [[_ ns]]
                                      (map (fn [n] [(get n "_file") (dissoc n "_file")])
                                           (chain-link! ns))))
                            (into []))]
        (write-json! ev file m)))
    {:bundle-dir (.getAbsolutePath ev)
     :trace-dir (.getAbsolutePath tr)}))

(deftest integration-pipeline-over-bundle
  (testing "risk-projection -> distribution -> var-projection over a real bundle"
    (let [proj (risk/project (bundle-dirs))
          d (var/build-distribution proj :per-scenario-peak-exposure)
          v (var/build-var-projection proj d)]
      (is (= 3 (:weighted-scenario-count (:coverage d))))
      (is (= 3000 (get-in v [:metrics :var/p99 :value]))
          "p99 over three uniform exposures {1000,2000,3000} = max")
      (is (= :pass (:status (risk/verify-root proj))))
      (is (= (:distribution/root d) (var/verify-distribution-root d)))
      (is (= (:var/root v) (var/verify-var-root v))))))
