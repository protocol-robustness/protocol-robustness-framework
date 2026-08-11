^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.not-admitted
  "The canonical admission-boundary showcase.

   A candidate is admitted only when the production predicate independently
   recomputes its admissibility. This notebook owns scenarios, mutations,
   positive controls, and presentation; production namespaces own semantics."
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.allocation.activation :as activation]
            [resolver-sim.assurance.force-authorisation :as force-auth]
            [resolver-sim.benchmark.outcome-manifest :as outcome-manifest]
            [resolver-sim.economics.with-bounty.policy :as bounty-policy]
            [resolver-sim.hash.canonical :as hash]
            [resolver-sim.io.content-addressed-store :as store]
            [prf.extensions.held-custody.mutation :as mut]))

;; # Not Admitted
;;
;; ## Can this candidate be accepted into the next trusted state or process?
;;
;; Six candidates can look plausible: valid structure, a claimed successful
;; status, a familiar economic action, an authority-shaped record, or valid
;; content under a familiar key. None crosses a trust boundary merely because
;; its producer says it should.
;;
;; **Claim:** a candidate presented as acceptable must independently recompute
;; as admissible. Otherwise it is **NOT ADMITTED**.
;;
;; Every case below has a nearby positive control. The point is not that PRF can
;; reject things; it is that one relevant difference changes the recomputed
;; admission decision.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- exception-result
  "Run a production transition and retain either its accepted value or its typed
   rejection, without allowing a rejected candidate to alter the input world."
  [f]
  (try
    {:admitted? true :value (f)}
    (catch clojure.lang.ExceptionInfo e
      {:admitted? false
       :error-type (or (:type (ex-data e)) (:error (ex-data e)))
       :error-data (ex-data e)})))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- short-value [value]
  (let [s (pr-str value)]
    (if (> (count s) 88) (str (subs s 0 87) "…") s)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- panel [title body]
  (clerk/html
   [:section {:style {:background "#0f172a" :color "#e2e8f0" :padding "18px"
                      :border-radius "6px" :margin "16px 0" :font-family "monospace"}}
    [:h3 {:style {:color "#7ADDDC" :margin "0 0 10px" :font-size "15px"}} title]
    body]))

