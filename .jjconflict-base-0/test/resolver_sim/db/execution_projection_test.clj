(ns resolver-sim.db.execution-projection-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.db.execution-projection :as sut]
            [resolver-sim.db.xtdb :as xtdb]
            [resolver-sim.run.package-index :as package-index]))

(def completion
  {"run_id" "run-1"
   "run_type" "benchmark"
   "lifecycle_status" "completed"
   "run_package_index_sha256" "sha256:index"
   "run_package_index_bytes" 42})

(def index
  {:run/id "run-1"
   :run/type :benchmark
   :run-package/hash "sha256:package-identity"
   :bundle/root-hash "sha256:bundle"
   :benchmark/id "bench-1"})

(def verified-context
  {:completion-report {:valid? true}
   :completeness-report {:complete? true}
   :integrity-report {:valid? true}
   :completion completion
   :package-index {:index index :sha256 "sha256:index-transport" :bytes 42}})

(deftest pure-builders-use-verified-explicit-context
  (let [p (sut/build-projection verified-context)]
    (is (= "run-1" (get-in p [:run :run-id])))
    (is (= :benchmark (get-in p [:run :run-type])))
    (is (= "sha256:package-identity" (get-in p [:run :package-index-root])))
    (is (= "sha256:index-transport" (get-in p [:run :package-index-sha256])))
    (is (= "sha256:bundle" (get-in p [:run :bundle-root])))
    (is (nil? (get-in p [:run :source-root]))
        "source-root is absent until an authoritative package field exists")
    (is (= [] (:benchmarks p))
        "package index does not itself contain verified benchmark execution rows")))

(deftest pure-builders-reject-unverified-or-incomplete-context
  (is (nil? (sut/build-projection (assoc-in verified-context [:integrity-report :valid?] false))))
  (is (nil? (sut/build-projection (assoc-in verified-context [:completeness-report :complete?] false))))
  (is (nil? (sut/build-projection (assoc-in verified-context [:completion-report :valid?] false)))))

(deftest nil-datasource-still-resolves-and-projects
  (let [resolved? (atom false)]
    (with-redefs [package-index/resolve-validation-context
                  (fn [run-root]
                    (reset! resolved? (= "verified-run" run-root))
                    verified-context)]
      (is (= "run-1" (get-in (sut/project-run! nil "verified-run") [:run :run-id])))
      (is @resolved?))))

(deftest query-apis-return-empty-vectors-for-nil-datasource
  (testing "nil is the explicit offline query contract"
    (is (= [] (sut/latest nil)))
    (is (= [] (sut/recent nil 10)))
    (is (= [] (sut/failures nil)))
    (is (= [] (sut/benchmark-rows nil "bench-1")))
    (is (= [] (sut/by-scenario nil "scenario-1")))
    (is (= [] (sut/by-use-case nil "use-case-1")))
    (is (= [] (sut/as-of nil (java.util.Date. 0))))
    (is (= [] (sut/as-of-benchmark-rows nil (java.util.Date. 0) "bench-1")))))

(deftest comparison-scope-is-explicit
  (is (= {:benchmark-id "b" :scenario-id "s" :use-case "u" :run-id "r" :as-of :t}
         (sut/comparison-scope {:benchmark-id "b" :scenario-id "s" :use-case "u"
                                :run-id "r" :as-of :t :ignored true}))))

(deftest bitemporal-query-vocabulary-is-offline-safe
  (testing "the system-time/history query vocabulary honours the nil-ds offline contract"
    (let [now (java.util.Date.)]
      (is (= [] (sut/execution-runs-valid-at nil now)))
      (is (= [] (sut/execution-runs-as-known-at nil now)))
      (is (= [] (sut/execution-runs-history nil)))
      (is (= [] (sut/benchmark-executions-valid-at nil "b" now)))
      (is (= [] (sut/benchmark-executions-as-known-at nil "b" now)))
      (is (= [] (sut/benchmark-executions-history nil "b"))))))

(deftest bitemporal-clause-helpers-produce-expected-xtdb-sql
  (testing "the shared clause helpers own FOR ... formatting from a timestamp value"
    (let [t (java.util.Date. 0)]
      (is (= "FOR VALID_TIME AS OF TIMESTAMP '1970-01-01T00:00:00Z'"
             (xtdb/valid-as-of t)))
      (is (= "FOR SYSTEM_TIME AS OF TIMESTAMP '1970-01-01T00:00:00Z'"
             (xtdb/system-as-of t)))
      (is (= "FOR ALL SYSTEM_TIME" xtdb/all-system-time))
      (is (= "FOR ALL VALID_TIME" xtdb/all-valid-time)))))

