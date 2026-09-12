(ns resolver-sim.protocols.sew.dispute-ruling-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.dispute-ruling :as ruling]))

(defn- root [x]
  (str "sha256:" (hc/domain-hash :evidence-record {:x x})))

(defn- input []
  {:ruling/basis-root (root :basis)
     :ruling/dispute-root (root :dispute)
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

(deftest ruling-is-closed-rooted-domain-separated-and-field-bound
  (let [artifact (ruling/build-dispute-ruling (input))
        payload (ruling/ruling-payload artifact)
        old-domain-root (str "sha256:" (hc/domain-hash :sew-resolution-decision-v1
                                                        (hc/project-canonical-safe payload)))]
    (is (ruling/valid-dispute-ruling? artifact))
    (is (= (:ruling/root artifact)
           (:ruling/root (ruling/build-dispute-ruling (input))))
        "same payload deterministically reconstructs the same root")
    (doseq [field [:ruling/basis-root :ruling/dispute-root :ruling/authority-root
                   :ruling/provider :ruling/external-dispute-id :ruling/external-ruling-id
                   :ruling/round :ruling/outcome :ruling/status :ruling/evidence-root
                   :ruling/policy-root :ruling/issued-at :ruling/authentication-root]]
      (is (not (ruling/valid-dispute-ruling?
                (assoc artifact field (if (= field :ruling/outcome) :refund "mutated"))))
          (str field " is committed")))
    (is (not (ruling/valid-dispute-ruling? (assoc artifact :ruling/root old-domain-root)))
        "the old sew decision domain cannot validate a ruling")
    (is (not (ruling/valid-dispute-ruling?
              (assoc artifact :ruling/freeform "uncommitted"))))))