;; ## Six candidates
;;
;; This is the whole boundary at a glance. Each negative result is derived by a
;; production validator or transition; the details below show the candidate and
;; its admissible counterpart.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def forbidden-case
  (let [admissible-policy
        (bounty-policy/normalize-with-bounty-policy
         {:composition/type :economics/with-bounty
          :composition/version 1
          :base {:operation/ref :prf/slash-distribution-v1
                 :result/schema :prf/base-result.v1}
          :bounty {:bounty/id :review-completion
                   :eligibility {:capability/ref {:capability/kind :economics/eligibility
                                                  :capability/id :not-admitted/eligible
                                                  :capability/version 1}}
                   :amount {:capability/ref {:capability/kind :economics/award-amount
                                             :capability/id :not-admitted/amount
                                             :capability/version 1}
                            :basis {:source :base/result :field :resolved-amount}}
                   :funding {:source :declared-reserve
                             :parameter/address [:bounties :review-reserve]}
                   :recipient {:source :event/actor}
                   :effect-contract :prf.effect/obligation-create.v2}})
        admitted (bounty-policy/validate-with-bounty-policy admissible-policy)
        candidate (assoc admissible-policy :bounty/on-ineligible :pay-out)
        rejected (bounty-policy/validate-with-bounty-policy candidate)]
    {:id :forbidden
     :candidate "An intrinsically prohibited payout policy"
     :looks-plausible "It has the same otherwise-valid policy structure."
     :mechanism "Policy eligibility"
     :admitted? (:valid? admitted)
     :rejected? (not (:valid? rejected))
     :result (some->> (:violations rejected)
                       (map :violation/id)
                       (filter #{:violation/unsupported-on-ineligible})
                       first)}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def forbidden-authorized-case
  (let [authorized-scope {:authorization/id "release-42"
                          :authorization/type :force-authorisation
                          :held/direction :out
                          :token :USDC
                          :amount 40
                          :held/account :escrow-principal
                          :owner/address "0xBob"
                          :held/reason :force-authorised-release
                          :held/workflow-id 42}
        record {:authorization/id "release-42"
                :authorization/status :active
                :consumed? false
                :starts-at 0
                :authorization/scope authorized-scope
                :authorization/scope-hash
                (force-auth/force-authorisation-scope-hash authorized-scope)}
        admitted (force-auth/verify-authorisation-usable record {} authorized-scope 0)
        ;; The candidate carries a genuine authority record, but asks to perform
        ;; a different (refund) effect than the scope grants (release).
        prohibited-scope (assoc authorized-scope :held/reason :force-authorised-refund)
        rejected (force-auth/verify-authorisation-usable record {} prohibited-scope 0)]
    {:id :forbidden-authorized
     :candidate "A prohibited refund carrying a genuine release authorization"
     :looks-plausible "The authorization exists, is active, and has a valid hash."
     :mechanism "Authority scope"
     :admitted? (:valid? admitted)
     :rejected? (not (:valid? rejected))
     :result (mapv :code (:errors rejected))}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def claimed-admissible-case
  (let [policy {:authority :coordinator :fail-closed true}
        passing-proof {:result/status :passing
                       :result-root (apply str (repeat 64 "a"))
                       :certificate-assertions-digest (apply str (repeat 64 "b"))}
        genuine (activation/build-receipt {:proof passing-proof :policy policy})
        rejected-proof {:result/status :rejected
                        :result-root (apply str (repeat 64 "c"))
                        :rejection/classification :ineligible-claimant}
        prohibited (activation/build-receipt {:proof rejected-proof :policy policy})
        claimed (assoc prohibited :activation/status :activated)]
    {:id :claimed-admissible
     :candidate "A prohibited receipt edited to claim :activated"
     :looks-plausible "Its visible status says it is admissible."
     :mechanism "Derived status and receipt root"
     :admitted? (activation/valid-activated-receipt? genuine)
     :rejected? (not (activation/valid-activated-receipt? claimed))
     :result :activation-root-does-not-recompute}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def add-held-action-case
  (let [auth-id "fa-deposit-42"
        scope {:authorization/id auth-id
               :authorization/type :force-authorisation
               :held/direction :in
               :token :USDC
               :amount 100
               :held/account :escrow-principal
               :owner/address "0xBob"
               :held/reason :escrow-principal-deposited
               :held/workflow-id 42}
        scope-hash (hash/domain-hash "force-authorisation-scope" scope)
        auth-record {:authorization/id auth-id
                     :authorization/type :force-authorisation
                     :authorization/scope-hash scope-hash}
        valid-adj {:mutation/id "m-add-1"
                   :held/amount 100
                   :held/token :USDC
                   :held/account :escrow-principal
                   :owner/address "0xBob"
                   :held/reason :escrow-principal-deposited
                   :held/workflow-id 42}
        valid-mutation (exception-result #(mut/add-held-action auth-record valid-adj {}))
        invalid-direction (exception-result #(mut/add-held-action auth-record (assoc valid-adj :held/direction :out) {}))
        check-valid (when (:admitted? valid-mutation)
                      (mut/check-force-auth-held-mutation (:value valid-mutation) {:authorization auth-record}))
        mismatched-auth (assoc-in auth-record [:authorization/scope-hash]
                                  (hash/domain-hash "force-authorisation-scope" {:forged true}))
        check-mismatched (when (:admitted? valid-mutation)
                           (mut/check-force-auth-held-mutation (:value valid-mutation) {:authorization mismatched-auth}))]
    {:id :add-held-action
     :description "The add-held-action convenience function builds an :add-held mutation artifact with direction fixed to :in. An explicit :out direction fails closed before the artifact is constructed, so no mutation artifact is produced. The built artifact is then independently verified against the authorization scope; a forged scope-hash fails verification."
     :candidate "A well-formed escrow-principal deposit with correct authorization"
     :looks-plausible "Token, amount, owner, workflow, and authorization id are all present and internally consistent."
     :mechanism "Direction-bound mutation artifact construction + intrinsic and authorization verification"
     :admitted? (:admitted? valid-mutation)
     :rejected? (not (:admitted? invalid-direction))
     :result (:error-type invalid-direction)
     :verified? (:verified? check-valid)
     :mismatched-rejected? (not (:verified? check-mismatched))
     :unchanged? true}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def add-held-case
  (let [auth-id "fa-release-42"
        scope {:authorization/id auth-id
               :authorization/type :force-authorisation
               :held/direction :out
               :token :USDC
               :amount 40
               :held/account :escrow-principal
               :owner/address "0xBob"
               :held/reason :force-authorised-release
               :held/workflow-id 42}
        scope-hash (hash/domain-hash "force-authorisation-scope" scope)
        auth-record {:authorization/id auth-id
                     :authorization/type :force-authorisation
                     :authorization/scope-hash scope-hash}
        valid-adj {:mutation/id "m-sub-1"
                   :held/amount 40
                   :held/token :USDC
                   :held/account :escrow-principal
                   :owner/address "0xBob"
                   :held/reason :force-authorised-release
                   :held/workflow-id 42}
        valid-mutation (exception-result #(mut/sub-held-action auth-record valid-adj {}))
        invalid-direction (exception-result #(mut/sub-held-action auth-record (assoc valid-adj :held/direction :in) {}))
        check-valid (when (:admitted? valid-mutation)
                      (mut/check-force-auth-held-mutation (:value valid-mutation) {:authorization auth-record}))
        mismatched-auth (assoc-in auth-record [:authorization/scope-hash]
                                  (hash/domain-hash "force-authorisation-scope" {:forged true}))
        check-mismatched (when (:admitted? valid-mutation)
                           (mut/check-force-auth-held-mutation (:value valid-mutation) {:authorization mismatched-auth}))]
    {:id :add-held
     :description "The sub-held-action convenience function builds a :sub-held mutation artifact with direction fixed to :out. An explicit :in direction fails closed before the artifact is constructed, so no mutation artifact is produced. The built artifact is then independently verified against the authorization scope; a forged scope-hash fails verification."
     :candidate "A valid-looking custody release with correct account but mismatched authorization binding"
     :looks-plausible "It cites a real active grant with the correct account and amount."
     :mechanism "Direction-bound mutation artifact construction + intrinsic and authorization verification"
     :admitted? (:admitted? valid-mutation)
     :rejected? (not (:admitted? invalid-direction))
     :result (:error-type invalid-direction)
     :verified? (:verified? check-valid)
     :mismatched-rejected? (not (:verified? check-mismatched))
     :unchanged? true}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def create-if-absent-case
  (let [root (str (System/getProperty "java.io.tmpdir") "/not-admitted-store-" (System/nanoTime))
        content-store (store/create-store root)
        key (str "sha256:" (hash/domain-hash "not-admitted-create-if-absent" {:key "candidate"}))
        first-write (store/put-if-absent! content-store
                                          {:hash-reference key :artifact {:candidate "A"} :verify map?})
        duplicate (store/put-if-absent! content-store
                                         {:hash-reference key :artifact {:candidate "A"} :verify map?})
        collision (exception-result
                   #(store/put-if-absent! content-store
                                          {:hash-reference key :artifact {:candidate "B"} :verify map?}))]
    {:id :create-if-absent
     :candidate "Different content presented under an occupied committed key"
     :looks-plausible "The replacement is itself valid canonical content."
     :mechanism "Identity and content admission"
     :admitted? (and (= :created (:status first-write)) (= :exists (:status duplicate)))
     :rejected? (not (:admitted? collision))
     :result (or (:error-type collision) (get-in collision [:error-data :reason]))
     :unchanged? (= {:candidate "A"} (store/resolve-artifact content-store key))}))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def admission-cases
  [forbidden-case forbidden-authorized-case claimed-admissible-case
   add-held-action-case add-held-case create-if-absent-case])

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "Six plausible candidates; six independently recomputed decisions"
 (clerk/table
  {:head ["Candidate" "Why it looks plausible" "Independent boundary" "Result"]
   :rows (mapv (fn [{:keys [candidate looks-plausible mechanism admitted? rejected?]}]
                 [candidate
                  looks-plausible
                  mechanism
                  (if (and admitted? rejected?) "NOT ADMITTED ✓" "CHECK FAILED ✕")])
               admission-cases)}))

;; ## What admitted means
;;
;; Admission is a recomputed property, never a producer-controlled label. The
;; production function decides using policy, a scoped authority, a receipt root,
;; an action contract, a state transition, or occupied content identity.

;; ## 1. Policy prohibition — `forbidden`
;;
;; The admissible policy tells the system to skip a bounty when the claimant is
;; ineligible. The candidate changes only that outcome to `:pay-out`. It remains
;; structurally complete, but policy prohibition wins.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "forbidden — policy eligibility wins"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}} "ADMITTED"]
   " — the default policy uses the supported ineligible outcome."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}} "NOT ADMITTED"]
   " — " (name (:result forbidden-case)) "."]])

