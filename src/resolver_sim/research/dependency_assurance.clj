(ns resolver-sim.research.dependency-assurance
  "Canonical declaration, policy, and resolution of research dependencies.

   Resolution is identity/provenance binding only. This namespace downloads no
   artifact, authenticates no principal, and performs no confidential
   execution or attestation."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

(def ^:const manifest-schema "research-dependency-manifest.v1")
(def ^:const policy-schema "research-dependency-policy.v1")
(def ^:const resolved-schema "resolved-research-dependency-set.v1")
(def ^:const impact-report-schema "research-dependency-impact-report.v1")

(def dependency-classes
  #{:prf-framework :prf-extension :registry-snapshot :clojure-model
    :scenario-corpus :solidity-source-build :deployed-evm-contract
    :anvil-foundry :proof-program-verifier :runtime-toolchain
    :local-ai-model :dataset-fixture :hermetic-environment})

(def visibility-classes
  #{:public :runner-readable-private :sealed-to-execution-environment})

(def acquisition-modes
  #{:public-by-root :authenticated-private :attestation-gated-sealed})

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- root? [value] (hash-ref/valid-sha256-ref? value))

(defn- canonical-roots? [values]
  (and (vector? values) (= values (vec (sort values)))
       (= (count values) (count (set values))) (every? root? values)))

(defn- private-material-key? [key]
  (let [name* (name key)]
    (or (str/includes? name* "plaintext")
        (str/includes? name* "public-uri")
        (str/includes? name* "download-uri"))))

(defn valid-dependency? [dependency]
  (and (map? dependency)
       (= #{:dependency/id :dependency/class :dependency/visibility
            :dependency/acquisition-mode :dependency/artifact-root
            :dependency/provenance-roots :dependency/metadata}
          (set (keys dependency)))
       (qualified-keyword? (:dependency/id dependency))
       (contains? dependency-classes (:dependency/class dependency))
       (contains? visibility-classes (:dependency/visibility dependency))
       (contains? acquisition-modes (:dependency/acquisition-mode dependency))
       (= (case (:dependency/visibility dependency)
            :public :public-by-root
            :runner-readable-private :authenticated-private
            :sealed-to-execution-environment :attestation-gated-sealed)
          (:dependency/acquisition-mode dependency))
       (root? (:dependency/artifact-root dependency))
       (canonical-roots? (:dependency/provenance-roots dependency))
       (map? (:dependency/metadata dependency))
       (or (= :public (:dependency/visibility dependency))
           (not-any? private-material-key? (keys (:dependency/metadata dependency))))))

(defn manifest-root [manifest]
  (root :research-dependency-manifest-v1
        (dissoc manifest :research-dependency-manifest/root)))

(defn validate-manifest [manifest]
  (let [dependencies (:dependencies manifest)
        valid? (and (map? manifest)
                    (= manifest-schema (:artifact/schema manifest))
                    (= #{:artifact/schema :dependencies
                         :research-dependency-manifest/root}
                       (set (keys manifest)))
                    (vector? dependencies)
                    (seq dependencies)
                    (= dependencies (vec (sort-by :dependency/id dependencies)))
                    (= (count dependencies)
                       (count (set (map :dependency/id dependencies))))
                    (every? valid-dependency? dependencies)
                    (= (:research-dependency-manifest/root manifest)
                       (try (manifest-root manifest) (catch Exception _ nil))))]
    {:valid? (boolean valid?)
     :errors (if valid? [] [:research-dependency-manifest/invalid])}))

