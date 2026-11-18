(ns resolver-sim.protocols.sew.authority-test
  "Tests for contract_model/authority.clj."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.types     :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.authority :as auth]
            [resolver-sim.protocols.sew.resolution :as res]))

(def alice    "0xAlice")
(def bob      "0xBob")
(def resolver "0xResolver")
(def carol    "0xCarol")
(def mod-addr "0xModule")

(def base-snapshot
  (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :max-dispute-duration 3600}))

(defn- world-with-escrow
  "World with one :disputed escrow, given optional settings overrides."
  ([] (world-with-escrow {}))
  ([settings-overrides]
   (let [snap (if (:resolution-module settings-overrides)
                (snap-fix/escrow-snapshot
                 (merge {:escrow-fee-bps 50 :max-dispute-duration 3600}
                        (select-keys settings-overrides [:resolution-module])))
                base-snapshot)
         sett (t/make-escrow-settings
               (dissoc settings-overrides :resolution-module))
         r    (lc/create-escrow
               (t/empty-world 1000)
               alice "0xUSDC" bob 1000 sett snap)
         w    (:world r)]
     (-> w
         (assoc-in [:escrow-transfers 0 :escrow-state]  :disputed)
         (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver)))))

;; ---------------------------------------------------------------------------
;; Priority 1: customResolver is exclusive
;; ---------------------------------------------------------------------------

(deftest custom-resolver-exclusive
  (let [w (world-with-escrow {:custom-resolver "0xCustom"})]
    (testing "custom resolver is authorized"
      (is (true? (auth/authorized-resolver? w 0 "0xCustom" nil))))
    (testing "module resolver not authorized when custom is set"
      (let [mod-fn (auth/make-default-resolution-module resolver)]
        (is (false? (auth/authorized-resolver? w 0 resolver mod-fn)))))
    (testing "direct resolver not authorized when custom is set"
      (is (false? (auth/authorized-resolver? w 0 resolver nil))))))

;; ---------------------------------------------------------------------------
;; Priority 2: resolution module
;; ---------------------------------------------------------------------------

(deftest resolution-module-authorizes
  (let [w      (world-with-escrow {:resolution-module mod-addr})
        mod-fn (auth/make-default-resolution-module resolver)]
    (is (true?  (auth/authorized-resolver? w 0 resolver mod-fn))
        "module-authorized resolver should pass")
    (is (false? (auth/authorized-resolver? w 0 carol mod-fn))
        "carol not authorized by module")))

(deftest execute-resolution-can-use-module-authorized-non-fallback-resolver
  (testing "module authorization is exercised even when caller != dispute-resolver fallback"
    (let [w (-> (world-with-escrow {:resolution-module mod-addr})
                (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver))
          mod-fn (fn [_wf caller] {:authorized? (= caller carol)})
          result (res/execute-resolution w 0 carol true "0xmodhash" mod-fn)]
      (is (:ok result) "module-authorized non-fallback resolver should execute successfully")
      (is (= carol (get-in (:world result) [:escrow-transfers 0 :resolution :resolved-by]))))))

(deftest resolution-module-fallthrough-to-direct
  "When a resolution module declines, BaseEscrow falls through to check
   et.disputeResolver — mirroring the Solidity pattern
     if (authorized) return true;
     return disputeResolver == et.disputeResolver;
   This is safe because escalateDispute() always keeps et.disputeResolver
   in sync with the current round's resolver."
  (let [w      (world-with-escrow {:resolution-module mod-addr})
        mod-fn (fn [_wf _caller] {:authorized? false})]
    ;; Module says no — et.disputeResolver = resolver → still authorized via fallthrough
    (is (true?  (auth/authorized-resolver? w 0 resolver mod-fn)))
    ;; Carol is neither the module-authorized resolver nor et.disputeResolver
    (is (false? (auth/authorized-resolver? w 0 carol   mod-fn)))))

;; ---------------------------------------------------------------------------
;; Priority 3: direct resolver fallback
;; ---------------------------------------------------------------------------

(deftest direct-resolver-fallback
  (let [w (world-with-escrow)]
    (is (true?  (auth/authorized-resolver? w 0 resolver nil)))
    (is (false? (auth/authorized-resolver? w 0 carol nil)))))

;; ---------------------------------------------------------------------------
;; ModuleSnapshot immutability
;; ---------------------------------------------------------------------------

(deftest snapshot-frozen-after-governance-change
  "Governance changing the module address must NOT affect in-flight escrows."
  (let [w0       (world-with-escrow)
        original (t/get-snapshot w0 0)
        ;; Simulate governance changing a different escrow's snapshot
        w1       (assoc-in w0 [:module-snapshots 99]
                           (snap-fix/escrow-snapshot {:escrow-fee-bps 999}))]
    (is (auth/snapshot-frozen? w1 0 original)
        "escrow 0 snapshot unchanged by governance update to escrow 99")))

;; ---------------------------------------------------------------------------
;; ResolutionMode dispatch
;; ---------------------------------------------------------------------------

(deftest resolution-mode-custom
  (let [w (world-with-escrow {:custom-resolver "0xCustom"})]
    (is (= :custom-resolver (auth/resolution-mode w 0)))))

(deftest resolution-mode-module
  (let [w (world-with-escrow {:resolution-module mod-addr})]
    (is (= :resolution-module (auth/resolution-mode w 0)))))

(deftest resolution-mode-direct
  (let [w (world-with-escrow)]
    (is (= :direct (auth/resolution-mode w 0)))))

