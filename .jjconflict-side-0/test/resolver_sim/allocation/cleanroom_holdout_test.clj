(ns resolver-sim.allocation.cleanroom-holdout-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.allocation.cleanroom-holdout :as sut]))

(def holdout-path
  "etc/conformance/cleanroom/realized-partial-fill-v1/holdout-inputs/holdout-67.input.json")

(deftest fixed-holdout-regenerates-identically
  (let [raw (slurp holdout-path)
        first-output (sut/expected-output raw)
        second-output (sut/expected-output raw)]
    (is (= first-output second-output))
    (is (= (:expected-output/root first-output)
           (sut/expected-output-root (dissoc first-output :expected-output/root))))
    (is (= "realized-partial-fill-holdout-expected-output.v1"
           (get first-output :schema-version)))
    (is (= "sha256:4b3d7faebdc8a77689c4019131f5fb6015687f6ab4b96a5fefefd9a419e4b438"
           (:input/sha256 first-output)))
    (is (every? string? (vals (:canonical-bytes first-output))))))

(deftest fixed-holdout-set-regenerates-its-sealed-commitment
  (let [inputs {"holdout-67.expected.edn" "holdout-67.input.json"
                "holdout-99.expected.edn" "holdout-99.input.json"
                "holdout-request-variant.expected.edn" "holdout-request-variant.input.json"}
        outputs (into {}
                      (map (fn [[output input]]
                             [output (sut/expected-output
                                      (slurp (str "etc/conformance/cleanroom/realized-partial-fill-v1/holdout-inputs/" input)))])
                           inputs))]
    (is (= "8a8f4475bf0a49ab52eddb67912fd6a2cd8d5f3ce73f7b3241593ba762eaf1f9"
           (sut/expected-output-set-commitment outputs)))))
