(ns resolver-sim.evidence.diff
  "Post-run structural diff-evidence generation.
   
   Generates diagnostic diff artifacts from before/after world states
   captured in replay traces.  Does NOT add hooks to protocol code.
   
   Usage:
     (build-diff-evidence! trace dir run-id scenario-id)
     ;; reads trace entries, writes diff-evidence/*.json
     ;; returns {:diff-count N :paths [...]}"
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [resolver-sim.evidence.chain :as chain]
            [resolver-sim.evidence.config :as evcfg]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.io.event-evidence :as io-evidence]
            [resolver-sim.logging :as log]))

(declare build-enhanced-diff-artifact build-fully-classified-diff-artifact build-domain-summary classify-diff-changes classify-diff-changes-semantic build-enhanced-domain-summary)

;; ?? Structural Diff Algorithm ????????????????????????????????????????????????

(defn- safe-bytes
  "Total bytes of a top-level value for size comparison."
  [v]
  (try (count (pr-str v))
       (catch Exception e
         (log/warn! "safe-bytes: failed to count bytes" {:value (pr-str v) :error (.getMessage e)})
         0)))

(defn structural-diff
  "Compute a structural diff between two maps (before, after).
   Walks top-level keys only.
   Returns a vector of change maps:
     {:path [:key] :op :added   :after val}
     {:path [:key] :op :removed :before val}
     {:path [:key] :op :changed :before val :after val :delta bytes-delta}"
  [before after]
  (let [keys-before (set (keys before))
        keys-after (set (keys after))
        added (set/difference keys-after keys-before)
        removed (set/difference keys-before keys-after)
        common (set/intersection keys-before keys-after)]
    (concat
     (for [k (sort added)]
       {:path [k] :op :added :after (get after k)})
     (for [k (sort removed)]
       {:path [k] :op :removed :before (get before k)})
     (for [k (sort common)
           :let [bv (get before k) av (get after k)]
           :when (not= bv av)]
       {:path [k] :op :changed
        :before bv :after av
        :delta (- (safe-bytes av) (safe-bytes bv))}))))

(defn stable-diff-hash
  "Compute a stable content hash of a diff's changes vector.
   Strips value-dependent keys so structural identity is
   independent of the actual values at the changed paths."
  [changes]
  (let [stripped (vec (for [c changes]
                        (dissoc c :before :after :delta)))]
    (hc/hash-with-intent {:hash/intent :state-diff} {:changes stripped})))

;; ?? Diff Artifact Builder ????????????????????????????????????????????????????

(defn build-diff-artifact
  "Build a single diff-evidence artifact map."
  [trace-entry before-world after-world event-index group-id changes]
  (let [etype (:action trace-entry)
        ts (:time trace-entry)
        diff-sha256 (stable-diff-hash changes)]
    {:schema/version "diff-evidence.v1"
     :evidence/id (str "diff-" (let [s (str diff-sha256)] (subs s 0 (min 8 (count s)))))
     :evidence/type :state-diff
     :evidence/layer :diff
     :evidence/role :diagnostic
     :event/index event-index
     :event/type (name etype)
     :event/time ts
     :evidence/group-id group-id
     :diff/summary
     {:changed-paths (count (filter #(= :changed (:op %)) changes))
      :added-paths (count (filter #(= :added (:op %)) changes))
      :removed-paths (count (filter #(= :removed (:op %)) changes))
      :financial-changes (count (filter (fn [c]
                                          (#{:total-held :total-principal-deposited
                                             :total-bonds-posted :resolver-stakes
                                             :claimable :total-yield-generated}
                                           (first (:path c))))
                                        changes))}
     :diff/changes (vec changes)
     :hash/diff diff-sha256}))

;; ?? Builder ??????????????????????????????????????????????????????????????????

(defn build-diff-evidence
  "Generate diff-evidence artifacts from a replay trace.
   
   For each consecutive pair of worlds in the trace, computes a structural
   diff and returns a sequence of diff artifact maps.
   
   The first entry (index 0) always produces an artifact showing the
   transition from nil (pre-init) to the initial world state."
  [trace & {:keys [scenario-id run-id group-id-fn]
            :or {scenario-id "unknown" run-id "unknown"
                 group-id-fn (fn [idx action] (str run-id ":" idx ":" action))}}]
  (let [worlds (keep :world trace)
        pairs (map vector (cons nil worlds) worlds)]
    (keep-indexed
     (fn [idx [before after]]
       (let [action (get-in (nth trace idx {:event {:action "init"}}) [:event :action])
             event-action (if (keyword? action) (name action) (str action))
             changes (if (and before after (not= before after))
                       (structural-diff before after)
                       [])
             group-id (group-id-fn idx event-action)]
         (when (seq changes)
           (build-fully-classified-diff-artifact (nth trace idx {:event {:action action}})
                                                 before after
                                                 idx group-id changes))))
     pairs)))

;; ?? Diff Index ????????????????????????????????????????????????????????????????

(defn build-diff-index
  "Build a lightweight index of diff artifacts by event index.
   Returns {:by-event-index {0 {...}, 1 {...}}}
   where each entry has :diff-id, :event-type, :changed-paths, :added-paths, :removed-paths."
  [diffs]
  (let [by-idx (reduce (fn [acc d]
                         (assoc acc (:event/index d)
                                {:diff-id (:evidence/id d)
                                 :event/type (:event/type d)
                                 :changed-paths (:changed-paths (:diff/summary d))
                                 :added-paths (:added-paths (:diff/summary d))
                                 :removed-paths (:removed-paths (:diff/summary d))}))
                       {} diffs)]
    {:by-event-index by-idx}))

;; ?? Write + Register ?????????????????????????????????????????????????????????

(defn write-diff-evidence!
  "Generate and persist diff-evidence artifacts from a replay trace.
   
   Writes each diff to diff-evidence/<id>.json and builds a combined
   diff-index.json.  Registers both in the chain registry.
   
   Returns {:diff-count N :paths [...]}"
  [trace dir & {:keys [scenario-id run-id]}]
  (let [out-dir (or dir (str (evcfg/artifact-dir)))
        diff-dir (str out-dir "/diff-evidence")
        diffs (build-diff-evidence trace :scenario-id scenario-id :run-id run-id)
        index (build-diff-index diffs)]
    (.mkdirs (io/file diff-dir))
    (doseq [d diffs]
      (let [f (io/file diff-dir (str (:evidence/id d) ".json"))]
        (spit f (json/write-str d {:key-fn io-evidence/qualified-key :indent true}))))
    ;; Write combined index
    (let [idx-f (io/file diff-dir "diff-index.json")]
      (spit idx-f (json/write-str index {:indent true})))
    ;; Register in chain
    (chain/register-additional-artifact!
     (chain/index-artifact-entry :diff-evidence "diff-evidence"
                                 "diff-evidence.v1" "DIAGNOSTIC"))
    (println "Wrote" (count diffs) "diff-evidence artifacts to" diff-dir)
    {:diff-count (count diffs)
     :paths (mapv (fn [d] (str "diff-evidence/" (:evidence/id d) ".json")) diffs)}))

;; ?? Path Classification ???????????????????????????????????????????????????????
;;
;; Known world-state path prefixes and their domain classification.
;; Used by classify-diff-paths and build-domain-summary to produce
;; researcher-friendly summaries.
;;
;; A path matches if its first element matches :path-prefix.

(def ^:private path-classification-rules
  [{:path-prefix :total-held :domain :financial :label "Balances held"}
   {:path-prefix :total-principal-deposited :domain :financial :label "Principal deposited"}
   {:path-prefix :total-bonds-posted :domain :financial :label "Bonds posted"}
   {:path-prefix :total-fot-fees :domain :financial :label "FoT fees"}
   {:path-prefix :total-yield-generated :domain :yield :label "Yield generated"}
   {:path-prefix :claimable :domain :claimable :label "Claimable balances"}
   {:path-prefix :resolver-stakes :domain :resolver :label "Resolver stakes"}
   {:path-prefix :resolver-frozen-until :domain :resolver :label "Resolver freeze state"}
   {:path-prefix :resolver-capacities :domain :resolver :label "Resolver capacity"}
   {:path-prefix :resolver-unavailable :domain :resolver :label "Resolver unavailability"}
   {:path-prefix :resolver-yield-profiles :domain :resolver :label "Resolver yield profiles"}
   {:path-prefix :resolver-epoch-slashed :domain :slashing :label "Epoch slashing"}
   {:path-prefix :resolver-slash-total :domain :slashing :label "Slash totals"}
   {:path-prefix :pending-fraud-slashes :domain :slashing :label "Pending fraud slashes"}
   {:path-prefix :appeal-bond-custody :domain :bonding :label "Appeal bond custody"}
   {:path-prefix :bond-balances :domain :bonding :label "Bond balances"}
   {:path-prefix :bond-slashed :domain :bonding :label "Slashed bonds"}
   {:path-prefix :bond-fees :domain :bonding :label "Bond fees"}
   {:path-prefix :escrow-transfers :domain :escrow :label "Escrow transfers"}
   {:path-prefix :dispute-levels :domain :dispute :label "Dispute levels"}
   {:path-prefix :pending-settlements :domain :dispute :label "Pending settlements"}
   {:path-prefix :superseded-pending-settlements :domain :dispute :label "Superseded settlements"}
   {:path-prefix :previous-decisions :domain :dispute :label "Previous decisions"}
   {:path-prefix :next-workflow-id :domain :escrow :label "Next workflow ID"}
   {:path-prefix :total-fees-received :domain :financial :label "Fees received"}
   {:path-prefix :total-breached :domain :risk :label "Breach counter"}
   {:path-prefix :circuit-breaker :domain :risk :label "Circuit breaker"}
   {:path-prefix :unavailability-stats :domain :risk :label "Unavailability stats"}
   {:path-prefix :yield :domain :yield :label "Yield module state"}
   {:path-prefix :module-snapshots :domain :escrow :label "Module snapshots"}
   {:path-prefix :evidence-updated? :domain :slashing :label "Evidence updated flags"}
   {:path-prefix :escrow-settings :domain :escrow :label "Escrow settings"}
   {:path-prefix :last-escalation-block-time :domain :dispute :label "Last escalation time"}
   {:path-prefix :bond-posted-by-workflow :domain :bonding :label "Bonds by workflow"}
   {:path-prefix :appeal-bond-distributions-by-token :domain :bonding :label "Bond distributions"}
   {:path-prefix :appeal-bonds-forfeited-insurance :domain :bonding :label "Forfeited bonds"}
   {:path-prefix :workflow-snapshots :domain :escrow :label "Workflow snapshots"}
   {:path-prefix :previous-workflow-snapshots :domain :escrow :label "Previous snapshots"}
   ;; Internal / noisy paths ? filtered from summaries but kept in changes
   {:path-prefix :context :domain :internal :label "Temporal/execution context" :suppress-from-summary? true}
   {:path-prefix :params :domain :internal :label "Protocol parameters" :suppress-from-summary? true}
   {:path-prefix :agents :domain :internal :label "Agent state" :suppress-from-summary? true}
   {:path-prefix :sequencer :domain :internal :label "Sequencer state" :suppress-from-summary? true}
   {:path-prefix :block-time :domain :internal :label "Temporal clock" :suppress-from-summary? true}
   {:path-prefix :temporal :domain :internal :label "Temporal context" :suppress-from-summary? true}])

(declare build-enhanced-diff-artifact build-fully-classified-diff-artifact build-domain-summary classify-diff-changes classify-diff-changes-semantic build-enhanced-domain-summary)

(defn- classify-path
  "Classify a path vector (e.g. [:total-held :USDC]) into a domain, label, and
   whether to suppress from summaries.  Returns nil for unknown paths."
  [path]
  (some (fn [rule]
          (when (= (:path-prefix rule) (first path))
            (select-keys rule [:domain :label :suppress-from-summary?])))
        path-classification-rules))

(defn classify-diff-changes
  "Add :domain and :label to each change in the changes vector.
   Unknown paths get :domain :unknown :label \"Other\"."
  [changes]
  (mapv (fn [c]
          (if-let [cls (classify-path (:path c))]
            (assoc c :domain (:domain cls) :label (:label cls))
            (assoc c :domain :unknown :label "Other")))
        changes))

;; ?? Domain Summaries ??????????????????????????????????????????????????????????

(defn build-domain-summary
  "Group diff changes by domain and produce a compact summary.
   
   Returns a map keyed by domain keyword with:
     :domain       ? domain keyword
     :label        ? human-readable label
     :changed      ? count of changed paths
     :added        ? count of added paths
     :removed      ? count of removed paths
     :total        ? total changes in this domain
     :suppressed?  ? true if this domain is filtered from researcher summaries"
  [classified-changes]
  (let [by-domain (group-by :domain classified-changes)
        sup? (set (keep :domain (filter :suppress-from-summary? (map classify-path (map :path classified-changes)))))]
    (into (sorted-map)
          (map (fn [[domain changes]]
                 [domain {:domain domain
                          :label (or (some #(:label %) (filter #(= domain (:domain %)) (map classify-path (map :path changes)))) (name domain))
                          :changed (count (filter #(= :changed (:op %)) changes))
                          :added (count (filter #(= :added (:op %)) changes))
                          :removed (count (filter #(= :removed (:op %)) changes))
                          :total (count changes)
                          :suppressed? (contains? sup? domain)}]))
          by-domain)))

;; ?? Updated Diff Artifact Builder ????????????????????????????????????????????

(defn build-enhanced-diff-artifact
  "Like build-diff-artifact but includes classified paths and domain summaries.
   
   Adds:
   - :diff/changes with :domain and :label on each entry
   - :diff/domains with domain summaries
   - :diff/summary with suppressed-{changed,added,removed} counts for noisy paths"
  [trace-entry before-world after-world event-index group-id changes]
  (let [classified (classify-diff-changes changes)
        domains (build-domain-summary classified)
        base (build-diff-artifact trace-entry before-world after-world event-index group-id changes)
        suppressed-total (reduce + 0 (map :total (filter :suppressed? (vals domains))))
        suppressed-changed (reduce + 0 (map :changed (filter :suppressed? (vals domains))))
        suppressed-added (reduce + 0 (map :added (filter :suppressed? (vals domains))))
        suppressed-removed (reduce + 0 (map :removed (filter :suppressed? (vals domains))))]
    (assoc base
           :diff/changes classified
           :diff/domains domains
           :diff/summary (assoc (:diff/summary base)
                                :suppressed-paths suppressed-total
                                :suppressed-changed suppressed-changed
                                :suppressed-added suppressed-added
                                :suppressed-removed suppressed-removed))))

;; ?? Invariant Result Linking ?????????????????????????????????????????????????
;;
;; Links invariant results from replay traces into the evidence registry.
;; Invariant results are already present in trace entries as :violations and
;; :invariants-ok?.  This module extracts them and produces an index that can
;; be merged into the evidence registry's :indexes map.
;;
(defn trace-has-invariants?
  "Check if a trace entry carries invariant results."
  [trace-entry]
  (contains? trace-entry :violations))

(defn- extract-violation-summary
  "Convert a single violation map into a compact summary."
  [inv-id violation]
  (let [viols (:violations violation [])
        holds? (:holds? violation true)]
    {:invariant/id (name inv-id)
     :invariant/holds? holds?
     :invariant/violation-count (count viols)
     :invariant/violations (when (seq viols) (take 3 viols))}))  ;; truncate for compactness

(defn build-invariant-links
  "Extract invariant results from a replay trace and produce an index.
   
   Returns a map with:
   :by-event-index ? event-index -> {:invariants-ok? bool :invariant-ids [...]}
   :by-invariant-id ? inv-id -> [{:event/index N :holds? bool :violation-count N}]
   
   No changes to invariant capture ? purely post-processing over the trace."
  [trace]
  (let [events (keep-indexed
                (fn [idx entry]
                  (when (trace-has-invariants? entry)
                    (let [v (:violations entry)]
                      (when (map? v)
                        [idx {:violations v
                              :invariants-ok? (:invariants-ok? entry true)
                              :action (:action entry)}]))))
                trace)]
    (if (empty? events)
      {:by-event-index {} :by-invariant-id {}}
      (let [by-event (reduce (fn [acc [idx {:keys [violations invariants-ok?]}]]
                               (assoc acc idx {:invariants-ok? invariants-ok?
                                               :invariant-ids (vec (keys violations))
                                               :invariant-summaries (mapv (fn [[k v]]
                                                                            (extract-violation-summary k v))
                                                                          (seq violations))}))
                             {} events)
            by-inv-id (reduce (fn [acc [idx {:keys [violations]}]]
                                (reduce-kv (fn [acc2 inv-id v]
                                             (update acc2 inv-id conj
                                                     (merge {:event/index idx}
                                                            (extract-violation-summary inv-id v))))
                                           acc violations))
                              {} events)]
        {:by-event-index by-event
         :by-invariant-id by-inv-id}))))

(defn merge-invariant-links
  "Merge invariant link indexes into an existing evidence registry.
   
   registry ? the registry map (with :indexes)
   trace    ? the replay trace
   
   Returns the registry with invariant links added to :indexes.
   The invariant links are additive ? no existing indexes are modified."
  [registry trace]
  (let [links (build-invariant-links trace)]
    (if (empty? (:by-event-index links))
      registry
      (update-in registry [:indexes :by-invariant]
                 merge {:links links}))))

;; ?? Semantic Classification ??????????????????????????????????????????????????
;;
;; Adds a :classification field to each diff change indicating the nature
;; of the change from a researcher's perspective.
;;
;; Classification values:
;;   :expected            ? Normal state mutation from this event type
;;   :unexpected          ? State mutation that is notable for this event type
;;   :invariant-relevant  ? Change that affects invariant checks (balances, claims)
;;   :financial-boundary  ? Change at a financial domain boundary
;;   :diagnostic-only     ? Internal/administrative, not analysis-relevant
;;   :unknown             ? Cannot classify, researcher should inspect

(declare build-enhanced-diff-artifact build-fully-classified-diff-artifact build-domain-summary classify-diff-changes classify-diff-changes-semantic build-enhanced-domain-summary)

;; ?? Structural Diff Algorithm ????????????????????????????????????????????????

(defn- safe-bytes
  "Total bytes of a top-level value for size comparison."
  [v]
  (try (count (pr-str v))
       (catch Exception e
         (log/warn! "safe-bytes: failed to count bytes" {:value (pr-str v) :error (.getMessage e)})
         0)))

;; ── Path Classification ───────────────────────────────────────────────────────

(def ^:private path-classification-rules
  [{:path-prefix :total-held :domain :financial :label "Balances held"}
   {:path-prefix :total-principal-deposited :domain :financial :label "Principal deposited"}
   {:path-prefix :total-bonds-posted :domain :financial :label "Bonds posted"}
   {:path-prefix :total-fot-fees :domain :financial :label "FoT fees"}
   {:path-prefix :total-yield-generated :domain :yield :label "Yield generated"}
   {:path-prefix :claimable :domain :claimable :label "Claimable balances"}
   {:path-prefix :resolver-stakes :domain :resolver :label "Resolver stakes"}
   {:path-prefix :resolver-frozen-until :domain :resolver :label "Resolver freeze state"}
   {:path-prefix :resolver-capacities :domain :resolver :label "Resolver capacity"}
   {:path-prefix :resolver-unavailable :domain :resolver :label "Resolver unavailability"}
   {:path-prefix :resolver-yield-profiles :domain :resolver :label "Resolver yield profiles"}
   {:path-prefix :resolver-epoch-slashed :domain :slashing :label "Epoch slashing"}
   {:path-prefix :resolver-slash-total :domain :slashing :label "Slash totals"}
   {:path-prefix :pending-fraud-slashes :domain :slashing :label "Pending fraud slashes"}
   {:path-prefix :appeal-bond-custody :domain :bonding :label "Appeal bond custody"}
   {:path-prefix :bond-balances :domain :bonding :label "Bond balances"}
   {:path-prefix :bond-slashed :domain :bonding :label "Slashed bonds"}
   {:path-prefix :bond-fees :domain :bonding :label "Bond fees"}
   {:path-prefix :escrow-transfers :domain :escrow :label "Escrow transfers"}
   {:path-prefix :dispute-levels :domain :dispute :label "Dispute levels"}
   {:path-prefix :pending-settlements :domain :dispute :label "Pending settlements"}
   {:path-prefix :superseded-pending-settlements :domain :dispute :label "Superseded settlements"}
   {:path-prefix :previous-decisions :domain :dispute :label "Previous decisions"}
   {:path-prefix :next-workflow-id :domain :escrow :label "Next workflow ID"}
   {:path-prefix :total-fees-received :domain :financial :label "Fees received"}
   {:path-prefix :total-breached :domain :risk :label "Breach counter"}
   {:path-prefix :circuit-breaker :domain :risk :label "Circuit breaker"}
   {:path-prefix :unavailability-stats :domain :risk :label "Unavailability stats"}
   {:path-prefix :yield :domain :yield :label "Yield module state"}
   {:path-prefix :module-snapshots :domain :escrow :label "Module snapshots"}
   {:path-prefix :evidence-updated? :domain :slashing :label "Evidence updated flags"}
   {:path-prefix :escrow-settings :domain :escrow :label "Escrow settings"}
   {:path-prefix :last-escalation-block-time :domain :dispute :label "Last escalation time"}
   {:path-prefix :bond-posted-by-workflow :domain :bonding :label "Bonds by workflow"}
   {:path-prefix :appeal-bond-distributions-by-token :domain :bonding :label "Bond distributions"}
   {:path-prefix :appeal-bonds-forfeited-insurance :domain :bonding :label "Forfeited bonds"}
   {:path-prefix :workflow-snapshots :domain :escrow :label "Workflow snapshots"}
   {:path-prefix :previous-workflow-snapshots :domain :escrow :label "Previous snapshots"}
   {:path-prefix :context :domain :internal :label "Temporal/execution context" :suppress-from-summary? true}
   {:path-prefix :params :domain :internal :label "Protocol parameters" :suppress-from-summary? true}
   {:path-prefix :agents :domain :internal :label "Agent state" :suppress-from-summary? true}
   {:path-prefix :sequencer :domain :internal :label "Sequencer state" :suppress-from-summary? true}
   {:path-prefix :block-time :domain :internal :label "Temporal clock" :suppress-from-summary? true}
   {:path-prefix :temporal :domain :internal :label "Temporal context" :suppress-from-summary? true}])

(defn- classify-path
  [path]
  (some (fn [rule]
          (when (= (:path-prefix rule) (first path))
            (select-keys rule [:domain :label :suppress-from-summary?])))
        path-classification-rules))

(defn classify-diff-changes
  [changes]
  (mapv (fn [c]
          (if-let [cls (classify-path (:path c))]
            (assoc c :domain (:domain cls) :label (:label cls))
            (assoc c :domain :unknown :label "Other")))
        changes))

(defn build-domain-summary
  [classified-changes]
  (let [by-domain (group-by :domain classified-changes)
        sup? (set (keep :domain (filter :suppress-from-summary? (map classify-path (map :path classified-changes)))))]
    (into (sorted-map)
          (map (fn [[domain changes]]
                 [domain {:domain domain
                          :label (or (some #(:label %) (filter #(= domain (:domain %)) (map classify-path (map :path changes)))) (name domain))
                          :changed (count (filter #(= :changed (:op %)) changes))
                          :added (count (filter #(= :added (:op %)) changes))
                          :removed (count (filter #(= :removed (:op %)) changes))
                          :total (count changes)
                          :suppressed? (contains? sup? domain)}]))
          by-domain)))

(defn build-enhanced-diff-artifact
  [trace-entry before-world after-world event-index group-id changes]
  (let [classified (classify-diff-changes changes)
        domains (build-domain-summary classified)
        base (build-diff-artifact trace-entry before-world after-world event-index group-id changes)
        suppressed-total (reduce + 0 (map :total (filter :suppressed? (vals domains))))
        suppressed-changed (reduce + 0 (map :changed (filter :suppressed? (vals domains))))
        suppressed-added (reduce + 0 (map :added (filter :suppressed? (vals domains))))
        suppressed-removed (reduce + 0 (map :removed (filter :suppressed? (vals domains))))]
    (assoc base
           :diff/changes classified
           :diff/domains domains
           :diff/summary (assoc (:diff/summary base)
                                :suppressed-paths suppressed-total
                                :suppressed-changed suppressed-changed
                                :suppressed-added suppressed-added
                                :suppressed-removed suppressed-removed))))

;; ── Semantic Classification ──────────────────────────────────────────────────

(def ^:private event-type-classification
  {:total-held #{:create_escrow :release :refund :sender-cancel
                 :recipient-cancel :execute_pending_settlement
                 :slash_resolver :execute_fraud_slash :finalize
                 :deposit :withdraw :post_appeal_bond}
   :total-principal-deposited #{:create_escrow}
   :total-bonds-posted #{:post_appeal_bond :escalate_dispute :challenge_resolution}
   :total-yield-generated #{:accrue_yield :execute_pending_settlement :finalize}
   :total-fees-received #{:create_escrow :withdraw_fees}
   :total-fot-fees #{:create_escrow :release :refund}
   :total-breached #{:slash_resolver :execute_fraud_slash :finalize}
   :resolver-stakes #{:register_stake :withdraw_stake :slash_resolver
                      :execute_fraud_slash :handle_reversal_slashing}
   :resolver-frozen-until #{:execute_fraud_slash :execute_fraud_group_slash :unfreeze_resolver}
   :resolver-capacities #{:raise_dispute :execute_resolution :finalize}
   :resolver-epoch-slashed #{:execute_fraud_slash}
   :resolver-slash-total #{:execute_fraud_slash}
   :pending-fraud-slashes #{:propose_fraud_slash :appeal_slash :resolve_appeal :execute_fraud_slash}
   :appeal-bond-custody #{:appeal_slash :resolve_appeal}
   :bond-balances #{:post_appeal_bond :slash_bond :return_bond}
   :bond-slashed #{:slash_bond}
   :bond-fees #{:post_appeal_bond}
   :escrow-transfers #{:create_escrow :raise_dispute :execute_resolution
                       :execute_pending_settlement :release :refund
                       :sender_cancel :recipient_cancel}
   :dispute-levels #{:escalate_dispute :challenge_resolution}
   :pending-settlements #{:execute_resolution :execute_pending_settlement
                          :escalate_dispute :challenge_resolution}
   :previous-decisions #{:execute_resolution}
   :next-workflow-id #{:create_escrow}
   :claimable #{:execute_pending_settlement :release :refund
                :claim_deferred_yield :return_bond :slash_bond
                :withdraw_escrow :auto_cancel :auto_cancel_disputed}
   :yield #{:deposit :withdraw :accrue_yield :execute_pending_settlement :finalize}
   :circuit-breaker #{:slash_resolver :execute_fraud_slash :update_unavailability}
   :evidence-updated? #{:execute_resolution :propose_fraud_slash :handle_reversal_slashing}})

(defn classify-change
  [change event-action]
  (let [path-prefix (first (:path change))
        rule (some #(when (= (:path-prefix %) path-prefix) %) path-classification-rules)
        domain (:domain rule)
        is-internal? (= :internal domain)
        is-financial? (#{:financial :claimable} domain)
        expected-actions (get event-type-classification path-prefix #{})
        is-expected? (contains? expected-actions (keyword event-action))]
    (cond
      is-internal? :diagnostic-only
      is-financial? (if is-expected? :expected :financial-boundary)
      is-expected? :expected
      :else (if is-financial? :financial-boundary :unexpected))))

(defn classify-diff-changes-semantic
  [changes event-action]
  (mapv (fn [c]
          (let [base-class (classify-change c event-action)]
            (assoc c :classification base-class)))
        changes))

(defn build-enhanced-domain-summary
  [classified-changes]
  (let [domain-summary (build-domain-summary classified-changes)
        by-cls (frequencies (map :classification classified-changes))
        total (count classified-changes)]
    (assoc domain-summary :by-classification by-cls :total-classified total)))

(defn build-fully-classified-diff-artifact
  [trace-entry before-world after-world event-index group-id changes]
  (let [event-action (:action trace-entry)
        action-kw (if (keyword? event-action) event-action (keyword (name event-action)))
        semantic-changes (classify-diff-changes-semantic changes action-kw)
        base (build-enhanced-diff-artifact trace-entry before-world after-world
                                           event-index group-id semantic-changes)
        domain-summary-with-class (build-enhanced-domain-summary (:diff/changes base))]
    (assoc base
           :diff/changes semantic-changes
           :diff/domains domain-summary-with-class
           :diff/summary (assoc (:diff/summary base)
                                :by-classification (:by-classification domain-summary-with-class)))))

;; ── Invariant Result Linking ─────────────────────────────────────────────────

(defn trace-has-invariants?
  [trace-entry]
  (contains? trace-entry :violations))

(defn- extract-violation-summary
  [inv-id violation]
  (let [viols (:violations violation [])
        holds? (:holds? violation true)]
    {:invariant/id (name inv-id)
     :invariant/holds? holds?
     :invariant/violation-count (count viols)
     :invariant/violations (when (seq viols) (take 3 viols))}))

(defn build-invariant-links
  [trace]
  (let [events (keep-indexed
                (fn [idx entry]
                  (when (trace-has-invariants? entry)
                    (let [v (:violations entry)]
                      (when (map? v)
                        [idx {:violations v
                              :invariants-ok? (:invariants-ok? entry true)
                              :action (:action entry)}]))))
                trace)]
    (if (empty? events)
      {:by-event-index {} :by-invariant-id {}}
      (let [by-event (reduce (fn [acc [idx {:keys [violations invariants-ok?]}]]
                               (assoc acc idx {:invariants-ok? invariants-ok?
                                               :invariant-ids (vec (keys violations))
                                               :invariant-summaries (mapv (fn [[k v]] (extract-violation-summary k v)) (seq violations))}))
                             {} events)
            by-inv-id (reduce (fn [acc [idx {:keys [violations]}]]
                                (reduce-kv (fn [acc2 inv-id v]
                                             (update acc2 inv-id conj
                                                     (merge {:event/index idx} (extract-violation-summary inv-id v))))
                                           acc violations))
                              {} events)]
        {:by-event-index by-event
         :by-invariant-id by-inv-id}))))

(defn merge-invariant-links
  [registry trace]
  (let [links (build-invariant-links trace)]
    (if (empty? (:by-event-index links))
      registry
      (update-in registry [:indexes :by-invariant] merge {:links links}))))
