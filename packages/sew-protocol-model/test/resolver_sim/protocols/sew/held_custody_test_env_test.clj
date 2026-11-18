(ns resolver-sim.protocols.sew.held-custody-test-env-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.protocols.sew.held-custody-test-env :as env]
            [resolver-sim.protocols.sew.types :as types]))

(defn- profile-input
  [consensus-validation]
  (let [snapshot-root (env/parameter-root {:fixture :snapshot})]
    {:protocol {:protocol/id :sew}
     :snapshot-id :sew/test-snapshot-v1
     :snapshot-root snapshot-root
     :construction {:world/mode :public-actions
                    :action/entrypoint :resolver-sim.protocols.sew/apply-action
                    :deterministic? true}
     :governance {:governance/mode :restricted
                  :governance/identity "0xGov"
                  :authorization/assurance :address-bound}
     :consensus {:consensus/type :researcher-force-authorisation
                 :policy/root (env/parameter-root {:fixture :policy})
                 :review-round/root (env/parameter-root {:fixture :round})
                 :membership/root (env/parameter-root {:fixture :membership})
                 :threshold 2
                 :signature/scheme :ed25519}
     :custody {:primitive :held-adjustment :ledger/origin :zero
               :ledger/complete? true :artifact/schemas [2 3]}
     :parameter-attribution {:verification :structural
                             :resolution :not-modelled
                             :value-check :not-modelled
                             :amount-check :not-modelled}
     :checks [:public-action :replay :custody-artifact]
     :limitations [:parameter-resolution-not-modelled]
     :consensus-validation consensus-validation}))

(deftest test-environment-profile-derives-instead-of-trusting-assurance
  (let [incomplete (env/build-test-environment-profile (profile-input {}))
        complete-checks (zipmap env/consensus-check-keys (repeat true))
        consensus (env/build-test-environment-profile (profile-input complete-checks))]
    (is (= :public-address-bound (:profile/trust-level incomplete)))
    (is (= :public-address-bound (:profile/trust-level consensus))
        "a profile cannot claim terminal consensus assurance")
    (is (= (:profile/hash consensus) (:environment/hash consensus)))
    (is (:valid? (env/validate-test-environment-profile incomplete)))
    (is (:valid? (env/validate-test-environment-profile consensus)))
    (is (not (:valid? (env/validate-test-environment-profile
                       (assoc consensus :profile/trust-level :consensus-authenticated-public)
                       :consensus-validation {}))))))

(deftest terminal-run-envelope-derives-aggregate-assurance
  (let [root (env/parameter-root {:fixture :root})
        pending (env/build-test-environment-envelope
                 {:environment-hash root :run-id :run/pending
                  :lifecycle :consensus-grant-reserved})
        terminal (env/build-test-environment-envelope
                  {:environment-hash root :run-id :run/terminal
                   :lifecycle :terminal-verified})]
    (is (= :public-address-bound (:run/assurance pending)))
    (is (= :public-address-bound (:run/assurance terminal))
        "structural builders never authenticate terminal evidence")
    (is (= :researcher-threshold-authenticated (:consensus/assurance terminal)))
    (is (hash-ref/valid-sha256-ref? (:run/hash terminal)))
    (is (not= (:run/hash pending) (:run/hash terminal)))))

(deftest environment-upgrades-are-new-hash-bound-profiles
  (let [base (env/build-test-environment-profile
              (assoc (profile-input {})
                     :capabilities #{:public-sew-actions}
                     :limitations #{:terminal-consumption-not-modelled}))
        upgraded (env/build-test-environment-profile
                  (assoc (profile-input {})
                         :capabilities #{:public-sew-actions :terminal-force-authorisation-receipt}
                         :limitations #{}
                         :supersedes {:profile/id (:profile/id base)
                                      :environment/hash (:environment/hash base)}
                         :upgrade {:upgrade/kind :capability-extension
                                   :upgrade/reason :terminal-receipts}))
        contradictory (assoc base :environment/capabilities
                            #{:terminal-force-authorisation-receipt})]
    (is (not= (:environment/hash base) (:environment/hash upgraded)))
    (is (= (:environment/hash base)
           (get-in upgraded [:environment/supersedes :environment/hash])))
    (is (:valid? (env/validate-test-environment-profile upgraded)))
    (is (contains? (set (:errors (env/validate-test-environment-profile contradictory)))
                   :environment-hash-mismatch)
        "any in-place capability mutation is hash-invalid")))

(deftest envelope-cannot-switch-environments-or-overclaim-terminal-state
  (let [root-a (env/parameter-root {:fixture :environment-a})
        root-b (env/parameter-root {:fixture :environment-b})
        first (env/build-test-environment-envelope
               {:environment-hash root-a :run-id :run/one :lifecycle :initialized})
        next (env/build-test-environment-envelope
              {:environment-hash root-a :run-id :run/one
               :previous-envelope-hash (:run/hash first)
               :transition :reserve :lifecycle :consensus-grant-reserved})
        switched (assoc next :environment/hash root-b)]
    (is (:valid? (env/validate-test-environment-envelope next :previous-envelope first)))
    (is (contains? (set (:errors (env/validate-test-environment-envelope
                                      switched :previous-envelope first)))
                   :environment-switch-during-run))
    (is (contains? (set (:errors (env/validate-test-environment-envelope
                                      (assoc next :run/assurance :consensus-authenticated-public)
                                      :previous-envelope first)))
                   :unverified-consensus-assurance))))

(deftest parameter-fixtures-produce-authentic-canonical-roots
  (let [fixture (env/parameter-fixture)
        root (env/parameter-root fixture)]
    (is (hash-ref/valid-sha256-ref? root))
    (is (= root (get (env/authoritative-parameter-context fixture)
                     :parameter-context/root)))
    (is (not= root (env/parameter-root
                    (env/parameter-fixture {:parameters {:sew/escrow-principal
                                                          {:unit :USDC :variant :changed}}}))))))

(deftest public-disputed-world-retains-an-authentic-action-transcript
  (let [{:keys [result world transcript workflow-id context] :as fixture}
        (env/public-disputed-world)
        observation (env/held-observation {:world world
                                            :world-before (env/public-empty-held-world)
                                            :transcript transcript
                                            :context context})]
    (is (:ok result))
    (is (= 0 workflow-id))
    (is (= :disputed (types/escrow-state world workflow-id)))
    (is (= ["create-escrow" "raise-dispute"]
           (mapv #(get-in % [:event :action]) transcript)))
    (is (every? :ok? transcript))
    (is (= world (:world/after observation)))
    (is (= (:held-adjustments world) (:held/adjustments observation)))
    (is (contains? observation :invariants))))
