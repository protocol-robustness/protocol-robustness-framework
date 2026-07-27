(ns scripts.gen-fixtures
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.assurance.trust-sequence-definition :as tsd]
            [resolver-sim.assurance.procedure-execution-witness :as pew]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.evidence.chain :as chain]))

(def json-key (fn [k] (if (keyword? k) (if-let [ns (namespace k)] (str ns "/" (name k)) (name k)) (str k))))

(defn build [td wrong-id]
  (let [ev-dir (str td "/event-evidence")
        src (edn/read-string (slurp "data/sequences/force-authorised-custody-adjustment.edn"))
        defn (tsd/build-definition {:id (:trust-sequence-definition/id src) :provider (:trust-sequence-definition/provider src) :steps (:trust-sequence-definition/steps src)})
        def-root (:trust-sequence-definition/root defn)
        correct-id "fa-fixture-0"
        planned-id (or wrong-id correct-id)]
    (doseq [d [td (str td "/benchmark/assertions") (str td "/manifest") ev-dir]] (.mkdirs (io/file d)))
    (letfn [(mk [type s prev extra before after]
              (let [raw {:evidence/type type :scenario/id "fixture" :world/before-hash before :world/after-hash after
                         :inputs (merge {:force-auth/auth-id correct-id} extra)
                         :evidence/chain-hash-scheme "link-v1" :evidence/chain-seq s :evidence/chain-prev-hash prev}
                    eh (hc/hash-with-intent {:hash/intent :evidence-content} (dissoc raw :evidence/chain-hash-scheme :evidence/chain-seq :evidence/chain-prev-hash))
                    ch (chain/chain-link-hash eh s prev)]
                (assoc raw :evidence/hash eh :evidence/chain-self-hash ch)))]
      (let [ev1 (mk "force-authorisation-granted" 1 nil {:force-auth/workflow-id "wf"} "0x00" "0x01")
            ev2 (mk "force-authorisation-executed" 2 (:evidence/chain-self-hash ev1) {:force-auth/workflow-id "wf"} "0x01" "0x02")
            ev3 (mk "escrow-released" 3 (:evidence/chain-self-hash ev2) {:finalize/workflow-id "wf" :finalize/authorization-id correct-id} "0x02" "0x03")
            evs [ev1 ev2 ev3]]
        (doseq [ev evs] (spit (io/file ev-dir (str "ev-" (:evidence/chain-seq ev) ".json")) (json/write-str ev {:key-fn json-key})))
        (let [arts (mapv (fn [ev] {:id (str "ev-" (subs (:evidence/hash ev) 0 12)) :kind "transition-evidence" :evidence-hash (:evidence/hash ev)}) evs)
              rbase {:schema-version "evidence-registry.v1" :run-id "fixture" :generated-at "2025-06-01T00:00:00Z" :evidence-count 3 :evidence-hashes (mapv :evidence/hash evs) :artifacts arts}
              registry (assoc rbase :registry-hash (hc/hash-with-intent {:hash/intent :registry} rbase))
              cursor {:cursor/scope :targeted-evidence :cursor/final-seq 3 :cursor/final-self-hash (:evidence/chain-self-hash ev3) :cursor/total-captured 3}]
          (spit (io/file ev-dir "evidence-registry.json") (json/write-str registry {:key-fn json-key}))
          (spit (io/file ev-dir "chain-cursor-final.json") (json/write-str cursor {:key-fn json-key}))
          (spit (io/file td "benchmark/definition.edn") (pr-str {:benchmark/id :benchmark/force-authorisation-custody-v1 :benchmark/protocol :protocol/sew :benchmark/trust-sequence-definition-root def-root :benchmark/expected-correlation-id planned-id}))
          (spit (io/file td "benchmark/execution-plan.edn") (pr-str {:schema_version "benchmark-execution-plan.v1" :benchmark/id :benchmark/force-authorisation-custody-v1 :executions [] :trust-sequence-definition-root def-root :expected-correlation-id planned-id}))
          (spit (io/file td "benchmark/index.edn") (pr-str {:executions [{:dir ev-dir :artifacts {:evidence-registry {:path (str ev-dir "/evidence-registry.json")} :chain-cursor {:path (str ev-dir "/chain-cursor-final.json")}}}]}))
          (let [witness (pew/build-witness {:id "fixture-witness" :definition-root def-root :initial-input-root "0x00" :step-bindings [{:step/id :prf.step/authorisation-granted :evidence ev1} {:step/id :prf.step/authorised-execution :evidence ev2} {:step/id :prf.step/authorised-consumption-custody-adjustment :evidence ev3}] :result-root "0x03"})]
            (spit (io/file td "manifest/execution-witness.json") (json/write-str witness {:key-fn json-key}))
            (spit (io/file td "benchmark/assertions/canonical-integrity.json") (json/write-str {"status" (if wrong-id "failed" "passed") "schema_version" "canonical-integrity.v1"}))
            (spit (io/file td "completion.json") (json/write-str {"schema_version" "benchmark-completion.v1" "run_id" "fixture" "lifecycle_status" "completed"}))))
      (println "Built:" td))))

(defn -main [& args]
  (build "test/fixtures/trust-boundary/valid" nil)
  (build "test/fixtures/trust-boundary/complete-but-unassured" "wrong-planned-id")
  (println "Done."))
