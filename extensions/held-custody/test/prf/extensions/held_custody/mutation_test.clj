(ns prf.extensions.held-custody.mutation-test
  "Tests for the held-custody mutation member: builder, action/direction
   contract, and the :valid? / :verified? distinction."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.evidence.artifact :as artifact]
            [prf.extensions.held-custody.mutation :as mut]))

(defn- scope [id dir amt]
  {:authorization/id id
   :authorization/type :force-authorisation
   :held/direction dir
   :token "USDC"
   :amount amt
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- auth [id dir amt]
  (let [s (scope id dir amt)]
    {:authorization/id id
     :authorization/status :active
     :authorization/type :force-authorisation
     :authorization/scope-hash (fa/force-authorisation-scope-hash
                                (fa/normalize-force-authorisation-scope s))
     :authorization/scope (fa/normalize-force-authorisation-scope s)
     :starts-at 0
     :expires-at 1000}))

(defn- adjustment [mutation-id action direction amount]
  {:mutation/id mutation-id
   :held/action action
   :held/direction direction
   :held/amount amount
   :held/token "USDC"
   :held/account :escrow-principal
   :owner/address "0xrecipient"
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- mk [mutation-id action direction amount auth-id]
  (mut/build-force-auth-held-mutation
   (auth auth-id direction amount)
   (adjustment mutation-id action direction amount)
   {}))

(deftest builds-direction-and-action-faithfully
  (doseq [[action direction] (vec mut/action->direction)]
    (let [m (mk (str "m-" (name action)) action direction 100 "fa-0")]
      (is (= action (:held/action m)) (str "action preserved: " action))
      (is (= direction (:held/direction m)))
      (is (= "force-auth-held-custody-mutation.v1" (:schema-version m)))
      (is (= :force-auth-held-custody-mutation (:artifact/kind m)))
      (is (mut/valid-force-auth-held-mutation? m {}))
      (testing (str action " preserves the precise source action, not normalised to :sub-held")
        (is (= action (:held/action m)))))))

(deftest unknown-action-fails-closed
  (let [ex (try (mk "m-x" :bogus-action :in 100 "fa-0")
                (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? ex))
    (is (= :held-custody/invalid-action-direction (:error (ex-data ex))))))

(deftest action-direction-mismatch-fails-closed
  (testing "an add-held action with an :out direction is rejected"
    (let [ex (try (mk "m-x" :add-held :out 100 "fa-0")
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= :held-custody/invalid-action-direction (:error (ex-data ex)))))))

(deftest invalid-amount-fails-closed
  (testing "missing, negative, and non-numeric amounts are rejected"
    (doseq [amount [nil -5 "100"]]
      (let [ex (try (mk "m-x" :add-held :in amount "fa-0")
                    (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? ex) (str "amount rejected: " (pr-str amount)))
        (is (= :held-custody/invalid-amount (:error (ex-data ex)))))))
  (testing "a zero amount is permitted (amounts are non-negative, not positive)"
    (is (some? (mk "m-x" :add-held :in 0 "fa-0")))))

(deftest valid-vs-verified
  (let [m (mk "m-1" :add-held :in 100 "fa-0")]
    (testing "valid? is intrinsic and passes without authorization context"
      (is (mut/valid-force-auth-held-mutation? m {})))
    (testing "verified? requires the authorization context"
      (is (not (mut/verified-force-auth-held-mutation? m {})))
      (is (= :valid-unverified (:status (mut/check-force-auth-held-mutation m {})))))
    (testing "verified? passes when the grant reconciles"
      (is (mut/verified-force-auth-held-mutation? m {:authorization (auth "fa-0" :in 100)}))
      (is (= :valid-verified (:status (mut/check-force-auth-held-mutation m {:authorization (auth "fa-0" :in 100)})))))
    (testing "verified? fails on wrong amount / id / direction"
      (is (not (mut/verified-force-auth-held-mutation? m {:authorization (auth "fa-0" :in 99)})))
      (is (not (mut/verified-force-auth-held-mutation? m {:authorization (auth "fa-9" :in 100)})))
      (is (not (mut/verified-force-auth-held-mutation? m {:authorization (auth "fa-0" :out 100)}))))
    (testing "the unverified dimension is machine-readable"
      (is (= [{:reason :authorization-record-unavailable :authorization/id "fa-0"}]
             (:unverified (mut/check-force-auth-held-mutation m {})))))))

(deftest projection-integrity-and-scope-compatibility
  (let [m (mk "m-1" :add-held :in 100 "fa-0")]
    (testing "projection-hash authenticates the committed projection"
      (is (mut/projection-integrity-valid? m))
      (is (= (:authorization-scope/projection-hash m)
             (mut/projection-hash (:authorization-scope/projection m))))
      (is (mut/mutation-scope-compatible? m))
      (is (= :held-custody-mutation (:operation (:authorization-scope/projection m)))))
    (testing "a forged projection-hash is rejected"
      (let [forged (assoc m :authorization-scope/projection-hash "sha256:forged")
            r (mut/check-force-auth-held-mutation forged {})]
        (is (not (:valid? r)))
        (is (false? (:projection-integrity-valid? (:checks r))))))
    (testing "a mutation whose own fields disagree with its projection is rejected"
      (let [forged (assoc m :held/token :ETH)
            r (mut/check-force-auth-held-mutation forged {})]
        (is (not (:valid? r)))
        (is (false? (:mutation-scope-compatible? (:checks r))))))))

(deftest member-carries-no-stored-scope-verifies-flag
  (let [m (mk "m-1" :add-held :in 100 "fa-0")]
    (is (not (contains? m :authorization/scope-verifies?)))))

(deftest canonical-commitment-is-optional
  (let [m (mk "m-1" :add-held :in 100 "fa-0")]
    (is (mut/valid-force-auth-held-mutation?
         (artifact/attach-canonical-commitment m) {}))))
