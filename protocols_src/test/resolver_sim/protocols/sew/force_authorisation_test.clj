(ns resolver-sim.protocols.sew.force-authorisation-test
  "Force-authorisation lifecycle tests.

   Covers four scenarios:
     1. grant -> execute -> consumed       (happy path)
     2. grant -> revoke -> execute         (rejected)
     3. grant -> expired -> execute        (rejected)
     4. grant -> execute -> execute again  (rejected by Gap 1 guard)"
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.accounting :as acct]
             [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
             [resolver-sim.run.bundle-root :as br]
             [resolver-sim.time.context :as time-ctx]
             [resolver-sim.hash.canonical :as hc]
             [resolver-sim.protocols.sew.related-claims :as rc]
             [resolver-sim.protocols.sew.invariants :as inv]))

(def gov-addr "0xGov")
(def alice-addr "0xAlice")
(def bob-addr "0xBob")
(def resolver-addr "0xResolver")
(def usdc "0xUSDC")

(def gov-ctx
  "Context with a governance agent for grant/revoke actions."
  {:agent-index {"gov" {:id "gov" :address gov-addr :role "governance"}}
   :governance-identity gov-addr})

(def exec-ctx
  "Context with any resolvable agent for execute actions."
  {:agent-index {"exec" {:address resolver-addr}}})

(defn- disputed-world
  "Create a world with one :disputed escrow at block-time 1000.
   The dispute-resolver is set to resolver-addr for resolution authorization.
   The escrow is at the FINAL round (:max-dispute-level 0) so a force-authorised
   resolution finalizes immediately (release + consumption) rather than opening
   a pending settlement."
  [& {:keys [appeal-dur amount] :or {appeal-dur 0 amount 10000}}]
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps        50
                                        :max-dispute-duration  3600
                                        :appeal-window-duration appeal-dur})
        w0   (-> (t/empty-world 1000)
                 (assoc :params {:max-dispute-level 0}))
        cr   (lc/create-escrow w0 alice-addr usdc bob-addr amount
                               (t/make-escrow-settings {}) snap)
        w    (:world cr)]
    (-> w
        (assoc-in [:escrow-transfers 0 :escrow-state] :disputed)
        (assoc-in [:escrow-transfers 0 :sender-status] :raise-dispute)
        (assoc-in [:escrow-transfers 0 :dispute-resolver] resolver-addr)
        (assoc-in [:dispute-timestamps 0] 1000))))

(defn- grant-force-auth
  "Call apply-action to grant a force-authorisation and return the world + auth-id."
  [world & {:keys [workflow-id reason starts-at duration expires-at is-release
                   parameter-context parameter-address]
            :or {workflow-id 0 reason :resolver-overcapacity}}]
  (let [params (merge {:workflow-id workflow-id :reason reason}
                      (when parameter-context {:parameter/context parameter-context})
                      (when parameter-address {:parameter/address parameter-address})
                      (when (some? is-release) {:is-release is-release})
                      (when starts-at {:starts-at starts-at})
                      (when duration {:duration duration})
                      (when expires-at {:expires-at expires-at}))
        event {:seq 0 :time 1000 :agent "gov" :action "grant-force-authorisation"
               :params params}
        result (sew/apply-action gov-ctx world event)]
    (if (:ok result)
      {:world (:world result)
       :auth-id (get-in result [:extra :authorization/id])}
      {:error (:error result)})))

(defn- revoke-force-auth
  "Call apply-action to revoke a force-authorisation."
  [world auth-id]
  (let [event {:seq 1 :time 1000 :agent "gov" :action "revoke-force-authorisation"
               :params {:authorization-id auth-id}}
        result (sew/apply-action gov-ctx world event)]
    (if (:ok result)
      {:world (:world result)
       :auth-id auth-id}
      {:error (:error result)})))

(defn- execute-force-auth
  "Call apply-action to execute a force-authorised resolution."
  [world auth-id & {:keys [workflow-id is-release parameter-context parameter-address]
                    :or {workflow-id 0 is-release true}}]
  (let [event {:seq 2 :time 1000 :agent "exec" :action "execute-force-authorised-action"
               :params (cond-> {:workflow-id workflow-id
                                 :authorization-id auth-id
                                 :is-release is-release}
                         parameter-context (assoc :parameter/context parameter-context)
                         parameter-address (assoc :parameter/address parameter-address))}
        result (sew/apply-action exec-ctx world event)]
    (if (:ok result)
      {:world (:world result)
       :auth-id auth-id}
      {:error (:error result)})))

