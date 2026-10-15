(ns notebooks.composed-pro-rata-v2-settlement
  {:nextjournal.clerk/toc true
   :nextjournal.clerk/dark-mode true
   :nextjournal.clerk/visibility {:code :fold :result :show}}
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [nextjournal.clerk :as clerk])
  (:import [java.nio.file Path Paths]))

;; # From Shortfall to Settlement
;; ## What does the composed pro-rata execution enforce?
;;
;; This notebook reads the retained `prf-evm/evidence/composed-pro-rata-v2-settlement`
;; fixture and runs its read-only validator. It does not calculate authoritative
;; status itself. Set `PRF_EVM_ROOT` to the root of the matching `prf-evm`
;; checkout if it is not found at `../../prf-evm` or `../prf-evm`.
;;
;; **Qualification boundary:** the SP1 proof and public-values profile are V1;
;; the allocator and operation-preparation components are V2; the reserve
;; lifecycle is V3. Execution was observed in local Foundry (chain ID 31337).
;; These call-boundary observations are not mined public-chain receipts.
;;
;; Visual description: the 100-unit request bar uses blue, teal and violet
;; segments for Alice, Bob and Carol's requested shares, followed by an amber
;; segment for the 30 unmet units. Green check marks identify specific validated
;; or locally observed checks. Amber warnings identify authority and network
;; limitations. Arrows show the fixture's causal order, not public-chain time.
;; The separate 10-unit restricted refund is shown outside the 70-unit claimant
;; allocation and is never added to Carol's 14-unit entitlement.

