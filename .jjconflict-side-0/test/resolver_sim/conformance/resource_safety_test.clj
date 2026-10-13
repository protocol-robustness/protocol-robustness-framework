(ns resolver-sim.conformance.resource-safety-test
  "G9c resource safety: arbitrary external bundles must yield typed rejections,
   never crashes or partial verification."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.shell :as shell]
            [resolver-sim.conformance.json :as json-scan]))

(deftest duplicate-keys-detected
  (is (= "a" (json-scan/duplicate-json-key "{\"a\":1,\"a\":2}")))
  (is (= "x" (json-scan/duplicate-json-key "{\"a\":{\"x\":1,\"x\":2}}")))
  (is (nil? (json-scan/duplicate-json-key "{\"a\":1,\"b\":2}")))
  (is (nil? (json-scan/duplicate-json-key "[{\"a\":1},{\"a\":2}]"))))

(deftest excessive-nesting-rejected
  (let [deep (loop [n 200 s "{}"] (if (zero? n) s (recur (dec n) (str "{\"x\":" s "}"))))]
    (is (json-scan/nesting-too-deep? deep))
    (is (not (json-scan/nesting-too-deep? "{\"a\":{\"b\":1}}")))))

(deftest size-limit-typed
  (testing "CLI bundle verify on an oversized file yields a typed rejection"
    (let [huge (str "{\"pad\":\"" (apply str (repeat (* 11 1024 1024) \a)) "\"}")
          f (java.io.File/createTempFile "huge" ".json")
          _ (spit f huge)]
      (try
        (let [{:keys [exit out]} (shell/sh
                                  "clojure" "-M:conformance-cli" "bundle" "verify"
                                  (.getPath f))]
          (is (= 1 exit))
          (is (str/includes? out "bundle-too-large")))
        (finally (.delete f))))))
