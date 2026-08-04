(ns resolver-sim.evidence.force-authorisation-aggregate-test
  "Aggregate boundary, membership, and reconciliation tests for the
   force-auth-add-held-summary.v1 aggregate.

   These tests exercise check-aggregate directly (not only through a higher
   level constructor) and pin the exact boundary matrix across the four
   artifact layers:

     force-auth                    base authorization identity/policy/scope/validity
     force-auth-add                authorized add operation / add-specific evidence
     force-auth-add-held           the content-addressed member artifact
     force-auth-add-held-summary.v1  the derived aggregate over members

   The two lower layers exist only inside :force-auth-add-held in this codebase;
   the base/add validators are deliberately polymorphic and the exact-kind
   predicates exist so boundary-sensitive code never relies on them."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.force-authorisation :as fa]
            [resolver-sim.evidence.force-authorisation :as e]
            [resolver-sim.hash.canonical :as hc]))

;; ── Fixtures ────────────────────────────────────────────────────────────────

(defn- scope-for
  "A force-authorisation scope map keyed by canonical keyword fields."
  [owner amount]
  {:authorization/id "fa-0"
   :authorization/type :force-authorisation
   :held/direction :out
   :token "USDC"
   :amount amount
   :held/account :escrow-principal
   :owner/address owner
   :held/reason :force-authorised-release
   :held/workflow-id 0})

(defn- auth-for
  "An authorization record whose scope-hash authenticates the given scope."
  [id scope-map]
  (let [scope (fa/normalize-force-authorisation-scope scope-map)]
    {:authorization/id id
     :authorization/status :active
     :authorization/type :force-authorisation
     :authorization/scope-hash (fa/force-authorisation-scope-hash scope)
     :authorization/scope scope
     :starts-at 0
     :expires-at 1000}))

(defn- mk-member
  "Build a canonical force-auth-add-held member artifact."
  ([token amount direction]
   (mk-member token amount direction "adj-1"))
  ([token amount direction adjustment-id]
   (e/build-force-auth-add-held
    {:authorization (auth-for "fa-0" (scope-for "0xrecipient" 5000))
     :scope-map (scope-for "0xrecipient" 5000)
     :adjustment {:held-adjustment/id adjustment-id
                  :token token
                  :amount amount
                  :held/direction direction}
     :consumed-at 500
     :consumed-by "0xgov"})))

(defn- rehash
  "Rebuild an artifact's :artifact/hash and :artifact/preimage over a body,
   matching the evidence namespace finalize-artifact convention (the envelope —
   including any optional canonical commitment — is stripped before hashing)."
  [body]
  (let [clean (apply dissoc body e/artifact-envelope-keys)
        h (str "sha256:" (hc/domain-hash :evidence-record clean))]
    (assoc clean :artifact/hash h :artifact/preimage (pr-str clean))))

(defn- tamper
  "Apply changes and recompute the content hash over the exact resulting body."
  [artifact & {:as changes}]
  (rehash (merge artifact changes)))

(defn- v1-projected-relabel
  "Craft a v1-labeled summary carrying v2-only fields whose content hash is
   computed over the v1 projection — the exact artifact the projection-based
   migration reader accepts but the exact-shape aggregate check must reject."
  [v1-summary & {:as changes}]
  (let [body (merge v1-summary changes)
        projected (e/downgrade-add-held-summary-v2->v1 body)]
    (assoc (apply dissoc body e/artifact-envelope-keys)
           :artifact/hash (str "sha256:" (hc/domain-hash :evidence-record projected))
           :artifact/preimage (pr-str projected))))

(defn- permissive-v1
  "Build a permissive v1 summary via the legacy v2 builder + v1 projection, for
   mixed-validity member sets the fail-fast builders reject."
  [member-set]
  (rehash (e/downgrade-add-held-summary-v2->v1
           (e/build-force-auth-add-held-summary-permissive member-set {}))))

(def ^:private members
  (vec [(mk-member :USDC 100 :in "adj-1")
        (mk-member :ETH 50 :in "adj-2")]))

(defn- summary-v1 []
  (e/build-force-auth-add-held-summary-v1 members {}))

(defn- summary-v2 []
  (e/build-force-auth-add-held-summary members {}))

;; ── Conceptual layer fixtures for the boundary matrix ──────────────────────

(def ^:private force-auth-artifact
  "A conceptual :force-auth artifact (exact base layer). No production builder
   produces this standalone artifact — it is a boundary fixture."
  {:schema-version e/force-auth-schema-version
   :artifact/kind e/force-auth-kind
   :artifact/verifier e/force-auth-verifier-id
   :authorization/id "fa-0"
   :authorization/type :force-authorisation
   :authorization/scope-hash "0xscope"
   :authorization/scope-verifies? true})

(def ^:private force-auth-add-artifact
  "A conceptual :force-auth-add artifact (exact add layer). No production
   builder produces this standalone artifact — it is a boundary fixture."
  {:schema-version e/force-auth-add-schema-version
   :artifact/kind e/force-auth-add-kind
   :artifact/verifier e/force-auth-add-verifier-id
   :authorization/id "fa-0"
   :authorization/type :force-authorisation
   :authorization/scope-hash "0xscope"
   :authorization/scope-verifies? true
   :held/adjustment-id "adj-9"
   :held/token :USDC
   :held/direction :in
   :held/amount 100
   :held/account :escrow-principal})

;; ── Boundary matrix ─────────────────────────────────────────────────────────

