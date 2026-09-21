(ns resolver-sim.benchmark.researcher-axis
  "Projection-only, root-bound researcher coordinates.

   Researcher identity remains the governed :researcher/id plus the existing
   signer-key-set and review-round semantics. This namespace derives a dense
   researcher axis for one authenticated authority snapshot; coordinates are
   never global identities and never enter signed interaction statements."
  (:require [resolver-sim.benchmark.governed-authority-state :as authority-state]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.research-definition :as research]))

(def ^:const axis-kind :researcher)

(defn- authority-body [authority-material key]
  (or (get authority-material key)
      (throw (ex-info "missing frozen authority body"
                      {:key key}))))

(defn- verify-authority-roots! [authority-material review-round governance-body signer-key-set]
  (let [expected-round-root (:review-round/root authority-material)
        expected-governance-root (:review-governance/root authority-material)
        expected-key-set-root (:signer-key-set/root authority-material)
        actual-round-root (authority-state/review-round-material-root review-round)
        actual-governance-root (governance/governance-root governance-body)
        actual-key-set-root (authority-state/signer-key-set-root signer-key-set)]
    (doseq [[field expected actual] [[:review-round/root expected-round-root actual-round-root]
                                     [:review-governance/root expected-governance-root actual-governance-root]
                                     [:signer-key-set/root expected-key-set-root actual-key-set-root]]]
      (when-not (= expected actual)
        (throw (ex-info "frozen authority root does not match supplied body"
                        {:field field :expected expected :actual actual}))))))

(defn- valid-researcher-ids [review-round governance-body signer-key-set]
  (let [entries (:signer-key-set/entries signer-key-set)
        member-ids (->> (:review-round/members review-round)
                        (map :researcher/id)
                        distinct)]
    (->> member-ids
         (filter (fn [researcher-id]
                   (some (fn [entry]
                           (and (= researcher-id (:researcher/id entry))
                                (= :resolved
                                   (authority-state/resolve-position-status
                                    review-round governance-body signer-key-set
                                    {:researcher/id researcher-id
                                     :signing-key/id (:signing-key/id entry)}))))
                         entries)))
         (sort-by str)
         vec)))

(defn- validate-axis! [axis]
  (when-not (map? axis)
    (throw (ex-info "researcher axis must be a map" {:axis axis})))
  (when-not (= research/axis-schema (:artifact/schema axis))
    (throw (ex-info "researcher axis schema is invalid" {:axis axis})))
  (when-not (= axis-kind (:axis/kind axis))
    (throw (ex-info "researcher axis kind is invalid" {:axis/kind (:axis/kind axis)})))
  (when-not (and (string? (:axis/root axis))
                 (= (:axis/root axis) (research/axis-root (dissoc axis :axis/root))))
    (throw (ex-info "researcher axis root is invalid" {:axis/root (:axis/root axis)})))
  (let [members (:axis/members axis)
        keys (mapv :axis/key members)
        ids (mapv :axis/id members)]
    (when-not (and (vector? members)
                   (= keys (vec (range (count members))))
                   (= (count keys) (count (distinct keys)))
                   (= (count ids) (count (distinct ids)))
                   (every? #(and (string? %) (seq %)) ids)
                   (every? #(= {:researcher/id (:axis/id %)} (:axis/member %)) members))
      (throw (ex-info "researcher axis members are invalid" {:members members}))))
  axis)

(defn researcher-coordinate
  "Resolve a semantic researcher ID to its scoped researcher coordinate."
  [axis researcher-id]
  (validate-axis! axis)
  (when-not (and (string? researcher-id) (seq researcher-id))
    (throw (ex-info "researcher ID is invalid" {:researcher/id researcher-id})))
  (if-let [member (some #(when (= researcher-id (:axis/id %)) %) (:axis/members axis))]
    {:scope/root (:axis/root axis) :axis axis-kind :key (:axis/key member)}
    (throw (ex-info "researcher ID is outside the researcher axis"
                    {:researcher/id researcher-id :scope/root (:axis/root axis)}))))

(defn researcher-member
  "Resolve a scoped researcher coordinate to its committed researcher binding."
  [axis coordinate]
  (validate-axis! axis)
  (when-not (and (map? coordinate)
                 (= #{:scope/root :axis :key} (set (keys coordinate)))
                 (= (:scope/root coordinate) (:axis/root axis))
                 (= (:axis coordinate) axis-kind)
                 (integer? (:key coordinate))
                 (not (neg? (:key coordinate))))
    (throw (ex-info "researcher coordinate is invalid or out of scope"
                    {:coordinate coordinate :scope/root (:axis/root axis)})))
  (if-let [member (some #(when (= (:key coordinate) (:axis/key %)) %) (:axis/members axis))]
    (:axis/member member)
    (throw (ex-info "researcher coordinate is out of range"
                    {:coordinate coordinate :scope/root (:axis/root axis)}))))

(defn build-researcher-axis
  "Build the dense researcher axis for frozen authenticated authority material.

   Membership is exactly the existing combined review-round membership plus
   signer-key eligibility path: the signer-key set is cross-checked against
   governance, then each member must have at least one key whose existing
   `resolve-position-status` is `:resolved`. Multiple valid keys yield one
   researcher coordinate."
  [authority-material]
  (let [review-round (authority-body authority-material :authority-material/review-round)
        governance-body (authority-body authority-material :authority-material/review-governance)
        signer-key-set (authority-body authority-material :authority-material/signer-key-set)]
    (verify-authority-roots! authority-material review-round governance-body signer-key-set)
    (let [eligibility (authority-state/signer-key-eligible-in-governance?
                       signer-key-set governance-body)]
      (when-not (:eligible? eligibility)
        (throw (ex-info "frozen signer-key set is not governance-eligible"
                        {:errors (:errors eligibility)})))
      (let [ids (valid-researcher-ids review-round governance-body signer-key-set)
            axis {:artifact/schema research/axis-schema
                  :axis/kind axis-kind
                  :axis/scope {:review-round/root (:review-round/root authority-material)
                               :signer-key-set/root (:signer-key-set/root authority-material)}
                  :axis/members (mapv (fn [key researcher-id]
                                        {:axis/key key
                                         :axis/id researcher-id
                                         :axis/member {:researcher/id researcher-id}})
                                      (range) ids)}]
        (assoc axis :axis/root (research/axis-root axis))))))

