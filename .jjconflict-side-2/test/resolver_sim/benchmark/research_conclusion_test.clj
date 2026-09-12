(ns resolver-sim.benchmark.research-conclusion-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.research-conclusion :as rc]
            [resolver-sim.benchmark.research-theorem-outcome :as rto]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const minimal-conclusion
  {:conclusion/id :conclusion/quota-bounded-write-back
   :conclusion/premise
   {:x "Every participant allocation satisfies its effective quota,
        the deferred residual is conserved, and the successor position
        writes that residual as :position/current-amount."}
   :conclusion/result
   {:y "The quota-bounded partial-fill transition preserves the participant's
        remaining entitlement in authoritative deferred-position state."}
   :conclusion/status :established
   :conclusion/scope {:cases 128
                      :parameter-domain-root "sha256:domain"
                      :model-root "sha256:model"}
   :conclusion/qualifications
   ["No conclusion is made about coalition incentive compatibility."]
   :conclusion/supporting-theorem-hashes
   [(str "sha256:" (apply str (take 64 (cycle "11"))))
    (str "sha256:" (apply str (take 64 (cycle "22"))))]})

(deftest build-minimal-conclusion
  (let [c (rc/build-conclusion minimal-conclusion)]
    (is (rc/conclusion-valid? c))
    (is (some? (:conclusion/hash c)))
    (is (= :conclusion/quota-bounded-write-back (:conclusion/id c)))
    (is (= :therefore (:conclusion/inference c)))
    (is (= :established (:conclusion/status c)))))

(deftest build-conclusion-defaults-status
  (let [c (rc/build-conclusion
           (dissoc minimal-conclusion :conclusion/status))]
    (is (= :established (:conclusion/status c)))))

(deftest build-conclusion-requires-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :conclusion/id"
                        (rc/build-conclusion (dissoc minimal-conclusion :conclusion/id)))))

(deftest build-conclusion-requires-premise
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :conclusion/premise"
                        (rc/build-conclusion (dissoc minimal-conclusion :conclusion/premise)))))

(deftest build-conclusion-requires-result
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing :conclusion/result"
                        (rc/build-conclusion (dissoc minimal-conclusion :conclusion/result)))))

(deftest build-conclusion-rejects-invalid-status
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid :conclusion/status"
                        (rc/build-conclusion (assoc minimal-conclusion :conclusion/status :bogus)))))

(deftest build-conclusion-rejects-hash-mismatch
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Declared conclusion/hash"
                        (rc/build-conclusion (assoc minimal-conclusion :conclusion/hash "sha256:wrong")))))

(deftest validate-conclusion-valid
  (let [c (rc/build-conclusion minimal-conclusion)
        result (rc/validate-conclusion c)]
    (is (:valid? result))))

(deftest validate-conclusion-rejects-tampered-hash
  (let [c (rc/build-conclusion minimal-conclusion)
        bad (assoc c :conclusion/hash "sha256:fake")
        result (rc/validate-conclusion bad)]
    (is (not (:valid? result)))))

(deftest conclusion-overreaches-without-qualifications
  (let [c (rc/build-conclusion
           (-> minimal-conclusion
               (assoc :conclusion/qualifications [])
               (assoc :conclusion/scope {})))]
    (is (rc/conclusion-overreaches? c))))

(deftest conclusion-does-not-overreach-with-qualifications
  (let [c (rc/build-conclusion minimal-conclusion)]
    (is (not (rc/conclusion-overreaches? c)))))

(deftest conclusion-does-not-overreach-with-scope
  (let [c (rc/build-conclusion
           (assoc minimal-conclusion
                  :conclusion/qualifications []
                  :conclusion/scope {:cases 128}))]
    (is (not (rc/conclusion-overreaches? c)))))

(deftest collective-hash-deterministic
  (let [c1 (rc/build-conclusion minimal-conclusion)
        c2 (rc/build-conclusion
            (assoc minimal-conclusion
                   :conclusion/id :conclusion/incentive-compatibility
                   :conclusion/premise {:x "Different premise"}
                   :conclusion/result {:y "Different result"}))
        h1 (rc/conclusion-collective-hash [c1 c2])
        h2 (rc/conclusion-collective-hash [c2 c1])]
    (is (= h1 h2) "collective hash must be order-independent")))

(deftest valid-conclusion-statuses-catalog
  (is (rc/valid-conclusion-status? :established))
  (is (rc/valid-conclusion-status? :qualified))
  (is (rc/valid-conclusion-status? :tentative))
  (is (rc/valid-conclusion-status? :contested))
  (is (rc/valid-conclusion-status? :withdrawn))
  (is (not (rc/valid-conclusion-status? :bogus))))

