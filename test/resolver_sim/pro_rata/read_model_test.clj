(ns resolver-sim.pro-rata.read-model-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [next.jdbc :as jdbc]
            [resolver-sim.db.pool :as pool]
            [resolver-sim.hash.reference :as ref]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.content-addressed-store :as cas]
            [resolver-sim.pro-rata.read-model :as read-model]
            [resolver-sim.pro-rata.publication :as publication]
            [resolver-sim.pro-rata.invocation-publication-binding :as binding]
            [resolver-sim.pro-rata.postgres-publication-store :as pg]
            [resolver-sim.pro-rata.invocation-publication-binding-test :as fixture]
            [resolver-sim.pro-rata.canonical-effects :as effects]
            [resolver-sim.transaction.ordering :as ordering]
            [resolver-sim.pro-rata.protocol-transaction-realization :as realization]
            [resolver-sim.pro-rata.application :as application]
            [resolver-sim.pro-rata.effect-compilation-semantics :as semantics]
            [resolver-sim.pro-rata.target-map :as target-map]
            [resolver-sim.pro-rata.allocation :as allocation])
  (:import [java.nio.file Files]))

;; ── Fixtures ────────────────────────────────────────────────────────────────

(defn database-url []
  (or (System/getenv "DATABASE_URL")
      "jdbc:postgresql://localhost:5433/postgres?user=postgres&password=postgres"))

(defn skip-if-no-db [f]
  (try
    (let [ds (pool/pool (database-url) {})]
      (jdbc/execute-one! ds ["SELECT 1"])
      (.close ds)
      (f))
    (catch Exception e
      (println "PostgreSQL tests skipped (" (.getMessage e) ")")
      (System/exit 0))))

(def ^:dynamic *ds* nil)

(defn pg-fixture [f]
  (let [ds (pool/pool (database-url) {})]
    (pg/ensure-schema! ds)
    (binding [*ds* ds]
      (try (f)
           (finally
             (jdbc/execute! ds ["TRUNCATE TABLE prf_economic_publication_binding, prf_economic_publication_ordering, prf_economic_publication_partition"])
             (.close ds))))))

(use-fixtures :once skip-if-no-db)
(use-fixtures :each pg-fixture)

;; ── Helpers ────────────────────────────────────────────────────────────────

