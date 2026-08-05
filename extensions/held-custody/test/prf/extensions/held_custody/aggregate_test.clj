(ns prf.extensions.held-custody.aggregate-test
  "Tests for the held-custody mutation summary and aggregate checker: explicit
   flow fields, canonical zero handling, no :total-amount, and the
   :valid? / :verified? distinction."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [prf.extensions.held-custody.mutation :as mut]
            [prf.extensions.held-custody.aggregate :as agg]))

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

(defn- mk [mutation-id action direction amount auth-id]
  (mut/build-force-auth-held-mutation
   (auth auth-id direction amount)
   {:mutation/id mutation-id
    :held/action action
    :held/direction direction
    :held/amount amount
    :held/token "USDC"
    :held/account :escrow-principal
    :owner/address "0xrecipient"
    :held/reason :force-authorised-release
    :held/workflow-id 0}
   {}))

(deftest summary-exposes-explicit-flow-fields
  (let [add (mk "m1" :add-held :in 100 "fa-0")
        sub (mk "m2" :finalize-released :out 40 "fa-1")
        summary (agg/build-held-mutation-summary [add sub] {})]
    (is (= 100 (:gross-inflow summary)))
    (is (= 40 (:gross-outflow summary)))
    (is (= 140 (:gross-flow summary)))
    (is (= 60 (:net-change summary)))
    (is (= {:in 100 :out 40} (:amount-by-direction summary)))
    (is (= {:in 1 :out 1} (:by-direction summary)))
    (testing "no ambiguous :total-amount"
      (is (not (contains? summary :total-amount))))
    (testing "precise actions preserved, sparse action maps"
      (is (= {:add-held 100 :finalize-released 40} (:amount-by-action summary)))
      (is (= {:add-held 1 :finalize-released 1} (:by-action summary))))))

(deftest canonical-zero-handling-in-direction-maps
  (let [all-in (agg/build-held-mutation-summary [(mk "m1" :add-held :in 100 "fa-0")] {})]
    (is (= {:in 100 :out 0} (:amount-by-direction all-in)))
    (is (= {:in 1 :out 0} (:by-direction all-in)))))

(deftest recompute-matches-builder
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :finalize-released :out 40 "fa-1")]]
    (is (= (agg/build-held-mutation-summary members {})
           (agg/recompute-held-mutation-summary members {})))))

(deftest aggregate-valid-vs-verified
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :sub-held :out 40 "fa-1")]
        summary (agg/build-held-mutation-summary members {})]
    (testing "intrinsic validity without authorization context"
      (let [r (agg/check-held-mutation-aggregate summary members {})]
        (is (:valid? r))
        (is (not (:verified? r)))
        (is (= :valid-unverified (:status r)))
        (is (= ["fa-0" "fa-1"] (:unverified-authorization-ids r)))))
    (testing "fully verified when all grants reconcile"
      (let [r (agg/check-held-mutation-aggregate summary members
              {:authorizations {"fa-0" (auth "fa-0" :in 100)
                                "fa-1" (auth "fa-1" :out 40)}})]
        (is (:valid? r))
        (is (:verified? r))
        (is (= :valid-verified (:status r)))
        (is (empty? (:unverified-authorization-ids r)))))
    (testing "partial grants surface the missing authorization ids"
      (let [r (agg/check-held-mutation-aggregate summary members
              {:authorizations {"fa-0" (auth "fa-0" :in 100)}})]
        (is (:valid? r))
        (is (not (:verified? r)))
        (is (= ["fa-1"] (:unverified-authorization-ids r)))))))

(deftest invalid-members-excluded-from-flows-and-cause-failure
  (let [good (mk "m1" :add-held :in 100 "fa-0")
        bad (assoc (mk "m2" :sub-held :out 40 "fa-1") :held/amount 999)]
    (testing "the fail-fast builder rejects invalid members"
      (is (thrown? clojure.lang.ExceptionInfo
                   (agg/build-held-mutation-summary [good bad] {}))))
    (testing "the permissive recompute excludes the invalid member from flows
              and reports it; the aggregate is non-passing"
      (let [summary (agg/recompute-held-mutation-summary [good bad] {})
            r (agg/check-held-mutation-aggregate summary [good bad] {})]
        (is (= 100 (:gross-inflow summary)))
        (is (= 0 (:gross-outflow summary)) "invalid :out member excluded from outflow")
        (is (= 1 (:invalid-count summary)))
        (is (some #(= :content-hash-mismatch (:reason %)) (:invalid-artifacts summary)))
        (is (not (:valid? r)))
        (is (false? (:members-valid? (:checks r))))))))

(deftest empty-summary-is-permitted
  (let [summary (agg/build-held-mutation-summary [] {})]
    (is (= 0 (:gross-inflow summary)))
    (is (= 0 (:gross-outflow summary)))
    (is (= 0 (:gross-flow summary)))
    (is (= 0 (:net-change summary)))
    (is (= {:in 0 :out 0} (:amount-by-direction summary)))))

(deftest flow-arithmetic-and-non-negativity-are-checked
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :sub-held :out 40 "fa-1")]
        tampered (assoc (agg/build-held-mutation-summary members {})
                        :gross-flow 999 :net-change 1)
        r (agg/check-held-mutation-aggregate tampered members {})]
    (is (not (:valid? r)))
    (is (false? (:flow-reconciles? (:checks r))))
    (is (some #(= [:gross-flow] (:path %)) (:mismatches r))))
  (testing "negative gross fields are rejected"
    (let [members [(mk "m1" :add-held :in 100 "fa-0")]
          tampered (assoc (agg/build-held-mutation-summary members {}) :gross-inflow -1)
          r (agg/check-held-mutation-aggregate tampered members {})]
      (is (not (:valid? r)))
      (is (false? (:amounts-non-negative? (:checks r)))))))

(deftest summary-rejects-noncanonical-equivalent-preimage
  (testing "the .v1 summary contract uses :exact and rejects noncanonical
            equivalent preimages"
    (let [members [(mk "m1" :add-held :in 100 "fa-0")]
          summary (agg/build-held-mutation-summary members {})
          p (:artifact/preimage summary)
          whitespaced (assoc summary :artifact/preimage
                             (str "{ " (subs p 1 (dec (count p))) " }"))
          r (agg/check-held-mutation-aggregate whitespaced members {})]
      (is (:valid? (agg/check-held-mutation-aggregate summary members {})))
      (is (not (:valid? r))
          "a noncanonical equivalent summary preimage fails the :exact check")
      (is (false? (:summary-identity-valid? (:checks r)))))))