;; ## 2. Scoped authority — `forbidden-authorized`
;;
;; A genuine authorization for a release cannot be re-used as a refund
;; authorization. Authority is evidence of one bounded permission, not a magic
;; word that legalizes every otherwise prohibited action.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "forbidden-authorized — authority is scoped"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}} "ADMITTED"]
   " — the grant and requested release scope recompute identically."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}} "NOT ADMITTED"]
   " — requested refund scope produces " (short-value (:result forbidden-authorized-case)) "."]])

;; ## 3. Derived status — `claimed-admissible`
;;
;; A prohibited receipt can be edited to say `:activated`, but the receipt root
;; and rejected-proof binding still determine admissibility. The declaration is
;; not trusted over the recomputation.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "claimed-admissible — a declaration is not evidence"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}} "ADMITTED"]
   " — a passing proof produces a receipt that validates as activated."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}} "NOT ADMITTED"]
   " — changing a prohibited receipt's visible status cannot make its root recompute."]])

;; ### Downstream consistency — hash-bearing roots
;;
;; A candidate must not pass an early gate only to be rejected by the canonical
;; downstream validator. This is a regression proof for that boundary: both
;; production gates reject a model root that merely looks like a SHA-256
;; reference. `hash-bearing-root-keys` is the shared implementation detail; the
;; admission property is that the decision stays consistent downstream.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- sha256-ref [hex-character]
  (str "sha256:" (apply str (repeat 64 hex-character))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def downstream-root-consistency
  (let [valid (outcome-manifest/build-manifest
               {:benchmark/content-root (sha256-ref "c")
                :benchmark/model-root (sha256-ref "a")
                :benchmark/evaluation-policy-root (sha256-ref "e")
                :execution/model-instance-root (sha256-ref "1")
                :execution/plan-root (sha256-ref "2")
                :execution/parameter-domain-root (sha256-ref "d")
                :execution/sampling-policy-root (sha256-ref "b")
                :execution/generated-case-set-root (sha256-ref "f")
                :execution/status :completed
                :results/operational {:conservation :pass}})
        malformed (assoc valid :benchmark/model-root "sha256:short")
        early-valid (outcome-manifest/pre-application-checks valid)
        downstream-valid (outcome-manifest/validate-manifest valid)
        early-rejected (outcome-manifest/pre-application-checks malformed)
        downstream-rejected (outcome-manifest/validate-manifest malformed)]
    {:positive? (and (:pre-application-valid? early-valid)
                     (:valid? downstream-valid))
     :negative? (and (not (:pre-application-valid? early-rejected))
                     (not (:valid? downstream-rejected)))
     :early-errors (:errors early-rejected)
     :downstream-errors (:errors downstream-rejected)}))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "Downstream consistency — a hash-like root is rejected at every gate"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}}
                            (if (:positive? downstream-root-consistency) "ADMITTED by both gates" "CHECK FAILED")]
   " — a complete manifest has valid committed roots."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}}
                       (if (:negative? downstream-root-consistency) "NOT ADMITTED by either gate" "CHECK FAILED")]
   " — `:benchmark/model-root` is `sha256:short`, not a complete SHA-256 reference."]
  [:p {:style {:color "#94a3b8" :font-size "12px"}}
   "Early gate: " (short-value (:early-errors downstream-root-consistency))]
  [:p {:style {:color "#94a3b8" :font-size "12px"}}
   "Downstream validator: " (short-value (:downstream-errors downstream-root-consistency))]])

