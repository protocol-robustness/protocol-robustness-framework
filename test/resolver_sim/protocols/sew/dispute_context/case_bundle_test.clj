(ns resolver-sim.protocols.sew.dispute-context.case-bundle-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.protocols.sew.dispute-context.case-bundle :as bundle])
  (:import [java.nio.file Files]))

(defn- root [value]
  (ref/sha256-ref (canonical/domain-hash :researcher-position value)))

(defn- fixture []
  (let [basis (root :basis-a)
        round (root :round-a)
        workflow "workflow-a"
        research-history [{:workflow/id workflow :round/id "round-a" :basis/root basis
                           :position :dissent :signature/status :unverified}]]
    (bundle/build-bundle
     {:case/alias "dispute-0042"
      :case/identity {:case/id "dispute-0042" :workflow/id workflow}
      :case/protocol-history
      [{:workflow/id workflow :round/id "round-a" :round/root round
        :predecessor/root nil :basis/root basis :ruling/status :model-only
        :economic-effects/status :unverified}]
      :case/appeal-bond-world
      {:appeal-bond/records
       {"bond-a" {:bond/id "bond-a" :workflow/id workflow :dispute/id "dispute-0042"
                  :appeal/ordinal 0 :asset :USDC :bond/posted-amount 100
                  :bond/posting-fee 5 :bond/custodied-amount 95
                  :bond/payer "appellant" :bond/funder "appellant"
                  :posting-operation/id "post-bond-a" :bond/status :open
                  :bond/remaining-custody 95}}}
      :case/research-history research-history
      :case/research-history-anchor (bundle/research-history-root research-history)
      :case/incentive-state {:eligibility :unverified :obligation :unverified
                             :funding :unverified :allocation :unverified
                             :payment :unpaid}
      :case/incentive-links {:policy/root (root :policy)
                             :eligibility/root (root :eligibility)
                             :obligation/root (root :obligation)}})))

(deftest case-bundle-round-trips-through-immutable-cas
  (let [store (cas/create-store (.toString (Files/createTempDirectory "prf-case-bundle" (make-array java.nio.file.attribute.FileAttribute 0))))
        artifact (fixture)
        stored (bundle/persist-bundle! store artifact)
        resolved (bundle/resolve-bundle store (:bundle/root artifact))
        view (bundle/case-view (:bundle resolved))
        wrong-key (root :wrong-cas-key)]
    (is (= (:bundle/root artifact) (:hash stored)))
    (is (:valid? resolved))
    (is (= "dispute-0042" (:case/alias view)))
    (is (= :unverified (get-in view [:verification :verification/live-execution])))
    (is (= :unverified (get-in view [:verification :verification/frames])))
    (is (= :verified (get-in view [:verification :verification/bonds])))
    (is (= :unverified (get-in view [:verification :verification/bond-disposition])))
    (is (= 100 (get-in view [:case/appeal-bonds :appeal-bond/records "bond-a"
                             :bond/posted-amount])))
    (is (= :partial (get-in view [:verification :verification/completeness])))
    (is (= :linked-unverified (get-in view [:verification :verification/incentives])))
    (is (= :unpaid (get-in view [:incentive/state :payment])))
    (cas/put-if-absent! store {:hash-reference wrong-key :artifact artifact
                               :verify #(-> (bundle/verify-bundle %) :valid?)})
    (is (false? (:valid? (bundle/resolve-bundle store wrong-key))))))

(deftest case-bundle-rejects-altered-and-cross-case-history
  (let [artifact (fixture)
        second-basis (root :different-basis)
        wrong-case (assoc-in artifact [:case/research-history 0 :basis/root] second-basis)
        tampered (assoc-in artifact [:case/protocol-history 0 :basis/root] second-basis)
        omitted-dissent (assoc-in artifact [:case/research-history] [])
        swapped-alias (assoc artifact :case/alias "dispute-9999")
        wrong-case-bond (assoc-in artifact
                                  [:case/appeal-bond-world :appeal-bond/records "bond-a" :workflow/id]
                                  "workflow-z")
        forged-payment (assoc-in artifact [:case/incentive-state :payment] :paid)
        forged-pointer (assoc artifact :case/current-pointer
                              {:round/id "round-x" :round/root (root :round-x)
                               :basis/root (root :basis-x)})]
    (is (:valid? (bundle/verify-bundle artifact)))
    (is (false? (:valid? (bundle/verify-bundle wrong-case))))
    (is (false? (:valid? (bundle/verify-bundle tampered))))
    (is (false? (:valid? (bundle/verify-bundle omitted-dissent))))
    (is (false? (:valid? (bundle/verify-bundle swapped-alias))))
    (is (false? (:valid? (bundle/verify-bundle wrong-case-bond))))
    (is (false? (:valid? (bundle/verify-bundle forged-payment))))
    (is (false? (:valid? (bundle/verify-bundle forged-pointer))))))
