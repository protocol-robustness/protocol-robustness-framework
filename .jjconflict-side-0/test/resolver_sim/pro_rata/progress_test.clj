(ns resolver-sim.pro-rata.progress-test
  "SP-A: progress event model, reducer semantics, atom-adapter equivalence, and
   observer non-interference. Progress is operational/noncanonical: it must never
   affect request/result/evidence identity and :event ordering makes no semantic
   claim."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.pro-rata.allocation :as allocation]
            [resolver-sim.pro-rata.evidence :as evidence]
            [resolver-sim.pro-rata.progress :as progress]))

(defn- reduce-all [snapshot events]
  (reduce progress/reducer snapshot events))

(deftest reducer-reaches-completed-monotonically
  (let [events [{:event :phase-started :status :running :phase :preparing :total 3}
                {:event :phase-started :phase :requesting}
                {:event :phase-started :phase :allocating}
                {:event :claimants-completed :delta 1}
                {:event :claimants-completed :delta 1}
                {:event :claimants-completed :delta 1}
                {:event :allocation-completed}]
        s (reduce-all nil events)]
    (is (= "pro-rata-progress.v1" (:progress/schema s)))
    (is (= :completed (:status s)))
    (is (= :completed (:phase s)))
    (is (= 3 (:current s)) "current accumulates via atomic deltas")
    (is (= 3 (:total s)))))

(deftest claimant-deltas-are-order-independent
  (testing "atomic deltas advance current regardless of arrival order"
    (let [events (shuffle (repeat 5 {:event :claimants-completed :delta 1}))]
      (is (= 5 (:current (reduce-all nil events))))))
  (testing "a negative delta is not privileged"
    (is (= 2 (:current (reduce-all nil [{:event :claimants-completed :delta 2}]))))))

(deftest redistribution-tracks-pass-index-monotonically
  (let [s (reduce-all nil [{:event :phase-started :status :running :phase :allocating}
                           {:event :redistribution-started :pass-index 0}
                           {:event :redistribution-started :pass-index 1}
                           {:event :redistribution-started :pass-index 2}])]
    (is (= :redistributing (:phase s)))
    (is (= :running (:status s)))
    (is (= 2 (:pass-index s)))))

(deftest proving-is-indeterminate-but-truthful
  (let [s (reduce-all nil [{:event :proving :status :running :phase :proving
                            :elapsed-ms 1234}])]
    (is (= :proving (:phase s)))
    (is (= :running (:status s)))
    (is (= 1234 (:elapsed-ms s)))))

(deftest terminal-failed-carries-phase-and-category
  (let [s (progress/terminal-failed :proving {:error-category :proof-generation-failed})]
    (is (= :failed (:status s)))
    (is (= :proving (:phase s)))
    (is (= :proof-generation-failed (:error/category s)))
    (is (nil? (:stack-trace s)) "stack traces/machine strings are never canonical")))

