(ns resolver-sim.benchmark.governed-authority-state-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.governed-authority-resolution :as resolution]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-governance-evidence :as evidence]))

(def ^:private not-rooted-msg "authority material is not rooted and authenticated")
(def ^:private runtime-value-msg "authority material contains runtime or mutable value")

(defn- hash-ref [ch]
  (str "sha256:" (apply str (take 64 (cycle ch)))))

(def ^:private genesis-ref (hash-ref "11"))
(def ^:private config-ref (hash-ref "22"))
(def ^:private activation-ref (hash-ref "44"))
(def ^:private control-evidence-ref (hash-ref "55"))
(def ^:private admissibility-ref (hash-ref "66"))
(def ^:private round-hash-ref (hash-ref "77"))

(defn- round-body []
  {:benchmark/content-root (hash-ref "aa")
   :review-round/members []
   :review-round/membership-frozen-at 0
   :review-round/policy-root (hash-ref "bb")
   :review-round/purpose :model-admission})

(defn- governance-body []
  {:schema-version "review-governance.v1"})

(defn- position-time-body []
  {:schema-version "position-time-basis.v1" :position-acceptance-roots []})

(defn- key-set [keys]
  {:artifact/schema state/signer-key-set-schema :signer-key-set/keys keys})

(defn- authenticated-material
  ([] (authenticated-material (key-set [(hash-ref "k1") (hash-ref "k2")])))
  ([ks]
   (let [rb (round-body) gb (governance-body) pb (position-time-body)]
     {:chain-instance-genesis/root genesis-ref
      :chain-configuration/root config-ref
      :review-governance/root (governance/governance-root gb)
      :review-governance-activation/root activation-ref
      :control-plane-evidence/root control-evidence-ref
      :review-governance-admissibility/root admissibility-ref
      :review-round/hash round-hash-ref
      :review-round/root (state/review-round-material-root rb)
      :position-time-basis/root (evidence/position-time-basis-root pb)
      :signer-key-set/root (state/signer-key-set-root ks)
      :authority-material/review-round rb
      :authority-material/review-governance gb
      :authority-material/position-time-basis pb
      :authority-material/signer-key-set ks})))

(defn- store-envelope [state-root predecessor sequence]
  {:chain-instance-genesis/root genesis-ref
   :execution/state-root state-root
   :chain-configuration/root config-ref
   :review-governance/root (governance/governance-root (governance-body))
   :review-governance-activation/root activation-ref
   :configuration-head/root (hash-ref "88")
   :control-plane-evidence/root control-evidence-ref
   :publication/sequence sequence
   :publication/predecessor-root predecessor})

(defn- fresh-store
  ([] (fresh-store (authenticated-material)))
  ([material]
   (let [state-root (hash-ref "aa00")
         envelope (state/build-envelope (store-envelope state-root nil 0))]
     {:store (state/new-store envelope material)
      :state-root state-root
      :envelope envelope
      :head (:authoritative-state-envelope/root envelope)
      :material material})))

(defn- admission-basis
  ([w] (admission-basis w (:state-root w) (:head w)))
  ([_w state-root anchor]
   (resolution/build-resolution-basis-v2
    {:resolution/purpose :current-admission
     :chain-instance-genesis/root genesis-ref
     :resolution/state-before-root state-root
     :resolution/anchor-root anchor
     :review-round/hash round-hash-ref
     :authority-resolver/root (:governed-authority-resolver/root resolution/default-resolver)})))

(defn- audit-basis-v2 [state-root anchor]
  (resolution/build-resolution-basis-v2
   {:resolution/purpose :historical-audit
    :chain-instance-genesis/root genesis-ref
    :resolution/state-before-root state-root
    :resolution/anchor-root anchor
    :review-round/hash round-hash-ref
    :authority-resolver/root (:governed-authority-resolver/root resolution/default-resolver)}))

(defn- v1-basis [purpose state-root anchor]
  (resolution/build-resolution-basis
   {:resolution/purpose purpose
    :chain-instance-genesis/root genesis-ref
    :resolution/state-before-root state-root
    :resolution/anchor-root anchor
    :review-round/hash round-hash-ref}))

