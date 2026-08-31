(ns resolver-sim.benchmark.replication-observation-set
  "Pure derived observations over signed researcher run reports.

   This namespace deliberately does not define replication semantics.  Exact
   scope remains owned by outcome-manifest/exact-replication-scope?.

   Identity vocabulary: the observation exposes each member via the
   established `*/root` reference naming (repository convention for committed
   content-address roots).  These are NOT independently derived identities;
   each is an exact projection of the canonical hash already committed by the
   source artifacts, so no parallel identity vocabulary exists:

     :researcher-run-report/root  ≡ :researcher-run-report/hash
     :outcome-manifest/root       ≡ :researcher-run-report/outcome-manifest-hash
                                  ≡ (:benchmark-outcome/hash manifest)
     :outcome/root                ≡ :researcher-run-report/outcome-hash
                                  ≡ (outcome/outcome-hash manifest)

   The only new root is :replication-observation-set/root, the observation's
   own domain-hashed commitment.  The projection invariants above are pinned
   by test."
  (:require [resolver-sim.benchmark.outcome-manifest :as outcome]
            [resolver-sim.benchmark.verified-researcher-run :as verified]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def schema-version "replication-observation-set.v1")
(def relation-type :exact-replication)
(def assurance-boundary
  {:signature-verification :verified-against-supplied-key
   :researcher-to-key-binding :unresolved})

(defn- fail! [reason data]
  (throw (ex-info (str "Invalid replication observation: " (name reason))
                  (assoc data :reason reason))))

(defn- verify-member!
  "Verify one member entry through the shared verified-researcher-run primitive.
   Returns {:report :manifest :report-root :manifest-root :outcome-root}."
  [entry]
  (verified/verify entry))

(defn- canonical-members [members]
  (vec (sort-by :report-root members)))

(defn- check-duplicates! [members]
  (let [roots (map :report-root members)
        pairs (map (fn [{:keys [report]}]
                     [(:researcher/id report) (:run/id report)]) members)]
    (when-not (= (count roots) (count (distinct roots)))
      (fail! :duplicate-report-root {:roots roots}))
    (when-not (= (count pairs) (count (distinct pairs)))
      (fail! :duplicate-researcher-run {:pairs pairs}))))

(defn- scope-check! [members]
  (let [reference (-> members first :manifest)]
    (doseq [{:keys [manifest report-root]} (rest members)]
      (when-not (outcome/exact-replication-scope? reference manifest)
        (fail! :incompatible-exact-replication-scope
               {:reference-manifest (:researcher-run-report/outcome-manifest-hash
                                     (-> members first :report))
                :report-root report-root})))))

(defn- outcome-groups [members]
  (->> members
       (group-by :outcome-root)
       (sort-by key)
       (mapv (fn [[root ms]]
               {:outcome/root root
                :members (vec (sort (map :report-root ms)))}))))

(defn- agreement-status [n groups]
  (cond
    (= 1 n) :insufficient
    (= 1 (count groups)) :unanimous
    (= n (count groups)) :all-distinct
    :else :disagreement))

(defn- diversity [members]
  (let [values (fn [f] (vec (sort (distinct (keep f members)))))]
    {:researcher-ids (values #(get-in % [:report :researcher/id]))
     :runner-ids (values #(get-in % [:report :runner :runner/id]))
     :source-tree-hashes (values #(get-in % [:report :runner :source-tree-hash]))
     :distribution-hashes (values #(get-in % [:report :runner :distribution-hash]))
     :environment-hashes (values #(get-in % [:report :runner :environment-hash]))}))

(defn- body-from-members [members]
  (let [canonical (canonical-members members)
        groups (outcome-groups canonical)]
    {:schema-version schema-version
     :relation/type relation-type
     :reference/member (-> canonical first :report-root)
     :members (mapv (fn [{:keys [report-root manifest-root outcome-root]}]
                      {:researcher-run-report/root report-root
                       :outcome-manifest/root manifest-root
                       :outcome/root outcome-root}) canonical)
     :scope/verification {:classification relation-type
                          :all-members-exact-replication? true}
     :outcome-agreement {:status (agreement-status (count canonical) groups)
                         :completed (count canonical)
                         :distinct-outcome-roots (count groups)
                         :groups groups}
     :provenance-diversity (diversity canonical)
     :authorship-assurance assurance-boundary}))

(defn observation-root [body]
  (hash-ref/sha256-ref
   (hc/domain-hash :replication-observation-set body)))

(defn build
  "Build a canonical observation from entries {:report :manifest :public-key-path}.
   A :public-key-resolver may instead resolve a verifier key from each report."
  [entries]
  (when-not (seq entries) (fail! :no-members {}))
  (let [members (canonical-members (mapv verify-member! entries))]
    (check-duplicates! members)
    (scope-check! members)
    (let [body (body-from-members members)]
      (assoc body :replication-observation-set/root (observation-root body)))))

(defn verify
  "Revalidate supplied entries and require exact equality with observation.
   Caller-supplied derived fields are therefore never trusted."
  [observation entries]
  (try
    (let [expected (build entries)
          valid? (= expected observation)]
      {:valid? valid?
       :reason (when-not valid? :derived-observation-mismatch)
       :expected expected})
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :errors (ex-data e)})))