;; ── Scenario 1: grant -> execute -> consumed ─────────────────────────────────

(deftest force-auth-grant-execute-consumed
  (let [world0 (disputed-world)
        {:keys [world auth-id] :as grant-result} (grant-force-auth world0)]
    (is auth-id "force-authorisation should be granted with an auth-id")
    (is (nil? (:error grant-result)) "grant should succeed")
    (let [world1 world

          record (get-in world1 [:force-authorisations auth-id])]
      (is (= :active (:authorization/status record)) "auth should be active after grant")
      (is (false? (:consumed? record)) "auth should not be consumed after grant")

      (let [{:keys [world error] :as exec-result} (execute-force-auth world1 auth-id)]
        (is (nil? error) "force-authorised execution should succeed")
        (let [world2 world

              record (get-in world2 [:force-authorisations auth-id])]
          (is (= :consumed (:authorization/status record)) "auth should be consumed after execution")
          (is (true? (:consumed? record)) "auth :consumed? should be true")

          (let [consumed (get-in world2 [:force-authorisations/consumed auth-id])]
            (is consumed "consumed registry entry should exist")
            (is (true? (:consumed? consumed)) "consumed registry entry should indicate consumed")
            (is (= auth-id (:authorization/id consumed)) "consumed registry should reference auth-id"))

          (is (= :released (t/escrow-state world2 0)) "escrow should be released"))))))

(deftest force-auth-public-parameter-provenance-round-trip
  (let [root-a (str "sha256:" (apply str (repeat 64 "a")))
        root-b (str "sha256:" (apply str (repeat 64 "b")))
        context-a {:parameter-context/type :protocol-parameters
                   :parameter-context/root root-a :parameter-context/version 1}
        context-b (assoc context-a :parameter-context/root root-b)
        address {:parameter/id :sew/escrow-principal}
        world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0
                                                   :parameter-context context-a
                                                   :parameter-address address)
        grant (get-in world [:force-authorisations auth-id])
        accepted (execute-force-auth world auth-id
                                     :parameter-context context-a
                                     :parameter-address address)
        adjustment (some-> accepted :world :held-adjustments last)
        mismatched (execute-force-auth world auth-id
                                       :parameter-context context-b
                                       :parameter-address address)]
    (is auth-id)
    (is (= context-a (get-in grant [:authorization/scope :parameter/context])))
    (is (= address (get-in grant [:authorization/scope :parameter/address])))
    (is (nil? (:error accepted)))
    (is (= context-a (:parameter/context adjustment)))
    (is (= address (:parameter/address adjustment)))
    (is (= :force-authorisation-grant-scope-mismatch (:error mismatched))
        "a changed context root is rejected before finalization")))

(deftest force-auth-public-related-claims-grant-and-member-execution
  (let [snapshot (snap-fix/escrow-snapshot {:escrow-fee-bps 50 :appeal-window-duration 0})
        world0 (disputed-world)
        created (lc/create-escrow world0 alice-addr usdc bob-addr 8000
                                   (t/make-escrow-settings {}) snapshot)
        world1 (-> (:world created)
                   (assoc-in [:escrow-transfers 1 :escrow-state] :disputed)
                   (assoc-in [:escrow-transfers 1 :sender-status] :raise-dispute)
                   (assoc-in [:escrow-transfers 1 :dispute-resolver] resolver-addr)
                   (assoc-in [:dispute-timestamps 1] 1000))
        relationship-result (sew/apply-action gov-ctx world1
                                              {:seq 1 :time 1000 :agent "gov"
                                               :action "grant-related-claims"
                                               :params {:workflow-ids [0 1]
                                                        :type :force-authorisation-batch
                                                        :reason "batch settlement"}})
        relationship-id (:relationship-id relationship-result)
        parameter-context {:parameter-context/type :protocol-parameters
                           :parameter-context/root (str "sha256:" (apply str (repeat 64 "a")))
                           :parameter-context/version 1}
        parameter-address {:parameter/id :sew/escrow-principal}
        grant-result (sew/apply-action gov-ctx (:world relationship-result)
                                       {:seq 2 :time 1000 :agent "gov"
                                        :action "grant-related-claims-force-authorisation"
                                        :params {:relationship-id relationship-id
                                                 :reason :resolver-overcapacity
                                                 :parameter/context parameter-context
                                                 :parameter/address parameter-address}})
        auth-id (get-in grant-result [:extra :authorization/id])
        grant (get-in (:world grant-result) [:force-authorisations auth-id])
        execute-0 (execute-force-auth (:world grant-result) auth-id
                                      :workflow-id 0
                                      :parameter-context parameter-context
                                      :parameter-address parameter-address)
        execute-1 (execute-force-auth (:world execute-0) auth-id
                                      :workflow-id 1
                                      :parameter-context parameter-context
                                      :parameter-address parameter-address)]
    (is (:ok relationship-result))
    (is auth-id)
    (is (= :related-claims (:authorization/scope-kind grant)))
    (is (= 2 (count (:member-scope-hashes grant))))
    (is (every? #(contains? % :held/position-id)
                (map :authorization/scope [grant])))
    (is (nil? (:error execute-0)) "first related member executes")
    (is (nil? (:error execute-1)) "second related member executes")
    (is (= :consumed (get-in execute-1 [:world :force-authorisations auth-id :authorization/status])))
    (is (true? (:holds? (inv/force-authorisations-lifecycle-consistent? (:world execute-1)))))
    (is (true? (:holds? (inv/related-claims-authorisation-scope-closed? (:world execute-1)))))))

