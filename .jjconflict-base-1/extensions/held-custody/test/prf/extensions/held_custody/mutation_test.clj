(ns prf.extensions.held-custody.mutation-test
  "Tests for the held-custody mutation member: builder, action/direction
   contract, and the :valid? / :verified? distinction."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.evidence.artifact :as artifact]
            [prf.extensions.held-custody.aggregate :as agg]
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

(deftest member-rejects-noncanonical-equivalent-preimage
  (testing "the .v1 member contract uses :exact and rejects whitespace-equivalent
            preimages that still decode to the same body"
    (let [m (mk "m-1" :add-held :in 100 "fa-0")
          p (:artifact/preimage m)
          whitespaced (str "{ " (subs p 1 (dec (count p))) " }")]
      (is (mut/valid-force-auth-held-mutation? m {}))
      (is (artifact/preimage-decodes-to-body? m))
      (is (artifact/canonical-preimage-valid? m))
      (is (not (mut/valid-force-auth-held-mutation? (assoc m :artifact/preimage whitespaced) {}))
          "a noncanonical equivalent preimage is rejected under :exact"))))

;; ── add-held-action convenience ─────────────────────────────────────────────

(deftest add-held-action-builds-inward-add-held
  (let [adj {:mutation/id "m-a"
             :held/amount 100
             :held/token "USDC"
             :held/account :escrow-principal
             :owner/address "0xrecipient"
             :held/reason :force-authorised-release
             :held/workflow-id 0}
        m  (mut/add-held-action (auth "fa-0" :in 100) adj {})]
    (is (= :add-held (:held/action m)))
    (is (= :in (:held/direction m)))
    (is (= "force-auth-held-custody-mutation.v1" (:schema-version m)))
    (is (mut/valid-force-auth-held-mutation? m {}))
    (is (mut/verified-force-auth-held-mutation? m {:authorization (auth "fa-0" :in 100)}))
    (testing "the member is counted in add-held accounting"
      (let [s (agg/held-mutation-sequence-body [m])]
        (is (= 1 (:add-held/count s)))
        (is (= 100 (:add-held/amount s)))))))

(deftest add-held-action-ignores-passthrough-direction
  (testing "a nil direction is normalized to :in"
    (let [adj {:name "m-b" :held/amount 10 :held/token "USDC"
               :held/account :escrow-principal :owner/address "0xr"
               :held/reason :inbound :held/workflow-id 0}
          m (mut/add-held-action (auth "fa-0" :in 10) adj {})]
      (is (= :add-held (:held/action m)))
      (is (= :in (:held/direction m))))))

(deftest add-held-action-rejects-outward-direction
  (testing "an explicit :out direction fails closed instead of being forced to :in"
    (let [ex (try (let [adj {:name "m-c" :held/direction :out :held/amount 50
                             :held/token "USDC" :held/account :escrow-principal
                             :owner/address "0xr" :held/reason :release :held/workflow-id 0}]
                     (mut/add-held-action (auth "fa-0" :out 50) adj {}))
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= :held-custody/invalid-action-direction (:error (ex-data ex)))))))

;; ── sub-held-action convenience ─────────────────────────────────────────────

(deftest sub-held-action-builds-outward-sub-held
  (let [adj {:mutation/id "m-sub"
             :held/amount 40
             :held/token "USDC"
             :held/account :escrow-principal
             :owner/address "0xrecipient"
             :held/reason :force-authorised-release
             :held/workflow-id 0}
        m  (mut/sub-held-action (auth "fa-0" :out 40) adj {})]
    (is (= :sub-held (:held/action m)))
    (is (= :out (:held/direction m)))
    (is (= "force-auth-held-custody-mutation.v1" (:schema-version m)))
    (is (mut/valid-force-auth-held-mutation? m {}))
    (is (mut/verified-force-auth-held-mutation? m {:authorization (auth "fa-0" :out 40)}))
    (testing "the member is counted in sub-held accounting (amount-by-action)"
      (let [s (agg/held-mutation-sequence-body [m])]
        (is (= {:sub-held 40} (:amount-by-action s)))
        (is (= 0 (:add-held/amount s)))))))

(deftest sub-held-action-rejects-inward-direction
  (testing "an explicit :in direction fails closed instead of being forced to :out"
    (let [ex (try (let [adj {:mutation/id "m-sub-i" :held/direction :in :held/amount 40
                             :held/token "USDC" :held/account :escrow-principal
                             :owner/address "0xr" :held/reason :release :held/workflow-id 0}]
                     (mut/sub-held-action (auth "fa-0" :in 40) adj {}))
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= :held-custody/invalid-action-direction (:error (ex-data ex)))))))

(deftest add-and-sub-actions-are-symmetric
  (testing "add-held and sub-held conveniences produce opposite directions of the same action pair"
    (let [add-adj {:name "m-a" :held/amount 100 :held/token "USDC"
                   :held/account :escrow-principal :owner/address "0xr"
                   :held/reason :force-authorised-release :held/workflow-id 0}
          sub-adj {:name "m-s" :held/amount 40 :held/token "USDC"
                   :held/account :escrow-principal :owner/address "0xr"
                   :held/reason :force-authorised-release :held/workflow-id 0}
          add (mut/add-held-action (auth "fa-0" :in 100) add-adj {})
          sub (mut/sub-held-action (auth "fa-0" :out 40) sub-adj {})
          s   (agg/held-mutation-sequence-body [add sub])]
      (is (= :in (:held/direction add)))
      (is (= :out (:held/direction sub)))
      (is (= {:add-held 100 :sub-held 40} (:amount-by-action s)))
      (is (= 1 (:add-held/count s)))
      (is (= 100 (:add-held/amount s))))))
