(ns resolver-sim.benchmark.claim-registry-test
  "Tests for the single claim-registry resolution boundary.

   Covers path precedence (CLI > env > default), source detection, fail-closed
   validation of external registries, and the security property that an external
   registry can select a compiled evaluator but cannot invent evaluator code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.benchmark.claim-registry :as cr]))

(defn- temp-file
  [content]
  (let [f (java.io.File/createTempFile "claim-registry" ".edn")]
    (spit f content)
    f))

(defn- valid-registry
  [& {:keys [claims schema-version]
      :or {schema-version 1
           claims [{:claim/id :evidence-root-present
                    :claim/title "t"
                    :claim/description "d"
                    :claim/property-types #{:integrity}
                    :claim/evaluator :evidence-root-present}]}}]
  (pr-str {:claim-registry/version schema-version :claims claims}))

(defn- entry
  [& {:keys [id evaluator]
      :or {id :evidence-root-present evaluator :evidence-root-present}}]
  {:claim/id id
   :claim/title (str id)
   :claim/description (str id)
   :claim/property-types #{:integrity}
   :claim/evaluator evaluator})

;; ── Path precedence & source detection ──────────────────────────────────────

(deftest cli-path-takes-precedence-over-env-and-default
  (testing "an explicit CLI path wins over env and default"
    (let [cli "/tmp/auditor-claims.edn"]
      (is (= cli (cr/claim-registry-path cli)))
      (is (= :cli (cr/claim-registry-source cli))))))

(defn- with-registry-env
  [value f]
  (with-redefs [cr/env-var (fn [k] (if (= k "PRF_BENCHMARKS_CLAIM_REGISTRY") value nil))]
    (f)))

(deftest env-path-used-when-no-cli-path
  (testing "PRF_BENCHMARKS_CLAIM_REGISTRY is used only when no CLI path is given"
    (with-registry-env "/tmp/env-claims.edn"
      (fn []
        (is (= "/tmp/env-claims.edn" (cr/claim-registry-path nil)))
        (is (= :environment (cr/claim-registry-source nil)))
        ;; CLI still wins over env
        (is (= "/tmp/cli.edn" (cr/claim-registry-path "/tmp/cli.edn")))
        (is (= :cli (cr/claim-registry-source "/tmp/cli.edn")))))))

(deftest default-path-is-repository-registry
  (testing "no CLI path and no env falls back to the repository default"
    (with-registry-env nil
      (fn []
        (is (= "benchmarks/claim-registry.edn" (cr/claim-registry-path nil)))
        (is (= :default (cr/claim-registry-source nil)))))))

;; ── Fail-closed loading ─────────────────────────────────────────────────────

(deftest load-valid-external-registry-selects-compiled-evaluator
  (testing "an auditor can supply a registry that selects a COMPILED evaluator"
    (let [f (temp-file (valid-registry))
          loaded (cr/load-claim-registry (.getPath f))]
      (is (= :cli (:claim-registry/source loaded)))
      (is (= 1 (count (:claims loaded))))
      (is (= :evidence-root-present (get-in loaded [:claim-map :evidence-root-present :claim/id])))
      (is (str/starts-with? (cr/registry-file-sha256 (.getPath f)) "sha256:")))))

(deftest external-registry-cannot-invent-evaluator-code
  (testing "an auditor cannot invent :claim/evaluator :auditor/my-code — fails closed"
    (let [f (temp-file (valid-registry
                        :claims [(entry :id :claim/auditor-claim :evaluator :auditor/my-code)]))
          ex (try (cr/load-claim-registry (.getPath f)) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :claim-registry-invalid (:kind (ex-data ex))))
      (is (some #(= :unknown-evaluator (:kind %)) (:errors (ex-data ex)))))))

(deftest missing-file-fails-closed
  (let [ex (try (cr/load-claim-registry "/nonexistent/registry.edn") nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :missing-file (:kind (ex-data ex))))))

(deftest unsupported-schema-version-fails-closed
  (let [f (temp-file (valid-registry :schema-version 99))
        ex (try (cr/load-claim-registry (.getPath f)) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (some #(= :unsupported-schema (:kind %)) (:errors (ex-data ex))))))

(deftest duplicate-claim-ids-fail-closed
  (let [f (temp-file (valid-registry
                      :claims [(entry :id :evidence-root-present)
                               (entry :id :evidence-root-present)]))
        ex (try (cr/load-claim-registry (.getPath f)) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (some #(= :duplicate-claim-id (:kind %)) (:errors (ex-data ex))))))

(deftest malformed-registry-fails-closed
  (testing "non-map / unparseable content is rejected, not silently defaulted"
    (let [f (temp-file "this is not edn }")
          ex (try (cr/load-claim-registry (.getPath f)) nil
                  (catch Exception e e))]
      (is (some? ex))))

  (testing "a :claims value that is not sequential fails closed"
    (let [f (temp-file (pr-str {:claim-registry/version 1 :claims :oops}))
          ex (try (cr/load-claim-registry (.getPath f)) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (some #(= :not-a-sequence (:kind %)) (:errors (ex-data ex)))))))

(deftest missing-required-keys-fail-closed
  (let [f (temp-file (valid-registry
                      :claims [{:claim/id :partial-claim}]))
        ex (try (cr/load-claim-registry (.getPath f)) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (some #(= :missing-required-key (:kind %)) (:errors (ex-data ex))))))

;; ── Default registry retains known-gap semantics ────────────────────────────

(deftest default-registry-loads-with-known-gaps
  (testing "the repository default registry may declare claims whose evaluators
            are not yet compiled (known gaps surfaced by coverage) — loading the
            DEFAULT registry does not fail on those"
    (let [loaded (cr/load-claim-registry nil)]
      (is (= :default (:claim-registry/source loaded)))
      (is (pos? (count (:claims loaded))))
      (is (contains? loaded :claim-map)))))

(deftest validate-allows-known-gaps-when-not-fatal
  (let [data (read-string (valid-registry
                           :claims [(entry :id :claim/level-cascade-integrity
                                           :evaluator :claim/level-cascade-integrity)]))
        fatal (cr/validate-claim-registry data nil true)
        non-fatal (cr/validate-claim-registry data nil false)]
    (is (some #(= :unknown-evaluator (:kind %)) fatal))
    (is (empty? non-fatal))))