(deftest force-auth-grant-release-cannot-execute-refund
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0 :is-release true)
        record (get-in world [:force-authorisations auth-id])
        result (execute-force-auth world auth-id :is-release false)]
    (is (= :force-authorised-release
           (get-in record [:authorization/scope :held/reason])))
    (is (= :force-authorisation-grant-scope-mismatch (:error result))
        "a release-scoped grant must not authorize a refund")
    (is (= :disputed (t/escrow-state world 0))
        "a rejected scope mismatch must not mutate the escrow")))

;; ── Scenario 2: grant -> revoke -> execute (rejected) ────────────────────────

(deftest force-auth-grant-revoke-execute-rejected
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)]
    (is auth-id "grant should succeed")
    (let [world1 world

          {:keys [error] :as revoke-result} (revoke-force-auth world1 auth-id)]
      (is (nil? error) "revoke should succeed")
      (let [world2 (:world revoke-result)

            {:keys [error] :as exec-result} (execute-force-auth world2 auth-id)]
        (is (= :force-authorisation-not-active error)
            "execution should be rejected after revoke")))))

;; ── Scenario 3: grant -> expired -> execute (rejected) ────────────────────────

(deftest force-auth-grant-expired-execute-rejected
  (let [world (disputed-world)
        now (time-ctx/block-ts world)
        {:keys [world auth-id]} (grant-force-auth world :expires-at (+ now 100))]
    (is auth-id "grant should succeed")

    (let [world (time-ctx/advance-time world {:to (+ now 200)})
          {:keys [error] :as exec-result} (execute-force-auth world auth-id)]
      (is (= :force-authorisation-expired error)
          "execution should be rejected after expiry"))))

;; ── Scenario 4: grant -> execute -> execute again (rejected) ──────────────────

(deftest force-auth-grant-execute-execute-again-rejected
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)]
    (is auth-id "grant should succeed")
    (let [world1 world

          {:keys [world]} (execute-force-auth world1 auth-id)]
      (is (= :released (t/escrow-state world 0)) "first execution should release escrow")
      (let [world2 world

            {:keys [error] :as exec-result} (execute-force-auth world2 auth-id)]
        (is (= :force-authorisation-not-active error)
            "second execution should be rejected (status is :consumed)")))))

;; ── Integration: protocol state flows into bundle root ───────────────────────