(deftest exact-boundary-matrix
  (testing "a valid force-auth passes only the (polymorphic) base validator"
    (is (e/valid-force-auth? force-auth-artifact))
    (is (e/exact-force-auth? force-auth-artifact))
    (is (not (e/valid-force-auth-add? force-auth-artifact)))
    (is (not (e/valid-force-auth-add-held? force-auth-artifact)))
    (is (not (e/valid-force-auth-add-held-summary-v1? force-auth-artifact [] {}))))

  (testing "a valid force-auth-add passes base + add, never held/summary"
    (is (e/valid-force-auth? force-auth-add-artifact) "base relationship supported")
    (is (not (e/exact-force-auth? force-auth-add-artifact))
        "polymorphic base acceptance does not make it an exact force-auth")
    (is (e/valid-force-auth-add? force-auth-add-artifact))
    (is (e/exact-force-auth-add? force-auth-add-artifact))
    (is (not (e/valid-force-auth-add-held? force-auth-add-artifact)))
    (is (not (e/valid-force-auth-add-held-summary-v1? force-auth-add-artifact [] {}))))

  (testing "a valid force-auth-add-held passes base and add relationships but is not a summary"
    (let [member (nth members 0)]
      (is (e/valid-force-auth? member) "base relationship supported")
      (is (not (e/exact-force-auth? member)))
      (is (e/valid-force-auth-add? member) "add relationship supported")
      (is (not (e/exact-force-auth-add? member)))
      (is (e/valid-force-auth-add-held? member))
      (is (e/exact-force-auth-add-held? member))
      (is (not (e/valid-force-auth-add-held-summary-v1? member [] {})))))

  (testing "a valid summary fails every lower layer and passes the summary validator"
    (let [v1 (summary-v1)]
      (is (not (e/valid-force-auth? v1)))
      (is (not (e/valid-force-auth-add? v1)))
      (is (not (e/valid-force-auth-add-held? v1)))
      (is (e/valid-force-auth-add-held-summary-v1? v1 members {})))))

(deftest polymorphic-vs-exact-predicates
  (testing "exact-kind predicates never accept an adjacent layer"
    (is (not (e/exact-force-auth? (nth members 0))))
    (is (not (e/exact-force-auth-add? (nth members 0))))
    (is (not (e/exact-force-auth-add-held? force-auth-artifact)))
    (is (not (e/exact-force-auth-add-held? force-auth-add-artifact)))
    (is (not (e/exact-force-auth-add-held? (summary-v1))))))

;; ── Aggregate identity ──────────────────────────────────────────────────────

(deftest valid-summary-and-members
  (let [v1 (summary-v1)
        result (e/check-aggregate v1 members {})]
    (is (true? (:valid? result)))
    (is (= :valid (:status result)))
    (is (= :force-auth-add-held-summary (:aggregate-kind result)))
    (is (= "force-auth-add-held-summary.v1" (:schema-version result)))
    (is (= 2 (:member-count result)))
    (is (= 2 (:valid-member-count result)))
    (is (= 0 (:invalid-member-count result)))
    (is (empty? (:invalid-members result)))
    (is (empty? (:mismatches result)))
    (is (true? (:summary-recomputes? (:checks result))))
    (is (true? (:aggregate-hash-valid? (:checks result))))
    (is (true? (:aggregate-shape-valid? (:checks result))))
    (is (true? (:member-set-complete? (:checks result))))
    (is (e/valid-force-auth-add-held-summary-v1? v1 members {}))))

(deftest predicate-delegates-to-check-aggregate
  (let [v1 (summary-v1)]
    (is (= (:valid? (e/check-aggregate v1 members {}))
           (e/valid-force-auth-add-held-summary-v1? v1 members {})))
    (is (= (:valid? (e/check-aggregate (summary-v2) members {:summary-version :v2}))
           (e/valid-force-auth-add-held-summary? (summary-v2) members {})))))

(deftest v2-does-not-pass-as-v1
  (testing "a .v2 summary cannot pass as .v1 merely because all v1 keys are present"
    (let [v2 (summary-v2)]
      (is (= "force-auth-add-held-summary.v2" (:schema-version v2)))
      (is (not (e/valid-force-auth-add-held-summary-v1? v2)))
      (is (not (:aggregate-schema-valid?
                (:checks (e/check-aggregate v2 members {}))))
          "check-aggregate (v1 target) rejects the v2 schema version")
      (is (e/valid-force-auth-add-held-summary? v2)))))

(deftest aggregate-kind-and-verifier-mismatch
  (let [v1 (summary-v1)
        wrong-kind (tamper v1 :artifact/kind :not-a-summary)
        wrong-verifier (tamper v1 :artifact/verifier "wrong-verifier.v1")
        wrong-hash (assoc v1 :artifact/hash "sha256:forged")]
    (is (not (:aggregate-kind-valid? (:checks (e/check-aggregate wrong-kind members {})))))
    (is (not (:aggregate-verifier-valid? (:checks (e/check-aggregate wrong-verifier members {})))))
    (is (not (:aggregate-hash-valid? (:checks (e/check-aggregate wrong-hash members {})))))))

(deftest v2-only-field-injected-into-v1-summary
  (testing "a .v2-only field in a .v1 summary is rejected by the aggregate check"
    (let [v1 (summary-v1)
          injected (tamper v1 :min-amount 5)]
      (is (not (:valid? (e/check-aggregate injected members {}))))
      (is (false? (:aggregate-shape-valid? (:checks (e/check-aggregate injected members {}))))))))

(deftest unknown-key-in-exact-shape-artifact
  (testing "an unknown key injected into a v1 summary is rejected"
    (let [v1 (summary-v1)
          injected (tamper v1 :bogus-key :x)]
      (is (not (:valid? (e/check-aggregate injected members {}))))
      (is (false? (:aggregate-shape-valid? (:checks (e/check-aggregate injected members {}))))))))

