(ns resolver-sim.research.ratification-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.research.ratification :as ratification]))

(def ^:private private-key "test-keys/test-researcher-signing-key")
(def ^:private public-key "test-keys/test-researcher-signing-key.pub")
(def ^:private evidence-root (str "sha256:" (apply str (take 64 (cycle "ab")))))
(def ^:private round-root (str "sha256:" (apply str (take 64 (cycle "cd")))))
(def ^:private subject-root (str "sha256:" (apply str (take 64 (cycle "ef")))))

(defn- fixture
  ([] (fixture {}))
  ([{:keys [allow-dissent? challenges-block?]
     :or {allow-dissent? true challenges-block? true}}]
   (let [policy (ratification/build-policy
                 {:id :research/policy
                  :authority-root subject-root
                  :eligible-principals #{:principal/a :principal/b :principal/c :principal/d}
                  :threshold 2
                  :allow-dissent? allow-dissent?
                  :challenges-block? challenges-block?})
         target (ratification/build-target
                 {:domain :prf/research
                  :subject-root subject-root
                  :evidence-root evidence-root
                  :round-root round-root
                  :policy-root (:policy/root policy)
                  :schema "claim.v1"})]
     {:policy policy :target target})))

(defn- contribution [target researcher principal outcome kind & [evidence-root*]]
  (let [subject-root (ratification/contribution-subject-root
                      {:target-root (:target/root target)
                       :principal-id principal :kind kind :evidence-root evidence-root*})
        decision (decisions/build-signed-decision-v2
                  researcher (:target/root target) (:target/root target) round-root
                  subject-root outcome private-key
                  :signing-key-id "shared-test-key"
                  :dissent-reason (when (not= outcome :approve) "needs review"))]
    (ratification/build-contribution
     {:target target :principal-id principal :decision decision :kind kind
      :evidence-root evidence-root*})))

(def ^:private key-bindings
  {"researcher-a" {:researcher/id "researcher-a" :principal/id :principal/a
                   :public-key-path public-key}
   "researcher-b" {:researcher/id "researcher-b" :principal/id :principal/b
                   :public-key-path public-key}
   "researcher-c" {:researcher/id "researcher-c" :principal/id :principal/c
                   :public-key-path public-key}
   "researcher-d" {:researcher/id "researcher-d" :principal/id :principal/d
                   :public-key-path public-key}})

(deftest exact-target-approvals-reconstruct-ratification
  (let [{:keys [target policy]} (fixture)
        contributions [(contribution target "researcher-a" :principal/a :approve :approve)
                       (contribution target "researcher-b" :principal/b :approve :approve)]
        inputs {:target target :policy policy :contributions contributions
                :key-bindings key-bindings}
        result (ratification/reconstruct-ratification inputs)]
    (is (= :ratified (:ratification/status result)))
    (is (false? (:ratification/operational-authority? result)))
    (is (:valid? (ratification/validate-ratification result inputs)))))

(deftest dissent-and-challenge-remain-explicit
  (let [{:keys [target policy]} (fixture)
        contributions [(contribution target "researcher-a" :principal/a :approve :approve)
                       (contribution target "researcher-b" :principal/b :dissent :dissent)
                       (contribution target "researcher-c" :principal/c :dissent :challenge evidence-root)]
        result (ratification/reconstruct-ratification
                {:target target :policy policy :contributions contributions
                 :key-bindings key-bindings})]
    (is (= :contested-unresolved (:ratification/status result)))
    (is (= 2 (:ratification/dissent-count result)))
    (is (= [evidence-root] (:ratification/challenge-roots result)))))

(deftest complete-opposition-is-rejected
  (let [{:keys [target policy]} (fixture)
        contributions [(contribution target "researcher-a" :principal/a :dissent :dissent)
                       (contribution target "researcher-b" :principal/b :dissent :dissent)
                       (contribution target "researcher-c" :principal/c :dissent :dissent)
                       (contribution target "researcher-d" :principal/d :dissent :dissent)]]
    (is (= :rejected
           (:ratification/status
            (ratification/reconstruct-ratification
             {:target target :policy policy :contributions contributions
              :key-bindings key-bindings}))))))

(deftest target-policy-and-principal-transplants-fail
  (let [{:keys [target policy]} (fixture)
        a (contribution target "researcher-a" :principal/a :approve :approve)
        b (contribution target "researcher-b" :principal/a :approve :approve)
        different-evidence (assoc target :target/evidence-root subject-root)
        tampered (assoc a :contribution/evidence-root subject-root)
        duplicate-principal-bindings
        (assoc key-bindings "researcher-b"
               {:researcher/id "researcher-b" :principal/id :principal/a
                :public-key-path public-key})]
    (testing "same signing key does not permit duplicate principals"
      (is (= [:duplicate-principal]
             (:errors (ex-data
                       (try
                         (ratification/reconstruct-ratification
                          {:target target :policy policy :contributions [a b]
                           :key-bindings duplicate-principal-bindings})
                         nil
                         (catch clojure.lang.ExceptionInfo error error)))))))
    (testing "foreign target cannot consume an existing signed decision"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ratification/build-contribution
                    {:target different-evidence :principal-id :principal/a
                     :decision (:contribution/decision a) :kind :approve}))))
    (testing "a new immutable basis requires a new signature"
      (let [successor (ratification/build-target
                       {:domain :prf/research :subject-root subject-root
                        :evidence-root evidence-root :round-root round-root
                        :policy-root (:policy/root policy) :schema "claim.v1"
                        :predecessor-root (:target/root target)})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ratification/build-contribution
                      {:target successor :principal-id :principal/a
                       :decision (:contribution/decision a) :kind :approve})))))
    (testing "modified evidence changes the contribution root"
      (is (not (:valid? (ratification/validate-ratification
                         {:schema/version "prf/research-ratification.v1"}
                         {:target target :policy policy :contributions [tampered]
                          :key-bindings key-bindings})))))))

(deftest dissent-history-is-part-of-reconstruction
  (let [{:keys [target policy]} (fixture)
        approval (contribution target "researcher-a" :principal/a :approve :approve)
        dissent (contribution target "researcher-b" :principal/b :dissent :dissent)
        inputs {:target target :policy policy :contributions [approval dissent]
                :key-bindings key-bindings}
        persisted (ratification/reconstruct-ratification inputs)]
    (is (false? (:valid? (ratification/validate-ratification
                          persisted (assoc inputs :contributions [approval])))))))

(deftest equivocation-ineligible-principal-and-v1-approval-fail
  (let [{:keys [target policy]} (fixture)
        approval (contribution target "researcher-a" :principal/a :approve :approve)
        dissent (contribution target "researcher-a" :principal/a :dissent :dissent)
        ineligible (contribution target "researcher-b" :principal/unlisted :approve :approve)
        inputs {:target target :policy policy :key-bindings key-bindings}]
    (testing "same researcher cannot equivocate across outcomes"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ratification/reconstruct-ratification
                    (assoc inputs :contributions [approval dissent])))))
    (testing "non-eligible principals cannot vote"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ratification/reconstruct-ratification
                    (assoc inputs :contributions [ineligible])))))
    (testing "legacy v1 signatures cannot become new approvals"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ratification/build-contribution
                    {:target target :principal-id :principal/a :kind :approve
                     :decision (assoc (:contribution/decision approval)
                                      :schema-version "researcher-decision.v1")}))))))
