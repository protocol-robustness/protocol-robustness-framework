(ns resolver-sim.benchmark.researcher-pro-rata-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-definition :as research]
            [resolver-sim.benchmark.researcher-axis :as researcher-axis]
            [resolver-sim.benchmark.researcher-pro-rata-projection :as projection]
            [resolver-sim.benchmark.researcher-reward :as reward]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.pro-rata.allocation :as allocation]))

(defn- root [seed]
  (str "sha256:" (apply str (take 64 (cycle seed)))))

(defn- throws? [thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo _ true)))

(def policy
  (reward/build-policy {:accepted-contribution-types #{:approval}
                        :required-verification #{:verified}
                        :required-claim-statuses #{:established}
                        :reward-amount 100
                        :reward-currency "USD"
                        :split-rules {:default :full}
                        :duplicate-rule {:mode :reject}
                        :conflict-of-interest-rule {:mode :reject}}))

(defn- reward-obligation [researcher contribution]
  (let [eligibility (reward/evaluate {:policy policy
                                      :researcher researcher
                                      :assignment-root (root "a")
                                      :contribution-root (root contribution)
                                      :contribution-type :approval
                                      :verification {:verified? true}
                                      :claim {:claimable? true
                                              :research-claim/status :established}
                                      :duplicate? false
                                      :conflict-of-interest? false})
        proposal (reward/build-proposal {:eligibility eligibility :policy policy})]
    (reward/build-obligation
     {:proposal proposal
      :eligibility eligibility
      :approval-authority {:authority/id "committee"
                           :authority/root (root "c")
                           :authority/authorized? true}})))

(defn- researcher-axis [researchers scope-seed]
  (let [body {:artifact/schema research/axis-schema
              :axis/kind :researcher
              :axis/scope {:review-round/root (root (str "round-" scope-seed))
                           :signer-key-set/root (root (str "keys-" scope-seed))}
              :axis/members (mapv (fn [key researcher-id]
                                    {:axis/key key
                                     :axis/id researcher-id
                                     :axis/member {:researcher/id researcher-id}})
                                  (range)
                                  (sort researchers))}]
    (assoc body :axis/root (research/axis-root body))))

(defn- allocation-result [rows]
  (allocation/allocate {:schema-version "pro-rata-allocation-request.v1"
                        :mechanism/version 1
                        :allocation/id :allocation/researcher-projection
                        :available 12
                        :rows rows
                        :rounding-policy :largest-remainder
                        :tie-break-policy :canonical-row-id
                        :redistribution-policy :unallocated}))

(defn- fixture []
  (let [o1 (reward-obligation "researcher-a" "1")
        o2 (reward-obligation "researcher-a" "2")
        o3 (reward-obligation "researcher-b" "3")
        axis (researcher-axis ["researcher-b" "researcher-a"] "fixture")
        result (allocation-result
                [{:row/id :row/b :obligation/id (:researcher-reward-obligation/root o1)
                  :requested 6 :weight 1}
                 {:row/id :row/a :obligation/id (:researcher-reward-obligation/root o1)
                  :requested 6 :weight 1}
                 {:row/id :row/c :obligation/id (:researcher-reward-obligation/root o3)
                  :requested 6 :weight 1}
                 {:row/id :row/unrelated :obligation/id :unrelated/obligation
                  :requested 6 :weight 1}])]
    {:axis axis :obligations [o3 o2 o1] :result result
     :o1 o1 :o2 o2 :o3 o3}))

(deftest projection-preserves-researcher-obligation-and-row-identities
  (let [{:keys [axis obligations result o1 o2]} (fixture)
        p (projection/build-projection {:researcher-axis axis
                                        :allocation-result result
                                        :obligations obligations})
        bindings (:bindings p)]
    (is (= "researcher-pro-rata-projection.v1" (:artifact/schema p)))
    (is (= ["researcher-a" "researcher-a" "researcher-b"]
           (mapv :researcher/id bindings)))
    (is (= [[] [:row/a :row/b]]
           (sort-by first (mapv :row/ids (filter #(= "researcher-a" (:researcher/id %)) bindings)))))
    (is (some #(= {:researcher/id "researcher-a"
                   :row/ids [:row/a :row/b]
                   :researcher-reward-obligation/root (:researcher-reward-obligation/root o1)}
                  (select-keys % [:researcher/id :row/ids :researcher-reward-obligation/root]))
              bindings))
    (is (some #(= {:researcher/id "researcher-a"
                   :row/ids []
                   :researcher-reward-obligation/root (:researcher-reward-obligation/root o2)}
                  (select-keys % [:researcher/id :row/ids :researcher-reward-obligation/root]))
              bindings))
    (is (hash-ref/valid-sha256-ref? (:researcher-pro-rata-projection/root p)))
    (is (= p (projection/validate-projection axis obligations result p)))))

(deftest coordinate-resolution-and-derived-row-view-are-scoped
  (let [{:keys [axis obligations result]} (fixture)
        p (projection/build-projection {:researcher-axis axis
                                        :allocation-result result
                                        :obligations obligations})
        coordinate (researcher-axis/researcher-coordinate axis "researcher-a")
        rows (projection/researcher-allocation-rows axis obligations p result coordinate)]
    (is (= "researcher-a" (:researcher/id
                           (researcher-axis/researcher-member axis coordinate))))
    (is (= [[] [:row/a :row/b]] (sort-by first (mapv (comp (partial mapv :row/id) :rows) rows))))
    (is (throws? #(researcher-axis/researcher-member
                   (researcher-axis axis "other-scope") coordinate)))))

