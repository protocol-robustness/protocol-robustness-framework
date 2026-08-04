(ns resolver-sim.extensions.envelope-test
  "Phase 1: closed core, open extension envelope — four-way classification,
   lossless preservation, hash binding, and the shareable envelope shape."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.extensions.envelope :as env]))

(def base-record
  {:method :weighted
   :inputs {:amount 100}})

(def envelope
  {:org.example/risk-model {:version 1 :parameters {:cap 50}}
   :org.example/secondary {:version 2}})

(def record-with-envelope
  (assoc base-record :extensions envelope))

(defn- resolver
  [{:keys [recognized available valid]}]
  (fn [extension-id payload]
    (cond
      (not (contains? recognized extension-id)) :unrecognized
      (not (contains? available extension-id)) {:status :unavailable}
      (valid extension-id payload) {:status :valid}
      :else {:status :invalid
             :violations [{:violation/id :fixture/payload-invalid
                           :details {:extension/id extension-id :payload payload}}]})))

;; ── envelope access ───────────────────────────────────────────────────────

(deftest envelope-access
  (is (env/has-envelope? record-with-envelope))
  (is (not (env/has-envelope? base-record)))
  (is (= envelope (env/envelope-of record-with-envelope)))
  (is (nil? (env/envelope-of base-record)))
  (is (= base-record (env/without-envelope record-with-envelope)))
  (is (= record-with-envelope
         (env/preserve-envelope (env/without-envelope record-with-envelope)
                                record-with-envelope))))

(deftest envelope-preserved-losslessly-through-validation-cycle
  (let [core-view (env/without-envelope record-with-envelope)
        recombined (env/preserve-envelope core-view record-with-envelope)]
    (is (= record-with-envelope recombined))))

;; ── classification ────────────────────────────────────────────────────────

(deftest recognized-and-validated
  (let [r (resolver {:recognized #{:org.example/risk-model :org.example/secondary}
                     :available #{:org.example/risk-model :org.example/secondary}
                     :valid (constantly true)})
        {:keys [valid? classifications]} (env/validate-envelope record-with-envelope r)]
    (is valid?)
    (is (= :recognized-validated
           (get (first (filter #(= :org.example/risk-model (:extension/id %)) classifications))
                :classification)))
    (is (= :recognized-validated
           (get (first (filter #(= :org.example/secondary (:extension/id %)) classifications))
                :classification)))))

(deftest recognized-but-unavailable
  (let [r (resolver {:recognized #{:org.example/risk-model}
                     :available #{}
                     :valid (constantly true)})
        {:keys [valid? classifications]}
        (env/validate-envelope record-with-envelope r)]
    ;; unavailable is preserved, never represented as validated, and does not fail
    (is valid?)
    (is (= :recognized-unavailable
           (get (first (filter #(= :org.example/risk-model (:extension/id %)) classifications))
                :classification)))))

(deftest unrecognized-preserved-opaquely
  (let [r (resolver {:recognized #{} :available #{} :valid (constantly true)})
        {:keys [valid? classifications]}
        (env/validate-envelope record-with-envelope r)]
    (is valid?)
    (is (= :unrecognized
           (get (first (filter #(= :org.example/risk-model (:extension/id %)) classifications))
                :classification)))
    (is (= envelope (env/envelope-of record-with-envelope))
        "unrecognized payloads are preserved losslessly")))

(deftest malformed-entry-fails
  (let [r (resolver {:recognized #{:org.example/risk-model}
                     :available #{:org.example/risk-model}
                     :valid (constantly false)})
        {:keys [valid? violations classifications]}
        (env/validate-envelope record-with-envelope r)]
    (is (not valid?))
    (is (= :malformed
           (get (first (filter #(= :org.example/risk-model (:extension/id %)) classifications))
                :classification)))
    (is (some #(= :extensions/error-malformed-envelope-entry (:violation/id %))
              violations))))

(deftest non-map-envelope-fails
  (let [bad (assoc base-record :extensions [1 2 3])
        r (resolver {:recognized #{} :available #{} :valid (constantly true)})
        {:keys [valid? violations]} (env/validate-envelope bad r)]
    (is (not valid?))
    (is (some #(= :extensions/error-invalid-envelope-shape (:violation/id %))
              violations))))

(deftest invalid-resolver-result-throws
  (let [bad (assoc base-record :extensions {:org.example/risk-model {:x 1}})
        resolver (fn [_ _] :nonsense)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (env/validate-envelope bad resolver)))))

;; ── hash binding ──────────────────────────────────────────────────────────

(deftest envelope-is-hash-bound
  (let [bindings (env/envelope-hash-binding "EXTENSION_ENVELOPE_TEST_V1" record-with-envelope)
        tampered (env/envelope-hash-binding "EXTENSION_ENVELOPE_TEST_V1"
                                            (assoc-in record-with-envelope
                                                      [:extensions :org.example/risk-model :parameters :cap] 51))]
    (is (not= (:with-envelope bindings) (:without-envelope bindings)))
    (is (= (:without-envelope bindings)
           (:without-envelope
            (env/envelope-hash-binding "EXTENSION_ENVELOPE_TEST_V1"
                                       (env/without-envelope record-with-envelope)))))
    (is (not= (:with-envelope bindings) (:with-envelope tampered))
        "changing an envelope payload changes the committed hash")))

;; ── shareable envelope shape ──────────────────────────────────────────────

(deftest shape-of-replaces-values-with-type-tags
  (is (= {:version :integer :parameters {:cap :integer}}
         (env/shape-of {:version 1 :parameters {:cap 50}})))
  (is (= {:org.example/risk-model {:version :integer :parameters {:cap :integer}}
          :org.example/secondary {:version :integer}}
         (env/shape-of envelope))))

(deftest envelope-shape-extracted
  (is (= {:org.example/risk-model {:version :integer :parameters {:cap :integer}}
          :org.example/secondary {:version :integer}}
         (env/envelope-shape record-with-envelope)))
  (is (nil? (env/envelope-shape base-record))))

(deftest shape-hash-shared-across-equal-shapes
  (testing "structurally identical envelopes share a shape hash despite value differences"
    (let [a (assoc-in record-with-envelope [:extensions :org.example/risk-model :parameters :cap] 50)
          b (assoc-in record-with-envelope [:extensions :org.example/risk-model :parameters :cap] 999)]
      (is (= (env/envelope-shape-hash a)
             (env/envelope-shape-hash b))))))

(deftest shape-hash-changes-with-shape
  (is (not= (env/envelope-shape-hash record-with-envelope)
            (env/envelope-shape-hash
             (assoc-in record-with-envelope
                       [:extensions :org.example/risk-model :parameters :other] 1))))
  (is (not= (env/envelope-shape-hash record-with-envelope)
            (env/envelope-shape-hash
             (assoc-in record-with-envelope
                       [:extensions :org.example/risk-model :parameters :cap] "50"))))
  (is (not= (env/envelope-shape-hash record-with-envelope)
            (env/envelope-shape-hash
             (update record-with-envelope :extensions
                     dissoc :org.example/secondary)))))

(deftest shape-hash-format
  (is (re-matches #"sha256:[0-9a-f]{64}" (env/envelope-shape-hash record-with-envelope))))

(deftest shape-artifact-shareable-edn
  (let [a (env/build-envelope-shape-artifact record-with-envelope)
        b (env/build-envelope-shape-artifact record-with-envelope)
        round-trip (edn/read-string (pr-str a))]
    (is (= a b))
    (is (= a round-trip))
    (is (= (:shape/hash a) (:shape/hash b)))
    (is (= 1 (:shape/schema-version a)))
    (is (= :extension-envelope (:shape/domain a)))
    (is (re-matches #"sha256:[0-9a-f]{64}" (:shape/hash a)))))
