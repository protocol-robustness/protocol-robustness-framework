(ns resolver-sim.conformance.release-test
  "G9b: the committed conformance release artifact is reproducible — the same
   committed files recompute the same release root, and the release binds the
   registry, corpus, vectors, and verifier roots."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [scripts.gen-release :as gr]))

(deftest committed-release-root-is-reproducible
  (let [committed (json/read-str (slurp "etc/conformance/release.v1.edn") :key-fn keyword)
        recomputed (:release/root gr/release-with-root)]
    (is (= (:release/root committed) recomputed)
        (str "committed " (:release/root committed) " != recomputed " recomputed))
    (is (= (:release/id committed) (name (:release/id gr/release-with-root))))
    (is (= (:registry/root committed) (:registry/root gr/release-with-root)))
    (is (= (:corpus/root committed) (:corpus/root gr/release-with-root)))
    (is (= (:vectors/root committed) (:vectors/root gr/release-with-root)))
    (is (= (get-in committed [:verifiers :python])
           (get-in gr/release-with-root [:verifiers :python])))))
