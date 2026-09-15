(ns resolver-sim.commands.evidence-cli-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private spec
  {:artifact-kind :transition
   :before {:balance 1}
   :after {:balance 2}
   :action {:action/type :increment}
   :result {:status :ok}
   :attribution {:ctx/run-id "run-1"}})

(defn- invoke [args input]
  (with-in-str input
    (with-out-str
      (dispatch/run args))))

(deftest evidence-make-emits-one-edn-record
  (let [output (invoke ["evidence" "make"] (pr-str spec))
        record (read-string output)]
    (is (= "evidence-record.v1" (:schema-version record)))
    (is (string? (:evidence-hash record)))))

(deftest evidence-make-json-is-equivalent-to-edn
  (let [edn-record (read-string (invoke ["evidence" "make"] (pr-str spec)))
        json-record (json/read-str (invoke ["evidence" "make" "--json"]
                                           (json/write-str spec))
                                   :key-fn keyword)]
    (is (= (:evidence-hash edn-record) (:evidence-hash json-record)))
    (is (= (hc/project-committable-content edn-record)
           (hc/project-committable-content json-record)))))

(deftest evidence-make-rejects-unknown-fields
  (let [exit-code (with-in-str (pr-str (assoc spec :typo true))
                    (dispatch/run ["evidence" "make"]))]
    (is (not= 0 exit-code))))
