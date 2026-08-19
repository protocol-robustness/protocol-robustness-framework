(ns resolver-sim.composition.command-lineage-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.composition.command-lineage :as cl]
            [resolver-sim.hash.reference :as hash-ref]))

(def s0 "sha256:0000000000000000000000000000000000000000000000000000000000000000")
(def s1 "sha256:1111111111111111111111111111111111111111111111111111111111111111")
(def s2 "sha256:2222222222222222222222222222222222222222222222222222222222222222")

(defn- build-cmd
  ([action in out] (build-cmd action in out [s0]))
  ([action in out shared-states]
   (let [members (mapv (fn [s] {:kind :shared-state :ref s}) shared-states)]
     (cl/build-command
      {:command/action action
       :command/input-state-root in
       :command/resulting-state-root out
       :command/built-with-includes members}))))

(defn- terminator-cmd
  [head]
  (cl/build-termination-command head s2))

(def ^:private cmd-a
  (build-cmd :action-a s0 s1 [s0]))

(def ^:private cmd-b
  (build-cmd :action-b s1 s2 [s1]))

(def ^:private cmd-c
  (build-cmd :action-c s0 s2 [s0]))

(def ^:private cmd-d
  (build-cmd :action-d s1 s0 [s1]))

;; ── Combination identity ────────────────────────────────────────────────────────