(defn- cas-resolver
  "Build a CAS store with all artifact bodies from the fixture and return
   a resolution function [root -> body-or-nil]."
  []
  (let [cas-store (cas/create-store
                   (str (Files/createTempDirectory "resolver-sim-read-model-cas-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0))))]
    (fn [root] (cas/resolve-artifact cas-store root))))

(defn- put-artifact!
  "Put an artifact into a CAS store with its root as the key."
  [store artifact root-field]
  (let [root (ref/sha256-ref (get artifact root-field))]
    (cas/put-if-absent! store
                        {:hash-reference root
                         :artifact artifact
                         :verify #(= % artifact)})))

(defn- build-v1-test-chain
  "Build a complete v1 publication chain in a CAS store and return
   {:head head :binding binding :resolution-fn resolution-fn}."
  []
  (let [resolved @#'fixture/fixture
        ordering (:publication-ordering resolved)
        binding (binding/build-binding resolved)
        head-base {:schema-version publication/application-publication-head-schema
                   :publication/last-ordering-root (:transaction-ordering/hash ordering)
                   :publication/application-binding-root (:pro-rata-invocation-publication-binding/root binding)
                   :publication/sequence 1
                   :publication/predecessor-root nil}
        head (assoc head-base :publication/head-root (publication/application-head-root head-base))
        cas-store (cas/create-store
                   (str (Files/createTempDirectory "resolver-sim-read-model-"
                                                   (make-array java.nio.file.attribute.FileAttribute 0))))
        resolution (fn [root] (cas/resolve-artifact cas-store root))]
    ;; Put all CAS-resolvable artifacts
    (put-artifact! cas-store head :publication/head-root)
    (put-artifact! cas-store ordering :transaction-ordering/hash)
    (put-artifact! cas-store binding :pro-rata-invocation-publication-binding/root)
    (put-artifact! cas-store (:capability-binding resolved) :binding/root)
    (put-artifact! cas-store (:use-case-application resolved) :application/root)
    (put-artifact! cas-store (:executable-distribution resolved) :executable-distribution/root)
    (put-artifact! cas-store (:output resolved) :pro-rata-output/root)
    (put-artifact! cas-store (:allocation resolved) :allocation/hash)
    (put-artifact! cas-store (:pro-rata-transition resolved) :transition/root)
    (put-artifact! cas-store (:canonical-transition resolved) :canonical-effect-transition/root)
    (put-artifact! cas-store (:receipt resolved) :applied-effect-receipt/root)
    (put-artifact! cas-store (:protocol-transaction-realization resolved) :protocol-transaction-realization/root)
    {:head head
     :binding binding
     :resolution-fn resolution}))

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
  (let [{:keys [head binding resolution-fn]} (build-v1-test-chain)
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
  (let [resolution-fn (cas-resolver)
        trace (read-model/trace-authoritative-publication
               resolution-fn {:head-root "sha256:aaaa"}),
        head-edge (first (:trace/edges trace))]
    (is (= :head-root (:trace/entry-point trace)))
    (is (= :incomplete (:trace/status trace)))
    (is (= [:missing] (:trace/reasons trace)))
    (is (false? (:resolved? head-edge))
        "head edge is unresolved")
    (is (= :missing (:reason head-edge)))))

(deftest trace-from-head-root-corrupt-body
  (let [{:keys [head resolution-fn]} (build-v1-test-chain)
        root (:publication/head-root head)
        bad-resolution (fn [r]
                         (if (= r root)
                           (assoc head :publication/last-ordering-root "sha256:bbbb")
                           (resolution-fn r)))
        trace (read-model/trace-authoritative-publication
               bad-resolution {:head-root root})
        head-edge (first (:trace/edges trace))]
    (is (= :head-root (:trace/entry-point trace)))
    (is (false? (:resolved? head-edge))
        "corrupt head is not resolved")
    (is (= :corrupt (:reason head-edge)))))

;; ── Trace from ordering root ───────────────────────────────────────────────

(deftest trace-from-ordering-root-resolves-backward
  (let [{:keys [head binding resolution-fn]} (build-v1-test-chain)
        ordering-root (-> head :publication/last-ordering-root)
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

;; ── Public trace serialization ─────────────────────────────────────────────

(deftest public-trace-strips-bodies
  (let [{:keys [head resolution-fn]} (build-v1-test-chain)
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

(deftest diagnostic-result-closes-expected-shape
  (let [result (read-model/diagnostic-result
                {:head nil :binding nil :ordering nil
                 :head-reason :missing :binding-reason nil :ordering-reason nil
                 :authoritative? false :correspondence? false :reachable? false
                 :semantic-status :not-applicable
                 :missing-roots [] :roots {} :v2? false})]
    (is (= :unavailable (get-in result [:authority :status])))
    (is (= :missing (get-in result [:authority :reason])))
    (is (= :unverified (get-in result [:correspondence :status])))
    (is (= :missing (get-in result [:correspondence :reason])))
    (is (= :not-applicable (get-in result [:semantic-recompilation :status])))
    (is (nil? (get-in result [:semantic-recompilation :reason])))
    (is (= :incomplete (get-in result [:reachability :status])))
    (is (= :missing (get-in result [:reachability :reason])))
    (is (empty? (get-in result [:reachability :missing-roots])))))

(deftest diagnostic-result-authority-root-mismatch
  (let [fake-head {:publication/head-root "sha256:aaaa"
                   :publication/last-ordering-root "sha256:bbbb"
                   :publication/application-binding-root "sha256:cccc"}
        fake-binding {:pro-rata-invocation-publication-binding/root "sha256:cccc"
                      :publication-ordering/root "sha256:bbbb"}
        fake-ordering {:transaction-ordering/hash "sha256:bbbb"}
        result (read-model/diagnostic-result
                {:head fake-head :binding fake-binding :ordering fake-ordering
                 :head-reason nil :binding-reason nil :ordering-reason nil
                 :authoritative? false :correspondence? false :reachable? false
                 :semantic-status :not-applicable
                 :missing-roots [] :roots {} :v2? false})]
    (is (= :unavailable (get-in result [:authority :status])))
    (is (= :root-mismatch (get-in result [:authority :reason])))))

(deftest diagnostic-result-reason-vocabulary-closed
  (doseq [dim [:authority :correspondence :semantic-recompilation :reachability]]
    (is (contains? read-model/reason-vocabulary dim)
        (str "dimension " (name dim) " has a reason vocabulary"))))
