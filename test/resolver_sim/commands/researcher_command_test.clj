(ns resolver-sim.commands.researcher-command-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [resolver-sim.commands.researcher :as cmd]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.support.ed25519 :as fx]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.hash.reference :as hash-ref]))

(defn- write-key-file
  "Write an Ed25519 private key to a temporary PEM file and return its path."
  [kp]
  (let [f (doto (java.io.File/createTempFile "researcher-key" ".pem")
            (.deleteOnExit))
        pem-key (.getEncoded (:private-key kp))
        b64 (.encodeToString (java.util.Base64/getEncoder) pem-key)
        pem (str "-----BEGIN PRIVATE KEY-----\n" b64 "\n-----END PRIVATE KEY-----\n")]
    (spit f pem)
    (.getPath f)))

(defn- write-input-file
  "Write EDN content to a temporary file and return its path."
  [content]
  (let [f (doto (java.io.File/createTempFile "researcher-input" ".edn")
            (.deleteOnExit))]
    (spit f (pr-str content))
    (.getPath f)))

(defn- make-keyed-round
  "Create a 3-member review round with integer keys for testing."
  []
  (rr/build-review-round
   {:benchmark/content-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    :review-round/purpose :force-authorisation
    :review-round/members
    [{:researcher/id "researcher-a" :role :model-steward :review-member/key 0}
     {:researcher/id "researcher-b" :role :independent-reproducer :review-member/key 1}
     {:researcher/id "researcher-c" :role :adversarial-reviewer :review-member/key 2}]
    :review-round/membership-frozen-at "2026-01-01T00:00:00Z"
    :review-round/policy-root "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    :review-round/force-target {:target/kind :emergency
                                :target/baseline-content-root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                                :target/branch-descriptor-hash "sha256:2222222222222222222222222222222222222222222222222222222222222222"
                                :target/proposed-content-root "sha256:3333333333333333333333333333333333333333333333333333333333333333"}
    :review-round/approval-set #{}
    :review-round/branch-descriptor "sha256:4444444444444444444444444444444444444444444444444444444444444444"}))

(defn- base-input
  "Build a valid input context map for researcher disagree/approve."
  [round]
  {:authorization/id :authorisation/test-001
   :authorisation/id :authorisation/test-001
   :authorisation/request-root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
   :review-round/hash (:review-round/hash round)
   :review-round round})

