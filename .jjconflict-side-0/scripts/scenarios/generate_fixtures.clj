(ns scripts.scenarios.generate-fixtures
  "One-shot fixture generation for the new S114 and S115 scenarios.
   Creates .trace.json and .report.edn files in data/fixtures/traces/ and /golden/."
  (:require [clojure.data.json :as json]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.contract-model.replay :as replay]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]))

(defn- replay->golden-report
  "Build a minimal golden report from a replay result."
  [suite-id trace-id result]
  (let [last-entry (last (:trace result))
        proj (:projection last-entry)
        metrics (get-in result [:metrics] {})]
    {:golden-schema-version "1.0"
     :suite-id (keyword suite-id)
     :trace-id trace-id
     :final-state-hash (hash (select-keys (or proj {}) [:metrics :trace-summary]))
     :metrics {:total-escrows (get metrics :total-escrows 0)
               :disputes-triggered (get metrics :disputes-triggered 0)
               :resolutions-executed (get metrics :resolutions-executed 0)
               :reverts (get metrics :reverts 0)
               :funds-lost (get metrics :funds-lost 0)}
     :outcome (:outcome result)}))

(defn generate!
  "Run the two new scenarios, write .trace.json and .report.edn."
  []
  (doseq [[scenario-path trace-id suite-id]
          [["scenarios/edn/S114_withdraw-fees-governance.edn"
            "s114-withdraw-fees-governance" :suites/forking-strategist]
           ["scenarios/edn/S115_claim-deferred-yield-recovery.edn"
            "s115-claim-deferred-yield-recovery" :suites/withdrawals]]]
    (let [scenario (io-sc/load-scenario-file scenario-path)
          result   (replay/replay-with-protocol sew/protocol scenario)
          trace    (:trace result)

          ;; Build a serialisable trace projection
          trace-entries (mapv (fn [entry]
                                {:seq (:seq entry)
                                 :action (:action entry)
                                 :time (:time entry)
                                 :agent (:agent entry)
                                 :result (:result entry)
                                 :error (:error entry)
                                 :params (:params entry)
                                 :projection (:projection entry)})
                              trace)

          full-output {:scenario-id (:scenario-id scenario)
                       :schema-version "1.0"
                       :outcome (:outcome result)
                       :events-processed (:events-processed result)
                       :halt-reason (:halt-reason result)
                       :trace trace-entries}

          trace-path  (str "data/fixtures/traces/" trace-id ".trace.json")
          golden-path (str "data/fixtures/golden/" trace-id ".report.edn")
          report      (replay->golden-report suite-id trace-id result)]

      ;; Write trace JSON
      (spit trace-path
            (json/write-str full-output :escape-unicode false :escape-slash false))
      (println (str "Wrote " trace-path))

      ;; Write golden report EDN
      (spit golden-path
            (with-out-str (clojure.pprint/pprint report)))
      (println (str "Wrote " golden-path))

      (flush))))

;; Run
(generate!)
