;; # Not Admitted — admission boundary, not application workflow
;;
;; ## How do generic admission boundaries behave?
;;
;; The framework provides small, composable checks. An application configures
;; them with its own policy and data; it owns the surrounding process.
;;
;; The concrete amount-tampering walkthrough is intentionally notebook-only in
;; `notebooks/demo_not_admitted`. The clean-room corpus is separately shown in
;; `notebooks/clean_room_not_admitted`.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :hidden :result :show}}
(ns notebooks.not-admitted
  (:require [nextjournal.clerk :as clerk]

            [resolver-sim.assurance.admission-fixed-point :as afp]
            [resolver-sim.assurance.custody :as custody]
            [resolver-sim.assurance.force-authorisation :as force-auth]
            [resolver-sim.composition.combination :as combination]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.resubmission.chain :as chain]
            [resolver-sim.resubmission.store :as store]))

;; ## The reusable boundaries

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- admit-request
  "Request shape for `chain/admit!`. Keyword args: :r :seq :parent :link :idem :basis :version."
  [& {:keys [r seq parent link idem basis version]}]
  (cond-> {:receipt-hash r :sequence seq :parent-receipt-hash parent
           :link-hash link :idempotency-key idem :basis-root basis}
     version (assoc :expected-chain-version version)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- configured-chain
  "A chain with a disposition public key (required for signed admission)."
  [family-id]
  (chain/new-chain family-id "sha256:0000000000000000000000000000000000000000000000000000000000000000"))

;; ## Held-custody evidence helpers (notebook-local)

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- build-chained-artifacts
  "Build held-custody artifacts from a held-adjustment ledger, wiring
   :held/previous-artifact-hash chain links in canonical order so that
   :held-custody/predecessor-continuity and :held-custody/sequence-replay
   are exercisable."
  [evidence-input]
  (reduce (fn [acc adjustment]
            (let [prev-hash (when (seq acc)
                              (:artifact/hash (last acc)))
                  art (custody/build-held-custody-artifact
                       (assoc adjustment :held/previous-artifact-hash prev-hash))]
              (conj acc art)))
          []
          (sort-by custody/held-adjustment-order evidence-input)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- escrow-adjustment
  "Build a canonical escrow-held adjustment. dir: :in|:out, before/after derived."
  [id dir amount before after reason]
  {:held-adjustment/id id
   :held/direction dir :token :USDC :amount (long amount)
   :held/before (long before) :held/after (long after)
   :held/account :escrow-principal :owner/address "0xalice"
   :held/workflow-id 1
   :held/reason reason
   :held/position-id [:held/position :USDC :escrow-principal 1]})

;; ## Multi-adjustment evidence (deposit + release, chained)

(def multi-adjustment-evidence
  [(escrow-adjustment "held-adjustment-1" :in 1000 0 1000 :escrow-principal-deposited)
   (escrow-adjustment "held-adjustment-2" :out 400 1000 600 :escrow-settlement-released)])

(def multi-adjustment-baseline-artifacts
  (build-chained-artifacts multi-adjustment-evidence))

(def multi-adjustment-baseline
  (afp/admission-fixed-point
   multi-adjustment-baseline-artifacts multi-adjustment-evidence))