(defn- resolve-basis
  "resolve-governed-authority-context with exceptions surfaced as data so a
   broken resolution path fails assertions with its cause instead of erroring."
  [store basis]
  (try (state/resolve-governed-authority-context store basis)
       (catch Exception e
         {:resolved? false :thrown (.getMessage e) :errors (:errors (ex-data e))})))

(defn- resolve*
  ([w] (resolve-basis (:store w) (admission-basis w)))
  ([w state-root anchor]
   (resolve-basis (:store w) (admission-basis w state-root anchor))))

(defn- issue-fence [w]
  (try
    (let [result (state/resolve-authority-material (:store w) (admission-basis w))]
      (if (:resolved? result)
        {:ok? true :result result}
        {:ok? false :reason (:reason result)}))
    (catch Exception e
      {:ok? false :thrown (.getMessage e) :errors (:errors (ex-data e))})))

(defn- transition-binding [context-root pre post transition-ref]
  (resolution/build-transition-binding
   {:resolved-review-authority-context/root context-root
    :transition/root transition-ref
    :transaction/state-before-root pre
    :transaction/state-after-root post
    :authorization/result-root (hash-ref "ff")}))

(defn- fence-record [w fence-id]
  (get-in @(.state (:store w)) [:issued-fences fence-id]))

(defn- new-store-error [envelope material]
  (try (state/new-store envelope material) nil
       (catch Exception e (.getMessage e))))

(defn- successor-of [w material]
  (let [pred (:authoritative-state-envelope/root (:envelope w))
        seq+ (inc (:publication/sequence (:envelope w)))
        state-root (hash-ref "aa01")]
    {:state-root state-root
     :envelope (state/build-envelope (store-envelope state-root pred seq+))
     :material material}))

;; ── priority 1: signer-key body/root substitution ─────────────────────────

(deftest signer-key-body-root-substitution-rejected
  (let [{:keys [envelope] :as w} (fresh-store)]
    (testing "positive control: intact body authenticates"
      (is (nil? (new-store-error (:envelope w) (:material w)))))
    (testing "extra rogue signer key appended, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (update-in (:material w)
                         [:authority-material/signer-key-set :signer-key-set/keys]
                         conj (hash-ref "rogue"))))))
    (testing "signing key element swapped, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in (:material w)
                        [:authority-material/signer-key-set :signer-key-set/keys 0]
                        (hash-ref "evil"))))))
    (testing "entire body replaced by a differently-rooted set, old root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc (:material w)
                     :authority-material/signer-key-set
                     (key-set [(hash-ref "z1") (hash-ref "z2")]))))))
    (testing "body schema tampered"
      (is (= "governed signer-key-set is invalid"
             (new-store-error
              envelope
              (assoc-in (:material w)
                        [:authority-material/signer-key-set :artifact/schema]
                        "governed-authority-signer-key-set.v0")))))))

;; ── priority 2: review-round body/root substitution ───────────────────────

(deftest review-round-body-root-substitution-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "round purpose tampered inside body, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in material [:authority-material/review-round :review-round/purpose]
                        :model-challenge)))))
    (testing "membership content tampered, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in material [:authority-material/review-round :benchmark/content-root]
                        (hash-ref "mutated"))))))))

;; ── priority 3: governance body/root substitution ─────────────────────────

(deftest governance-body-root-substitution-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "governance body swapped for a different canonical projection"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc material
                     :authority-material/review-governance
                     {:schema-version "review-governance.v1-backdoor"})))))))

;; ── priority 4: position-time body/root substitution ──────────────────────

(deftest position-time-body-root-substitution-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (testing "acceptance roots reordered/tampered, declared root kept"
      (is (= not-rooted-msg
             (new-store-error
              envelope
              (assoc-in material
                        [:authority-material/position-time-basis :position-acceptance-roots]
                        [(hash-ref "phantom")])))))))

;; ── priority 5: missing bodies with correct-looking roots ─────────────────