(defn- valid-scope-map
  "A scope map that passes parameter-attribution validation."
  []
  {:authorization/id "auth-001"
   :authorization/type :force-authorisation
   :held/direction :add
   :token :eth
   :amount 1000
   :held/account "0xabc"
   :held/position-id "pos-001"
   :owner/address "0xowner"
   :held/reason "test"
   :held/workflow-id "wf-001"
   :parameter/context {:parameter-context/type :protocol-parameters
                       :parameter-context/root "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                       :parameter-context/version 1}
   :parameter/address {:parameter/path ["arg1" "arg2"]}})

;; ── disagree ──────────────────────────────────────────────────────────────

(deftest disagree-resolves-member-key-and-signs-dissent
  (testing "researcher disagree resolves integer member-key 1 to researcher-b
            and produces a valid signed dissent"
    (let [round (make-keyed-round)
          kp (fx/keypair :rk/researcher-b)
          key-path (write-key-file kp)
          input (write-input-file (base-input round))
          result (cmd/disagree {:cmd/raw-args ["--input" input
                                               "--member-key" "1"
                                               "--key" key-path
                                               "--dissent-reason"
                                               "methodology concern"]})
          decision (:decision result)]
      (is (zero? (:exit-code result)))
      (is (= "researcher-b" (:researcher/id decision)))
      (is (= :dissent (:decision decision)))
      (is (= "methodology concern" (:dissent/reason decision)))
      (is (hash-ref/valid-sha256-ref? (:decision/hash decision)))
      (is (hash-ref/valid-sha256-ref? (:authorisation/request-root decision)))
      (is (= (:review-round/hash round)
             (:review-round/hash decision)))
      (is (rfa/valid-decision? (:decision decision)))))
  (testing "researcher disagree rejects an unknown member-key"
    (let [round (make-keyed-round)
          kp (fx/keypair)
          key-path (write-key-file kp)
          input (write-input-file (base-input round))
          result (cmd/disagree {:cmd/raw-args ["--input" input
                                               "--member-key" "9"
                                               "--key" key-path
                                               "--dissent-reason"
                                               "test"]})]
      (is (= 2 (:exit-code result)))
      (is (str/includes? (:message result) "not found")))))

(deftest disagree-rejects-invalid-option
  (testing "researcher disagree with unknown option returns error"
    (let [result (cmd/disagree {:cmd/raw-args ["--unknown" "x"]})]
      (is (= 2 (:exit-code result)))
      (is (str/includes? (:message result) "unknown option")))))

;; ── approve ───────────────────────────────────────────────────────────────

(deftest approve-resolves-member-key-and-signs-approval
  (testing "researcher approve with --outcome-root produces a v2 signed decision"
    (let [round (make-keyed-round)
          kp (fx/keypair :rk/researcher-a)
          key-path (write-key-file kp)
          outcome-root "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
          input (write-input-file (assoc (base-input round)
                                         :outcome/root outcome-root))
          result (cmd/approve {:cmd/raw-args ["--input" input
                                              "--member-key" "0"
                                              "--key" key-path
                                              "--outcome-root" outcome-root]})
          decision (:decision result)]
      (is (zero? (:exit-code result)))
      (is (= "researcher-a" (:researcher/id decision)))
      (is (= :approve (:decision decision)))
      (is (= "researcher-decision.v2" (:schema-version decision)))
      (is (= outcome-root (:outcome/root decision)))
      (is (hash-ref/valid-sha256-ref? (:decision/hash decision)))))
  (testing "researcher approve without --outcome-root produces a v1 signed decision"
    (let [round (make-keyed-round)
          kp (fx/keypair :rk/researcher-c)
          key-path (write-key-file kp)
          input (write-input-file (base-input round))
          result (cmd/approve {:cmd/raw-args ["--input" input
                                              "--member-key" "2"
                                              "--key" key-path]})
          decision (:decision result)]
      (is (zero? (:exit-code result)))
      (is (= "researcher-c" (:researcher/id decision)))
      (is (= :approve (:decision decision)))
      (is (hash-ref/valid-sha256-ref? (:decision/hash decision)))
      (is (nil? (:outcome/root decision))))))

;; ── check ─────────────────────────────────────────────────────────────────

(deftest check-classifies-usable-authorisation
  (testing "an active, in-window, unconsumed authorisation with valid scope
            is classified :usable"
    (let [scope (valid-scope-map)
          hash (fa/force-authorisation-scope-hash scope)
          record {:authorization/id "auth-001"
                  :authorization/status :active
                  :consumed? false
                  :authorization/scope-hash hash
                  :authorization/scope scope
                  :authorisation/decision-status :approved}
          input {:authorization/record record
                 :authorization/consumption-registry {}
                 :authorization/scope scope}
          stdout (with-out-str
                   (cmd/check {:cmd/raw-args ["--input" (write-input-file input)]}))
          parsed (json/read-str stdout :key-fn keyword)
          result (cmd/check {:cmd/raw-args ["--input" (write-input-file input)]})]
      (is (= 0 (:exit-code result)))
      (is (= "usable" (:outcome parsed)))
      (is (true? (:valid? parsed)))))

(deftest check-classifies-forbidden-authorisation
  (testing "an inactive authorisation is classified :forbidden"
    (let [scope (valid-scope-map)
          hash (fa/force-authorisation-scope-hash scope)
          record {:authorization/id "auth-001"
                  :authorization/status :revoked
                  :consumed? false
                  :authorization/scope-hash hash
                  :authorization/scope scope}
          input {:authorization/record record
                 :authorization/consumption-registry {}
                 :authorization/scope scope}
          result (cmd/check {:cmd/raw-args ["--input" (write-input-file input)]})]
      (is (= 1 (:exit-code result)))
      (is (= :forbidden (:message result))))))

(deftest check-classifies-forbidden-authorized
  (testing "an inactive but decision-approved authorisation is classified
            :forbidden-authorized"
    (let [scope (valid-scope-map)
          hash (fa/force-authorisation-scope-hash scope)
          record {:authorization/id "auth-001"
                  :authorization/status :revoked
                  :consumed? false
                  :authorization/scope-hash hash
                  :authorization/scope scope
                  :authorisation/decision-status :approved}
          input {:authorization/record record
                 :authorization/consumption-registry {}
                 :authorization/scope scope}
          result (cmd/check {:cmd/raw-args ["--input" (write-input-file input)]})]
      (is (= 1 (:exit-code result)))
      (is (= :forbidden-authorized (:message result))))))

(deftest check-classifies-invalid-parameter-attribution
  (testing "a scope map with invalid parameter attribution is classified
            :invalid-parameter-attribution"
    (let [bad-scope (assoc (valid-scope-map)
                          :parameter/context {:parameter-context/type :unknown-type
                                              :parameter-context/root "sha256:abcdef"})
          hash (fa/force-authorisation-scope-hash bad-scope)
          record {:authorization/id "auth-001"
                  :authorization/status :active
                  :consumed? false
                  :authorization/scope-hash hash
                  :authorization/scope bad-scope}
          input {:authorization/record record
                 :authorization/consumption-registry {}
                 :authorization/scope bad-scope}
          result (cmd/check {:cmd/raw-args ["--input" (write-input-file input)]})]
      (is (= 1 (:exit-code result)))
      (is (= :invalid-parameter-attribution (:message result))))))

(deftest check-handles-unknown-option
  (testing "researcher check with unknown option returns error"
    (let [result (cmd/check {:cmd/raw-args ["--unknown" "x"]})]
      (is (= 2 (:exit-code result)))
      (is (str/includes? (:message result) "unknown option"))))))