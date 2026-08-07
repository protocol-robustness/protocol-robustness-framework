(ns resolver-sim.benchmark.review.consensus-invariants-test
  "Algebraic invariants and exhaustive cardinality tests for three-member
   consensus classification.

   Core abstraction: non-assessment is evidence about EVALUABILITY, not a vote
   on the target.  Members whose target status is :not-evaluable (or
   :insufficient-information / :not-reviewed / :not-applicable) are excluded
   from majority computation, and a substantive classification is never
   influenced by a non-assessing member.

   :not-evaluable and :insufficient-information are both non-assessments but
   are kept as distinct certificate groups, so the two underlying
   classifications never become indistinguishable."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]))

(def member-ids ["a" "b" "c"])

(def assessing-statuses
  [:reproduced :unable-to-reproduce :qualified :challenged :supported :not-supported])

(def non-assessing-statuses
  [:not-evaluable :insufficient-information :not-reviewed :not-applicable])

(def all-statuses (concat assessing-statuses non-assessing-statuses))

(defn- consensus
  "Per-item consensus for a panel of the form {:status <kw>} (ids a/b/c)."
  [entries]
  (tmc/per-item-consensus :theorem/x :theorem entries member-ids))

(defn- non-assessing
  "Members in any non-assessment group of a consensus result."
  [c]
  (into #{}
        (mapcat #(get c % [])
                [:not-evaluable-members :insufficient-information-members
                 :not-reviewed-members :not-applicable-members :absent-members])))

(defn- invariant-violations
  "Return a vector of violated invariant descriptions, or [] when all hold."
  [c]
  (let [assessed (set (:assessed-members c))
        supporting (set (:supporting-members c))
        dissenting (set (:dissenting-members c))
        qualifying (set (:qualifying-members c))
        non (non-assessing c)
        contested (into #{} (mapcat :members (:contested-statuses c)))
        violations (volatile! [])
        check (fn [ok? msg] (when-not ok? (vswap! violations conj msg)))]
    (check (empty? (set/intersection assessed non))
           "assessed ∩ non-assessing = ∅")
    (check (set/subset? supporting assessed) "supporting ⊆ assessed")
    (check (set/subset? dissenting assessed) "dissenting ⊆ assessed")
    (check (set/subset? qualifying assessed) "qualifying ⊆ assessed")
    (check (empty? (set/intersection supporting dissenting)) "supporting ∩ dissenting = ∅")
    (when-not (= :contested (:status c))
      (check (= assessed (set/union supporting dissenting qualifying))
             "assessed = supporting ∪ dissenting ∪ qualifying (non-contested)"))
    (check (= (count assessed) (:assessed-count c)) ":assessed-count = |assessed|")
    (check (= 3 (:member-count c)) ":member-count = 3")
    (check (= (into #{} member-ids) (set/union assessed non))
           "assessed ∪ non-assessing covers the full panel")
    (check (= assessed (if (= :contested (:status c)) contested
                           (set/union supporting dissenting qualifying)))
           "assessed partition matches the classification")
    (check (= 0 (count (set/intersection
                        (set (:not-evaluable-members c))
                        (set (:insufficient-information-members c)))))
           ":not-evaluable and :insufficient-information groups are disjoint")
    @violations))

;; ── Exhaustive cardinality matrix ──────────────────────────────────────────

(deftest exhaustive-cardinality-matrix
  (testing "all four assessing/non-assessing cardinalities produce the expected
            semantic shape"
    (testing "0 assessing / 3 non-assessing -> :not-evaluable"
      (doseq [s non-assessing-statuses]
        (let [c (consensus (map (fn [id] {:researcher/id id :status s})
                                member-ids))]
          (is (= :not-evaluable (:status c)))
          (is (empty? (:assessed-members c)))
          (is (empty? (:supporting-members c)))
          (is (= 0 (:assessed-count c)))
          (is (= 3 (:member-count c))))))
    (testing "1 assessing / 2 non-assessing -> :single-assessment (never :unanimous)"
      (doseq [assessed-status assessing-statuses
              ne1 non-assessing-statuses
              ne2 non-assessing-statuses]
        (let [c (consensus [{:researcher/id "a" :status assessed-status}
                            {:researcher/id "b" :status ne1}
                            {:researcher/id "c" :status ne2}])]
          (is (= :single-assessment (:status c))
              (str assessed-status " + " ne1 " + " ne2))
          (is (= ["a"] (:supporting-members c)))
          (is (= 1 (:assessed-count c))))))
    (testing "2 assessing / 1 non-assessing -> unanimous or split among the two"
      (let [same (consensus [{:researcher/id "a" :status :reproduced}
                             {:researcher/id "b" :status :reproduced}
                             {:researcher/id "c" :status :not-evaluable}])
            split (consensus [{:researcher/id "a" :status :reproduced}
                              {:researcher/id "b" :status :challenged}
                              {:researcher/id "c" :status :not-evaluable}])]
        (is (= :unanimous (:status same)))
        (is (= ["a" "b"] (:supporting-members same)))
        (is (= 2 (:assessed-count same)))
        (is (= :contested (:status split)))))
    (testing "3 assessing -> normal three-member consensus"
      (let [c (consensus [{:researcher/id "a" :status :reproduced}
                          {:researcher/id "b" :status :reproduced}
                          {:researcher/id "c" :status :challenged}])]
        (is (= :majority-with-dissent (:status c)))
        (is (= ["a" "b"] (:supporting-members c)))
        (is (= ["c"] (:dissenting-members c)))
        (is (= 3 (:assessed-count c)))))))

(deftest majority-is-permutation-independent
  (testing "the substantive classification does not depend on member input order"
    (let [panels [[{:researcher/id "a" :status :reproduced}
                   {:researcher/id "b" :status :reproduced}
                   {:researcher/id "c" :status :challenged}]
                  [{:researcher/id "c" :status :challenged}
                   {:researcher/id "a" :status :reproduced}
                   {:researcher/id "b" :status :reproduced}]
                  [{:researcher/id "b" :status :reproduced}
                   {:researcher/id "c" :status :challenged}
                   {:researcher/id "a" :status :reproduced}]]]
      (let [shapes (map (fn [p] (select-keys (consensus p)
                                             [:status :supporting-members :dissenting-members]))
                        panels)]
        (is (apply = shapes)
            "every permutation of the same panel yields the same classification")))))

;; ── Property-based algebraic invariants ────────────────────────────────────

(def gen-entries
  "A 3-member panel with distinct fixed ids and arbitrary target statuses."
  (gen/fmap (fn [statuses]
              (mapv (fn [id st] {:researcher/id id :status st})
                    member-ids statuses))
            (gen/vector (gen/elements all-statuses) 3)))

(def prop-invariants
  "For every generated panel, the consensus satisfies the algebraic invariants:
   assessed and non-assessing partition the panel; supporting/dissenting/
   qualifying are assessed subsets; the assessed partition matches the
   classification."
  (prop/for-all [entries gen-entries]
                (empty? (invariant-violations (consensus entries)))))

(defn- mutate-non-assessing
  "Change one non-assessing member's status to another non-assessing status.
   Returns [mutated-entries mutated-status] or nil when the panel has no
   non-assessing member."
  [entries]
  (let [idx (first (keep-indexed (fn [i e]
                                   (when (some #{(:status e)} non-assessing-statuses) i))
                                 entries))]
    (when idx
      (let [st (:status (nth entries idx))
            new-st (first (remove #(= % st) non-assessing-statuses))
            mutated (assoc-in entries [idx :status] new-st)]
        [mutated new-st]))))

(def prop-mutation-invariance
  "Swapping a non-assessing member's status between any two non-assessing
   statuses (:not-evaluable ↔ :insufficient-information ↔ …) must never change
   the substantive classification — a non-assessing member never votes.  The
   provenance grouping may change, but :status / :supporting-members /
   :dissenting-members / :assessed-members must be identical."
  (prop/for-all [entries gen-entries]
                (let [m (mutate-non-assessing entries)]
                  (if (nil? m)
                    true
                    (let [[mutated _] m
                          a (consensus entries)
                          b (consensus mutated)]
                      (and (= (:status a) (:status b))
                           (= (:supporting-members a) (:supporting-members b))
                           (= (:dissenting-members a) (:dissenting-members b))
                           (= (:assessed-members a) (:assessed-members b))))))))

(deftest property-invariants-hold
  (doseq [[name p] {:invariants prop-invariants
                    :mutation-invariance prop-mutation-invariance}]
    (let [result (tc/quick-check 300 p)]
      (is (:pass? result) (str name " failed: " (pr-str (select-keys result [:num-tests :shrunk :fail]))))
      (is (pos? (:num-tests result))))))