(deftest missing-authenticated-bodies-with-declared-roots-rejected
  (let [{:keys [envelope material]} (fresh-store)]
    (doseq [body-key [:authority-material/review-round
                      :authority-material/review-governance
                      :authority-material/position-time-basis
                      :authority-material/signer-key-set]]
      (testing (str "missing " (name body-key) " with root fields retained")
        (is (= not-rooted-msg (new-store-error envelope (dissoc material body-key))))))
    (testing "all bodies stripped, every declared root retained"
      (let [stripped (apply dissoc material [:authority-material/review-round
                                             :authority-material/review-governance
                                             :authority-material/position-time-basis
                                             :authority-material/signer-key-set])]
        (is (= not-rooted-msg (new-store-error envelope stripped)))))
    (testing "non-map body standing in for an authenticated body"
      (is (= not-rooted-msg
             (new-store-error envelope (assoc material :authority-material/signer-key-set (hash-ref "x"))))))))

;; ── priority 6: runtime values in authenticated structures ────────────────

(deftest runtime-values-rejected-in-authenticated-material-and-publication
  (let [{:keys [envelope] :as w} (fresh-store)]
    (testing "callback inside the signer-key-set keys vector"
      (is (= runtime-value-msg
             (new-store-error
              envelope
              (assoc-in (:material w) [:authority-material/signer-key-set :signer-key-set/keys 0]
                        (fn [] :rogue))))))
    (testing "callback inside the review-round body"
      (is (= runtime-value-msg
             (new-store-error
              envelope
              (assoc-in (:material w) [:authority-material/review-round :review-round/membership-frozen-at]
                        (fn [] 0))))))
    (testing "callback at the top level of published material"
      (is (= runtime-value-msg
             (new-store-error envelope (assoc (:material w) :extraneous (fn [] :rogue))))))
    (testing "callback in successor publication is rejected and head does not move"
      (let [succ (successor-of w {:chain-instance-genesis/root genesis-ref
                                  :authority-material/signer-key-set (fn [] :rogue)})
            head-before (:head @(.state (:store w)))]
        (is (= runtime-value-msg
               (try
                 (let [r (state/publish-successor! (:store w) (:head w) (:envelope succ) (:material succ))]
                   (str "published: " (:published? r)))
                 (catch Exception e (.getMessage e)))))
        (is (= head-before (:head @(.state (:store w)))))))))

;; ── publication boundary: successors must be authenticated like genesis ───

(deftest successor-publication-requires-authenticated-material
  (let [{:keys [head] :as w} (fresh-store)
        succ (successor-of w {:chain-instance-genesis/root genesis-ref})]
    (testing "body-less, root-less successor material must not enter the store"
      (is (thrown? Exception
                   (state/publish-successor! (:store w) head (:envelope succ) (:material succ)))))
    (testing "head unchanged after rejected publication"
      (is (= head (:head @(.state (:store w))))))))

;; ── prerequisite: authenticated stores must resolve at all ────────────────

(deftest authenticated-store-resolves-current-admission
  (let [w (fresh-store)
        r (resolve* w)]
    (is (:resolved? r)
        "an authenticated store must complete current-admission resolution")
    (when (:resolved? r)
      (let [ctx (:context r)]
        (is (= (:head w) (:authority-state/root ctx)))
        (is (= (:state-root w) (:resolution/state-before-root ctx)))
        (is (some? (:resolved-review-authority-context/root ctx)))))))

;; ── priority 7: fence record proves exact material roots from issuance ────