(deftest test-combination-same-refs-different-order
  (testing "same shared-state + same includes in different order => same combination identity"
    (let [a {:kind :shared-state :ref s0}
          b {:kind :command :ref "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
          c {:kind :command :ref "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
          root1 (cl/combination-root [a b c])
          root2 (cl/combination-root [c b a])
          root3 (cl/combination-root [b a c])]
      (is (= root1 root2 root3))))
  (testing "different member set => different combination identity"
    (let [root1 (cl/combination-root [{:kind :shared-state :ref s0}
                                      {:kind :command :ref "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}])
          root2 (cl/combination-root [{:kind :shared-state :ref s1}
                                      {:kind :command :ref "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}])]
      (is (not= root1 root2))))
  (testing "duplicate shared-state => reject in combination-root"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cl/combination-root [{:kind :shared-state :ref s0}
                                       {:kind :shared-state :ref s0}])))))

(deftest test-combination-duplicate-ref-rejects
  (testing "duplicate ref as different kinds => reject"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cl/combination-root [{:kind :shared-state :ref s0}
                                       {:kind :command :ref s0}])))))

(deftest test-combination-valid
  (testing "build-combination produces valid record"
    (let [c (cl/build-combination [{:kind :shared-state :ref s0}
                                   {:kind :command :ref "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}])]
      (is (cl/combination-valid? c))
      (is (= cl/combination-schema (:combination/schema c)))
      (is (hash-ref/valid-sha256-ref? (:combination/root c)))))
  (testing "combination-root-valid? rejects mismatched root"
    (is (false? (:valid? (cl/combination-root-valid?
                          "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                          [{:kind :shared-state :ref s0}]))))))

;; ── Command verification ────────────────────────────────────────────────────────

(deftest test-verify-command-rejects-wrong-schema
  (testing "wrong-schema but self-consistent hash => reject"
    (let [base {:command/action :some-action
                :command/input-state-root s0
                :command/resulting-state-root s1
                :command/built-with-includes [{:kind :shared-state :ref s0}]}
          wrong-schema (assoc base :command/schema :wrong/schema.v999)
          with-root (assoc wrong-schema :command/root (cl/command-root wrong-schema))]
      (is (false? (cl/valid-command? with-root)))
      (is (false? (:valid? (cl/verify-command with-root))))))
  (testing "valid command passes verify-command"
    (let [result (cl/verify-command cmd-a)]
      (is (:valid? result))
      (is (empty? (:issues result))))))

(deftest test-verify-command-rejects-invalid-ref
  (testing "command with malformed state-root ref => reject"
    (let [result (cl/verify-command
                  {:command/schema cl/command-schema
                   :command/action :action-a
                   :command/input-state-root "not-a-ref"
                   :command/resulting-state-root s1
                   :command/built-with-includes [{:kind :shared-state :ref s0}]
                   :command/root s0})]
      (is (false? (:valid? result))))))

;; ── Concatenation ───────────────────────────────────────────────────────────────

(deftest test-concatenation-valid
  (testing "A.result == B.input => consecutive valid"
    (let [concatenation (cl/build-concatenation cmd-a cmd-b)]
      (is (= cl/concatenation-schema (:concatenation/schema concatenation)))
      (let [result (cl/verify-concatenation concatenation cmd-a cmd-b)]
        (is (:valid? result))
        (is (empty? (:issues result))))))
  (testing "A.result != B.input => throws on build"
    (is (thrown? clojure.lang.ExceptionInfo
                 (cl/build-concatenation cmd-b cmd-a)))))

(deftest test-concatenation-order-matters
  (testing "A ⧺ B versus B ⧺ A => different identity"
    (let [concat-ab (cl/build-concatenation cmd-a cmd-b)
          concat-ba (cl/build-concatenation cmd-d cmd-a)]
      (is (not= (:concatenation/root concat-ab)
                (:concatenation/root concat-ba))))))

(deftest test-concatenation-non-mutating
  (testing "non-mutating A: input == result == B.input => valid"
    (let [non-mutating (build-cmd :noop s0 s0 [s0])
          cmd-after (build-cmd :action-x s0 s1 [s0])
          concatenation (cl/build-concatenation non-mutating cmd-after)]
      (is (:valid? (cl/verify-concatenation concatenation non-mutating cmd-after))))))

(deftest test-verify-concatenation-validates-children
  (testing "concatenation with invalid child command => reject"
    (let [bad-command {:command/schema :wrong/schema
                       :command/action :action-a
                       :command/input-state-root s0
                       :command/resulting-state-root s1
                       :command/built-with-includes [{:kind :shared-state :ref s0}]}
          concatenation {:concatenation/schema cl/concatenation-schema
                         :concatenation/left (:command/root cmd-a)
                         :concatenation/right (:command/root bad-command)
                         :concatenation/join-state s1}]
      (is (false? (:valid? (cl/verify-concatenation concatenation cmd-a bad-command)))
          (str "Expected invalid, got: " (cl/verify-concatenation concatenation cmd-a bad-command))))))

;; ── Lineage verification ────────────────────────────────────────────────────────

(deftest test-lineage-ok
  (testing "valid two-command lineage => :ok"
    (let [result (cl/verify-lineage [cmd-a cmd-b])]
      (is (:valid? result))
      (is (= :ok (:status result)))
      (is (true? (:append? result)))))
  (testing "non-mutating command chain => :ok"
    (let [nm (build-cmd :noop s0 s0 [s0])
          c2 (build-cmd :action-x s0 s1 [s0])
          c3 (build-cmd :action-y s1 s2 [s1])]
      (is (:valid? (cl/verify-lineage [nm c2 c3]))))))

(deftest test-lineage-state-mismatch
  (testing "predecessor-state-mismatch when continuity breaks"
    (let [result (cl/verify-lineage [cmd-a cmd-c])]
      (is (false? (:valid? result)))
      (is (= :predecessor-state-mismatch (:status result))))))

;; ── Termination ─────────────────────────────────────────────────────────────────

(deftest test-termination-valid
  (testing "cancel-and-terminate built at current head => valid terminal lineage"
    (let [term (terminator-cmd cmd-b)
          result (cl/verify-lineage [cmd-a cmd-b term])]
      (is (:valid? result))
      (is (= :terminated (:status result)))
      (is (true? (:append? result)))))
  (testing "first-element terminator => :missing-termination-predecessor"
    (let [term (cl/build-termination-command cmd-a s2)
          result (cl/verify-lineage [term])]
      (is (false? (:valid? result)))
      (is (= :missing-termination-predecessor (:status result))))))

(deftest test-termination-stale-basis
  (testing "cancel-and-terminate built from stale ancestor => reject"
    (let [term (cl/build-termination-command cmd-a s2)
          result (cl/verify-lineage [cmd-a cmd-b term])]
      (is (false? (:valid? result)))
      (is (= :stale-termination-basis (:status result))))))

(deftest test-termination-stale-shared-state
  (testing "terminator input == current head but terminator shared-state != head => reject"
    (let [head cmd-b
          stale-term (cl/build-termination-command head s2)
          tampered (assoc-in stale-term
                             [:command/built-with-includes]
                             [{:kind :shared-state :ref s0}])
          tampered-with-root (assoc tampered
                                    :command/root (cl/command-root tampered))
          result (cl/verify-lineage [cmd-a cmd-b tampered-with-root])]
      (is (false? (:valid? result)))
      (is (= :stale-termination-shared-state (:status result))))))

(deftest test-terminator-after-terminal
  (testing "terminal-head ⧺ C => :predecessor-terminal"
    (let [term (terminator-cmd cmd-b)
          result (cl/verify-lineage [cmd-a cmd-b term cmd-a])]
      (is (false? (:valid? result)))
      (is (= :predecessor-terminal (:status result))))))

(deftest test-identical-terminal-replay
  (testing "identical terminal replay => {:valid? true :status :already-terminated}"
    (let [term (terminator-cmd cmd-b)
          result (cl/verify-lineage [cmd-a cmd-b term term])
          _ (println "Replay result:" result)]
      (is (:valid? result))
      (is (= :already-terminated (:status result)))
      (is (false? (:append? result))
          (str "Expected append? false, got: " result))
      (is (empty? (:errors result)))))
  (testing "different terminator after terminal => :predecessor-terminal"
    (let [term1 (terminator-cmd cmd-b)
          term2 (cl/build-termination-command cmd-b s1)
          result (cl/verify-lineage [cmd-a cmd-b term1 term2])]
      (is (false? (:valid? result)))
      (is (= :predecessor-terminal (:status result))))))

;; ── Termination receipt verification ────────────────────────────────────────────

(deftest test-verify-termination-receipt
  (testing "valid receipt verifies correctly"
    (let [head cmd-b
          term (terminator-cmd head)
          receipt (cl/build-termination-receipt term head)]
      (is (:valid? (cl/verify-termination-receipt receipt head term)))))
  (testing "receipt with wrong predecessor => reject"
    (let [head cmd-b
          term (terminator-cmd head)
          receipt (cl/build-termination-receipt term head)
          wrong-head cmd-a
          result (cl/verify-termination-receipt receipt wrong-head term)]
      (is (false? (:valid? result))
          (str "Expected invalid predecessor, got: " result))))
  (testing "receipt with stale shared-state => reject"
    (let [head cmd-b
          term (cl/build-termination-command head s2)
          receipt (cl/build-termination-receipt term head)
          stale-term (assoc-in term [:command/built-with-includes]
                               [{:kind :shared-state :ref s0}])
          stale-with-root (assoc stale-term
                                 :command/root (cl/command-root stale-term))
          result (cl/verify-termination-receipt receipt head stale-with-root)]
      (is (false? (:valid? result))
          (str "Expected stale shared-state rejection, got: " result)))))

;; ── Shared state helpers ────────────────────────────────────────────────────────

(deftest test-shared-state-ref
  (testing "extracts shared-state ref from command members"
    (is (= s0 (cl/shared-state-ref cmd-a))))
  (testing "returns nil when no shared-state member"
    (let [cmd (cl/build-command {:command/action :x
                                 :command/input-state-root s0
                                 :command/resulting-state-root s1
                                 :command/built-with-includes [{:kind :command :ref "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]})]
      (is (nil? (cl/shared-state-ref cmd))))))
