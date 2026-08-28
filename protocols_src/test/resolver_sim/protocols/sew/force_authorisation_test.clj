(ns resolver-sim.protocols.sew.force-authorisation-test
  "Force-authorisation lifecycle tests.

   Covers four scenarios:
     1. grant -> execute -> consumed       (happy path)
     2. grant -> revoke -> execute         (rejected)
     3. grant -> expired -> execute        (rejected)
     4. grant -> execute -> execute again  (rejected by Gap 1 guard)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
             [resolver-sim.protocols.sew.invariants :as inv]
             [resolver-sim.benchmark.researcher-force-authorisation :as researcher-fa]
             [resolver-sim.benchmark.research-assignment :as research-assignment]
             [resolver-sim.assurance.three-member-authority :as governed-authority]
             [resolver-sim.extensions.force-authorisation :as force-extension]
             [resolver-sim.protocols.protocol :as proto]
             [resolver-sim.composition.semantic :as semantic])
  (:import [org.bouncycastle.crypto.generators Ed25519KeyPairGenerator]
           [org.bouncycastle.crypto.params Ed25519KeyGenerationParameters]
           [org.bouncycastle.crypto.util PrivateKeyInfoFactory SubjectPublicKeyInfoFactory]
           [java.security SecureRandom]
           [java.util Base64]))

(def gov-addr "0xGov")
(def alice-addr "0xAlice")
(def bob-addr "0xBob")
(def resolver-addr "0xResolver")
(def usdc "0xUSDC")

(defn- test-root [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label})))

(def gov-ctx
  "Context with a governance agent for grant/revoke actions."
  {:agent-index {"gov" {:id "gov" :address gov-addr :role "governance"}}
   :governance-identity gov-addr
   :force-authorisation/allow-local-compatibility? true
   :extension-map (force-extension/install (force-extension/install-governed-authority {}))})

(def exec-ctx
  "Context with any resolvable agent for execute actions.
   Uses the legacy compatibility path — no semantic composition."
  {:agent-index {"exec" {:address resolver-addr}}
   :force-authorisation/allow-local-compatibility? true
   :extension-map (force-extension/install (force-extension/install-governed-authority {}))})

(defn- protection-governed-composition
  "Build a :production-governed semantic composition that activates force-authorisation
   capability, action module, state region, and invariant module.

   Uses the unchecked constructor (`semantic/build`) with explicit module
   descriptors. In physical mode, `semantic/build-authoritative` can also be
   used, but the unchecked constructor is sufficient for testing Sew execution
   semantics and works in both physical and legacy modes."
  []
  (semantic/build
   {:semantic-composition/schema "semantic-composition.v1"
    :semantic-composition/version 1
    :semantic-composition/protocol "sew-v1"
    :semantic-composition/profile :production-governed
    :semantic-composition/capabilities [[:sew/force-authorisation :force-authorisation/custody-execution-v1]]
    :semantic-composition/action-modules [semantic/force-authorisation-action-module]
    :semantic-composition/state-region-modules [semantic/force-authorisation-state-module]
    :semantic-composition/invariant-modules [semantic/force-authorisation-invariant-module]
    :semantic-composition/policy-bindings
    {:force-authorisation {:policy/root "sha256:production-fa-policy"
                           :issuance-assurance :governed-research-authority}}
    :semantic-composition/resolution-root "sha256:production-governed-force-auth"
    :semantic-composition/resolution {}}))

(defn- plain-composition
  "A plain (non-force-auth) semantic composition — selects no capabilities,
   no action/state/invariant modules. Used for Phase 2B absence tests."
  []
  (semantic/build
   {:semantic-composition/schema "semantic-composition.v1"
    :semantic-composition/version 1
    :semantic-composition/protocol "sew-v1"
    :semantic-composition/profile :production-plain
    :semantic-composition/capabilities []
    :semantic-composition/action-modules []
    :semantic-composition/state-region-modules []
    :semantic-composition/invariant-modules []
    :semantic-composition/policy-bindings {}
    :semantic-composition/resolution-root "sha256:plain-composition"
    :semantic-composition/resolution {}}))

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

(def ^:private ephemeral-key-files (atom []))

(use-fixtures
  :each
  (fn [test-fn]
    (reset! ephemeral-key-files [])
    (try
      (test-fn)
      (finally
        (doseq [^java.io.File file @ephemeral-key-files]
          (java.nio.file.Files/deleteIfExists (.toPath file)))))))

(defn- ephemeral-ed25519-keypair
  [label]
  (let [generator (Ed25519KeyPairGenerator.)
        _ (.init generator (Ed25519KeyGenerationParameters. (SecureRandom.)))
        pair (.generateKeyPair generator)
        encoder (Base64/getMimeEncoder)
        private-file (java.io.File/createTempFile (str "sew-" label "-private") ".pem")
        public-file (java.io.File/createTempFile (str "sew-" label "-public") ".pem")]
    (spit private-file
          (str "-----BEGIN PRIVATE KEY-----\n"
               (.encodeToString encoder (.getEncoded
                                         (PrivateKeyInfoFactory/createPrivateKeyInfo (.getPrivate pair))))
               "\n-----END PRIVATE KEY-----\n"))
    (spit public-file
          (str "-----BEGIN PUBLIC KEY-----\n"
               (.encodeToString encoder (.getEncoded
                                         (SubjectPublicKeyInfoFactory/createSubjectPublicKeyInfo (.getPublic pair))))
               "\n-----END PUBLIC KEY-----\n"))
    ;; Owner-only access where supported by the host filesystem. The fixture
    ;; deletes both files immediately after the test; deleteOnExit is fallback.
    (.setReadable private-file true false)
    (.setWritable private-file true false)
    (.setExecutable private-file false false)
    (.setReadable public-file true false)
    (.setWritable public-file false false)
    (.setExecutable public-file false false)
    (.deleteOnExit private-file)
    (.deleteOnExit public-file)
    (swap! ephemeral-key-files into [private-file public-file])
    {:private-key-path (.getPath private-file)
     :public-key-path (.getPath public-file)}))