(deftest fence-record-proves-exact-material-roots-from-issuance
  (let [{:keys [envelope material] :as w} (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance on an authenticated store")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            rec (fence-record w (:fence/id fence))]
        (is (some? rec) "issued fence id resolves to a registry record")
        (is (= :issued (:status rec)))
        (is (= :current-admission (:purpose rec)))
        (is (= (:authority-state/root context) (:authority-state-envelope/root rec)))
        (is (= (:resolution/state-before-root context) (:execution/state-root rec)))
        (is (= (:resolved-review-authority-context/root context)
               (:resolved-review-authority-context/root rec)))
        (is (= (:resolution-basis/root context) (:resolution-basis/root rec)))
        (is (= (:publication/sequence envelope) (:publication/sequence rec)))
        (doseq [[k v] [[:review-round/root (:review-round/root material)]
                       [:review-governance/root (:review-governance/root material)]
                       [:position-time-basis/root (:position-time-basis/root material)]
                       [:signer-key-set/root (:signer-key-set/root material)]]]
          (is (= v (get rec k)) (str "fence pins exact issuance value of " k)))
        (is (= (:authority-evaluation-basis/root
                (state/evaluation-basis
                 {:resolved-review-authority-context/root
                  (:resolved-review-authority-context/root context)
                  :review-round/root (:review-round/root material)
                  :review-governance/root (:review-governance/root material)
                  :position-time-basis/root (:position-time-basis/root material)
                  :signer-key-set/root (:signer-key-set/root material)}))
               (:authority-evaluation-basis/root rec))
            "fence binds the evaluation basis joining the context and key set")
        (is (= material (:authenticated-material (:result issued))))))))

;; ── priority 8 + transplant A: fences are store-instance and key-set bound ─

(deftest fences-not-interchangeable-across-stores-or-key-sets
  (let [ks-a (key-set [(hash-ref "kA1") (hash-ref "kA2")])
        ks-b (key-set [(hash-ref "kB1") (hash-ref "kB2")])
        wa (fresh-store (authenticated-material ks-a))
        ;; Store B: same envelope content, different authenticated key set
        wb (fresh-store (authenticated-material ks-b))
        fa (issue-fence wa)
        fb (issue-fence wb)]
    (is (:ok? fa) "prerequisite: store A issues a fence")
    (is (:ok? fb) "prerequisite: store B issues a fence")
    (when (and (:ok? fa) (:ok? fb))
      (let [fence-a (get-in fa [:result :fence])
            binding-a (transition-binding
                       (get-in fa [:result :context :resolved-review-authority-context/root])
                       (:state-root wa) (hash-ref "ada1") (hash-ref "ede1"))]
        (testing "fence issued by store A is unknown to store B"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store wb) fence-a binding-a nil nil))))
          (is (= (:head wb) (:head @(.state (:store wb))))))
        (testing "the reverse transplant is equally rejected"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store wa) (get-in fb [:result :fence]) nil nil nil)))))
        (testing "fence records pin their distinct issuance key sets"
          (let [rec-a (fence-record wa (:fence/id fence-a))
                rec-b (fence-record wb (:fence/id (get-in fb [:result :fence])))]
            (is (= (state/signer-key-set-root ks-a) (:signer-key-set/root rec-a)))
            (is (= (state/signer-key-set-root ks-b) (:signer-key-set/root rec-b)))
            (is (not= (:signer-key-set/root rec-a) (:signer-key-set/root rec-b)))))
        (testing
         "characterization: resolved contexts do not commit the key set, so the
          issued-fence registry is the barrier against interchange"
          (is (= (get-in fa [:result :context :resolved-review-authority-context/root])
                 (get-in fb [:result :context :resolved-review-authority-context/root]))))
        (testing "evaluation bases prove semantic key-set significance"
          (let [eb-a (get-in fa [:result :evaluation-basis])
                eb-b (get-in fb [:result :evaluation-basis])]
            (is (= state/evaluation-basis-schema (:artifact/schema eb-a)))
            (is (= (:resolved-review-authority-context/root eb-a)
                   (:resolved-review-authority-context/root eb-b))
                "same semantic context on both sides of the join")
            (is (not= (:authority-evaluation-basis/root eb-a)
                      (:authority-evaluation-basis/root eb-b))
                "different key sets yield different evaluation bases")))))))

;; ── transplant B: rotation after issuance stales outstanding fences ───────

