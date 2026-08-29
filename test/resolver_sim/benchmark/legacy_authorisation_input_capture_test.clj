(ns resolver-sim.benchmark.legacy-authorisation-input-capture-test
  "Replay conformance for immutable captures of legacy raw authority input."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.assurance.three-member-authority :as authority]
            [resolver-sim.benchmark.legacy-authorisation-input-capture :as capture]
            [resolver-sim.hash.canonical :as hc]))

(def ^:private authorisation-id :authorisation/legacy-replay)
(def ^:private request-root (str "sha256:" (apply str (take 64 (cycle "a1")))))
(def ^:private round-hash (str "sha256:" (apply str (take 64 (cycle "b2")))))
(def ^:private outcome-root (str "sha256:" (apply str (take 64 (cycle "c3")))))

(def ^:private review-round
  {:review-round/members [{:researcher/id "r-a" :role :model-steward}
                          {:researcher/id "r-b" :role :independent-reproducer}
                          {:researcher/id "r-c" :role :adversarial-reviewer}]})

(defn- v1-position [member]
  {:researcher/id member
   :authorisation/request-root request-root
   :review-round/hash round-hash
   :decision :approve
   :decision/hash
   (str "sha256:"
        (hc/domain-hash :researcher-decision
                        {:researcher/id member
                         :authorisation/id authorisation-id
                         :authorisation/request-root request-root
                         :review-round/hash round-hash
                         :decision :approve}))
   :signature {:value (str "v1-" member) :signed-at "t0"}})

(defn- v2-position [member]
  {:schema-version "researcher-decision.v2"
   :researcher/id member
   :authorisation/id authorisation-id
   :authorisation/request-root request-root
   :review-round/hash round-hash
   :outcome/root outcome-root
   :decision :approve
   :decision/hash
   (str "sha256:"
        (hc/domain-hash :researcher-decision-v2
                        {:researcher/id member
                         :authorisation/id authorisation-id
                         :authorisation/request-root request-root
                         :review-round/hash round-hash
                         :outcome/root outcome-root
                         :decision :approve}))
   :signature {:value (str "v2-" member) :signed-at "t0"}})

(defn- raw-authorisation [positions]
  {:authorisation/id authorisation-id
   :authorisation/request-root request-root
   :authorisation/review-round {:review-round/hash round-hash}
   :authorisation/target {:target/proposed-content-root outcome-root}
   :authorisation/decision-references positions})

(defn- raw-evaluator [authorisation]
  (authority/evaluate-three-member-authority
   :authorisation authorisation
   :review-round review-round
   :signature-valid? (constantly true)))

(deftest legacy-capture-replay-conforms-to-raw-evaluator-across-decision-versions
  (doseq [[label positions expected-status]
          [["v1" [(v1-position "r-a") (v1-position "r-b")] :not-authorised]
           ["v2" [(v2-position "r-a") (v2-position "r-b") (v2-position "r-c")] :authorised]
           ["mixed" [(v1-position "r-a") (v2-position "r-b")] :not-authorised]
           ["unknown-version" [(assoc (v1-position "r-a")
                                      :schema-version "researcher-decision.v9")
                               (assoc (v1-position "r-b")
                                      :schema-version "researcher-decision.v9")]
            :not-authorised]]]
    (testing label
      (let [raw (raw-authorisation positions)
            input-capture (capture/build-capture raw)]
        (is (:valid? (capture/validate-capture input-capture)))
        (is (= (raw-evaluator raw)
               (capture/replay input-capture raw-evaluator)))
        (is (= expected-status
               (:authority-status (capture/replay input-capture raw-evaluator))))))))

(deftest unknown-nested-raw-field-diverges-capture-root-without-changing-raw-evaluation
  (let [raw (raw-authorisation [(v2-position "r-a") (v2-position "r-b")])
        raw-with-unknown-field (assoc-in raw
                                         [:authorisation/target :legacy/unknown]
                                         :uninterpreted)
        capture-a (capture/build-capture raw)
        capture-b (capture/build-capture raw-with-unknown-field)]
    (is (= (raw-evaluator raw)
           (raw-evaluator raw-with-unknown-field))
        "the raw evaluator does not interpret the unknown nested field")
    (is (not= (:authorisation-input/root capture-a)
              (:authorisation-input/root capture-b))
        "captures commit the exact raw map, including unknown nested fields")
    (is (= (raw-evaluator raw-with-unknown-field)
           (capture/replay capture-b raw-evaluator)))))
