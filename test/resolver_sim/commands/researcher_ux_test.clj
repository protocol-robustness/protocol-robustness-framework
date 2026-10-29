(ns resolver-sim.commands.researcher-ux-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-conclusion :as conclusion]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.commands.researcher-ux :as ux]
            [resolver-sim.research.integer-sum-verifier :as integer-sum]
            [resolver-sim.research.resolution-view :as resolution-view]
            [resolver-sim.cli.dispatch :as dispatch]
            [resolver-sim.cli.registry :as registry]))

(defn- with-input-file [value f]
  (let [file (java.io.File/createTempFile "prf-research-command-" ".edn")]
    (try
      (spit file (pr-str value))
      (f (.getAbsolutePath file))
      (finally (.delete file)))))

(def ^:private root-a (str "sha256:" (apply str (repeat 64 "a"))))
(def ^:private root-b (str "sha256:" (apply str (repeat 64 "b"))))
(def ^:private root-c (str "sha256:" (apply str (repeat 64 "c"))))
(def ^:private root-d (str "sha256:" (apply str (repeat 64 "d"))))

(deftest researcher-command-routes-resolve
  (is (:ok? (registry/validate-paths)))
  (let [handlers (dispatch/get-command-handlers)]
    (is (every? handlers
                [:research-question-list :research-question-show
                 :research-evidence-inspect :research-conclusion-verify
                 :research-resolution-show :research-review-status
                 :research-policy-approval-prepare :research-policy-approval-sign
                 :research-policy-approval-verify :research-compare :research-export
                 :research-reproduce :research-submit :researcher-challenge-submit]))))

(deftest question-catalog-list-and-show
  (let [catalog {:questions
                 [{:research/id :question/example
                   :research-definition/root root-a
                   :research-definition
                   {:research/id :question/example
                    :research/title "Example question"
                    :research/question "Does the bounded invariant hold?"}}]}]
    (with-input-file catalog
      (fn [path]
        (let [listed (:result (ux/question-list {:input path}))
              shown (:result (ux/question-show {:input path
                                                :cmd/args [":question/example"]}))]
          (is (= :question/example
                 (get-in listed [:research/questions 0 :research/id])))
          (is (= "Example question" (:research/title shown)))
          (is (= root-a (:research-definition/root shown))))))))

(deftest evidence-inspection-does-not-imply-verification
  (with-input-file {:schema/version "example-evidence.v1"
                    :evidence/root root-a :payload {:result 42}}
    (fn [path]
      (let [result (:result (ux/evidence-inspect {:input path}))]
        (is (= :loaded-not-verified (:inspection/status result)))
        (is (= root-a (:artifact/root result)))))))

(deftest conclusion-command-shows-method-verification-separately
  (let [evidence (integer-sum/build-evidence
                  {:left 7 :right 35 :observed-result 42})
        artifact {:conclusion (integer-sum/build-fixture-conclusion evidence)
                  :method-evidence evidence
                  :trusted-method-verifier-root integer-sum/verifier-root}]
    (with-input-file artifact
      (fn [path]
        (let [result (:result (ux/conclusion-verify {:input path}))]
          (is (= :valid (:structure-status result)))
          (is (= :verified (:method-status result)))
          (is (= :established-within-verified-method (:research-status result))))))))

(deftest resolution-command-validates-the-view
  (let [view (resolution-view/build-view
              {:subject-root root-a :scope-root root-b :method-root root-c
               :evidence-root root-d :claim-status :supported-within-scope
               :method-status :verified :method-source-status :pinned
               :reproduction-status :independent
               :ratification-status :ratified
               :limitations ["bounded fixture"]})]
    (with-input-file view
      (fn [path]
        (let [result (:result (ux/resolution-show {:input path}))]
          (is (= :valid (:resolution/status result)))
          (is (false? (get-in result [:resolution/view
                                      :resolution-view/operational-authority?]))))))))

(deftest approval-sign-command-signs-only-the-selected-prepared-request
  (let [request {:approval/signing-requests
                 [{:researcher/id "member-a"
                   :signing-key/id "key/a"
                   :authorisation/id :research/policy-approval
                   :authorisation/request-root root-a
                   :review-round/hash (str "review-round:" (apply str (repeat 64 "e")))
                   :outcome/root root-b}]}
        key "test/fixtures/signing-keys/research-policy-approvers/member-a.key"
        public-key (slurp "test/fixtures/signing-keys/research-policy-approvers/member-a.pub")]
    (with-input-file request
      (fn [path]
        (let [signed (atom nil)]
          (with-out-str
            (reset! signed
                    (ux/policy-approval-sign
                     {:input path :key key :researcher-id "member-a"})))
          (is (= 0 (:exit-code @signed)))
          (is (= "member-a" (get-in @signed [:result :researcher/id])))
          (is (:valid? (decisions/verify-signed-decision-v2-with-public-key-content
                        (:result @signed) public-key))))))))
