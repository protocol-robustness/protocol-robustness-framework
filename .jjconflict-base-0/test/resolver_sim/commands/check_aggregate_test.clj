(ns resolver-sim.commands.check-aggregate-test
  "End-to-end test for the check-aggregate CLI command: it must be reachable
   through dispatch, run the review-aggregate check surface, and emit the
   COMPLETE machine-readable result (never a pass/fail reduction)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.review-round :as rr]
            [resolver-sim.commands.check-aggregate :as ca]))

(defn- write-edn! [path value]
  (spit (io/file path) (pr-str value))
  path)

(defn- valid-round
  []
  (rr/build-review-round
   {:benchmark/content-root "sha256:abc"
    :review-round/purpose :model-admission
    :review-round/members
    [{:review-member/key 0, :researcher/id "researcher-a" :role :model-steward}
     {:review-member/key 1, :researcher/id "researcher-b" :role :independent-reproducer}
     {:review-member/key 2, :researcher/id "researcher-c" :role :adversarial-reviewer}]
    :review-round/membership-frozen-at "2026-07-01T00:00:00Z"
    :review-round/policy-root "sha256:policy"}))

(defn- temp-file [prefix]
  (.getPath (java.io.File/createTempFile prefix ".edn")))

(deftest check-aggregate-holds-on-a-valid-round-and-emits-full-result
  (let [path (temp-file "rc-valid-")]
    (try
      (let [out (with-out-str
                  (let [result (ca/run {:input (write-edn! path {:review-round (valid-round)})})]
                    (is (zero? (:exit-code result)) "a valid round must pass")))]
        (testing "the output is the complete machine-readable result, not a boolean"
          (is (str/includes? out ":holds? true"))
          (is (str/includes? out ":three-member-standard"))
          (is (str/includes? out ":member-bit-width"))
          (is (str/includes? out ":member-key-density"))
          (is (str/includes? out ":checks"))
          (is (str/includes? out ":violations"))))
      (finally (io/delete-file path)))))

(deftest check-aggregate-fails-closed-with-full-violations-on-an-invalid-round
  (let [path (temp-file "rc-invalid-")]
    (try
      (let [round (assoc (valid-round)
                         :review-round/members
                         [{:review-member/key 0, :researcher/id "a" :role :model-steward}
                          {:review-member/key 0, :researcher/id "b" :role :independent-reproducer}
                          {:review-member/key 1, :researcher/id "c" :role :ghost-reviewer}])
            out (with-out-str
                  (let [result (ca/run {:input (write-edn! path {:review-round round})})]
                    (is (= 1 (:exit-code result)) "an invalid round must fail")))]
        (testing "a failing aggregate reports the full set of findings, never a bare boolean"
          (is (str/includes? out "duplicate-member-keys"))
          (is (str/includes? out "unknown-member-role"))
          (is (not (str/includes? out ":holds? true"))
              "a failing aggregate must not be misreported as holding")))
      (finally (io/delete-file path)))))

(deftest check-aggregate-json-output-preserves-the-full-result
  (let [path (temp-file "rc-json-")]
    (try
      (let [out (with-out-str
                  (let [result (ca/run {:input (write-edn! path {:review-round (valid-round)})
                                        :json? true})]
                    (is (zero? (:exit-code result)))))
            parsed (try (clojure.data.json/read-str out :key-fn keyword) (catch Exception _ nil))]
        (testing "JSON output is the complete machine-readable result"
          (is (true? (:holds? parsed)))
          (is (contains? parsed :checks))
          (is (contains? (get-in parsed [:checks :three-member-standard]) :violations))
          (is (empty? (get-in parsed [:checks :three-member-standard :violations])))))
      (finally (io/delete-file path)))))

(deftest check-aggregate-missing-input-fails
  (let [{:keys [exit-code message]} (ca/run {:input nil})]
    (is (= 1 exit-code))
    (is (= "failed to read input" message))))