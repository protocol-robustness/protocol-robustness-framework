(ns resolver-sim.pro-rata.effect-compilation-v3-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.pro-rata.effect-compilation-binding-v2 :as binding]
            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.effect-compilation-v3 :as compilation]
            [resolver-sim.pro-rata.target-map :as target-map])
  (:import [java.nio.file Files]))

(defn- root [tag] (str "sha256:" (hc/domain-hash :registry tag)))

(defn- fixture []
  (let [allocation (allocation/allocate {:allocation/id :test/allocation
                                         :available 10
                                         :rows [{:row/id :claim/alice :requested 10 :weight 1}]})
        targets (target-map/build-target-map
                 {:allocation-subjects-root (root :subjects) :scope-root (root :scope)
                  :mapping-profile-root (root :profile)
                  :targets [{:allocation/subject-id :allocation/liquidity :mapping/role :available :quantity/root (root :liquidity)}
                            {:allocation/subject-id :claim/alice :mapping/role :filled :quantity/root (root :filled)}
                            {:allocation/subject-id :claim/alice :mapping/role :outstanding :quantity/root (root :outstanding)}]})
        semantics (semantics/build :all-active)
        artifact (compilation/compile {:allocation allocation :target-map targets :semantics semantics
                                       :allocation-policy-root (root :policy)})
        raw-effects (effects/compile-pro-rata-effects allocation
                                                      {:liquidity/root (root :liquidity)
                                                       :claim/alice {:filled/root (root :filled)
                                                                     :outstanding/root (root :outstanding)}})
        canonical (effects/transition {(root :liquidity) 10 (root :outstanding) 10} raw-effects)
        bodies {(:allocation/hash allocation) allocation
                (:target-map/root targets) targets
                (:effect-compilation-semantics/root semantics) semantics}]
    {:artifact artifact :canonical canonical :bodies bodies}))

(deftest v3-requires-closed-verified-bodies-and-exact-transition
  (let [{:keys [artifact canonical bodies]} (fixture)
        resolve-body #(get bodies %)
        contract (binding/build artifact canonical)]
    (is (= "pro-rata-effect-compilation.v3" (:schema-version artifact)))
    (is (= artifact (binding/recompute artifact resolve-body)))
    (is (binding/valid? contract artifact canonical resolve-body))
    (is (not (binding/valid? contract artifact canonical (constantly nil))))
    (is (not (binding/valid? contract artifact
                             (assoc canonical :effects/root (root :forged)) resolve-body)))
    (is (not (semantics/valid? (assoc (get bodies (:effect-compilation-semantics/root artifact))
                                      :unknown true))))))

(deftest v3-recompilation-is-runtime-orthogonal
  (let [{:keys [artifact canonical bodies]} (fixture)
        allocation (get bodies (:realized-allocation/root artifact))
        target-map (get bodies (:target-map/root artifact))
        compiler-semantics (get bodies (:effect-compilation-semantics/root artifact))
        runtime-options {:parallelism 32
                         :worker-pool {:claimants 8}
                         :execution/claimant-parallelism 4
                         :execution/claimant-parallel-threshold 2
                         :execution/quiescence-timeout-seconds 99
                         :worker-scheduling :randomized}
        with-runtime (compilation/compile (merge runtime-options
                                                 {:allocation allocation
                                                  :target-map target-map
                                                  :semantics compiler-semantics
                                                  :allocation-policy-root (root :policy)}))]
    (let [canonical (:canonical (fixture))
          base-binding (binding/build artifact canonical)
          runtime-binding (binding/build with-runtime canonical)]
      (is (= artifact with-runtime))
      (is (= (:effect-compilation/root artifact)
             (:effect-compilation/root with-runtime)))
      (is (= (:effect-compilation-binding/root base-binding)
             (:effect-compilation-binding/root runtime-binding))))))

(deftest v2-binding-rejects-missing-and-substituted-semantic-bodies
  (let [{:keys [artifact canonical bodies]} (fixture)
        contract (binding/build artifact canonical)
        semantics-root (:effect-compilation-semantics/root artifact)
        target-root (:target-map/root artifact)
        allocation-root (:realized-allocation/root artifact)]
    (is (not (binding/valid? contract artifact canonical (fn [_] (dissoc bodies semantics-root)))))
    (is (not (binding/valid? contract artifact canonical (fn [_] (dissoc bodies target-root)))))
    (is (not (binding/valid? contract artifact canonical (fn [_] (dissoc bodies allocation-root)))))
    (is (not (binding/valid? contract artifact canonical
                             (fn [root]
                               (if (= root semantics-root)
                                 (assoc (get bodies root) :effect-compilation/profile :aggregate-held-credit)
                                 (get bodies root))))))))

(deftest v2-recompilation-survives-fresh-cas-instance
  (let [{:keys [artifact canonical bodies]} (fixture)
        contract (binding/build artifact canonical)
        root->body (merge bodies
                          {(:effect-compilation/root artifact) artifact
                           (:effect-compilation-binding/root contract) contract
                           (:canonical-effect-transition/root canonical) canonical})
        directory (Files/createTempDirectory "resolver-sim-v2-restart-"
                                             (make-array java.nio.file.attribute.FileAttribute 0))
        store (cas/create-store directory)
        _ (doseq [[root body] root->body]
            (let [qualified (ref/sha256-ref root)
                  result (cas/put-if-absent! store {:hash-reference qualified
                                                    :artifact body
                                                    :verify #(= body %)})]
              (is (:crash-durable? result))))
        reopened (cas/create-store directory)
        resolve-after-restart
        (fn [root]
          (let [qualified (ref/sha256-ref root)]
            (cas/resolve-durable-artifact reopened qualified
                                          #(= (get root->body root) %))))]
    (is (binding/valid? contract artifact canonical resolve-after-restart))
    (is (= artifact (binding/recompute artifact resolve-after-restart)))))

(deftest v2-binding-and-receipt-fail-closed-on-resolver-substitution
  (let [{:keys [artifact canonical bodies]} (fixture)
        resolve-body #(get bodies %)
        contract (binding/build artifact canonical)
        receipt-base {:schema-version application/receipt-v2-schema
                      :authorization/root (root :authorization)
                      :protocol-effect-set/root (:effects/root artifact)
                      :executed-effect-set/root (:effects/root artifact)
                      :applied-adjustment-refinement/root (root :refinement)
                      :applied-adjustments/root (root :adjustments)
                      :state-before/root (:state-before/root canonical)
                      :state-after/root (:state-after/root canonical)
                      :ledger-before/root (root :ledger-before)
                      :ledger-after/root (root :ledger-after)
                      :application/status :applied
                      :effect-compilation-binding/root (:effect-compilation-binding/root contract)}
        receipt (assoc receipt-base :applied-effect-receipt/root
                       (hc/domain-hash :applied-effect-receipt receipt-base))]
    (is (application/receipt-valid? receipt artifact canonical resolve-body))
    (is (not (application/receipt-valid? receipt artifact canonical))
        "V2 receipt does not downgrade to root-only or V1 validation")
    (is (not (binding/valid? (assoc contract :unknown true) artifact canonical resolve-body))
        "binding V2 is closed")
    (is (not (binding/valid? contract artifact canonical
                             #(if (= % (:target-map/root artifact))
                                (assoc (get bodies %) :targets [])
                                (get bodies %))))
        "a resolver cannot substitute a body under a retained root")
    (is (not (application/receipt-valid? receipt artifact canonical (constantly nil)))
        "unavailable retained bodies invalidate historical semantic recompilation")))
