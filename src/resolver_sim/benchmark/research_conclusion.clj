(ns resolver-sim.benchmark.research-conclusion
  "Research conclusion: a concise \"X, therefore Y\" canonical artifact.

   Each conclusion commits to:
     - what was established (X)
     - the inference step (therefore)
     - the result (Y)
     - what was NOT concluded (qualifications)
     - which falsifiers remain untested
     - supporting theorem hashes

   Conclusions must not overreach: a finding that no profitable
   request-splitting strategy was observed within the committed parameter
   domain should NOT be presented as universal strategy-proofness.

   The inference is always :therefore — conclusions are validated by
   the bridge from established premises to bounded results, not by
   the inference mechanism itself."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "research-conclusion.v1")

(def ^:const valid-conclusion-statuses
  "Controlled vocabulary for conclusion statuses."
  #{:established :qualified :tentative :contested :withdrawn})

(defn valid-conclusion-status?
  [s]
  (contains? valid-conclusion-statuses s))

(defn build-conclusion
  "Build a canonical research conclusion artifact.

   Required:
     conclusion/id            — qualified keyword
     conclusion/premise       — {:x \"what was established\"}
     conclusion/result        — {:y \"what follows\"}

   Optional:
     conclusion/status        — :established (default) | :qualified | :tentative | ...
     conclusion/scope         — {:cases n :parameter-domain-root sha256 ...}
     conclusion/qualifications — [\"what was not concluded\" ...]
     conclusion/supporting-theorem-hashes — [\"sha256:...\" ...]
     conclusion/hash          — pre-computed hash (rejected on mismatch)

   Returns the conclusion map with :conclusion/hash computed."
  [{:keys [conclusion/id
           conclusion/premise
           conclusion/result
           conclusion/status
           conclusion/scope
           conclusion/qualifications
           conclusion/supporting-theorem-hashes
           conclusion/hash]}]
  (let [errors (atom [])]
    (when-not (some? id)
      (swap! errors conj "missing :conclusion/id"))
    (when (and (some? id) (not (keyword? id)))
      (swap! errors conj ":conclusion/id must be a keyword"))
    (when-not (some? premise)
      (swap! errors conj "missing :conclusion/premise"))
    (when-not (some? result)
      (swap! errors conj "missing :conclusion/result"))
    (when (and (some? status) (not (valid-conclusion-status? status)))
      (swap! errors conj (str "invalid :conclusion/status: " status)))
    (when (seq @errors)
      (throw (ex-info (str "Conclusion build failed: " (str/join "; " @errors))
                      {:errors @errors})))
    (let [base {:schema-version schema-version
                :conclusion/id id
                :conclusion/premise premise
                :conclusion/inference :therefore
                :conclusion/result result
                :conclusion/status (or status :established)
                :conclusion/scope (or scope {})
                :conclusion/qualifications (vec (or qualifications []))
                :conclusion/supporting-theorem-hashes (vec (or supporting-theorem-hashes []))}
          computed-hash (str "sha256:"
                             (hc/domain-hash :research-conclusion base))]
      (when (and (some? hash) (not= hash computed-hash))
        (throw (ex-info "Declared conclusion/hash does not match computed value"
                        {:declared hash :computed computed-hash})))
      (assoc base :conclusion/hash computed-hash))))

(defn conclusion-hash
  "Return the content-addressed hash of a conclusion."
  [conclusion]
  (:conclusion/hash conclusion))

(defn conclusion-valid?
  "Structural validity check for a research conclusion."
  [conclusion]
  (and (= schema-version (:schema-version conclusion))
       (some? (:conclusion/id conclusion))
       (some? (:conclusion/hash conclusion))
       (some? (:conclusion/premise conclusion))
       (some? (:conclusion/result conclusion))))

(defn validate-conclusion
  "Standalone validator for a loaded research conclusion.
   Recomputes the conclusion hash and checks structural integrity.

   Returns {:valid? bool :errors [string]}."
  [conclusion]
  (let [errors (atom [])]
    (when-not (= schema-version (:schema-version conclusion))
      (swap! errors conj (str "expected schema-version " schema-version
                              " got " (:schema-version conclusion))))
    (when-not (some? (:conclusion/id conclusion))
      (swap! errors conj "missing :conclusion/id"))
    (when-not (some? (:conclusion/premise conclusion))
      (swap! errors conj "missing :conclusion/premise"))
    (when-not (some? (:conclusion/result conclusion))
      (swap! errors conj "missing :conclusion/result"))
    (let [s (:conclusion/status conclusion)]
      (when-not (valid-conclusion-status? s)
        (swap! errors conj (str "invalid :conclusion/status: " s))))
    (when (some? (:conclusion/hash conclusion))
      (let [without-hash (dissoc conclusion :conclusion/hash)
            computed (str "sha256:" (hc/domain-hash :research-conclusion without-hash))]
        (when-not (= computed (:conclusion/hash conclusion))
          (swap! errors conj (str "conclusion/hash mismatch: declared "
                                  (:conclusion/hash conclusion)
                                  " computed " computed)))))
    {:valid? (empty? @errors) :errors @errors}))

(defn conclusion-overreaches?
  "True when the conclusion lacks qualifications despite known limitations.
   Returns false when qualifications are present or when the conclusion
   explicitly limits its scope."
  [conclusion]
  (and (empty? (:conclusion/qualifications conclusion))
       (empty? (:conclusion/scope conclusion))
       (= :established (:conclusion/status conclusion))))

(defn conclusion-collective-hash
  "Compute the collective hash for a set of conclusions.
   Used to produce the :conclusion-root in outcome-hashes."
  [conclusions]
  (let [hashes (sort (map :conclusion/hash conclusions))]
    (str "sha256:"
         (hc/domain-hash :evidence-collection
                         {:type :conclusion-collection
                          :conclusion-hashes (vec hashes)}))))
