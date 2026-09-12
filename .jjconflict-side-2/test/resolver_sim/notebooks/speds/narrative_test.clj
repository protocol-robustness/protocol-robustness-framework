(ns resolver-sim.notebooks.speds.narrative-test
  "P1: Evidence-backed narrative frame model.
   Verifies the exit criteria: claims carry identifiable sources, missing data
   renders as unavailable, evidence changes move the narrative, removing
   evidence weakens or removes a claim, determinism, and no hardcoded metrics."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.notebook-support.speds.data :as data]
            [resolver-sim.notebook-support.speds.narrative :as n]
            [resolver-sim.notebook-support.speds.narrative-render :as render]))

(def real-scenario-ids
  ["scenarios/eq-v10-incentive-compat-fail-attack-success" ; guards+tags, theory-falsification
   "scenarios/eq-v4-dominant-strategy-pass"                 ; 2 guards+tags, regression
   "scenarios/eq-v9-nash-fail-attack-success"               ; guards+tags, theory-falsification
   "scenarios/eq-v11-spe-pass-honest-resolver"              ; no guards, no tags (sparse)
   "scenarios/s115-claim-deferred-yield-recovery"])         ; no guards (different suite)

(defn real-artifacts
  "Committed fixture artifacts: coverage, traces, golden reports."
  []
  {:coverage (data/load-coverage)
   :all-traces (data/load-all-traces)
   :golden-reports (data/load-all-golden-reports)})

(defn- collect-claims
  "Walks a frame and returns every claim map (maps with :claim/basis)."
  [x]
  (if (map? x)
    (if (contains? x :claim/basis)
      [x]
      (mapcat collect-claims (vals x)))
    (if (coll? x) (mapcat collect-claims x) [])))

(defn- render-text
  "Flattens a frame's Hiccup rendering to a plain string."
  [frame]
  (->> (render/render-frame frame) flatten (map str) (apply str)))

;; ──────────────────────────────────────────────────────────────────────────
;; Real data: every displayed claim has a source; missing data is unavailable.
;; ──────────────────────────────────────────────────────────────────────────

(deftest real-scenarios-build-frames
  (testing "all chosen real scenarios build frames through the same pipeline"
    (let [arts (real-artifacts)]
      (doseq [sid real-scenario-ids]
        (let [frame (n/build-frame arts sid)]
          (is (= "speds.frame.v1" (:frame/schema frame)))
          (is (= sid (:scenario/id frame)))
          (is (string? (:scenario/title frame))))))))

(deftest every-substantive-claim-has-identifiable-source
  (testing "observed/derived claims always carry a non-empty source"
    (let [arts (real-artifacts)]
      (doseq [sid real-scenario-ids
              claim (collect-claims (n/build-frame arts sid))]
        (is (contains? n/claim-bases (:claim/basis claim))
            (str "invalid basis " (:claim/basis claim) " in " sid))
        (when (not= :not-measured (:claim/basis claim))
          (is (seq (:claim/source claim))
              (str "claim " (:claim/id claim) " in " sid
                   " has basis " (:claim/basis claim) " but no source")))))))

(deftest real-frame-is-deterministic
  (testing "build-frame is deterministic for identical input"
    (let [arts (real-artifacts)]
      (doseq [sid real-scenario-ids]
        (is (= (n/build-frame arts sid) (n/build-frame arts sid)))))))

