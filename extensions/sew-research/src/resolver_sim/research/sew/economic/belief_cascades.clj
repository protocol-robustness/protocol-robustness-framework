(ns resolver-sim.research.sew.economic.belief-cascades
  "Phase V: Correlated Belief Cascades
   Tests: Can early bias create permanent drift through rational herding?"
  (:require [resolver-sim.stochastic.rng :as rng]
            [resolver-sim.sim.engine      :as engine]))

(defn resolver-decides
  "Resolver decision based on signal + prior"
  [signal prior confidence correlation-bias]
  (let [w-prior (+ (- 1.0 confidence) (* correlation-bias 0.2))
        w-prior (min 1.0 (max 0.0 w-prior))
        belief (+ (* w-prior prior) (* (- 1.0 w-prior) signal))]
    (if (> belief 0.5) 1 0)))

(defn epoch-results
  "Run one epoch with N resolvers"
  [n-resolvers ground-truth prior-rate conf corr rng]
  (let [correct (for [_ (range n-resolvers)]
                  (let [signal (if (< (rng/next-double rng) 0.8)
                                 (if ground-truth 1.0 0.0)
                                 (if ground-truth 0.0 1.0))
                        decision (resolver-decides signal prior-rate conf corr)
                        correct? (= (= decision 1) ground-truth)]
                    correct?))
        accuracy (/ (count (filter identity correct)) (double n-resolvers))]
    {:accuracy accuracy
     :consensus (> (count (filter identity correct)) (/ n-resolvers 2.0))}))

(def ^:private lock-threshold
  "Maximum early-accuracy and late-accuracy for the system to be considered
   locked into a wrong consensus.  Both must fall below these thresholds."
  {:early 0.5 :late 0.6})

(def ^:private drift-threshold
  "Minimum absolute accuracy drift for a cascading system to be classified as
   vulnerable (high risk of irreversible belief cascade)."
  0.15)

(defn run-cascade
  "Run simulation with early skew"
  [num-epochs n-resolvers early-skew confidence correlation-bias rng]
  (let [conf-val (case confidence :high 0.8 :medium 0.7 :low 0.6 0.7)
        early-period 3
        num-wrong (int (* early-skew 0.01 early-period))
        wrong-epochs (set (take num-wrong (shuffle (range early-period))))

        ground-truth (for [e (range num-epochs)]
                       (not (and (< e early-period) (wrong-epochs e))))

        epochs (loop [e 0 prior 0.5 results []]
                 (if (>= e num-epochs)
                   results
                   (let [truth (nth ground-truth e)
                         res (epoch-results n-resolvers truth prior conf-val correlation-bias rng)
                         correct? (:consensus res)
                         new-prior (double (+ prior (if correct? 0.1 -0.1)))
                         new-prior (min 1.0 (max 0.0 new-prior))]
                     (recur (inc e)
                            new-prior
                            (conj results (assoc res :epoch e :ground-truth truth))))))

        early-acc (if (> (count epochs) 0)
                    (/ (apply + (map :accuracy (take 3 epochs))) 3.0)
                    0.5)
        late-acc (if (> (count epochs) 0)
                   (/ (apply + (map :accuracy (drop (max 0 (- (count epochs) 5)) epochs))) 5.0)
                   0.5)
        drift (- late-acc early-acc)
        locked? (and (< early-acc (:early lock-threshold))
                     (< late-acc (:late lock-threshold)))]

    {:drift drift :early-accuracy early-acc :late-accuracy late-acc
     :locked? locked? :vulnerable? (and (> (Math/abs drift) drift-threshold) locked?)}))

(defn test-cascade
  "Test scenario"
  [scenario-name seed early-skew confidence correlation-bias]
  (let [rng (rng/make-rng seed)
        result (run-cascade 50 15 early-skew confidence correlation-bias rng)]
    {:scenario scenario-name
     :status (if (:vulnerable? result) :vulnerable :safe)
     :confidence (Math/abs (:drift result))
     :metrics {:drift (:drift result) :early (:early-accuracy result)
               :late (:late-accuracy result) :locked (:locked? result)}
     :reason (format "drift=%.1f%%, early=%.1f%%, late=%.1f%%, locked=%s"
                     (* 100.0 (:drift result))
                     (* 100.0 (:early-accuracy result))
                     (* 100.0 (:late-accuracy result))
                     (:locked? result))}))

(defn run-phase-v-sweep
  []
  (println "\n" (apply str (repeat 70 "=")))
  (println "Phase V: Correlated Belief Cascades")
  (println (apply str (repeat 70 "=")))

  (let [tests [[0 :medium 0.3 "baseline"]
               [20 :medium 0.1 "skew-low-corr"]
               [20 :medium 0.6 "skew-med-corr"]
               [20 :medium 0.9 "skew-high-corr"]
               [20 :low 0.6 "skew-low-conf"]]

        results (for [seed (range 42 47)
                      [skew conf corr name-str] tests]
                  (let [r (test-cascade (str name-str "-s" seed) seed skew conf corr)
                        status-str (if (= (:status r) :vulnerable) "VULN" "safe")]
                    (println (format "%-35s [%s]" (:scenario r) status-str))
                    r))]

    (let [vuln    (count (filter #(= :vulnerable (:status %)) results))
          passed? (<= vuln 1)]
      (println "\n" (apply str (repeat 70 "=")))
      (println (format "Results: %d vulnerable / %d total" vuln (count results)))
      (if (> vuln 5)
        (println "  🔴 CASCADES LOCK IN FREQUENTLY")
        (if (> vuln 1)
          (println "  🟡 Cascades in high-correlation scenarios")
          (println "  🟢 SAFE: System resists cascades")))
      (engine/make-result
       {:benchmark-id "V"
        :label        "Correlated Belief Cascades"
        :hypothesis   "System resists belief cascades (<= 1 vulnerable scenario)"
        :passed?      passed?
        :results      (vec results)
        :summary      {:vulnerable vuln :total (count results)}}))))

(defn -main [& args] (run-phase-v-sweep))
