(ns resolver-sim.protocols.sew.financial.p4b-create-escrow-integration-test
  "First real P4b integration: create_escrow.

   create_escrow is economically meaningful without being an immediate asset
   outflow: funds remain controlled by the protocol and transform from
   unencumbered assets into assets backing a new escrow obligation. Its
   insolvency-relevant effects are therefore #{:liability-creating
   :risk-increasing}, and policy denies it in :impaired / :insolvent /
   :recovering / :terminal states because of THOSE effects — not because the
   handler happens to be named create_escrow.

   Covers: canonical action normalization (with custom-resolver attributes),
   effect-driven healthy permit / impaired+insolvent+recovering+terminal deny
   with structured findings, realized economic deltas in transition evidence,
   the effect-realization invariant (declared ⊆ observed), S1→S2 stale-permit
   protection, and deny ⇒ no mutation."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]        ; protocol escrow lifecycle (create-escrow)
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.financial.lifecycle :as fl]))  ; insolvency lifecycle + P4b

(def policy (fl/default-policy))

(def fee0-snap
  "Snapshot with no escrow fee so economic deltas are exact."
  (snap-fix/escrow-snapshot {:escrow-fee-bps 0}))

(defn- escrow-world
  "A healthy world with one pending USDC escrow, built through the canonical
   lifecycle path so the held-custody ledger is schema-valid. Uses string
   \"USDC\" tokens (matching the scenario) and next-workflow-id 1."
  []
  (-> (t/empty-world 1000)
      (lc/create-escrow "0xbuyer" "USDC" "0xseller2" 1000
                        (t/make-escrow-settings {}) fee0-snap)
      :world))

(defn- insolvent-world
  []
  (-> (escrow-world)
      (assoc-in [:slash-credit-liabilities "0xRes0"] 500)))

(defn- impaired-world
  []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 0})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 10000 :escrow-state :released})
      (assoc-in [:claimable-v2 0 :settlement/principal "r"] 8000)
      (assoc-in [:yield/positions "o1"]
                {:token :USDC :principal 10000 :status :unwinding
                 :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                             :haircut-amount 2000 :reason :principal-loss}})))

(defn- assess [world] (solv/classify-solvency world))

(defn- chain-for [world]
  (let [r (fl/apply-verified :sew-v1 [] (assess world) {:at 100})]
    (get-in r [:state :episode/events])))

;; The exact scenario form the user presented.
(def create-escrow-action
  {:action "create_escrow"
   :params {:token "USDC"
            :to "0xseller2"
            :amount 1000
            :custom-resolver "0xresolver"}})

;; ── Canonical action normalization ───────────────────────────────────────────