(deftest v1-migration-reader-vs-aggregate-shape-strictness
  (testing "the projection-based migration reader tolerates v2-only keys ONLY when
            explicitly invoked; the exact reader and check-aggregate reject them"
    (let [v1 (summary-v1)
          relabeled (v1-projected-relabel v1 :min-amount 5)]
      (is (not (e/valid-force-auth-add-held-summary-v1? relabeled))
          "the exact v1 reader rejects v2-only keys")
      (is (e/valid-force-auth-add-held-summary-v1-migration? relabeled)
          "the migration reader projects v2-only keys away before hashing — explicit migration only")
      (is (not (:valid? (e/check-aggregate relabeled members {})))
          "check-aggregate rejects the exact-shape violation")
      (is (false? (:aggregate-shape-valid? (:checks (e/check-aggregate relabeled members {}))))))))

(deftest exact-v1-reader-rejects-v2-only-and-unknown-keys
  (let [v1 (summary-v1)
        v2-only (tamper v1 :min-amount 5)
        unknown (tamper v1 :bogus-key :x)]
    (is (not (e/valid-force-auth-add-held-summary-v1? v2-only)))
    (is (not (e/valid-force-auth-add-held-summary-v1? unknown)))
    (is (e/valid-force-auth-add-held-summary-v1? v1))
    (is (e/valid-force-auth-add-held-summary-v1-migration? v1))))

(deftest v2-cannot-pass-as-v1-through-any-boundary
  (let [v2 (summary-v2)]
    (is (not (e/valid-force-auth-add-held-summary-v1? v2)) "exact v1 reader")
    (is (not (e/valid-force-auth-add-held-summary-v1-migration? v2)) "migration reader")
    (is (not (:aggregate-schema-valid? (:checks (e/check-aggregate v2 members {})))) "check-aggregate (v1 target)")
    (is (not (e/valid-force-auth-add-held-summary-v1? v2 members {})) "3-arity aggregate predicate")))

;; ── Member identity and classification ─────────────────────────────────────

(defn- reasons-of [result]
  (mapv :reason (:invalid-members result)))

