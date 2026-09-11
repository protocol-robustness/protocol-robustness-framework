(ns resolver-sim.commands.compare-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.commands.compare :as compare]))

(defn- tmp-file
  [content ext]
  (let [f (doto (java.io.File/createTempFile "compare-cmd" ext)
            (.deleteOnExit))]
    (spit f content)
    (.getPath f)))

(deftest equal-files-exit-zero
  (let [a (tmp-file "{\"a\": 1}" ".json")
        b (tmp-file "{\"a\": 1}" ".json")
        out (with-out-str
              (let [{:keys [exit-code message]}
                    (compare/run {:cmd/args [a b]})]
                (is (zero? exit-code))
                (is (= "canonically equivalent" message))))]
    (is (re-find #"equivalent: true" out))))

(deftest differing-files-exit-one
  (let [a (tmp-file "{\"a\": 1}" ".json")
        b (tmp-file "{\"a\": 2}" ".json")
        out (with-out-str
              (let [{:keys [exit-code message]}
                    (compare/run {:cmd/args [a b]})]
                (is (= 1 exit-code))
                (is (= "not canonically equivalent" message))))]
    (is (re-find #"equivalent: false" out))
    (is (re-find #"divergence" out))))

(deftest missing-arg-usage
  (let [{:keys [exit-code]} (compare/run {:cmd/args ["only-one"]})]
    (is (= 2 exit-code))))

(deftest missing-file-error
  (let [{:keys [exit-code]} (compare/run {:cmd/args ["/nonexistent/a.json"
                                                     "/nonexistent/b.json"]})]
    (is (= 1 exit-code))))

(deftest json-output-mode
  (let [a (tmp-file "{\"a\": 1}" ".json")
        b (tmp-file "{\"a\": 2}" ".json")
        out (with-out-str
              (compare/run {:cmd/args [a b] :json? true}))]
    (is (re-find #"\"equivalent\?\": false" out))
    (is (re-find #"canonical-comparison.v1" out))))

(deftest reordered-json-is-equivalent
  (let [a (tmp-file "{\"a\": 1, \"b\": [1, 2]}" ".json")
        b (tmp-file "{\"b\": [1, 2], \"a\": 1}" ".json")]
    (is (zero? (:exit-code (compare/run {:cmd/args [a b]}))))))