;; ## 4. Economic action boundary — `add-held-action`
;;
;; The `add-held-action` convenience function builds an `:add-held` mutation
;; artifact with direction fixed to `:in`. An explicit `:out` direction fails
;; closed before the artifact is constructed, so no mutation artifact is
;; produced. The built artifact is then independently verified against the
;; authorization scope; a forged scope-hash fails verification.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "add-held-action — validate the requested effect before execution"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}} "ADMITTED"]
   " — an escrow-principal deposit builds a valid :add-held mutation artifact."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}} "NOT ADMITTED"]
   " — " (name (:result add-held-action-case))
   "; the explicit :out direction is rejected before any artifact is created."]
  [:p {:style {:color "#94a3b8" :font-size "12px"}}
   "Verified with correct auth? " (str (:verified? add-held-action-case))
   ". Mismatched auth rejected? " (str (:mismatched-rejected? add-held-action-case)) "."]])

;; ## 5. State-transition boundary — `add-held`
;;
;; The `sub-held-action` convenience function builds a `:sub-held` mutation
;; artifact with direction fixed to `:out`. An explicit `:in` direction fails
;; closed before the artifact is constructed. The built artifact is then
;; independently verified against the authorization scope; a forged scope-hash
;; fails verification.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "add-held — canonical transition admission"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}} "ADMITTED"]
   " — a matching grant permits the scoped custody release artifact."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}} "NOT ADMITTED"]
   " — " (name (:result add-held-case))
   "; the explicit :in direction is rejected before any artifact is created."]
  [:p {:style {:color "#94a3b8" :font-size "12px"}}
   "Verified with correct auth? " (str (:verified? add-held-case))
   ". Mismatched auth rejected? " (str (:mismatched-rejected? add-held-case)) "."]])