(def multi-adjustment-tampered-artifacts
  "Tamper adjustment 2's amount (400→500) and held/after (600→500).
   local-delta still passes (1000−500=500), but hash-integrity fails
   because :artifact/hash was committed to amount 400."
  (update multi-adjustment-baseline-artifacts 1
          #(assoc % :amount 500 :held/after 500)))

(def multi-adjustment-tampered
  (afp/admission-fixed-point
   multi-adjustment-tampered-artifacts multi-adjustment-evidence))

;; ## Multi-epoch evidence (sequential snapshots)

(def multi-epoch-evidence
  {0 [(escrow-adjustment "held-adjustment-1" :in 1000 0 1000 :escrow-principal-deposited)]

   1 [(escrow-adjustment "held-adjustment-1" :in 1000 0 1000 :escrow-principal-deposited)
      (escrow-adjustment "held-adjustment-2" :out 400 1000 600 :escrow-settlement-released)]

   2 [(escrow-adjustment "held-adjustment-1" :in 1000 0 1000 :escrow-principal-deposited)
      (escrow-adjustment "held-adjustment-2" :out 400 1000 600 :escrow-settlement-released)]

   3 [(escrow-adjustment "held-adjustment-1" :in 1000 0 1000 :escrow-principal-deposited)
      (escrow-adjustment "held-adjustment-2" :out 400 1000 600 :escrow-settlement-released)
      (escrow-adjustment "held-adjustment-3" :in 200 600 800 :escrow-principal-deposited)]})

(defn- epoch-artifacts
  "Build artifacts for a given epoch. Epoch 2 reuses epoch 1's evidence
   ledger but tampers adjustment 2's amount (400→500, after 600→500),
   so hash-integrity fails while local-delta still passes."
  [epoch]
  (let [evidence (get multi-epoch-evidence epoch)
        arts (build-chained-artifacts evidence)]
    (if (= epoch 2)
      (update arts 1 #(assoc % :amount 500 :held/after 500))
      arts)))

(def multi-epoch-results
  (vec
   (for [epoch (range 4)]
     (let [evidence (get multi-epoch-evidence epoch)
           arts (epoch-artifacts epoch)
           proj (afp/verify-and-project arts evidence)]
       (assoc proj :epoch epoch :tampered? (= epoch 2))))))

(def boundary-results
  (let [scope {:authorization/id "example-release-42"
               :authorization/type :force-authorisation
               :held/direction :out :token :USDC :amount 40
               :held/account :escrow-principal :owner/address "0xExampleUser"
               :held/reason :application-release :held/workflow-id 42
               :held/position-id [:held/position :USDC :escrow-principal 42]
               :parameter/context {:parameter-context/type :protocol-parameters
                                   :parameter-context/root "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                                   :parameter-context/version 1}
               :parameter/address {:parameter/id :sew/escrow-principal}}
          record {:authorization/id "example-release-42" :authorization/status :active
                  :consumed? false :starts-at 0 :authorization/scope scope
                  :authorization/scope-hash (force-auth/force-authorisation-scope-hash scope)}
          valid-scope-result (force-auth/verify-authorisation-usable record {} scope 0)
          tampered-scope (assoc scope :held/reason :application-refund)
          tampered-result (force-auth/verify-authorisation-usable record {} tampered-scope 0)
          malformed-scope (dissoc scope :parameter/address)
          malformed-result (force-auth/verify-authorisation-usable record {} malformed-scope 0)
          unconfigured-chain (chain/new-chain "sha256:example-family")
          unconfigured-result (chain/admit! unconfigured-chain {})

          configured-c (configured-chain "sha256:chain-family")
          req-a (admit-request :r :receipt-a :seq 1 :parent nil :link "sha256:link-a" :idem "sha256:idem-a" :basis "sha256:basis-a")
          result-a (binding [chain/*admit-compat-guard* nil]
                     (chain/admit-compat! configured-c req-a))
          req-b (admit-request :r :receipt-b :seq 2 :parent :receipt-a :link "sha256:link-b" :idem "sha256:idem-b" :basis "sha256:basis-b")
          result-b (binding [chain/*admit-compat-guard* nil]
                     (chain/admit-compat! configured-c req-b))
          head-after-b (store/chain-head configured-c)

          wrong-parent-req (admit-request :r :r4 :seq 3 :parent :receipt-a :link "sha256:link-4" :idem "sha256:idem-4" :basis "sha256:basis-4")
          wrong-parent-result (binding [chain/*admit-compat-guard* nil]
                                (chain/admit-compat! configured-c wrong-parent-req))

          pipeline {:combination/id :application-pipeline
                    :combination/version 1
                    :combination/nodes [{:node/id :collect
                                         :capability/ref [:example/collect :v1]
                                         :capability/version 1}
                                        {:node/id :verify
                                         :capability/ref [:example/verify :v1]
                                         :capability/version 1}]
                    :combination/input {:schema-ref :example/input :semantic-type :example/value}
                    :combination/expected-output {:schema-ref :example/output :semantic-type :example/value}}
          branched (assoc pipeline :combination/edges [{:from :collect :to :verify}
                                             {:from :collect :to :other}])]
       [{:boundary "Scoped authority"
         :framework-role "Checks that supplied action data stays inside an application's declared scope."
         :expected? (:valid? valid-scope-result)
         :fail-closed? (and (not (:valid? tampered-result))
                            (contains? (set (map :code (:errors tampered-result)))
                                       :scope-hash-mismatch)
                            (contains? (set (map :code (:errors tampered-result)))
                                       :scope-mismatch))
         :observation "TAMPERED SCOPE REJECTED"}
        {:boundary "Parameter attribution must be complete"
         :framework-role "Rejects a scope where :parameter/context is supplied without :parameter/address."
         :expected? (not (:valid? malformed-result))
         :fail-closed? (some #(= :invalid-parameter-attribution (:code %)) (:errors malformed-result))
         :application-owns "Application owns which parameter context/address identifies its intended parameters."}
        {:boundary "Unconfigured chain fails closed"
         :framework-role "Refuses admission when no trusted receipt authority is configured."
         :expected? (= :not-admitted (:admission-status unconfigured-result))
         :fail-closed? (= :receipt-authority-not-configured (:reason unconfigured-result))
         :application-owns "Application configures trusted key; framework enforces."}
        {:boundary "Configured chain advances on consecutive admissions"
         :framework-role "A chain with disposition authority accepts two changes; each produces a new chain head."
         :expected? (and (= :admitted (:admission-status result-a))
                         (= :admitted (:admission-status result-b)))
         :fail-closed? (and (= :ok (:reason result-a))
                            (= :ok (:reason result-b))
                            (not= :receipt-a head-after-b))
         :application-owns "Application owns when and how to submit changes; framework advances state."}
        {:boundary "Stale parent rejected"
         :framework-role "A change with stale parent (pointing to an old chain head) is rejected."
         :expected? (= :not-admitted (:admission-status wrong-parent-result))
         :fail-closed? (= :parent-not-current-head (:reason wrong-parent-result))
         :application-owns "Application owns submission timing; framework enforces ancestry."}
         {:boundary "Consecutive composition"
          :framework-role "Validates that v1 composition is a declared consecutive pipeline."
          :expected? (:valid? (combination/validate-combination pipeline))
          :fail-closed? (not (:valid? (combination/validate-combination branched)))
          :application-owns "Application defines workflow; framework validates structure."}
         {:boundary "Multi-adjustment hash-integrity"
          :framework-role "Every artifact's committed hash is recomputed independently; tampering any single adjustment in a multi-artifact chain is rejected by :held-custody/hash-integrity."
          :expected? (and (:admitted? (:original multi-adjustment-baseline))
                          (not (:admitted? (:original multi-adjustment-tampered)))
                          (contains? (set (:blocking-reasons (:original multi-adjustment-tampered)))
                                     :held-custody/hash-integrity))
          :fail-closed? (and (not (:admitted? (:original multi-adjustment-tampered)))
                             (contains? (set (:blocking-reasons (:original multi-adjustment-tampered)))
                                        :held-custody/hash-integrity))
          :observation "TAMPERED ADJUSTMENT REJECTED"
          :application-owns "Application owns which adjustments to batch; framework verifies each artifact's content against its committed hash."}
         {:boundary "Multi-epoch decision stability"
          :framework-role "Each committed evidence version produces a distinct decision-root; tampering at any epoch is rejected without disturbing prior committed roots."
          :expected? (let [legitimate (filter #(not (:tampered? %)) multi-epoch-results)
                           tampered (filter :tampered? multi-epoch-results)]
                       (and (every? :admitted? legitimate)
                            (= (count tampered) 1)
                            (not (:admitted? (first tampered)))
                            (contains? (set (:blocking-reasons (first tampered)))
                                       :held-custody/hash-integrity)
                            (apply distinct? (map :decision-root legitimate))))
          :fail-closed? (let [tampered (first (filter :tampered? multi-epoch-results))]
                         (= (:admitted? tampered) false))
          :observation "TAMPERED EPOCH REJECTED"
          :application-owns "Application owns epoch sequencing; framework enforces content-hash immutability per epoch."}]))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Boundary" "Framework check" "Expected behavior" "Observed" "Fail-closed behavior" "Application owns"]
  :rows (mapv (fn [{:keys [boundary framework-role expected? fail-closed? observation application-owns]}]
                [boundary framework-role
                 (if expected? "OBSERVED" "CHECK FAILED")
                 (if expected? "✓" "✗")
                 (if fail-closed? "REJECTED" "ERROR")
                 application-owns])
              boundary-results)})

;; ## Multi-Adjustment Evidence
;;
;; The single-artifact scope in `demo_not_admitted` shows hash-integrity catching
;; a tampered amount. With multiple chained adjustments, the same boundary holds:
;; tampering any single artifact in the chain is rejected, while the rest of the
;; chain remains structurally valid and the artifact sequence root changes.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- short-hash
  "Show the first 16 chars of a hash ref, truncated with an ellipsis."
  [h]
  (let [s (if (string? h) h (str h))]
    (if (<= (count s) 16) s (str (subs s 0 16) "…"))))

;; Adjustment ledger

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["ID" "Direction" "Amount" "Before" "After" "Reason"]
  :rows (mapv (fn [{:keys [held-adjustment/id held/direction amount held/before held/after held/reason]}]
                [id (name direction) amount before after (name reason)])
              multi-adjustment-evidence)})

;; Verification: baseline vs tampered (adjustment 2's amount changed 400→500)

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Check" "Baseline" "Tampered"]
  :rows (let [tampered-statuses (into {}
                                      (map (juxt :check/id :status)
                                           (:checks (:original multi-adjustment-tampered))))]
          (mapv (fn [{:keys [check/id status]}]
                  [id (name status) (name (get tampered-statuses id))])
                (:checks (:original multi-adjustment-baseline))))})

;; Fixed-point comparison

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Property" "Baseline (2 adjustments)" "Tampered (adjustment 2)"]
  :rows [["Admitted?" (if (:admitted? (:original multi-adjustment-baseline)) "✓" "✕")
          (if (:admitted? (:original multi-adjustment-tampered)) "✓" "✕")]
         ["Canonical round-trip"
          (if (:canonical-fixed-point? multi-adjustment-baseline) "PASS" "FAIL")
          (if (:canonical-fixed-point? multi-adjustment-tampered) "PASS" "FAIL")]
         ["Verification fixed-point"
          (if (:verification-fixed-point? multi-adjustment-baseline) "PASS" "FAIL")
          (if (:verification-fixed-point? multi-adjustment-tampered) "PASS" "FAIL")]
         ["Decision root consistent"
          (if (:decision-root-consistent? multi-adjustment-baseline) "Yes" "No")
          (if (:decision-root-consistent? multi-adjustment-tampered) "Yes" "No")]
         ["Blocking reasons"
          (pr-str (:blocking-reasons (:original multi-adjustment-baseline)))
          (pr-str (:blocking-reasons (:original multi-adjustment-tampered)))]
         ["Decision root"
          (short-hash (:decision-root (:original multi-adjustment-baseline)))
          (short-hash (:decision-root (:original multi-adjustment-tampered)))]
         ["Evidence root"
          (short-hash (:evidence-root (:original multi-adjustment-baseline)))
          (short-hash (:evidence-root (:original multi-adjustment-tampered)))]
         ["Subject root"
          (short-hash (:subject-root (:original multi-adjustment-baseline)))
           (short-hash (:subject-root (:original multi-adjustment-tampered)))]]
                  })

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [holds? (and (:admitted? (:original multi-adjustment-baseline))
                   (not (:admitted? (:original multi-adjustment-tampered)))
                   (contains? (set (:blocking-reasons (:original multi-adjustment-tampered)))
                              :held-custody/hash-integrity)
                   (:holds? multi-adjustment-baseline))]
   [:div {:style {:background (if holds? "#f0fdf4" "#fef2f2")
                  :border (str "1px solid " (if holds? "#86efac" "#fca5a5"))
                  :border-radius "8px" :padding "12px 16px" :font-family "monospace"}}
    "multi-adjustment admission boundary — "
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "HOLDS ✓" "VIOLATED ✕")]]))

;; ## Multi-Epoch Decision Stability
;;
;; Each epoch is a sequential evidence ledger version (snapshot). The admission
;; boundary is re-verified at each epoch. Legitimate changes produce :admitted
;; with a new decision-root; tampering at any epoch produces :not-admitted.
;; Decision roots form a chain — each captures the committed state at that epoch.

;; Epoch progression table

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/table
 {:head ["Epoch" "Adjustments" "Admitted?" "Decision Root" "Evidence Root" "Tampered?" "Blocking"]
  :rows (mapv (fn [result]
                (let [epoch (:epoch result)
                      evidence (get multi-epoch-evidence epoch)]
                  [epoch
                   (count evidence)
                   (if (:admitted? result) "✓" "✕")
                   (short-hash (:decision-root result))
                   (short-hash (:evidence-root result))
                   (if (:tampered? result) "Yes" "No")
                   (if (seq (:blocking-reasons result))
                     (mapv name (:blocking-reasons result))
                     "—")]))
              multi-epoch-results)})

;; Multi-epoch stability banner

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [legitimate (filter #(not (:tampered? %)) multi-epoch-results)
       tampered (first (filter :tampered? multi-epoch-results))
       legit-roots (map :decision-root legitimate)
       holds? (and (every? :admitted? legitimate)
                   (not (:admitted? tampered))
                   (= (count (set legit-roots)) (count legit-roots))
                   (contains? (set (:blocking-reasons tampered))
                              :held-custody/hash-integrity))]
   [:div {:style {:background (if holds? "#f0fdf4" "#fef2f2")
                  :border (str "1px solid " (if holds? "#86efac" "#fca5a5"))
                  :border-radius "8px" :padding "12px 16px" :font-family "monospace"}}
    "multi-epoch decision stability — "
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "HOLDS ✓" "VIOLATED ✕")]]))

;; ## Not admitted before certification
;;
;; A three-member research certificate is not created merely because three
;; artifacts were supplied. `pre-certificate-checks` first requires a frozen,
;; valid three-member round and one report, position, and canonical-index entry
;; for every member. This is a certificate-input admission gate: it belongs in
;; this inspection notebook, not in the public product-demo story.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def incomplete-certificate-cell
  {:review-round {:benchmark/content-root "sha256:demo-content"
                  :review-round/id "rr:incomplete"
                  :review-round/purpose :model-admission}
   :reports []
   :positions []})

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(def incomplete-certificate-check
  (tmc/pre-certificate-checks incomplete-certificate-cell))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Certificate input requirement" "Result for incomplete cell"]
  :rows [["frozen, valid three-member round"
          (if (:pre-certificate-valid? incomplete-certificate-check) "present" "NOT ADMITTED")]
         ["one report per member"
          (if (some #(clojure.string/includes? % "reports must contain")
                    (:errors incomplete-certificate-check)) "missing — rejected" "present")]
         ["one position per member"
          (if (some #(clojure.string/includes? % "positions must contain")
                    (:errors incomplete-certificate-check)) "missing — rejected" "present")]
         ["canonical index bound to the round"
          (if (some #(clojure.string/includes? % "canonical-indices")
                    (:errors incomplete-certificate-check)) "missing or invalid — rejected" "present")]]})

;; The exact errors are exposed for reviewer inspection. The builder refuses to
;; construct a certificate from this cell; it does not silently infer missing
;; members or repair mismatched provenance.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/code (:errors incomplete-certificate-check))

;; ## How to use this boundary
;;
;; 1. Build your application-specific candidate and evidence outside the framework.
;; 2. Call the relevant framework verifier at the admission cutpoint.
;; 3. If it rejects, leave your authoritative state unchanged and apply your own
;;    failure policy.
;; 4. Keep user workflows — including reservations, accounting, dispute handling,
;;    corpus selection, and pro-rata policy — in your application or notebook.
;;
;; `not-admitted` therefore means only: **this supplied candidate did not pass
;; the selected admission boundary**. It does not imply a canonical application
;; lifecycle or prescribe a recovery action.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 (let [holds? (every? #(and (:expected? %) (:fail-closed? %)) boundary-results)]
   [:div {:style {:background (if holds? "#f0fdf4" "#fef2f2")
                  :border (str "1px solid " (if holds? "#86efac" "#fca5a5"))
                  :border-radius "8px" :padding "12px 16px" :font-family "monospace"}}
    "generic admission boundaries produce the expected result — "
    [:strong {:style {:color (if holds? "#16a34a" "#dc2626")}}
     (if holds? "HOLDS ✓" "VIOLATED ✕")]]))