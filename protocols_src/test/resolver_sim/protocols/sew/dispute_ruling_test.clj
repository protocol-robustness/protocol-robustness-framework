(ns resolver-sim.protocols.sew.dispute-ruling-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.dispute-ruling :as ruling]))

(defn- root [x]
  (str "sha256:" (hc/domain-hash :evidence-record {:x x})))

(defn- input []
  {:ruling/dispute-root (root :dispute)
   :ruling/authority-root (root :authority)
   :ruling/provider :kleros
   :ruling/external-dispute-id "42"
   :ruling/external-ruling-id "7"
   :ruling/round 1
   :ruling/outcome :release
   :ruling/status :final
   :ruling/evidence-root (root :evidence)
   :ruling/policy-root (root :policy)
   :ruling/issued-at 1000
   :ruling/authentication-root (root :authentication)})

(deftest ruling-is-closed-rooted-and-outcome-bound
  (let [artifact (ruling/build-dispute-ruling (input))]
    (is (ruling/valid-dispute-ruling? artifact))
    (is (not= (:ruling/root artifact)
              (:ruling/root (ruling/build-dispute-ruling
                             (assoc (input) :ruling/outcome :refund)))))
    (is (not (ruling/valid-dispute-ruling?
              (assoc artifact :ruling/outcome :refund))))
    (is (not (ruling/valid-dispute-ruling?
              (assoc artifact :ruling/freeform "uncommitted"))))))