(deftest schema-and-vocabulary-are-stable
  (is (= #{:pending :running :completed :failed :cancelled} progress/statuses))
  (is (contains? progress/phases :redistributing))
  (is (contains? progress/phases :proving))
  (is (contains? progress/event-types :statement-admitted)
      "proof-pipeline vocabulary is defined (not wired) in SP-A")
  (is (not-any? #{:request/root :allocation/result :evidence/root} progress/event-types)
      "events are operational and carry no canonical root identity"))

(deftest untyped-events-are-legacy-compat-with-visible-marker
  (testing "a bare untyped field map is the legacy path, stamped compat-only"
    (let [s (reduce-all nil [{:status :running :phase :preparing :current 1}])]
      (is (= :untyped-event (:progress/compat s)) "legacy use is visibly marked")
      (is (= :preparing (:phase s)) "legacy map still best-effort merges fields")))
  (testing "an unknown event keyword is legacy, not part of the typed vocabulary"
    (let [s (reduce-all nil [{:event :no-such-event :status :running}])]
      (is (= :untyped-event (:progress/compat s)))))
  (testing "legacy :redistribution-pass remaps to :pass-index on the legacy path"
    (let [s (reduce-all nil [{:event :who :redistribution-pass 3}])]
      (is (= 3 (:pass-index s)))
      (is (= :untyped-event (:progress/compat s))))))

(deftest normative-typed-events-never-touch-the-legacy-path
  (let [events [{:event :phase-started :status :running :phase :allocating :total 2}
                {:event :claimants-completed :delta 1}
                {:event :claimants-completed :delta 1}
                {:event :allocation-completed}]
        s (reduce-all nil events)]
    (is (= "pro-rata-progress.v1" (:progress/schema s)))
    (is (= :completed (:status s)))
    (is (nil? (:progress/compat s))
        "normative typed events never take the legacy path"))
  (is (every? (fn [event]
                (nil? (:progress/compat (reduce-all nil [event]))))
              (mapv (fn [kw] {:event kw}) progress/event-types))
      "no event in the typed vocabulary marks a snapshot as legacy"))

(deftest atom-adapter-is-reducer-backed-and-operational-only
  (let [atom (progress/make-progress-atom {:programme/id :probe :allocation/id :alloc-1})
        observer (progress/progress-atom-observer atom)
        events [{:event :phase-started :status :running :phase :allocating :total 2}
                {:event :claimants-completed :delta 1}
                {:event :claimants-completed :delta 1}
                {:event :allocation-completed}]]
    (doseq [e events] (observer e))
    (is (= :pending (:status (progress/initial-progress))))
    (is (= {:progress/schema "pro-rata-progress.v1"
            :programme/id :probe
            :allocation/id :alloc-1
            :status :completed
            :phase :completed
            :current 2
            :total 2}
           @atom))))

(defn- allocation-request []
  {:schema-version "pro-rata-allocation-request.v1"
   :mechanism/version 1
   :allocation/id :noninterference
   :available 101
   :rows (mapv (fn [i]
                 {:row/id (keyword (str "row-" i))
                  :obligation/id (keyword (str "obl-" i))
                  :requested 10 :weight 1 :cap 10})
               (range 20))
   :rounding-policy :largest-remainder
   :tie-break-policy :canonical-row-id
   :redistribution-policy :redistribute-cap-excess})

(deftest observer-non-interference-on-canonical-identity  (let [baseline (allocation/allocate (allocation-request))
                                                                atom-res (allocation/allocate (assoc (allocation-request)
                                                                                                     :progress-atom (progress/make-progress-atom)))
                                                                cb-res (allocation/allocate (assoc (allocation-request)
                                                                                                   :on-progress (fn [_])))
                                                                slow-res (allocation/allocate (assoc (allocation-request)
                                                                                                     :on-progress (fn [_] (Thread/sleep 2))))
                                                                throw-res (allocation/allocate (assoc (allocation-request)
                                                                                                      :on-progress (fn [_] (throw (ex-info "observer boom" {})))))
                                                                all (map (juxt :canonical-request :request/hash :allocation/hash)
                                                                         [baseline atom-res cb-res slow-res throw-res])]
                                                            (testing "allocation result roots identical across observer variants"
                                                              (is (apply = (map :allocation/hash all)))
                                                              (is (apply = (map :request/hash all)))
                                                              (is (apply = all)))
                                                            (testing "evidence root identical despite a throwing observer"
                                                              (is (apply = (map (comp :evidence/hash evidence/mechanism-evidence-artifact)
                                                                                [baseline atom-res cb-res slow-res throw-res]))))))

(deftest counting-observer-works-without-a-progress-atom
  (let [{:keys [observe errors]}
        (progress/counting-observer (fn [event]
                                      (when (= :allocation-completed (:event event))
                                        (throw (ex-info "boom" {})))))
        result (allocation/allocate (assoc (allocation-request) :on-progress observe))]
    (is (= 1 @errors) "observer errors are counted in operational state")
    (is (string? (:allocation/hash result)) "allocation completes despite observer error")))

(deftest throwing-callback-cannot-affect-allocator-result
  (let [{:keys [observe errors]}
        (progress/counting-observer (fn [_] (throw (ex-info "always throws" {}))))
        result (allocation/allocate (assoc (allocation-request) :on-progress observe))]
    (is (not (contains? result :observer-errors))
        "error accounting is not part of the allocator result")
    (is (string? (:allocation/hash result)))
    (is (pos? @errors))))