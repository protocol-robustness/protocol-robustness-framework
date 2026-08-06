(ns prf.extensions.held-custody.aggregate-test
  "Tests for the held-custody mutation summary and aggregate checker: explicit
   flow fields, canonical zero handling, no :total-amount, the
   :valid? / :verified? distinction, and the consecutive mutation-sequence
   commitment."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.hash.framing-view :as fv]
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

(defn- mk [mutation-id action direction amount auth-id & [consumed-at]]
  (mut/build-force-auth-held-mutation
   (auth auth-id direction amount)
   (cond-> {:mutation/id mutation-id
            :held/action action
            :held/direction direction
            :held/amount amount
            :held/token "USDC"
            :held/account :escrow-principal
            :owner/address "0xrecipient"
            :held/reason :force-authorised-release
            :held/workflow-id 0}
     consumed-at (assoc :consumed-at consumed-at))
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

;; ── consecutive mutation-sequence commitment ────────────────────────────────

(deftest sequence-commits-the-ordered-run
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :finalize-released :out 40 "fa-1")]
        seq-artifact (agg/build-held-mutation-sequence members {})]
    (is (= "force-auth-held-custody-mutation-sequence.v1"
           (:schema-version seq-artifact)))
    (is (= :force-auth-held-custody-mutation-sequence
           (:artifact/kind seq-artifact)))
    (is (= "canonical-value-sequence.v1" (:encoding-contract seq-artifact)))
    (is (= :held-custody-mutations (:purpose seq-artifact)))
    (is (= 2 (:component-count seq-artifact)))
    (is (= ["m1" "m2"] (:member-order seq-artifact)))
    (is (= (mapv :artifact/hash members) (:member-hashes seq-artifact))
        "the ordered member content refs are committed")
    (is (= [:add-held :finalize-released] (:actions seq-artifact))
        "the run self-describes its held actions")
    (is (= [:force-auth-held-custody-mutation] (:kinds seq-artifact))
        "the run self-describes its artifact kinds")
    (is (= {:add-held 1 :finalize-released 1} (:action-counts seq-artifact)))
    (is (= {:force-auth-held-custody-mutation 2} (:kind-counts seq-artifact)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:sequence-hash seq-artifact)))
    (is (some? (:artifact/hash seq-artifact)))))

(deftest sequence-order-is-consecutive-by-consumed-at
  (let [members [(mk "m1" :add-held :in 100 "fa-0" 50)
                 (mk "m2" :add-held :in 10 "fa-1" 10)
                 (mk "m3" :sub-held :out 30 "fa-2" 30)]
        seq-artifact (agg/build-held-mutation-sequence members {})]
    (is (= ["m2" "m3" "m1"] (:member-order seq-artifact))
        "consumed-at ascending commits the consecutive temporal order")
    (testing "a different consecutive order commits different bytes"
      (is (not= (:sequence-hash seq-artifact)
                (:sequence-hash
                 (agg/build-held-mutation-sequence
                  [(mk "m1" :add-held :in 100 "fa-0" 50)
                   (mk "m2" :add-held :in 10 "fa-1" 10)
                   (mk "m3" :sub-held :out 30 "fa-2" 60)]
                  {})))))))

(deftest sequence-recompute-matches-builder-and-validates
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :sub-held :out 40 "fa-1")]
        seq-artifact (agg/build-held-mutation-sequence members {})]
    (is (= seq-artifact (agg/recompute-held-mutation-sequence members {})))
    (let [r (agg/check-held-mutation-sequence seq-artifact members {})]
      (is (:valid? r))
      (is (= :valid (:status r)))
      (is (true? (:sequence-identity-valid? (:checks r))))
      (is (true? (:sequence-recomputes? (:checks r)))))))

