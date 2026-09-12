(ns resolver-sim.architecture.legacy-production-gate-test
  "Phase 3B TOMBSTONE / absence gate — successor to the Phase 3A production
   gate.

   Phase 3A asked: are the legacy held-custody producers still present but
   forbidden? Phase 3B asks the permanent question: does legacy held-custody
   production still exist anywhere, and can it be reintroduced?

   This gate fails if any of the following reappear:
     - the deleted legacy core namespace (resolver-sim.evidence.force-authorisation);
     - the legacy builder / producer vars (build-force-auth-add-held,
       build-force-auth-add-held-v2, build-force-auth-add-held-summary,
       build-force-auth-add-held-summary-v1, build-force-auth-add-held-summary-permissive,
       and the legacy lifecycle builders);
     - any :status :legacy held-custody approval in protocol-boundaries.edn;
     - any reference to the deleted namespace from production source or policy.

   Historical READ support is permanent and extension-owned: it exists only
   through prf.extensions.held-custody.legacy-validate and the package
   manifest's :extension/historical-read contract, cross-checked by the
   conformance test (extensions/held-custody)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def deleted-ns
  "The legacy core namespace removed in Phase 3B."
  "resolver-sim.evidence.force-authorisation")

(def deleted-ns-file
  "The legacy namespace's source file."
  "src/resolver_sim/evidence/force_authorisation.clj")

(def legacy-producer-vars
  "Legacy held-custody PRODUCER / builder var names. None may reappear."
  #{"build-force-auth-add-held"
    "build-force-auth-add-held-v2"
    "build-force-auth-add-held-summary"
    "build-force-auth-add-held-summary-v1"
    "build-force-auth-add-held-summary-permissive"
    "build-force-auth-lifecycle"
    "build-force-auth-lifecycle-summary"
    "build-force-auth-lifecycle-summary-v1"})

(def boundary-policy
  (edn/read-string (slurp "config/architecture/protocol-boundaries.edn")))

(defn- production-source-files []
  (let [zones (filter #(every? (fn [r] (not (str/starts-with? r "extensions/")))
                               (:source-roots %))
                      (:architecture/zones boundary-policy))]
    (for [zone zones
          root (:source-roots zone)
          file (file-seq (io/file root))
          :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
      file)))

(defn- file-text [file]
  (str/join "\n" (line-seq (io/reader file))))

(defn- policy-text []
  (slurp "config/architecture/protocol-boundaries.edn"))

(deftest deleted-namespace-is-absent
  (testing "the legacy core namespace source file no longer exists"
    (is (not (.exists (io/file deleted-ns-file)))))
  (testing "no production source file references the deleted namespace"
    (let [hits (keep (fn [f]
                       (when (str/includes? (file-text f) deleted-ns)
                         (.getPath f)))
                     (production-source-files))]
      (is (empty? hits) (str "deleted namespace referenced in: " hits))))
  (testing "the policy no longer references the deleted namespace"
    (is (not (str/includes? (policy-text) deleted-ns)))))

(deftest legacy-producers-are-absent
  (testing "no legacy producer/builder var name appears in production source"
    (let [hits (keep (fn [f]
                       (let [text (file-text f)]
                         (seq (filter #(str/includes? text %) legacy-producer-vars))))
                     (production-source-files))]
      (is (empty? hits) (str "legacy producer names found in: " hits))))
  (testing "no legacy producer var name appears in policy/registration config"
    (is (not (some #(str/includes? (policy-text) %) legacy-producer-vars)))))

(deftest no-legacy-status-approvals-remain
  (testing "no held-custody approval in the policy carries :status :legacy"
    (let [legacy (into []
                       (for [key [:approved/core-domain-literals
                                  :approved/core-operation-literals
                                  :approved/core-status-literals]
                             approval (get boundary-policy key [])
                             :when (= :legacy (:status approval))]
                         {:key key :file (:file approval) :status (:status approval)}))]
      (is (empty? legacy) (str "legacy-status approvals remain: " legacy))))
  (testing "the legacy gate policy blocks are gone"
    (is (not (contains? boundary-policy :forbidden/legacy-production-vars)))
    (is (not (contains? boundary-policy :permitted/legacy-read-vars)))
    (is (not (contains? boundary-policy :approved/legacy-production-locations)))))

(deftest historical-support-is-not-core-owned
  (testing "core retains no legacy held-custody validator/reader vars (they are
            extension-owned)"
    (let [core-read-names
          ["valid-force-auth-add-held?"
           "valid-force-auth-add-held-v2?"
           "force-auth-add-held-scope-verifies?"
           "exact-force-auth-add-held?"
           "valid-force-auth-add-held-summary?"
           "valid-force-auth-add-held-summary-v1?"
           "valid-force-auth-add-held-summary-v1-migration?"
           "recompute-force-auth-add-held-summary"]
          definition-re
          (re-pattern (str "(?m)^\\s*\\(defn?\\s+("
                           (str/join "|" (map #(java.util.regex.Pattern/quote %) core-read-names))
                           ")\\b"))
          hits (keep (fn [f]
                       (let [m (re-find definition-re (file-text f))]
                         (when m (str (.getPath f) " defines " (second m)))))
                     (production-source-files))]
      (is (empty? hits)
          (str "historical validator/reader vars must not be defined in core: " hits)))))