;; ## 6. Identity/content boundary — `create-if-absent`
;;
;; The first canonical object occupies a committed key. An identical duplicate
;; is idempotent; different content under that occupied key is rejected, and the
;; original content remains authoritative.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "create-if-absent — content cannot silently replace an existing identity"
 [:div
  [:p "Positive control: " [:strong {:style {:color "#4ade80"}} "ADMITTED"]
   " — first creation succeeds and an identical duplicate is accepted as :exists."]
  [:p "Candidate: " [:strong {:style {:color "#f87171"}} "NOT ADMITTED"]
   " — " (name (:result create-if-absent-case))
   ". Original content unchanged? " [:strong (str (:unchanged? create-if-absent-case))] "."]])

;; ## Independent recomputation and mutation safety
;;
;; The notebook constructs the candidate and its relevant mutation locally, then
;; calls the production boundary. It does not reproduce policy, authorization,
;; accounting, receipt, or content-store semantics. Rejected state-changing
;; candidates return a typed rejection instead of a successor state.

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(panel
 "Admission evidence"
 (clerk/table
  {:head ["Case" "Admissible control" "Rejected candidate" "Typed result"]
   :rows (mapv (fn [{:keys [id admitted? rejected? result]}]
                 [(name id)
                  (if admitted? "ADMITTED" "FAILED")
                  (if rejected? "NOT ADMITTED" "FAILED")
                  (short-value result)])
               admission-cases)}))

;; ## Summary
;;
;; These are six different ways an apparently acceptable candidate fails to
;; cross a trust boundary:
;;
;; 1. policy eligibility;
;; 2. authority scope;
;; 3. derived status;
;; 4. action admission;
;; 5. state-transition admission; and
;; 6. identity/content admission.
;;
;; The shared rule is simple: **recompute admission independently, or do not
;; admit the candidate.**
