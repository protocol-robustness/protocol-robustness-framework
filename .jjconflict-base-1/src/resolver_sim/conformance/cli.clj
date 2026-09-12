(ns resolver-sim.conformance.cli
  "Minimal read-only conformance CLI (G8).

   Exposes lifecycle inspection only — it never repairs bundles, never executes
   missing profile steps, and never runs domain code.  Domain execution stays in
   the adapters (trace replay, benchmark reproduce, evidence-package build).

   Commands:
     conformance bundle verify <bundle.json>
     conformance claim derive <bundle.json>
     conformance profile validate <profile.edn>

   Every command outputs deterministic machine JSON:
     {:schema-version :status :outcome/class :claimable?
      :artifact/root :issues [...]}"
  (:require [clojure.data.json :as json]
            [resolver-sim.config.hardening :as hardening]
            [resolver-sim.conformance.json :as json-scan]
            [resolver-sim.conformance.bundle :as bundle]
            [resolver-sim.conformance.profile :as profile]
             ;; Load the production adapters so the committed implementation
             ;; registry root matches the one bound into generated receipts.
            [resolver-sim.trace.conformance.validators]
            [resolver-sim.benchmark.conformance.reproduction]
            [resolver-sim.evidence-package.conformance.admission]))

(def cli-schema-version "conformance.cli/v1")

(defn- read-json
  "Read a bundle JSON, rejecting duplicate object keys, oversized bundles, and
   excessive nesting (resource safety, CR-004): each yields a typed rejection
   shared by all verifiers."
  [path]
  (let [text (slurp path)
        max-bytes (hardening/value :conformance-max-bundle-bytes {:fallback (* 10 1024 1024)})]
    (when (> (.length text) max-bytes)
      (throw (ex-info "bundle too large" {:issue/code :bundle-too-large})))
    (when (json-scan/nesting-too-deep? text)
      (throw (ex-info "nesting too deep" {:issue/code :nesting-too-deep})))
    (when-let [dup (json-scan/duplicate-json-key text)]
      (throw (ex-info "duplicate JSON key" {:issue/code :duplicate-json-key :key dup})))
    (json/read-str text :key-fn keyword)))

(defn- machine-output
  [{:keys [status claimable? issues derived-claim]}]
  {:schema-version cli-schema-version
   :status (name status)
   :outcome/class (if claimable? "claimable" "not-claimable")
   :claimable? claimable?
   :artifact/root (get-in derived-claim [:claim/json-root])
   :issues (mapv :issue/code issues)})

(defn- machine-output-println [x] (println (json/write-str x {:indent true})))

(defn verify-bundle-command
  [path]
  (try
    (let [result (bundle/verify-bundle (read-json path))]
      (machine-output-println (machine-output result))
      (System/exit (if (= :pass (:status result)) 0 1)))
    (catch clojure.lang.ExceptionInfo e
      (let [code (or (:issue/code (ex-data e)) :malformed-json)]
        (machine-output-println {:schema-version cli-schema-version
                                 :status "rejected"
                                 :outcome/class "not-claimable"
                                 :claimable? false
                                 :artifact/root nil
                                 :issues [(name code)]})
        (System/exit 1)))))

(defn derive-claim-command
  [path]
  (let [b (read-json path)
        b (update-in b [:reconciliation :reconciliation/status] keyword)
        claim (bundle/derive-claim-from-bundle b)]
    (if claim
      (machine-output-println {:schema-version cli-schema-version
                               :status "pass"
                               :outcome/class "claimable"
                               :claimable? true
                               :artifact/root (:claim/json-root claim)
                               :issues []})
      (machine-output-println {:schema-version cli-schema-version
                               :status "rejected"
                               :outcome/class "not-claimable"
                               :claimable? false
                               :artifact/root nil
                               :issues ["claim-not-derivable"]}))
    (System/exit (if claim 0 1))))

(defn validate-profile-command
  [path]
  (let [p (profile/load-profile path)
        r (profile/validate-profile-full p)]
    (machine-output-println {:schema-version cli-schema-version
                             :status (if (:valid? r) "pass" "rejected")
                             :outcome/class (if (:valid? r) "profile-valid" "profile-invalid")
                             :claimable? false
                             :artifact/root nil
                             :issues (mapv :violation/id (:violations r))})
    (System/exit (if (:valid? r) 0 1))))

(defn -main
  [& args]
  (case (vec (take 2 args))
    ["bundle" "verify"] (verify-bundle-command (nth args 2))
    ["bundle" "inspect"] (machine-output-println
                          {:schema-version cli-schema-version
                           :status "ok" :outcome/class "bundle-inspected"
                           :claimable? false :artifact/root nil :issues []})
    ["claim" "derive"] (derive-claim-command (nth args 2))
    ["profile" "validate"] (validate-profile-command (nth args 2))
    (do (println "usage: conformance {bundle verify|inspect, claim derive, profile validate} <arg>")
        (System/exit 2))))
