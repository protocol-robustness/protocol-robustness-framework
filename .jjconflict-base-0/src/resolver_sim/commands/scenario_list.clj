(ns resolver-sim.commands.scenario-list
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.config.paths :as paths]))

(defn- read-catalog
  []
  (let [catalog-file (paths/scenarios-catalog)]
    (when-let [path (rp/resolve-path catalog-file)]
      (try (edn/read-string (slurp path))
           (catch Exception _ nil)))))

(defn list-scenarios
  [{:keys [protocol search json?]}]
  (let [catalog (read-catalog)
        all (if catalog
              (:scenarios catalog)
              [])]
    (if (empty? all)
      (do (println "No scenario catalog found at scenarios/catalog.edn")
          {:exit-code 0 :message "No scenarios"})
      (let [filtered (filter (fn [s]
                               (and (or (nil? protocol)
                                        (= protocol (:scenario/protocol s)))
                                    (or (nil? search)
                                        (str/includes?
                                         (str/lower-case (:scenario/id s))
                                         (str/lower-case search)))))
                             all)
            display (mapv (fn [s]
                            {:scenario/id (:scenario/id s)
                             :protocol (:scenario/protocol s)
                             :purpose (:scenario/purpose s)
                             :claim (:scenario/claim-id s)})
                          filtered)]
        (if json?
          (do (require 'clojure.data.json)
              (println ((resolve 'clojure.data.json/write-str) {:scenarios display} :indent true)))
          (do (printf "Available scenarios: %d\n\n" (count filtered))
              (pp/print-table [:scenario/id :protocol :purpose :claim]
                              (take 100 display))
              (when (> (count filtered) 100)
                (printf "\n... and %d more (use --search or --format json)\n"
                        (- (count filtered) 100)))))
        {:exit-code 0 :message (str (count filtered) " scenarios")}))))
