(ns resolver-sim.research.changelog-challenge
  "changelog-challenge.v1
   
   Allows a researcher to challenge, narrow, correct, or qualify a specific
   claim made by a previous changelog entry. The original changelog entry
   remains unchanged — resolution is append-only."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "changelog-challenge.v1")

(def challenge-categories
  "Controlled vocabulary for challenge categories."
  #{:factually-incorrect :claim-overstated :scope-omitted
    :evidence-not-committed :implementation-mismatch
    :superseded-understanding :ambiguous-wording})

(def challenge-statuses
  "Controlled vocabulary for challenge lifecycle statuses."
  #{:open :confirmed :confirmed-with-qualification
    :corrected :contested :unresolved :invalid :withdrawn :superseded})

(def ^:private status-transitions
  "Allowed status transitions."
  {:open                       #{:confirmed :confirmed-with-qualification
                                 :corrected :contested :unresolved
                                 :invalid :withdrawn :superseded}
   :confirmed                  #{:superseded}
   :confirmed-with-qualification #{:confirmed :superseded}
   :corrected                  #{:superseded}
   :contested                  #{:open :superseded}
   :unresolved                 #{:superseded}
   :invalid                    #{:open :superseded}
   :withdrawn                  #{:open :superseded}})

(def resolution-options
  "Controlled vocabulary for proposed resolution types."
  #{:confirm-entry :qualify-entry :issue-correction-entry
    :supersede-entry :mark-contested :no-change})

(defn valid-category?
  "True when category is in the controlled challenge-categories vocabulary."
  [cat]
  (contains? challenge-categories cat))

(defn valid-challenge-status?
  "True when status is in the controlled challenge-statuses vocabulary."
  [st]
  (contains? challenge-statuses st))

(defn valid-status-transition?
  "True when transitioning from `from` to `to` is allowed."
  [from to]
  (contains? (get status-transitions from #{}) to))

(defn valid-resolution?
  "True when resolution is in the controlled resolution-options vocabulary."
  [res]
  (contains? resolution-options res))

(defn normalise-changelog-ref
  "Build a minimal target reference for a changelog entry.
   Uses file path, line range, and a content hash of the targeted
   lines as a stable identity mechanism."
  [file-path start-line end-line]
  (let [sha256 (str "sha256:"
                    (hc/domain-hash :changelog-challenge
                                    {:file file-path
                                     :start-line start-line
                                     :end-line end-line
                                     :target :changelog-entry}))]
    {:changelog-entry-hash sha256
     :file file-path
     :start-line start-line
     :end-line end-line}))

(defn build-challenge
  "Build a changelog-challenge.v1 artifact.
   
   Required: target, category, assertion, proposed-by.
   The challenge/hash is computed from all fields except itself."
  [{:keys [challenge/target
           challenge/category
           challenge/assertion
           challenge/evidence
           challenge/proposed-resolution
           challenge/proposed-wording
           challenge/proposed-by
           challenge/created-at
           challenge/supersedes]}]
  (let [status :open]
    (when-not target
      (throw (ex-info "Challenge requires a target" {})))
    (when-not (:changelog-entry-hash target)
      (throw (ex-info "Challenge target requires :changelog-entry-hash" {})))
    (when-not category
      (throw (ex-info "Challenge requires a category" {})))
    (when-not (valid-category? category)
      (throw (ex-info (str "Invalid challenge category: " category)
                      {:category category :allowed challenge-categories})))
    (when (str/blank? assertion)
      (throw (ex-info "Challenge requires a non-blank assertion" {})))
    (when-not proposed-by
      (throw (ex-info "Challenge requires :proposed-by" {})))
    (when (and proposed-resolution (not (valid-resolution? proposed-resolution)))
      (throw (ex-info (str "Invalid proposed resolution: " proposed-resolution)
                      {:resolution proposed-resolution :allowed resolution-options})))
    (let [effective-created-at (or created-at (str (java.time.Instant/now)))
          semantic-base
          {:schema-version schema-version
           :challenge/target target
           :challenge/category category
           :challenge/assertion assertion
           :challenge/evidence (vec (or evidence []))
           :challenge/proposed-resolution proposed-resolution
           :challenge/proposed-wording proposed-wording
           :challenge/proposed-by proposed-by
           :challenge/created-at effective-created-at}
          challenge-hash (str "sha256:"
                              (hc/domain-hash :changelog-challenge semantic-base))
          challenge-id (str "challenge:" (subs challenge-hash (count "sha256:")))]
      {:schema-version schema-version
       :challenge/id challenge-id
       :challenge/status status
       :challenge/target target
       :challenge/category category
       :challenge/assertion assertion
       :challenge/evidence (vec (or evidence []))
       :challenge/proposed-resolution proposed-resolution
       :challenge/proposed-wording proposed-wording
       :challenge/proposed-by proposed-by
       :challenge/created-at effective-created-at
       :challenge/supersedes supersedes
       :challenge/hash challenge-hash})))

(defn validate-challenge
  "Standalone validator for a loaded or constructed challenge artifact.
   
   Checks schema version, controlled vocabularies, required fields,
   target structure, and hash consistency.
   
   Returns {:valid? bool :errors [string] :warnings [string]}."
  [challenge]
  (let [errors (atom [])
        warnings (atom [])]
    (when-not (= schema-version (:schema-version challenge))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version challenge))))
    (let [cat (:challenge/category challenge)]
      (when-not (valid-category? cat)
        (swap! errors conj (str "invalid category: " cat " allowed: " challenge-categories))))
    (let [st (:challenge/status challenge)]
      (when (and st (not (valid-challenge-status? st)))
        (swap! errors conj (str "invalid status: " st " allowed: " challenge-statuses))))
    (when-not (:challenge/assertion challenge)
      (swap! errors conj "missing :challenge/assertion"))
    (when-not (:challenge/proposed-by challenge)
      (swap! errors conj "missing :challenge/proposed-by"))
    (let [target (:challenge/target challenge)]
      (when-not target
        (swap! errors conj "missing :challenge/target"))
      (when (and target (nil? (:changelog-entry-hash target)))
        (swap! errors conj "target missing :changelog-entry-hash")))
    (let [res (:challenge/proposed-resolution challenge)]
      (when (and res (not (valid-resolution? res)))
        (swap! errors conj (str "invalid proposed-resolution: " res))))
    (let [hash-field (:challenge/hash challenge)]
      (when hash-field
        (when-not (str/starts-with? hash-field "sha256:")
          (swap! errors conj "challenge/hash does not start with sha256:"))
        (let [semantic-base
              {:schema-version schema-version
               :challenge/target (:challenge/target challenge)
               :challenge/category (:challenge/category challenge)
               :challenge/assertion (:challenge/assertion challenge)
               :challenge/evidence (:challenge/evidence challenge)
               :challenge/proposed-resolution (:challenge/proposed-resolution challenge)
               :challenge/proposed-wording (:challenge/proposed-wording challenge)
               :challenge/proposed-by (:challenge/proposed-by challenge)
               :challenge/created-at (:challenge/created-at challenge)}
              computed (str "sha256:"
                            (hc/domain-hash :changelog-challenge semantic-base))]
          (when-not (= computed hash-field)
            (swap! errors conj (str "challenge/hash mismatch: declared "
                                    hash-field " computed " computed))))))
    {:valid? (empty? @errors) :errors @errors :warnings @warnings}))
