(ns resolver-sim.protocols.sew.held-custody-test-env
  "Deterministic, test-only public-path environment for Sew held custody.
   Builders return action transcripts and never assert test expectations."
  (:require [resolver-sim.protocols.sew :as sew]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snapshots]
            [resolver-sim.protocols.sew.invariants :as invariants]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const profile-schema-version 1)
(def ^:const profile-id :held-custody-force-auth-v1)
(def ^:const terminal-run-status :terminal-verified)
(def ^:const run-lifecycles
  #{:initialized :consensus-grant-reserved :execution-manifested
    :execution-recorded :terminal-receipt-recorded :terminal-verified
    :failed-after-reservation})
(def ^:const legal-run-transitions
  {:initialized #{:consensus-grant-reserved}
   :consensus-grant-reserved #{:execution-manifested :failed-after-reservation}
   :execution-manifested #{:execution-recorded :failed-after-reservation}
   :execution-recorded #{:terminal-receipt-recorded :failed-after-reservation}
   :terminal-receipt-recorded #{:terminal-verified}
   :failed-after-reservation #{:terminal-receipt-recorded}})
(def ^:const stable-run-anchor-keys
  [:run/id :research-assignment/hash :researcher-force-authorisation/hash
   :reservation/hash :reservation/execution-attempt-id
   :sew/authorization-id :sew/scope-hash])
(def ^:const consensus-check-keys
  #{:authorisation-valid? :decision-signatures-valid? :policy-binding-valid?
    :review-round-binding-valid? :public-sew-binding-valid?
    :reservation-binding-valid? :receipt-binding-valid?})

(def identities
  {:alice "0xAlice"
   :bob "0xBob"
   :governance "0xGov"
   :executor "0xExecutor"
   :resolver "0xResolver"
   :token :USDC})

(defn profile-hash
  "Canonical environment hash for a test-environment-profile.v1. The profile
   describes stable capability/configuration only; run state belongs in a
   test-environment envelope."
  [profile]
  (hash-ref/sha256-ref
   (hash/hash-with-intent {:hash/intent :evidence-record}
                          (dissoc profile :profile/hash :environment/hash))))

(defn environment-hash
  "Alias that makes the profile hash's environment-commitment role explicit."
  [profile]
  (profile-hash profile))

(defn- valid-consensus-validation?
  [validation]
  (and (map? validation)
       (every? true? (map validation consensus-check-keys))))

(defn derive-trust-level
  "Derive assurance from verified environment facts. A profile's requested
   level is never trusted. Consensus requires every researcher/public binding
   check; otherwise the strongest possible result is public address-bound."
  [{:keys [construction governance consensus-validation]}]
  (let [public? (and (= :public-actions (:world/mode construction))
                     (= :resolver-sim.protocols.sew/apply-action
                        (:action/entrypoint construction))
                     (true? (:deterministic? construction))
                     (= :restricted (:governance/mode governance))
                     (some? (:governance/identity governance))
                     (= :address-bound (:authorization/assurance governance)))]
    (cond
      ;; A profile can describe consensus-capable fixtures but never proves a
      ;; completed run. Terminal receipt/evidence verification derives the
      ;; stronger assurance in a separate run envelope.
      public? :public-address-bound
      :else :mechanism-synthetic)))

(defn build-test-environment-profile
  "Build a hash-bound profile from deterministic configuration. The optional
   `:consensus-validation` value is runtime verification output and is never
   persisted; it can only elevate a fully public profile when every required
   researcher/public binding check is true."
  [{:keys [protocol snapshot-id snapshot-root construction governance consensus
           custody parameter-attribution checks limitations capabilities
           resolver-provenance supersedes upgrade consensus-validation]}]
  (let [derived-level (derive-trust-level {:construction construction
                                           :governance governance
                                           :consensus-validation consensus-validation})
        base {:artifact/type :test-environment-profile
              :artifact/version profile-schema-version
              :profile/id profile-id
              :profile/trust-level derived-level
              :protocol protocol
              :protocol/snapshot {:snapshot/id snapshot-id :snapshot/root snapshot-root}
              :construction construction
              :governance governance
              :consensus consensus
              :resolver-provenance resolver-provenance
              :custody custody
              :parameter-attribution parameter-attribution
              :environment/capabilities (set capabilities)
              :environment/limitations (set limitations)
              :environment/supersedes supersedes
              :environment/upgrade upgrade
              :profile/checks (vec checks)
              :profile/limitations (vec limitations)}]
    (let [environment-hash (profile-hash base)]
      (assoc base :profile/hash environment-hash
                  :environment/hash environment-hash))))

(defn validate-test-environment-profile
  "Validate profile shape, integrity, and that its declared level is consistent
   with the supplied independently derived consensus verification results."
  [profile & {:keys [consensus-validation]}]
  (let [derived (derive-trust-level {:construction (:construction profile)
                                     :governance (:governance profile)
                                     :consensus-validation consensus-validation})
        errors (cond-> []
                 (not= :test-environment-profile (:artifact/type profile))
                 (conj :invalid-artifact-type)
                 (not= profile-schema-version (:artifact/version profile))
                 (conj :unsupported-profile-version)
                 (not= profile-id (:profile/id profile))
                 (conj :invalid-profile-id)
                 (not (hash-ref/valid-sha256-ref? (get-in profile [:protocol/snapshot :snapshot/root])))
                 (conj :invalid-snapshot-root)
                 (not= (:profile/hash profile) (profile-hash profile))
                 (conj :profile-hash-mismatch)
                 (not= (:environment/hash profile) (profile-hash profile))
                 (conj :environment-hash-mismatch)
                 (not (set? (:environment/capabilities profile)))
                 (conj :invalid-capabilities)
                 (not (set? (:environment/limitations profile)))
                 (conj :invalid-limitations)
                 (and (get-in profile [:environment/supersedes :environment/hash])
                      (not (hash-ref/valid-sha256-ref?
                            (get-in profile [:environment/supersedes :environment/hash]))))
                 (conj :invalid-supersedes-reference)
                 (and (:environment/upgrade profile)
                      (not (keyword? (get-in profile [:environment/upgrade :upgrade/kind]))))
                 (conj :invalid-upgrade-kind)
                 (and (contains? (:environment/capabilities profile)
                                 :terminal-force-authorisation-receipt)
                      (contains? (:environment/limitations profile)
                                 :terminal-consumption-not-modelled))
                 (conj :contradictory-terminal-capability)
                 (not= (:profile/trust-level profile) derived)
                 (conj :derived-trust-level-mismatch))]
    {:valid? (empty? errors)
     :derived-trust-level derived
     :errors errors}))

(defn envelope-hash
  [envelope]
  (hash-ref/sha256-ref
   (hash/hash-with-intent {:hash/intent :evidence-record}
                          (dissoc envelope :run/hash))))

(defn build-test-environment-envelope
  "Build a per-run envelope referencing a stable environment hash. A completed
   consensus-public claim is derived only for terminally verified evidence;
   callers cannot declare it directly."
  [{:keys [environment-hash profile-id previous-envelope-hash transition
           run-id seed started-at completed-at transcript-root
           world-before-root world-after-root research-assignment-hash
           researcher-authorisation-hash reservation-hash sew-authorisation-id
           sew-scope-hash held-adjustment-id held-artifact-hash outcome-manifest-hash
           consumption-receipt-hash execution-evidence-hash lifecycle outcome
           reservation-attempt-id]}]
  (let [terminal? (= terminal-run-status lifecycle)
        ;; Structural construction never authenticates terminal artifacts.
        ;; Only verify-test-environment-envelope may derive stronger assurance.
        aggregate :public-address-bound
        base {:artifact/type :test-environment-run
              :artifact/version 1
              :environment/hash environment-hash
              :environment/profile-id profile-id
              :run/previous-envelope-hash previous-envelope-hash
              :run/transition transition
              :run/id run-id :run/seed seed :run/started-at started-at
              :run/completed-at completed-at :actions/transcript-root transcript-root
              :world/before-root world-before-root :world/after-root world-after-root
              :research-assignment/hash research-assignment-hash
              :researcher-force-authorisation/hash researcher-authorisation-hash
              :reservation/hash reservation-hash
              :reservation/execution-attempt-id reservation-attempt-id
              :sew/authorization-id sew-authorisation-id
              :sew/scope-hash sew-scope-hash :held-adjustment/id held-adjustment-id
              :held-custody-artifact/hash held-artifact-hash
              :outcome-manifest/hash outcome-manifest-hash
              :consumption-receipt/hash consumption-receipt-hash
              :execution-evidence/hash execution-evidence-hash
              :governance/assurance :address-bound
              :consensus/assurance (when (not= lifecycle :initialized)
                                     :researcher-threshold-authenticated)
              :run/lifecycle lifecycle :run/outcome outcome
              :run/assurance aggregate}]
    (assoc base :run/hash (envelope-hash base))))

(defn validate-test-environment-envelope
  "Validate immutable run-envelope integrity and the minimum transition
   contract. The caller supplies the preceding envelope when present so an
   envelope cannot silently switch environments mid-lifecycle."
  [envelope & {:keys [previous-envelope]}]
  (let [lifecycle (:run/lifecycle envelope)
        terminal? (= lifecycle terminal-run-status)
        errors (cond-> []
                 (not= :test-environment-run (:artifact/type envelope))
                 (conj :invalid-run-artifact-type)
                 (not= 1 (:artifact/version envelope))
                 (conj :unsupported-run-version)
                 (not (hash-ref/valid-sha256-ref? (:environment/hash envelope)))
                 (conj :invalid-environment-hash)
                 (not (contains? run-lifecycles lifecycle))
                 (conj :invalid-run-lifecycle)
                 (not= (:run/hash envelope) (envelope-hash envelope))
                 (conj :run-hash-mismatch)
                 (and previous-envelope
                      (not= (:run/previous-envelope-hash envelope)
                            (:run/hash previous-envelope)))
                 (conj :previous-envelope-mismatch)
                 (and previous-envelope
                      (not= (:environment/hash envelope)
                            (:environment/hash previous-envelope)))
                 (conj :environment-switch-during-run)
                 (and (= lifecycle :initialized) (:run/previous-envelope-hash envelope))
                 (conj :genesis-has-predecessor)
                 (and (not= lifecycle :initialized) (nil? (:run/previous-envelope-hash envelope)))
                 (conj :non-genesis-missing-predecessor)
                 (and previous-envelope
                      (not (contains? (get legal-run-transitions
                                          (:run/lifecycle previous-envelope) #{})
                                      lifecycle)))
                 (conj :illegal-lifecycle-transition)
                 (and previous-envelope
                      (some #(not= (get envelope %) (get previous-envelope %))
                            stable-run-anchor-keys))
                 (conj :stable-run-anchor-mismatch)
                 (= :consensus-authenticated-public (:run/assurance envelope))
                 (conj :unverified-consensus-assurance)
                 (and terminal? (not (hash-ref/valid-sha256-ref?
                                      (:consumption-receipt/hash envelope))))
                 (conj :missing-terminal-receipt)
                 (and terminal? (not (hash-ref/valid-sha256-ref?
                                      (:execution-evidence/hash envelope))))
                 (conj :missing-terminal-execution-evidence))]
    {:valid? (empty? errors) :errors errors}))

(defn verify-test-environment-envelope
  "Resolve and verify terminal evidence separately from structural envelope
   validation. Each verifier is trusted code over a resolved artifact; callers
   cannot satisfy this function with merely syntactic hash references."
  [envelope {:keys [previous-envelope resolve-reservation resolve-outcome-manifest
                    resolve-terminal-receipt resolve-execution-evidence
                    verify-reservation verify-outcome-manifest
                    verify-terminal-receipt verify-execution-evidence]}]
  (let [structural (validate-test-environment-envelope envelope
                                                        :previous-envelope previous-envelope)
        resolve+verify (fn [reference resolver verifier]
                         (let [artifact (when (and (fn? resolver) reference) (resolver reference))]
                           (boolean (and artifact (fn? verifier) (verifier artifact)))))
        checks {:reservation-valid?
                (resolve+verify (:reservation/hash envelope) resolve-reservation verify-reservation)
                :outcome-manifest-valid?
                (resolve+verify (:outcome-manifest/hash envelope) resolve-outcome-manifest verify-outcome-manifest)
                :terminal-receipt-valid?
                (resolve+verify (:consumption-receipt/hash envelope) resolve-terminal-receipt verify-terminal-receipt)
                :execution-evidence-valid?
                (resolve+verify (:execution-evidence/hash envelope) resolve-execution-evidence verify-execution-evidence)}
        terminal? (= terminal-run-status (:run/lifecycle envelope))
        verified? (and (:valid? structural) terminal? (every? true? (vals checks)))
        derived-assurance (if verified? :consensus-authenticated-public :public-address-bound)]
    {:valid? verified?
     :structural structural
     :checks checks
     :derived {:governance/assurance (:governance/assurance envelope)
               :consensus/assurance (:consensus/assurance envelope)
               :run/lifecycle (:run/lifecycle envelope)
               :run/outcome (:run/outcome envelope)
               :run/assurance derived-assurance}
     :claimed-assurance-match? (= (:run/assurance envelope) derived-assurance)}))

(defn parameter-fixture
  "Return a deterministic parameter snapshot suitable for a rooted context.
   This is an opaque content commitment; it does not resolve or economically
   validate parameter values."
  ([] (parameter-fixture {}))
  ([overrides]
   (merge {:parameter-fixture/schema "held-custody-test-parameters.v1"
           :protocol/id :sew
           :parameters {:sew/escrow-principal {:unit :USDC}
                        :sew/force-authorisation {:enabled? true}}}
          overrides)))

(defn parameter-root
  "Return the canonical SHA-256 reference of a deterministic parameter fixture."
  [fixture]
  (hash-ref/sha256-ref
   (hash/hash-with-intent {:hash/intent :evidence-record} fixture)))

(defn authoritative-parameter-context
  [fixture]
  {:parameter-context/type :protocol-parameters
   :parameter-context/root (parameter-root fixture)
   :parameter-context/version 1})

(defn parameter-address
  ([] (parameter-address :sew/escrow-principal))
  ([id] {:parameter/id id}))

(defn test-context
  "Create a restricted, address-bound public Sew action context."
  ([] (test-context {}))
  ([{:keys [snapshot force-authorisation-policy]
     :or {snapshot (snapshots/escrow-snapshot
                     {:escrow-fee-bps 50 :appeal-window-duration 0
                      :max-dispute-duration 3600})}}]
   {:agent-index {"alice" {:id "alice" :address (:alice identities) :type "honest"}
                  "bob" {:id "bob" :address (:bob identities) :type "honest"}
                  "gov" {:id "gov" :address (:governance identities) :role "governance"}
                  "executor" {:id "executor" :address (:executor identities) :type "honest"}}
    :snapshot snapshot
    :governance-mode :restricted
    :governance-identity (:governance identities)
    :force-authorisation-policy
    (or force-authorisation-policy
        {:allowed-reasons #{:resolver-overcapacity}
         :default-duration 3600
         :max-duration 7200})}))

(defn public-empty-held-world
  ([] (public-empty-held-world {}))
  ([{:keys [block-time complete?] :or {block-time 1000 complete? true}}]
   (cond-> (types/empty-world block-time)
     complete? (assoc-in [:params :held-adjustments/complete?] true)
     complete? (assoc-in [:params :held-ledger/origin] :zero))))

(defn- apply-event
  [context world transcript event]
  (let [result (sew/apply-action context world event)]
    {:result result
     :world (or (:world result) world)
     :transcript (conj transcript {:event event
                                   :ok? (:ok result)
                                   :error (:error result)})}))

(defn public-disputed-world
  "Reach a disputed escrow through public Sew actions and retain the transcript.
   `:custom-resolver` is configured at creation; no state is directly mutated."
  ([] (public-disputed-world {}))
  ([{:keys [context amount token from to block-time]
     :or {amount 10000 token :USDC from "alice" to "0xBob" block-time 1000}}]
   (let [context (or context (test-context))
         world0 (public-empty-held-world {:block-time block-time})
         created (apply-event context world0 []
                              {:seq 0 :time block-time :agent from :action "create-escrow"
                               :params {:token (name token) :to to :amount amount
                                        :custom-resolver (:resolver identities)}})
         workflow-id (get-in created [:result :extra :workflow-id])
         disputed (if (some? workflow-id)
                    (apply-event context (:world created) (:transcript created)
                                 {:seq 1 :time block-time :agent from :action "raise-dispute"
                                  :params {:workflow-id workflow-id}})
                    created)]
     (assoc disputed :context context :workflow-id workflow-id))))

(defn held-observation
  "Collect non-asserting custody, replay, and invariant observations.
   `world-before` and `transcript` are supplied by public fixture builders."
  [{:keys [world world-before transcript context]}]
  (let [adjustments (:held-adjustments world [])
        replay (try {:result (custody/replay-held-adjustment-state adjustments)}
                    (catch Exception e {:error (ex-data e)}))
        closed-form (try {:checks (custody/held-custody-closed-form-checks
                                   (vals (:held-artifacts world {})))}
                         (catch Exception e {:error (ex-data e)}))]
    {:world/before world-before
     :world/after world
     :actions/transcript transcript
     :governance/provenance (some-> world :force-authorisations vals first :authorization/provenance)
     :authorization/assurance (some-> world :force-authorisations vals first
                                       :authorization/provenance :authorization/assurance)
     :held/adjustments adjustments
     :held/artifacts (:held-artifacts world {})
     :held/index (:held-ledger/index world)
     :held/total (:total-held world)
     :held/positions (:held/positions world)
     :force/authorisations (:force-authorisations world {})
     :force/consumed (:force-authorisations/consumed world {})
     :force/consumption-records (:force-authorisations/consumption-records world {})
     :replay replay
     :custody/closed-form closed-form
     :invariants (invariants/check-all world)
     :test/context context}))
