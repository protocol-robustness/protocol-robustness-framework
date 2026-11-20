(ns resolver-sim.research.challenge-001-demo-test-runner
  "One-command local validation, isolating each expensive process suite in a JVM."
  (:require [clojure.string :as str]))

(def ^:private unit-test-namespaces
  '[resolver-sim.research.shortfall-pilot-test
    resolver-sim.research.researcher-challenges-devnet-test
    resolver-sim.research.researcher-challenges-test
    resolver-sim.research.shortfall-publication-test
    resolver-sim.economics.with-bounty.obligation-settlement-test])

(def ^:private process-test-vars
  [["resolver-sim.research.researcher-challenges-process-test"
    "challenge-001-v2-profile-reconstructs-in-a-fresh-process"]
   ["resolver-sim.research.researcher-challenges-process-test"
    "separate-participant-operator-and-observer-processes"]
   ["resolver-sim.research.researcher-challenges-process-test"
    "controlled-publication-trust-state-process-kill-boundaries"]
   ["resolver-sim.research.researcher-challenges-process-test"
    "arithmetic-gated-controlled-shortfall-admission-process-kill-boundaries"]
   ["resolver-sim.research.researcher-challenges-process-test"
    "controlled-admission-literal-process-kill-at-durable-write-boundaries"]])

(defn- unit-test-code []
  (str "(require 'clojure.test " (str/join " " (map #(str "'" %) unit-test-namespaces)) ") "
       "(let [r (apply clojure.test/run-tests '" unit-test-namespaces ")] "
       "(println \"CHALLENGE-TEST-COUNTS\" (:test r) (:pass r) (:fail r) (:error r)) "
       "(when (pos? (+ (:fail r) (:error r))) (System/exit 1)))"))

(defn- process-test-code [[namespace test-name]]
  (str "(require 'clojure.test '" namespace ") "
       "(let [c (ref {:test 0 :pass 0 :fail 0 :error 0})] "
       "(binding [clojure.test/*report-counters* c] "
       "(clojure.test/test-vars [(var " namespace "/" test-name ")])) "
       "(println \"CHALLENGE-TEST-COUNTS\" (:test @c) (:pass @c) (:fail @c) (:error @c)) "
       "(when (pos? (+ (:fail @c) (:error @c))) (System/exit 1)))"))

(defn- run-child! [label code]
  (println (str "Running isolated suite: " label))
  (flush)
  (let [java (str (System/getProperty "java.home") "/bin/java")
        classpath (System/getProperty "java.class.path")
        command [java "-cp" classpath "clojure.main" "-e" code]
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit (.waitFor process)
        counts (some (fn [line]
                       (when (str/starts-with? line "CHALLENGE-TEST-COUNTS ")
                         (mapv #(Long/parseLong %) (rest (re-matches #"CHALLENGE-TEST-COUNTS ([0-9]+) ([0-9]+) ([0-9]+) ([0-9]+)" line)))))
                     (str/split-lines output))]
    (print output)
    (flush)
    {:exit exit :counts counts :label label}))

(defn -main [& _]
  (let [results (into [(run-child! "unit namespaces" (unit-test-code))]
                      (map (fn [test-var]
                             (run-child! (str/join "/" test-var)
                                         (process-test-code test-var)))
                           process-test-vars))
        totals (reduce (fn [acc {:keys [counts]}]
                         (if counts (mapv + acc counts) acc))
                       [0 0 0 0] results)
        failures (filter #(not= 0 (:exit %)) results)
        [test-count assertion-count failure-count error-count] totals]
    (println (str "Challenge 001 demo validation: " test-count " tests, "
                  assertion-count " assertions, " failure-count " failures, "
                  error-count " errors."))
    (when (seq failures)
      (binding [*out* *err*]
        (println "Failed isolated suites:" (str/join ", " (map :label failures))))
      (System/exit 1))))