(deftest force-auth-protocol-state-hashes-in-bundle-root
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)]
    (is auth-id "grant should succeed")
    (let [world1 world
          {:keys [world]} (execute-force-auth world1 auth-id)]
      (is (= :released (t/escrow-state world 0)) "execution should release escrow")
      (let [world2 world
            fa (get world2 :force-authorisations)
            fa-consumed (get world2 :force-authorisations/consumed)]
        (is (map? fa) "force-authorisations should be a map in the world")
        (is (map? fa-consumed) "force-authorisations/consumed should be a map in the world")

        (let [result {:status :pass
                      :totals {:passed 1 :failed 0 :total 1}
                      :protocol/force-authorisations fa
                      :protocol/force-authorisations-consumed fa-consumed}
              request {:runner/backend :local-current
                       :runner-selection {:mode :pinned :runner-id :runner/local-bb}
                       :suite/key :test
                       :protocol/default-id "sew-v1"
                       :evidence/profile :standard
                       :output/profile :full}
              bundle (br/build-bundle-root request result)
              proto (get bundle :protocol/state-hashes)]
          (is (map? proto) ":protocol/state-hashes should be present in bundle root")
          (is (string? (:force-authorisations/hash proto))
              "force-authorisations/hash should be a string")
          (is (string? (:force-authorisations/consumed-hash proto))
              "force-authorisations/consumed-hash should be a string")
          (is (pos? (count (:force-authorisations/hash proto)))
              "force-authorisations/hash should be non-empty")
          (is (pos? (count (:force-authorisations/consumed-hash proto)))
              "force-authorisations/consumed-hash should be non-empty")

          ;; Verify determinism: same world state → same hashes
          (let [bundle2 (br/build-bundle-root request result)
                proto2 (get bundle2 :protocol/state-hashes)]
            (is (= (:force-authorisations/hash proto) (:force-authorisations/hash proto2))
                "force-authorisations/hash should be deterministic")
            (is (= (:force-authorisations/consumed-hash proto) (:force-authorisations/consumed-hash proto2))
                "force-authorisations/consumed-hash should be deterministic")))))))

(deftest force-auth-protocol-state-hashes-absent-when-no-force-auth
  (let [world (disputed-world)
        result {:status :pass
                :totals {:passed 1 :failed 0 :total 1}
                :protocol/force-authorisations nil
                :protocol/force-authorisations-consumed nil}
        request {:runner/backend :local-current
                 :runner-selection {:mode :pinned :runner-id :runner/local-bb}
                 :suite/key :test
                 :protocol/default-id "sew-v1"
                 :evidence/profile :standard
                 :output/profile :full}
        bundle (br/build-bundle-root request result)
        proto (get bundle :protocol/state-hashes)]
    (is (nil? proto) ":protocol/state-hashes should be absent when no force-auth state")))

;; ── Related-claims force-authorisation lifecycle ──────────────────────────────

