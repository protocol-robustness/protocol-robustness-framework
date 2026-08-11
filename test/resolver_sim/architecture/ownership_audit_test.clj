(ns resolver-sim.architecture.ownership-audit-test
  "Content-authority classification audit tests.

   Validates config/architecture/content-authority.edn and the classification
   resolution logic in scripts.ownership-audit. This is the P0 contract: every
   governed file resolves to exactly one classification, legal values are used,
   :mixed? files carry :reason + :split-intent, no rule matches only outside
   governed roots, and the known rootzone surfaces are recorded as
   missing-extension-points (debt allowlist)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [scripts.ownership-audit :as audit]))

(def manifest-path "config/architecture/content-authority.edn")

(defn- load-manifest []
  (edn/read-string (slurp manifest-path)))

(def manifest (load-manifest))

(deftest manifest-is-well-formed
  (testing "top-level shape"
    (is (= 1 (:schema-version manifest)))
    (is (seq (:governed-roots manifest)))
    (is (seq (:defaults manifest)))
    (is (seq (:allowed-authorities manifest)))
    (is (seq (:allowed-content-kinds manifest)))
    (is (seq (:allowed-support-statuses manifest))))
  (testing "test-support is a legal content-kind"
    (is (contains? (:allowed-content-kinds manifest) :test-support))))

(deftest governed-roots-resolve-to-exactly-one-classification
  (testing "every governed file resolves, with no errors"
    (let [result (audit/audit manifest)]
      (is (:valid? result)
          (str "classification errors: " (pr-str (:errors result)))))))

(deftest every-mixed-file-has-reason-and-split-intent
  (testing "all :mixed? overrides carry :reason and a legal :split-intent"
    (let [bad (into []
                    (keep (fn [rule]
                            (when (and (:mixed? rule)
                                       (or (clojure.string/blank? (str (:reason rule)))
                                           (not (contains? #{:planned :intrinsic}
                                                           (:split-intent rule)))))
                              (:path rule))))
                    (:overrides manifest))]
      (is (empty? bad) (str "incomplete mixed rules: " (pr-str bad))))))

(deftest rule-values-are-legal
  (testing "every default/override uses allowed authority/content-kind/support-status"
    (let [errors (audit/validate-allowed-values manifest)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest no-rule-matches-only-outside-governed-roots
  (testing "rule placement is confined to governed roots"
    (let [errors (audit/validate-rule-placement manifest)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest every-governed-root-is-covered
  (testing "no governed root lacks a default rule matching a file under it"
    (let [files (audit/governed-files manifest)
          errors (audit/validate-root-coverage manifest files)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest no-dead-default-rules
  (testing "every default glob matches at least one governed file"
    (let [files (audit/governed-files manifest)
          errors (audit/validate-dead-defaults manifest files)]
      (is (empty? errors) (str (pr-str errors))))))

(deftest known-missing-extension-points-recorded
  (testing "the two rootzone surfaces are recorded as known debt"
    (let [surfaces (set (map (juxt :path :surface)
                             (:known-missing-extension-points manifest)))]
      (is (contains? surfaces
                     ["src/resolver_sim/hash/canonical.clj" :rootzones]))
      (is (contains? surfaces
                     ["src/resolver_sim/definitions/passive_registries.clj" :rootzones])))))

(deftest overrides-take-precedence-over-defaults
  (testing "a classified override wins over the core default"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/concepts/ecommerce_reporting.clj")]
      (is (= :classified (:status res)))
      (is (= :user (get-in res [:classification :authority])))
      (is (= :example (get-in res [:classification :content-kind]))))))

(deftest mixed-override-wins-over-default
  (testing "a :mixed? override beats the default classification"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/hash/canonical.clj")]
      (is (= :mixed (:status res)))
      (is (= :planned (get-in res [:rule :split-intent]))))))

(deftest protocol-integration-override
  (testing "solidity-shadow-registry is classified as protocol integration"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/definitions/solidity_shadow_registry.clj")]
      (is (= :protocol (get-in res [:classification :authority])))
      (is (= :integration (get-in res [:classification :content-kind]))))))

(deftest test-support-classification
  (testing "dummy protocol is PRF test-support, not core-contract"
    (let [res (audit/resolve-classification
               manifest "src/resolver_sim/protocols/dummy.clj")]
      (is (= :prf (get-in res [:classification :authority])))
      (is (= :test-support (get-in res [:classification :content-kind]))))))

(deftest glob-matching
  (testing "** matches nested files and directories"
    (is (audit/glob-matches? "src/resolver_sim/**" "src/resolver_sim/core.clj"))
    (is (audit/glob-matches? "src/resolver_sim/**" "src/resolver_sim/a/b/c.clj"))
    (is (audit/glob-matches? "benchmarks/packs/sew/**" "benchmarks/packs/sew/registry.edn"))
    (is (not (audit/glob-matches? "benchmarks/packs/sew/**" "benchmarks/packs/prf-core/x.edn"))))
  (testing "exact override paths match only the file"
    (is (audit/glob-matches? "src/resolver_sim/hash/canonical.clj"
                             "src/resolver_sim/hash/canonical.clj"))
    (is (not (audit/glob-matches? "src/resolver_sim/hash/canonical.clj"
                                  "src/resolver_sim/hash/sequence.clj")))))

(deftest audit-detects-unclassified-file
  (testing "a governed file with no matching rule reports :unclassified"
    (let [m (assoc manifest :defaults [] :overrides [])
          res (audit/resolve-classification m "src/resolver_sim/core.clj")]
      (is (= :unclassified (:status res))))))

(deftest audit-detects-ambiguous-defaults
  (testing "overlapping defaults that disagree are detected"
    (let [m (assoc manifest
                   :defaults [{:path "src/**" :authority :prf
                               :content-kind :core-contract :support-status :normative}
                              {:path "src/resolver_sim/**" :authority :protocol
                               :content-kind :integration :support-status :supported}]
                   :overrides [])
          res (audit/resolve-classification m "src/resolver_sim/x.clj")]
      (is (= :ambiguous-default (:status res))))))