(deftest substitutions-fail-closed
  (let [{:keys [axis obligations result]} (fixture)
        p (projection/build-projection {:researcher-axis axis
                                        :allocation-result result
                                        :obligations obligations})
        altered (fn [bindings]
                  (let [body (assoc p :bindings bindings)]
                    (assoc body :researcher-pro-rata-projection/root
                           (projection/projection-root body))))
        wrong-allocation (assoc p :allocation/root (root "other-allocation"))
        wrong-researcher
        (altered (update-in (:bindings p) [0 :researcher/id] (constantly "researcher-b")))
        wrong-obligation
        (altered (update-in (:bindings p)
                            [0 :researcher-reward-obligation/root]
                            (constantly (root "other-obligation"))))
        wrong-row (altered (assoc-in (:bindings p) [0 :row/ids] [:row/c]))
        conflicting-row (altered (assoc-in (assoc-in (:bindings p) [0 :row/ids] [:row/a])
                                           [1 :row/ids] [:row/a]))]
    (is (throws? #(projection/validate-projection axis obligations result wrong-allocation)))
    (is (throws? #(projection/validate-projection axis obligations result wrong-researcher)))
    (is (throws? #(projection/validate-projection axis obligations result wrong-obligation)))
    (is (throws? #(projection/validate-projection axis obligations result wrong-row)))
    (is (throws? #(projection/validate-projection axis obligations result conflicting-row)))))

(deftest projection-requires-complete-closed-obligation-bindings
  (let [{:keys [axis obligations result]} (fixture)
        p (projection/build-projection {:researcher-axis axis
                                        :allocation-result result
                                        :obligations obligations})
        reroot (fn [bindings]
                 (let [body (assoc p :bindings bindings)]
                   (assoc body :researcher-pro-rata-projection/root
                          (projection/projection-root body))))]
    (is (throws? #(projection/validate-projection
                   axis obligations result (assoc p :bindings {}))))
    (is (throws? #(projection/validate-projection
                   axis obligations result
                   (reroot (vec (butlast (:bindings p)))))))
    (is (throws? #(projection/validate-projection
                   axis obligations result
                   (reroot (conj (:bindings p) (first (:bindings p)))))))
    (is (throws? #(projection/validate-projection
                   axis obligations result
                   (reroot (conj (vec (butlast (:bindings p)))
                                 (assoc (first (:bindings p))
                                        :researcher-reward-obligation/root
                                        (root "unsupplied-obligation")))))))))

(deftest deterministic-input-ordering-does-not-change-projection
  (let [{:keys [axis obligations result]} (fixture)
        reordered (allocation-result (reverse (get-in result [:canonical-request :rows])))
        p1 (projection/build-projection {:researcher-axis axis
                                         :allocation-result result
                                         :obligations obligations})
        p2 (projection/build-projection {:researcher-axis axis
                                         :allocation-result reordered
                                         :obligations (reverse obligations)})]
    (is (= (:allocation/hash result) (:allocation/hash reordered)))
    (is (= p1 p2))))

(deftest work-stealing-is-canonically-equivalent-to-sequential
  (let [{:keys [axis obligations result]} (fixture)
        input {:researcher-axis axis :allocation-result result :obligations obligations}
        sequential (projection/build-projection input {:mode :sequential})
        one-worker (projection/build-projection input {:mode :work-stealing :workers 1})
        stolen (projection/build-projection
                input
                {:mode :work-stealing
                 :workers 2
                 :delay-fn (fn [obligation]
                             (case (:researcher/id obligation)
                               "researcher-a" 25
                               "researcher-b" 0
                               5))})
        repeated (projection/build-projection
                  input
                  {:mode :work-stealing
                   :workers 8
                   :delay-fn (fn [_] 1)})
        empty-input (projection/build-projection
                     (assoc input :obligations [])
                     {:mode :work-stealing :workers 4})]
    (is (= sequential one-worker))
    (is (= sequential stolen))
    (is (= sequential repeated))
    (is (= [] (:bindings empty-input)))
    (is (= (:researcher-pro-rata-projection/root sequential)
           (:researcher-pro-rata-projection/root stolen)))))

(deftest real-zero-row-remains-distinct-from-missing-row
  (let [{:keys [axis o1]} (fixture)
        result (allocation-result [{:row/id :row/zero
                                    :obligation/id (:researcher-reward-obligation/root o1)
                                    :requested 0 :weight 0}])
        p (projection/build-projection {:researcher-axis axis
                                        :allocation-result result
                                        :obligations [o1]})
        rows (projection/researcher-allocation-rows
              axis [o1] p result (researcher-axis/researcher-coordinate axis "researcher-a"))]
    (is (= [:row/zero] (mapv :row/id (:rows (first rows)))))
    (is (= 0 (:allocated (first (:rows (first rows))))))))
