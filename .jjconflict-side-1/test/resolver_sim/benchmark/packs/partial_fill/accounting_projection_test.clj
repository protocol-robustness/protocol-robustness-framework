(ns resolver-sim.benchmark.packs.partial-fill.accounting-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.packs.partial-fill.accounting-projection :as projection]
            [resolver-sim.research.accounting-semantics :as semantics]))

(defn- root [character]
  (str "sha256:" (apply str (repeat 64 character))))

(defn- relation [claim target]
  (semantics/build-claim-to
   {:claim-root (root claim)
    :claimant-root (root \a)
    :target-root (root target)
    :target-category :claim-target/account}))

(defn- mapping [subject target]
  (let [body {:artifact/schema projection/schema
              :application-evidence/root (root \1)
              :application-output/root (root \2)
              :accounting/source-bindings
              [{:entry-index 0 :entry/root (root \3)
                :accounting/subject-root (root subject)
                :claim-to/root (:claim-to/root (relation \4 target))}
               {:entry-index 1 :entry/root (root \5)
                :accounting/subject-root (root subject)
                :claim-to/root (:claim-to/root (relation \6 target))}]}]
    (assoc body :accounting-source-map/root (projection/source-map-root body))))

(deftest explicit-entry-crosswalk-is-rooted-and-validatable
  (let [base (mapping \7 \8)
        changed-subject (mapping \9 \8)
        changed-target (mapping \7 \b)]
    (is (projection/valid-source-map? base))
    (is (not= (:accounting-source-map/root base)
              (:accounting-source-map/root changed-subject)))
    (is (not= (:accounting-source-map/root base)
              (:accounting-source-map/root changed-target)))
    (is (false? (projection/valid-source-map?
                 (assoc base :accounting-source-map/root (root \c)))))
    (testing "the mapping requires a one-to-one indexed entry coverage"
      (let [duplicate (assoc-in base [:accounting/source-bindings 1 :entry-index] 0)
            duplicate (assoc duplicate :accounting-source-map/root
                             (projection/source-map-root
                              (dissoc duplicate :accounting-source-map/root)))]
        (is (false? (projection/valid-source-map? duplicate)))))))

(deftest partial-fill-identifiers-do-not-manufacture-accounting-roots
  (is (thrown? clojure.lang.ExceptionInfo
               (projection/build-source-map
                {:application-evidence-profile {}
                 :application-evidence-args
                 {:application {:accounting-entries []}
                  :propagation {:accounting-entries []}}
                 :entry-bindings []}))))