(deftest base-force-auth-inserted-as-member
  (let [v1 (summary-v1)
        result (e/check-aggregate v1 [force-auth-artifact (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:artifact-kind-mismatch] (reasons-of result)))))

(deftest force-auth-add-inserted-as-held-member
  (let [v1 (summary-v1)
        result (e/check-aggregate v1 [force-auth-add-artifact (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:artifact-kind-mismatch] (reasons-of result)))))

(deftest summary-inserted-into-own-member-set
  (let [v1 (summary-v1)
        result (e/check-aggregate v1 [v1 (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:artifact-kind-mismatch] (reasons-of result)))))

(deftest raw-authorization-record-inserted-as-member
  (let [v1 (summary-v1)
        raw (auth-for "fa-0" (scope-for "0xrecipient" 5000))
        result (e/check-aggregate v1 [raw (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:not-force-auth-add-held] (reasons-of result)))))

(deftest nil-and-non-map-members-do-not-throw
  (let [v1 (summary-v1)
        result (e/check-aggregate v1 [(nth members 0) nil] {})
        scalar (e/check-aggregate v1 [42 "nope"] {})]
    (is (not (:valid? result)))
    (is (= [:not-force-auth-add-held] (reasons-of result)))
    (is (not (:valid? scalar)))
    (is (= [:not-force-auth-add-held :not-force-auth-add-held] (reasons-of scalar)))))

(deftest artifact-kind-changed-without-recomputed-hash
  (let [v1 (summary-v1)
        member (assoc (nth members 0) :artifact/kind :force-auth-add)
        result (e/check-aggregate v1 [member (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:artifact-kind-mismatch] (reasons-of result)))))

(deftest artifact-kind-changed-with-recomputed-hash
  (let [v1 (summary-v1)
        member (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                              :artifact/kind :force-auth-add))
        result (e/check-aggregate v1 [member (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:artifact-kind-mismatch] (reasons-of result))
        "a self-consistent artifact of the wrong kind is still not a member")))

(deftest schema-version-changed
  (let [v1 (summary-v1)
        future-member (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                     :schema-version "force-auth-add-held.v3"))
        wrong-member (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                    :schema-version "not-a-held.v1"))
        relabeled-v2 (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                    :schema-version "force-auth-add-held.v2"))
        result-future (e/check-aggregate v1 [future-member (nth members 1)] {})
        result-wrong (e/check-aggregate v1 [wrong-member (nth members 1)] {})
        result-v2 (e/check-aggregate v1 [relabeled-v2 (nth members 1)] {})]
    (is (not (:valid? result-future)))
    (is (= [:unsupported-member-version] (reasons-of result-future)))
    (is (not (:valid? result-wrong)))
    (is (= [:schema-version-mismatch] (reasons-of result-wrong)))
    (is (not (:valid? result-v2)))
    (is (= [:content-hash-mismatch] (reasons-of result-v2))
        "a v1 artifact relabeled to the now-supported v2 schema fails canonical v2 verification")))

(deftest verifier-identifier-changed
  (let [v1 (summary-v1)
        member (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                              :artifact/verifier "wrong-verifier.v1"))
        result (e/check-aggregate v1 [member (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:verifier-mismatch] (reasons-of result)))))

(deftest content-hash-corrupted
  (let [v1 (summary-v1)
        member (assoc (nth members 0) :artifact/hash "sha256:forged")
        result (e/check-aggregate v1 [member (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:content-hash-mismatch] (reasons-of result)))))

(deftest missing-authorization-and-adjustment-ids
  (let [v1 (summary-v1)
        no-auth (rehash (dissoc (nth members 0) :authorization/id))
        no-adj (rehash (dissoc (nth members 0) :held/adjustment-id))
        r1 (e/check-aggregate v1 [no-auth (nth members 1)] {})
        r2 (e/check-aggregate v1 [no-adj (nth members 1)] {})]
    (is (not (:valid? r1)))
    (is (= [:missing-authorization-id] (reasons-of r1)))
    (is (not (:valid? r2)))
    (is (= [:missing-adjustment-id] (reasons-of r2)))))

(deftest unverified-authorization-binding-is-a-failure
  (let [v1 (summary-v1)
        unverified (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                  :authorization/scope-verifies? false))
        result (e/check-aggregate v1 [unverified (nth members 1)] {})]
    (is (not (:valid? result)))
    (is (= [:authorization-binding-mismatch] (reasons-of result)))))

;; ── Aggregate / member reconciliation ──────────────────────────────────────

(deftest incorrect-aggregate-total
  (let [v1 (tamper (summary-v1) :total-amount 999)
        result (e/check-aggregate v1 members {})
        total-mismatch (first (filter #(= [:total-amount] (:path %))
                                      (:mismatches result)))]
    (is (not (:valid? result)))
    (is (false? (:summary-recomputes? (:checks result))))
    (is (some #(= [:total-amount] (:path %)) (:mismatches result)))
    (is (= 150 (:expected total-mismatch)))))

(deftest incorrect-category-subtotal
  (let [v1 (tamper (summary-v1) :by-token {:USDC 99 :ETH 50})
        result (e/check-aggregate v1 members {})]
    (is (not (:valid? result)))
    (is (some #(= [:by-token] (:path %)) (:mismatches result)))))

(deftest incorrect-min-max-amount-v2
  (let [v2 (summary-v2)
        tampered (tamper v2 :min-amount 5 :max-amount 999)
        result (e/check-aggregate tampered members {:summary-version :v2})]
    (is (not (:valid? result)))
    (is (some #(= [:min-amount] (:path %)) (:mismatches result)))
    (is (some #(= [:max-amount] (:path %)) (:mismatches result)))))

(deftest incorrect-earliest-latest-timestamp-v2
  (let [v2 (summary-v2)
        tampered (tamper v2 :consumed-at-earliest 1 :consumed-at-latest 1)
        result (e/check-aggregate tampered members {:summary-version :v2})]
    (is (not (:valid? result)))
    (is (some #(= [:consumed-at-earliest] (:path %)) (:mismatches result)))
    (is (some #(= [:consumed-at-latest] (:path %)) (:mismatches result)))))

(deftest invalid-member-excluded-from-totals-but-fails-aggregate
  (testing "an invalid member causes aggregate failure, is reported in triage,
            and its amount never enters the totals (builder and recompute share
            the same canonical derivation)"
    (let [good (nth members 0)
          bad (assoc (nth members 1) :held/amount 999)
          v1 (permissive-v1 [good bad])
          result (e/check-aggregate v1 [good bad] {})]
      (is (not (:valid? result)))
      (is (false? (:members-valid? (:checks result))))
      (is (= [:content-hash-mismatch] (reasons-of result)))
      (is (= 100 (:total-amount v1)) "the invalid member's amount is excluded from the stored totals")
      (is (= 100 (:total-amount (e/recompute-force-auth-add-held-summary [good bad] {})))
          "the recomputation excludes it identically")
      (is (true? (:summary-recomputes? (:checks result)))
          "the stored summary honestly reflects the member set (shared derivation)")
      (is (= 1 (:invalid-count v1))))))

(deftest amount-integrity-triage-without-coercion
  (let [missing (rehash (dissoc (nth members 0) :held/amount))
        nil-amount (rehash (assoc (nth members 0) :held/amount nil))
        string-amount (rehash (assoc (nth members 0) :held/amount "100"))
        negative (rehash (assoc (nth members 0) :held/amount -50))
        r-missing (e/check-aggregate (e/build-force-auth-add-held-summary-v1 [missing] {}) [missing] {})
        r-nil (e/check-aggregate (e/build-force-auth-add-held-summary-v1 [nil-amount] {}) [nil-amount] {})
        r-string (e/check-aggregate (e/build-force-auth-add-held-summary-v1 [string-amount] {}) [string-amount] {})
        r-negative (e/check-aggregate (e/build-force-auth-add-held-summary-v1 [negative] {}) [negative] {})]
    (testing "missing / nil / string amounts never contribute to totals and never throw"
      (is (empty? (:invalid-members r-missing)))
      (is (empty? (:invalid-members r-nil)))
      (is (empty? (:invalid-members r-string)))
      (is (= 0 (:total-amount (e/recompute-force-auth-add-held-summary [missing] {}))))
      (is (= 0 (:total-amount (e/recompute-force-auth-add-held-summary [nil-amount] {}))))
      (is (= 0 (:total-amount (e/recompute-force-auth-add-held-summary [string-amount] {}))))
      (is (some #(= :missing-amount (:kind %)) (:warnings r-missing)))
      (is (some #(= :missing-amount (:kind %)) (:warnings r-nil)))
      (is (some #(= :non-numeric-amount (:kind %)) (:warnings r-string))))
    (testing "a negative amount is numeric and contributes, but is triaged"
      (is (empty? (:invalid-members r-negative)))
      (is (= -50 (:total-amount (e/recompute-force-auth-add-held-summary [negative] {}))))
      (is (some #(= :negative-amount (:kind %)) (:warnings r-negative))))))

;; ── Completeness and membership ─────────────────────────────────────────────

(deftest missing-committed-member
  (let [three [(nth members 0) (nth members 1) (mk-member :DAI 25 :in "adj-3")]
        v1 (e/build-force-auth-add-held-summary-v1 three {})
        result (e/check-aggregate v1 (subvec three 0 2) {})]
    (is (not (:valid? result)))
    (is (false? (:member-set-complete? (:checks result))))
    (is (false? (:summary-recomputes? (:checks result))))
    (is (= 3 (:total v1)))
    (is (= 2 (:member-count result)))))

(deftest extra-uncommitted-member
  (let [extra (mk-member :DAI 25 :in "adj-3")
        v1 (e/build-force-auth-add-held-summary-v1 members {})
        result (e/check-aggregate v1 (conj members extra) {})]
    (is (not (:valid? result)))
    (is (false? (:member-set-complete? (:checks result))))
    (is (false? (:summary-recomputes? (:checks result))))))

(deftest correct-count-but-incorrect-member-set
  (testing "count equality alone is not completeness: a different member set with
            the same cardinality is detected through field reconciliation"
    (let [other [(mk-member :USDC 300 :in "adj-8")
                 (mk-member :DAI 25 :in "adj-9")]
          v1 (summary-v1)
          result (e/check-aggregate v1 other {})]
      (is (= 2 (:total v1)))
      (is (= 2 (:member-count result)))
      (is (true? (:member-set-complete? (:checks result)))
          "count-based completeness is not enough")
      (is (false? (:summary-recomputes? (:checks result))))
      (is (not (:valid? result)))
      (is (some #(= [:total-amount] (:path %)) (:mismatches result)))
      (is (some #(= [:by-token] (:path %)) (:mismatches result))))))

(deftest reordered-members-order-is-irrelevant
  (let [v1 (summary-v1)
        forward (e/check-aggregate v1 members {})
        reversed (e/check-aggregate v1 (reverse members) {})]
    (is (true? (:valid? forward)))
    (is (true? (:valid? reversed)))
    (is (empty? (:mismatches reversed)))
    (is (empty? (:invalid-members reversed)))))

(deftest empty-aggregate-policy
  (testing "the documented empty-set policy permits an empty aggregate"
    (let [empty (e/build-force-auth-add-held-summary-v1 [] {})
          result (e/check-aggregate empty [] {})]
      (is (true? (:valid? result)))
      (is (= 0 (:member-count result)))
      (is (= 0 (:total-amount empty)))
      (is (e/valid-force-auth-add-held-summary-v1? empty [] {}))))
  (testing "an empty summary cannot be reconciled against a non-empty member set"
    (let [empty (e/build-force-auth-add-held-summary-v1 [] {})
          result (e/check-aggregate empty members {})]
      (is (not (:valid? result)))
      (is (false? (:member-set-complete? (:checks result)))))))

(deftest cannot-claim-more-valid-members-than-checked
  (let [three [(nth members 0) (nth members 1) (mk-member :DAI 25 :in "adj-3")]
        v1 (e/build-force-auth-add-held-summary-v1 three {})
        result (e/check-aggregate v1 (subvec three 0 2) {})]
    (is (= 3 (:valid-count v1)) "the summary claims three valid members")
    (is (= 2 (:valid-member-count result)) "only two were actually checked")
    (is (false? (:valid? result)))))

(deftest duplicate-adjustment-id-detected
  (let [dups [(mk-member :USDC 100 :in "adj-1")
              (mk-member :ETH 50 :in "adj-1")]
        v1 (e/build-force-auth-add-held-summary-v1 dups {})
        lenient (e/check-aggregate v1 dups {})
        strict (e/check-aggregate v1 dups {:unique-adjustment-ids? true})]
    (testing "duplicate adjustment ids are a warning by default"
      (is (true? (:valid? lenient)))
      (is (some #(and (= :duplicate-adjustment-id (:kind %))
                      (= "adj-1" (:value %)))
                (:warnings lenient))))
    (testing "and a hard failure when uniqueness is required"
      (is (not (:valid? strict)))
      (is (= [:duplicate-adjustment-id :duplicate-adjustment-id]
             (reasons-of strict))))))

(deftest duplicate-authorization-id-detected-when-required
  (let [dups [(mk-member :USDC 100 :in "adj-1")
              (mk-member :ETH 50 :in "adj-2")]
        v1 (e/build-force-auth-add-held-summary-v1 dups {})
        lenient (e/check-aggregate v1 dups {})
        strict (e/check-aggregate v1 dups {:unique-authorization-ids? true})]
    (testing "duplicate authorization ids are allowed by default (many adjustments per grant)"
      (is (true? (:valid? lenient)))
      (is (some #(and (= :duplicate-authorization-id (:kind %))
                      (= "fa-0" (:value %)))
                (:warnings lenient))))
    (testing "and fail the aggregate when uniqueness is required"
      (is (not (:valid? strict)))
      (is (= [:duplicate-authorization-id :duplicate-authorization-id]
             (reasons-of strict))))))

(deftest duplicate-member-identical-artifact-detected
  (let [same [(nth members 0) (nth members 0)]
        v1 (permissive-v1 same)
        result (e/check-aggregate v1 same {})]
    (is (not (:valid? result)))
    (is (false? (:member-identities-unique? (:checks result))))
    (is (every? #(= :duplicate-member (:reason %)) (:invalid-members result)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (e/build-force-auth-add-held-summary-v1 same {}))
        "the fail-fast builder rejects identical duplicate members")))

(deftest inconsistent-authorization-scope
  (testing "two valid members binding the same authorization id to different scopes
            are outside the aggregate's declared scope"
    (let [scope-a (scope-for "0xrecipient" 5000)
          scope-b (scope-for "0xother" 7000)
          m-a (e/build-force-auth-add-held
               {:authorization (auth-for "fa-0" scope-a)
                :scope-map scope-a
                :adjustment {:held-adjustment/id "adj-1" :token :USDC :amount 100 :held/direction :in}})
          m-b (e/build-force-auth-add-held
               {:authorization (auth-for "fa-0" scope-b)
                :scope-map scope-b
                :adjustment {:held-adjustment/id "adj-2" :token :ETH :amount 50 :held/direction :in}})
          v1 (permissive-v1 [m-a m-b])
          result (e/check-aggregate v1 [m-a m-b] {})]
      (is (true? (:authorization/scope-verifies? m-a)))
      (is (true? (:authorization/scope-verifies? m-b)))
      (is (not (nil? (:authorization/id m-a))))
      (is (not (:valid? result)))
      (is (every? #(= :authorization-binding-mismatch (:reason %))
                  (:invalid-members result)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (e/build-force-auth-add-held-summary-v1 [m-a m-b] {}))
          "the fail-fast builder rejects conflicting bindings"))))

;; ── Developer-facing API ────────────────────────────────────────────────────

(deftest construction-and-validation-are-separate
  (testing "2-arity construction API"
    (is (= (e/build-force-auth-add-held-summary {:artifacts members})
           (e/build-force-auth-add-held-summary members {})))
    (is (= (e/build-force-auth-add-held-summary-v1 {:artifacts members})
           (e/build-force-auth-add-held-summary-v1 members {}))))
  (testing "the fail-fast builder rejects non-passing members with structured diagnostics"
    (let [good (nth members 0)
          bad (assoc (nth members 1) :held/amount 999)
          ex (try (e/build-force-auth-add-held-summary [good bad] {})
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= 1 (:invalid-count (ex-data ex))))
      (is (= [:content-hash-mismatch]
             (mapv :reason (:invalid-members (ex-data ex)))))))
  (testing "a permissive builder output can be non-passing under check-aggregate"
    (let [good (nth members 0)
          bad (assoc (nth members 1) :held/amount 999)
          built (permissive-v1 [good bad])]
      (is (e/valid-force-auth-add-held-summary-v1? built) "content-hash reader passes")
      (is (not (e/valid-force-auth-add-held-summary-v1? built [good bad] {}))
          "aggregate predicate fails"))))

(deftest recompute-is-a-pure-projection
  (let [expected (e/build-force-auth-add-held-summary-v1 members {})
        recomputed (e/recompute-force-auth-add-held-summary members {})
        reversed (e/recompute-force-auth-add-held-summary (reverse members) {})]
    (is (= expected recomputed)
        "for an all-valid member set the canonical recomputation equals the builder")
    (is (= expected reversed)
        "recomputation is order-independent")
    (is (= 150 (:total-amount recomputed)))
    (is (string? (:artifact/hash recomputed)))
    (is (= "force-auth-add-held-summary.v1" (:schema-version recomputed)))))

(deftest check-aggregate-is-deterministic-data-only-and-non-throwing
  (let [v1 (summary-v1)
        a (e/check-aggregate v1 members {})
        b (e/check-aggregate v1 members {})
        nil-summary (e/check-aggregate nil members {})
        scalar-summary (e/check-aggregate 42 members {})
        nil-members (e/check-aggregate v1 nil {})]
    (is (= a b))
    (is (false? (:valid? nil-summary)))
    (is (false? (:valid? scalar-summary)))
    (is (false? (:valid? nil-members)))
    (is (= :invalid (:status nil-summary)))
    (is (map? (:checks nil-summary)))
    (is (vector? (:invalid-members scalar-summary)) "malformed summary: still structured")))

(deftest unsupported-option-is-a-programmer-error
  (let [v1 (summary-v1)
        ex (try (e/check-aggregate v1 members {:summary-version :v9})
                (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? ex))
    (is (= :v9 (:summary-version (ex-data ex))))))

;; ── Preimage integrity (hardened valid-artifact?) ──────────────────────────

(deftest preimage-integrity-adversarial
  (testing "correct body + correct hash + forged preimage is rejected"
    (let [member (nth members 0)
          forged (assoc member :artifact/preimage "forged-preimage")
          v1 (summary-v1)
          forged-summary (assoc v1 :artifact/preimage "forged-preimage")]
      (is (not (e/valid-force-auth-add-held? forged)))
      (is (not (e/valid-force-auth-add-held-summary-v1? forged-summary)))
      (is (not (e/valid-force-auth-add-held-summary? forged-summary)))))
  (testing "correct body + correct preimage + forged hash is rejected"
    (let [member (nth members 0)
          forged (assoc member :artifact/hash "sha256:forged")
          v1 (summary-v1)
          forged-summary (assoc v1 :artifact/hash "sha256:forged")]
      (is (not (e/valid-force-auth-add-held? forged)))
      (is (not (e/valid-force-auth-add-held-summary-v1? forged-summary)))))
  (testing "mutated body with the original preimage/hash is rejected"
    (let [member (assoc (nth members 0) :held/amount 999)
          v1 (summary-v1)
          mutated (assoc v1 :total-amount 999)]
      (is (not (e/valid-force-auth-add-held? member)))
      (is (not (e/valid-force-auth-add-held-summary-v1? mutated)))
      (is (false? (:aggregate-hash-valid?
                   (:checks (e/check-aggregate mutated members {})))))))
  (testing "non-string preimage is rejected"
    (is (not (e/valid-force-auth-add-held? (assoc (nth members 0) :artifact/preimage 42))))
    (is (not (e/valid-force-auth-add-held-summary-v1? (assoc (summary-v1) :artifact/preimage 42))))))

(deftest preimage-integrity-valid-artifacts-still-pass
  (let [member (nth members 0)
        v1 (summary-v1)
        v2 (summary-v2)
        lifecycle (e/build-force-auth-lifecycle
                   {:authorisations {"fa-0" (auth-for "fa-0" (scope-for "0xrecipient" 5000))}
                    :consumption-registry {}})
        lifecycle-summary (e/build-force-auth-lifecycle-summary
                           {:authorisations {"fa-0" (auth-for "fa-0" (scope-for "0xrecipient" 5000))}
                            :consumption-registry {}})]
    (is (e/valid-force-auth-add-held? member))
    (is (e/valid-force-auth-add-held-summary-v1? v1))
    (is (e/valid-force-auth-add-held-summary? v2))
    (is (e/valid-force-auth-lifecycle? lifecycle))
    (is (e/valid-force-auth-lifecycle-summary? lifecycle-summary))))

;; ── Fail-fast construction (builder / validator alignment) ─────────────────

(deftest fail-fast-builder-rejects-non-passing-members
  (testing "lower-layer artifacts are rejected as members"
    (let [ex (try (e/build-force-auth-add-held-summary
                   [force-auth-artifact (nth members 1)] {})
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= [:artifact-kind-mismatch] (mapv :reason (:invalid-members (ex-data ex)))))))
  (testing "malformed members are rejected"
    (let [ex (try (e/build-force-auth-add-held-summary [nil] {})
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= [:not-force-auth-add-held] (mapv :reason (:invalid-members (ex-data ex)))))))
  (testing "unverified authorization bindings are rejected"
    (let [unverified (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                    :authorization/scope-verifies? false))
          ex (try (e/build-force-auth-add-held-summary [unverified] {})
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= [:authorization-binding-mismatch]
             (mapv :reason (:invalid-members (ex-data ex))))))))

(deftest fail-fast-builder-permits-domain-multiplicity
  (testing "duplicate adjustment ids remain permitted by default policy"
    (let [dups [(mk-member :USDC 100 :in "adj-1")
                (mk-member :ETH 50 :in "adj-1")]
          built (e/build-force-auth-add-held-summary dups {})]
      (is (= 2 (:valid-count built)))
      (is (= 1 (:distinct-adjustment-ids built)))))
  (testing "strict uniqueness options fail fast when enabled"
    (let [dups [(mk-member :USDC 100 :in "adj-1")
                (mk-member :ETH 50 :in "adj-1")]
          ex (try (e/build-force-auth-add-held-summary dups {:unique-adjustment-ids? true})
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex))
      (is (= [:duplicate-adjustment-id :duplicate-adjustment-id]
             (mapv :reason (:invalid-members (ex-data ex))))))))

(deftest fail-fast-builder-output-passes-check-aggregate
  (let [built (e/build-force-auth-add-held-summary members {})
        result (e/check-aggregate built members {:summary-version :v2})]
    (is (true? (:valid? result)))
    (is (empty? (:invalid-members result)))
    (is (empty? (:mismatches result)))))

;; ── v2 recomputation reproducibility ───────────────────────────────────────

(deftest recompute-v2-reproduces-builder
  (testing "all-valid members"
    (is (= (e/build-force-auth-add-held-summary members {})
           (e/recompute-force-auth-add-held-summary members {:summary-version :v2}))))
  (testing "mixed valid/invalid members (permissive path)"
    (let [mixed [(nth members 0) (assoc (nth members 1) :held/amount 999)]]
      (is (= (e/build-force-auth-add-held-summary-permissive mixed {})
             (e/recompute-force-auth-add-held-summary mixed {:summary-version :v2})))))
  (testing "verified and unverified authorisations (permissive path)"
    (let [unverified (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                    :authorization/scope-verifies? false))
          set-with-unverified [(nth members 1) unverified]]
      (is (= (e/build-force-auth-add-held-summary-permissive set-with-unverified {})
             (e/recompute-force-auth-add-held-summary set-with-unverified {:summary-version :v2})))))
  (testing "empty input"
    (is (= (e/build-force-auth-add-held-summary [] {})
           (e/recompute-force-auth-add-held-summary [] {:summary-version :v2})))
    (is (= (e/build-force-auth-add-held-summary-v1 [] {})
           (e/recompute-force-auth-add-held-summary [] {}))))
  (testing "triage fields are reproduced, not silently dropped"
    (let [mixed [(nth members 0) (assoc (nth members 1) :held/amount 999)]
          recomputed (e/recompute-force-auth-add-held-summary mixed {:summary-version :v2})]
      (is (= 1 (:invalid-count recomputed)))
      (is (some #(= :content-hash-mismatch (:reason %))
                (:invalid-artifacts recomputed)))
      (is (= [] (:unverified-authorization-ids recomputed))))
    (let [unverified (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                    :authorization/scope-verifies? false))
          recomputed (e/recompute-force-auth-add-held-summary [unverified] {:summary-version :v2})]
      (is (= ["fa-0"] (:unverified-authorization-ids recomputed))))))

;; ── Scope verification assurance gap (#6) ──────────────────────────────────

(deftest scope-verifies-flag-is-not-independently-derivable
  (testing "a self-consistent artifact asserting scope-verifies? true passes content
            and aggregate validation even though the binding was never verified —
            the member commits only the scope-hash, not the scope map or the
            recorded scope-hash, so the flag cannot be re-derived"
    (let [forged (rehash (assoc (dissoc (nth members 0) :artifact/hash :artifact/preimage)
                                :authorization/scope-hash "0xunverifiable"
                                :authorization/scope-verifies? true))
          built (e/build-force-auth-add-held-summary [forged] {})
          result (e/check-aggregate built [forged] {:summary-version :v2})]
      (is (e/valid-force-auth-add-held? forged)
          "structural/content validation passes")
      (is (true? (:authorization/scope-verifies? forged)))
      (is (empty? (:invalid-members result))
          "the aggregate accepts it as a verified member")
      (is (= 1 (:scope-verified-count built))
          "scope counts trust the asserted flag"))))

;; ── force-auth-add-held.v2: derived scope commitment ───────────────────────

(defn- mk-member-v2
  "Build a canonical force-auth-add-held.v2 member with a committed scope
   projection."
  ([token amount direction] (mk-member-v2 token amount direction "adj-1"))
  ([token amount direction adjustment-id]
   (e/build-force-auth-add-held-v2
    {:authorization (auth-for "fa-0" (scope-for "0xrecipient" 5000))
     :scope-map (scope-for "0xrecipient" 5000)
     :adjustment {:held-adjustment/id adjustment-id
                  :token token
                  :amount amount
                  :held/direction direction}
     :consumed-at 500
     :consumed-by "0xgov"})))

(deftest v2-member-scope-commitment
  (testing "a v2 member commits the three-part scope commitment and never stores
            the scope-verifies? boolean"
    (let [m (mk-member-v2 :USDC 100 :in)]
      (is (= "force-auth-add-held.v2" (:schema-version m)))
      (is (= "force-authorisation-scope-hash.v1" (:authorization/scope-derivation m)))
      (is (map? (:authorization/scope-projection m)))
      (is (not (contains? m :authorization/scope-verifies?))
          "scope-verifies? is derived, never stored")
      (is (= (:authorization/scope-hash m)
             (fa/force-authorisation-scope-hash (:authorization/scope-projection m))))
      (is (e/valid-force-auth-add-held-v2? m))
      (is (e/exact-force-auth-add-held? m))
      (is (e/force-auth-add-held-scope-verifies? m) "derived, not trusted"))))

(deftest v2-member-forged-scope-cannot-assert-verification
  (testing "the #6 attack surface is closed for v2: a self-consistent artifact
            cannot claim a verified binding its committed projection does not
            authenticate"
    (let [forged (rehash (assoc (dissoc (mk-member-v2 :USDC 10 :in "adj-9")
                                        :artifact/hash :artifact/preimage)
                                :authorization/scope-hash "0xunverifiable"))]
      (is (not (e/valid-force-auth-add-held-v2? forged))
          "the scope-hash must authenticate the committed projection")
      (is (not (e/force-auth-add-held-scope-verifies? forged)))
      (let [ex (try (e/build-force-auth-add-held-summary [forged] {})
                    (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? ex) "the fail-fast builder rejects the forged member")
        (is (seq (:invalid-members (ex-data ex)))))
      (let [summary (e/build-force-auth-add-held-summary-permissive [forged] {})
            result (e/check-aggregate summary [forged] {:summary-version :v2})]
        (is (not (:valid? result)))
        (is (= 0 (:scope-verified-count summary)) "scope counts derive, never trust")))))

(deftest v2-member-with-stored-scope-verifies-is-rejected
  (let [m (rehash (assoc (dissoc (mk-member-v2 :USDC 10 :in "adj-9")
                                 :artifact/hash :artifact/preimage)
                         :authorization/scope-verifies? true))]
    (is (not (e/valid-force-auth-add-held-v2? m))
        "a stored scope-verifies? boolean is forbidden on v2 members")))

(deftest v2-members-flow-through-aggregates
  (let [members-v2 [(mk-member-v2 :USDC 100 :in "adj-1")
                    (mk-member-v2 :ETH 50 :in "adj-2")]
        v2 (e/build-force-auth-add-held-summary members-v2 {})
        v1 (e/build-force-auth-add-held-summary-v1 members-v2 {})
        chk (e/check-aggregate v2 members-v2 {:summary-version :v2})]
    (is (= 2 (:valid-count v2)))
    (is (= 2 (:scope-verified-count v2)))
    (is (true? (:valid? chk)))
    (is (e/valid-force-auth-add-held-summary-v1? v1))
    (is (empty? (:invalid-members (e/check-aggregate v1 members-v2 {}))))))

;; ── Optional parallel canonical commitment (portable hashing) ──────────────

(deftest canonical-commitment-is-optional-and-non-breaking
  (testing "attaching the commitment never changes :artifact/hash"
    (let [v1 (summary-v1)
          committed (e/attach-canonical-commitment v1)]
      (is (= (:artifact/hash v1) (:artifact/hash committed)))
      (is (= (:artifact/preimage v1) (:artifact/preimage committed)))
      (is (string? (:artifact/canonical-bytes-v2 committed)))
      (is (= (:artifact/hash v1) (:artifact/canonical-hash-v2 committed)))
      (is (e/valid-force-auth-add-held-summary-v1? committed))))
  (testing "artifacts without the commitment validate exactly as before"
    (is (e/valid-force-auth-add-held-summary-v1? (summary-v1)))
    (is (e/valid-force-auth-add-held-summary? (summary-v2)))
    (is (e/valid-force-auth-add-held? (nth members 0)))))

(deftest canonical-commitment-cannot-be-forged
  (let [v1 (summary-v1)
        committed (e/attach-canonical-commitment v1)]
    (testing "a forged canonical-bytes-v2 is rejected"
      (is (not (e/valid-force-auth-add-held-summary-v1?
                (assoc committed :artifact/canonical-bytes-v2 "00ff")))))
    (testing "a forged canonical-hash-v2 is rejected"
      (is (not (e/valid-force-auth-add-held-summary-v1?
                (assoc committed :artifact/canonical-hash-v2 "sha256:forged")))))
    (testing "the commitment survives EDN round-trip"
      (is (e/valid-force-auth-add-held-summary-v1?
           (edn/read-string (pr-str committed)))))))

(deftest canonical-commitment-verifies-on-members-and-lifecycle
  (let [member (e/attach-canonical-commitment (nth members 0))
        lifecycle (e/attach-canonical-commitment
                   (e/build-force-auth-lifecycle-summary
                    {:authorisations {"fa-0" (auth-for "fa-0" (scope-for "0xrecipient" 5000))}
                     :consumption-registry {}}))]
    (is (e/valid-force-auth-add-held? member))
    (is (e/valid-force-auth-lifecycle-summary? lifecycle))))