(deftest force-auth-related-claims-lifecycle-invariants
  (let [snap (snap-fix/escrow-snapshot {:escrow-fee-bps 50})
        usdc-kw :0xUSDC
        w0 (t/empty-world 1000)
        cr0 (lc/create-escrow w0 alice-addr usdc-kw bob-addr 10000
                              (t/make-escrow-settings {}) snap)
        w1 (:world cr0)
        cr1 (lc/create-escrow w1 alice-addr usdc-kw bob-addr 10000
                              (t/make-escrow-settings {}) snap)
        w2 (:world cr1)
        wf-0 0 wf-1 1
        rel-result (rc/create-related-claims! w2
                     {:type :same-incident
                      :members [{:claim/kind :sew/workflow :workflow/id wf-0}
                                {:claim/kind :sew/workflow :workflow/id wf-1}]
                      :reason "test-force-auth-lifecycle"
                      :created-by {:actor/type :test :actor/address "0xGov"}
                      :created-at-step 0})
        w3 (:world rel-result)
        rel-id (:relationship-id rel-result)
        rel (rc/get-related-claims w3 rel-id)
        auth-id "fa-rel-lifecycle"
        parameter-context {:parameter-context/type :protocol-parameters
                           :parameter-context/root (str "sha256:" (apply str (repeat 64 "a")))
                           :parameter-context/version 1}
        parameter-address {:parameter/id :sew/escrow-principal}
        ;; sub-held needs to match the keyword key that create-escrow stores
        held-amount (get-in w3 [:total-held usdc-kw] 0)
        sub-0 (quot held-amount 4)
        sub-1 (quot held-amount 4)
        scope-0 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out
                 :token usdc-kw :amount sub-0
                 :held/account :escrow-principal
                 :held/position-id [:held/position usdc-kw :escrow-principal wf-0]
                 :owner/address bob-addr
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-0
                 :parameter/context parameter-context
                 :parameter/address parameter-address}
        scope-1 {:authorization/id auth-id
                 :authorization/type :force-authorisation
                 :held/direction :out
                 :token usdc-kw :amount sub-1
                 :held/account :escrow-principal
                 :held/position-id [:held/position usdc-kw :escrow-principal wf-1]
                 :owner/address bob-addr
                 :held/reason :force-authorised-release
                 :held/workflow-id wf-1
                 :parameter/context parameter-context
                 :parameter/address parameter-address}
        hash-0 (hc/domain-hash "force-authorisation-scope" scope-0)
        hash-1 (hc/domain-hash "force-authorisation-scope" scope-1)
        w4 (-> w3
               (assoc-in [:force-authorisations auth-id]
                         {:authorization/id auth-id
                          :authorization/type :force-authorisation
                          :authorization/status :active
                          :consumed? false
                                                    :starts-at 0
                                                    :authorization/scope-kind :related-claims
                                                    :relationship/id rel-id
                                                    :relationship/hash (:relationship/hash rel)
                                                    :member-scope-hashes [hash-0 hash-1]
                                                    :authorization/scope scope-0
                                                    :authorization/scope-hash hash-0})
               (assoc :next-force-authorisation-id 1))
        auth-prov {:authorization/type :force-authorisation
                   :authorization/id auth-id
                   :authorization/scope-kind :related-claims
                   :authorization/scope-hash hash-0
                   :relationship/id rel-id
                   :relationship/hash (:relationship/hash rel)
                   :member-scope-hashes [hash-0 hash-1]}
        w5 (acct/sub-held w4 usdc-kw sub-0
                          {:action "finalize-released"
                           :reason :force-authorised-release
                           :authorization-provenance auth-prov
                           :parameter/context parameter-context
                           :parameter/address parameter-address
                           :extra {:held/workflow-id wf-0
                                   :owner/address bob-addr}})
        c1 (get-in w5 [:force-authorisations/consumed auth-id])
        w6 (acct/sub-held w5 usdc-kw sub-1
                          {:action "finalize-released"
                           :reason :force-authorised-release
                           :authorization-provenance auth-prov
                           :parameter/context parameter-context
                           :parameter/address parameter-address
                           :extra {:held/workflow-id wf-1
                                   :owner/address bob-addr}})
        c2 (get-in w6 [:force-authorisations/consumed auth-id])
        scope-closed (inv/related-claims-authorisation-scope-closed? w6)
        consumed (get-in w6 [:force-authorisations/consumed auth-id])
        tampered (assoc-in w6 [:force-authorisations/consumption-records auth-id hash-0
                                :parameter/address]
                           {:parameter/id :sew/escrow-fee})
        malformed (-> w6
                      (update :held-adjustments
                              (fn [adjustments]
                                (mapv #(if (= auth-id
                                              (get-in % [:authorization/provenance :authorization/id]))
                                         (assoc % :parameter/address nil)
                                         %)
                                      adjustments)))
                      (assoc-in [:force-authorisations/consumption-records auth-id hash-0
                                 :parameter/address] nil))
        substituted-grant (assoc-in w6 [:force-authorisations auth-id :member-scope-hashes]
                                    [hash-1 "substituted-member-scope"])
        tampered-stored-scope (assoc-in w6 [:force-authorisations auth-id
                                            :authorization/scope :amount]
                                        999)]
    ;; After first member: per-member tracking with partial consumption
    (is (true? (:consumed? c1)) "first member consumption recorded")
    (is (contains? (:consumed-members c1) hash-0) "first member hash tracked")
    (is (not (contains? (:consumed-members c1) hash-1)) "second member not yet consumed")
    (is (= 1 (:member-count c1)) "one member consumed after first execution")
    ;; After both members: full consumption tracking
    (is (true? (:consumed? c2)) "second member consumption recorded")
    (is (contains? (:consumed-members c2) hash-1) "second member hash tracked")
    (is (= 2 (:member-count c2)) "both members consumed")
    ;; related-claims invariant: consumed entry references valid relationship
    (is (true? (:holds? scope-closed))
        (str "related-claims-authorisation-scope-closed should hold: " (:violations scope-closed)))
    (is (some? consumed) "consumed registry entry should exist")
    (is (= :consumed (get-in w6 [:force-authorisations auth-id :authorization/status]))
        "grant is terminal only after every committed member is consumed")
    (is (true? (:holds? (inv/force-authorisations-lifecycle-consistent? w6)))
        "persisted member commitments and held adjustments remain linked")
    (is (false? (:holds? (inv/force-authorisations-lifecycle-consistent? tampered)))
        "a changed provenance field invalidates the immutable consumption binding")
    (is (false? (:holds? (inv/related-claims-authorisation-scope-closed? tampered)))
        "scope closure independently rejects the tampered consumption record")
    (is (false? (:holds? (inv/related-claims-authorisation-scope-closed? malformed)))
        "scope closure rejects matching but malformed one-sided provenance")
    (is (false? (:holds? (inv/related-claims-authorisation-scope-closed? substituted-grant)))
        "scope closure rejects a relationship member substituted outside the grant")
    (is (false? (:holds? (inv/force-authorisations-lifecycle-consistent? tampered-stored-scope)))
        "lifecycle recomputes and authenticates stored scope hashes")))

