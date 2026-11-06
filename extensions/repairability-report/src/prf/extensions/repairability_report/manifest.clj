(ns prf.extensions.repairability-report.manifest
  "Read-only repairability report section capability."
  (:require [prf.extensions.repairability-report.report :as report]))

(def capability
  {:capability/kind :report/section
   :capability/id :repairability/report-v1
   :capability/version 1
   :capability/contract-version 1
   :entrypoint 'prf.extensions.repairability-report.manifest/extension
   :input-schema :prf/repairability-report-input.v1
   :output-schema :prf/repairability-report-section.v1
   :composition-contract
   {:composition-contract/version 1
    :composition/input {:schema-ref :prf/repairability-report-input.v1
                         :semantic-type :repair-evidence-projection
                         :cardinality :one}
    :composition/output {:schema-ref :prf/repairability-report-section.v1
                          :semantic-type :read-only-report-section
                          :cardinality :one}}
   :declared-dependencies []})

(def package
  {:extension/id :prf.extensions/repairability-report
   :extension/version "0.1.0"
   :extension/api-version 1
   :extension/manifest-version 1
   :extension/capabilities [capability]
   :extension/license "Apache-2.0"
   :extension/maintainers ["PRF core"]
   :extension/support-policy :experimental
   :extension/funding-status :unfunded
   :extension/status {:lifecycle :candidate
                      :distribution :in-tree
                      :conformance :unqualified
                      :reproduction :not-qualified
                      :verification :not-qualified
                      :maintenance :best-effort
                      :adoption :none}})

(defn extension
  "Return the report section renderer for explicit registry resolution."
  []
  {:build-section report/build-section})
