(ns resolver-sim.benchmark.researcher-axis-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.researcher-axis :as axis]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.review-round :as rr]))

(defn- hash-ref [seed]
  (str "sha256:" (apply str (take 64 (cycle seed)))))

(defn- throws? [thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo _ true)))

(def public-key
  "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")

(defn- governance-body [researchers]
  {:schema-version "review-governance.v1"
   :governance/epoch 0
   :governance/roles #{:reviewer}
   :governance/principals
   (mapv (fn [researcher-id]
           {:principal/id researcher-id
            :status :active
            :principal/independence-group (str "group-" researcher-id)
            :principal/independence-basis-root (hash-ref "ab")
            :principal/keys
            (mapv (fn [key-id]
                    {:key/id key-id
                     :status :active
                     :key/algorithm :ed25519
                     :key/public-key public-key})
                  (case researcher-id
                    "r1" ["k1" "k2"]
                    "r2" ["k3" "k4"]
                    []))})
         researchers)
   :governance/members
   (mapv (fn [researcher-id]
           {:reviewer/member-id researcher-id
            :principal/id researcher-id
            :status :active
            :granted-roles #{:reviewer}})
         researchers)
   :governance/policies
   [{:policy/id "p1"
     :member-count 3
     :threshold 2
     :required-roles #{:reviewer}
     :role-cardinality :unique
     :equivocation-policy :invalid-seat}]})

(defn- signer-key-set [entries]
  {:artifact/schema state/signer-key-set-schema
   :signer-key-set/entries
   (mapv (fn [[researcher-id key-id]]
           {:researcher/id researcher-id
            :signing-key/id key-id
            :signing-key/algorithm :ed25519
            :signing-key/public-key public-key})
         entries)})

(defn- review-round [researchers]
  {:artifact/schema rr/governed-schema-version
   :schema-version rr/governed-schema-version
   :benchmark/content-root (hash-ref "aa")
   :review-round/members (mapv (fn [researcher-id]
                                 {:researcher/id researcher-id
                                  :role :reviewer})
                               researchers)
   :review-round/membership-frozen-at 0
   :review-round/policy-root (hash-ref "bb")
   :review-round/purpose :model-admission
   :review-round/chain-configuration-root (hash-ref "cc")
   :review-round/governance-root (hash-ref "dd")
   :review-round/governance-epoch 0
   :review-round/constituted-at 0
   :review-round/policy-id "p1"
   :review-round/policy-hash (hash-ref "ee")})

(defn- authority-material
  ([researchers]
   (authority-material researchers
                       (mapv (fn [researcher-id]
                               [researcher-id (case researcher-id
                                                "r1" "k1"
                                                "r2" "k3")])
                             researchers)))
  ([researchers entries]
   (let [round (review-round researchers)
         governance-researchers (vec (distinct (concat researchers (map first entries))))
         gov (governance-body governance-researchers)
         ks (signer-key-set entries)]
     {:review-round/root (state/review-round-material-root round)
      :review-governance/root (governance/governance-root gov)
      :signer-key-set/root (state/signer-key-set-root ks)
      :authority-material/review-round round
      :authority-material/review-governance gov
      :authority-material/signer-key-set ks})))

(deftest researcher-axis-is-dense-and-deterministically-ordered
  (let [material (authority-material ["r2" "r1"])
        researcher-axis (axis/build-researcher-axis material)
        members (:axis/members researcher-axis)]
    (is (= :researcher (:axis/kind researcher-axis)))
    (is (= [0 1] (mapv :axis/key members)))
    (is (= ["r1" "r2"] (mapv :axis/id members)))
    (is (= 2 (count (set (map :axis/id members)))))
    (is (= (get-in material [:review-round/root])
           (get-in researcher-axis [:axis/scope :review-round/root])))
    (is (= (get-in material [:signer-key-set/root])
           (get-in researcher-axis [:axis/scope :signer-key-set/root])))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:axis/root researcher-axis)))))

(deftest coordinate-resolution-is-scoped-and-fails-closed
  (let [researcher-axis (axis/build-researcher-axis (authority-material ["r1" "r2"]))
        coordinate (axis/researcher-coordinate researcher-axis "r1")]
    (is (= {:researcher/id "r1"}
           (axis/researcher-member researcher-axis coordinate)))
    (is (throws? #(axis/researcher-coordinate researcher-axis "unknown")))
    (doseq [invalid [{:scope/root (hash-ref "ff") :axis :researcher :key 0}
                     {:scope/root (:axis/root researcher-axis) :axis :case :key 0}
                     {:scope/root (:axis/root researcher-axis) :axis :researcher :key -1}
                     {:scope/root (:axis/root researcher-axis) :axis :researcher :key 1.5}
                     {:scope/root (:axis/root researcher-axis) :axis :researcher :key 99}
                     {:scope/root (:axis/root researcher-axis) :axis :researcher}]]
      (is (throws? #(axis/researcher-member researcher-axis invalid))))))

(deftest multiple-valid-keys-produce-one-researcher-coordinate
  (let [researcher-axis (axis/build-researcher-axis
                         (authority-material ["r1"] [["r1" "k1"] ["r1" "k2"]]))]
    (is (= 1 (count (:axis/members researcher-axis))))
    (is (= "r1" (:axis/id (first (:axis/members researcher-axis)))))
    (is (= {:researcher/id "r1"}
           (:axis/member (first (:axis/members researcher-axis)))))))

(deftest only-round-members-with-existing-eligible-keys-are-included
  (let [material (authority-material ["r1"] [["r1" "k1"] ["r2" "k3"]])
        researcher-axis (axis/build-researcher-axis material)]
    (is (= ["r1"] (mapv :axis/id (:axis/members researcher-axis))))))

(deftest authority-roots-are-part-of-axis-commitment
  (let [base (authority-material ["r1" "r2"])
        changed-round (authority-material ["r1"])
        changed-key-set (authority-material ["r1" "r2"]
                                            [["r1" "k1"] ["r1" "k2"] ["r2" "k3"]
                                             ["r2" "k4"]])]
    (is (not= (:axis/root (axis/build-researcher-axis base))
              (:axis/root (axis/build-researcher-axis changed-round))))
    (is (not= (:axis/root (axis/build-researcher-axis base))
              (:axis/root (axis/build-researcher-axis changed-key-set))))))

(deftest stale-authority-body-is-rejected
  (let [material (authority-material ["r1" "r2"])
        stale (assoc material :signer-key-set/root (hash-ref "ff"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"frozen authority root does not match supplied body"
                          (axis/build-researcher-axis stale)))))