(deftest key-set-rotation-rejects-finalisation-of-pre-rotation-fence
  (let [ks-1 (key-set [(hash-ref "k1") (hash-ref "k2")])
        ks-2 (key-set [(hash-ref "k2a") (hash-ref "k2b")])
        w (fresh-store (authenticated-material ks-1))
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issued while K1 applies")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            pre-basis-root (get-in issued [:result :evaluation-basis :authority-evaluation-basis/root])
            succ (successor-of w (authenticated-material ks-2))]
        (testing "rotating to K2 moves the head"
          (is (:published? (state/publish-successor!
                            (:store w) (:head w) (:envelope succ) (:material succ)))))
        (testing "pre-rotation fence cannot finalise against post-rotation store"
          (is (= :state-not-at-required-head
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence
                           (transition-binding
                            (:resolved-review-authority-context/root context)
                            (:state-root w) (:state-root succ) (hash-ref "ede1"))
                           (:envelope succ) (:material succ))))))
        (testing "the rotated store records K2, never K1"
          (let [reissued (issue-fence (assoc w :state-root (:state-root succ)
                                             :head (:authoritative-state-envelope/root (:envelope succ))
                                             :envelope (:envelope succ)))]
            (is (:ok? reissued))
            (when (:ok? reissued)
              (let [rec-post (fence-record w (get-in reissued [:result :fence :fence/id]))]
                (is (= (state/signer-key-set-root ks-2) (:signer-key-set/root rec-post))
                    "rotated store records K2, never K1")
                (is (not= pre-basis-root (:authority-evaluation-basis/root rec-post))
                    "post-rotation fences bind the K2 evaluation basis")))))))))

;; ── priority 9: forged / stale / consumed / context / pre-state fences ────

(deftest forged-stale-consumed-context-prestate-fences-rejected
  (let [w (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance on an authenticated store")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            pre (:state-root w)
            post (hash-ref "aa99")
            good-binding (transition-binding
                          (:resolved-review-authority-context/root context)
                          pre post (hash-ref "ede1"))]
        (testing "forged fence id"
          (is (= :unknown-fence
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) {:fence/id (str (java.util.UUID/randomUUID))}
                           good-binding nil nil)))))
        (testing "structurally invalid binding"
          (is (= :authority-transition-binding-invalid
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence (dissoc good-binding :artifact/schema) nil nil)))))
        (testing "pre-state mismatch"
          (is (= :fence-pre-state-mismatch
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence
                           (transition-binding
                            (:resolved-review-authority-context/root context)
                            (hash-ref "e5e5e5") post (hash-ref "ede1"))
                           nil nil)))))
        (testing "foreign authority context in binding"
          (is (= :authority-context-mismatch
                 (:reason (state/finalise-under-authority-fence!
                           (:store w) fence
                           (transition-binding (hash-ref "fcfcfc")
                                               pre post (hash-ref "ede1"))
                           nil nil)))))
        (testing "stale fence after unrelated successor publication"
          (let [succ (successor-of w (authenticated-material))]
            (state/publish-successor! (:store w) (:head w) (:envelope succ) (:material succ))
            (is (= :state-not-at-required-head
                   (:reason (state/finalise-under-authority-fence!
                             (:store w) fence good-binding (:envelope succ) (:material succ)))))))))))

;; ── priority 10: atomic successor + binding + fence terminalization ───────

(deftest atomic-successor-binding-terminalization
  (let [w (fresh-store)
        issued (issue-fence w)]
    (is (:ok? issued) "prerequisite: fence issuance on an authenticated store")
    (when (:ok? issued)
      (let [{:keys [context fence]} (:result issued)
            succ (successor-of w (authenticated-material))
            binding (transition-binding
                     (:resolved-review-authority-context/root context)
                     (:state-root w) (:state-root succ) (hash-ref "ede1"))
            store (:store w)
            result (state/finalise-under-authority-fence!
                    store fence binding (:envelope succ) (:material succ))
            snap @(.state store)
            rec (get-in snap [:issued-fences (:fence/id fence)])
            succ-root (:authoritative-state-envelope/root (:envelope succ))]
        (testing "finalisation succeeds and returns the terminal result"
          (is (:finalised? result))
          (is (= succ-root (:authoritative-state-envelope/root (:envelope result))))
          (is (= binding (:authority-binding result))))
        (testing "head, envelope index, material, and binding commit together"
          (is (= succ-root (:head snap)))
          (is (= succ-root (get-in snap [:by-state (:state-root succ)])))
          (is (= (:material succ) (get-in snap [:material (:state-root succ)])))
          (is (= binding (get-in snap [:authority-bindings succ-root]))))
        (testing "fence terminalized with binding and successor identity"
          (is (= :consumed (:status rec)))
          (is (= (:governed-authority-transition-binding/root binding)
                 (:transition-binding/root rec)))
          (is (= succ-root (:successor-envelope/root rec)))
          (is (= result (:result rec))))
        (testing "exact retry replays the original terminal result"
          (is (= result (state/finalise-under-authority-fence!
                         store fence binding (:envelope succ) (:material succ)))))
        (testing "conflicting reuse of the consumed fence rejects"
          (let [conflicting (transition-binding
                             (:resolved-review-authority-context/root context)
                             (:state-root w) (:state-root succ) (hash-ref "dfd1"))]
            (is (= :fence-already-consumed
                   (:reason (state/finalise-under-authority-fence!
                             store fence conflicting (:envelope succ) (:material succ)))))))
        (testing "post-transition state resolves as the new head"
          (let [r (resolve* (assoc w :state-root (:state-root succ)
                                   :head succ-root :envelope (:envelope succ)))]
            (is (:resolved? r))
            (when (:resolved? r)
              (is (= succ-root (get-in r [:context :authority-state/root]))))))))))

