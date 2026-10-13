(ns resolver-sim.benchmark.packs.partial-fill.accounting-projection
  "Explicit bridge from validated partial-fill application evidence to rooted
   research-accounting sources. The bridge does not infer accounting subjects
   or claim targets from participant/account identifiers: callers provide a
   complete, validated claim-to relation and accounting subject root per entry.

   This remains a generic explicit crosswalk, not a yield claim generator.
   Yield participant credits may eventually bind to genuine canonical claims;
   pool-side funding debits are movements, not claimant claims. Since the
   current accounting-request.v1 source contract requires claim-to/root on each
   source, do not use this adapter to manufacture a debit relation."
  (:require [resolver-sim.benchmark.packs.partial-fill.pro-rata-application-evidence
             :as application-evidence]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accounting-semantics :as accounting-semantics]
            [resolver-sim.yield.partial-fill :as pf]))

(def ^:const schema "partial-fill-accounting-source-map.v1")

(defn- root [domain value]
  (hash-ref/sha256-ref (hc/domain-hash domain value)))

(defn- root? [value]
  (hash-ref/valid-sha256-ref? value))

(defn source-map-root [mapping]
  (root :evidence-record
        (dissoc mapping :accounting-source-map/root)))

(defn- entry-root [output-root entry]
  (root :evidence-content
        {:application-output/root output-root
         :accounting-entry entry}))

(defn- accounting-source [mapping-root binding]
  (let [entry (:accounting-entry binding)
        category (case (:direction entry)
                   :debit :accounting/debit
                   :credit :accounting/credit
                   nil)
        value (:amount entry)
        body {:accounting/category category
              :accounting/value value
              :accounting/subject-root (:accounting/subject-root binding)
              :claim-to/root (:claim-to/root binding)}]
    (when-not (and category (integer? value))
      (throw (ex-info "Unsupported partial-fill accounting entry"
                      {:entry entry})))
    (assoc body :source/root
           (root :evidence-record
                 {:accounting-source-map/root mapping-root
                  :entry/root (:entry/root binding)
                  :accounting/source body}))))

(defn build-source-map
  "Bind every entry in a validated partial-fill application to an explicit
   accounting subject and rooted directional claim-to relation.

   `entry-bindings` must contain exactly one row per canonical output entry:
     {:entry-index n
      :accounting/subject-root <root>
      :claim-to <valid claim-to-relation.v1>}

   No target/subject root is derived from symbolic account, participant, or
   obligation identifiers. Returns a rooted mapping plus research-accounting
   sources suitable for `accounting-request.v1` only when every supplied
   relation is semantically genuine. In particular, the current V1 accounting
   source contract cannot yet represent a pool-side debit without a claim-to
   relation; do not fill that gap with a synthetic or aggregate claim."
  [{:keys [application-evidence-profile application-evidence-args entry-bindings]}]
  (let [application (:application application-evidence-args)
        propagation (:propagation application-evidence-args)
        profile-check
        (application-evidence/verify-pro-rata-application-evidence
         application-evidence-profile application-evidence-args)
        raw-entries (pf/canonical-accounting-entries
                     (:accounting-entries application))
        expected-entry-hash
        (hash-ref/sha256-ref
         (hc/domain-hash :evidence-collection raw-entries))
        output (pf/pro-rata-application-output-projection application propagation)
        output-root (pf/pro-rata-application-output-hash application propagation)
        entries (get-in output [:accounting :entries])
        entries-by-index (vec (sort-by :entry-index entries))
        indices (mapv :entry-index entries-by-index)
        bindings (vec (sort-by :entry-index entry-bindings))]
    (when-not (:valid? profile-check)
      (throw (ex-info "Partial-fill application evidence does not verify"
                      {:mismatches (:mismatches profile-check)})))
    (when-not (and (true? (get-in application-evidence-profile
                                  [:evidence-profile/verification :propagation-valid?]))
                   (true? (get-in application-evidence-profile
                                  [:evidence-profile/verification :accounting-reconciled?])))
      (throw (ex-info "Partial-fill accounting evidence is not validated"
                      {:verification (:evidence-profile/verification
                                      application-evidence-profile)})))
    (when-not (and (true? (:valid? (pf/validate-pro-rata-propagation propagation)))
                   (= (:evidence-profile/propagation-hash application-evidence-profile)
                      (:propagation/hash propagation))
                   (= (:evidence-profile/application-hash application-evidence-profile)
                      (:application/hash application))
                   (= expected-entry-hash
                      (:evidence-profile/accounting-evidence-hash
                       application-evidence-profile))
                   (= raw-entries
                      (pf/canonical-accounting-entries
                       (:accounting-entries propagation))))
      (throw (ex-info "Application profile is not bound to supplied accounting entries"
                      {:application-hash (:application/hash application)
                       :propagation-hash (:propagation/hash propagation)})))
    (when-not (and (seq entries)
                   (= indices (vec (range (count entries))))
                   (= (count entries) (count bindings))
                   (= indices (mapv :entry-index bindings)))
      (throw (ex-info "Accounting entry bindings must exactly cover output entries"
                      {:expected-indices indices
                       :actual-indices (mapv :entry-index bindings)})))
    (let [bound-entries
          (mapv (fn [entry binding]
                  (let [subject-root (:accounting/subject-root binding)
                        relation (:claim-to binding)]
                    (when-not (and (root? subject-root)
                                   (accounting-semantics/valid-claim-to? relation))
                      (throw (ex-info "Invalid accounting subject or claim-to binding"
                                      {:entry-index (:entry-index entry)})))
                    {:entry-index (:entry-index entry)
                     :entry/root (entry-root output-root entry)
                     :accounting-entry entry
                     :accounting/subject-root subject-root
                     :claim-to/root (:claim-to/root relation)}))
                entries-by-index bindings)
          base {:artifact/schema schema
                :application-evidence/root
                (:evidence-profile/hash application-evidence-profile)
                :application-output/root output-root
                :accounting/source-bindings
                (mapv #(dissoc % :accounting-entry) bound-entries)}
          mapping (assoc base :accounting-source-map/root (source-map-root base))
          sources (mapv #(accounting-source (:accounting-source-map/root mapping) %)
                        bound-entries)]
      {:mapping mapping
       :sources (vec (sort-by :source/root sources))})))

(defn valid-source-map?
  "Check mapping self-root, required parent roots, canonical entry ordering,
   and one-to-one entry coverage. Full parent artifact revalidation belongs to
   `build-source-map`, which consumes the source artifacts themselves."
  [mapping]
  (and (= schema (:artifact/schema mapping))
       (root? (:application-evidence/root mapping))
       (root? (:application-output/root mapping))
       (vector? (:accounting/source-bindings mapping))
       (seq (:accounting/source-bindings mapping))
       (= (mapv :entry-index (:accounting/source-bindings mapping))
          (vec (range (count (:accounting/source-bindings mapping)))))
       (every? (fn [binding]
                 (and (integer? (:entry-index binding))
                      (root? (:entry/root binding))
                      (root? (:accounting/subject-root binding))
                      (root? (:claim-to/root binding))))
               (:accounting/source-bindings mapping))
       (= (:accounting-source-map/root mapping)
          (try (source-map-root mapping) (catch Exception _ nil)))))