;; ── :withdrawn terminal-retraction semantics ────────────────────────────────

(defn- withdrawn-input
  "A :withdrawn conclusion carrying only premise/result and a retraction note."
  []
  (-> minimal-conclusion
      (assoc :conclusion/status :withdrawn
             :conclusion/scope {}
             :conclusion/supporting-theorem-hashes []
             :conclusion/qualifications
             ["Retracted: the follow-up reproduction did not replicate the observed outcome."])))

(deftest withdrawn-predicate
  (let [c (rc/build-conclusion (withdrawn-input))]
    (is (rc/withdrawn? c))
    (is (not (rc/withdrawn? (rc/build-conclusion minimal-conclusion))))))

(deftest build-withdrawn-allows-retraction-note
  (let [c (rc/build-conclusion (withdrawn-input))]
    (is (rc/conclusion-valid? c))
    (is (rc/withdrawn? c))
    (is (= 1 (count (:conclusion/qualifications c))))))

(deftest build-withdrawn-rejects-scope
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot carry :conclusion/scope"
                        (rc/build-conclusion
                         (assoc (withdrawn-input) :conclusion/scope {:cases 1})))))

(deftest build-withdrawn-rejects-supporting-theorems
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"cannot carry :conclusion/supporting-theorem-hashes"
                        (rc/build-conclusion
                         (assoc (withdrawn-input)
                                :conclusion/supporting-theorem-hashes
                                [(str "sha256:" (apply str (take 64 (cycle "33"))))])))))

(deftest build-withdrawn-rejects-falsifiers
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"cannot carry :conclusion/falsifiers"
                        (rc/build-conclusion
                         (assoc (withdrawn-input)
                                :conclusion/falsifiers
                                [{:falsifier/id :f1 :status :observed}])))))

(deftest validate-withdrawn-with-note-valid
  (let [c (rc/build-conclusion (withdrawn-input))]
    (is (:valid? (rc/validate-conclusion c)))))

