(ns resolver-sim.io.scenario-fixture-sync
  "Sync invariant scenario maps to trace.json and public scenarios/edn/*.edn fixtures."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [resolver-sim.io.scenario-export :as export]
            [resolver-sim.io.scenarios :as sc]
            [resolver-sim.protocols.sew.invariant-scenarios :as scenarios]))

(defn- flatten-scenarios
  [entries]
  (mapcat (fn [[_display v]]
            (if (vector? v) v [v]))
          entries))

(defn all-invariant-scenario-maps
  []
  (flatten-scenarios scenarios/all-scenarios))

(defn- read-json-file [path]
  (when (.exists (io/file path))
    (json/read-str (slurp path) :key-fn keyword)))

(defn- normalize-expected-errors [errs]
  (vec (sort-by :seq
                (map (fn [e]
                       {:seq    (:seq e)
                        :action (keyword (:action e))
                        :error  (keyword (:error e))})
                     (or errs [])))))

(defn- contract-fields [scenario]
  {:expected-errors         (normalize-expected-errors (:expected-errors scenario))
   :strict-expected-errors? (boolean (:strict-expected-errors? scenario false))
   :allow-open-disputes?    (boolean (:allow-open-disputes? scenario false))})

(defn trace-contract-drift
  "Compare Clojure scenario contract fields to an on-disk trace.json map.
   Returns nil when aligned, else a drift map."
  [scenario trace-doc]
  (let [expected (contract-fields scenario)
        actual   {:expected-errors         (normalize-expected-errors (:expected-errors trace-doc))
                  :strict-expected-errors? (boolean (:strict-expected-errors? trace-doc false))
                  :allow-open-disputes?    (boolean (:allow-open-disputes? trace-doc false))}]
    (when (not= expected actual)
      {:scenario-id (:scenario-id scenario)
       :expected expected
       :actual   actual})))

(defn trace-path [scenario-id]
  (str "data/fixtures/traces/" scenario-id ".trace.json"))

(defn public-scenario-path [scenario-id]
  (some-> scenario-id export/scenario-id->public-json-filename
          (str sc/*scenario-dir* "/")))

(defn export-scenario-fixtures!
  [scenario & {:keys [write-public-json?]}]
  (export/export-scenario-files!
   scenario
   :write-public-json? (boolean write-public-json?)))

(defn sync-scenario!
  [scenario & opts]
  (apply export-scenario-fixtures! scenario opts))

(defn sync-all-with-traces!
  [& {:keys [write-public-json? only-scenario-ids]}]
  (let [scenarios (cond->> (all-invariant-scenario-maps)
                    (seq only-scenario-ids)
                    (filter #(contains? (set only-scenario-ids) (:scenario-id %))))
        results   (mapv (fn [s]
                          (let [sid (:scenario-id s)
                                tp  (trace-path sid)]
                            (if (.exists (io/file tp))
                              (do (sync-scenario! s :write-public-json? write-public-json?)
                                  {:scenario-id sid :synced true :trace-path tp})
                              {:scenario-id sid :synced false :reason :no-trace-file})))
                        scenarios)]
    {:synced (filter :synced results)
     :skipped (remove :synced results)}))

(defn collect-trace-contract-drifts
  []
  (vec (keep (fn [s]
               (let [tp (trace-path (:scenario-id s))]
                 (when (.exists (io/file tp))
                   (trace-contract-drift s (read-json-file tp)))))
             (all-invariant-scenario-maps))))

(defn collect-missing-trace-files
  [scenario-ids]
  (vec (filter #(not (.exists (io/file (trace-path %)))) scenario-ids)))