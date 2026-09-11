(ns resolver-sim.protocols.sew.financial.episode-report-test
  "P5 — insolvency episode report reconstruction.

   The report is DERIVED from committed source artifacts (assessment roots,
   lifecycle event roots, decision roots, transition roots) and its :report-root
   is recomputable from those ordered sources.

   The golden fixtures establish that P5 reports CONTEXTUAL response behavior:
   the same create_escrow action (declared #{:liability-creating
   :risk-increasing}) is DENIED while the episode is :impaired (no mutation)
   and LEGITIMATELY EXECUTED while healthy."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.financial.solvency :as solv]
            [resolver-sim.protocols.sew.financial.lifecycle :as fl]
            [resolver-sim.protocols.sew.financial.episode-report :as er]))

(def policy (fl/default-policy))
(def fee0-snap (snap-fix/escrow-snapshot {:escrow-fee-bps 0}))

(defn- solvent-world []
  (-> (t/empty-world 1000)
      (lc/create-escrow "0xbuyer" "USDC" "0xseller2" 1000
                        (t/make-escrow-settings {}) fee0-snap)
      :world))

(defn- impaired-world []
  (-> (t/empty-world 1000)
      (assoc :total-held {:USDC 0})
      (assoc-in [:escrow-transfers 0]
                {:token :USDC :amount-after-fee 10000 :escrow-state :released})
      (assoc-in [:claimable-v2 0 :settlement/principal "r"] 8000)
      (assoc-in [:yield/positions "o1"]
                {:token :USDC :principal 10000 :status :unwinding
                 :shortfall {:fulfilled-amount 8000 :deferred-amount 0
                             :haircut-amount 2000 :reason :principal-loss}})))

(defn- assess [world] (solv/classify-solvency world))

(def create-escrow-action
  {:action "create_escrow"
   :params {:token "USDC" :to "0xseller2" :amount 1000
            :custom-resolver "0xresolver"}})

(defn- healthy-chain []
  (let [r (fl/apply-verified :sew-v1 [] (assess (solvent-world)) {:at 1000})]
    (get-in r [:state :episode/events])))

(defn- impaired-chain []
  ;; T1000 healthy, T1010 impaired (episode onset at 1010)
  (let [r1 (fl/apply-verified :sew-v1 [] (assess (solvent-world)) {:at 1000})
        e1 (get-in r1 [:state :episode/events])
        r2 (fl/apply-verified :sew-v1 e1 (assess (impaired-world)) {:at 1010})]
    (get-in r2 [:state :episode/events])))

(defn- attempt-record
  "Commit an attempt artifact from a real response decision (and optional
   executed transition)."
  [{:keys [at action decision transition]}]
  {:attempt/at at
   :attempt/effects (:action/effects (fl/normalize-action action))
   :attempt/decision (:decision decision)
   :attempt/decision-root (:decision-root decision)
   :attempt/transition transition})

;; ── Golden fixture: create_escrow attempted while impaired → deny, no mutation

