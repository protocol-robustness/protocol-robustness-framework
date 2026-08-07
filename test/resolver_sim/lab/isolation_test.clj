(ns resolver-sim.lab.isolation-test
  "Concurrent-run isolation and recovery tests.

   The web server caps concurrency at 2, but capping is not the same as
   isolation. These tests verify that simultaneous runs — even runs of
   different experiments — do not cross-contaminate run identity, evidence
   roots, or artifact directories, that independent reruns reproduce their
   semantic roots, and that killing one subprocess neither damages a concurrent
   run nor the service (a subsequent run completes)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.lab.exec :as exec]
            [resolver-sim.lab.runner :as runner]))

(def ^:private runs-dir "/tmp/lab-isolation-runs")

(defn- run-async
  "Run an experiment in a background thread."
  [request]
  (let [id (runner/generate-run-id)]
    {:run-id id
     :future (future
               (exec/run-experiment! request id
                                     {:runs-dir runs-dir
                                      :timeout-ms 120000}))}))

(def ^:private withdrawal-fcfs
  {:experiment "withdrawal-constrained-liquidity.v1"
   :parameters {:available-liquidity 1000 :alice-requested 500
                :bob-requested 500 :carol-requested 400 :mechanism "fcfs"}})

(def ^:private withdrawal-fcfs-2
  {:experiment "withdrawal-constrained-liquidity.v1"
   :parameters {:available-liquidity 800 :alice-requested 500
                :bob-requested 500 :carol-requested 400 :mechanism "fcfs"}})

(def ^:private insolvency
  {:experiment "insolvency-after-loss.v1"
   :parameters {:custody 1000 :recognized-loss 100}})

(deftest concurrent-different-experiments-do-not-cross-contaminate
  (let [a (run-async withdrawal-fcfs)
        b (run-async insolvency)
        ra @(:future a)
        rb @(:future b)]
    (is (= "completed" (str (:lab-run/status ra))))
    (is (= "completed" (str (:lab-run/status rb))))
    (let [id-a (:lab-run/id ra)
          id-b (:lab-run/id rb)
          roots-a (vals (get-in ra [:evidence :roots]))
          roots-b (vals (get-in rb [:evidence :roots]))]
      (is (not= id-a id-b))
      ;; No identity or root leakage in either direction.
      (is (not (some #(= id-b %) roots-a)))
      (is (not (some #(= id-a %) roots-b)))
      ;; Artifact files contain only their own run identity.
      (let [artifact-a (slurp (str runs-dir "/" id-a "/request.json.result.json"))
            artifact-b (slurp (str runs-dir "/" id-b "/request.json.result.json"))]
        (is (str/includes? artifact-a id-a))
        (is (str/includes? artifact-b id-b))
        (is (not (str/includes? artifact-a id-b)))
        (is (not (str/includes? artifact-b id-a)))))))

(deftest concurrent-same-experiment-different-inputs-stay-distinct
  (let [a (run-async withdrawal-fcfs)
        b (run-async withdrawal-fcfs-2)
        ra @(:future a)
        rb @(:future b)]
    (is (= "completed" (str (:lab-run/status ra))))
    (is (= "completed" (str (:lab-run/status rb))))
    (let [root-a (get-in ra [:evidence :roots :withdrawal-root])
          root-b (get-in rb [:evidence :roots :withdrawal-root])]
      (is root-a)
      (is root-b)
      (is (not= root-a root-b)))))

(deftest determinism-under-concurrency
  (let [runs (mapv (fn [_] (run-async withdrawal-fcfs)) [1 2])
        results (mapv (comp deref :future) runs)]
    (is (every? #(= "completed" (str (:lab-run/status %))) results))
    (is (= (get-in (nth results 0) [:evidence :roots :withdrawal-root])
           (get-in (nth results 1) [:evidence :roots :withdrawal-root])))
    (is (= (get-in (nth results 0) [:inputs/hash])
           (get-in (nth results 1) [:inputs/hash])))))

(deftest killed-run-does-not-harm-concurrent-or-followup-runs
  (let [killer (runner/generate-run-id)
        ;; timeout 1ms: JVM startup alone exceeds this, so the subprocess is
        ;; destroyed mid-run.
        killed-future (future
                        (exec/run-experiment! withdrawal-fcfs killer
                                              {:runs-dir runs-dir
                                               :timeout-ms 1}))
        ;; A healthy run starts while the other is being killed.
        healthy (run-async insolvency)
        killed @killed-future
        healthy-result @(:future healthy)]
    (is (re-find #"execution-error" (str (:lab-run/status killed))))
    (is (str/includes? (get-in killed [:lab-run/error :message]) "timed out"))
    (is (= "completed" (str (:lab-run/status healthy-result))))
    (is (seq (get-in healthy-result [:evidence :roots])))
    ;; The service remains usable afterwards.
    (let [followup (exec/run-experiment! withdrawal-fcfs
                                         (runner/generate-run-id)
                                         {:runs-dir runs-dir
                                          :timeout-ms 120000})]
      (is (= "completed" (str (:lab-run/status followup)))))))