(defn build-manifest [dependencies]
  (let [body {:artifact/schema manifest-schema
              :dependencies (vec (sort-by :dependency/id dependencies))}
        rooted (assoc body :research-dependency-manifest/root (manifest-root body))
        validation (validate-manifest rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid research dependency manifest" validation)))
    rooted))

(defn policy-root [policy]
  (root :research-dependency-policy-v1
        (dissoc policy :research-dependency-policy/root)))

(defn validate-policy [policy]
  (let [valid? (and (map? policy)
                    (= policy-schema (:artifact/schema policy))
                    (= #{:artifact/schema :dependency-policy/required-classes
                         :dependency-policy/allowed-visibility
                         :dependency-policy/allowed-acquisition-modes
                         :dependency-policy/sealed-environment-root
                         :research-dependency-policy/root}
                       (set (keys policy)))
                    (set/subset? (set (:dependency-policy/required-classes policy))
                                 dependency-classes)
                    (= (:dependency-policy/required-classes policy)
                       (vec (sort (:dependency-policy/required-classes policy))))
                    (set/subset? (set (:dependency-policy/allowed-visibility policy))
                                 visibility-classes)
                    (= (:dependency-policy/allowed-visibility policy)
                       (vec (sort (:dependency-policy/allowed-visibility policy))))
                    (set/subset? (set (:dependency-policy/allowed-acquisition-modes policy))
                                 acquisition-modes)
                    (= (:dependency-policy/allowed-acquisition-modes policy)
                       (vec (sort (:dependency-policy/allowed-acquisition-modes policy))))
                    (or (nil? (:dependency-policy/sealed-environment-root policy))
                        (root? (:dependency-policy/sealed-environment-root policy)))
                    (= (:research-dependency-policy/root policy)
                       (try (policy-root policy) (catch Exception _ nil))))]
    {:valid? (boolean valid?) :errors (if valid? [] [:research-dependency-policy/invalid])}))

(defn build-policy
  [{:keys [required-classes allowed-visibility allowed-acquisition-modes
           sealed-environment-root]}]
  (let [body {:artifact/schema policy-schema
              :dependency-policy/required-classes (vec (sort required-classes))
              :dependency-policy/allowed-visibility (vec (sort allowed-visibility))
              :dependency-policy/allowed-acquisition-modes
              (vec (sort allowed-acquisition-modes))
              :dependency-policy/sealed-environment-root sealed-environment-root}
        rooted (assoc body :research-dependency-policy/root (policy-root body))
        validation (validate-policy rooted)]
    (when-not (:valid? validation)
      (throw (ex-info "Invalid research dependency policy" validation)))
    rooted))

(defn resolved-root [resolved]
  (root :resolved-research-dependency-set-v1
        (dissoc resolved :resolved-research-dependency-set/root)))

(defn- resolution-valid-for-dependency? [policy dependency resolution]
  (and (= (:dependency/id dependency) (:dependency/id resolution))
       (= (:dependency/artifact-root dependency) (:dependency/artifact-root resolution))
       (= (:dependency/acquisition-mode dependency) (:resolution/acquisition-mode resolution))
       (= :resolved (:resolution/status resolution))
       (root? (:resolution/evidence-root resolution))
       (if (= :sealed-to-execution-environment (:dependency/visibility dependency))
         (and (= :attestation-gated-sealed (:resolution/acquisition-mode resolution))
              (root? (:resolution/environment-root resolution))
              (= (:dependency-policy/sealed-environment-root policy)
                 (:resolution/environment-root resolution)))
         (nil? (:resolution/environment-root resolution)))))

(defn resolve-dependencies
  "Resolve an immutable manifest from explicit acquisition evidence.

   Sealed resolution is declaration-only: an evidence root and exact target
   environment are bound, but no attestation or key release occurs here."
  [manifest policy resolutions]
  (when-not (and (:valid? (validate-manifest manifest))
                 (:valid? (validate-policy policy)))
    (throw (ex-info "Invalid dependency manifest or policy" {})))
  (let [dependencies (:dependencies manifest)
        by-id (into {} (map (juxt :dependency/id identity) resolutions))
        classes (set (map :dependency/class dependencies))
        allowed-visibility (set (:dependency-policy/allowed-visibility policy))
        allowed-modes (set (:dependency-policy/allowed-acquisition-modes policy))]
    (when-not (and (= (set (map :dependency/id dependencies)) (set (keys by-id)))
                   (set/subset? (set (:dependency-policy/required-classes policy)) classes)
                   (every? #(contains? allowed-visibility (:dependency/visibility %)) dependencies)
                   (every? #(contains? allowed-modes (:dependency/acquisition-mode %)) dependencies)
                   (every? (fn [dependency]
                             (resolution-valid-for-dependency?
                              policy dependency (get by-id (:dependency/id dependency))))
                           dependencies))
      (throw (ex-info "Dependency resolution does not satisfy its manifest and policy" {})))
    (let [body {:artifact/schema resolved-schema
                :research-dependency-manifest/root
                (:research-dependency-manifest/root manifest)
                :research-dependency-policy/root
                (:research-dependency-policy/root policy)
                :resolved/dependencies (vec (sort-by :dependency/id resolutions))
                :resolution/assurance :declared-acquisition-evidence}
          rooted (assoc body :resolved-research-dependency-set/root
                        (resolved-root body))]
      rooted)))

(defn valid-resolved-set? [resolved]
  (and (map? resolved)
       (= resolved-schema (:artifact/schema resolved))
       (= :declared-acquisition-evidence (:resolution/assurance resolved))
       (root? (:research-dependency-manifest/root resolved))
       (root? (:research-dependency-policy/root resolved))
       (vector? (:resolved/dependencies resolved))
       (= (:resolved/dependencies resolved)
          (vec (sort-by :dependency/id (:resolved/dependencies resolved))))
       (= (:resolved-research-dependency-set/root resolved)
          (try (resolved-root resolved) (catch Exception _ nil)))))

(defn impact-report-root [report]
  (root :research-dependency-impact-report-v1
        (dissoc report :research-dependency-impact-report/root)))

(defn- reconstruct-resolved-set [manifest policy resolved]
  (when (and (:valid? (validate-manifest manifest))
             (:valid? (validate-policy policy))
             (valid-resolved-set? resolved))
    (try
      (let [reconstructed (resolve-dependencies manifest policy
                                                 (:resolved/dependencies resolved))]
        (when (= reconstructed resolved) reconstructed))
      (catch Exception _ nil))))

(defn- dependency-impact-projection [manifest resolved]
  (let [resolution-index (into {} (map (juxt :dependency/id identity)
                                        (:resolved/dependencies resolved)))]
    (mapv (fn [dependency]
            (let [resolution (get resolution-index (:dependency/id dependency))]
              {:dependency/id (:dependency/id dependency)
               :dependency/class (:dependency/class dependency)
               :dependency/visibility (:dependency/visibility dependency)
               :dependency/artifact-root (:dependency/artifact-root dependency)
               :dependency/provenance-roots (:dependency/provenance-roots dependency)
               :dependency/metadata-root
               (root :research-dependency-metadata-v1 (:dependency/metadata dependency))
               :resolution/evidence-root (:resolution/evidence-root resolution)
               :resolution/acquisition-mode (:resolution/acquisition-mode resolution)}))
          (:dependencies manifest))))

(defn- valid-impact-projection? [projection]
  (and (map? projection)
       (= #{:dependency/id :dependency/class :dependency/visibility
            :dependency/artifact-root :dependency/provenance-roots
            :dependency/metadata-root :resolution/evidence-root
            :resolution/acquisition-mode}
          (set (keys projection)))
       (qualified-keyword? (:dependency/id projection))
       (contains? dependency-classes (:dependency/class projection))
       (contains? visibility-classes (:dependency/visibility projection))
       (root? (:dependency/artifact-root projection))
       (canonical-roots? (:dependency/provenance-roots projection))
       (root? (:dependency/metadata-root projection))
       (root? (:resolution/evidence-root projection))
       (contains? acquisition-modes (:resolution/acquisition-mode projection))))

(defn compare-dependency-state
  "Compare two independently reconstructed dependency states without changing
   either historical artifact. An externally supplied baseline root is
   required before drift can be asserted. Even with no observed drift, this
   report cannot assert currentness or latestness.

   Inputs are maps with :manifest, :policy and :resolved. The baseline trust
   anchor is :trusted-baseline-root and must equal the reconstructed baseline
   resolved-set root. The observed set is caller supplied; acquisition,
   observation freshness and external trust are outside this function.

   Returns a content-addressed impact report with one of:
     :requalification-required
     :unknown-unanchored-baseline
     :matching-roots-freshness-unverified."
  [{:keys [baseline observed trusted-baseline-root]}]
  (let [baseline-set (when (map? baseline)
                       (reconstruct-resolved-set (:manifest baseline)
                                                 (:policy baseline)
                                                 (:resolved baseline)))
        observed-set (when (map? observed)
                       (reconstruct-resolved-set (:manifest observed)
                                                 (:policy observed)
                                                 (:resolved observed)))]
    (when-not (and baseline-set observed-set
                   (root? (:assessment-root baseline))
                   (= (:assessment-dependency-set-root baseline)
                      (:resolved-research-dependency-set/root baseline-set)))
      (throw (ex-info "Dependency impact inputs failed reconstruction"
                      {:reason :invalid-dependency-impact-input})))
    (let [baseline-root (:resolved-research-dependency-set/root baseline-set)
          observed-root (:resolved-research-dependency-set/root observed-set)
          baseline-anchor-status (cond
                                   (not (root? trusted-baseline-root)) :missing
                                   (= trusted-baseline-root baseline-root) :matched
                                   :else :mismatch)
          before (dependency-impact-projection (:manifest baseline) baseline-set)
          after (dependency-impact-projection (:manifest observed) observed-set)
          before-by-id (into {} (map (juxt :dependency/id identity) before))
          after-by-id (into {} (map (juxt :dependency/id identity) after))
          ids (sort (into (set (keys before-by-id)) (keys after-by-id)))
          changes (->> ids
                       (keep (fn [id]
                               (let [old (get before-by-id id)
                                     new (get after-by-id id)]
                                 (when (not= old new)
                                   {:dependency/id id :before old :after new}))))
                       vec)
          state-differs? (or (not= (:research-dependency-manifest/root (:manifest baseline))
                         (:research-dependency-manifest/root (:manifest observed)))
                     (not= (:research-dependency-policy/root (:policy baseline))
                           (:research-dependency-policy/root (:policy observed)))
                     (not= baseline-root observed-root))
          change-reasons (cond-> []
                           (not= (:research-dependency-manifest/root (:manifest baseline))
                                 (:research-dependency-manifest/root (:manifest observed)))
                           (conj :dependency-manifest-differs)
                           (not= (:research-dependency-policy/root (:policy baseline))
                                 (:research-dependency-policy/root (:policy observed)))
                           (conj :dependency-policy-differs)
                           (not= baseline-root observed-root)
                           (conj :resolved-dependency-set-differs))
          impact-status (cond
                          state-differs? :requalification-required
                          (not= :matched baseline-anchor-status) :unknown-unanchored-baseline
                          :else :matching-roots-freshness-unverified)
          body {:artifact/schema impact-report-schema
                :impact/baseline-manifest-root
                (:research-dependency-manifest/root (:manifest baseline))
                :impact/baseline-policy-root
                (:research-dependency-policy/root (:policy baseline))
                :impact/baseline-resolved-root baseline-root
                :impact/historical-assessment-root (:assessment-root baseline)
                :impact/historical-assessment-dependency-root
                (:assessment-dependency-set-root baseline)
                :impact/observed-manifest-root
                (:research-dependency-manifest/root (:manifest observed))
                :impact/observed-policy-root
                (:research-dependency-policy/root (:policy observed))
                :impact/observed-resolved-root observed-root
                :impact/trusted-baseline-root trusted-baseline-root
                :impact/baseline-anchor-status baseline-anchor-status
                :impact/changed-dependencies changes
                :impact/change-reasons change-reasons
                :impact/dependency-state-differs? state-differs?
                :impact/historical-evidence-status :preserved-by-reference
                :impact/current-assessment-status impact-status
                :impact/latestness :not-established
                :impact/authority-issued? false}
          report (assoc body :research-dependency-impact-report/root
                        (impact-report-root body))]
      report)))

(defn validate-impact-report [report]
  (let [anchor (cond
                 (not (root? (:impact/trusted-baseline-root report))) :missing
                 (= (:impact/trusted-baseline-root report)
                    (:impact/baseline-resolved-root report)) :matched
                 :else :mismatch)
        state-differs? (or (not= (:impact/baseline-manifest-root report)
                         (:impact/observed-manifest-root report))
                   (not= (:impact/baseline-policy-root report)
                         (:impact/observed-policy-root report))
                   (not= (:impact/baseline-resolved-root report)
                         (:impact/observed-resolved-root report)))
        change-reasons (cond-> []
                         (not= (:impact/baseline-manifest-root report)
                               (:impact/observed-manifest-root report))
                         (conj :dependency-manifest-differs)
                         (not= (:impact/baseline-policy-root report)
                               (:impact/observed-policy-root report))
                         (conj :dependency-policy-differs)
                         (not= (:impact/baseline-resolved-root report)
                               (:impact/observed-resolved-root report))
                         (conj :resolved-dependency-set-differs))
        expected-status (cond
                          state-differs? :requalification-required
                          (not= :matched anchor) :unknown-unanchored-baseline
                          :else :matching-roots-freshness-unverified)
        changes (:impact/changed-dependencies report)
        change-ids (if (vector? changes) (mapv :dependency/id changes) [])
        change-entries-valid?
        (and (vector? changes)
             (every? (fn [change]
                       (and (map? change)
                            (= #{:dependency/id :before :after} (set (keys change)))
                            (qualified-keyword? (:dependency/id change))
                            (or (valid-impact-projection? (:before change))
                                (nil? (:before change)))
                            (or (valid-impact-projection? (:after change))
                                (nil? (:after change)))
                            (or (:before change) (:after change))
                            (or (nil? (:before change))
                                (= (:dependency/id change) (get-in change [:before :dependency/id])))
                            (or (nil? (:after change))
                                (= (:dependency/id change) (get-in change [:after :dependency/id])))
                            (not= (:before change) (:after change))))
                     changes))
        computed-root (try (impact-report-root report) (catch Exception _ nil))
        errors (cond-> []
                 (not= impact-report-schema (:artifact/schema report))
                 (conj :unsupported-impact-report-schema)
                 (not= #{:artifact/schema
                         :impact/baseline-manifest-root :impact/baseline-policy-root
                         :impact/baseline-resolved-root :impact/observed-manifest-root
                         :impact/observed-policy-root :impact/observed-resolved-root
                         :impact/historical-assessment-root
                         :impact/historical-assessment-dependency-root
                         :impact/trusted-baseline-root :impact/baseline-anchor-status
                         :impact/changed-dependencies :impact/change-reasons
                         :impact/dependency-state-differs?
                         :impact/historical-evidence-status
                         :impact/current-assessment-status :impact/latestness
                         :impact/authority-issued?
                         :research-dependency-impact-report/root}
                       (set (keys report)))
                 (conj :invalid-impact-report-fields)
                 (not-every? root? [(:impact/baseline-manifest-root report)
                                   (:impact/baseline-policy-root report)
                                   (:impact/baseline-resolved-root report)
                                   (:impact/historical-assessment-root report)
                                   (:impact/historical-assessment-dependency-root report)
                                   (:impact/observed-manifest-root report)
                                   (:impact/observed-policy-root report)
                                   (:impact/observed-resolved-root report)])
                 (conj :invalid-impact-root-reference)
                 (not= (:impact/baseline-resolved-root report)
                       (:impact/historical-assessment-dependency-root report))
                 (conj :historical-assessment-dependency-binding-mismatch)
                 (not (root? (:research-dependency-impact-report/root report)))
                 (conj :missing-impact-report-root)
                 (not (contains? #{:matched :missing :mismatch}
                                 (:impact/baseline-anchor-status report)))
                 (conj :invalid-baseline-anchor-status)
                 (not= anchor (:impact/baseline-anchor-status report))
                 (conj :baseline-anchor-status-mismatch)
                 (not (contains? #{:requalification-required
                                   :unknown-unanchored-baseline
                                   :matching-roots-freshness-unverified}
                                 (:impact/current-assessment-status report)))
                 (conj :invalid-impact-status)
                 (not (boolean? (:impact/dependency-state-differs? report)))
                 (conj :invalid-state-difference-flag)
                 (not= :preserved-by-reference (:impact/historical-evidence-status report))
                 (conj :historical-evidence-must-remain-immutable)
                 (not= :not-established (:impact/latestness report))
                 (conj :dependency-impact-cannot-establish-latestness)
                 (not= false (:impact/authority-issued? report))
                 (conj :dependency-impact-cannot-issue-authority)
                 (not= state-differs? (:impact/dependency-state-differs? report))
                 (conj :state-difference-mismatch)
                 (not= change-reasons (:impact/change-reasons report))
                 (conj :change-reasons-mismatch)
                 (not= expected-status (:impact/current-assessment-status report))
                 (conj :current-assessment-status-mismatch)
                 (not (vector? (:impact/changed-dependencies report)))
                 (conj :invalid-changed-dependencies)
                 (not (and (every? qualified-keyword? change-ids)
                           (= change-ids (vec (sort change-ids)))))
                 (conj :noncanonical-changed-dependencies)
                 (not change-entries-valid?)
                 (conj :invalid-dependency-change-entry)
                 (and (:research-dependency-impact-report/root report)
                      (not= (:research-dependency-impact-report/root report)
                            computed-root))
                 (conj :impact-report-root-mismatch))]
    {:valid? (empty? errors) :errors errors}))
