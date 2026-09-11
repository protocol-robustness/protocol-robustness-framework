(ns resolver-sim.pro-rata.read-model-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [java.nio.file :as Files]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.read-model :as read-model]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.invocation-publication-binding-test :as fixture]
            [resolver-sim.pro-rata.postgres-publication-store :as pg])
  (:import [java.nio.file Files FileAttribute]))

;; ── Helpers ────────────────────────────────────────────────────────────────

(def ^:private root-field-map
  "Maps artifact role keywords to the field holding their self-root, for CAS
   persistence during tests."
  {:publication/head :publication/head-root
   :publication/ordering :transaction-ordering/hash
   :publication/binding :pro-rata-invocation-publication-binding/root
   :capability-binding :binding/root
   :use-case-application :application/root
   :executable-distribution :executable-distribution/root
   :pro-rata-output :pro-rata-output/root
   :allocation :allocation/hash
   :canonical-transition :canonical-effect-transition/root
   :pro-rata-transition :transition/root
   :applied-effect-receipt :applied-effect-receipt/root
   :protocol-transaction-realization :protocol-transaction-realization/root})

(defn- temp-cas-store []
  (cas/create-store
   (str (Files/createTempDirectory "resolver-sim-read-model"
                                   (make-array FileAttribute 0)))))

