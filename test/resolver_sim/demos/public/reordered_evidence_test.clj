(ns resolver-sim.demos.public.reordered-evidence-test
  "Tests for the reordered-evidence public-demo projection (public-demo.v1).

   Pins that the projected verdicts and evidence match the executable
   reorder-chain demo, that the projection is deterministic, and that it fails
   closed on missing evidence."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [resolver-sim.demos.reorder-chain.demo :as demo]
            [resolver-sim.demos.public.reordered-evidence :as projection]))

(deftest artifact-identifies-itself
  (let [p (projection/project)]
    (is (= "public-demo.v1" (get p "schema")))
    (is (= "reordered-evidence" (get-in p ["demo" "id"])))
    (is (= 1 (get-in p ["demo" "version"])))
    (is (seq (get-in p ["demo" "question"])))))

(deftest verdicts-match-the-executable-model
  (let [p (projection/project)
        m (demo/run)]
    (is (= (get-in m [:demo/baseline :admitted?])
           (get-in p ["baseline" "admitted"])))
    (is (= (get-in m [:demo/outcome :admitted?])
           (get-in p ["outcome" "admitted"])))
    (is (= (map #(if (keyword? %) (name %) (name (:reason %)))
                (get-in m [:demo/outcome :failed-checks]))
           (get-in p ["outcome" "failed-checks"])))
    (is (= (get-in m [:demo/evidence :committed-hash])
           (get-in p ["evidence" "committed-hash"])))))

(deftest evidence-lines-are-verbatim
  (let [p (projection/project)
        m (demo/run)]
    (is (= (mapv (fn [[label value]] [label (str value)])
                 (get-in m [:demo/evidence :lines]))
           (get-in p ["evidence" "lines"])))
    (is (= (count (get-in m [:demo/evidence :after/checks]))
           (count (get-in p ["evidence" "checks"]))))
    (is (every? #(contains? % "status") (get-in p ["evidence" "checks"])))))

(deftest projection-is-deterministic
  (is (= (projection/json-str) (projection/json-str)))
  (is (= (projection/json-str)
         (str (json/write-str (projection/project)) "\n"))))

(deftest missing-evidence-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (#'projection/require-field!
                (dissoc (demo/run) :demo/evidence)
                [:demo/evidence]))))

(deftest provenance-binds-to-the-executable-result
  (let [p (projection/project)
        source (get p "source")]
    (is (= (get-in p ["evidence" "committed-hash"])
           (get source "result-root")))
    (is (= (get-in p ["evidence" "input-root"])
           (get source "input-root")))))
