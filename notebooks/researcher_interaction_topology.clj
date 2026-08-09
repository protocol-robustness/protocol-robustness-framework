;; # Researcher Interaction Topology
;;
;; **Audience:** Protocol reviewers, security researchers, model validators.
;;
;; **Purpose:** Demonstrate integer-keyed researcher interaction topology
;; for three-member research rounds.  Round-local member keys separate
;; global researcher identity from local interaction position, enabling
;; compact consensus vectors, deterministic grouping, and cross-round
;; isomorphism analysis.
;;
;; **Key concepts:**
;; - `:review-member/key` — dense zero-based integer, unique within a round
;; - `:supporting-member-indices` / `:dissenting-member-indices` — additive vectors
;; - `{:review-round/hash "sha256:..." :review-member/key N}` — scoped reference
;;
;; **Data contract:**
;; - This notebook is self-contained — no external evidence bundle required

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.researcher-interaction-topology
  (:require [nextjournal.clerk :as clerk]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.benchmark.researcher-run-report :as rrr]
            [resolver-sim.benchmark.researcher-position :as rp]
            [resolver-sim.benchmark.review.three-member-certificate :as tmc]
            [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
            [resolver-sim.benchmark.outcome-manifest :as om]
            [resolver-sim.benchmark.signing :as signing]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def base-input
  {:benchmark/content-root "sha256:demo-content"
   :benchmark/model-root "sha256:demo-model"
   :benchmark/evaluation-policy-root "sha256:demo-eval"
   :execution/status :completed
   :execution/model-instance-root "sha256:demo-mi"
   :execution/plan-root "sha256:demo-plan"
   :execution/parameter-domain-root "sha256:demo-domain"
   :execution/sampling-policy-root "sha256:demo-sampling"
   :execution/realised-parameter-set-root "sha256:demo-params"
   :execution/generated-case-set-root "sha256:demo-cases"
   :results/operational {:conservation :pass :quota-bounded :pass}})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def runner-info
  {:runner/id :runner/demo
   :source-tree-hash "sha256:demo-tree"
   :distribution-hash "sha256:demo-dist"
   :environment-hash "sha256:demo-env"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def evidence-refs
  {:evidence-dag-root "sha256:demo-dag"
   :event-evidence-root "sha256:demo-events"
   :execution-log-root "sha256:demo-log"})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def manifest (om/build-manifest base-input))

;; ## 1.  Keyed Review Round

;; A three-member review round with explicit integer member keys.
;; The keys commit into the review-round hash — they are not derived
;; from string sorting.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def keyed-members
  [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
   {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
   {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(def review-round
  (rr/build-review-round
   {:benchmark/content-root "sha256:demo-content"
    :review-round/purpose :model-admission
    :review-round/members keyed-members
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:demo-policy"}))

(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div "round keyed? " (str (rr/round-uses-member-keys? review-round))]
  [:div "member keys: " (pr-str (rr/member-keys review-round))]
  [:div "round hash:  " (:review-round/id review-round)]])

;; ## 2.  Bidirectional Key Lookup

;; Integer keys and researcher IDs are independently retrievable.
;; This is the basis for scoped references:

;;   `{:review-round/hash "sha256:..." :review-member/key N}`

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Member Key" "Researcher ID" "Role" "lookup by key" "lookup by ID"]
  :rows [[0 "researcher-a" "model-steward"
          (rr/researcher-id-for-member-key review-round 0)
          (rr/member-key-for-researcher review-round "researcher-a")]
         [1 "researcher-b" "independent-reproducer"
          (rr/researcher-id-for-member-key review-round 1)
          (rr/member-key-for-researcher review-round "researcher-b")]
         [2 "researcher-c" "adversarial-reviewer"
          (rr/researcher-id-for-member-key review-round 2)
          (rr/member-key-for-researcher review-round "researcher-c")]]})

;; ## 3.  Certificate Consensus with Additive Key Vectors

;; Three researchers submit positions.  The certificate groups them by
;; dimension and emits integer key vectors alongside existing ID vectors.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn- finalise-report
  "Compute the content hash for an unsigned run report so it passes
   certificate pre-checks.  Mirrors the test-suite helper."
  [report]
  (assoc report :researcher-run-report/hash
         (str "sha256:" (hc/domain-hash :researcher-run-report report))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def reports
  (mapv (fn [id]
          (-> (rrr/build-report {:outcome-manifest manifest
                                 :researcher-id id
                                 :runner-info runner-info
                                 :evidence-refs evidence-refs
                                 :run-id (str "demo-run-" id)})
              (finalise-report)))
        ["researcher-a" "researcher-b" "researcher-c"]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def outcome-hash
  (:researcher-run-report/outcome-hash (first reports)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def positions
  [(rp/build-position
    {:benchmark/content-root "sha256:demo-content"
     :researcher/id "researcher-a"
     :outcome-hash outcome-hash
     :dimensions {:publication {:status :publish}
                  :model-state {:status :adequate}
                  :evidence {:status :sufficient}}})
   (rp/build-position
    {:benchmark/content-root "sha256:demo-content"
     :researcher/id "researcher-b"
     :outcome-hash outcome-hash
     :dimensions {:publication {:status :publish}
                  :model-state {:status :adequate}
                  :evidence {:status :sufficient}}})
   (rp/build-position
    {:benchmark/content-root "sha256:demo-content"
     :researcher/id "researcher-c"
     :outcome-hash outcome-hash
     :dimensions {:publication {:status :publish}
                  :model-state {:status :incomplete}
                  :evidence {:status :insufficient}}})])

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def cert
  (-> (tmc/build-certificate
       {:review-round review-round
        :reports reports
        :positions positions})
      (tmc/finalise-certificate!)))

;; ### 3.1  Unanimous Dimensions

;; When all three researchers agree, both key and ID vectors reflect
;; the full set.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [pub (get-in cert [:other-consensus :publication])]
  (clerk/html
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div {:style {:color "#22c55e" :fontWeight 700}} "publication — " (pr-str (:status pub))]
    [:div "  supporting members: " (pr-str (:supporting-members pub))]
    [:div "  supporting indices: " (pr-str (:supporting-member-indices pub))]]))

