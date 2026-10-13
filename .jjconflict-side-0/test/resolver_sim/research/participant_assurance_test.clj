(ns resolver-sim.research.participant-assurance-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.research.participant-assurance :as assurance]
            [resolver-sim.research.evidence-attribution :as attribution]
            [resolver-sim.research.interaction-reproduction :as reproduction]
            [resolver-sim.research.interaction-reproduction-test :as fixture]
            [resolver-sim.benchmark.research-definition :as definition]
            [resolver-sim.benchmark.researcher-axis :as researcher]
            [resolver-sim.evidence.forensic-graph :as forensic]))

(defn root [ch] (str "sha256:" (apply str (repeat 64 ch))))
(defn researcher-axis []
  (let [body {:artifact/schema definition/axis-schema :axis/kind :researcher
              :axis/members (mapv (fn [key id]
                                    {:axis/key key :axis/id id :axis/member {:researcher/id id}})
                                  [0 1] ["reference/a" "reference/b"])}]
    (assoc body :axis/root (definition/axis-root body))))

(defn context [axis id impl]
  (let [{:keys [input original]} (fixture/fixture)
        execution (reproduction/execute input impl)
        evidence (reproduction/compare-execution execution original)]
    {:implementation impl :execution execution :original original :evidence evidence
     :attribution (attribution/project-attribution
                   axis (researcher/researcher-coordinate axis id)
                   {:subject {:subject/kind :research/interaction
                              :subject/root (:research-coordinate-interaction/root original)}
                    :evidence-root (:reproduction-evidence/root evidence)
                    :relation :reproduced :disclosure :anonymous})}))

(defn pair [same-reference? same-implementation?]
  (let [axis (researcher-axis)
        {:keys [implementation]} (fixture/fixture)
        alternate (if same-implementation? implementation
                      (reproduction/contrast-implementation
                       (:resolved-research-dependency-set/root implementation) "1.0.0"))]
    {:axis axis :contexts [(context axis "reference/a" implementation)
                           (context axis (if same-reference? "reference/a" "reference/b") alternate)]}))

(deftest reference-and-implementation-distinctness-do-not-establish-person-independence
  (doseq [[same-reference? same-implementation? expected-reference expected-implementation]
          [[false false :yes :yes] [true false :no :yes] [false true :yes :no] [true true :no :no]]]
    (let [{:keys [axis contexts]} (pair same-reference? same-implementation?)
          result (assurance/build-assurance axis contexts [])]
      (is (= expected-reference (get-in result [:assurance/dimensions :participant-reference-distinct])))
      (is (= expected-implementation (get-in result [:assurance/dimensions :implementation-artifact-distinct])))
      (doseq [dimension assurance/real-world-dimensions]
        (is (= :unknown (get-in result [:assurance/dimensions dimension]))))
      (is (= :comparable (:assurance/comparison-status result)))
      (is (every? #(= :anonymous (:contribution/disclosure %)) (:assurance/contributions result))))))

(deftest declarations-authentication-and-conflicts-do-not-infer-independence
  (let [{:keys [axis contexts]} (pair false false)
        claims [{:dimension :authenticated-identities :value :yes :evidence-roots [(root \a)]}
                {:dimension :organization-distinct :value :yes :evidence-roots [(root \b)]}
                {:dimension :organization-distinct :value :no :evidence-roots [(root \c)]}]
        result (assurance/build-assurance axis contexts claims)]
    (is (= :yes (:declared (assurance/declared-dimension-view result :authenticated-identities))))
    (is (= :unknown (:established (assurance/declared-dimension-view result :authenticated-identities))))
    (is (= :conflicting (:declared (assurance/declared-dimension-view result :organization-distinct))))
    (is (= :unknown (get-in result [:assurance/dimensions :person-distinct])))
    (is (= 3 (count (:assurance/declarations result))))
    (is (= result (assurance/build-assurance axis (vec (reverse contexts)) (vec (concat claims claims)))))
    (is (= result (assurance/build-assurance
                   axis contexts (mapv #(into (array-map) (reverse (seq %))) claims))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (assurance/build-assurance axis contexts [{:dimension :person-distinct :value :yes :evidence-roots []}])))))

(deftest duplicate-reproductions-and-version-only-differences-are-counted-conservatively
  (let [{:keys [axis contexts]} (pair true true)
        duplicate (assurance/build-assurance axis contexts [])
        first-context (first contexts)
        original-impl (:implementation first-context)
        changed-version (reproduction/implementation
                         (:resolved-research-dependency-set/root original-impl) "2.0.0")
        version-context (context axis "reference/b" changed-version)
        version-pair (assurance/build-assurance axis [first-context version-context] [])]
    (is (= 1 (:assurance/unique-execution-count duplicate)))
    (is (= 1 (:assurance/matching-reproduction-count duplicate)))
    (is (= :no (get-in version-pair [:assurance/dimensions :implementation-artifact-distinct])))
    (is (not= (get-in first-context [:execution :reproduction-execution/root])
              (get-in version-context [:execution :reproduction-execution/root])))))

(deftest substitution-and-source-projection-cannot-satisfy-reproduction-assurance
  (let [{:keys [axis contexts]} (pair false false)
        [a b] contexts
        result (assurance/build-assurance axis contexts [])]
    (doseq [bad [(assoc b :implementation (:implementation a))
                 (assoc b :execution {:evidence/mode :source-projection})
                 (assoc b :evidence (:evidence a))
                 (assoc-in b [:attribution :attribution/evidence-root] (root \f))
                 (assoc b :original (assoc (:original b) :interaction/value 999))]]
      (is (thrown? clojure.lang.ExceptionInfo (assurance/build-assurance axis [a bad] []))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (assurance/validate-assurance
                  (assoc-in result [:assurance/dimensions :person-distinct] :yes) axis contexts [])))))

(deftest assurance-adds-generic-forensic-lineage-without-changing-reproduction
  (let [{:keys [axis contexts]} (pair false false)
        result (assurance/build-assurance axis contexts [])
        node (assurance/evidence-node result axis contexts [])
        graph (forensic/validate-forensic-evidence-graph
               {:nodes [node] :artifact-roots (get-in node [:extensions :forensic/causal-parent-roots])})]
    (is (:valid? graph))
    (is (= :restricted (get-in node [:extensions :forensic/visibility])))
    (is (= result (assurance/build-assurance axis (vec (reverse contexts)) [])))
    (is (= (get-in (first contexts) [:execution :execution/fresh-result])
           (get-in (second contexts) [:execution :execution/fresh-result])))
    (is (not= (get-in (first contexts) [:execution :reproduction-execution/root])
              (get-in (second contexts) [:execution :reproduction-execution/root])))))
