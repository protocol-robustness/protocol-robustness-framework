(ns prf.canonical.reference-test
  (:require [clojure.test :refer [deftest is testing]]
            [prf.canonical.reference :as reference]))

(deftest canonical-sha256-reference-round-trip
  (let [digest (apply str (repeat 64 "a"))
        ref (str "sha256:" digest)]
    (is (= ref (reference/sha256-ref digest)))
    (is (= ref (reference/sha256-ref ref)))
    (is (= digest (reference/parse-sha256-ref ref)))
    (is (reference/valid-sha256-ref? ref)))
  (testing "noncanonical encodings are not silently normalized"
    (doseq [invalid [nil "A" (str "sha256:" (apply str (repeat 64 "A")))
                     (str "sha256:" (apply str (repeat 63 "a")))]]
      (is (not (reference/valid-sha256-ref? invalid)))
      (is (nil? (reference/parse-sha256-ref invalid)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (reference/sha256-ref invalid))))))
