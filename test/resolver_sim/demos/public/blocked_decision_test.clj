(ns resolver-sim.demos.public.blocked-decision-test
  "Tests for the public-demo projection boundary (public-demo.v1).

   The projection is the only boundary between PRF and the product site, so it
   must never drift from the executable demo. These tests pin:

     - the artifact shape (schema, demo id/version);
     - that protocol facts (admitted?, failed checks, committed hash) are
       copied verbatim from the executable model, never recomputed;
     - that the projection is deterministic;
     - that required fields fail closed if absent."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.demos.not-admitted.demo :as demo]
            [resolver-sim.demos.public.blocked-decision :as projection]))

(deftest artifact-identifies-itself
  (let [p (projection/project)]
    (is (= "public-demo.v1" (get p "schema")))
    (is (= "blocked-decision" (get-in p ["demo" "id"])))
    (is (= 1 (get-in p ["demo" "version"])))
    (is (seq (get-in p ["demo" "question"])))))

(deftest protocol-facts-match-the-executable-model
  (let [p (projection/project)
        m (demo/run)]
    (is (= (get-in m [:demo/outcome :admitted?])
           (get-in p ["outcome" "admitted"])))
    (is (= (map name (get-in m [:demo/outcome :failed-checks]))
           (get-in p ["outcome" "failed-checks"])))
    (is (= (get-in m [:demo/evidence :committed-hash])
           (get-in p ["evidence" "committed-hash"])))
    (is (= (get-in m [:demo/baseline :admitted?])
           (get-in p ["baseline" "admitted"])))))

(deftest projected-evidence-is-verbatim
  (let [p (projection/project)
        m (demo/run)]
    (testing "evidence lines carry the executable roots"
      (is (= (mapv (fn [[label value]] [label (str value)])
                   (get-in m [:demo/evidence :lines]))
             (get-in p ["evidence" "lines"]))))
    (testing "every check appears with status"
      (is (= (count (get-in m [:demo/evidence :after/checks]))
             (count (get-in p ["evidence" "checks"]))))
      (is (every? #(contains? % "status") (get-in p ["evidence" "checks"]))))))

(deftest exactly-one-failing-check-is-projected
  (let [p (projection/project)
        checks (get-in p ["evidence" "checks"])
        failing (filter #(= "fail" (get % "status")) checks)]
    (is (= ["hash-integrity"] (mapv #(get % "id") failing)))
    (is (every? #(seq (get % "detail")) failing))))

(deftest projection-is-deterministic
  (let [a (projection/json-str)
        b (projection/json-str)]
    (is (= a b)))
  (is (= (projection/json-str)
         (str (json/write-str (projection/project)) "\n"))
      "json-str is the canonical serialisation of the projected map"))

(deftest source-points-at-the-deep-notebook
  (let [p (projection/project)
        source (get p "source")]
    (is (= "not_admitted" (get source "notebook"))
        "the deep-link targets the substantive evidence notebook")
    (is (= "demo_not_admitted" (get source "demo-notebook"))
        "the executable demo notebook is preserved as provenance")))

(deftest missing-evidence-fails-closed
  (testing "a missing required field throws rather than silently defaulting"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'projection/require-field!
                  (dissoc (demo/run) :demo/evidence)
                  [:demo/evidence])))))

(deftest provenance-binds-to-the-executable-result
  (let [p (projection/project)
        source (get p "source")]
    (is (= (get-in p ["evidence" "committed-hash"])
           (get source "result-root")))
    (is (= (get-in p ["evidence" "input-root"])
           (get source "input-root")))))
