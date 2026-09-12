(ns resolver-sim.sim.adversarial.adversarial
  "Adversarial parameter search with Production Threat Envelope (PTE)."
  (:require [resolver-sim.sim.batch :as batch]
            [resolver-sim.stochastic.rng :as rng]))

(def pte-v1
  {:resolver-fee-bps [100 300]
   :resolver-bond-bps [500 1500]
   :slash-multiplier [1.0 3.0]
   :slashing-detection-probability [0.03 0.30]})

(defn random-point-constrained [rng pte]
  (let [bond (cond (< (rng/next-double rng) 0.5) 500
                   (< (rng/next-double rng) 0.8) 800
                   :else 1500)
        det (+ 0.03 (* (rng/next-double rng) 0.27))
        fee (+ 100 (* (rng/next-double rng) 200))
        slash (+ 1.0 (* (rng/next-double rng) 2.0))]
    {:resolver-fee-bps (int fee) :resolver-bond-bps bond
     :slash-multiplier slash :slashing-detection-probability det
     :appeal-probability-if-correct 0.05 :appeal-probability-if-wrong 0.40
     :allow-slashing? true}))

(defn eval-params [params n-trials]
  (let [full-params (merge {:strategy :honest :appeal-bond-bps 700
                            :freeze-on-detection? true :freeze-duration-days 3
                            :appeal-window-days 7} params)
        r1 (rng/make-rng 42) r2 (rng/make-rng 43)
        res-h (batch/run-batch r1 n-trials (assoc full-params :strategy :honest))
        res-m (batch/run-batch r2 n-trials (assoc full-params :strategy :malicious))
        he (double (:honest-mean res-h))
        me (double (:malice-mean res-m))]
    {:honest-ev he :malicious-ev me
     :dominance-ratio (if (zero? me) Double/POSITIVE_INFINITY (/ he me))
     :params params}))

(defn hill-climb [rng pte n-iter]
  (let [start (random-point-constrained rng pte)
        best (eval-params start 500)]
    (loop [cur best i 0]
      (if (>= i n-iter) cur
          (let [keys-vec [:resolver-fee-bps :resolver-bond-bps :slash-multiplier :slashing-detection-probability]
                k (nth keys-vec (rng/sample-index rng (count keys-vec)))
                v (get pte k [0 1])
                delta (* (- (second v) (first v)) 0.1 (- (rng/next-double rng) 0.5))
                curr-v (get (:params cur) k 0)
                new-v (max (first v) (min (second v) (+ curr-v delta)))
                cand-params (assoc (:params cur) k new-v)
                cand (eval-params cand-params 500)]
            (if (> (:malicious-ev cand) (:malicious-ev cur))
              (recur cand (inc i)) (recur cur (inc i))))))))

(defn grid-search [pte]
  (let [fees [100 150 200 250 300] bonds [500 800 1000 1500]
        slashes [1.0 1.5 2.0 2.5 3.0] detects [0.03 0.10 0.20 0.30]]
    (for [f fees b bonds s slashes d detects]
      {:resolver-fee-bps f :resolver-bond-bps b :slash-multiplier s
       :slashing-detection-probability d :allow-slashing? true
       :appeal-probability-if-correct 0.05 :appeal-probability-if-wrong 0.40})))

(defn find-flip [worst n-trials]
  (let [det (get worst :slashing-detection-probability 0.03)
        bond (get worst :resolver-bond-bps 500)]
    (map #(eval-params % n-trials)
         [(assoc worst :slashing-detection-probability (max 0.01 (- det 0.01)))
          (assoc worst :resolver-bond-bps (max 100 (- bond 100)))
          (merge worst {:slashing-detection-probability (max 0.01 (- det 0.01))
                        :resolver-bond-bps (max 100 (- bond 100))})])))

(defn run-pte-analysis [& {:keys [n-trials n-restarts] :or {n-trials 1000 n-restarts 50}}]
  (println "\n=== PTE v1.0 Analysis ===")
  (println "Constraints: Bond 5-15%, Fee 1-3%, Detection 3-30%, Slash 1-3x")
  (println "")

  (let [grid-results (doall (map #(eval-params % n-trials) (grid-search pte-v1)))
        hill-results (doall (for [i (range n-restarts)] (hill-climb (rng/make-rng i) pte-v1 100)))
        all-results (concat grid-results hill-results)
        sorted (sort-by :malicious-ev > all-results)
        worst (first sorted) best (last sorted)
        flip-tests (find-flip (:params worst) n-trials)
        wins (filter #(>= (:malicious-ev %) (:honest-ev %)) all-results)]

    (println "Worst cases:")
    (doseq [r (take 5 sorted)]
      (let [p (:params r)]
        (println (format "  A:%+.0f H:%+.0f R:%.2f | F:%d B:%d S:%.1f D:%.0f"
                         (double (:malicious-ev r)) (double (:honest-ev r)) (double (:dominance-ratio r))
                         (int (:resolver-fee-bps p)) (int (:resolver-bond-bps p))
                         (double (:slash-multiplier p)) (* 100 (double (:slashing-detection-probability p)))))))

    (println "")
    (println "Flip tests:")
    (doseq [r flip-tests]
      (let [p (:params r)
            status (if (>= (:malicious-ev r) (:honest-ev r)) "ATTACKER WINS" "still < honest")]
        (println (format "  Det:%.0f%% Bond:%d -> A:%+.0f H:%+.0f [%s]"
                         (* 100 (double (:slashing-detection-probability p))) (int (:resolver-bond-bps p))
                         (double (:malicious-ev r)) (double (:honest-ev r)) status))))

    (println "")
    (println "=== SUMMARY ===")
    (let [we (double (:malicious-ev worst)) be (double (:malicious-ev best))
          wr (double (:dominance-ratio worst))]
      (println (format "  Max attacker EV: %+.0f" we))
      (println (format "  Min attacker EV: %+.0f" be))
      (println (format "  Worst dominance ratio: %.2f" wr))
      (println (format "  Attacker >= honest: %d/%d" (count wins) (count all-results)))
      (cond
        (>= we 0) (println "  OK: Attacker profitable but below honest")
        (>= wr 1.0) (println "  WARNING: Attacker can beat honest!")
        :else (println "  OK: System secure")))))

(defn run-adversarial-search [params & {:keys [n-trials method] :or {n-trials 1000}}]
  (run-pte-analysis :n-trials n-trials :n-restarts 50))
