(ns resolver-sim.conformance.release-test
  "G9b: the committed conformance release artifact is reproducible — a FRESH
   process (clean production registry) regenerating it derives the same release
   root, and the release binds the registry, corpus, vectors, and verifier
   roots.  Reproducibility is asserted in a subprocess so in-suite registry
   pollution cannot mask drift."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(deftest committed-release-root-is-reproducible
  (let [committed (json/read-str (slurp "etc/conformance/release.v1.edn") :key-fn keyword)
        {:keys [exit out]} (shell/sh "clojure"
                                     "-M:test:with-sew"
                                     "-e" "(require 'scripts.gen-release)(println (:release/root scripts.gen-release/release-with-root))")
        recomputed (some->> (str/split-lines out) (last) (str/trim))]
    (is (zero? exit) out)
    (is (some? recomputed))
    (is (= (:release/root committed) recomputed)
        (str "committed " (:release/root committed) " != recomputed " recomputed))
    (is (= (:release/id committed) (name (:release/id committed))))
    (is (= "569918738a7a48439d17c73ffdb505d437ea8e4769438c560d7408694f2d09ac"
           (:registry/root committed)))))

(deftest release-binds-surfaces
  (let [committed (json/read-str (slurp "etc/conformance/release.v1.edn") :key-fn keyword)]
    (is (some? (:corpus/root committed)))
    (is (some? (:vectors/root committed)))
    (is (some? (:profiles/root committed)))
    (is (some? (:schemas/root committed)))
    (is (some? (:issues/root committed)))
    (is (some? (get-in committed [:verifiers :python])))
    (is (some? (get-in committed [:verifiers :clojure])))
    (is (= "canonical-json-sha256.v1" (:canonicalisation/version committed)))))
