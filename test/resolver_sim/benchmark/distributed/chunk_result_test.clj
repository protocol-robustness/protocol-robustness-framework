(ns resolver-sim.benchmark.distributed.chunk-result-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.distributed.chunk-result :as sut]))

(defn- root [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(def manifest-input
  {:chunk/id "chunk-0001"
   :run-plan/root (root "a")
   :execution-plan/root (root "b")
   :chunk/input-root (root "c")
   :chunk/work-root (root "d")
   :executable-distribution/root (root "7")
   :chunk/execution-ids ["execution-1" "execution-2"]
   :chunk/staged-artifact-manifest-roots [(root "e") (root "f")]
   :chunk/result-root (root "0")
   :sensitivity/root (root "1")})

(deftest detached-chunk-result-is-closed-and-self-rooted
  (let [manifest (sut/build-manifest manifest-input)]
    (is (= sut/schema (:artifact/schema manifest)))
    (is (sut/verify-manifest manifest))
    (is (= manifest (sut/build-manifest manifest)))
    (is (= (:chunk/result-root manifest)
           (:chunk/result-root
            (sut/build-manifest (assoc manifest-input :chunk/result-root (root "2")))))
        "caller-supplied result root cannot influence the built artifact")
    (is (not (sut/verify-manifest (assoc manifest :chunk/result-root (root "2")))))
    (is (not (sut/verify-manifest (assoc manifest :extra/value true))))))

(deftest result-root-commits-ordered-aligned-execution-rows-and-identity
  (let [base (sut/build-manifest manifest-input)
        changed-row (sut/build-manifest
                     (assoc manifest-input :chunk/staged-artifact-manifest-roots [(root "e") (root "0")]))
        reordered (sut/build-manifest
                   (assoc manifest-input
                          :chunk/execution-ids ["execution-2" "execution-1"]
                          :chunk/staged-artifact-manifest-roots [(root "f") (root "e")]))]
    (is (not= (:chunk/result-root base) (:chunk/result-root changed-row)))
    (is (not= (:chunk/result-root base) (:chunk/result-root reordered)))
    (doseq [field [:chunk/id :run-plan/root :execution-plan/root :chunk/input-root :chunk/work-root]]
      (is (not= (:chunk/result-root base)
                (:chunk/result-root (sut/build-manifest (assoc manifest-input field
                                                               (if (= field :chunk/id) "chunk-0002" (root "2"))))))
          (str "result root commits " field)))))

(deftest detached-chunk-result-requires-ordered-aligned-staged-artifacts
  (testing "the manifest binds each execution to one staged artifact manifest"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-manifest
                  (assoc manifest-input :chunk/staged-artifact-manifest-roots [(root "e")]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-manifest
                  (assoc manifest-input :chunk/execution-ids ["execution-1" "execution-1"]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/build-manifest
                  (assoc manifest-input :chunk/input-root "not-a-root"))))))
