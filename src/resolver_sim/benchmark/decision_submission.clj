(ns resolver-sim.benchmark.decision-submission
  "Non-authorising retention boundary for governed researcher decisions."
  (:require
   [resolver-sim.benchmark.governed-authority-state :as state]
   [resolver-sim.benchmark.review-governance :as governance]
   [resolver-sim.benchmark.review-round :as rr]
   [resolver-sim.benchmark.researcher-force-authorisation :as rfa]
   [resolver-sim.hash.canonical :as hc]
   [resolver-sim.hash.reference :as ref]))

(def ^:const schema-version "governed-researcher-decision-submission.v1")

(defn decision-root [decision]
  (ref/sha256-ref
   (hc/domain-hash :governed-researcher-decision-submission-v1
                   (hc/project-canonical-safe decision))))

(defn- reject [reason & [errors]]
  {:status :rejected :reason reason :errors (vec (or errors []))})

(defn- exact-governed-key? [governance-body signer-key-set decision round]
  (let [researcher-id (:researcher/id decision)
        key-id (:signing-key/id decision)
        member (governance/member-by-id governance-body researcher-id)
        key (governance/member-signing-key governance-body researcher-id key-id)
        entry (some #(when (and (= researcher-id (:researcher/id %))
                                (= key-id (:signing-key/id %))) %)
                    (:signer-key-set/entries signer-key-set))]
    (and member key entry
         (= (:key/algorithm key) (:signing-key/algorithm entry))
         (= (:key/public-key key) (:signing-key/public-key entry))
         (= :ed25519 (:key/algorithm key))
         (contains? (set (map :researcher/id (:review-round/members round)))
                    researcher-id))))

(defn submit-decision!
  "Validate and immutably retain one governed V2 decision. This never counts a
   threshold, issues authority, or consumes a permit. `store` is an atom whose
   :decisions map is keyed by decision root.

   The input must carry the root-verified frozen `:authority-material` bundle.
   Round, governance, and signer-key material are derived only from that bundle;
   detached caller-supplied body maps are deliberately not an authority source.
   V1 decisions are retained nowhere on this authoritative path because they do
   not commit the required complete outcome."
  [store {:keys [decision authorisation-id request-root outcome-root authority-material]}]
  (let [review-round (:authority-material/review-round authority-material)
        review-governance (:authority-material/review-governance authority-material)
        signer-key-set (:authority-material/signer-key-set authority-material)]
    (cond
      (not (map? decision)) (reject :malformed-decision)
      (not (state/authenticated-authority-material? authority-material))
      (reject :authority-material-unavailable)
      (not= :v2-complete-outcome (rfa/classify-decision-version decision))
      (reject :unsupported-decision-version)
      (not (rr/governed-round? review-round)) (reject :invalid-review-round)
      (not= authorisation-id (:authorisation/id decision))
      (reject :authorisation-id-mismatch)
      (not= request-root (:authorisation/request-root decision))
      (reject :request-mismatch)
      (not= (:review-round/hash decision)
            (:review-round/hash authority-material)) (reject :round-mismatch)
      (and outcome-root (not= outcome-root (:outcome/root decision)))
      (reject :outcome-mismatch)
      (not (contains? (set (map :researcher/id (:review-round/members review-round)))
                      (:researcher/id decision)))
      (reject :unknown-member)
      (not (exact-governed-key? review-governance signer-key-set decision review-round))
      (reject :governed-key-mismatch)
      (not (rfa/decision-hash-valid? decision authorisation-id))
      (reject :decision-hash-mismatch)
      :else
      (let [signature (state/verify-decision-signature-with-entries
                       signer-key-set decision)
            root (decision-root decision)]
        (cond
          (not (:valid? signature)) (reject :signature-invalid [(:reason signature)])
          (contains? (get @store :decisions {}) root)
          {:status :duplicate :decision/root root :decision decision}
          (some (fn [[_ d]] (and (= (:researcher/id d) (:researcher/id decision))
                                 (not= (decision-root d) root)))
                (get @store :decisions {}))
          (reject :seat-equivocation)
          :else
          (do (swap! store update :decisions (fnil assoc {}) root decision)
              {:status :accepted :decision/root root :decision decision}))))))

(defn decision-set [store]
  (->> (vals (get @store :decisions {}))
       (sort-by (juxt :researcher/id :decision/hash))
       vec))