(deftest validate-rejects-withdrawn-with-scope
  (let [c (rc/build-conclusion (withdrawn-input))
        tampered (assoc c :conclusion/scope {:cases 1})
        result (rc/validate-conclusion tampered)]
    (is (not (:valid? result)))
    (is (some #(re-find #"cannot carry :conclusion/scope" %) (:errors result)))))

(deftest validate-rejects-withdrawn-with-supporting-theorems
  (let [c (rc/build-conclusion (withdrawn-input))
        tampered (assoc c :conclusion/supporting-theorem-hashes
                        [(str "sha256:" (apply str (take 64 (cycle "44"))))])
        result (rc/validate-conclusion tampered)]
    (is (not (:valid? result)))
    (is (some #(re-find #"cannot carry :conclusion/supporting-theorem-hashes" %)
              (:errors result)))))

(deftest validate-rejects-withdrawn-with-falsifiers
  (let [c (rc/build-conclusion (withdrawn-input))
        tampered (assoc c :conclusion/falsifiers
                        [{:falsifier/id :f1 :status :untested}])
        result (rc/validate-conclusion tampered)]
    (is (not (:valid? result)))
    (is (some #(re-find #"cannot carry :conclusion/falsifiers" %) (:errors result)))))

(deftest withdrawn-does-not-overreach
  (let [c (rc/build-conclusion (withdrawn-input))]
    (is (not (rc/conclusion-overreaches? c))
        "a withdrawn retraction is never an overreaching established claim")))

(deftest collective-hash-excludes-withdrawn
  (let [active (rc/build-conclusion minimal-conclusion)
        withdrawn (rc/build-conclusion (withdrawn-input))]
    (is (= (rc/conclusion-collective-hash [active])
           (rc/conclusion-collective-hash [active withdrawn]))
        "a withdrawn conclusion must not contribute to the collective root")))

(deftest collective-hash-regression-all-active
  (let [c1 (rc/build-conclusion minimal-conclusion)
        c2 (rc/build-conclusion
            (assoc minimal-conclusion
                   :conclusion/id :conclusion/incentive-compatibility
                   :conclusion/premise {:x "Different premise"}
                   :conclusion/result {:y "Different result"}))
        expected (str "sha256:"
                      (hc/domain-hash :evidence-collection
                                      {:type :conclusion-collection
                                       :conclusion-hashes
                                       (vec (sort (map :conclusion/hash [c1 c2])))}))]
    (is (= expected (rc/conclusion-collective-hash [c1 c2]))
        "all-active input must produce the unfiltered collective root (no hash churn)")))

;; ── Outcome hardening: falsifiers, overreach enforcement, support ─────────

(deftest conclusion-commits-falsifiers
  (let [c (rc/build-conclusion
           (assoc minimal-conclusion
                  :conclusion/falsifiers
                  [{:falsifier/id :falsifier/coalitional-split
                    :status :untested}]))]
    (is (= [{:falsifier/id :falsifier/coalitional-split :status :untested}]
           (:conclusion/falsifiers c)))
    (is (rc/conclusion-valid? c))
    (is (:valid? (rc/validate-conclusion c)))
    (is (not= (:conclusion/hash c)
              (:conclusion/hash (rc/build-conclusion minimal-conclusion)))
        "adding falsifiers must change the committed hash")))

(deftest falsifiers-bound-only-when-present
  (testing "backward compatibility: without falsifiers the field is absent from
            the artifact and old hashes recompute unchanged"
    (let [c (rc/build-conclusion minimal-conclusion)]
      (is (not (contains? c :conclusion/falsifiers)))
      (is (:valid? (rc/validate-conclusion c))))))

(deftest build-conclusion-rejects-invalid-falsifier
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"falsifiers"
                        (rc/build-conclusion
                         (assoc minimal-conclusion
                                :conclusion/falsifiers
                                [{:falsifier/id :f :status :bogus}])))))

(deftest validate-rejects-invalid-falsifier-shape
  (let [c (rc/build-conclusion
           (assoc minimal-conclusion
                  :conclusion/falsifiers
                  [{:falsifier/id :f :status :untested}]))
        bad (assoc c :conclusion/falsifiers [{:falsifier/id :f :status :nope}])]
    (is (not (:valid? (rc/validate-conclusion bad))))))

(deftest validate-enforces-overreach
  (let [over (rc/build-conclusion
              (-> minimal-conclusion
                  (assoc :conclusion/qualifications [])
                  (assoc :conclusion/scope {})))]
    (is (rc/conclusion-overreaches? over))
    (is (not (:valid? (rc/validate-conclusion over)))
        "an overreaching :established conclusion must not validate")
    (is (some #(re-find #"overreaches" %) (:errors (rc/validate-conclusion over))))))

(deftest validate-rejects-malformed-supporting-theorem-hash
  (let [c (rc/build-conclusion
           (assoc minimal-conclusion
                  :conclusion/supporting-theorem-hashes ["not-a-hash"]))]
    (is (not (:valid? (rc/validate-conclusion c))))))

(deftest verify-conclusion-support-resolves-theorems
  (let [th (rto/build-theorem-outcome
            {:theorem/id :theorem/quota-bounded
             :theorem/type :boundedness
             :theorem/statement {:if {:claim :x} :then {:claim :y}}
             :theorem/scope {:benchmark/content-root "sha256:content"}
             :theorem/conclusion {:status :established :claim-id :claim/qb}})
        resolver (fn [h] (when (= h (:theorem/hash th)) th))
        c (rc/build-conclusion
           (assoc minimal-conclusion
                  :conclusion/supporting-theorem-hashes [(:theorem/hash th)]))
        r (rc/verify-conclusion-support c resolver)]
    (is (:valid? r))
    (is (= [th] (:resolved-theorems r))))
  (testing "a missing theorem fails the transitive commitment rule"
    (let [c (rc/build-conclusion
             (assoc minimal-conclusion
                    :conclusion/supporting-theorem-hashes
                    [(str "sha256:" (apply str (take 64 (cycle "aa"))))]))]
      (is (not (:valid? (rc/verify-conclusion-support c (constantly nil)))))))
  (testing "a theorem whose hash does not recompute to the claimed hash fails"
    (let [th (rto/build-theorem-outcome
              {:theorem/id :theorem/other
               :theorem/type :conservation
               :theorem/statement {:if {:claim :a} :then {:claim :b}}
               :theorem/scope {:benchmark/content-root "sha256:content"}
               :theorem/conclusion {:status :established :claim-id :claim/other}})
          claimed (str "sha256:" (apply str (take 64 (cycle "bb"))))
          c (rc/build-conclusion
             (assoc minimal-conclusion
                    :conclusion/supporting-theorem-hashes [claimed]))
          ;; resolver returns the real theorem whose hash is th's true hash,
          ;; so the claimed hash does not recompute to it.
          resolver (fn [_h] th)]
      (is (not= claimed (:theorem/hash th)))
      (is (not (:valid? (rc/verify-conclusion-support c resolver)))))))
