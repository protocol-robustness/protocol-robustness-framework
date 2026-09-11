(ns resolver-sim.sim.adversarial.ring-model
  "Per-member ring coordination model.

   Replaces the aggregate-floor ring exit model in phase-ai with a stochastic
   per-member simulation. Each ring member is an individual actor with its own
   detection roll each epoch.

   Improvements over the aggregate model:
     - Variance: small rings exit unevenly (one lucky epoch, one catastrophic)
     - Entry dynamics: new members join when expected profit > expected detection cost
     - Cascade signalling: ≥2 detections in one epoch triggers remaining members
       to halve their activity (lay low), reducing both income and detection risk
     - Individual member histories: traceable per-epoch trajectories

   Enable via :use-ring-model? true in phase-ai params."
  (:require [resolver-sim.stochastic.rng :as rng]))

;; ---------------------------------------------------------------------------
;; Data model
;; ---------------------------------------------------------------------------

(defrecord RingMember
           [id
            epoch-joined
            status          ; :active | :laying-low | :exited
            disputes-handled
            epochs-active
            detected-epoch  ; nil or epoch number when detected
            ])

(defn- make-member [id epoch-joined]
  (->RingMember id epoch-joined :active 0 0 nil))

(defn make-ring
  "Initialise a ring of n members, all joining at epoch `start-epoch`.
   Returns {:members [...] :active-count n :total-joined n}"
  [n start-epoch]
  (let [members (vec (for [i (range n)]
                       (make-member (str "ring-" i) start-epoch)))]
    {:members      members
     :active-count n
     :total-joined n}))

;; ---------------------------------------------------------------------------
;; Per-epoch evolution
;; ---------------------------------------------------------------------------

(defn- detection-roll
  "True if this member is detected this epoch."
  [^java.util.SplittableRandom rng eff-det laying-low?]
  ;; Laying-low members face 50% reduced detection (they do less)
  (< (rng/next-double rng) (if laying-low? (* 0.5 eff-det) eff-det)))

(defn- should-new-member-join?
  "True if joining the ring is expected-positive-EV this epoch."
  [ring params]
  (let [active-now   (:active-count ring)
        max-size     (:ring-max-size params (* 3 (:ring-size params 5)))
        fee-per-disp (* (:escrow-size params 10000)
                        (/ (:resolver-fee-bps params 100) 10000.0))
        n-trials     (:n-trials-per-epoch params 100)
        ;; Expected disputes for one ring member (ring gets ring-fraction of disputes)
        total-r      (+ active-now (:n-resolvers params 60))
        ring-frac    (/ (inc active-now) total-r)
        disputes-per (double (* ring-frac n-trials (/ 1.0 (inc active-now))))
        expected-fee (* disputes-per fee-per-disp)
        ;; Expected cost: detection probability × slash penalty
        base-det     (:fraud-detection-probability params 0.05)
        eff-det      (/ base-det (max 1 (inc active-now)))
        slash-bps    (:fraud-slash-bps params 500)
        slash-cost   (* (:escrow-size params 10000) (/ slash-bps 10000.0) eff-det)]
    (and (< active-now max-size)
         (> expected-fee slash-cost))))

(defn evolve-ring-epoch
  "Simulate one epoch for the ring population.

   Each active member independently rolls for detection.
   Detected members exit. If ≥2 detections, remaining lay low for one epoch.
   Laying-low members from last epoch resume activity (status → :active).
   New members join if EV-positive and ring is below max-size.

   Returns:
     {:ring         updated-ring
      :exits        [{:id :epoch :was-laying-low?}]
      :entries      [{:id :epoch-joined}]
      :lay-low?     bool (true if cascade triggered this epoch)
      :active-count int}"
  [rng ring params epoch]
  (let [;; Step 1: members that were laying-low resume activity
        members-resumed
        (mapv (fn [m]
                (if (= :laying-low (:status m))
                  (assoc m :status :active)
                  m))
              (:members ring))

        ;; Effective detection probability per member
        base-det  (:fraud-detection-probability params 0.05)
        ring-size (max 1 (count (filter #(= :active (:status %)) members-resumed)))
        eff-det   (/ base-det ring-size)

        ;; Step 2: detection rolls for active members
        ;; (use split-rng per member for determinism)
        detection-results
        (mapv (fn [m]
                (if (= :active (:status m))
                  (let [[sub-rng _] (rng/split-rng rng)
                        detected?   (detection-roll sub-rng eff-det false)]
                    (assoc m :_detected? detected?))
                  (assoc m :_detected? false)))
              members-resumed)

        ;; Step 3: apply detection outcomes
        exits
        (vec (filter #(:_detected? %) detection-results))

        survivors
        (mapv (fn [m]
                (if (:_detected? m)
                  (assoc m :status :exited :detected-epoch epoch)
                  (-> m
                      (update :disputes-handled + 1)
                      (update :epochs-active inc)
                      (dissoc :_detected?))))
              detection-results)

        ;; Step 4: cascade — enough concurrent detections triggers lay-low
        cascade?  (>= (count exits) (:cascade-threshold params 2))
        members-after-exits
        (if cascade?
          (mapv (fn [m]
                  (if (= :active (:status m))
                    (assoc m :status :laying-low)
                    m))
                survivors)
          survivors)

        ;; Step 5: remove exited members (or keep in history with :exited status)
        ;; We keep them for history but active-count reflects only :active + :laying-low
        new-active-count
        (count (filter #(#{:active :laying-low} (:status %)) members-after-exits))

        ;; Step 6: optional new member entry
        ring-so-far {:members      members-after-exits
                     :active-count new-active-count
                     :total-joined (:total-joined ring)}
        entry-candidate (when (should-new-member-join? ring-so-far params)
                          (let [new-id (str "ring-" (:total-joined ring))]
                            (make-member new-id epoch)))

        final-members   (if entry-candidate
                          (conj members-after-exits entry-candidate)
                          members-after-exits)
        final-active    (if entry-candidate (inc new-active-count) new-active-count)
        final-joined    (if entry-candidate (inc (:total-joined ring)) (:total-joined ring))]

    {:ring         {:members      final-members
                    :active-count final-active
                    :total-joined final-joined}
     :exits        (mapv #(-> % (dissoc :_detected?) (select-keys [:id :epoch-joined :detected-epoch :disputes-handled]))
                         exits)
     :entries      (when entry-candidate [(select-keys entry-candidate [:id :epoch-joined])])
     :lay-low?     cascade?
     :active-count final-active}))

;; ---------------------------------------------------------------------------
;; Public helpers
;; ---------------------------------------------------------------------------

(defn active-count
  "Current number of active (or laying-low) ring members."
  [ring]
  (count (filter #(#{:active :laying-low} (:status %)) (:members ring))))

(defn ring-summary
  "Return a compact summary map suitable for epoch logging."
  [ring epoch-result]
  {:active-count (:active-count epoch-result)
   :exits-count  (count (:exits epoch-result))
   :entries      (count (or (:entries epoch-result) []))
   :lay-low?     (:lay-low? epoch-result)
   :total-joined (get-in epoch-result [:ring :total-joined])})
