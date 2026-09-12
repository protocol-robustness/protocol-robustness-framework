(ns resolver-sim.evidence.acceptance
  "Composed acceptance report.

   Artifact validity is NOT a sufficient acceptance decision on its own. This
   namespace composes the distinct acceptance stages so the boundary between
   them stays explicit:

     schema-valid -> content-integrity-valid -> registry-membership
     -> required-chain -> publisher-commitment -> file-integrity

   Each stage is a distinct, independent result. No stage is overloaded with
   the policy of another; a strong content-integrity result never implies
   publisher authenticity, and a valid publisher signature never implies
   canonical artifact content.

   Stages:
     :content-integrity     content-addressed artifact validity
                           (resolver-sim.evidence.artifact/verify-artifact)
     :registry-membership   the artifact has an entry in the accepted registry
     :required-chain        the artifact is in the required chain for the run
     :publisher-commitment  a publisher envelope commits to the artifact set
                           under an authorised key
     :file-integrity        the file on disk matches the registry binding
                           (hash/path/bytes)")

(def acceptance-stages
  "The ordered acceptance stages, from content to external binding."
  [:content-integrity
   :registry-membership
   :required-chain
   :publisher-commitment
   :file-integrity])

(defn stage-report
  "Normalize one stage result. Accepts {:valid? bool :reason kw :details map}
   or nil (treated as an unexplained failure, :stage-missing)."
  [stage-result]
  (cond
    (nil? stage-result)
    {:valid? false :reason :stage-missing :details {}}

    (map? stage-result)
    {:valid? (boolean (:valid? stage-result))
     :reason (or (:reason stage-result) :ok)
     :details (or (:details stage-result) {})}

    :else
    {:valid? false :reason :stage-malformed :details {:value stage-result}}))

(defn acceptance-report
  "Compose a per-stage acceptance report.

   `stages` is a map of stage keyword -> stage result. Any stage not supplied
   is recorded as an unexplained failure (:stage-missing), so the composed
   report is fail-closed. Returns:

     {:accepted? bool
      :content-integrity {:valid? ... :reason ... :details ...}
      :registry-membership {...}
      :required-chain {...}
      :publisher-commitment {...}
      :file-integrity {...}}"
  [stages]
  (let [normalized
        (into {}
              (map (fn [stage]
                     [stage (stage-report (get stages stage))]))
              acceptance-stages)]
    (merge {:accepted? (every? :valid? (vals normalized))} normalized)))

(defn accepted?
  "True only when every acceptance stage reports :valid?."
  [report]
  (:accepted? report))
