(ns resolver-sim.assurance.checkpoint-verification-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]))

(deftest historical-july-checkpoint-is-independent-of-worktree
  (let [{:keys [exit out err]}
        (shell/sh "bb" "assurance:verify-checkpoint"
                  "sew-v1x-appeal-bond-correction-2026-07-31")]
    (is (zero? exit) (str out "\n" err))
    (is (.contains out "PROVENANCE-INCOMPLETE"))
    (is (.contains out "scenario-count 7"))))

(deftest worktree-drift-is-reported-separately
  (let [{:keys [exit out err]}
        (shell/sh "bb" "assurance:check-worktree"
                  "sew-v1x-appeal-bond-correction-2026-07-31")]
    (is (not (zero? exit)) (str out "\n" err))
    (is (.contains out "MISMATCH"))
    (is (.contains out "DR-C-002-slash-appeal-bond-lifecycle.edn"))))

(deftest deployment-checkpoint-is-not-unqualified-pass
  (let [{:keys [exit out err]}
        (shell/sh "bb" "assurance:verify-checkpoint"
                  "sew-v1x-deployment-era")]
    (is (zero? exit) (str out "\n" err))
    (is (.contains out "PROVENANCE-INCOMPLETE"))
    (is (not (.contains out "VALID")))))
