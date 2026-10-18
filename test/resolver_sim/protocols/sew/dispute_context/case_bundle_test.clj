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
      :case/research-history research-history
      :case/research-history-anchor (bundle/research-history-root research-history)
      :case/incentive-links {:policy/root (root :policy)
                             :eligibility/root (root :eligibility)
                             :obligation/root (root :obligation)}})))

(deftest case-bundle-round-trips-through-immutable-cas
  (let [store (cas/create-store (.toString (Files/createTempDirectory "prf-case-bundle" (make-array java.nio.file.attribute.FileAttribute 0))))
        artifact (fixture)
        stored (bundle/persist-bundle! store artifact)
        resolved (bundle/resolve-bundle store (:bundle/root artifact))
        view (bundle/case-view (:bundle resolved))]
    (is (= (:bundle/root artifact) (:hash stored)))
    (is (:valid? resolved))
    (is (= "dispute-0042" (:case/alias view)))
    (is (= :unverified (get-in view [:verification :verification/live-execution])))
    (is (= :unverified (get-in view [:verification :verification/frames])))
    (is (= :linked-unverified (get-in view [:verification :verification/incentives])))))

(deftest case-bundle-rejects-altered-and-cross-case-history
  (let [artifact (fixture)
        second-basis (root :different-basis)
        wrong-case (assoc-in artifact [:case/research-history 0 :basis/root] second-basis)
        tampered (assoc-in artifact [:case/protocol-history 0 :basis/root] second-basis)
        omitted-dissent (assoc-in artifact [:case/research-history] [])
        swapped-alias (assoc artifact :case/alias "dispute-9999")
        forged-pointer (assoc artifact :case/current-pointer
                              {:round/id "round-x" :round/root (root :round-x)
                               :basis/root (root :basis-x)})]
    (is (:valid? (bundle/verify-bundle artifact)))
    (is (false? (:valid? (bundle/verify-bundle wrong-case))))
    (is (false? (:valid? (bundle/verify-bundle tampered))))
    (is (false? (:valid? (bundle/verify-bundle omitted-dissent))))
    (is (false? (:valid? (bundle/verify-bundle swapped-alias))))
    (is (false? (:valid? (bundle/verify-bundle forged-pointer))))))