(defn- put! [store root field-key artifact]
  (cas/put-if-absent! store
                      {:hash-reference root
                       :artifact artifact
                       :verify #(true? %)}))

(defn- build-test-chain
  "Build a complete V1 publication chain in a CAS store.
   Returns {:head head :binding binding :resolution-fn resolution-fn :head-root root}"
  []
  (let [resolved (@#'fixture/fixture)
        ordering (:publication-ordering resolved)
        binding (binding/build-binding resolved)
        head-base {:schema-version publication/application-publication-head-schema
                   :publication/last-ordering-root (:transaction-ordering/hash ordering)
                   :publication/application-binding-root (:pro-rata-invocation-publication-binding/root binding)
                   :publication/sequence 1
                   :publication/predecessor-root nil}
        head (assoc head-base :publication/head-root (publication/application-head-root head-base))
        cas-store (temp-cas-store)
        resolution (fn [root] (cas/resolve-artifact cas-store root))]
    (doseq [entry [[:publication/head head :publication/head-root]
                   [:publication/ordering ordering :transaction-ordering/hash]
                   [:publication/binding binding :pro-rata-invocation-publication-binding/root]
                   [:capability-binding (:capability-binding resolved) :binding/root]
                   [:use-case-application (:use-case-application resolved) :application/root]
                   [:executable-distribution (:executable-distribution resolved) :executable-distribution/root]
                   [:pro-rata-output (:output resolved) :pro-rata-output/root]
                   [:allocation (:allocation resolved) :allocation/hash]
                   [:canonical-transition (:canonical-transition resolved) :canonical-effect-transition/root]
                   [:pro-rata-transition (:pro-rata-transition resolved) :transition/root]
                   [:applied-effect-receipt (:receipt resolved) :applied-effect-receipt/root]
                   [:protocol-transaction-realization (:protocol-transaction-realization resolved)
                    :protocol-transaction-realization/root]]]
      (let [[_ body field] entry
            root (ref/sha256-ref (get body field))]
        (cas/put-if-absent! cas-store {:hash-reference root
                                       :artifact body
                                       :verify #(true? %)})))
    {:head head
     :binding binding
     :resolution-fn resolution
     :head-root (:publication/head-root head)}))

;; ── Status and reason vocabulary invariants ────────────────────────────────

(deftest status-vocabulary-is-closed
  (testing "authority has exactly :committed and :unavailable"
    (is (= #{:committed :unavailable} (get read-model/status-vocabulary :authority))))
  (testing "correspondence has exactly :verified and :unverified"
    (is (= #{:verified :unverified} (get read-model/status-vocabulary :correspondence))))
  (testing "semantic-recompilation has exactly :verified :unavailable :invalid :not-applicable"
    (is (= #{:verified :unavailable :invalid :not-applicable}
           (get read-model/status-vocabulary :semantic-recompilation))))
  (testing "reachability has exactly :complete and :incomplete"
    (is (= #{:complete :incomplete} (get read-model/status-vocabulary :reachability)))))

(deftest reason-vocabulary-is-closed
  (testing "authority reasons are exactly :missing :corrupt :root-mismatch"
    (is (= #{:missing :corrupt :root-mismatch} (get read-model/reason-vocabulary :authority))))
  (testing "correspondence reasons include :correspondence-mismatch"
    (is (= #{:missing :corrupt :root-mismatch :correspondence-mismatch}
           (get read-model/reason-vocabulary :correspondence))))
  (testing "semantic-recompilation reasons include :unsupported-schema"
    (is (= #{:missing :corrupt :root-mismatch :unsupported-schema :correspondence-mismatch}
           (get read-model/reason-vocabulary :semantic-recompilation))))
  (testing "all-reasons is the union of all dimension reasons"
    (is (= (set (keys read-model/reason-vocabulary))
           (set (keys read-model/status-vocabulary))))
    (is (= (into #{} (apply concat (vals read-model/reason-vocabulary)))
           read-model/all-reasons)))
  (testing "no overlap: status keywords are not reason keywords"
    (is (empty? (set/intersection
                 (into #{} (apply concat (vals read-model/status-vocabulary)))
                 read-model/all-reasons)))))

;; ── Trace from head root ───────────────────────────────────────────────────

(deftest trace-from-head-root-resolves-full-chain
  (let [{:keys [head resolution-fn]} (build-test-chain)
        trace (read-model/trace-authoritative-publication
               resolution-fn {:head-root (:publication/head-root head)})]
    (is (= :head-root (:trace/entry-point trace)))
    (is (= :complete (:trace/status trace)))
    (is (empty? (:trace/reasons trace)))
    (is (seq (:trace/edges trace)))
    (is (every? :resolved? (:trace/edges trace)))
    (is (every? nil? (map :reason (:trace/edges trace))))
    (let [roles (set (map :artifact-role (:trace/edges trace)))]
      (is (contains? roles :publication/head))
      (is (contains? roles :publication/ordering))
      (is (contains? roles :publication/binding))
      (is (contains? roles :applied-effect-receipt))
      (is (contains? roles :protocol-transaction-realization))
      (is (contains? roles :capability-binding))
      (is (contains? roles :pro-rata-output))
      (is (contains? roles :allocation))
      (is (contains? roles :canonical-transition))
      (is (contains? roles :use-case-application))
      (is (contains? roles :executable-distribution)))))

(deftest trace-from-head-root-entry-point-not-found
  (let [cas-store (temp-cas-store)
        resolution (fn [root] (cas/resolve-artifact cas-store root))
        trace (read-model/trace-authoritative-publication
               resolution {:head-root "sha256:aaaa0000000000000000000000000000000000000000000000000000000000aa"})]
    (is (= :head-root (:trace/entry-point trace)))
    (is (= :incomplete (:trace/status trace)))
    (is (= [:missing] (:trace/reasons trace)))
    (is (empty? (rest (:trace/edges trace))))
    (let [head-edge (first (:trace/edges trace))]
      (is (false? (:resolved? head-edge))
          "head edge is unresolved")
      (is (= :missing (:reason head-edge))))))

(deftest trace-from-head-root-corrupt-body
  (let [{:keys [head resolution-fn]} (build-test-chain)
        root (:publication/head-root head)
        corrupt-resolution (fn [r]
                             (if (= r root)
                               (assoc head :publication/last-ordering-root
                                      "sha256:bbbb00000000000000000000000000000000000000000000000000000000bbbb")
                               (resolution-fn r)))
        trace (read-model/trace-authoritative-publication
               corrupt-resolution {:head-root root})
        head-edge (first (:trace/edges trace))]
    (is (= :head-root (:trace/entry-point trace)))
    (is (false? (:resolved? head-edge))
        "corrupt head is not resolved")
    (is (= :corrupt (:reason head-edge)))))

;; ── Trace from ordering root ───────────────────────────────────────────────

(deftest trace-from-ordering-root-resolves-forward
  (let [{:keys [head resolution-fn]} (build-test-chain)
        ordering-root (:publication/last-ordering-root head)
        trace (read-model/trace-authoritative-publication
               resolution-fn {:ordering-root ordering-root})]
    (is (= :ordering-root (:trace/entry-point trace)))
    (is (= :complete (:trace/status trace)))
    (is (empty? (:trace/reasons trace)))
    (is (seq (:trace/edges trace)))
    (is (every? :resolved? (:trace/edges trace)))
    (let [roles (set (map :artifact-role (:trace/edges trace)))]
      (is (contains? roles :publication/ordering))
      (is (contains? roles :applied-effect-receipt))
      (is (contains? roles :protocol-transaction-realization))
      (is (contains? roles :canonical-transition))
      (is (not (contains? roles :publication/head))
          "ordering-root entry does not trace backward to head"))))

(deftest trace-from-ordering-root-not-found
  (let [cas-store (temp-cas-store)
        resolution (fn [root] (cas/resolve-artifact cas-store root))
        trace (read-model/trace-authoritative-publication
               resolution {:ordering-root
                           "sha256:cccc00000000000000000000000000000000000000000000000000000000cccc"})]
    (is (= :ordering-root (:trace/entry-point trace)))
    (is (= :incomplete (:trace/status trace)))
    (is (= [:missing] (:trace/reasons trace)))
    (let [ordering-edge (first (:trace/edges trace))]
      (is (false? (:resolved? ordering-edge)))
      (is (= :missing (:reason ordering-edge))))))

;; ── Public trace serialization ─────────────────────────────────────────────

(deftest public-trace-strips-bodies
  (let [{:keys [head resolution-fn]} (build-test-chain)
        trace (read-model/trace-authoritative-publication
               resolution-fn {:head-root (:publication/head-root head)})
        public (read-model/public-trace trace)]
    (is (map? public))
    (is (vector? (:trace/edges public)))
    (is (every? #(not (contains? % :body)) (:trace/edges public))
        "no edge contains :body after public-trace")
    (is (every? :resolved? (:trace/edges public))
        "all edges resolved in full trace")))

;; ── Diagnostic result shape ────────────────────────────────────────────────

(deftest diagnostic-result-all-missing
  (let [result (read-model/diagnostic-result
                {:head nil :binding nil :ordering nil
                 :head-reason :missing :binding-reason nil :ordering-reason nil
                 :authoritative? false :correspondence? false :reachable? false
                 :semantic-status :not-applicable
                 :missing-roots [] :roots {} :v2? false})]
    (is (= :unavailable (get-in result [:authority :status])))
    (is (= :missing (get-in result [:authority :reason])))
    (is (= :nil (get-in result [:authority :root])))  ; head is nil -> root is nil
    (is (= :unverified (get-in result [:correspondence :status])))
    (is (= :missing (get-in result [:correspondence :reason])))
    (is (= :not-applicable (get-in result [:semantic-recompilation :status])))
    (is (nil? (get-in result [:semantic-recompilation :reason])))
    (is (= :incomplete (get-in result [:reachability :status])))
    (is (= :missing (get-in result [:reachability :reason])))
    (is (empty? (get-in result [:reachability :missing-roots])))))

(deftest diagnostic-result-authority-root-mismatch
  (let [fake-head {:publication/head-root "sha256:aaaa0000000000000000000000000000000000000000000000000000000000aa"
                   :publication/last-ordering-root "sha256:bbbb00000000000000000000000000000000000000000000000000000000bbbb"
                   :publication/application-binding-root "sha256:cccc00000000000000000000000000000000000000000000000000000000cccc"}
        fake-binding {:pro-rata-invocation-publication-binding/root
                      "sha256:cccc00000000000000000000000000000000000000000000000000000000cccc"
                      :publication-ordering/root
                      "sha256:bbbb00000000000000000000000000000000000000000000000000000000bbbb"}
        fake-ordering {:transaction-ordering/hash
                       "sha256:bbbb00000000000000000000000000000000000000000000000000000000bbbb"}
        result (read-model/diagnostic-result
                {:head fake-head :binding fake-binding :ordering fake-ordering
                 :head-reason nil :binding-reason nil :ordering-reason nil
                 :authoritative? false :correspondence? false :reachable? false
                 :semantic-status :not-applicable
                 :missing-roots [] :roots {} :v2? false})]
    (is (= :unavailable (get-in result [:authority :status])))
    (is (= :root-mismatch (get-in result [:authority :reason])))))

(deftest diagnostic-result-authority-corrupt
  (let [result (read-model/diagnostic-result
                {:head nil :binding nil :ordering nil
                 :head-reason :corrupt :binding-reason nil :ordering-reason nil
                 :authoritative? false :correspondence? false :reachable? false
                 :semantic-status :not-applicable
                 :missing-roots [] :roots {} :v2? false})]
    (is (= :corrupt (get-in result [:authority :reason]))
        "head reason of :corrupt surfaces in authority diagnostic")))
