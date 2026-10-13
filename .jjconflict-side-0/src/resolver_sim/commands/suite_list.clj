(ns resolver-sim.commands.suite-list
  (:require [clojure.pprint :as pp]
            [resolver-sim.io.scenario-runner :as sr]))

(defn list-suites
  [{:keys [json?]}]
  (let [summaries (sr/known-suite-summaries)]
    (if (empty? summaries)
      (do (println "No suites registered")
          {:exit-code 0 :message "No suites"})
      (let [display (mapv (fn [s]
                            {:suite/key (:suite/key s)
                             :suite/type (:suite/type s)
                             :suite/protocols (first (:suite/protocols s))
                             :suite/scenario-count (:suite/scenario-count s)
                             :suite/title (:suite/title s)})
                          summaries)]
        (if json?
          (do (require 'clojure.data.json)
              (println ((resolve 'clojure.data.json/write-str) {:suites display} :indent true)))
          (do (printf "Available suites: %d\n\n" (count display))
              (pp/print-table [:suite/key :suite/type :suite/protocols
                               :suite/scenario-count :suite/title]
                              display)))
        {:exit-code 0 :message (str (count summaries) " suites")}))))
