(ns resolver-sim.demos.public.liquidity-shortfall-test
  "Tests for the liquidity-shortfall public-demo projection (public-demo.v1).

   The projection is the only boundary between PRF and the product site. These
   tests pin that every allocation fact is copied verbatim from the executable
   model and never recomputed, and that the projection is deterministic and
   fails closed."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.demos.liquidity-shortfall.demo :as demo]
            [resolver-sim.demos.public.liquidity-shortfall :as projection]))

(deftest artifact-identifies-itself
  (let [p (projection/project)]
    (is (= "public-demo.v1" (get p "schema")))
    (is (= "liquidity-shortfall" (get-in p ["demo" "id"])))
    (is (= 1 (get-in p ["demo" "version"])))
    (is (seq (get-in p ["demo" "question"])))))

(deftest allocation-facts-match-the-executable-model
  (let [p (projection/project)
        m (demo/run)
        by-id (into {} (map (juxt :request/id identity)) (:demo/requests m))]
    (testing "pool"
      (is (= (:available (:demo/pool m))
             (get-in p ["scenario" "pool" "available"])))
      (is (= (:requested (:demo/pool m))
             (get-in p ["scenario" "pool" "requested"]))))
    (testing "per-request allocation copied verbatim"
      (doseq [[label r] [["alice" :alice] ["bob" :bob] ["cara" :cara]]]
        (let [row (first (filter #(= label (get % "id"))
                                 (get-in p ["scenario" "requests"])))]
          (is (= (:allocated (get by-id r)) (get row "allocated")) label)
          (is (= (:shortfall (get by-id r)) (get row "shortfall")) label))))
    (testing "conservation"
      (is (= (:holds? (:demo/conservation m))
             (get-in p ["conservation" "holds"]))))))

(deftest evidence-is-verbatim
  (let [p (projection/project)
        m (demo/run)]
    (is (= (get-in m [:demo/evidence :committed-hash])
           (get-in p ["evidence" "committed-hash"])))
    (is (= (get-in m [:demo/evidence :request/hash])
           (get-in p ["evidence" "request-hash"])))
    (is (= (mapv (fn [[label value]] [label (str value)])
                 (get-in m [:demo/evidence :lines]))
           (get-in p ["evidence" "lines"])))))

(deftest projection-is-deterministic
  (is (= (projection/json-str) (projection/json-str)))
  (is (= (projection/json-str)
         (str (json/write-str (projection/project)) "\n"))))

(deftest missing-evidence-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (#'projection/require-field!
                (dissoc (demo/run) :demo/pool)
                [:demo/pool]))))

(deftest provenance-binds-to-the-executable-result
  (let [p (projection/project)
        source (get p "source")]
    (is (= (get-in p ["evidence" "committed-hash"])
           (get source "result-root")))
    (is (= (get-in p ["evidence" "request-hash"])
           (get source "input-root")))))
