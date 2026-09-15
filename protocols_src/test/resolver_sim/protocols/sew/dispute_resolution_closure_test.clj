(ns resolver-sim.protocols.sew.dispute-resolution-closure-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.protocols.sew.dispute-resolution-terminal-envelope :as envelope]
            [resolver-sim.protocols.sew.terminal-evidence-publication :as publication]))

(defn- root [label]
  (str "sha256:" (hc/domain-hash :evidence-record {:label label :padding "0123456789abcdef0123456789abcdef"})))

(def terminal-hash (root :terminal))
(def closure {:closure/root (root :closure)})

(defn- terminal-publication []
  (publication/build-publication
   {:terminal-reservation/hash terminal-hash
    :public-authorisation/id "terminal-publisher"
    :public-authorisation/scope-hash (root :scope)
    :consumption/effect-outcome :not-produced
    :published/objects {:staged-capture/hash (root :staged)
                        :terminal-snapshot/hash (root :snapshot)
                        :outcome-manifest/hash (root :manifest)
                        :consumption-receipt/hash (root :receipt)
                        :execution-evidence/hash (root :evidence)}}))

(deftest authenticated-terminal-publication-requires-the-external-closure-binding
  (let [binding (envelope/build-envelope terminal-hash closure)
        registry (atom {})
        published (terminal-publication)]
    (is (:published? (publication/publish-authenticated-terminal!
                      registry terminal-hash closure binding published)))
    (testing "closure substitution and missing envelopes fail before V1 publication"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closure envelope"
                            (publication/publish-authenticated-terminal!
                             (atom {}) terminal-hash {:closure/root (root :other)} binding published)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closure envelope"
                            (publication/publish-authenticated-terminal!
                             (atom {}) terminal-hash closure nil published))))))
