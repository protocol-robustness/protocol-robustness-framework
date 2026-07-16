(ns resolver-sim.suite.execution-plan-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.suite.execution-plan :as plan]))

(def suite-definition
  {:scenario-ids ["S-1" "S-2"] :protocol-id "sew-v1" :kind :file-path-suite})

(def entries
  [{:execution/id "sha256:one" :execution/directory "exec-0001-aaaaaaaaaaaaaaaa"}
   {:execution/id "sha256:two" :execution/directory "exec-0002-bbbbbbbbbbbbbbbb"}])

(deftest suite-definition-hash-is-stable-and-suite-scoped
  (is (= (plan/suite-definition-hash :example suite-definition)
         (plan/suite-definition-hash :example suite-definition)))
  (is (not= (plan/suite-definition-hash :example suite-definition)
            (plan/suite-definition-hash :other suite-definition))))

(deftest plan-rejects-ambiguous-child-identity-or-directory
  (is (= entries (plan/validate-plan! entries)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"duplicate execution IDs"
                        (plan/validate-plan! (conj entries (assoc (second entries)
                                                               :execution/directory "exec-0003-cccccccccccccccc")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"directory collisions"
                        (plan/validate-plan! (conj entries {:execution/id "sha256:three"
                                                           :execution/directory (:execution/directory (second entries))})))))