(deftest golden-impaired-create-escrow-denied
  (let [chain (impaired-chain)
        world (impaired-world)
        d (fl/response-decision :sew-v1 chain (assess world) policy
                                create-escrow-action world {:request/id "create-1"})
        head-root (:lifecycle-head-root d)
        gate (fl/authorize-and-execute d create-escrow-action "create-1" world head-root
                                       :sew-v1 #{} identity)
        head-state (-> (fl/apply-verified :sew-v1 chain (assess world) {:at 1018})
                       :state)
        report (er/episode-report :sew-v1 head-state
                                  [(attempt-record {:at 1018 :chain chain
                                                    :action create-escrow-action
                                                    :decision d :transition nil})])
        econ (:economic-robustness report)
        resp (:response-robustness report)]
    (is (= :deny (:decision d)))
    (is (= :decision-denied (:error gate)) "no mutation occurred")
    (testing "economic robustness"
      (is (= 1010 (:episode-onset econ)) "episode begins at the first transition away from :healthy")
      (is (= 1010 (:impairment-onset econ)))
      (is (nil? (:insolvency-onset econ)))
      (is (false? (:terminal? econ)))
      (is (true? (:episode-open-ended? econ)) "not recovered → explicitly open-ended")
      (is (zero? (:relapse-count econ))))
    (testing "response robustness — contextual: the same action is risky here because denied"
      (is (= 1 (:risk-increasing-attempts resp)))
      (is (= 1 (:risk-increasing-denials resp)))
      (is (zero? (:invalid-authorization-attempts resp)))
      (is (zero? (:unauthorized-transitions resp)))
      (is (zero? (:denied-executions resp)))
      (is (= 0 (:restriction-activation-latency resp)) "policy restricts immediately at impairment onset")
      (is (= 8 (:first-denial-latency resp)) "T1018 − T1010")
      (is (nil? (:protective-action-latency resp)) "no protective action attempted")
      (is (= 1.0 (:decision-compliance resp)))
      (is (nil? (:transition-authorization-compliance resp)) "no protected transitions"))
    (testing "reproducible report"
      (is (= "insolvency-episode-report.v1" (:episode-report/version report)))
      (is (seq (get-in report [:sources :assessment-roots])))
      (is (seq (get-in report [:sources :lifecycle-event-roots])))
      (is (= [(:decision-root d)] (get-in report [:sources :response-decision-roots])))
      (is (empty? (get-in report [:sources :transition-roots])))
      (is (= (er/report-root (dissoc report :report-root)) (:report-root report))
          "report-root recomputes from the committed body"))))

;; ── Healthy fixture: the same create_escrow is legitimately executed

(deftest healthy-create-escrow-executed
  (let [chain (healthy-chain)
        world (solvent-world)
        d (fl/response-decision :sew-v1 chain (assess world) policy
                                create-escrow-action world {:request/id "create-1"})
        head-root (:lifecycle-head-root d)
        gate (fl/authorize-and-execute
              d create-escrow-action "create-1" world head-root :sew-v1 #{}
              (fn [w] (:world (lc/create-escrow w "0xbuyer" "USDC" "0xseller2" 1000
                                                (t/make-escrow-settings {:custom-resolver "0xresolver"})
                                                fee0-snap))))
        head-state (-> (fl/apply-verified :sew-v1 chain (assess world) {:at 1018})
                       :state)
        report (er/episode-report :sew-v1 head-state
                                  [(attempt-record {:at 1018 :chain chain
                                                    :action create-escrow-action
                                                    :decision d :transition (:transition gate)})])
        econ (:economic-robustness report)
        resp (:response-robustness report)]
    (is (= :permit (:decision d)))
    (is (:ok? gate) "executed while healthy")
    (testing "economic robustness — never left healthy"
      (is (nil? (:episode-onset econ)))
      (is (nil? (:impairment-onset econ)))
      (is (false? (:episode-open-ended? econ))))
    (testing "response robustness — same action is NOT a denial here"
      (is (= 1 (:risk-increasing-attempts resp)))
      (is (zero? (:risk-increasing-denials resp)))
      (is (zero? (:unauthorized-transitions resp)))
      (is (nil? (:first-denial-latency resp)) "no prohibited attempt → undefined, not a failure")
      (is (= 1.0 (:decision-compliance resp)))
      (is (= 1.0 (:transition-authorization-compliance resp))))
    (testing "enforcement integrity is visible in the sources"
      (is (= 1 (count (get-in report [:sources :transition-roots])))
          "the executed transition root is committed"))))

(deftest report-is-contextual-not-action-intrinsic
  (testing "the same create_escrow action yields different response behavior by state"
    (let [impaired-report (let [chain (impaired-chain)
                                world (impaired-world)
                                d (fl/response-decision :sew-v1 chain (assess world) policy
                                                        create-escrow-action world {:request/id "c1"})
                                head (-> (fl/apply-verified :sew-v1 chain (assess world) {:at 1018}) :state)]
                            (er/episode-report :sew-v1 head
                                               [(attempt-record {:at 1018 :action create-escrow-action
                                                                 :decision d :transition nil})]))
          healthy-report (let [chain (healthy-chain)
                               world (solvent-world)
                               d (fl/response-decision :sew-v1 chain (assess world) policy
                                                       create-escrow-action world {:request/id "c1"})
                               head (-> (fl/apply-verified :sew-v1 chain (assess world) {:at 1018}) :state)]
                           (er/episode-report :sew-v1 head
                                              [(attempt-record {:at 1018 :action create-escrow-action
                                                                :decision d :transition nil})]))]
      (is (= 1 (:risk-increasing-denials (:response-robustness impaired-report))))
      (is (zero? (:risk-increasing-denials (:response-robustness healthy-report))))
      (is (not= (:report-root impaired-report) (:report-root healthy-report))))))

(deftest relapse-is-recovery-then-renewed-impairment
  (testing "the initial onset is not a relapse; recovery progress then renewed
            impairment within the same episode is"
    (let [a-solv (assess (solvent-world))
          a-impaired (assess (impaired-world))
          r1 (fl/apply-verified :sew-v1 [] a-impaired {:at 1010}) e1 (:episode/events (:state r1))
          r2 (fl/apply-verified :sew-v1 e1 a-solv {:at 2000}) e2 (:episode/events (:state r2))
          r3 (fl/apply-verified :sew-v1 e2 a-impaired {:at 2100})
          econ (er/economic-robustness (:state r3))]
      (is (= 1010 (:impairment-onset econ)))
      (is (= 1010 (:episode-onset econ))
          "episode onset stays at the first transition away from :healthy (T1010)")
      (is (= 1 (:relapse-count econ))
          "recovering (2000) then renewed impairment (2100) is one relapse"))))
