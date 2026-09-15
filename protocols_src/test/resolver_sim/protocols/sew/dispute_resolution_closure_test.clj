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
  {:terminal-reservation/hash terminal-hash
   :public-authorisation/id "terminal-publisher"
   :public-authorisation/scope-hash (root :scope)
   :consumption/effect-outcome :not-produced
   :published/objects {:staged-capture/hash (root :staged)
                       :terminal-snapshot/hash (root :snapshot)
                       :outcome-manifest/hash (root :manifest)
                       :consumption-receipt/hash (root :receipt)
                       :execution-evidence/hash (root :evidence)}})

(defn- terminal-publication-v1 []
  (publication/build-publication (terminal-publication)))

(defn- terminal-publication-v2 [binding]
  (publication/build-publication-v2 (terminal-publication) binding))

(deftest authenticated-terminal-publication-requires-the-external-closure-binding
  (let [binding (envelope/build-envelope terminal-hash closure)
        registry (atom {})
        published (terminal-publication-v2 binding)]
    (is (:published? (publication/publish-authenticated-terminal!
                      registry terminal-hash closure binding published)))
    (testing "V1 remains valid but cannot publish an authenticated terminal"
      (is (publication/valid-publication? (terminal-publication-v1)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closure envelope"
                            (publication/publish-authenticated-terminal!
                             (atom {}) terminal-hash closure binding
                             (terminal-publication-v1)))))
    (testing "envelope and closure substitution fail before publication"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closure envelope"
                            (publication/publish-authenticated-terminal!
                             (atom {}) terminal-hash {:closure/root (root :other)} binding published)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closure envelope"
                            (publication/publish-authenticated-terminal!
                             (atom {}) terminal-hash closure nil published))))))

(deftest v2-publication-commits-the-terminal-envelope
  (let [binding (envelope/build-envelope terminal-hash closure)
        publication (terminal-publication-v2 binding)
        other-envelope (envelope/build-envelope terminal-hash {:closure/root (root :other)})]
    (is (= (:terminal-envelope/hash binding) (:terminal-envelope/hash publication)))
    (is (publication/valid-publication? publication))
    (is (some #{:terminal-envelope-mismatch}
              (:errors (publication/validate-publication-against-terminal
                        publication other-envelope closure nil))))
    (is (some #{:dispute-resolution-closure-mismatch}
              (:errors (publication/validate-publication-against-terminal
                        publication binding {:closure/root (root :other)} nil))))))

(deftest terminal-reservation-mapping-supports-cold-artifact-resolution
  (let [binding (envelope/build-envelope terminal-hash closure)
        published (terminal-publication-v2 binding)
        artifacts {(:publication/hash published) published
                   (:terminal-envelope/hash binding) binding
                   (:dispute-resolution/closure-root binding) closure}
        registry (atom {})]
    (publication/publish-terminal! registry terminal-hash published)
    (let [resolved (publication/resolve-published-terminal registry terminal-hash artifacts)]
      (is (= (:publication/hash published) (:publication-hash resolved)))
      (is (= published (:publication resolved)))
      (is (= binding (:terminal-envelope resolved)))
      (is (= closure (:dispute-closure resolved))))))