(deftest sequence-check-rejects-tampering
  (let [members [(mk "m1" :add-held :in 100 "fa-0")]
        seq-artifact (agg/build-held-mutation-sequence members {})
        tampered (assoc seq-artifact :sequence-hash
                        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        r (agg/check-held-mutation-sequence tampered members {})]
    (is (not (:valid? r)))
    (is (false? (:sequence-identity-valid? (:checks r)))
        "a tampered body fails the exact content round-trip")))

(deftest sequence-check-rejects-divergent-member-set
  (let [one [(mk "m1" :add-held :in 100 "fa-0")]
        both [(mk "m1" :add-held :in 100 "fa-0")
              (mk "m2" :sub-held :out 40 "fa-1")]
        seq-artifact (agg/build-held-mutation-sequence one {})
        r (agg/check-held-mutation-sequence seq-artifact both {})]
    (is (not (:valid? r)))
    (is (false? (:sequence-recomputes? (:checks r)))
        "a valid sequence artifact over a different member run is rejected")
    (is (seq (:mismatches r)))))

(deftest aggregate-folds-sequence-validity
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :sub-held :out 40 "fa-1")]
        summary (agg/build-held-mutation-summary members {})
        seq-artifact (agg/build-held-mutation-sequence members {})]
    (testing "a matching sequence keeps the aggregate valid"
      (let [r (agg/check-held-mutation-aggregate summary members {:sequence seq-artifact})]
        (is (:valid? r))
        (is (true? (:sequence-valid? (:checks r))))))
    (testing "a tampered sequence makes the aggregate non-passing"
      (let [bad (assoc seq-artifact :sequence-hash
                       "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
            r (agg/check-held-mutation-aggregate summary members {:sequence bad})]
        (is (not (:valid? r)))
        (is (false? (:sequence-valid? (:checks r))))))))

(deftest sequence-bytes-frame-as-a-single-canonical-value
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :sub-held :out 40 "fa-1")]
        ba (agg/held-mutation-sequence-bytes members)]
    (testing "the bound sequence is itself one canonical value"
      (let [v (fv/verify-single ba)]
        (is (:canonical? v))
        (is (:single? v))))
    (testing "the framed commitment is self-describing"
      (let [d (fv/frame-stream ba)
            decoded (get-in d [:frames 0 :value])]
        (is (= 1 (count (:frames d))) "one canonical value, not a bare stream")
        (is (= "canonical-value-sequence.v1" (:encoding-contract decoded)))
        (is (= :held-custody-mutations (:purpose decoded)))
        (is (= 2 (:component-count decoded)))
        (is (= 2 (count (:components decoded))))))))

(deftest sequence-rejects-invalid-members-fail-fast
  (let [good (mk "m1" :add-held :in 100 "fa-0")
        bad (assoc (mk "m2" :sub-held :out 40 "fa-1") :held/amount 999)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (agg/build-held-mutation-sequence [good bad] {})))))

(deftest member-kind-attributes-add-held-kind
  (testing "native members report their own artifact kind"
    (is (= :force-auth-held-custody-mutation
           (agg/member-kind (mk "m1" :add-held :in 100 "fa-0")))))
  (testing "projected legacy add-held members are attributed to add-held-kind"
    (is (= :force-auth-add-held
           (agg/member-kind {:mutation/id "m1"
                             :legacy/classification :legacy-direction-bound}))))
  (testing "the add-held-kind constant matches the legacy evidence domain"
    (is (= :force-auth-add-held agg/legacy-add-held-kind))))

(deftest sequence-body-exposes-actions-and-kinds
  (let [body (agg/held-mutation-sequence-body
              [{:artifact/hash "sha256:a"
                :mutation/id "m1" :held/action :add-held :held/amount 100
                :held/consumed-at 10
                :artifact/kind :force-auth-held-custody-mutation}
               {:artifact/hash "sha256:b"
                :mutation/id "m2" :held/action :add-held :held/amount 50
                :held/consumed-at 20
                :legacy/classification :legacy-direction-bound}])]
    (is (= ["m1" "m2"] (:member-order body)))
    (is (= [:add-held] (:actions body)))
    (is (= #{:force-auth-add-held :force-auth-held-custody-mutation}
           (set (:kinds body)))
        "the run self-describes both the native and the add-held kind")
    (is (= {:add-held 2} (:action-counts body)))
    (is (= {:force-auth-add-held 1 :force-auth-held-custody-mutation 1}
           (:kind-counts body)))
    (is (= 150 (:add-held/amount body)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:sequence-hash body)))))

