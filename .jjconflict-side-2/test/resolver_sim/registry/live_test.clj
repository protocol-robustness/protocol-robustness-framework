(ns resolver-sim.registry.live-test
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.registry.live :as live]))

;; ── File-backed registries ───────────────────────────────────────────────────

(deftest read-concept-registry
  (testing "read-live-registry reads concept registry from classpath"
    (let [result (live/read-live-registry :concept)]
      (is (= :concept (:registry/type result)))
      (is (= :classpath (:registry/source result)))
      (is (map? (:registry/content result)))
      (is (contains? (:registry/content result) :concepts))
      (is (vector? (:concepts (:registry/content result)))))))

(deftest read-command-registry
  (testing "read-live-registry reads command registry from classpath"
    (let [result (live/read-live-registry :command)]
      (is (= :command (:registry/type result)))
      (is (contains? (:registry/content result) :commands))
      (is (vector? (:commands (:registry/content result)))))))

(deftest read-claim-registry
  (testing "read-live-registry reads claim registry from filesystem"
    (let [result (live/read-live-registry :claim)]
      (is (= :claim (:registry/type result)))
      (is (contains? (:registry/content result) :claims))
      (is (vector? (:claims (:registry/content result))))))
  (testing "claim registry content exposes selection provenance and raw document
            from a single read (no divergent second read)"
    (let [content (:registry/content (live/read-live-registry :claim))]
      (is (= :default (:claim-registry/source content)))
      (is (string? (:claim-registry/path content)))
      (is (integer? (:claim-registry/version content)))
      (is (map? (:claim-registry/data content)))
      ;; The document's own claim entries and the resolver's validated claims
      ;; come from the same file read, so they must agree.
      (is (= (count (:claims (:claim-registry/data content)))
             (count (:claims content)))))))

(deftest read-benchmark-registry
  (testing "read-live-registry reads benchmark registry"
    (let [result (live/read-live-registry :benchmark)]
      (is (= :benchmark (:registry/type result)))
      (is (contains? (:registry/content result) :packs))
      (is (contains? (:registry/content result) :domains)))))

(deftest read-definitions-registry
  (testing "read-live-registry reads definitions from in-memory namespace"
    (let [result (live/read-live-registry :definitions)]
      (is (= :definitions (:registry/type result)))
      (is (contains? (:registry/content result) :purposes))
      (is (contains? (:registry/content result) :claims))
      (is (contains? (:registry/content result) :invariants)))))

(deftest read-protocol-registry
  (testing "read-live-registry reads protocol registry"
    (let [result (live/read-live-registry :protocol)]
      (is (= :protocol (:registry/type result)))
      (is (contains? (:registry/content result) :protocols/known))
      (is (vector? (:protocols/known (:registry/content result)))))))

;; ── World-backed registries ──────────────────────────────────────────────────

(deftest read-yield-module-from-world
  (testing "read-live-registry reads yield-module from world state"
    (let [world {:yield/modules {:liquid {:module/id :liquid}
                                 :fixed {:module/id :fixed}}}
          result (live/read-live-registry :yield-module world)]
      (is (= :yield-module (:registry/type result)))
      (is (= :live (:registry/source result)))
      (is (= {:liquid {:module/id :liquid}
              :fixed {:module/id :fixed}}
             (:registry/content result))))))

(deftest read-sew-resolver-from-world
  (testing "read-live-registry reads sew-resolver from world state"
    (let [world {:resolver-stakes {"0xabc" 1000 "0xdef" 500}}
          result (live/read-live-registry :sew-resolver world)]
      (is (= :sew-resolver (:registry/type result)))
      (is (= :live (:registry/source result)))
      (is (= {"0xabc" 1000 "0xdef" 500} (:registry/content result))))))

(deftest world-preference-over-file
  (testing "world state is preferred over file fallback"
    (let [world {:resolver-stakes {"0xlive" 999}}
          result (live/read-live-registry :sew-resolver world)]
      (is (= :live (:registry/source result)))
      (is (= {"0xlive" 999} (:registry/content result))))))

;; ── update-live-registry! ────────────────────────────────────────────────────

(deftest update-and-read-live
  (testing "update-live-registry! stores data, read-live-registry returns it as :live"
    (live/register-registry! :test-live-write {})
    (live/update-live-registry! :test-live-write {:my-key "my-value"})
    (let [result (live/read-live-registry :test-live-write)]
      (is (= :live (:registry/source result)))
      (is (= {:my-key "my-value"} (:registry/content result))))))