(deftest canonicalizes-create-escrow-action
  (let [a (fl/normalize-action create-escrow-action)]
    (is (= :create-escrow (:action/type a)))
    (is (= {:token "USDC" :beneficiary "0xseller2" :amount 1000 :resolver "0xresolver"}
           (:action/params a))
        "scenario :to → canonical :beneficiary; :custom-resolver → :resolver")
    (is (= #{:liability-creating :risk-increasing} (:action/effects a))
        "create_escrow is NOT :asset-outflow — funds stay in protocol custody")
    (is (= :custom (get-in a [:action/attributes :resolver/type]))
        "custom resolver is a classified risk attribute, not a global effect")
    (is (string? (get-in a [:action/attributes :resolver/root])))
    (is (= 64 (count (:action/root a))))))

(deftest action-root-is-economic-identity-only
  (testing "seq/time/agent are NOT inside the action root — that is execution context"
    (let [a (fl/normalize-action create-escrow-action)]
      (is (not (contains? (:action/params a) :seq)))
      (is (not (contains? (:action/params a) :time)))
      (is (not (contains? (:action/params a) :agent))))))

(deftest custom-resolver-changes-action-root
  (let [canonical {:action "create_escrow"
                   :params {:token "USDC" :to "0xseller2" :amount 1000}}
        custom create-escrow-action]
    (is (not= (fl/action-root canonical) (fl/action-root custom))
        "resolver provenance is bound in the economic action identity")))

;; ── Effect-driven policy decisions ───────────────────────────────────────────

(deftest create-escrow-permitted-when-healthy
  (let [chain (chain-for (escrow-world))
        pre (escrow-world)
        d (fl/response-decision :sew-v1 chain (assess (escrow-world)) policy
                                create-escrow-action pre {:request/id "create-1"})]
    (is (= :permit (:decision d)))
    (is (contains? (:reasons d) [:action-type-permitted]))
    (is (contains? (:reasons d) [:effect-permitted :liability-creating]))
    (is (contains? (:reasons d) [:effect-permitted :risk-increasing]))))

(deftest create-escrow-denied-by-effects-in-non-healthy-states
  (testing "denial is driven by #{:liability-creating :risk-increasing}, not the handler name"
    (doseq [[label world] {:impaired (impaired-world)
                           :insolvent (insolvent-world)}]
      (let [chain (chain-for world)
            pre world
            d (fl/response-decision :sew-v1 chain (assess world) policy
                                    create-escrow-action pre {:request/id "create-1"})]
        (testing label
          (is (= :deny (:decision d)))
          (is (contains? (:reasons d) [:effect-not-permitted :liability-creating]))
          (is (contains? (:reasons d) [:effect-not-permitted :risk-increasing]))
          (is (not (fl/permitted-action? :sew-v1 chain (assess world) policy
                                         create-escrow-action pre))))))))

(deftest create-escrow-denied-in-recovering-and-terminal
  (testing "recovering only admits risk-reducing/recovery actions; terminal admits settlement/recovery only"
    (let [a-ins (assess (insolvent-world))
          a-solv (assess (escrow-world))
          r1 (fl/apply-verified :sew-v1 [] a-ins {:at 100}) e1 (:episode/events (:state r1))
          r2 (fl/apply-verified :sew-v1 e1 a-solv {:at 200}) e2 (:episode/events (:state r2))
          r3 (fl/apply-verified :sew-v1 e2 a-ins {:at 300}) e3 (:episode/events (:state r3))
          r4 (fl/apply-verified :sew-v1 e3 a-ins {:at 400}) e4 (:episode/events (:state r4))
          r5 (fl/apply-verified :sew-v1 e4 a-ins {:at 500})
          recovering-events e2
          terminal-events (get-in r5 [:state :episode/events])
          pre (escrow-world)]
      (is (= :recovering (:lifecycle/state (:state r2))))
      (is (= :terminal (:lifecycle/state (:state r5))))
      (doseq [[label chain] {:recovering recovering-events :terminal terminal-events}]
        (let [d (fl/response-decision :sew-v1 chain (assess (escrow-world)) policy
                                      create-escrow-action pre {:request/id "create-1"})]
          (testing label
            (is (= :deny (:decision d)))
            (is (contains? (:reasons d) [:effect-not-permitted :liability-creating]))))))))

;; ── Execution + realized effects ─────────────────────────────────────────────

(deftest executes-and-exposes-realized-economic-change
  (let [chain (chain-for (escrow-world))
        pre (escrow-world)
        snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
        d (fl/response-decision :sew-v1 chain (assess (escrow-world)) policy
                                create-escrow-action pre {:request/id "create-1"})
        head-root (:lifecycle-head-root d)
        result (fl/authorize-and-execute
                d create-escrow-action "create-1" pre head-root :sew-v1 #{}
                (fn [world]
                  (:world (lc/create-escrow world "0xbuyer" "USDC" "0xseller2" 1000
                                            (t/make-escrow-settings {:custom-resolver "0xresolver"})
                                            snap))))]
    (is (:ok? result))
    (let [tr (:transition result)]
      (is (= (:decision-root d) (:response-decision/root tr)))
      (is (= {:USDC 1000} (:liability-delta (:transition/effects tr)))
          "realized USDC liability increase is exposed")
      (is (contains? (:transition/realized-effects tr) :liability-creating))
      (is (fl/effect-realized? :liability-creating (:transition/realized-effects tr)))
      (is (empty? (fl/unrealized-declared-effects #{:liability-creating :risk-increasing}
                                                  (:transition/realized-effects tr)))
          "the action classifier was truthful: declared ⊆ observed"))))

(deftest effect-realization-catches-misclassification
  (testing "an action wrongly classified :no-economic-effect that changes liabilities is caught"
    (let [pre (escrow-world)
          post (assoc-in pre [:escrow-transfers 1] {:token :USDC :amount-after-fee 1000
                                                    :escrow-state :pending})
          realized (fl/realized-effects pre post)]
      (is (not (fl/effect-realized? :no-economic-effect realized))
          "misclassified action has no :no-economic-effect in its realized set")
      (is (= #{:liability-creating :asset-outflow :risk-increasing} realized)
          "a liability without backing is liability-creating + net asset-outflow + risk-increasing"))))

(deftest misclassified-no-economic-effect-transition-is-rejected
  (testing "THE regression: classifier says #{:no-economic-effect}, mutation secretly
            creates a liability → the transition evidence is REJECTED, never committed"
    (let [{:keys [events]} (chain-for (escrow-world))
          pre (escrow-world)
          ;; a decision authorized under a benign #{:no-economic-effect} declaration
          benign-action {:action/type :settle :action/effects #{:no-economic-effect}}
          d (fl/response-decision :sew-v1 events (assess (escrow-world)) policy
                                  benign-action pre {:request/id "hidden-1"})
          head-root (:lifecycle-head-root d)
          ;; the mutation secretly creates a liability
          sneaky (fn [s] (assoc-in s [:escrow-transfers 9]
                                   {:token :USDC :amount-after-fee 1000 :escrow-state :pending}))
          result (fl/authorize-and-execute d benign-action "hidden-1" pre head-root :sew-v1 #{} sneaky)]
      (is (= :permit (:decision d))
          "the benign classification was authorized (no-economic-effect is permitted in healthy)")
      (is (= :effect-contract-violated (:error result))
          "a transition that realizes undeclared effects is rejected")
      (is (contains? (:issues result) :undeclared-realized-effects)
          "observed ⊆ declared is the correct direction: undeclared realized effects are the violation")
      (is (contains? (:issues result) :no-economic-effect-violated)
          "declared :no-economic-effect but deltas changed → violated")
      (is (contains? (:transition/realized-effects (:transition result)) :liability-creating)
          "the violation is deterministically exposed in the (invalid) transition evidence"))))

(deftest no-economic-effect-is-exclusive
  (is (fl/valid-effect-declaration? #{:no-economic-effect}))
  (is (not (fl/valid-effect-declaration? #{:no-economic-effect :asset-outflow}))
      ":no-economic-effect may not co-occur with any other effect")
  (is (not (fl/valid-effect-declaration? #{:not-a-real-effect}))))

(deftest derived-effects-covered-by-observed-subset-declared
  (testing "an undeclared DERIVED effect cannot slip through the evidenced filter"
    (let [declared #{:liability-creating}
          realized #{:liability-creating :risk-increasing}]
      (is (= #{:risk-increasing} (fl/undeclared-realized-effects declared realized))
          "derived :risk-increasing is policy-relevant and must be caught by the FULL-set check")
      (is (= #{:risk-increasing} (:undeclared-realized-effects (fl/effect-contract declared realized)))))))

(deftest enforcement-rejects-undeclared-derived-effect
  (testing "valid-transition-evidence? rejects a transition whose declaration omits
            a derived effect that realization produces"
    (let [chain (chain-for (escrow-world))
          pre (escrow-world)
          snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
          d (fl/response-decision :sew-v1 chain (assess (escrow-world)) policy
                                  create-escrow-action pre {:request/id "create-1"})
          ;; a buggy/under-declared decision that omits the derived :risk-increasing
          buggy-d (assoc d :action/effects #{:liability-creating})
          post (:world (lc/create-escrow pre "0xbuyer" "USDC" "0xseller2" 1000
                                         (t/make-escrow-settings {:custom-resolver "0xresolver"})
                                         snap))
          evidence (fl/transition-evidence d "create-1" create-escrow-action pre post)
          validity (fl/valid-transition-evidence? buggy-d create-escrow-action "create-1" pre post evidence)]
      (is (= #{:liability-creating :risk-increasing} (:transition/realized-effects evidence)))
      (is (not (:valid? validity)))
      (is (contains? (:issues validity) :undeclared-realized-effects)
          "declared #{:liability-creating} but realized includes derived :risk-increasing → invalid"))))

(deftest fixed-point-holds-across-the-authorization-boundary
  (testing "scenario → normalize → decision commits root R; the CANONICAL action
            crosses the boundary and re-normalizes to the SAME root R → enforcement
            succeeds; any mutation of a canonical field changes the root and is not
            authorized"
    (let [{:keys [events]} (chain-for (escrow-world))
          pre (escrow-world)
          scenario create-escrow-action
          R (fl/action-root scenario)
          d (fl/response-decision :sew-v1 events (assess (escrow-world)) policy
                                  scenario pre {:request/id "r1"})
          head-root (:lifecycle-head-root d)
          canonical (fl/normalize-action scenario)
          snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
          result (fl/authorize-and-execute
                  d canonical "r1" pre head-root :sew-v1 #{}
                  (fn [w] (:world (lc/create-escrow w "0xbuyer" "USDC" "0xseller2" 1000
                                                    (t/make-escrow-settings {:custom-resolver "0xresolver"})
                                                    snap))))
          mutated-beneficiary (assoc-in canonical [:action/params :beneficiary] "0xother")
          mutated-resolver (assoc-in canonical [:action/params :resolver] "0xother")
          mutated-token (assoc-in canonical [:action/params :token] "DAI")
          mutated-amount (assoc-in canonical [:action/params :amount] 2000)]
      (is (fl/canonical-action? canonical) "canonical form is a true fixed point")
      (is (= R (:action/root d)) "decision commits the scenario root R")
      (is (= R (fl/action-root canonical))
          "the canonical spelling crossing the boundary re-normalizes to the SAME root R")
      (is (:ok? result)
          "enforcement with the canonical action succeeds against the decision made on the scenario spelling")
      (doseq [mutated [mutated-beneficiary mutated-resolver mutated-token mutated-amount]]
        (is (not= R (fl/action-root mutated)) "mutating a canonical field changes the root")
        (is (not (fl/decision-authorizes? d mutated "r1" pre head-root :sew-v1))
            "the mutated action is NOT authorized by the decision")))))

(deftest create-escrow-declared-and-realized-agree-exactly
  (let [chain (chain-for (escrow-world))
        pre (escrow-world)
        snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
        d (fl/response-decision :sew-v1 chain (assess (escrow-world)) policy
                                create-escrow-action pre {:request/id "create-1"})
        head-root (:lifecycle-head-root d)
        result (fl/authorize-and-execute
                d create-escrow-action "create-1" pre head-root :sew-v1 #{}
                (fn [world]
                  (:world (lc/create-escrow world "0xbuyer" "USDC" "0xseller2" 1000
                                            (t/make-escrow-settings {:custom-resolver "0xresolver"})
                                            snap))))]
    (is (:ok? result))
    (is (= (:action/effects d) (:transition/realized-effects (:transition result)))
        "exact effect contract: declared #{:liability-creating :risk-increasing} == realized")
    (is (= {:USDC 1000} (:liability-delta (:transition/economic-deltas (:transition result)))))
    (is (= {:USDC 1000} (:asset-delta (:transition/economic-deltas (:transition result))))
        "raw realizable assets increase as funds enter custody (NOT counted as asset loss)")
    (is (empty? (:economic-headroom-delta (:transition/economic-deltas (:transition result))))
        "economic headroom (assets − liabilities) is unchanged: funds transform from unencumbered to obligation-backing")
    (is (= #{:liability-creating} (:transition/primitive-effects (:transition result))))
    (is (= #{:risk-increasing} (:transition/derived-effects (:transition result))))))

;; ── Stale-permit protection (S1 → S2) ────────────────────────────────────────

(deftest stale-permit-cannot-execute-against-advanced-state
  (testing "a permit at S1 (USDC obligations 1000) cannot execute after another escrow
            advances the world to S2 (USDC obligations 2000)"
    (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0})
          s1 (escrow-world)
          chain (chain-for s1)
          d (fl/response-decision :sew-v1 chain (assess s1) policy
                                  create-escrow-action s1 {:request/id "create-1"})
          head-root (:lifecycle-head-root d)
          ;; another escrow executes, advancing the world
          s2 (:world (lc/create-escrow s1 "0xbuyer" "USDC" "0xother" 1000
                                       (t/make-escrow-settings {}) snap))]
      (is (= 1000 (get-in (fl/economic-deltas s1 s2) [:liability-delta :USDC]))
          "S2 has +1000 USDC obligations vs S1")
      (is (fl/decision-authorizes? d create-escrow-action "create-1" s1 head-root :sew-v1)
          "permit valid at S1")
      (is (not (fl/decision-authorizes? d create-escrow-action "create-1" s2 head-root :sew-v1))
          "permit produced at S1 cannot execute against S2 (exact pre-state binding)"))))

;; ── Deny ⇒ no mutation ───────────────────────────────────────────────────────

(deftest denied-create-escrow-causes-no-mutation
  (let [chain (chain-for (impaired-world))
        pre (escrow-world)
        d (fl/response-decision :sew-v1 chain (assess (impaired-world)) policy
                                create-escrow-action pre {:request/id "create-1"})
        head-root (:lifecycle-head-root d)
        called (atom 0)
        result (fl/authorize-and-execute
                d create-escrow-action "create-1" pre head-root :sew-v1 #{}
                (fn [world] (swap! called inc) world))]
    (is (= :decision-denied (:error result))
        "impaired lifecycle denies create_escrow (liability-creating)")
    (is (zero? @called) "no state mutation occurred")))
