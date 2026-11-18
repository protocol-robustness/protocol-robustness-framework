(ns resolver-sim.protocols.sew.reversal-fixtures
  "Shared builders for L0 release → escalation → L1 refund reversal-slash worlds."
  (:require [resolver-sim.protocols.sew.snapshot-fixtures :as snap-fix]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.protocols.sew.lifecycle :as lc]
            [resolver-sim.protocols.sew.resolution :as res]
            [resolver-sim.protocols.sew.registry :as reg]
            [resolver-sim.time.context :as time-ctx]))

(defn- require-ok!
  [result step context]
  (when-not (:ok result)
    (throw (ex-info (str context " failed at " step)
                    {:error (:error result) :step step :context context})))
  result)

(declare build-reversal-world)

(defn- at-block-time
  [world block-times step-key]
  (if-let [t (get block-times step-key)]
    (time-ctx/with-temporal-context world {:block-ts t})
    world))

(defn- escalate!
  [world workflow-id caller escalation-via l1-resolver block-times context]
  (let [esc-fn (fn [_w _wfid _caller _level] {:ok true :new-resolver l1-resolver})
        world' (at-block-time world block-times :escalation)]
    (case escalation-via
      :challenge (require-ok!
                  (res/challenge-resolution world' workflow-id caller esc-fn)
                  "challenge-resolution"
                  context)
      :escalate (require-ok!
                 (res/escalate-dispute world' workflow-id caller esc-fn)
                 "escalate-dispute"
                 context))))

(def ^:private base-opts
  {:initial-block-time 1000
   :buyer "0xBuyer"
   :seller "0xSeller"
   :l0-resolver "0xL0Res"
   :l1-resolver "0xL1Res"
   :challenger "0xChallenger"
   :escalation-caller nil
   :token "USDC"
   :escrow-amount 8000
   :l0-stake 10000
   :l1-stake 10000
   :escalation-via :challenge
   :l0-is-release true
   :l1-is-release false
   :l0-hash "0xhash-l0"
   :l1-hash "0xhash-l1"
   :snapshot {}
   :block-times {}})

;; Invariant S101: L0 at 1120, third-party challenge at 1130, L1 at 1200.
(def s101-track1-reversal
  {:escalation-via :challenge
   :block-times {:l0 1120 :escalation 1130 :l1 1200}})

;; Phase K: participant escalation on a long appeal window, same block throughout.
(def participant-escalation-reversal
  {:escalation-via :escalate
   :l0-resolver "0xRes0"
   :l1-resolver "0xRes1"
   :token "0xToken"
   :escrow-amount 5000
   :l0-stake 5000
   :l1-stake 5000
   :escalation-caller "0xSeller"
   :l0-hash "hash0"
   :l1-hash "hash1"
   :snapshot {:escrow-fee-bps 0
              :appeal-window-duration 3600
              :appeal-bond-bps 1000
              :reversal-slash-bps 2500}
   :block-times {}})

;; ---------------------------------------------------------------------------
;; Track 2 reversal: submit evidence before L1 so the slash is :pending
;; ---------------------------------------------------------------------------

(def s106-track2-reversal
  "Preset matching S106: submit-evidence before L1 reversal creates Track 2 pending slash."
  {:escalation-via :escalate
   :block-times {:l0 1120 :escalation 1130 :evidence 1140 :l1 1200}
   :l0-is-release true
   :l1-is-release false
   :snapshot {:appeal-window-duration 200 :reversal-slash-bps 2500
              :resolver-bond-bps 0 :escrow-fee-bps 150}})

(defn build-reversal-world-track2
  "Like build-reversal-world but records evidence before L1 resolution,
   producing a Track 2 (:pending) reversal slash instead of Track 1 (:executed).

   opts — same as build-reversal-world; see s106-track2-reversal for the presets.
   Returns same shape as build-reversal-world but the slash is :pending."
  ([] (build-reversal-world-track2 {}))
  ([opts]
   (let [result (build-reversal-world (merge opts (select-keys s106-track2-reversal
                                                [:escalation-via :l0-is-release
                                                 :l1-is-release :l0-hash :l1-hash])))
         step-after-l1 (:after-l1 (:steps result))
         world-after-l0 (:after-l0 (:steps result))
         world-after-l0-with-evidence (assoc-in world-after-l0
                                                 [:evidence-updated? (:workflow-id result)] true)
         escalation-time (or (get-in opts [:block-times :evidence]) 1140)
         esc-fn (fn [_ _ _ _] {:ok true :new-resolver "0xL1Res"})
         after-esc-with-evidence (:world (res/escalate-dispute
                                          (time-ctx/with-temporal-context
                                            world-after-l0-with-evidence
                                            {:block-ts escalation-time})
                                          (:workflow-id result) "0xSeller" esc-fn))
         after-l1-track2 (:world (res/execute-resolution
                                   (time-ctx/with-temporal-context
                                     after-esc-with-evidence
                                     {:block-ts (or (get-in opts [:block-times :l1]) 1200)})
                                   (:workflow-id result) "0xL1Res" false "0xl1hash" nil))]
     {:world after-l1-track2
      :workflow-id (:workflow-id result)
      :steps (:steps result)})))

;; ---------------------------------------------------------------------------
;; Appeal lifecycle: pending slash → appealed → ready for governance
;; ---------------------------------------------------------------------------

(defn build-appeal-world
  "Build a world where a fraud slash has been proposed and appealed.
   Returns {:world <appealed> :workflow-id n :slash-id id}.

   opts may contain :token (default USDC), :stake (default 1000),
   :slash-amount (default 100), :appeal-bond-amount (default 60)."
  ([] (build-appeal-world {}))
  ([opts]
   (let [token         (or (:token opts) "USDC")
         stake         (or (:stake opts) 1000)
         slash-amount  (or (:slash-amount opts) 100)
         bond-amount   (or (:appeal-bond-amount opts) 60)
         max-offense   (or (:max-slash-per-offense-bps opts) 10000)
         epoch-cap     (or (:slash-epoch-cap-bps opts) 5000)
         snap          (snap-fix/escrow-snapshot
                         {:appeal-window-duration 100
                          :appeal-bond-amount bond-amount
                          :escrow-fee-bps 0})
         world0        (-> (t/empty-world 1000)
                           (assoc-in [:params :max-slash-per-offense-bps] max-offense)
                           (assoc-in [:params :slash-epoch-cap-bps] epoch-cap)
                           (reg/register-stake "0xResolver" stake))
         {:keys [world workflow-id]}
         (lc/create-escrow world0 "0xBuyer" token "0xSeller" 2000
                           {:custom-resolver "0xResolver"} snap)
         after-raise   (:world (lc/raise-dispute world workflow-id "0xBuyer"))
         after-resolve (:world (res/execute-resolution after-raise workflow-id
                                                        "0xResolver" true "0xhash" nil))
         after-propose (:world (res/propose-fraud-slash after-resolve workflow-id
                                                         "0xGov" "0xResolver" slash-amount))
         after-appeal  (:world (res/appeal-slash after-propose workflow-id "0xResolver"))]
     {:world     after-appeal
      :workflow-id workflow-id
      :slash-id  workflow-id})))

;; ---------------------------------------------------------------------------
;; Multi-level reversal chain: L0 → L1 → L2 (max level)
;; ---------------------------------------------------------------------------

(defn build-multi-level-reversal-world
  "Runs L0 release → escalate → L1 refund → escalate → L2 release (reversal at each level).
   Returns {:world <after L2> :workflow-id n :steps {:after-l0 :after-l1 :after-l2}}."
  ([] (build-multi-level-reversal-world {}))
  ([opts]
   (let [snap (snap-fix/escrow-snapshot
               (merge {:appeal-window-duration 60 :reversal-slash-bps 2500
                       :max-dispute-level 2 :dispute-resolver "0xL0"
                       :resolver-bond-bps 0 :escrow-fee-bps 0}
                      opts))
         world0 (-> (t/empty-world 1000)
                    (reg/register-stake "0xL0" 8000)
                    (reg/register-stake "0xL1" 8000)
                    (reg/register-stake "0xL2" 8000))
         {:keys [world workflow-id]}
         (lc/create-escrow world0 "0xBuyer" "USDC" "0xSeller" 5000 {} snap)
         after-raise  (:world (lc/raise-dispute world workflow-id "0xBuyer"))
         after-l0     (:world (res/execute-resolution
                               (time-ctx/with-temporal-context after-raise {:block-ts 1120})
                               workflow-id "0xL0" true "0xl0" nil))
         esc-fn1      (fn [_ _ _ _] {:ok true :new-resolver "0xL1"})
         after-esc1   (:world (res/escalate-dispute
                               (time-ctx/with-temporal-context after-l0 {:block-ts 1120})
                               workflow-id "0xBuyer" esc-fn1))
         after-l1     (:world (res/execute-resolution
                               (time-ctx/with-temporal-context after-esc1 {:block-ts 1180})
                               workflow-id "0xL1" false "0xl1" nil))
         esc-fn2      (fn [_ _ _ _] {:ok true :new-resolver "0xL2"})
         after-esc2   (:world (res/escalate-dispute
                               (time-ctx/with-temporal-context after-l1 {:block-ts 1180})
                               workflow-id "0xSeller" esc-fn2))
         after-l2     (:world (res/execute-resolution
                               (time-ctx/with-temporal-context after-esc2 {:block-ts 1240})
                               workflow-id "0xL2" true "0xl2" nil))]
     {:world after-l2
      :workflow-id workflow-id
      :steps {:after-l0 after-l0 :after-l1 after-l1 :after-l2 after-l2}})))

(defn build-reversal-world
  "Runs create → dispute → L0 release → escalation → L1 refund (reversal slash).

  opts — merged over [[base-opts]]; see [[s101-track1-reversal]] and
  [[participant-escalation-reversal]] for common presets.

  Returns {:world <after L1> :workflow-id n
           :steps {:after-raise :after-l0 :after-escalation}}."
  ([] (build-reversal-world {}))
  ([opts]
   (let [{:keys [initial-block-time buyer seller l0-resolver l1-resolver challenger
                 escalation-caller token escrow-amount l0-stake l1-stake escalation-via
                 l0-is-release l1-is-release l0-hash l1-hash snapshot block-times]}
         (merge base-opts s101-track1-reversal opts)
         context "build-reversal-world"
         snap (snap-fix/escrow-snapshot
               (merge {:appeal-window-duration 120
                       :challenge-window-duration 120
                       :reversal-slash-bps 2500
                       :max-dispute-level 2
                       :dispute-resolver l0-resolver}
                      snapshot))
         esc-caller (or escalation-caller
                        (case escalation-via
                          :escalate seller
                          :challenge challenger))
         world0 (-> (t/empty-world initial-block-time)
                    (reg/register-stake l0-resolver l0-stake)
                    (reg/register-stake l1-resolver l1-stake))
         create-r (require-ok! (lc/create-escrow world0 buyer token seller escrow-amount {} snap)
                               "create-escrow"
                               context)
         workflow-id (:workflow-id create-r)
         after-raise (:world (require-ok! (lc/raise-dispute (:world create-r) workflow-id buyer)
                                          "raise-dispute"
                                          context))
         after-l0 (:world (require-ok!
                           (res/execute-resolution (at-block-time after-raise block-times :l0)
                                                   workflow-id l0-resolver l0-is-release
                                                   l0-hash nil)
                           "l0-execute-resolution"
                           context))
         esc-r (escalate! after-l0 workflow-id esc-caller escalation-via l1-resolver
                          block-times context)
         after-escalation (:world esc-r)
         after-l1 (:world (require-ok!
                           (res/execute-resolution (at-block-time after-escalation block-times :l1)
                                                   workflow-id l1-resolver l1-is-release
                                                   l1-hash nil)
                           "l1-execute-resolution"
                           context))]
     {:world after-l1
      :workflow-id workflow-id
      :steps {:after-raise after-raise
              :after-l0 after-l0
              :after-escalation after-escalation}})))