(deftest update-with-meta
  (testing "update-live-registry! accepts optional meta"
    (live/register-registry! :test-with-meta {})
    (live/update-live-registry! :test-with-meta {:data 42} {:author "test"})
    (let [info (live/registry-info :test-with-meta)]
      (is (true? (:live? info)))
      (is (= {:author "test"} (:live-meta info)))
      (is (some? (:live-updated-at info))))))

(deftest update-overrides-file
  (testing "update-live-registry! takes priority over file-backed read"
    (live/register-registry! :test-live-override {:resolve-fn (fn [_] {:original true})})
    (live/update-live-registry! :test-live-override {:overridden true})
    (let [result (live/read-live-registry :test-live-override)]
      (is (= :live (:registry/source result)))
      (is (= {:overridden true} (:registry/content result))))))

;; ── register-registry! ───────────────────────────────────────────────────────

(deftest register-custom-type
  (testing "register-registry! adds a new registry type"
    (live/register-registry! :my-custom {:resolve-fn (fn [_] {:custom true})})
    (let [result (live/read-live-registry :my-custom)]
      (is (= :my-custom (:registry/type result)))
      (is (= {:custom true} (:registry/content result))))
    (is (contains? (set (live/list-registry-types)) :my-custom))))

(deftest register-with-world-path
  (testing "register-registry! supports world-path types"
    (live/register-registry! :world-thing {:world-path [:thing]})
    (let [world {:thing {:nested "data"}}
          result (live/read-live-registry :world-thing world)]
      (is (= :live (:registry/source result)))
      (is (= {:nested "data"} (:registry/content result))))))

;; ── Clear operations ─────────────────────────────────────────────────────────

(deftest clear-registry
  (testing "clear-registry! removes live entry, subsequent read falls back to file"
    (live/register-registry! :test-clear {:canonical-path "benchmarks/claim-registry.edn"})
    (live/update-live-registry! :test-clear {:mocked true})
    (live/clear-registry! :test-clear)
    (let [result (live/read-live-registry :test-clear)]
      (is (not= :live (:registry/source result)))
      (is (contains? (:registry/content result) :claims)))))

(deftest clear-all-registries
  (testing "clear-all-registries! wipes all live entries, preserves config"
    (live/register-registry! :test-a {:canonical-path "some/path.edn"})
    (live/register-registry! :test-b {:resolve-fn (fn [_] {})})
    (live/update-live-registry! :test-a {:a 1})
    (live/update-live-registry! :test-b {:b 2})
    (let [cleared (live/clear-all-registries!)]
      (is (>= cleared 2) (str "returns count of cleared entries (≥2): " cleared))
      (let [info-a (live/registry-info :test-a)
            info-b (live/registry-info :test-b)]
        (is (false? (:live? info-a)))
        (is (false? (:live? info-b)))
        (is (= "some/path.edn" (get-in info-a [:config :canonical-path]))
            "resolver config preserved")))))

;; ── Force refresh ────────────────────────────────────────────────────────────

(deftest force-refresh-skips-live
  (testing ":force option skips live atom and re-reads from source"
    (live/register-registry! :test-force {:resolve-fn (fn [_] {:from-source true})})
    (live/update-live-registry! :test-force {:stale true})
    (let [result (live/read-live-registry :test-force nil {:force true})]
      (is (not= :live (:registry/source result)))
      (is (= {:from-source true} (:registry/content result))))))

;; ── Error cases ──────────────────────────────────────────────────────────────

(deftest unknown-type-throws
  (testing "read-live-registry throws for unknown registry type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown registry type"
                          (live/read-live-registry :nonexistent-type)))))

;; ── List and info ────────────────────────────────────────────────────────────

(deftest list-registry-types-includes-all
  (testing "list-registry-types returns all known types"
    (let [types (live/list-registry-types)]
      (is (contains? (set types) :benchmark))
      (is (contains? (set types) :concept))
      (is (contains? (set types) :command))
      (is (contains? (set types) :claim))
      (is (contains? (set types) :protocol))
      (is (contains? (set types) :evidence))
      (is (contains? (set types) :yield-module))
      (is (contains? (set types) :sew-resolver))
      (is (contains? (set types) :definitions)))))

(deftest registry-info-returns-config
  (testing "registry-info returns type config and live status"
    (let [info (live/registry-info :concept)]
      (is (= :concept (:registry/type info)))
      (is (map? (:config info)))
      (is (some? (:live? info))))))

(deftest registry-info-for-live-type
  (testing "registry-info reflects live status after update"
    (live/register-registry! :test-info {})
    (live/update-live-registry! :test-info {:hello "world"})
    (let [info (live/registry-info :test-info)]
      (is (true? (:live? info)))
      (is (= :live (:live-source info))))))

(deftest registry-info-for-unknown-type
  (testing "registry-info returns nil for unknown type"
    (is (nil? (live/registry-info :not-registered)))))

