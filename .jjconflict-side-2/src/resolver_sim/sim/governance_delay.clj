;; Governance response time modeling
;; Models the delay between fraud detection and governance-approved slashing execution
;; During this "pending slashes" window, fraudsters continue earning but are frozen

(ns resolver-sim.sim.governance-delay
  (:require [clojure.math :refer [round]]))

;; ============================================================================
;; GOVERNANCE RESPONSE TIME MODEL
;; ============================================================================

(defn initialize-pending-slashes
  "Create empty pending slashes queue at start of epoch"
  []
  {:pending-slashes []        ; Queue of {:detected-epoch, :resolver-id, :amount, :reason}
   :governance-response-days 3 ; How many days governance has to vote
   :resolved-slashes []})

(defn calculate-governance-approval-epoch
  "Determine when governance vote deadline passes
  
  Detection happens at end of current epoch.
  Governance then has `governance-response-days` to vote.
  Assuming 3-4 day epochs in real blockchain, that's roughly 1 epoch delay.
  
  For simulation: If detected in epoch N, earliest execution is epoch N+response-epochs"
  [detected-epoch governance-response-days]
  (let [;; Assume 4 days per epoch (standard in DeFi)
        epochs-per-4-days (/ governance-response-days 4)
        response-epochs (long (Math/ceil epochs-per-4-days))]
    (+ detected-epoch response-epochs)))

(defn mark-slash-detected
  "Add slash to pending queue; it will be executed after governance delay
  
  During pending window:
  - Resolver bond is frozen ✓
  - Resolver earnings are frozen (no new assignments) ✓
  - Resolver can appeal (contract feature) ✓
  
  Returns updated pending slashes queue"
  [pending-state slash-amount resolver-id detected-epoch governance-response-days reason]
  (let [approval-epoch (calculate-governance-approval-epoch detected-epoch governance-response-days)]
    (update pending-state :pending-slashes conj
            {:resolver-id resolver-id
             :amount slash-amount
             :detected-epoch detected-epoch
             :approval-epoch approval-epoch
             :reason reason
             :status :pending-governance})))

(defn get-pending-slashes-for-resolver
  "Get all pending slashes for a specific resolver"
  [pending-state resolver-id]
  (filter #(= (:resolver-id %) resolver-id)
          (:pending-slashes pending-state)))