;; ### 3.2  Majority-with-Dissent Dimensions

;; When member 2 (carol) dissents, the key vector `[2]` identifies
;; the dissenter compactly without repeating the researcher ID string.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [model (get-in cert [:model-consensus :model-state])
      evid  (get-in cert [:other-consensus :evidence])]
  (clerk/html
   [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"}}
    [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                   :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
     [:div {:style {:color "#f59e0b" :fontWeight 700}} "model-state — " (pr-str (:status model))]
     [:div "  supporting: " (pr-str (:supporting-members model)) "  indices " (pr-str (:supporting-member-indices model))]
     [:div "  dissenting: " (pr-str (:dissenting-members model)) "  indices " (pr-str (:dissenting-member-indices model))]]
    [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                   :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
     [:div {:style {:color "#f59e0b" :fontWeight 700}} "evidence — " (pr-str (:status evid))]
     [:div "  supporting: " (pr-str (:supporting-members evid)) "  indices " (pr-str (:supporting-member-indices evid))]
     [:div "  dissenting: " (pr-str (:dissenting-members evid)) "  indices " (pr-str (:dissenting-member-indices evid))]]]))

;; ### 3.3  Member Positions Carry Keys

;; Each position entry in the certificate includes the derived
;; `:review-member/key` when the round is keyed.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Researcher" "Key" "Position Hash"]
  :rows (mapv (fn [mp]
                [(:researcher/id mp) (:review-member/key mp) (:position/hash mp)])
              (:member-positions cert))})

;; ## 4.  Force-Authorisation by Key

;; A three-member force-authorisation with 2 approvals and 1 dissent.
;; The `verify-against-round` function cross-checks member keys against
;; the frozen review round.

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(with-redefs [signing/sign-hash (fn [_ _ _] "deadbeef")]
  (def fa-auth
    (rfa/build-authorisation
     {:authorisation/id :authorisation/demo
      :authorisation/policy
      {:policy/id :research/three-member-force-authorisation
       :policy/version 1 :policy/schema-version "fa-policy.v1"
       :policy/hash "sha256:fa-policy"}
      :authorisation/review-round
      {:review-round/id (:review-round/id review-round)
       :review-round/hash "sha256:demo-round-hash"}
      :authorisation/request-root "sha256:demo-request"
      :authorisation/target
      {:target/kind :benchmark-branch
       :target/baseline-content-root "sha256:baseline"
       :target/branch-descriptor-hash "sha256:branch"
       :target/proposed-content-root "sha256:proposed"}
      :authorisation/decision-references
      [{:researcher/id "researcher-a" :decision :approve
        :decision/hash "sha256:dec-a" :review-member/key 0
        :signature {:algorithm :ed25519 :value "sig1" :signed-at "now"}}
       {:researcher/id "researcher-b" :decision :approve
        :decision/hash "sha256:dec-b" :review-member/key 1
        :signature {:algorithm :ed25519 :value "sig2" :signed-at "now"}}
       {:researcher/id "researcher-c" :decision :dissent
        :decision/hash "sha256:dec-c" :review-member/key 2
        :dissent/reason "scope concern"
        :signature {:algorithm :ed25519 :value "sig3" :signed-at "now"}}]
      :authorisation/threshold {:required 2 :eligible 3}})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [check (rfa/verify-against-round review-round fa-auth)]
  (clerk/html
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div "round check valid? " (:valid? check)]
    [:div "approval keys: " (pr-str (:approval-member-keys check))]
    [:div "dissent keys:  " (pr-str (:dissent-member-keys check))]]))

;; ## 5.  Isomorphism: Identity ≠ Topology