;; ---------------------------------------------------------------------------
;; DefaultResolutionModule stub
;; ---------------------------------------------------------------------------

(deftest default-module-authorizes-only-its-resolver
  (let [mod-fn (auth/make-default-resolution-module resolver)]
    (is (true?  (:authorized? (mod-fn 0 resolver))))
    (is (false? (:authorized? (mod-fn 0 carol))))))

;; ---------------------------------------------------------------------------
;; KlerosArbitrableProxy stub
;; ---------------------------------------------------------------------------

(deftest kleros-module-authorizes-by-level
  (let [levels   {0 "0xJuror1" 1 "0xJuror2"}
        level-fn (fn [wf] (get {0 0, 1 1} wf 0))
        mod-fn   (auth/make-kleros-module levels level-fn)]
    (is (true?  (:authorized? (mod-fn 0 "0xJuror1"))) "level 0 juror authorized for wf 0")
    (is (false? (:authorized? (mod-fn 0 "0xJuror2"))) "level 1 juror not authorized at level 0")
    (is (true?  (:authorized? (mod-fn 1 "0xJuror2"))) "level 1 juror authorized for wf 1")))

;; ---------------------------------------------------------------------------
;; Resolver overflow authorization
;; ---------------------------------------------------------------------------

(def gov-addr    "0xGovernance")
(def overflow-resolver "0xOverflow")
(def primary-addr "0xPrimary")

(defn- make-overflow-record
  "Build a resolver-overflow record for testing."
  [& {:keys [status starts-at expires-at max-workflows used resolvers reason
             primary]
      :or   {status :active, starts-at 0, expires-at 99999, max-workflows 5
             used #{}, resolvers #{overflow-resolver}, reason :resolver-overcapacity
             primary primary-addr}}]
  {:overflow-id        0
   :scope              :resolver
   :resolver           primary
   :reason             reason
   :authorized-by      gov-addr
   :created-at         0
   :starts-at          starts-at
   :expires-at         expires-at
   :max-workflows      max-workflows
   :failover-resolvers resolvers
   :used-workflows     used
   :status             status})

(defn- world-with-overflow-escrow
  "World with one :disputed escrow under primary-addr and an overflow record."
  [& {:keys [record overrides]
      :or   {record (make-overflow-record)}}]
  (let [snap  (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :max-dispute-duration 3600})
        sett  (t/make-escrow-settings {})
        r     (lc/create-escrow (t/empty-world 1000) alice "0xUSDC" bob 1000 sett snap)
        w     (:world r)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] primary-addr)
        (assoc-in [:resolver-overflows 0] record))))

;; authorized-overflow-resolver? — basic success path

(deftest overflow-authorizes-listed-resolver
  (let [w (world-with-overflow-escrow)]
    (is (some? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "listed failover resolver is authorized under overflow")))

(deftest overflow-rejects-unlisted-resolver
  (let [w (world-with-overflow-escrow)]
    (is (nil? (auth/authorized-overflow-resolver? w 0 carol 0))
        "unlisted actor is not authorized")))

(deftest overflow-rejects-expired
  (let [w (world-with-overflow-escrow :record (make-overflow-record :expires-at 500))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "expired overflow does not authorize")))

(deftest overflow-rejects-not-yet-started
  (let [w (world-with-overflow-escrow :record (make-overflow-record :starts-at 9999))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "overflow not yet started does not authorize")))

(deftest overflow-rejects-cap-exhausted
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :max-workflows 2 :used #{0 1}))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "cap-exhausted overflow does not authorize")))

(deftest overflow-rejects-duplicate-workflow
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :used #{0}))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "already-resolved workflow is not authorized")))

(deftest overflow-rejects-wrong-resolver
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :primary "0xOther"))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "workflow with different primary resolver is rejected")))

(deftest overflow-rejects-non-disputed
  (let [w (-> (world-with-overflow-escrow)
              (assoc-in [:escrow-transfers 0 :escrow-state] :pending))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "non-disputed workflow is rejected")))

(deftest overflow-rejects-revoked
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :status :revoked))]
    (is (nil? (auth/authorized-overflow-resolver? w 0 overflow-resolver 0))
        "revoked overflow does not authorize")))

(deftest overflow-returns-record-on-success
  (let [w (world-with-overflow-escrow)
        result (auth/authorized-overflow-resolver? w 0 overflow-resolver 0)]
    (is (map? result) "returns record on success")
    (is (= 0 (:overflow-id result)))))

;; active-overflows-for — search-based lookup

(deftest active-overflows-returns-active
  (let [w (world-with-overflow-escrow)]
    (is (= 1 (count (auth/active-overflows-for w 0)))
        "finds the active overflow")))

(deftest active-overflows-empty-for-terminal
  (let [w (-> (world-with-overflow-escrow)
              (assoc-in [:escrow-transfers 0 :escrow-state] :released))]
    (is (empty? (auth/active-overflows-for w 0))
        "terminal workflow has no active overflows")))

(deftest active-overflows-empty-for-different-resolver
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :primary "0xOther"))]
    (is (empty? (auth/active-overflows-for w 0))
        "overflow for different resolver is not returned")))

(deftest active-overflows-excludes-expired
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :expires-at 500))]
    (is (empty? (auth/active-overflows-for w 0))
        "expired overflow excluded")))

(deftest active-overflows-excludes-cap-exhausted
  (let [w (world-with-overflow-escrow
            :record (make-overflow-record :max-workflows 1 :used #{0}))]
    (is (empty? (auth/active-overflows-for w 0))
        "cap-exhausted overflow excluded")))