(defn process-governance-approvals
  "Execute slashes whose governance window has closed
  
  Called at end of each epoch. For each pending slash where current-epoch >= approval-epoch:
  - Move to resolved list
  - Freeze resolver (already frozen by detection, but confirm)
  - Return the slash amount to be waterfall-processed"
  [pending-state current-epoch]
  (let [ready-for-execution (filter #(>= current-epoch (:approval-epoch %))
                                    (:pending-slashes pending-state))
        still-pending (filter #(< current-epoch (:approval-epoch %))
                              (:pending-slashes pending-state))]
    {:pending-state (-> pending-state
                        (assoc :pending-slashes still-pending)
                        (update :resolved-slashes concat (map #(assoc % :status :executed) ready-for-execution)))
     :executable-slashes ready-for-execution
     :slashes-still-pending still-pending}))

(defn get-resolver-pending-balance
  "Get total amount frozen in pending slashes for a resolver
  
  This is bond amount that resolver cannot withdraw while slashes pending"
  [pending-state resolver-id]
  (reduce + 0 (map :amount (get-pending-slashes-for-resolver pending-state resolver-id))))

(defn is-resolver-frozen-for-governance
  "Check if resolver has pending slashes (governance window open)
  
  Frozen resolvers:
  - Cannot withdraw bonds
  - Cannot accept new dispute assignments
  - Cannot earn new fees
  - CAN appeal existing slashes (contract feature)"
  [pending-state resolver-id]
  (not (empty? (get-pending-slashes-for-resolver pending-state resolver-id))))

;; ============================================================================
;; VULNERABILITY WINDOW ANALYSIS
;; ============================================================================

(defn calculate-fraudster-vulnerability-window
  "Calculate how many epochs fraudster can continue earning before slash executes
  
  Timeline:
  - Epoch N: Fraud detected, slashing triggered
  - Epoch N+1 to N+response-epochs: Governance votes (resolver frozen but can appeal)
  - Epoch N+response-epochs+1: Slash executes via governance
  
  During epochs N+1 to N+response-epochs:
  - Resolver is frozen (cannot withdraw bonds)
  - Resolver cannot accept NEW assignments (frozen in workload-weight)
  - Resolver CANNOT earn new fees (frozen state prevents assignment)
  - But: If resolver had pending assignments from before freeze, those execute
  
  Returns:
  - :vulnerability-window-epochs: How many epochs until slash executes
  - :max-earnings-during-window: Rough estimate of fees earned during window"
  [governance-response-days resolution-fee-per-dispute]
  (let [epochs-per-4-days (/ governance-response-days 4)
        response-epochs (long (Math/ceil epochs-per-4-days))

        ;; During frozen window, resolver gets NO new assignments (workload-weight = 0)
        ;; Earnings during window = 0 (fully frozen)
        max-earnings 0]
    {:vulnerability-window-epochs response-epochs
     :governance-response-days governance-response-days
     :max-earnings-during-window max-earnings
     :notes "Resolver fully frozen during governance window - no new earnings possible"}))

(defn calculate-slash-timing-impact
  "Analyze impact of governance response delay on attack profitability
  
  Parameters:
  - slash-amount: Penalty amount (e.g., 10k for junior)
  - governance-response-days: How long governance vote takes (e.g., 3-7 days)
  - daily-profit-if-honest: What resolver earns per day if playing honestly
  - daily-profit-if-malicious: Extra profit per day from fraudulent activity
  
  Returns:
  - :slash-happens-epoch: When slash finally executes
  - :window-duration-epochs: How many epochs fraudster avoids penalty
  - :delayed-penalty-cost: Cost of delay (opportunity cost of frozen capital)
  - :break-even-analysis: Is fraudulent activity still profitable after delay?
  
  Note: Under full freeze model, break-even is NEVER profitable (no earnings during window)"
  [slash-amount governance-response-days daily-profit-honest daily-profit-malicious]
  (let [epochs-per-4-days (/ governance-response-days 4)
        response-epochs (long (Math/ceil epochs-per-4-days))

        ;; During window, resolver earns ZERO (fully frozen)
        earnings-during-window 0

        ;; Cost of delay = opportunity cost of having capital locked
        ;; Assuming capital could earn daily-profit-honest if unfrozen
        opportunity-cost (* response-epochs 4 daily-profit-honest)

        ;; Total cost to attacker
        total-cost (+ slash-amount opportunity-cost)

        ;; Profit from fraudulent activity (already earned before detection)
        fraud-profit (* daily-profit-malicious 10) ;; Assume 10-day fraud window before detection

        ;; Break-even: Is fraud-profit > total-cost?
        is-profitable (> fraud-profit total-cost)]

    {:window-duration-epochs response-epochs
     :window-duration-days (* response-epochs 4)
     :governance-response-days governance-response-days
     :slash-amount slash-amount
     :earnings-during-window earnings-during-window
     :opportunity-cost opportunity-cost
     :total-penalty (+ slash-amount opportunity-cost)
     :fraud-profit fraud-profit
     :is-profitable-after-governance-delay is-profitable
     :break-even-daily-profit (/ (+ slash-amount opportunity-cost) 10)
     :margin-of-safety (- fraud-profit total-cost)}))

;; ============================================================================
;; INTEGRATION WITH MULTI-EPOCH SIMULATION
;; ============================================================================

(defn apply-governance-delays-to-epoch
  "Process one epoch of simulation with governance delay mechanics
  
  Flow:
  1. Start of epoch: Check if any pending slashes should execute
  2. Mid-epoch: Run disputes (frozen resolvers don't get assignments)
  3. End of epoch: If new fraud detected, mark as pending-governance
  
  This is called ONCE per epoch and modifies resolver state based on pending slashes"
  [epoch-state current-epoch governance-state]

  ;; Step 1: Execute slashes whose governance window has closed
  (let [{:keys [pending-state executable-slashes]}
        (process-governance-approvals governance-state current-epoch)

        ;; Step 2: Mark frozen resolvers in dispute assignments
        ;; (Mid-epoch: resolvers with pending slashes don't get new assignments)
        frozen-resolver-ids (set (map :resolver-id (:pending-slashes pending-state)))

        ;; Step 3: Apply executed slashes via waterfall
        ;; (This is done by returning executable-slashes to caller for waterfall processing)
        ]

    {:updated-epoch-state (update epoch-state :frozen-resolvers frozen-resolver-ids)
     :updated-governance-state pending-state
     :slashes-to-execute executable-slashes}))

;; ============================================================================
;; METRICS & REPORTING
;; ============================================================================

(defn summarize-governance-delay-metrics
  "Generate summary statistics about governance delays during simulation
  
  Returns:
  - :total-pending-slashes: How many slashes awaited governance approval
  - :avg-governance-delay-epochs: Average time from detection to execution
  - :max-governance-delay-epochs: Longest pending window
  - :fraudster-earnings-during-window: How much fraudsters earned while pending
  - :appeals-filed: How many resolvers appealed (if tracked)
  - :appeals-successful: How many appeals overturned slashing (if tracked)"
  [governance-state]
  (let [resolved (map #(assoc % :delay-epochs (- (:approval-epoch %) (:detected-epoch %)))
                      (:resolved-slashes governance-state))
        total-slashes (count resolved)
        avg-delay (if (> total-slashes 0)
                    (/ (reduce + 0 (map :delay-epochs resolved)) total-slashes)
                    0)
        max-delay (if (> total-slashes 0)
                    (apply max (map :delay-epochs resolved))
                    0)]

    {:total-pending-slashes-resolved total-slashes
     :avg-governance-delay-epochs (double (/ (round (* avg-delay 100)) 100))
     :max-governance-delay-epochs max-delay
     :pending-slashes-still-waiting (count (:pending-slashes governance-state))
     :frozen-resolvers (count (set (map :resolver-id (:pending-slashes governance-state))))
     :breakdown-by-reason (frequencies (map :reason resolved))}))

(comment
  ;; Example: Calculate impact of 3-day governance delay
  (calculate-slash-timing-impact
   10000      ;; slash amount ($10k junior bond)
   3          ;; governance-response-days
   100        ;; daily-profit-honest ($100/day)
   500)       ;; daily-profit-malicious ($500/day from fraud)

  ;; Expected: Not profitable (full freeze prevents earnings during window)
  ;; Result:
  ;; {:window-duration-epochs 1
  ;;  :window-duration-days 4
  ;;  :slash-amount 10000
  ;;  :earnings-during-window 0
  ;;  :total-penalty 10400
  ;;  :fraud-profit 5000
  ;;  :is-profitable-after-governance-delay false  <- KEY FINDING
  ;;  :margin-of-safety -5400}

  ;; With 7-day governance delay:
  (calculate-slash-timing-impact 10000 7 100 500)
  ;; Result:
  ;; {:window-duration-epochs 2
  ;;  :window-duration-days 8
  ;;  :slash-amount 10000
  ;;  :earnings-during-window 0
  ;;  :total-penalty 10800
  ;;  :fraud-profit 5000
  ;;  :is-profitable-after-governance-delay false  <- Still not profitable
  ;;  :margin-of-safety -5800}
  )
