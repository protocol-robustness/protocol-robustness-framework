(ns resolver-sim.concepts.registry-test
  "Concept registry schema integrity: duplicate ids and missing required keys
   fail closed (the registry is not published)."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.concepts.registry :as cr]))

(defn- registry-entry
  ([id] (registry-entry id (str "data/concepts/t/" (name id) ".edn")))
  ([id file] {:concept/id id :concept/type :framework :concept/file file}))

(defn- full-concept
  [id]
  (merge {:concept/id id
          :concept/name (name id)
          :concept/summary "s"
          :concept/stakeholder-question "q"
          :concept/protocols #{:protocol/sew-v1}
          :concept/roles {} :concept/entities {} :concept/actions {}
          :concept/outcomes {} :concept/failure-modes []
          :concept/metrics {} :concept/assumptions []
          :concept/out-of-scope []}
         {:concept/type :framework}))

(deftest valid-concepts-have-no-integrity-violations
  (let [entries [(registry-entry :test/a) (registry-entry :test/b)]
        concepts [(full-concept :test/a) (full-concept :test/b)]]
    (is (empty? (cr/registry-integrity-violations entries concepts)))))

(deftest duplicate-concept-ids-fail-closed
  (testing "duplicate :concept/id across files is an integrity violation with
            both sources reported (file load order is not hidden semantic state)"
    (let [entries [(registry-entry :test/dup "data/concepts/t/a.edn")
                   (registry-entry :test/dup "data/concepts/t/b.edn")]
          violations (cr/registry-integrity-violations
                      entries [(full-concept :test/dup) (full-concept :test/dup)])]
      (is (= [{:concept/id :test/dup
               :violation/id :violation/duplicate-concept-id
               :sources ["data/concepts/t/a.edn" "data/concepts/t/b.edn"]}]
             violations)))))

(deftest missing-required-keys-fail-closed
  (testing "a concept missing required members is an integrity violation, not
            a warning-and-serve"
    (let [incomplete (dissoc (full-concept :test/missing)
                             :concept/assumptions :concept/out-of-scope)
          violations (cr/registry-integrity-violations
                      [(registry-entry :test/missing)] [incomplete])]
      (is (= [{:concept/id :test/missing
               :violation/id :violation/missing-required-concept-keys
               :missing [:concept/assumptions :concept/out-of-scope]}]
             violations)))))

(deftest missing-use-case-keys-fail-closed
  (testing "use-case concepts missing their use-case contract are violations"
    (let [incomplete (-> (full-concept :test/uc)
                         (assoc :concept/type :use-case)
                         (dissoc :concept/maturity :concept/evidence))
          violations (cr/registry-integrity-violations
                      [(registry-entry :test/uc)] [incomplete])]
      (is (some #(= :violation/missing-required-use-case-keys (:violation/id %))
                violations)))))

(deftest load-registry-publishes-only-when-valid
  (testing "the real registry is integrity-clean and publishes"
    (let [{:keys [concepts]} (cr/load-registry)]
      (is (seq concepts))
      (is (empty? (cr/registry-integrity-violations
                   (:concepts (:registry (cr/load-registry)))
                   concepts))))))
