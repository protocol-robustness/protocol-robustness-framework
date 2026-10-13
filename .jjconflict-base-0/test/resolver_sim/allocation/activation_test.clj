(ns resolver-sim.allocation.activation-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.allocation.activation :as act]
            [resolver-sim.allocation.kernel :as kernel]))

(defn- real-passing-proof
  "A genuinely produced passing proof (a-vs-b-plus-c kernel result)."
  []
  (let [input (slurp "scenarios/allocation/a-vs-b-plus-c/kernel-input.json")
        parsed (json/read-str input :key-fn str)]
    (kernel/run-kernel parsed)))

(def ^:private policy
  {:authority :coordinator
   :fail-closed true})

(defn- mutated-rejected-proof
  "Mutate a genuinely produced passing proof so verification would reject it:
   tamper the committed result root and inject a rejection classification."
  [proof]
  (assoc proof
         :result/status :rejected
         :result-root (apply str (repeat 64 "f"))
         :rejection/classification :result-root-mismatch
         :rejection/reason "mutated result root"))

(deftest passing-proof-produces-valid-activated-receipt
  (testing "a passing proof yields an activated receipt that is valid authorization"
    (let [receipt (act/build-receipt {:proof (real-passing-proof) :policy policy})]
      (is (= :activated (:activation/status receipt)))
      (is (nil? (:rejection/classification receipt)))
      (is (true? (act/valid-activated-receipt? receipt)))
      (is (re-matches #"[0-9a-f]{64}" (:activation/root receipt))))))

(deftest rejected-proof-produces-prohibited-receipt
  (testing "a genuinely produced proof mutated so verification rejects it can never
            yield a valid activation receipt — verification failure is an
            authorization boundary, not metadata"
    (let [proof (real-passing-proof)
          rejected (mutated-rejected-proof proof)
          receipt (act/build-receipt {:proof rejected :policy policy})]
      (is (= :prohibited (:activation/status receipt)))
      (is (= :result-root-mismatch (:rejection/classification receipt)))
      (is (false? (act/valid-activated-receipt? receipt))
          "the activation path cannot emit/accept a valid receipt for a rejected proof"))))

(deftest contradictory-passing-rejected-proof-is-prohibited-at-construction
  (testing "a rejection classification prevents activation even when a caller
            incorrectly leaves :result/status as :passing"
    (let [contradictory (assoc (real-passing-proof)
                               :rejection/classification :proof-invalid)
          receipt (act/build-receipt {:proof contradictory :policy policy})]
      (is (= :prohibited (:activation/status receipt)))
      (is (false? (act/valid-activated-receipt? receipt))))))

(deftest activation-cannot-be-forged-from-rejected-proof
  (testing "even if an attacker overwrites the status to :activated, the receipt
            is invalid because the rejection classification is bound"
    (let [proof (real-passing-proof)
          rejected (mutated-rejected-proof proof)
          receipt (act/build-receipt {:proof rejected :policy policy})
          forged (assoc receipt :activation/status :activated)]
      (is (false? (act/valid-activated-receipt? forged))
          "a forged :activated status on a rejected proof's receipt is invalid"))))

(deftest receipt-root-is-content-addressed
  (testing "the receipt root recomputes and is deterministic"
    (let [a (act/build-receipt {:proof (real-passing-proof) :policy policy})
          b (act/build-receipt {:proof (real-passing-proof) :policy policy})]
      (is (= (:activation/root a) (:activation/root b)))
      (is (= (:activation/root a)
             (act/receipt-root a))))))

(deftest all-active-no-churn
  (testing "all-active activation binds the unfiltered result-root byte-identically"
    (let [proof (real-passing-proof)]
      (is (true? (act/all-active? proof)))
      (is (true? (act/all-active-no-churn?
                  {:proof proof :policy policy
                   :unfiltered-result-root (:result-root proof)})))
      (is (false? (act/all-active-no-churn? {:proof proof :policy policy}))
          "a copied proof result-root alone is not evidence of no-churn")
      (is (= (:result-root (act/build-receipt {:proof proof :policy policy}))
             (:result-root proof))))))

(deftest proof-root-binds-certificate-digest
  (testing "the receipt's proof-root is the certificate-assertions-digest for a kernel proof"
    (let [proof (real-passing-proof)
          receipt (act/build-receipt {:proof proof :policy policy})]
      (is (= (:certificate-assertions-digest proof) (:proof-root receipt))))))
