;; # Appealed Escrow Walkthrough — A Third-Party Challenge
;;
;; A deterministic, notebook-local example of challenge-resolution. This is not
;; a scenario-registry entry or a canonical benchmark run.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :hide :result :show}}
(ns notebooks.appealed-escrow-walkthrough
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.notebook-support.ui :as ui]
            [resolver-sim.protocols.sew.types :as types]
            [resolver-sim.protocols.sew.lifecycle :as lifecycle]
            [resolver-sim.protocols.sew.resolution :as resolution]
            [resolver-sim.protocols.sew.accounting :as accounting]
            [resolver-sim.time.context :as time-ctx]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def appealed-escrow-example
  {:example/id :example/third-party-appealed-escrow
   :escrow/id :escrow/appealed-example-001
   :workflow-id 0
   :token :TEST
   :principal 10000
   :sender :actor/alice
   :recipient :actor/bob
   :resolver/l0 :resolver/alpha
   :challenger :actor/carol
   :resolver/l1 :resolver/beta
   :addresses {:actor/alice "0xalice"
               :actor/bob "0xbob"
               :resolver/alpha "0xresolver-alpha"
               :actor/carol "0xcarol"
               :resolver/beta "0xresolver-beta"}
   :labels {:actor/alice "Alice — escrow sender"
            :actor/bob "Bob — escrow recipient"
            :resolver/alpha "Resolver Alpha — L0 resolver"
            :actor/carol "Carol — unrelated third-party challenger"
            :resolver/beta "Resolver Beta — L1 resolver"}
   :times {:funded 1000 :disputed 1010 :l0-resolution 1020
           :challenge 1030 :l1-resolution 1040 :settlement 1100}
   :snapshot {:escrow-fee-bps 0
              :appeal-window-duration 60
              :challenge-bond-bps 500
              :appeal-bond-protocol-fee-bps 150
              :max-dispute-duration 3600}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- addr [role]
  (get-in appealed-escrow-example [:addresses role]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- at-time [world timestamp]
  (time-ctx/advance-time world {:to timestamp}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- project-world [world]
  (let [wf (:workflow-id appealed-escrow-example)
        token (:token appealed-escrow-example)
        escrow (get-in world [:escrow-transfers wf])
        pending (get-in world [:pending-settlements wf])
        superseded (last (get-in world [:superseded-pending-settlements wf]))]
    {:escrow/status (:escrow-state escrow)
    ;; :total-held includes generic challenge-bond custody. Keep escrow principal
    ;; separate so the walkthrough does not mistake Carol's bond for principal.
    :principal/held (if (= :disputed (:escrow-state escrow)) (:amount-after-fee escrow) 0)
    :principal/amount-after-fee (:amount-after-fee escrow)
    :custody/total-held (get-in world [:total-held token] 0)
     :dispute/level (get-in world [:dispute-levels wf] 0)
     :resolver/current (:dispute-resolver escrow)
     :pending/active? (:exists pending)
     :pending/outcome (when (:exists pending) (if (:is-release pending) :release-to-bob :refund-to-alice))
     :challenge/deadline (:appeal-deadline pending)
     :pending/superseded? (boolean superseded)
     :pending/superseded-level (:level superseded)
     :bond/gross-posted (get-in world [:total-bonds-posted token] 0)
     :bond/fee (get-in world [:bond-fees token] 0)
     :bond/net-held (get-in world [:bond-balances wf (addr :actor/carol)] 0)
     :bond/owner (when (pos? (get-in world [:bond-balances wf (addr :actor/carol)] 0)) (addr :actor/carol))
     :final/claimable-alice (get-in world [:claimable wf (addr :actor/alice)] 0)
     :final/claimable-bob (get-in world [:claimable wf (addr :actor/bob)] 0)
     :final/claimable-carol (get-in world [:claimable wf (addr :actor/carol)] 0)
     :evidence/refs [:escrow-created :dispute-raised :resolution-challenged
                     :bond-posted :resolution-executed :settlement-executed]}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn build-appealed-escrow-world []
  (let [{:keys [workflow-id token principal snapshot times]} appealed-escrow-example
        alice (addr :actor/alice)
        bob (addr :actor/bob)
        alpha (addr :resolver/alpha)
        carol (addr :actor/carol)
        beta (addr :resolver/beta)
        initial (types/empty-world (:funded times))
        create (lifecycle/create-escrow initial alice token bob principal
                                        {:custom-resolver nil :yield-preset :off}
                                        (assoc snapshot :dispute-resolver alpha))
        w1 (:world create)
        dispute (lifecycle/raise-dispute (at-time w1 (:disputed times)) workflow-id alice)
        w2 (:world dispute)
        l0 (resolution/execute-resolution (at-time w2 (:l0-resolution times)) workflow-id alpha true "l0-release" nil)
        w3 (:world l0)
        before-challenge (project-world w3)
        challenge (resolution/challenge-resolution
                   (at-time w3 (:challenge times)) workflow-id carol
                   (fn [_world _workflow-id _caller level]
                     (if (= level 0) {:ok true :new-resolver beta} {:ok false :error :unexpected-level})))
        w4 (:world challenge)
        after-challenge (project-world w4)
        l1 (resolution/execute-resolution (at-time w4 (:l1-resolution times)) workflow-id beta false "l1-refund" nil)
        w5 (:world l1)
        before-settlement (project-world w5)
        settlement (resolution/execute-pending-settlement (at-time w5 (:settlement times)) workflow-id)
        w6 (:world settlement)
        bond-return (accounting/return-bond w6 workflow-id carol)
        w7 (:world bond-return)]
    {:steps [{:step/id :fund
              :title "Alice funds the escrow"
              :actor :actor/alice :action :create-escrow
              :reader-explanation "Alice locks principal for Bob under Resolver Alpha."
              :expected-effects ["Principal held" "Workflow created"]
              :result create :before (project-world initial) :after (project-world w1)}
             {:step/id :dispute
              :title "Alice raises a dispute"
              :actor :actor/alice :action :raise-dispute
              :reader-explanation "The escrow enters the dispute state and Alpha becomes active resolver."
              :expected-effects ["Escrow disputed"]
              :result dispute :before (project-world w1) :after (project-world w2)}
             {:step/id :l0-provisional
              :title "Resolver Alpha issues an L0 provisional release"
              :actor :resolver/alpha :action :execute-resolution
              :reader-explanation "The proposed release to Bob is provisional; no principal has moved."
              :expected-effects ["Pending settlement" "Challenge window opens"]
              :result l0 :before (project-world w2) :after before-challenge}
             {:step/id :carol-challenge
              :title "Carol challenges the provisional resolution"
              :actor :actor/carol :action :challenge-resolution
              :reader-explanation "Carol is unrelated to the escrow and pays a separate generic challenge bond."
              :expected-effects ["L0 pending settlement superseded" "Level advances to L1" "Fee and net custody recorded"]
              :result challenge :before before-challenge :after after-challenge}
             {:step/id :l1-provisional
              :title "Resolver Beta issues an L1 provisional refund"
              :actor :resolver/beta :action :execute-resolution
              :reader-explanation "L1 materially changes the L0 outcome from release-to-Bob to refund-to-Alice."
              :expected-effects ["New pending settlement" "New challenge window"]
              :result l1 :before after-challenge :after before-settlement}
             {:step/id :settle
              :title "The L1 window closes and settlement executes"
              :actor :system/keeper :action :execute-pending-settlement
              :reader-explanation "Principal moves only after the L1 provisional result reaches finality."
              :expected-effects ["Escrow refunded" "Principal no longer held"]
              :result settlement :before before-settlement :after (project-world w6)}
             {:step/id :return-challenge-bond
              :title "Carol’s successful challenge bond is returned"
              :actor :system/protocol :action :return-bond
              :reader-explanation "The generic net bond is returned to Carol as claimable after the successful correction path."
              :expected-effects ["Generic bond custody cleared" "Carol credited with net bond"]
              :result bond-return :before (project-world w6) :after (project-world w7)}]
     :before-challenge before-challenge
     :after-challenge after-challenge
     :before-settlement before-settlement
     :final (project-world w7)}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def walkthrough (delay (build-appealed-escrow-world)))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "# Appealed Escrow Walkthrough — A Third-Party Challenge

This reader-facing title uses “appealed escrow,” but the executed protocol
operation is ``challenge-resolution``. Carol challenges a **provisional escrow
resolution**; she does not submit, fund, or control a resolver's
``appeal-slash`` fraud-slash appeal.")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [before-challenge after-challenge final]} @walkthrough]
  (clerk/html
   [:div
    [:h2 "1. The case in one screen"]
    [:table
     [:tbody
      [:tr [:th "Escrow"] [:td "Appealed Example 001 (workflow 0)"]]
      [:tr [:th "Parties"] [:td "Alice → Bob"]]
      [:tr [:th "Principal"] [:td (str (:principal appealed-escrow-example) " TEST token units")]]
      [:tr [:th "L0 outcome"] [:td (str (:pending/outcome before-challenge) " by Resolver Alpha")]]
      [:tr [:th "Third-party challenger"] [:td "Carol"]]
      [:tr [:th "Challenge bond"] [:td (str (:bond/gross-posted after-challenge) " gross; fee " (:bond/fee after-challenge) "; net custody " (:bond/net-held after-challenge))]]
      [:tr [:th "L1 resolver / final outcome"] [:td (str "Resolver Beta → escrow " (:escrow/status final) ", Alice claimable " (:final/claimable-alice final))]]]]
    [:p [:strong "Carol can fund and initiate the correction path without owning the escrow, becoming the resolver, or gaining authority over the final ruling."]]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 2. Two different correction rights")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:table
  [:thead [:tr [:th "Mechanism"] [:th "Eligible caller"] [:th "Subject being challenged"] [:th "Bond path"] [:th "Decision authority"]]]
  [:tbody
   [:tr [:td "Resolver slash appeal"] [:td "Slashed resolver only"] [:td "Fraud slash"] [:td "Slash-scoped custody"] [:td "Governance"]]
   [:tr [:td [:strong "Resolution challenge — executed here"]] [:td "Any valid participant or third party"] [:td "Provisional escrow resolution"] [:td "Generic fee-bearing challenge-bond custody"] [:td "Next-level resolver"]]]])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 3. Escrow lifecycle timeline

```mermaid
timeline
  title Third-party provisional-resolution challenge
  1000 : Escrow funded
  1010 : Dispute raised
  1020 : L0 provisional release to Bob
  1030 : Carol posts challenge bond; L0 settlement superseded; L1 begins
  1040 : L1 provisional refund to Alice
  1100 : L1 window closed; settlement executed
```")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [before-challenge]} @walkthrough]
  (clerk/html
   [:div
    [:h2 "4. The provisional L0 outcome"]
    [:table [:tbody
             [:tr [:th "Proposed allocation"] [:td (name (:pending/outcome before-challenge))]]
             [:tr [:th "Challenge deadline"] [:td (str (:challenge/deadline before-challenge))]]
             [:tr [:th "Current dispute level"] [:td (str "L" (:dispute/level before-challenge))]]
             [:tr [:th "Current resolver"] [:td (:resolver/current before-challenge)]]
             [:tr [:th "Pending settlement"] [:td (str (:pending/active? before-challenge))]]
             [:tr [:th "Principal still held"] [:td (str (:principal/held before-challenge))]]]]
    [:p "The L0 resolution is provisional. It proposes an allocation but does not transfer principal until its applicable challenge window closes."]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [after-challenge]} @walkthrough]
  (clerk/html
   [:div
    [:h2 "5. Carol’s challenge"]
    [:table [:tbody
             [:tr [:th "Actor"] [:td "Carol — unrelated third party"]]
             [:tr [:th "Action"] [:td "challenge-resolution"]]
             [:tr [:th "Time relative to deadline"] [:td (str (:challenge appealed-escrow-example) " < " (:challenge/deadline after-challenge))]]
             [:tr [:th "Gross challenge bond"] [:td (str (:bond/gross-posted after-challenge))]]
             [:tr [:th "Protocol fee"] [:td (str (:bond/fee after-challenge))]]
             [:tr [:th "Net challenge-bond custody"] [:td (str (:bond/net-held after-challenge) " owned by Carol")]]
             [:tr [:th "Result"] [:td (str "L" (:dispute/level after-challenge) "; resolver " (:resolver/current after-challenge))]]]]
    [:p "Carol’s bond is separate from the 10,000-token escrow principal."]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [before-challenge after-challenge]} @walkthrough]
  (clerk/html
   [:div
    [:h2 "6. Atomic state transition"]
    [:table
     [:thead [:tr [:th "Property"] [:th "Before challenge"] [:th "After challenge"]]]
     [:tbody
      [:tr [:td "Pending settlement"] [:td (str "Active L" (:dispute/level before-challenge))] [:td (if (:pending/superseded? after-challenge) "Superseded" "Not superseded")]]
      [:tr [:td "Dispute level"] [:td (str "L" (:dispute/level before-challenge))] [:td (str "L" (:dispute/level after-challenge))]]
      [:tr [:td "Current resolver"] [:td (:resolver/current before-challenge)] [:td (:resolver/current after-challenge)]]
      [:tr [:td "Escrow principal"] [:td (str (:principal/held before-challenge) " held")] [:td (str (:principal/held after-challenge) " still held")]]
      [:tr [:td "Carol’s challenge bond"] [:td "None"] [:td (str "Fee " (:bond/fee after-challenge) "; net held " (:bond/net-held after-challenge))]]
      [:tr [:td "Financial finality"] [:td "Not reached"] [:td "Still not reached"]]]]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [before-settlement final]} @walkthrough]
  (clerk/html
   [:div
    [:h2 "7. L1 resolution and 8. finality"]
    [:p (str "Resolver Beta changes the provisional outcome from " (:pending/outcome (:before-challenge @walkthrough))
             " to " (:pending/outcome before-settlement) ". This demonstrates a correction path, not that every challenge is correct.")]
    [:table [:tbody
             [:tr [:th "L1 challenge deadline"] [:td (str (:challenge/deadline before-settlement))]]
             [:tr [:th "Settlement time"] [:td (str (:settlement (:times appealed-escrow-example)))]]
             [:tr [:th "Final escrow status"] [:td (name (:escrow/status final))]]
             [:tr [:th "Final principal allocation"] [:td (str "Alice claimable: " (:final/claimable-alice final) "; Bob claimable: " (:final/claimable-bob final))]]
             [:tr [:th "Challenge-bond outcome"] [:td (str "Carol net-bond claimable: " (:final/claimable-carol final))]]]]
    [:p "The challenge itself did not transfer principal. The L1 provisional result became executable only after its own window closed."]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [after-challenge final]} @walkthrough]
  (clerk/html
   [:div
    [:h2 "9. Accounting reconciliation"]
    [:table
     [:thead [:tr [:th "Flow"] [:th "Amount / owner"] [:th "State at end"]]]
     [:tbody
      [:tr [:td "Escrow principal"] [:td (str (:principal appealed-escrow-example) " TEST")]
       [:td (str "No longer escrow-held; allocated to Alice as " (:final/claimable-alice final))]]
      [:tr [:td "Challenge-bond gross amount"] [:td (str (:bond/gross-posted after-challenge) " posted by Carol")] [:td "Recorded cumulatively"]]
      [:tr [:td "Challenge fee"] [:td (str (:bond/fee after-challenge))] [:td "Recorded in generic :bond-fees"]]
      [:tr [:td "Net challenge-bond custody"] [:td (str (:bond/net-held after-challenge) " owned by Carol")] [:td (str "Returned to Carol as claimable " (:final/claimable-carol final) "; final generic balance " (:bond/net-held final))]]
      [:tr [:td "Slash-appeal bond"] [:td "None in this walkthrough"] [:td "Not combined with challenge accounting"]]]]
    [:p "Limit: this walkthrough shows separate observed ledgers. It does not establish full per-bond conservation or a completed generic challenge-bond return/slash outcome."]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [before-challenge after-challenge before-settlement final]} @walkthrough
      wf (:workflow-id appealed-escrow-example)
      checks [["Third-party challenge accepted" (true? (get-in (nth (:steps @walkthrough) 3) [:result :ok]))]
              ["Challenge occurred before deadline" (< (:challenge (:times appealed-escrow-example)) (:challenge/deadline before-challenge))]
              ["Prior provisional resolution existed" (:pending/active? before-challenge)]
              ["Prior pending settlement superseded" (:pending/superseded? after-challenge)]
              ["Dispute advanced exactly one level" (= 1 (- (:dispute/level after-challenge) (:dispute/level before-challenge)))]
              ["Principal did not move during challenge" (= (:principal/held before-challenge) (:principal/held after-challenge))]
              ["Challenge bond separate from escrow custody" (and (pos? (:bond/net-held after-challenge)) (= (:principal/held before-challenge) (:principal/held after-challenge)))]
              ["Generic bond fee recorded" (pos? (:bond/fee after-challenge))]
              ["L1 produced a new provisional outcome" (not= (:pending/outcome before-challenge) (:pending/outcome before-settlement))]
              ["Settlement did not occur before finality" (>= (:settlement (:times appealed-escrow-example)) (:challenge/deadline before-settlement))]
              ["Final allocation matches executed settlement" (and (= :refunded (:escrow/status final)) (= (:principal appealed-escrow-example) (:final/claimable-alice final)))]
              ["Successful challenge bond returned" (= (:bond/net-held after-challenge) (:final/claimable-carol final))]
              ["Relevant custody amounts are non-negative" (every? #(>= % 0) [(:bond/fee after-challenge) (:bond/net-held after-challenge) (:bond/net-held final)])]]]
  (clerk/html
   [:div
    [:h2 "10. Walkthrough checks"]
    [:table
     [:thead [:tr [:th "Check"] [:th "Result"]]]
     [:tbody (for [[label passed?] checks]
               [:tr [:td label] [:td (if passed? "PASS" "FAIL")]])]]]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(let [{:keys [before-challenge]} @walkthrough]
  (clerk/md (str "## 11. Counterfactual without the challenge\n\n"
                 "Had Carol not challenged, the active L0 pending settlement proposing ``"
                 (name (:pending/outcome before-challenge)) "`` would have become eligible for execution at its deadline ``"
                 (:challenge/deadline before-challenge) "``. This is a derived explanation from the pre-challenge snapshot; the notebook does not execute a second mutable branch.")))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md "## 12. What this example proves and does not prove

**Demonstrates:** third-party standing for a provisional-resolution challenge;
capital commitment through a separate challenge bond; fee/custody separation;
supersession of the L0 settlement; one-level sequential escalation; preservation
of principal during the challenge; and a higher-level correction path.

**Does not prove:** optimal calibration, aggregate deterrence, affordability,
statistical decision-quality improvement, full per-bond conservation, or live
Kleros/external-arbitration integration.")

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/md (str "---\n\n**Illustrative-evidence footer**  \n"
               "Example ID: ``" (:example/id appealed-escrow-example) "``  \n"
               "Escrow reference: ``" (:escrow/id appealed-escrow-example) "`` / workflow ``" (:workflow-id appealed-escrow-example) "``  \n"
               "Protocol snapshot: notebook-local deterministic map (challenge bond 500 bps; generic bond fee 150 bps; window 60 modeled seconds).  \n"
               "Modeled execution time: deterministic block times 1000–1100.  \n"
               "Wall-clock time: intentionally not recorded; re-evaluation is deterministic.  \n"
               "This is local illustrative evidence, not a canonical benchmark run.\n"))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(ui/notebook-navigation "Appeal Analysis")
