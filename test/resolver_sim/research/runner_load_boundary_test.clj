(ns resolver-sim.research.runner-load-boundary-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest generic-runner-path-defers-anvil-until-observed-evm-work
  (let [probe "(require 'resolver-sim.research.runner-transport)
               (require 'resolver-sim.research.observed-execution-work)
               (assert (not (contains? (loaded-libs) 'resolver-sim.research.anvil-executor)))
               (println :generic-runner-anvil-free)
               (requiring-resolve 'resolver-sim.research.anvil-executor/action-root)
               (assert (contains? (loaded-libs) 'resolver-sim.research.anvil-executor))
               (println :observed-work-loads-anvil-on-demand)"
        process (-> (ProcessBuilder. ^java.util.List
                                     ["clojure" "-M:test" "-e" probe])
                    (.directory (java.io.File. (System/getProperty "user.dir")))
                    (.redirectErrorStream true)
                    .start)
        output (slurp (.getInputStream process))
        finished? (.waitFor process 90 java.util.concurrent.TimeUnit/SECONDS)]
    (when-not finished? (.destroyForcibly process))
    (is finished? "isolated namespace-load probe should terminate")
    (is (zero? (.exitValue process)) output)
    (is (str/includes? output ":generic-runner-anvil-free") output)
    (is (str/includes? output ":observed-work-loads-anvil-on-demand") output)))