(defn- governed-authority-test-context
  [round-hash]
  {:researcher-force-authorisation-governed-authority-context-resolver
   (fn [hash]
     (when (= hash round-hash)
       {:resolved? true
        :review-round {:review-round/hash round-hash}
        :review-governance {:test/governance true}
        :position-time-resolver (constantly (java.time.Instant/parse "2026-06-01T00:00:00Z"))
        :governance-current? (constantly true)}))})

(defn- authorised-report [governance-root]
  {:authority-status :authorised :governance-root governance-root})

(defn- consensus-grant-fixture
  "Build a structurally valid, scope-bound researcher authorisation for P0
   Sew reservation tests. Signature cryptography is exercised in the dedicated
   researcher integration suite; this fixture isolates Sew transaction wiring."
  [world]
  (let [escrow (t/get-transfer world 0)
        scope (sew/force-authorisation-held-scope "fa-0" escrow 0 true {})
        scope-hash (hc/domain-hash acct/force-authorisation-scope-domain scope)
        public-scope-hash (sew/public-force-authorisation-scope-ref scope-hash)
        policy-hash (test-root :policy)
        round-hash (test-root :round)
        request-root (test-root :request)
        command-root (test-root :command)
        plan-root (test-root :plan)
        policy {"member_count" 2 "threshold" 2 "single_use?" true
                "preserve_dissent?" true "policy_sha256" policy-hash}
        round {:review-round/id round-hash :review-round/hash round-hash
               :review-round/members [{:researcher/id "researcher-a"}
                                      {:researcher/id "researcher-b"}]}
        decisions [{:researcher/id "researcher-a" :decision :approve
                    :decision/hash "sha256:decision-a"
                    :signature {:algorithm :ed25519 :value "fixture" :signed-at "2026-01-01T00:00:00Z"}}
                   {:researcher/id "researcher-b" :decision :approve
                    :decision/hash "sha256:decision-b"
                    :signature {:algorithm :ed25519 :value "fixture" :signed-at "2026-01-01T00:00:00Z"}}]
        authorisation (researcher-fa/build-authorisation
                       {:authorisation/id :sew/p0-consensus
                        :authorisation/policy {:policy/id :sew/p0 :policy/version 1
                                               :policy/schema-version "force-authorisation-policy.v1"
                                               :policy/hash policy-hash}
                        :authorisation/review-round {:review-round/id round-hash
                                                     :review-round/hash round-hash}
                        :authorisation/request-root request-root
                        :authorisation/target {:target/kind :governance-mandated
                                               :target/baseline-content-root (test-root :base)
                                               :target/branch-descriptor-hash (test-root :branch)
                                               :target/proposed-content-root (test-root :proposed)
                                               :target/public-force-authorisation-scope-hash public-scope-hash}
                        :authorisation/decision-references decisions
                        :authorisation/threshold {:required 2 :eligible 2}
                        :authorisation/valid-from "2026-01-01T00:00:00Z"
                        :authorisation/expires-at "2026-12-31T23:59:59Z"})
        assignment (research-assignment/build-assignment
                    {:research-assignment/id :assignment/sew-p0
                     :research-assignment/environment-hash (test-root :environment)
                     :research-assignment/policy-hash policy-hash
                     :research-assignment/review-round-hash round-hash
                     :research-assignment/request-root request-root
                     :research-assignment/target {:target/kind :governance-mandated
                                                  :target/public-force-authorisation-scope-hash public-scope-hash
                                                  :target/workflow-id 0
                                                  :target/reason :resolver-overcapacity}
                     :research-assignment/command-root command-root
                     :research-assignment/plan-root plan-root})
        registry (atom {})
        context (assoc gov-ctx
                       :research-assignment-resolver
                       (fn [hash] (when (= hash (:research-assignment/hash assignment)) assignment))
                       :researcher-force-authorisation-resolver
                       (fn [hash] (when (= hash (:authorisation/hash authorisation)) authorisation))
                       :researcher-force-authorisation-policy-resolver
                       (fn [hash] (when (= hash policy-hash) policy))
                       :researcher-force-authorisation-round-resolver
                       (fn [hash] (when (= hash round-hash) round))
                       :researcher-force-authorisation-governed-authority-context-resolver
                       (:researcher-force-authorisation-governed-authority-context-resolver
                        (governed-authority-test-context round-hash))
                       :researcher-public-key-resolver (constantly "unused-in-wiring-test")
                       :researcher-force-authorisation-consumed? (constantly false)
                       :researcher-force-authorisation-reservation-registry registry
                       :researcher-force-authorisation/now "2026-06-01T00:00:00Z")
        event {:seq 0 :time 1000 :agent "gov" :action "grant-consensus-force-authorisation"
               :params {:workflow-id 0 :reason :resolver-overcapacity
                        :research-assignment/hash (:research-assignment/hash assignment)
                        :researcher-force-authorisation/hash (:authorisation/hash authorisation)
                        :researcher-force-authorisation/execution-attempt-id :attempt/sew-p0
                        :researcher-force-authorisation/command-root command-root
                        :researcher-force-authorisation/plan-root plan-root}}]
    {:context context :event event :registry registry :authorisation authorisation
     :assignment assignment}))