(deftest remote-authority-required-classification
  (testing "a native add-held member is forbidden from local in-process
            authorization (force-auth-add evidence)"
    (is (agg/remote-authority-required? (mk "m1" :add-held :in 100 "fa-0"))))
  (testing "outward mutations are not force-auth-add evidence"
    (is (not (agg/remote-authority-required? (mk "m1" :sub-held :out 40 "fa-0"))))
    (is (not (agg/remote-authority-required?
              (mk "m1" :finalize-released :out 40 "fa-0")))))
  (testing "add-held-kind :force-auth-add-held always requires remote authority"
    (is (agg/remote-authority-required?
         {:artifact/kind :force-auth-add-held :mutation/id "m1"})))
  (testing "a projected legacy member carrying the add-held action is forbidden"
    (is (agg/remote-authority-required?
         {:mutation/id "m1" :held/action :add-held
          :legacy/classification :legacy-direction-bound}))))

(deftest sequence-exposes-force-auth-add-forbidden
  (let [with-add [(mk "m1" :add-held :in 100 "fa-0")
                  (mk "m2" :sub-held :out 40 "fa-1")]
        out-only [(mk "m1" :sub-held :out 40 "fa-0")
                  (mk "m2" :finalize-released :out 10 "fa-1")]
        seq-with-add (agg/build-held-mutation-sequence with-add {})
        seq-out-only (agg/build-held-mutation-sequence out-only {})]
    (is (true? (:force-auth-add-forbidden? seq-with-add)))
    (is (= 1 (:remote-authority-required-count seq-with-add)))
    (is (false? (:force-auth-add-forbidden? seq-out-only)))
    (is (= 0 (:remote-authority-required-count seq-out-only)))))

(deftest sequence-exposes-force-auth-authorised
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :sub-held :out 40 "fa-1")]
        seq-artifact (agg/build-held-mutation-sequence members {})]
    (is (true? (:force-auth-authorised? seq-artifact))
        "every native member is force-authorisation-backed")
    (is (= 2 (:authorisation/id-count seq-artifact)))
    (testing "force-auth-authorised? documents the backing invariant"
      (is (true? (agg/force-auth-authorised? members)))
      (is (false? (agg/force-auth-authorised?
                   [(assoc (first members) :authorization/id nil)]))))))

(deftest sequence-exposes-add-held-amount-posture
  (let [members [(mk "m1" :add-held :in 100 "fa-0")
                 (mk "m2" :add-held :in 50 "fa-1")
                 (mk "m3" :sub-held :out 40 "fa-2")
                 (mk "m4" :finalize-released :out 10 "fa-3")]
        seq-artifact (agg/build-held-mutation-sequence members {})]
    (is (= 2 (:add-held/count seq-artifact)))
    (is (= 150 (:add-held/amount seq-artifact))
        "the run reports the total value added via add-held")
    (is (= {:add-held 150 :finalize-released 10 :sub-held 40}
           (:amount-by-action seq-artifact))
        "amount posture by action, sorted")
    (testing "a run with no add-held reports zero"
      (let [out-only (agg/build-held-mutation-sequence
                      [(mk "m1" :sub-held :out 40 "fa-0")] {})]
        (is (= 0 (:add-held/count out-only)))
        (is (= 0 (:add-held/amount out-only)))
        (is (= {:sub-held 40} (:amount-by-action out-only)))))))