;; ── Skip-fallback (test-mode) ────────────────────────────────────────────────

(deftest skip-fallback-unloaded-type
  (testing ":skip-fallback returns unavailable for unloaded live-only types"
    (live/register-registry! :test-sc {:world-path [:my :data]})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Registry not available"
                          (live/read-live-registry :test-sc nil {:skip-fallback true}))))
  (testing ":skip-fallback with world still works"
    (let [result (live/read-live-registry :test-sc {:my {:data "hello"}} {:skip-fallback true})]
      (is (= :live (:registry/source result)))
      (is (= "hello" (:registry/content result))))))

(deftest skip-fallback-returns-live-directly
  (testing ":skip-fallback returns live-write data without file fallback"
    (live/register-registry! :test-sc-live {})
    (live/update-live-registry! :test-sc-live {:already-loaded true})
    (let [result (live/read-live-registry :test-sc-live nil {:skip-fallback true})]
      (is (= :live (:registry/source result)))
      (is (= {:already-loaded true} (:registry/content result))))))

;; ── read-live convenience function (test-mode wrapper) ───────────────────────

(deftest read-live-zero-arity
  (testing "read-live with no args returns the atom snapshot"
    (live/update-live-registry! :snapshot-check {:marker true})
    (let [snapshot (live/read-live)]
      (is (map? snapshot))
      (is (contains? snapshot :snapshot-check)))))

(deftest read-live-with-type
  (testing "read-live with type skips file fallback"
    (let [result (live/read-live :test-sc-live)]
      (is (= :live (:registry/source result)))
      (is (= {:already-loaded true} (:registry/content result))))))

(deftest read-live-with-world
  (testing "read-live with type and world prefers world state"
    (let [result (live/read-live :sew-resolver {:resolver-stakes {:fast 999}})]
      (is (= :live (:registry/source result)))
      (is (= {:fast 999} (:registry/content result))))))

;; ── Dynamic var *live-only* ──────────────────────────────────────────────────

(deftest live-only-binding-skips-file-fallback
  (testing "binding *live-only* to true skips file fallback globally"
    (live/register-registry! :test-dyn {:world-path [:my :cfg]})
    (binding [resolver-sim.registry.live/*live-only* true]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Registry not available"
                            (live/read-live-registry :test-dyn))))
    (testing "and world state still works"
      (binding [resolver-sim.registry.live/*live-only* true]
        (let [result (live/read-live-registry :test-dyn {:my {:cfg "loaded"}})]
          (is (= :live (:registry/source result)))
          (is (= "loaded" (:registry/content result))))))))

;; ── with-test-registry macro ─────────────────────────────────────────────────

(def ^:dynamic *test-trace* nil)

(deftest with-test-registry-injects-fixtures
  (testing "with-test-registry injects fixtures and skips file fallback"
    (live/register-registry! :test-macro-fixture {:world-path [:fixture :data]})
    (live/with-test-registry
      {:test-macro-fixture {:injected true}}
      (let [result (live/read-live-registry :test-macro-fixture)]
        (is (= :live (:registry/source result)))
        (is (= {:injected true} (:registry/content result)))))))

(deftest with-test-registry-empty-fixtures
  (testing "with-test-registry with no fixtures still binds *live-only*"
    (live/register-registry! :test-macro-empty {:world-path [:e]})
    (live/with-test-registry
      nil
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Registry not available"
                            (live/read-live-registry :test-macro-empty))))))

(deftest with-test-registry-restores-state
  (testing "with-test-registry restores live atom after exit"
    (live/register-registry! :test-macro-restore {:world-path [:r]})
    (live/update-live-registry! :test-macro-restore {:original true})
    (live/with-test-registry
      {:test-macro-restore {:injected true}}
      (is (= {:injected true} (:registry/content (live/read-live :test-macro-restore)))))
    (let [after (live/read-live-registry :test-macro-restore)]
      (is (= {:original true} (:registry/content after))))))

(deftest with-test-registry-nested
  (testing "nested with-test-registry blocks compose correctly"
    (live/register-registry! :test-macro-a {:world-path [:a]})
    (live/register-registry! :test-macro-b {:world-path [:b]})
    (live/update-live-registry! :test-macro-b {:outer true})
    (live/with-test-registry
      {:test-macro-a {:level 1}}
      (live/with-test-registry
        {:test-macro-b {:level 2}}
        (is (= {:level 1} (:registry/content (live/read-live :test-macro-a))))
        (is (= {:level 2} (:registry/content (live/read-live :test-macro-b)))))
      (is (= {:outer true} (:registry/content (live/read-live :test-macro-b)))))))
