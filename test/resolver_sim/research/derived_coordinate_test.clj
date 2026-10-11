(ns resolver-sim.research.derived-coordinate-test
  (:refer-clojure :exclude [derive])
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.derived-coordinate :as derived]
            [resolver-sim.research.evidence-attribution :as attribution]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.interaction-reproduction-test :as fixture]
            [resolver-sim.benchmark.research-definition :as definition]
            [resolver-sim.benchmark.researcher-axis :as researcher]
            [resolver-sim.research.coordinate-interaction :as interaction]
            [resolver-sim.evidence.forensic-graph :as forensic]
            [resolver-sim.research.semantic-accounting-test :as accounting]))

(defn derive [args]
  (derived/derive-interaction (:x-axis args) (:y-axis args) (:levels args) (:observations args)))

(deftest wrapper-preserves-existing-semantic-result-and-links-reproduction
  (let [{:keys [input implementation original]} (fixture/fixture)
        args (:computation/input input)
        result (derive args)
        second (reproduction/contrast-implementation
                (:resolved-research-dependency-set/root implementation) "1.0.0")
        execution (reproduction/execute input second)
        evidence (reproduction/compare-execution execution original)]
    (is (= (:research-coordinate-interaction/root original) (:derived/result-root result)))
    (is (= 8 (:derived/value result)))
    (is (= result (derive args)))
    (is (map? (derived/evidence-node result (:x-axis args) (:y-axis args)
                                     (:levels args) (:observations args))))
    (is (map? (derived/link-reproduction result input execution original evidence)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derived/link-reproduction (assoc result :derived/value 0)
                                            input execution original evidence)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derived/link-reproduction result input {:evidence/mode :source-projection}
                                            original evidence)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (derived/link-reproduction result input execution
                                            (assoc original :research-coordinate-interaction/root
                                                   (accounting/fake-root \f)) evidence)))))

(deftest operator-permissions-are-explicit-not-inferred-from-integers
  (let [args (:computation/input (:input (fixture/fixture)))]
    (doseq [kind [:researcher :implementation :runner]]
      ;; Even a self-rooted integer/eligible axis cannot bypass the prohibition.
      (let [axis (assoc (:x-axis args) :axis/kind kind)
            axis (assoc axis :research-axis-semantics/root (interaction/axis-semantics-root axis))]
        (is (thrown? clojure.lang.ExceptionInfo (derive (assoc args :x-axis axis))))))
    (let [missing (-> args
                      (assoc-in [:observations :x1-y1 :observation/status] :missing)
                      (assoc-in [:observations :x1-y1 :observation/value] nil)
                      (assoc-in [:observations :x1-y1 :observation/reason] :absent))
          incompatible (assoc-in args [:observations :x1-y1 :measure/id] :measure/other)]
      (is (= :missing (:derived/status (derive missing))))
      (is (nil? (:derived/value (derive missing))))
      (is (= :not-comparable (:derived/status (derive incompatible))))
      (is (= :incompatible-outcome-measures (:derived/reason (derive incompatible)))))
    (is (not= (:coordinate-derived-result/root (derive args))
              (:coordinate-derived-result/root
               (derive (assoc-in args [:observations :x1-y1 :observation/root]
                                 (accounting/fake-root \f))))))))

(deftest diff-families-remain-distinct
  (doseq [[schema family] [["research-definition-diff.v1" :structural-diff]
                           ["research-analysis-comparison.v1" :semantic-comparison]
                           ["research-coordinate-interaction.v1" :numeric-differential]
                           ["reproduction-evidence.v1" :reproduction-evidence]]]
    (is (= family (derived/relation-family {:artifact/schema schema}))))
  (is (thrown? clojure.lang.ExceptionInfo (derived/relation-family {:artifact/schema "unknown"}))))

(defn nominal-axis []
  ;; Projection fixture only; it does not fabricate authenticated authority.
  (let [body {:artifact/schema definition/axis-schema :axis/kind :researcher
              :axis/members [{:axis/key 0 :axis/id "experiment-pseudonym"
                              :axis/member {:researcher/id "experiment-pseudonym"}}]}]
    (assoc body :axis/root (definition/axis-root body))))

(deftest anonymous-attribution-is-nominal-and-does-not-change-semantic-results
  (let [axis (nominal-axis)
        coordinate (researcher/researcher-coordinate axis "experiment-pseudonym")
        {:keys [original]} (fixture/fixture)
        declaration {:subject {:subject/kind :research/interaction
                               :subject/root (:research-coordinate-interaction/root original)}
                     :evidence-root (accounting/fake-root \a)
                     :relation :reproduced :disclosure :anonymous}
        anonymous (attribution/project-attribution axis coordinate declaration)
        named (attribution/project-attribution axis coordinate (assoc declaration :disclosure :identified))]
    (is (= :declared (:attribution/epistemic-role anonymous)))
    (is (= coordinate (:attribution/coordinate anonymous)))
    (is (not= (:researcher-evidence-attribution/root anonymous)
              (:researcher-evidence-attribution/root named)))
    (is (= (:attribution/subject anonymous) (:attribution/subject named)))
    (is (not (contains? anonymous :real-world-identity)))
    (is (map? (attribution/evidence-node anonymous axis)))
    (doseq [bad [(assoc coordinate :key 1)
                 (assoc coordinate :scope/root (accounting/fake-root \f))]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (attribution/project-attribution axis bad declaration))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (attribution/validate-attribution (assoc anonymous :attribution/relation :produced) axis)))
    (is (not= (:researcher-evidence-attribution/root anonymous)
              (:researcher-evidence-attribution/root
               (attribution/project-attribution axis coordinate
                                                (assoc declaration :evidence-root (accounting/fake-root \b))))))))

(deftest projections-use-the-generic-forensic-dag
  (let [{:keys [input original]} (fixture/fixture)
        args (:computation/input input)
        result (derive args)
        axis (nominal-axis)
        coordinate (researcher/researcher-coordinate axis "experiment-pseudonym")
        attributed (attribution/project-attribution
                    axis coordinate
                    {:subject {:subject/kind :research/interaction
                               :subject/root (:research-coordinate-interaction/root original)}
                     :evidence-root (:coordinate-derived-result/root result)
                     :relation :produced :disclosure :anonymous})
        nodes [(derived/evidence-node result (:x-axis args) (:y-axis args)
                                      (:levels args) (:observations args))
               (attribution/evidence-node attributed axis)]
        roots (vec (distinct (mapcat #(get-in % [:extensions :forensic/causal-parent-roots]) nodes)))
        graph (forensic/validate-forensic-evidence-graph {:nodes nodes :artifact-roots roots})]
    (is (:valid? graph))
    (is (= :declared (get-in (second nodes) [:extensions :forensic/role])))
    (is (= :restricted (get-in (second nodes) [:extensions :forensic/visibility])))
    (is (= (:research-coordinate-interaction/root original) (:derived/result-root result)))))