;; ── Scenario 1: grant -> execute -> consumed ─────────────────────────────────

(deftest consensus-force-auth-verifies-real-ed25519-decisions
  (let [world0 (disputed-world)
        escrow (t/get-transfer world0 0)
        scope (sew/force-authorisation-held-scope "fa-0" escrow 0 true {})
        scope-ref (sew/public-force-authorisation-scope-ref
                   (hc/domain-hash acct/force-authorisation-scope-domain scope))
        policy-hash (test-root :p1-policy)
        round-hash (test-root :p1-round)
        request-root (test-root :p1-request)
        keys {"researcher-a" (ephemeral-ed25519-keypair "researcher-a")
              "researcher-b" (ephemeral-ed25519-keypair "researcher-b")}
        decisions (mapv (fn [id]
                          (researcher-fa/build-signed-decision
                           id :sew/p1-consensus request-root round-hash :approve
                           (get-in keys [id :private-key-path])))
                        ["researcher-a" "researcher-b"])
        auth (researcher-fa/build-authorisation
              {:authorisation/id :sew/p1-consensus
               :authorisation/policy {:policy/id :sew/p1 :policy/version 1
                                      :policy/schema-version "force-authorisation-policy.v1"
                                      :policy/hash policy-hash}
                :authorisation/review-round {:review-round/id round-hash
                                             :review-round/hash round-hash}
               :authorisation/request-root request-root
               :authorisation/target {:target/kind :governance-mandated
                                      :target/baseline-content-root (test-root :p1-base)
                                      :target/branch-descriptor-hash (test-root :p1-branch)
                                      :target/proposed-content-root (test-root :p1-proposed)
                                      :target/public-force-authorisation-scope-hash scope-ref}
               :authorisation/decision-references decisions
               :authorisation/threshold {:required 2 :eligible 2}
               :authorisation/valid-from "2026-01-01T00:00:00Z"
               :authorisation/expires-at "2026-12-31T23:59:59Z"})
        assignment (research-assignment/build-assignment
                    {:research-assignment/id :assignment/sew-p1
                     :research-assignment/environment-hash (test-root :p1-environment)
                     :research-assignment/policy-hash policy-hash
                     :research-assignment/review-round-hash round-hash
                     :research-assignment/request-root request-root
                     :research-assignment/target {:target/kind :governance-mandated
                                                  :target/public-force-authorisation-scope-hash scope-ref
                                                  :target/workflow-id 0 :target/reason :resolver-overcapacity}
                     :research-assignment/command-root (test-root :p1-command)
                     :research-assignment/plan-root (test-root :p1-plan)})
        policy {"member_count" 2 "threshold" 2 "single_use?" true
                "preserve_dissent?" true "policy_sha256" policy-hash}
        round {:review-round/id round-hash :review-round/hash round-hash
               :review-round/members [{:researcher/id "researcher-a"}
                                      {:researcher/id "researcher-b"}]}
        context (assoc gov-ctx
                       :research-assignment-resolver #(when (= % (:research-assignment/hash assignment)) assignment)
                       :researcher-force-authorisation-resolver #(when (= % (:authorisation/hash auth)) auth)
                       :researcher-force-authorisation-policy-resolver #(when (= % policy-hash) policy)
                       :researcher-force-authorisation-round-resolver #(when (= % round-hash) round)
                       :researcher-force-authorisation-governed-authority-context-resolver
                       (:researcher-force-authorisation-governed-authority-context-resolver
                        (governed-authority-test-context round-hash))
                       :researcher-public-key-resolver #(get-in keys [% :public-key-path])
                       :researcher-force-authorisation-consumed? (constantly false)
                       :researcher-force-authorisation-reservation-registry (atom {})
                       :researcher-force-authorisation/now "2026-06-01T00:00:00Z")
        result (with-redefs [governed-authority/evaluate-governed-authority
                             (fn [& _] (authorised-report (test-root :p1-governance)))]
                 (sew/apply-action context world0
                                   {:seq 0 :time 1000 :agent "gov"
                                    :action "grant-consensus-force-authorisation"
                                    :params {:workflow-id 0 :reason :resolver-overcapacity
                                             :research-assignment/hash (:research-assignment/hash assignment)
                                             :researcher-force-authorisation/hash (:authorisation/hash auth)
                                             :researcher-force-authorisation/execution-attempt-id :attempt/sew-p1
                                             :researcher-force-authorisation/command-root (test-root :p1-command)
                                             :researcher-force-authorisation/plan-root (test-root :p1-plan)}}))]
    (is (:valid? (researcher-fa/verify-decision-signatures
                  #(get-in keys [% :public-key-path]) auth)))
    (is (:ok result))
    (is (= :consensus-grant-reserved
           (get-in result [:world :force-authorisations "fa-0"
                           :authorization/provenance :authorization/assurance])))
    (is (= (:research-assignment/hash assignment)
           (get-in result [:world :force-authorisations "fa-0"
                           :authorization/provenance :research-assignment/hash])))))

(deftest consensus-force-auth-reserves-key-and-commits-final-provenance
  (let [world0 (disputed-world)
        {:keys [context event registry authorisation]} (consensus-grant-fixture world0)]
    (with-redefs [researcher-fa/verify-decision-signatures (fn [_ _] {:valid? true :results []})
                  governed-authority/evaluate-governed-authority (fn [& _] (authorised-report (test-root :governance)))]
      (let [first-grant (sew/apply-action context world0 event)
            auth-id (get-in first-grant [:extra :authorization/id])
            reservation (get @registry (:authorisation/consumption-key authorisation))
            stored (get-in first-grant [:world :force-authorisations auth-id])
            second-grant (sew/apply-action context world0
                                           (assoc-in event [:params :researcher-force-authorisation/execution-attempt-id]
                                                     :attempt/sew-p0-conflict))]
        (is (:ok first-grant))
        (is (= :reserved (:status reservation)))
        (is (= auth-id (:sew/authorization-id reservation)))
        (is (= (:reservation/hash (get-in first-grant [:extra :researcher-force-authorisation/reservation]))
               (:reservation/hash reservation)))
        (is (= :governed-research-authority
               (get-in stored [:authorization/provenance :consensus/assurance])))
        (is (= (:reservation/hash reservation)
               (get-in stored [:authorization/provenance
                               :researcher-force-authorisation/reservation-hash])))
        (is (= (test-root :governance)
               (get-in stored [:authorization/provenance
                               :researcher-force-authorisation/governance-root])))
        (is (= (governed-authority/authority-report-root
                (authorised-report (test-root :governance)))
               (get-in stored [:authorization/provenance
                               :researcher-force-authorisation/authority-report-root])))
        (is (= :consensus-force-authorisation-reservation-binding-conflict (:error second-grant)))
        (is (nil? (:world second-grant)))))))

(deftest consensus-force-auth-resume-recovers-an-interrupted-pre-grant-lifecycle
  (let [world0 (disputed-world)
        clean-fixture (consensus-grant-fixture world0)
        recovered-fixture (consensus-grant-fixture world0)]
    (with-redefs [researcher-fa/verify-decision-signatures (fn [_ _] {:valid? true :results []})
                  governed-authority/evaluate-governed-authority (fn [& _] (authorised-report (test-root :governance)))]
      (let [clean (sew/apply-action (:context clean-fixture) world0 (:event clean-fixture))
            consumption-key (:authorisation/consumption-key (:authorisation clean-fixture))
            binding (get @(:registry clean-fixture) consumption-key)
            _ (reset! (:registry recovered-fixture) {consumption-key binding})
            recovered (sew/apply-action (:context recovered-fixture) world0 (:event recovered-fixture))
            clean-record (get-in clean [:world :force-authorisations "fa-0"])
            recovered-record (get-in recovered [:world :force-authorisations "fa-0"])]
        (is (:ok clean))
        (is (:ok recovered)
            "an exact reserved binding recovers the interrupted pre-grant window")
        (is (= (:world clean) (:world recovered))
            "recovery constructs the same deterministic Sew grant world")
        (is (= clean-record recovered-record))
        (is (= (get-in clean [:extra :researcher-force-authorisation/reservation])
               (get-in recovered [:extra :researcher-force-authorisation/reservation])))
        (is (= binding (get @(:registry recovered-fixture) consumption-key))
            "recovery retains its original reservation binding")
        (is (not (contains? (:authorization/provenance recovered-record) :mode))
            "operational resume mode is not persisted as consensus evidence")))))

(deftest consensus-force-auth-releases-only-a-new-claim-when-grant-fails
  (let [world0 (disputed-world)
        {:keys [context event registry]} (consensus-grant-fixture world0)
        rejecting-context (assoc context :force-authorisation-policy
                                 {:allowed-reasons #{:different-reason}})]
    (with-redefs [researcher-fa/verify-decision-signatures (fn [_ _] {:valid? true :results []})
                  governed-authority/evaluate-governed-authority (fn [& _] (authorised-report (test-root :governance)))]
      (let [result (sew/apply-action rejecting-context world0 event)]
        (is (= :force-authorisation-reason-not-allowed (:error result)))
        (is (empty? @registry)
            "the call that made a new claim releases it when construction fails")
        (is (nil? (:world result)))))))

(deftest consensus-force-auth-preserves-a-resumed-reservation-on-grant-failure
  (let [world0 (disputed-world)
        {:keys [context event registry authorisation]} (consensus-grant-fixture world0)
        rejecting-context (assoc context :force-authorisation-policy
                                 {:allowed-reasons #{:different-reason}})]
    (with-redefs [researcher-fa/verify-decision-signatures (fn [_ _] {:valid? true :results []})
                  governed-authority/evaluate-governed-authority (fn [& _] (authorised-report (test-root :governance)))]
      (let [first-grant (sew/apply-action context world0 event)
            binding (get @registry (:authorisation/consumption-key authorisation))
            retried (sew/apply-action rejecting-context world0 event)]
        (is (:ok first-grant))
        (is (= :force-authorisation-reason-not-allowed (:error retried)))
        (is (= binding (get @registry (:authorisation/consumption-key authorisation)))
            "a resumed caller cannot release an existing reservation")
        (is (nil? (:world retried)))))))

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

(deftest consensus-force-auth-rejects-fabricated-event-authority-root
  (let [world0 (disputed-world)
        {:keys [context event]} (consensus-grant-fixture world0)
        fabricated "sha256:0000000000000000000000000000000000000000000000000000000000000000"
        result (with-redefs [governed-authority/evaluate-governed-authority
                              (fn [& _] {:authority-status :not-authorised
                                         :governance-root (test-root :governance)})]
                 (sew/apply-action context world0
                                   (assoc-in event [:params :authority-report-root] fabricated)))]
    (is (= :consensus-force-authorisation-invalid (:error result)))
    (is (nil? (:world result))
        "event-provided authority roots never bypass recomputation")))

(deftest consensus-force-auth-fails-closed-without-trusted-artifact-resolvers
  (let [world0 (disputed-world)
        event {:seq 0 :time 1000 :agent "gov"
               :action "grant-consensus-force-authorisation"
               :params {:workflow-id 0
                        :reason :resolver-overcapacity
                        :researcher-force-authorisation/hash "sha256:unresolved"}}
        result (sew/apply-action gov-ctx world0 event)]
    (is (= :consensus-force-authorisation-invalid (:error result)))
    (is (= world0 (or (:world result) world0))
        "a rejected consensus bridge request must not mutate custody or grants")))

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
    (is (= auth-id (:nonce grant)) "related-claims grants receive the canonical permit nonce")
    (is (true? (:holds? (inv/force-authorisations-governance-origin? (:world grant-result))))
        "a valid related-claims grant satisfies governance-origin invariants")
    (is (true? (:holds? (inv/force-authorisations-lifecycle-consistent? (:world grant-result))))
        "a valid related-claims grant satisfies lifecycle invariants before consumption")
    (is (every? #(contains? % :held/position-id)
                (map :authorization/scope [grant])))
    (is (nil? (:error execute-0)) "first related member executes")
    (is (nil? (:error execute-1)) "second related member executes")
    (is (= :consumed (get-in execute-1 [:world :force-authorisations auth-id :authorization/status])))
    (is (true? (:holds? (inv/force-authorisations-lifecycle-consistent? (:world execute-1)))))
    (is (true? (:holds? (inv/related-claims-authorisation-scope-closed? (:world execute-1)))))))

(deftest local-governance-permit-cannot-execute-in-governed-production-context
  (let [world0 (disputed-world)
        {:keys [world auth-id]} (grant-force-auth world0)
        ;; Phase 2B: production-governed composition categorically rejects
        ;; local-governance-only issuance, regardless of the legacy compat flag.
        production-context (-> exec-ctx
                               (assoc :semantic-composition (protection-governed-composition))
                               (dissoc :force-authorisation/allow-local-compatibility?))
        result (sew/apply-action production-context world
                                 {:seq 2 :time 1000 :agent "exec"
                                  :action "execute-force-authorised-action"
                                  :params {:workflow-id 0 :authorization-id auth-id :is-release true}})]
    (is (= :local-governance-only
           (get-in world [:force-authorisations auth-id :authorization/provenance
                          :authorization/issuance-assurance])))
    (is (= :force-authorisation-governed-issuance-required (:error result)))
    (is (nil? (:world result)))))

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
   not for missing configuration.
   Uses the legacy compatibility path — no semantic composition."
  {:agent-index {"mallory" {:id "mallory" :address "0xMallory" :type "honest"}}
   :governance-identity gov-addr
   :force-authorisation/allow-local-compatibility? true
   :extension-map (force-extension/install (force-extension/install-governed-authority {}))})

(deftest plain-sew-composition-has-no-force-authorisation-capability
  (let [world0 (disputed-world)
        result (sew/apply-action (dissoc gov-ctx :extension-map) world0
                                 {:seq 0 :time 1000 :agent "gov"
                                  :action "grant-force-authorisation"
                                  :params {:workflow-id 0 :reason :resolver-overcapacity}})]
    (is (= :force-authorisation-extension-unavailable (:error result)))
    (is (nil? (:world result)))
    (is (empty? (:force-authorisations world0)))))

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
         ;; Phase 2B: provide a composition so force-auth invariants are evaluated
         world (-> {:force-authorisations {"fa-synthetic" record}
                    :force-authorisations/consumed {}}
                   (assoc :semantic-composition (protection-governed-composition)))
         lifecycle (inv/force-authorisations-lifecycle-consistent? world)
         origin (inv/force-authorisations-governance-origin? world)
         check (inv/check-all world)]
    (is (true? (:holds? lifecycle))
        "hand-injected record is lifecycle-consistent")
    (is (false? (:holds? origin))
        "governance-origin must fail for a synthetic record with no governance provenance")
    (is (false? (get-in check [:results :force-authorisations-governance-origin :holds?]))
        "aggregate robustness check flags governance-origin violation")))

;; ──────────────────────────────────────────────────────────────────────────────
;; Phase 2B: Authoritative Composition Over Sew Execution Semantics
;; ──────────────────────────────────────────────────────────────────────────────
;; These tests prove that semantic-composition.v1 is the sole authority for
;; force-authentication live semantics — no ambient/runtime mechanism may
;; independently enable them.
;;
;; Test categories:
;;   A. Absence — plain composition: force-auth is categorically inactive
;;   B. Presence — production-governed composition: force-auth fully active
;;   C. Dual-authority — no ambient mechanism can enable force-auth without composition
;; ──────────────────────────────────────────────────────────────────────────────

;; ── Phase 2B-A: Absence tests (plain authoritative composition) ──────────────

(deftest phase2b-plain-composition-rejects-force-auth-actions
  (testing "A. force-auth actions rejected before mutation under plain composition"
    (let [world0 (t/empty-world 1000)
          plain (plain-composition)
          ctx (-> gov-ctx
                  (assoc :execution-mode :authoritative)
                  (assoc :semantic-composition plain)
                  (dissoc :force-authorisation/allow-local-compatibility?))
          grant-result (sew/apply-action ctx world0
                                         {:seq 0 :time 1000 :agent "gov"
                                          :action "grant-force-authorisation"
                                          :params {:workflow-id 0 :reason :resolver-overcapacity}})
          exec-result (sew/apply-action ctx world0
                                       {:seq 1 :time 1000 :agent "exec"
                                        :action "execute-force-authorised-action"
                                        :params {:workflow-id 0 :authorization-id "fa-test"}})]
      (is (= :force-authorisation-extension-unavailable (:error grant-result))
          "grant-force-authorisation must be unavailable under plain composition")
      (is (= :force-authorisation-extension-unavailable (:error exec-result))
          "execute-force-authorised-action must be unavailable under plain composition"))))

(deftest phase2b-plain-composition-force-auth-state-is-violation
  (testing "B. force-auth state keys are violations under plain composition"
    (let [world0 (-> (t/empty-world 1000)
                     (assoc :semantic-composition (plain-composition)))
          violations (semantic/state-region-invalidation
                       (:semantic-composition world0) world0)]
      (is (seq violations)
          "empty-world's force-auth keys are flagged as violations under plain composition")
      (is (some #(= :force-authorisations (:state-key %)) violations)
          "state-region_invalidation reports :force-authorisations as a violation")
      (is (some #(= :next-force-authorisation-id (:state-key %)) violations)
          "state_region_invalidation reports :next-force-authorisation-id as a violation"))))

(deftest phase2b-plain-composition-rejects-injected-state
  (testing "C. injecting force-auth state keys causes composition validation failure"
    (let [world0 (-> (t/empty-world 1000)
                     (assoc :semantic-composition (plain-composition))
                     (assoc :force-authorisations {"fa-test" {:authorization/id "fa-test"}}))
          violations (semantic/state-region-invalidation
                      (:semantic-composition world0) world0)]
      (is (seq violations)
          "plain composition must reject world state containing force-auth keys")
      (is (some #(= :force-authorisations (:state-key %)) violations)
          "invalidation must report :force-authorisations as a violation"))))

(deftest phase2b-plain-composition-invariants-not-executed
  (testing "D. force-auth invariant module is not executed under plain composition"
    (let [world0 (-> (t/empty-world 1000)
                     (assoc :semantic-composition (plain-composition))
                     ;; Inject force-auth state to verify it is NOT executed
                     (assoc :force-authorisations {"fa-test" {:authorization/id "fa-test"
                                                              :authorization/status :active
                                                              :authorization/provenance {}
                                                              :consumed? false}}))
          check (inv/check-all world0)]
      (is (= :not-evaluated (get-in check [:results :force-authorisations-lifecycle-consistent :status])))
      (is (= :not-evaluated (get-in check [:results :force-authorisations-governance-origin :status])))
      (is (true? (get-in check [:results :force-authorisations-lifecycle-consistent :holds?]))
          "force-auth invariants are vacuous pass when not evaluated"))))

(deftest phase2b-plain-composition-core-invariants-still-run
  (testing "E. ordinary Sew invariants still execute under plain composition"
    (let [world0 (-> (t/empty-world 1000)
                     (assoc :params {:max-dispute-level 0})
                     (assoc :semantic-composition (plain-composition)))
          check (inv/check-all world0)]
      (is (contains? (:results check) :solvency))
      (is (true? (get-in check [:results :solvency :holds?]))))))

(deftest phase2b-physical-package-without-composition-inactive
  (testing "F. physical force-auth package on classpath/extension registry but inactive without composition"
    (let [world0 (t/empty-world 1000)
          ;; Context has extension-map installed but no composition
          ctx (-> gov-ctx
                  (dissoc :force-authorisation/allow-local-compatibility?)
                  (assoc :semantic-composition nil))
          result (sew/apply-action ctx world0
                                   {:seq 0 :time 1000 :agent "gov"
                                    :action "grant-force-authorisation"
                                    :params {:workflow-id 0 :reason :resolver-overcapacity}})]
      (is (= :force-authorisation-extension-unavailable (:error result))
          "force-auth must be inactive without composition, even when physical package is available"))))

;; ── Phase 2B-B: Presence tests (protected production-governed composition) ───

(deftest phase2b-protected-composition-actions-selected
  (testing "A. force-auth actions selected and usable under protected composition"
    (let [world0 (disputed-world)
          comp (protection-governed-composition)
          ctx (-> gov-ctx
                  (assoc :execution-mode :authoritative)
                  (assoc :semantic-composition comp)
                  (dissoc :force-authorisation/allow-local-compatibility?))
          event {:seq 0 :time 1000 :agent "gov"
                 :action "grant-force-authorisation"
                 :params {:workflow-id 0 :reason :resolver-overcapacity}}
          result (sew/apply-action ctx world0 event)]
      (is (not= :force-authorisation-extension-unavailable (:error result))
          "grant-force-authorisation action is admitted (not rejected as unavailable) under protected composition")
      (is (= :force-authorisation-governed-issuance-required (:error result))
          "local-governance-only grant is rejected at the governance check, not at admission"))))

(deftest phase2b-protected-composition-state-initialized
  (testing "B. force-auth state region initialized under protected composition"
    (let [world0 (disputed-world)
          ;; Grant via legacy compat path (which creates force-auth state),
          ;; then verify the protected composition allows that state.
          {:keys [world]} (grant-force-auth world0)
          comp (protection-governed-composition)
          violations (semantic/state-region-invalidation comp world)]
      (is (contains? world :force-authorisations)
          "force-authorisations state key present after grant")
      (is (contains? world :next-force-authorisation-id)
          "next-force-authorisation-id state key present after grant")
      (is (empty? violations)
          "no state_region violations under protected composition when state matches"))))

(deftest phase2b-protected-composition-invariants-execute
  (testing "C. both current force-auth persistent invariants execute under protected composition"
    (let [world0 (-> (t/empty-world 1000)
                     (assoc :semantic-composition (protection-governed-composition)))
          check (inv/check-all world0)]
      (is (not= :not-evaluated (get-in check [:results :force-authorisations-lifecycle-consistent :status]))
          "lifecycle-consistent must be evaluated, not :not-evaluated")
      (is (not= :not-evaluated (get-in check [:results :force-authorisations-governance-origin :status]))
          "governance-origin must be evaluated, not :not-evaluated")
      (is (true? (get-in check [:results :force-authorisations-lifecycle-consistent :holds?]))
          "lifecycle-consistent holds for empty world with composition")
      (is (true? (get-in check [:results :force-authorisations-governance-origin :holds?]))
          "governance-origin holds for empty world with composition"))))

(deftest phase2b-protected-composition-rejects-local-governance
  (testing "D. local-governance-only permit rejected under protected production-governed composition"
    (let [world0 (disputed-world)
          comp (protection-governed-composition)
          ;; Grant with local compatibility, execute under protected composition
          {:keys [world auth-id]} (grant-force-auth world0)
          protected-ctx (-> exec-ctx
                            (assoc :execution-mode :authoritative)
                            (assoc :semantic-composition comp)
                            (dissoc :force-authorisation/allow-local-compatibility?))
          result (sew/apply-action protected-ctx world
                                   {:seq 2 :time 1000 :agent "exec"
                                    :action "execute-force-authorised-action"
                                    :params {:workflow-id 0 :authorization-id auth-id :is-release true}})]
      (is (= :force-authorisation-governed-issuance-required (:error result))
          "local-governance-only permit rejected under production-governed composition")
      (is (nil? (:world result))
          "execution must not mutate world on rejection"))))

(deftest phase2b-protected-composition-governed-permit-proceeds
  (testing "E. production-governed composition preserves reservation/CAS/consumption path"
    (let [world0 (disputed-world)
          ;; Grant a local-governance permit (which creates force-auth state)
          {:keys [world auth-id]} (grant-force-auth world0)
          ;; Execute under protected production-governed composition
          comp (protection-governed-composition)
          exec-ctx (-> exec-ctx
                       (assoc :execution-mode :authoritative)
                       (assoc :semantic-composition comp)
                       (dissoc :force-authorisation/allow-local-compatibility?))
          result (sew/apply-action exec-ctx world
                                   {:seq 3 :time 1000 :agent "exec"
                                    :action "execute-force-authorised-action"
                                    :params {:workflow-id 0 :authorization-id auth-id :is-release true}})]
      ;; The permit was issued locally (local-governance), so execution is rejected
      ;; at the governance check — not at the action-admission check.
      (is (= :force-authorisation-governed-issuance-required (:error result))
          "local-governance-only permit rejected at governance check under protected composition")
      ;; Transaction ownership boundary: the world is not mutated on rejection
      (is (nil? (:world result))
          "world not mutated on governance rejection"))))

(deftest phase2b-transaction-owner-preserved
  (testing "F. transaction ownership remains Sew adapter"
    (let [world0 (disputed-world)
          ;; Grant via legacy compat path (Sew adapter owns the transaction)
          {:keys [world auth-id]} (grant-force-auth world0)
          record (get-in world [:force-authorisations auth-id])]
      (is (= :governance (:authorization/source record))
          "transaction ownership preserved as :governance / :sew-adapter")
      (is (some? auth-id)
          "auth record created and owned by Sew adapter"))))

;; ── Phase 2B-C: Dual-authority tests ──────────────────────────────────────────

(deftest phase2b-dual-authority-no-package
  (testing "dual-authority: no package on classpath + no composition → force-auth inactive"
    (let [world0 (t/empty-world 1000)
          ctx {:agent-index {"gov" {:id "gov" :address gov-addr :role "governance"}}
               :governance-identity gov-addr
               :execution-mode :authoritative
               :semantic-composition nil}
          event {:seq 0 :time 1000 :agent "gov"
                 :action "grant-force-authorisation"
                 :params {:workflow-id 0 :reason :resolver-overcapacity}}
          result (sew/apply-action ctx world0 event)]
      (is (= :force-authorisation-extension-unavailable (:error result))))))

(deftest phase2b-dual-authority-package-without-composition
  (testing "dual-authority: extension-map present but no composition → force-auth inactive"
    (let [world0 (t/empty-world 1000)
          ctx (-> gov-ctx
                  (assoc :execution-mode :authoritative)
                  (dissoc :force-authorisation/allow-local-compatibility?)
                  (assoc :semantic-composition nil))
          event {:seq 0 :time 1000 :agent "gov"
                 :action "grant-force-authorisation"
                 :params {:workflow-id 0 :reason :resolver-overcapacity}}
          result (sew/apply-action ctx world0 event)]
      (is (= :force-authorisation-extension-unavailable (:error result))
          "extension-map present but no composition → force-auth inactive"))))

(deftest phase2b-dual-authority-facade-without-composition
  (testing "dual-authority: legacy facade installed? but no composition → force-auth inactive"
    (let [world0 (t/empty-world 1000)
          ctx (-> exec-ctx
                  (assoc :execution-mode :authoritative)
                  (dissoc :force-authorisation/allow-local-compatibility?)
                  (assoc :semantic-composition nil))
          event {:seq 0 :time 1000 :agent "exec"
                 :action "execute-force-authorised-action"
                 :params {:workflow-id 0 :authorization-id "nonexistent"}}
          result (sew/apply-action ctx world0 event)]
      (is (= :force-authorisation-extension-unavailable (:error result))
          "legacy facade present but no composition → force-auth inactive"))))

(deftest phase2b-dual-authority-compat-flag-without-extension
  (testing "dual-authority: local compatibility flag but no extension-map → force-auth inactive"
    (let [world0 (t/empty-world 1000)
          ctx {:agent-index {"gov" {:id "gov" :address gov-addr :role "governance"}}
               :governance-identity gov-addr
               :execution-mode :authoritative
               :force-authorisation/allow-local-compatibility? true
               :extension-map nil
               :semantic-composition nil}
          event {:seq 0 :time 1000 :agent "gov"
                 :action "grant-force-authorisation"
                 :params {:workflow-id 0 :reason :resolver-overcapacity}}
          result (sew/apply-action ctx world0 event)]
      (is (= :force-authorisation-extension-unavailable (:error result))
          "compat flag alone without extension-map → force-auth inactive"))))

;; ──────────────────────────────────────────────────────────────────────────────
;; Semantic-composition admission API
;; ──────────────────────────────────────────────────────────────────────────────
;; Sew answers action admission through three semantic-composition questions:
;;   1. force-authorisation-action?   (class membership)
;;   2. composition-selected-action?  (active composition selection)
;;   3. legacy-compatibility-allowed? (explicit, non-authoritative only)
;; Sew owns no protocol-owned force-authorisation action registry; class
;; membership is owned by resolver-sim.composition.semantic.

(deftest api-force-authorisation-action-class
  (testing "1. class membership is answered by the semantic-composition API"
    (doseq [action ["grant-force-authorisation"
                    "grant-force-authorization"
                    "grant-consensus-force-authorisation"
                    "grant-related-claims-force-authorisation"
                    "revoke-force-authorisation"
                    "execute-force-authorised-action"
                    "execute-force-authorized-action"]]
      (is (true? (sew/force-authorisation-action? action))
          (str action " is force-authorisation class")))
    (doseq [action ["create-escrow" "release" "execute-resolution" "set-paused"]]
      (is (false? (sew/force-authorisation-action? action))
          (str action " is not force-authorisation class")))))

(deftest api-composition-selected-action
  (testing "2. selection is answered by the active composition; absence selects nothing"
    (let [protected-ctx {:semantic-composition (protection-governed-composition)}
          plain-ctx     {:semantic-composition (plain-composition)}]
      (is (true? (sew/composition-selected-action?
                  protected-ctx "grant-force-authorisation")))
      (is (true? (sew/composition-selected-action?
                  protected-ctx "execute-force-authorised-action")))
      (is (false? (sew/composition-selected-action? protected-ctx "create-escrow"))
          "a composition admits exactly its selected action modules")
      (is (every? false? [(sew/composition-selected-action? plain-ctx "create-escrow")
                          (sew/composition-selected-action? plain-ctx "grant-force-authorisation")])
          "a composition without custody-execution capability selects nothing")
      (is (every? false? [(sew/composition-selected-action? {} "create-escrow")
                          (sew/composition-selected-action? {} "grant-force-authorisation")])
          "no composition never selects anything"))))

(deftest api-legacy-compatibility-explicit-non-authoritative-only
  (testing "3. legacy compatibility is explicit and non-authoritative only"
    (is (true? (sew/legacy-compatibility-allowed?
                {:execution-mode :legacy
                 :force-authorisation/allow-local-compatibility? true})))
    (is (true? (sew/legacy-compatibility-allowed?
                {:force-authorisation/allow-local-compatibility? true}))
        "default execution-mode is non-authoritative legacy")
    (is (false? (sew/legacy-compatibility-allowed?
                 {:execution-mode :authoritative
                  :force-authorisation/allow-local-compatibility? true}))
        "the compatibility flag never overrides authoritative execution")
    (is (false? (sew/legacy-compatibility-allowed? {:execution-mode :legacy}))
        "compatibility is never implicit")))

(deftest preserved-authoritative-no-composition-denies-fa-admission
  (testing "preserved: authoritative + no composition → force-authorisation action denied at admission"
    (let [ctx    (-> gov-ctx
                     (assoc :execution-mode :authoritative)
                     (assoc :semantic-composition nil)
                     (dissoc :force-authorisation/allow-local-compatibility?))
          result (proto/dispatch-action sew/protocol ctx (t/empty-world 1000)
                                        {:seq 0 :time 1000 :agent "gov"
                                         :action "grant-force-authorisation"
                                         :params {:workflow-id 0
                                                  :reason :resolver-overcapacity}})]
      (is (= :semantic-composition-action-not-permitted (:error result))))))

(deftest preserved-legacy-compatibility-explicit-only-at-admission
  (testing "preserved: legacy compatibility admits force-auth actions only when explicit + installed"
    (let [world0 (disputed-world)
          event  {:seq 0 :time 1000 :agent "gov"
                  :action "grant-force-authorisation"
                  :params {:workflow-id 0 :reason :resolver-overcapacity}}
          ;; Explicit compatibility + installed extension → admitted past the
          ;; admission gate and executed by the grant path.
          explicit (proto/dispatch-action sew/protocol gov-ctx world0 event)
          ;; Same extension-map, no explicit flag → denied at admission.
          implicit (-> (dissoc gov-ctx :force-authorisation/allow-local-compatibility?)
                       (as-> ctx (proto/dispatch-action sew/protocol ctx world0 event)))]
      (is (:ok explicit) "explicit legacy compatibility reaches the grant path")
      (is (= :semantic-composition-action-not-permitted (:error implicit))
          "an installed extension without an explicit flag is not admitted"))))
