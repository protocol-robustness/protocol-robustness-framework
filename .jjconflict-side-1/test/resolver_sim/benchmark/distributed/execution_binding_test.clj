(ns resolver-sim.benchmark.distributed.execution-binding-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.distributed.execution-binding :as sut]))

(defn- root [digit]
  (str "sha256:" (apply str (repeat 64 digit))))

(deftest binding-commits-only-plan-and-distribution-roots
  (let [binding (sut/build-binding {:execution-plan/root (root "a")
                                    :executable-distribution/root (root "b")
                                    :local-key/id "worker-a"
                                    :execution/id "ignored"})]
    (is (= #{:execution-binding/schema
             :execution-plan/root
             :executable-distribution/root
             :execution-binding/root}
           (set (keys binding))))
    (is (= "benchmark-execution-binding.v1"
           (:execution-binding/schema binding)))
    (is (= #{:execution-plan/root :executable-distribution/root}
           sut/required-fields))
    (is (sut/verify-binding binding))))

(deftest binding-root-commits-both-roots
  (let [distribution-root (root "b")
        binding (sut/build-binding {:execution-plan/root (root "a")
                                    :executable-distribution/root distribution-root})]
    (is (not= (:execution-binding/root binding)
              (:execution-binding/root
               (sut/build-binding {:execution-plan/root (root "c")
                                   :executable-distribution/root distribution-root}))))
    (is (not= (:execution-binding/root binding)
              (:execution-binding/root
               (sut/build-binding {:execution-plan/root (root "a")
                                   :executable-distribution/root (root "d")}))))))

(deftest invalid-roots-are-rejected
  (testing "both roots are required SHA-256 references"
    (doseq [binding [{:execution-plan/root "not-a-root"
                      :executable-distribution/root (root "b")}
                     {:execution-plan/root (root "a")
                      :executable-distribution/root "not-a-root"}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/build-binding binding))))))

(deftest tampering-fails-verification
  (let [binding (sut/build-binding {:execution-plan/root (root "a")
                                    :executable-distribution/root (root "b")})]
    (is (not (sut/verify-binding
              (assoc binding :execution-plan/root (root "c")))))))