;; ── Authentication boundary: governance gate -> provenance -> lifecycle ──────

(def non-gov-ctx
  "Context with a non-governance actor for gate rejection tests.
   Governance identity is configured so the actor is rejected for role/address,
   not for missing configuration."
  {:agent-index {"mallory" {:id "mallory" :address "0xMallory" :type "honest"}}
   :governance-identity gov-addr})

(deftest force-auth-non-governance-grant-rejected
  (let [world0 (disputed-world)
        event {:seq 0 :time 1000 :agent "mallory" :action "grant-force-authorisation"
               :params {:workflow-id 0 :reason :resolver-overcapacity}}
        result (sew/apply-action non-gov-ctx world0 event)]
    (is (= :not-governance (:error result)))
    (is (not (:ok result)))
    (is (empty? (:force-authorisations world0))
        "no authorization record created by a non-governance actor")))

(deftest force-auth-non-governance-revoke-rejected
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)
        event {:seq 1 :time 1000 :agent "mallory" :action "revoke-force-authorisation"
               :params {:authorization-id auth-id}}
        result (sew/apply-action non-gov-ctx world event)]
    (is (= :not-governance (:error result)))
    (is (= :active (get-in world [:force-authorisations auth-id :authorization/status]))
        "grant remains active after a non-governance revoke attempt")))

(deftest force-auth-governance-grant-carries-provenance
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)
        record (get-in world [:force-authorisations auth-id])]
    (is (= :force-authorisation (:authorization/type record)))
    (is (= :governance (:authorization/source record)))
    (is (= :with-governance-actor
           (get-in record [:authorization/provenance :authorization/check])))
    (is (= :governance
           (get-in record [:authorization/provenance :authorization/source])))
    (is (some? (:nonce record)))
    (is (= gov-addr (:created-by record)))
    (is (seq (:authorization/history record)))
    (is (true? (:holds? (inv/force-authorisations-governance-origin? world)))
        "governance-granted record satisfies the governance-origin invariant")))

(deftest force-auth-governance-revoke-transition
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)
        {:keys [world]} (revoke-force-auth world auth-id)]
    (is (= :revoked (get-in world [:force-authorisations auth-id :authorization/status])))
    (is (= :with-governance-actor
           (get-in world [:force-authorisations auth-id :authorization/last-provenance :authorization/check])))
    (is (>= (count (get-in world [:force-authorisations auth-id :authorization/history])) 2)
        "revoke appends to the authorization history")
    (is (true? (:holds? (inv/force-authorisations-governance-origin? world)))
        "revoked record retains governance origin")))

;; ── Adversarial invariant: lifecycle-consistent but governance-less ──────────

(deftest force-auth-governance-origin-invariant-detects-synthetic
  (let [        scope-map {:authorization/id "fa-synthetic"
                   :authorization/type :force-authorisation
                   :held/direction :out :token usdc :amount 100
                   :held/account :escrow-principal
                   :owner/address bob-addr :held/reason :force-authorised-release
                   :held/workflow-id 0}
        scope-hash (hc/domain-hash "force-authorisation-scope" scope-map)
        record {:authorization/id "fa-synthetic"
                :authorization/type :force-authorisation
                :authorization/status :active
                :consumed? false :starts-at 0
                :authorization/scope scope-map
                :authorization/scope-hash scope-hash}
        world {:force-authorisations {"fa-synthetic" record}
               :force-authorisations/consumed {}}
        lifecycle (inv/force-authorisations-lifecycle-consistent? world)
        origin (inv/force-authorisations-governance-origin? world)
        check (inv/check-all world)]
    (is (true? (:holds? lifecycle))
        "hand-injected record is lifecycle-consistent")
    (is (false? (:holds? origin))
        "governance-origin must fail for a synthetic record with no governance provenance")
    (is (false? (get-in check [:results :force-authorisations-governance-origin :holds?]))
        "aggregate robustness check flags governance-origin violation")))