;; ---------------------------------------------------------------------------
;; Invalid-source return semantics
;;
;; `project-run!` / `resolve-projection` return nil only for an invalid or
;; incomplete source. A valid source always returns a non-nil projection map
;; regardless of whether persistence is enabled, so a caller can unambiguously
;; distinguish:
;;   valid source + persistence disabled  → non-nil projection (no writes)
;;   invalid/incomplete source            → nil
;; nil is therefore reserved for the invalid/incomplete contract and cannot be
;; confused with a valid-but-offline projection.
;; ---------------------------------------------------------------------------

(deftest valid-source-with-disabled-persistence-vs-invalid-source-are-distinct
  (testing "valid+persistence-disabled returns non-nil; invalid/incomplete returns nil"
    (with-redefs [package-index/resolve-validation-context
                  (fn [run-root]
                    (if (= "invalid" run-root)
                      {:completion-report {:valid? false}
                       :completeness-report {:complete? false}
                       :integrity-report {:valid? false}
                       :reasons [:bad]}
                      verified-context))]
      (is (some? (sut/project-run! nil "valid-root"))
          "valid source + nil ds returns the projection (non-nil), never nil")
      (is (nil? (sut/project-run! nil "invalid"))
          "invalid source returns nil")
      (is (nil? (sut/resolve-projection "invalid"))
          "resolve-projection nil is reserved for invalid/incomplete sources"))))

;; ---------------------------------------------------------------------------
;; Time semantics
;;
;; The projected `:valid-from` is the completion (execution/completion) time from
;; the terminal completion seal — NOT simulated/protocol event time (trace
;; :time / world :block-ts). That completion time is persisted as the XTDB
;; valid-time (`_valid_from`), i.e. the projection/valid time. `_valid_from`
;; must never be reinterpreted as canonical protocol or execution event time.
;; ---------------------------------------------------------------------------

(def completed-at-completion
  (assoc completion "completed_at" "2030-05-05T12:00:00Z" "valid_from" "2030-05-05T12:00:00Z"))

(deftest valid-from-is-completion-time-not-protocol-event-time
  (testing ":valid-from derives from the completion's completed_at, distinct from event/block time"
    (let [ctx (assoc verified-context :completion completed-at-completion)
          p   (sut/build-projection ctx)]
      (is (some? (get-in p [:run :valid-from])))
      (is (= "2030-05-05T12:00:00Z"
             (-> (get-in p [:run :valid-from]) .toInstant str))
          "valid-from is the terminal completion (execution/completion) time")
      (is (nil? (:time-event-index (get-in p [:run])))
          "the projection carries no simulated/protocol event time as its valid time"))))

;; ---------------------------------------------------------------------------
;; Projection identity & idempotence
;;
;; Idempotence is pinned to "same validated source → same deterministic
;; projection → repeated insertion is harmless". The existing completion/package
;; identity root (`:run-package/hash`) is retained in the projection so a
;; caller can distinguish same-identity-same-content from arbitrary replacement.
;; ---------------------------------------------------------------------------

(deftest projection-is-deterministic-and-retains-package-identity-root
  (testing "the same validated source yields the same projection and retains its identity root"
    (let [a (sut/build-projection verified-context)
          b (sut/build-projection verified-context)]
      (is (= a b)
          "same source → same deterministic projection (the basis of harmless re-insertion)")
      (is (= "sha256:package-identity" (get-in a [:run :package-index-root]))
          "the package identity root (run-package/hash) is retained in the projection")
      (is (= "sha256:index-transport" (get-in a [:run :package-index-sha256]))
          "the completion-bound package-index checksum is retained"))))

(deftest benchmark-executions-are-dormant-without-artifact-index
  (testing "a context with no :benchmark-index artifact yields no benchmark rows (dormant)"
    (let [ctx (assoc verified-context :run-root nil)]
      (is (= [] (:benchmarks (sut/build-projection ctx)))))))

;; ---------------------------------------------------------------------------
;; Epoch valid-time fallback
;;
;; Real completion seals carry no `completed_at`/`valid_from`, so the projection
;; yields `:valid-from` nil and persistence falls back to the deterministic
;; epoch instant (2000-01-01). The degraded AS-OF meaning is explicit: a row is
;; \"valid since epoch\", so AS-OF at/before epoch does not see it.
;; ---------------------------------------------------------------------------

(deftest epoch-valid-time-fallback-when-completion-has-no-timestamp
  (testing "a completion without completed_at/valid_from yields a nil :valid-from (epoch fallback)"
    (let [p (sut/build-projection verified-context)]
      (is (nil? (get-in p [:run :valid-from]))
          "no completion timestamp → :valid-from is nil (epoch fallback at persistence)")
      (is (= (xtdb/sql-ts nil) "TIMESTAMP '2000-01-01T00:00:00Z'")
          "sql-ts serializes nil as the deterministic epoch instant"))))
