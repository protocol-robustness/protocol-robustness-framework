(ns resolver-sim.commands.benchmark-list
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.config.paths :as paths]))

(defn- load-index
  "Read benchmarks/registry.edn, walk the pack hierarchy, return flat benchmark list."
  []
  (if-let [registry (try (rp/edn-read rp/canonical-registry-path)
                         (catch Exception _ nil))]
    (let [packs (:packs registry)]
      {:benchmarks
       (mapcat (fn [pack]
                 (let [pack-id (:pack/id pack)
                       pack-reg-path (rp/pack-registry-path (:pack/registry pack))
                       pack-reg (try (rp/edn-read pack-reg-path) (catch Exception _ nil))
                       pack-name (name pack-id)]
                   (if pack-reg
                     (mapv (fn [b]
                             (let [bench-path (rp/relative-to pack-reg-path (:benchmark/file b))
                                   bm-id (str pack-name "/" (name (:benchmark/id b)))
                                   manifest (try (rp/edn-read bench-path) (catch Exception _ {}))]
                               {:id bm-id
                                :description (or (:benchmark/description manifest)
                                                 (-> (:benchmark/id b) name (str/replace "-" " ")))
                                :status (:benchmark/status b)
                                :claims (count (:benchmark/claims manifest))
                                :manifest bench-path}))
                           (:benchmarks pack-reg))
                     [])))
               packs)})
    (do (println (str (paths/benchmarks-registry) " not found"))
        {:benchmarks []})))

(defn list-benchmarks
  [{:keys [json?]}]
  (let [{benchmarks :benchmarks} (load-index)]
    (if (empty? benchmarks)
      (do (println "No benchmarks found in registry")
          {:exit-code 0 :message "No benchmarks"})
      (let [display (mapv (fn [b]
                            {:id (:id b)
                             :status (:status b)
                             :claims (:claims b)
                             :description (:description b)})
                          benchmarks)]
        (if json?
          (do (require 'clojure.data.json)
              (println ((resolve 'clojure.data.json/write-str) {:benchmarks display} :indent true)))
          (do (printf "Available benchmarks: %d\n\n" (count display))
              (pp/print-table [:id :status :claims :description] display)))
        {:exit-code 0 :message (str (count benchmarks) " benchmarks")}))))