(deftest sparse-scenario-renders-not-measured
  (testing "scenario with no guards/tags renders explicit NOT MEASURED, no synthetic copy"
    (let [frame (n/build-frame (real-artifacts) "scenarios/eq-v11-spe-pass-honest-resolver")]
      (is (= :not-measured (:response/basis (:response frame))))
      (is (empty? (:response/guards (:response frame))))
      (is (= :not-measured (get-in frame [:threat :threat/tags :claim/basis])))
      (is (re-find #"NOT MEASURED" (render-text frame))))))

(deftest no-hardcoded-metrics-in-render
  (testing "rendered frame never asserts invented latency/accuracy/margin/invariant success"
    (let [frame (n/build-frame (real-artifacts) "scenarios/eq-v10-incentive-compat-fail-attack-success")
          txt   (render-text frame)]
      (doseq [forbidden ["0.1ms" "1e-18" "MARGIN: 100%" "SOLVENCY GUARANTEED"
                         "REPLAY: VERIFIED" "ATTACK DEFLECTED"]]
        (is (not (str/includes? txt forbidden)) (str "found forbidden: " forbidden))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Mutation tests: removing/altering evidence weakens or removes the claim.
;; ──────────────────────────────────────────────────────────────────────────

(defn- minimal-arts
  [& {:keys [scenarios traces golden] :or {scenarios [] traces [] golden {}}}]
  {:coverage {:scenarios scenarios}
   :all-traces traces
   :golden-reports golden})

(defn- scenario-with-guard
  [& {:keys [guards tags] :or {guards [] tags []}}]
  {:id "scenarios/S01_attack"
   :title "Attack"
   :purpose "theory-falsification"
   :threat-tags tags
   :guards guards})

(defn- trace-with-events
  [& {:keys [actions] :or {actions []}}]
  {:scenario-id "S01_attack"
   :id "scenarios/S01_attack"
   :_filename "S01_attack.trace.json"
   :events (mapv (fn [i a] {:seq i :agent "attacker" :action a :time 1000})
                 (range) actions)
   :expectations {:terminal [{:path ["live-states" 0] :equals "disputed"}]
                  :metrics [{:name "attack-attempts" :op ":=" :value 1}]}})

(deftest expected-absent-is-not-measured
  (testing "expectations map lacking a key is :not-measured, not :observed with nil"
    (let [arts (minimal-arts :scenarios [(scenario-with-guard)]
                             :traces [{:scenario-id "S01_attack"
                                       :id "scenarios/S01_attack"
                                       :events [{:seq 0 :agent "a" :action "x" :time 1}]
                                       :expectations {:terminal nil}}])
          frame (n/build-frame arts "scenarios/S01_attack")]
      (is (= :not-measured (get-in frame [:outcome :outcome/expected :terminal :claim/basis])))
      (is (= :not-measured (get-in frame [:outcome :outcome/expected :metrics :claim/basis]))))))

(deftest real-theory-verdict-surfaced
  (testing "falsification scenarios surface the golden report's property verdict"
    (let [frame (n/build-frame (real-artifacts)
                               "scenarios/eq-v10-incentive-compat-fail-attack-success")
          theory (get-in frame [:outcome :outcome/actual :theory])]
      (is (= :observed (:claim/basis (:result/theory-status theory))))
      (is (= :not-falsified (:claim/value (:result/theory-status theory))))
      (is (= :observed (:claim/basis (:result/mechanism-status theory))))
      (is (= :fail (:claim/value (:result/mechanism-status theory))))
      (is (= :property-violated (:claim/value (:result/mechanism-reason theory))))
      (is (seq (:claim/source (:result/mechanism-status theory)))))))

(deftest mutation-remove-golden-weakens-theory
  (testing "removing the golden report makes the property verdict unavailable"
    (let [golden {"s01-attack" {:outcome :pass :metrics {:attack-attempts 1}
                                :theory {:status :not-falsified :falsified? false
                                         :mechanism-status :fail}}}
          with   (n/build-frame (minimal-arts :scenarios [(scenario-with-guard)]
                                              :traces [(trace-with-events :actions ["raise_dispute"])]
                                              :golden golden)
                                "scenarios/S01_attack")
          without (n/build-frame (minimal-arts :scenarios [(scenario-with-guard)]
                                               :traces [(trace-with-events :actions ["raise_dispute"])])
                                 "scenarios/S01_attack")]
      (is (= :observed (get-in with [:outcome :outcome/actual :theory :result/mechanism-status :claim/basis])))
      (doseq [k [:result/theory-status :result/falsified? :result/mechanism-status
                 :result/mechanism-reason :result/display-label]]
        (is (= :not-measured (get-in without [:outcome :outcome/actual :theory k :claim/basis])))))))

(deftest expected-metrics-render-legible
  (testing "expected metrics render as readable specs, not raw structures"
    (let [txt (render-text (n/build-frame (real-artifacts)
                                          "scenarios/eq-v10-incentive-compat-fail-attack-success"))]
      (is (str/includes? txt "ATTACK-ATTEMPTS"))
      (is (str/includes? txt ":= 1"))
      (is (not (str/includes? txt "{:name"))))))

(deftest mutation-remove-golden-weakens-outcome
  (testing "removing the golden report demotes actual outcome :observed -> :not-measured"
    (let [golden {"s01-attack" {:outcome :fail :final-state-hash "abc"
                                :metrics {:attack-attempts 1 :invariant-violations 1}}}
          with   (n/build-frame (minimal-arts :scenarios [(scenario-with-guard)]
                                              :traces [(trace-with-events :actions ["raise_dispute"])]
                                              :golden golden)
                                "scenarios/S01_attack")
          without (n/build-frame (minimal-arts :scenarios [(scenario-with-guard)]
                                               :traces [(trace-with-events :actions ["raise_dispute"])])
                                 "scenarios/S01_attack")]
      (is (= :observed (get-in with [:outcome :outcome/actual :outcome :claim/basis])))
      (is (= :not-measured (get-in without [:outcome :outcome/actual :outcome :claim/basis])))
      (is (= :not-measured (get-in without [:guarantees 0 :claim/basis]))))))

(deftest mutation-remove-trace-weakens-response-and-events
  (testing "removing the trace makes guard exercise and event sequence unavailable"
    (let [with   (n/build-frame (minimal-arts :scenarios [(scenario-with-guard
                                                           :guards [{:guard "adversarial-attempt" :transition "raise_dispute"}])]
                                              :traces [(trace-with-events :actions ["raise_dispute"])])
                                "scenarios/S01_attack")
          without (n/build-frame (minimal-arts :scenarios [(scenario-with-guard
                                                            :guards [{:guard "adversarial-attempt" :transition "raise_dispute"}])])
                                 "scenarios/S01_attack")]
      (is (= :derived (get-in with [:response :response/guards 0 :guard/exercised :claim/basis])))
      (is (true? (get-in with [:response :response/guards 0 :guard/exercised :claim/value])))
      (is (= :not-measured (get-in without [:response :response/guards 0 :guard/exercised :claim/basis])))
      (is (empty? (:outcome/events (:outcome without)))))))

(deftest mutation-remove-guards-weakens-response
  (testing "scenario without guards has no protocol-response claim"
    (let [frame (n/build-frame (minimal-arts :scenarios [(scenario-with-guard)]
                                             :traces [(trace-with-events :actions ["raise_dispute"])])
                               "scenarios/S01_attack")]
      (is (= :not-measured (:response/basis (:response frame))))
      (is (empty? (:response/guards (:response frame)))))))

(deftest mutation-changing-trace-changes-narrative
  (testing "changing whether the guarded transition is exercised flips the derived claim"
    (let [exercised   (n/build-frame (minimal-arts
                                      :scenarios [(scenario-with-guard
                                                   :guards [{:guard "adversarial-attempt" :transition "raise_dispute"}])]
                                      :traces [(trace-with-events :actions ["raise_dispute"])])
                                     "scenarios/S01_attack")
          not-exercised (n/build-frame (minimal-arts
                                        :scenarios [(scenario-with-guard
                                                     :guards [{:guard "adversarial-attempt" :transition "raise_dispute"}])]
                                        :traces [(trace-with-events :actions ["create_escrow"])])
                                       "scenarios/S01_attack")]
      (is (true? (get-in exercised [:response :response/guards 0 :guard/exercised :claim/value])))
      (is (false? (get-in not-exercised [:response :response/guards 0 :guard/exercised :claim/value]))))))