(defn- candidate-evm-roots []
  (let [env-root (System/getenv "PRF_EVM_ROOT")]
    (->> (concat (when (seq env-root) [env-root]) ["../../prf-evm" "../prf-evm"])
         (map #(-> (Paths/get % (make-array String 0)) .toAbsolutePath .normalize))
         (filter #(.exists (.toFile (.resolve ^Path % "scripts/verify-composed-pro-rata-v2.py")))))))

(def evm-root
  (or (first (candidate-evm-roots))
      (throw (ex-info "Set PRF_EVM_ROOT to the prf-evm checkout containing the retained composed fixture" {}))))

(def evidence-dir (.resolve ^Path evm-root "evidence/composed-pro-rata-v2-settlement"))
(def manifest (with-open [r (io/reader (.toFile (.resolve evidence-dir "manifest.json")))]
                (json/read r :key-fn keyword)))
(def validation-run
  (let [script (.toString (.resolve ^Path evm-root "scripts/verify-composed-pro-rata-v2.py"))
        {:keys [exit out err]} (shell/sh "python3" script :dir (.toFile evm-root))]
    (when-not (zero? exit)
      (throw (ex-info "Retained composed-fixture validator failed" {:exit exit :stderr err :stdout out})))
    {:exit exit :stderr err :result (json/read-str out :key-fn keyword)}))
(def validation (:result validation-run))

(when-not (= "PASS" (:status validation))
  (throw (ex-info "Validator did not return PASS" {:status (:status validation)})))
(when-not (= (:schema manifest) (:manifestSchema validation))
  (throw (ex-info "Validator and manifest schema identities differ" {})))

;; The validator checks every manifest-listed fixture hash, source/proof roots,
;; independent replays, Foundry observations, transfer recipients, accounting
;; equations and negative-test logs. The notebook only presents its result.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:border "1px solid #2f855a" :border-radius "12px" :padding "18px"
                :background "#10251f" :color "#e5f5ee"}}
  [:div {:style {:font-size "12px" :letter-spacing "0.08em" :text-transform "uppercase" :color "#86efac"}}
   "✓ Retained fixture validation passed"]
  [:div {:style {:font-size "24px" :font-weight 700 :margin-top "8px"}}
   "100 requested · 70 available · 30 unmet"]
  [:div {:style {:margin-top "8px" :color "#b8c7c0"}}
   "35 / 21 / 14 independently reproduced, admitted and locally withdrawn"]
  [:div {:style {:margin-top "12px" :font-family "monospace" :font-size "12px" :overflow-wrap "anywhere"}}
   "Operation " (:operationId validation)]])

;; ## 1. The shortfall — the claim set exceeds available capital
;;
;; The retained source witness declares the three requests and 70 available
;; units under the committed policy. The 30 units are unmet requests in this
;; fixture, not a recognized loss or deferred claim. The 10 restricted reserve
;; units are separate from both available allocation capital and claimant
;; entitlements.

(def requests (get-in manifest [:economicInputs :requests]))
(def inputs (:inputs validation))
(def allocation (:calculatedAllocations validation))

(clerk/table
 {:head ["Recipient" "Requested" "Calculated allocation" "Unfilled request"]
  :rows (mapv (fn [{:keys [participant claimId requested unfilled]}]
                [participant requested (get allocation (keyword claimId)) unfilled]) requests)})

(defn- share-segment [color width label amount]
  [:div {:style {:width (str width "%") :min-width "20px" :height "28px"
                 :background color :display "flex" :align-items "center"
                 :justify-content "center" :color "#fff" :font-size "12px"
                 :font-weight 700 :overflow "hidden"}}
   (str label " " amount)])

(def request-bar
  (let [total (:requested inputs)
        colors ["#3978b8" "#168c83" "#7656a5"]
        allocated-segments
        (mapv (fn [request color]
                (let [amount (get allocation (keyword (:claimId request)))]
                  (share-segment color (* 100.0 (/ amount total)) (:participant request) amount)))
              requests colors)
        unmet-segment (share-segment "#d89b28" (* 100.0 (/ (:unmet inputs) total))
                                     "Unmet" (:unmet inputs))]
    (clerk/html
     [:div
      (into [:div {:style {:display "flex" :width "100%" :border-radius "6px" :overflow "hidden"}}]
            (conj allocated-segments unmet-segment))
      [:div {:style {:display "flex" :justify-content "space-between" :font-size "12px" :margin-top "6px"}}
       [:span (str (get-in validation [:reconciliation :claimSettlement :fundedToAllocator])
                   " allocated from available capital")]
       [:span (str (:unmet inputs) " unmet")]]
      [:div {:style {:font-size "12px" :color "#9aa7b2" :margin-top "8px"}}
       (str "Restricted reserve capital (" (get-in manifest [:economicInputs :protectedRestrictedCapital])
            ") is outside this allocation bar.")]])))

request-bar

;; ## 2. Proof — bind the calculation to this source and operation
;;
;; Clojure and Rust source-bound replay projections match. The retained SP1
;; Groth16 proof was accepted by the local EVM verifier and admitted under the
;; retained registry/configuration roots. This establishes the proof's declared
;; claim and identity in the tested local context; it does not authenticate the
;; real-world truth or completeness of requests.

(clerk/table
 {:head ["Evidence item" "Retained identity / result"]
  :rows [["Source root" (:sourceRoot manifest)]
         ["Prepared operation root" (:preparedOperationRoot manifest)]
         ["Reserve snapshot" (:reserveStateCommitment manifest)]
         ["Program profile" (get-in manifest [:proofIdentity :programId])]
         ["Public-values schema" (get-in manifest [:proofIdentity :publicValuesSchema])]
         ["Verification / admission" (get-in manifest [:qualification :positiveResult])]
         ["Independent replay" "✓ Clojure and Rust projections match"]]})

;; ## 3. Authority — the protected route gates funding
;;
;; The local execution passes through proof verification, registry admission,
;; operation ratification and the AO execution caller. The diagram is linear:
;; each arrow means the next operation consumes the prior admitted context.
;; Green denotes the retained local pass. Amber denotes that the authority is
;; fixture-appointed and has not been externally authenticated.

(clerk/html
 [:div {:style {:display "flex" :flex-wrap "wrap" :align-items "center" :gap "8px"}}
  (for [label ["Source committed" "→" "Proof verified + admitted" "→"
               "Operation authorized" "→" "70 funded through AO route"]]
    (if (= label "→")
      [:span {:style {:color "#8b98a5" :font-size "20px"}} label]
      [:span {:style {:border "1px solid #35725b" :border-radius "8px" :padding "9px 12px"
                      :color "#b7efcf" :background "#11271f"}}
       "✓ " label]))])

;; ⚠ Local fixture authority only. The retained test establishes route behavior
;; for this fixture, not production authority or an externally authenticated signer.

;; ## 4. Settlement — compare calculated, funded and locally observed amounts
;;
;; Arrows connect three distinct facts. The first is reconstructed calculation,
;; the second is the amount funded as claimant liability, and the third is the
;; token transfer observed during local Foundry execution. These are not public
;; chain receipts.

(def withdrawals (:localObservedWithdrawals validation))
(clerk/table
 {:head ["Recipient" "Calculated" "Funded entitlement" "Local observed withdrawal"]
  :rows (mapv (fn [[request amount withdrawn]]
                [(:participant request) amount amount withdrawn])
              (map vector requests (map #(get allocation (keyword (:claimId %))) requests) withdrawals))})

(clerk/html
 [:div {:style {:border-left "3px solid #738092" :padding-left "14px" :margin "16px 0"}}
  [:div "Allocator: " [:strong "Closed"] "  →  Reserve: " [:strong "Finalized"]]
  [:div {:style {:font-size "13px" :color "#9aa7b2" :margin-top "5px"}}
   "The allocator's claimant lifecycle closes before the reserve's separate restricted-capital finalization."]])

;; ## 5. Accounting — liabilities decline as claims are paid
;;
;; The snapshots and transfer observations are locally observed at Foundry
;; call boundaries. The derived journal checks custody at each snapshot and
;; claimant liability as claims are paid. Carol's 14-unit entitlement withdrawal
;; and separate 10-unit restricted refund remain distinct.

(def liability-stages
  (filter #(contains? #{"activated" "after_alice_claim" "after_bob_claim" "allocator_closed"}
                       (get-in % [:observation :stage]))
          (:stateProjections validation)))

(clerk/table
 {:head ["Stage" "Outstanding funded liability" "Evidence class"]
  :rows (mapv (fn [state]
                [(get-in state [:observation :stage])
                 (get-in state [:observation :outstandingLiability])
                 "Local Foundry observation"])
              liability-stages)})

(clerk/html
 [:div {:style {:display "grid" :grid-template-columns "repeat(auto-fit,minmax(180px,1fr))" :gap "10px"}}
  [:div {:style {:padding "12px" :border "1px solid #356b57" :border-radius "8px"}}
   "✓ Claimant transfers: 35 + 21 + 14 = 70"]
  [:div {:style {:padding "12px" :border "1px solid #356b57" :border-radius "8px"}}
   "✓ Requests: 100 = 70 allocated + 30 unmet"]
  [:div {:style {:padding "12px" :border "1px solid #d0a33a" :border-radius "8px"}}
   "⚠ Restricted refund: separate 10 to fixture-recorded Carol beneficiary"]])

;; ## 6. Falsification — attempts that the protected path rejects
;;
;; Red/cross denotes a demonstrated negative test, not a general statement that
;; every possible attack is prevented. Each listed case is from the retained
;; local test log and validator output. The fresh-proof recovery branch is
;; separate: it recovers claimant settlement but an unrelated 1-unit reserve
;; hold correctly blocks terminal finalization.

(clerk/table
 {:head ["Rejected attempt" "Result"]
  :rows (mapv (fn [test-name] [test-name "✓ Rejected in local Foundry test"])
              (:negativeTestsPassed validation))})

(clerk/html
 [:div {:style {:border "1px solid #d0a33a" :border-radius "8px" :padding "14px"
                :background "#282318"}}
  [:strong "⚠ Recovery boundary"]
  [:div {:style {:margin-top "6px"}}
   "A fresh source-bound proof restores the same claimant settlement after stale assurance; the remaining 1-unit hold prevents reserve finalization, with state unchanged."]])

;; ## Qualification summary
;;
;; **Independently reproduced:** Clojure and Rust reconstruct the declared
;; allocation. **Cryptographically verified:** the retained SP1 Groth16 proof
;; is verified/admitted in the local Foundry context. **Locally observed:**
;; contract state and token transfers at test call boundaries.
;;
;; **Not established:** public-chain deployment or mined receipts; external
;; authentication of fixture authorities; truth/completeness of requests
;; outside the fixture; Carol's external legal beneficial ownership; or behavior
;; beyond the tested source, contracts, dependencies and adversarial cases.

(clerk/table
 {:head ["Qualification" "Status"]
  :rows [["Source-bound allocation replay" "✓ Independently reproduced"]
         ["SP1 proof and local admission" "✓ Cryptographically verified locally"]
         ["Funding, withdrawals and reserve finalization" "✓ Locally observed"]
         ["Public-chain execution / mined receipts" "⚠ Not established"]
         ["External authority and request authentication" "⚠ Not established"]]})

;; ## Reproduce independently
;;
;; Run `python3 scripts/verify-composed-pro-rata-v2.py` in the pinned `prf-evm`
;; checkout. It validates the evidence manifest and emits the machine-readable
;; result consumed above. See `evidence/composed-pro-rata-v2-settlement/README.md`
;; for the exact Foundry commands, dependency identities and adversarial matrix.
;;
;; The site publishes a linked evidence-lineage view for this exact projection:
;; [Open the evidence path, supporting records and open qualifications](../../demos/composed-pro-rata-settlement/#evidence-lineage).
