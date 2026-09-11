(ns resolver-sim.benchmark.governed-authority-derived-finalization-test
  "AUTH-STATE-AFTER-FINALIZATION — versioned K2 → derived-state bridge.

   Proves the derived finalizer derives the exact successor from a retained
   report (never caller-supplied successor), commits a
   governed-authority-derived-result-receipt.v1, and that high-assurance
   admission rejects the legacy receipt kind (LEGACY-NON-UPGRADE)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.governed-authority-derived-finalization :as d]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.configuration-head :as config-head]
            [resolver-sim.genesis :as genesis]))

(defn- hr [ch] (str "sha256:" (apply str (take 64 (cycle ch)))))
(def ^:private genesis-ref (hr "11"))

(defrecord TestStore [state])

(defn- material [chain-config-root]
  (let [pk (apply str (repeat 64 "a"))
        ks {:artifact/schema state/signer-key-set-schema
            :signer-key-set/entries
            [{:researcher/id "r1" :signing-key/id "k1"
              :signing-key/algorithm :ed25519 :signing-key/public-key pk}]}
        gov {:schema-version "review-governance.v1"
             :governance/epoch 0 :governance/roles #{:reviewer}
             :governance/principals
             [{:principal/id "r1" :status :active :principal/independence-group "g1"
               :principal/independence-basis-root (hr "ab")
               :principal/keys [{:key/id "k1" :status :active :key/algorithm :ed25519 :key/public-key pk}]}]
             :governance/members
             [{:reviewer/member-id "r1" :principal/id "r1" :status :active :granted-roles #{:reviewer}}]
             :governance/policies
             [{:policy/id "p1" :member-count 3 :threshold 2 :required-roles #{:reviewer}
               :role-cardinality :unique :equivocation-policy :invalid-seat}]}
        round {:artifact/schema rr/governed-schema-version :schema-version rr/governed-schema-version
               :benchmark/content-root (hr "aa")
               :review-round/members [{:researcher/id "r1" :role :reviewer}]
               :review-round/membership-frozen-at 0
               :review-round/policy-root (hr "bb")
               :review-round/purpose :model-admission
               :review-round/chain-configuration-root chain-config-root
               :review-round/governance-root (governance/governance-root gov)
               :review-round/governance-epoch 0
               :review-round/constituted-at 0
               :review-round/policy-id "p1"
               :review-round/policy-hash (hr "dd")}]
    {:chain-instance-genesis/root genesis-ref
     :chain-configuration/root chain-config-root
     :review-governance/root (governance/governance-root gov)
     :review-governance-activation/root (hr "44")
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root (state/review-round-material-root round)
     :review-round/hash (hr "77")
     :position-time-basis/root (hr "ee")
     :position-time-index/root (hr "ff")
     :control-plane-evidence/root (hr "55")
     :review-governance-admissibility/root (hr "66")
     :authority-material/review-governance gov
     :authority-material/signer-key-set ks
     :authority-material/review-round round
     :authority-material/position-time-index {:artifact/schema "x" :entries []}}))

(defn- envelope [mat head state-root]
  (state/build-envelope-v2
   {:chain-instance-genesis/root genesis-ref
    :execution/state-root state-root
    :chain-configuration/root (:chain-configuration/root mat)
    :review-governance/root (:review-governance/root mat)
    :review-governance-activation/root (:review-governance-activation/root mat)
    :control-plane-evidence/root (:control-plane-evidence/root mat)
    :position-time-index/root (:position-time-index/root mat)
    :publication/sequence 0
    :publication/predecessor-root nil}
   head))

(deftest derived-finalization-derives-and-commits
  (let [parent-config genesis/chain-configuration-v0-fixture
        parent-root (genesis/chain-configuration-root parent-config)
        new-config genesis/chain-configuration-v1-fixture
        new-root (genesis/chain-configuration-root new-config)
        mat (material parent-root)
        head (config-head/initial-head parent-root 1)
        state-root (hr "b0")
        env (envelope mat head state-root)
        head-root (:configuration-head/root env)
        s0-root (:authoritative-state-envelope/root env)
        report {:authority-status :authorised :authoritative-target-root new-root}
        report-root (authority/authority-report-root report)
        fence-id "f1"
        fence-record {:authority-status :authorised
                      :authority-report/root report-root
                      :authority-state-envelope/root s0-root
                      :resolved-review-authority-context/root (hr "cc")
                      :execution/state-root state-root
                      :publication/sequence 0
                      :status :issued}
        store (->TestStore
               (atom {:head s0-root
                      :envelopes {s0-root env}
                      :material {state-root mat}
                      :configuration-head-states {head-root head}
                      :chain-configurations {parent-root parent-config}
                      :issued-fences {fence-id fence-record}
                      :authority-reports {report-root report}}))
        result (d/finalise-under-authority-fence-derived!
                store fence-id s0-root {:chain-configuration/body new-config})]
    (is (:finalised? result))
    (is (d/derived-receipt? (:governed-authority-derived-result-receipt result)))
    (let [receipt (:governed-authority-derived-result-receipt result)
          post (:post-authoritative-state-envelope/root receipt)
          material-root (:successor-material/root receipt)]
      (is (= post (get-in @(:state store) [:head]))
          "receipt.post-envelope/root == store head == S1 (envelope root)")
      (is (not= post material-root)
          "successor-envelope-root and successor-material-root are distinct roots")
      (is (= material-root
             (get-in @(:state store) [:envelopes post :execution/state-root]))
          "derived successor-material-root == E1.execution/state-root (M1)"))
    (is (d/high-assurance-derived-finalization? result)
        "derived finalization is high-assurance")))

(deftest derived-finalization-requires-exact-predecessor
  (let [parent-config genesis/chain-configuration-v0-fixture
        parent-root (genesis/chain-configuration-root parent-config)
        new-config genesis/chain-configuration-v1-fixture
        new-root (genesis/chain-configuration-root new-config)
        mat (material parent-root)
        head (config-head/initial-head parent-root 1)
        state-root (hr "b0")
        env (envelope mat head state-root)
        s0-root (:authoritative-state-envelope/root env)
        report {:authority-status :authorised :authoritative-target-root new-root}
        report-root (authority/authority-report-root report)
        fence-id "f1"
        fence-record {:authority-status :authorised :authority-report/root report-root
                      :authority-state-envelope/root s0-root
                      :resolved-review-authority-context/root (hr "cc")
                      :execution/state-root state-root :publication/sequence 0 :status :issued}
        store (->TestStore
               (atom {:head s0-root :envelopes {s0-root env} :material {state-root mat}
                      :configuration-head-states {(:configuration-head/root env) head}
                      :chain-configurations {parent-root parent-config}
                      :issued-fences {fence-id fence-record}
                      :authority-reports {report-root report}}))
        ;; wrong expected-predecessor → reject before mutation
        result (d/finalise-under-authority-fence-derived! store fence-id (hr "9a"))]
    (is (false? (:finalised? result)))
    (is (= :state-not-at-required-head (:reason result)))
    (is (= s0-root (get-in @(:state store) [:head])) "no mutation on stale predecessor")))

(deftest legacy-receipt-rejected-by-high-assurance-admission
  (testing "LEGACY-NON-UPGRADE: the old receipt kind cannot satisfy the
            derived-finalization requirement, even with identical S0/S1 roots"
    (let [legacy {:artifact/schema "governed-authority-result-receipt.v1"
                  :pre-authoritative-state-envelope/root (hr "a")
                  :post-authoritative-state-envelope/root (hr "b")}]
      (is (false? (d/high-assurance-derived-finalization? legacy)))
      (is (nil? (:governed-authority-derived-result-receipt legacy))))))

(deftest legacy-finalizer-rejects-derived-only-lineage-before-mutation
  (testing "AUTH-LINEAGE-CONSERVATION (high-assurance profile): a lineage
            declared derived-only cannot be mutated by the legacy caller-rooting
            finalizer, even with a valid K2 fence and a self-consistent
            caller-selected S1'. The rejection must occur before mutation."
    (let [parent-config genesis/chain-configuration-v0-fixture
          parent-root (genesis/chain-configuration-root parent-config)
          new-config genesis/chain-configuration-v1-fixture
          new-root (genesis/chain-configuration-root new-config)
          mat (material parent-root)
          head (config-head/initial-head parent-root 1)
          state-root (hr "b0")
          env (envelope mat head state-root)
          s0-root (:authoritative-state-envelope/root env)
          report {:authority-status :authorised :authoritative-target-root new-root}
          report-root (authority/authority-report-root report)
          fence-id "f1"
          fence-record {:authority-status :authorised :authority-report/root report-root
                        :authority-state-envelope/root s0-root
                        :resolved-review-authority-context/root (hr "cc")
                        :execution/state-root state-root :publication/sequence 0 :status :issued}
          store (->TestStore
                 (atom {:head s0-root :envelopes {s0-root env} :material {state-root mat}
                        :configuration-head-states {(:configuration-head/root env) head}
                        :chain-configurations {parent-root parent-config}
                        :issued-fences {fence-id fence-record}
                        :authority-reports {report-root report}}))
          ;; a caller-selected successor envelope whose roots are internally
          ;; self-consistent (sequence/prod link), but NOT the derived successor
          arbitrary-env (envelope mat head (hr "de"))
          arbitrary-material (material parent-root)
          ;; legacy binding over arbitrary state-after (caller-rooted)
          binding (-> {:transaction/state-before-root state-root
                       :transaction/state-after-root (:execution/state-root arbitrary-env)
                       :resolved-review-authority-context/root (hr "cc")
                       :publication/predecessor-root s0-root}
                      (assoc :governed-authority-transition-binding/root (hr "ee")))]
      ;; declare the lineage high-assurance / derived-only
      (state/declare-derived-only-lineage! store)
      (is (state/derived-only-lineage? store))
      (let [result (state/finalise-under-authority-fence!
                    store {:fence/id fence-id} binding arbitrary-env arbitrary-material)]
        (is (false? (:finalised? result)))
        (is (= :derived-only-lineage-rejects-legacy-finalization (:reason result)))
        (is (= s0-root (get-in @(:state store) [:head]))
            "head unchanged: legacy finalization rejected BEFORE mutation")))))