;; Two rounds with different global researchers but identical key
;; assignments share the same interaction topology.  The mapping
;; `{0 → researcher-a, 1 → researcher-b, 2 → researcher-c}` vs
;; `{0 → researcher-x, 1 → researcher-y, 2 → researcher-z}`
;; produces the same approval and dissent vectors by key.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def round-iso-a
  (rr/build-review-round
   {:benchmark/content-root "sha256:iso"
    :review-round/purpose :model-admission
    :review-round/members
    [{:review-member/key 0, :researcher/id "researcher-a", :role :model-steward}
     {:review-member/key 1, :researcher/id "researcher-b", :role :independent-reproducer}
     {:review-member/key 2, :researcher/id "researcher-c", :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def round-iso-b
  (rr/build-review-round
   {:benchmark/content-root "sha256:iso"
    :review-round/purpose :model-admission
    :review-round/members
    [{:review-member/key 0, :researcher/id "researcher-x", :role :model-steward}
     {:review-member/key 1, :researcher/id "researcher-y", :role :independent-reproducer}
     {:review-member/key 2, :researcher/id "researcher-z", :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div "Round A hash: " (:review-round/id round-iso-a)]
  [:div "Round B hash: " (:review-round/id round-iso-b)]
  [:div "Different hashes: " (not= (:review-round/id round-iso-a)
                                   (:review-round/id round-iso-b))]
  [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px"}}
   "Identical topology (member keys): "
   (pr-str (rr/member-keys round-iso-a))]
  [:div "Different identities (round A): "
   (pr-str (mapv #(rr/researcher-id-for-member-key round-iso-a %) [0 1 2]))]
  [:div "Different identities (round B): "
   (pr-str (mapv #(rr/researcher-id-for-member-key round-iso-b %) [0 1 2]))]
  [:div {:style {:marginTop "8px" :borderTop "1px solid #334155" :paddingTop "8px" :color "#94a3b8"}}
   "Approval vector [0, 1] identifies the same topological positions "
   "regardless of which researchers occupy them."]])

;; ## 6.  Scoped Reference Rule

;; A member key alone is not globally meaningful.  It must be paired
;; with a scope root for unambiguous reference.

;; | Valid | Reference | Scope |
;; |-------|-----------|-------|
;; | ✅ | `{:review-round/hash \"sha256:...\" :review-member/key 1}` | review-round hash |
;; | ❌ | `{:review-member/key 1}` | scope missing — meaningless |

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  [:div {:style {:color "#22c55e" :fontWeight 700}} "✓ Valid scoped reference"]
  [:div "  {:review-round/hash " (:review-round/id review-round)
   " :review-member/key 1}"]
  [:div "  → " (rr/researcher-id-for-member-key review-round 1)]
  [:div {:style {:marginTop "12px" :color "#ef4444" :fontWeight 700}} "✗ Unscoped (not meaningful)"]
  [:div "  {:review-member/key 1} — scope unknown"]])

;; ## 7.  Legacy Round Compatibility

;; Unkeyed rounds (no `:review-member/key` on any member) continue to
;; validate, hash, and build certificates without emitting key vectors.

^{:nextjournal.clerk/visibility {:code :show :result :hide}}
(def legacy-members
  [{:researcher/id "researcher-a" :role :model-steward}
   {:researcher/id "researcher-b" :role :independent-reproducer}
   {:researcher/id "researcher-c" :role :adversarial-reviewer}])

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [legacy-content-root (:benchmark/content-root manifest)
      legacy-round
      (rr/build-review-round
       {:benchmark/content-root legacy-content-root
        :review-round/purpose :model-admission
        :review-round/members legacy-members
        :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
        :review-round/policy-root "sha256:policy"})
      legacy-reports
      (mapv (fn [id]
              (-> (rrr/build-report {:outcome-manifest manifest
                                     :researcher-id id
                                     :runner-info runner-info
                                     :evidence-refs evidence-refs
                                     :run-id (str "legacy-run-" id)})
                  (finalise-report)))
            ["researcher-a" "researcher-b" "researcher-c"])
      legacy-outcome-hash (:researcher-run-report/outcome-hash (first legacy-reports))
      legacy-pos
      [(rp/build-position
        {:benchmark/content-root legacy-content-root
         :researcher/id "researcher-a" :outcome-hash legacy-outcome-hash
         :dimensions {:publication {:status :publish}}})
       (rp/build-position
        {:benchmark/content-root legacy-content-root
         :researcher/id "researcher-b" :outcome-hash legacy-outcome-hash
         :dimensions {:publication {:status :publish}}})
       (rp/build-position
        {:benchmark/content-root legacy-content-root
         :researcher/id "researcher-c" :outcome-hash legacy-outcome-hash
         :dimensions {:publication {:status :publish}}})]
      legacy-cert
      (-> (tmc/build-certificate
           {:review-round legacy-round :reports legacy-reports :positions legacy-pos})
          (tmc/finalise-certificate!))]
  (clerk/html
   [:div {:style {:background "#0f172a" :padding "16px" :borderRadius "8px"
                  :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
    [:div "round valid? " (rr/round-valid? legacy-round)]
    [:div "round uses member keys? " (rr/round-uses-member-keys? legacy-round)]
    [:div "certificate valid? " (tmc/certificate-valid? legacy-cert)]
    [:div "key vector present? "
          (contains? (get-in legacy-cert [:other-consensus :publication])
                :supporting-member-indices)]]))