;; ── regression: existing resolution semantics on authenticated fixtures ───

(deftest authenticated-state-resolution
  (let [s0 (hash-ref "b0")
        e0 (state/build-envelope (store-envelope s0 nil 0))
        store (state/new-store e0 (authenticated-material))
        w {:store store :state-root s0 :envelope e0
           :head (:authoritative-state-envelope/root e0)}
        b0 (admission-basis w)]
    (is (:resolved? (state/resolve-governed-authority-context store b0)))
    (let [s1 (hash-ref "c1")
          e1 (state/build-envelope
              (store-envelope s1 (:authoritative-state-envelope/root e0) 1))]
      (is (:published?
           (state/publish-successor!
            store (:authoritative-state-envelope/root e0) e1 (authenticated-material))))
      (is (= :state-not-at-required-head
             (:reason (state/resolve-governed-authority-context store b0))))
      (is (:resolved?
           (state/resolve-governed-authority-context
            store (v1-basis :transition-replay s0 (:authoritative-state-envelope/root e1)))))
      (is (:resolved?
           (state/resolve-governed-authority-context
            store (audit-basis-v2 s0 (:authoritative-state-envelope/root e1))))))))

(deftest current-admission-rejects-v1-basis
  (testing "V1 basis is rejected for live current-admission (live downgrade blocked)"
    (let [{:keys [store] :as w} (fresh-store)
          v1-admission (v1-basis :current-admission (:state-root w) (:head w))]
      (is (not (:valid? (resolution/validate-resolution-basis-any v1-admission)))
          "validate-resolution-basis-any rejects V1 for current-admission")
      (is (some #(re-find #"current-admission requires" %)
                (:errors (resolution/validate-resolution-basis-any v1-admission)))
          "rejection cites the current-admission v2 requirement")
      (is (= :resolution-basis-invalid
             (:reason (resolve-basis store v1-admission)))
          "state resolution rejects V1 current-admission at the basis gate")
      (is (:valid? (resolution/validate-resolution-basis-any
                    (v1-basis :transition-replay (:state-root w) (:head w))))
          "V1 remains accepted for transition-replay (historical compatibility)")
      (is (:valid? (resolution/validate-resolution-basis-any
                    (v1-basis :historical-audit (:state-root w) (:head w))))
          "V1 remains accepted for historical-audit (historical compatibility)"))))

(deftest rejects-state-and-round-substitution
  (let [w (fresh-store)]
    (is (= :state-unavailable
           (:reason (resolve* w (hash-ref "ffff") (:head w)))))
    (let [different-round-store
          (state/new-store (:envelope w)
                           (assoc (:material w) :review-round/hash (hash-ref "deadbeef")))]
      (is (= :round-not-found-at-state
             (:reason
              (try
                (state/resolve-governed-authority-context
                 different-round-store
                 (admission-basis (assoc w :store different-round-store)))
                (catch Exception e
                  {:reason ::unexpected-throw :throw (.getMessage e)}))))))))
