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
   the inference mechanism itself.

   Since the outcome-hardening work, a conclusion also commits which
   falsifiers remain untested (:conclusion/falsifiers, sharing the theorem
   falsifier vocabulary), and supporting theorem hashes are required to be
   well-formed and — when a resolver is supplied — verifiable (see
   verify-conclusion-support).

   :withdrawn is a terminal retraction, not a downgraded finding.  A withdrawn
   conclusion asserts no evidence: it must not carry :conclusion/scope or
   :conclusion/supporting-theorem-hashes (enforced at build and validate), and
   it is excluded from the collective evidence root (conclusion-collective-hash)
   and from reproduction claims.  A free-text retraction note is permitted via
   :conclusion/qualifications."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.benchmark.research-theorem-outcome :as rto]))

(def ^:const schema-version "research-conclusion.v1")

(def ^:const valid-conclusion-statuses
  "Controlled vocabulary for conclusion statuses."
  #{:established :qualified :tentative :contested :withdrawn})

(defn valid-conclusion-status?
  [s]
  (contains? valid-conclusion-statuses s))

(defn withdrawn?
  "True when the conclusion is a :withdrawn terminal retraction."
  [conclusion]
  (= :withdrawn (:conclusion/status conclusion)))

(defn- withdrawn-with-evidence-claims?
  "True when a :withdrawn conclusion carries scope, supporting-theorem, or
   falsifier references it must not assert (a retraction asserts no evidence)."
  [status scope supporting-theorem-hashes falsifiers]
  (and (= :withdrawn status)
       (or (seq (or scope {}))
           (seq (or supporting-theorem-hashes []))
           (seq (or falsifiers [])))))

(defn valid-falsifier?
  "True when f is a well-formed falsifier reference
   {:falsifier/id kw :status kw}, status in the theorem falsifier vocabulary
   (:observed | :not-observed | :untested)."
  [f]
  (and (map? f)
       (keyword? (:falsifier/id f))
       (contains? rto/valid-falsifier-statuses (:status f))))

(declare conclusion-overreaches?)

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
     conclusion/falsifiers    — [{:falsifier/id kw :status kw} ...]
                                (:observed | :not-observed | :untested)
     conclusion/supporting-theorem-hashes — [\"sha256:...\" ...]
     conclusion/hash          — pre-computed hash (rejected on mismatch)

   Returns the conclusion map with :conclusion/hash computed.

   Backward compatibility: :conclusion/falsifiers is bound in the preimage only
   when non-empty, so pre-existing artifacts (without the field) recompute
   unchanged."
  [{:keys [conclusion/id
           conclusion/premise
           conclusion/result
           conclusion/status
           conclusion/scope
           conclusion/qualifications
           conclusion/falsifiers
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
    (when (withdrawn-with-evidence-claims? status scope supporting-theorem-hashes falsifiers)
      (when (seq (or scope {}))
        (swap! errors conj ":withdrawn conclusions cannot carry :conclusion/scope"))
      (when (seq (or supporting-theorem-hashes []))
        (swap! errors conj ":withdrawn conclusions cannot carry :conclusion/supporting-theorem-hashes"))
      (when (seq (or falsifiers []))
        (swap! errors conj ":withdrawn conclusions cannot carry :conclusion/falsifiers")))
    (when (some? (some (fn [f] (not (valid-falsifier? f))) falsifiers))
      (swap! errors conj "invalid :conclusion/falsifiers entry"))
    (when (seq @errors)
      (throw (ex-info (str "Conclusion build failed: " (str/join "; " @errors))
                      {:errors @errors})))
    (let [base (cond-> {:schema-version schema-version
                        :conclusion/id id
                        :conclusion/premise premise
                        :conclusion/inference :therefore
                        :conclusion/result result
                        :conclusion/status (or status :established)
                        :conclusion/scope (or scope {})
                        :conclusion/qualifications (vec (or qualifications []))
                        :conclusion/supporting-theorem-hashes (vec (or supporting-theorem-hashes []))}
                 (seq falsifiers)
                 (assoc :conclusion/falsifiers (vec falsifiers)))
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
   Recomputes the conclusion hash and checks structural integrity, falsifier
   shape, supporting-theorem hash well-formedness, and — for :established
   conclusions — that the conclusion does not overreach (has qualifications or
   scope).

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
        (swap! errors conj (str "invalid :conclusion/status: " s)))
      (when (withdrawn-with-evidence-claims? s
                                             (:conclusion/scope conclusion)
                                             (:conclusion/supporting-theorem-hashes conclusion)
                                             (:conclusion/falsifiers conclusion))
        (when (seq (:conclusion/scope conclusion))
          (swap! errors conj ":withdrawn conclusions cannot carry :conclusion/scope"))
        (when (seq (:conclusion/supporting-theorem-hashes conclusion))
          (swap! errors conj ":withdrawn conclusions cannot carry :conclusion/supporting-theorem-hashes"))
        (when (seq (:conclusion/falsifiers conclusion))
          (swap! errors conj ":withdrawn conclusions cannot carry :conclusion/falsifiers"))))
    (when (some? (some (fn [f] (not (valid-falsifier? f)))
                       (:conclusion/falsifiers conclusion)))
      (swap! errors conj "invalid :conclusion/falsifiers entry"))
    (when (some? (some (fn [h] (not (hash-ref/valid-sha256-ref? h)))
                       (:conclusion/supporting-theorem-hashes conclusion)))
      (swap! errors conj "invalid :conclusion/supporting-theorem-hashes entry"))
    (when (conclusion-overreaches? conclusion)
      (swap! errors conj "conclusion overreaches: :established with no qualifications and no scope"))
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
   A :withdrawn conclusion is a terminal retraction and does not constitute
   evidence, so it is excluded from the collective root.  Used to produce the
   :conclusion-root in outcome-hashes.  For an all-active input the root is
   byte-identical to the unfiltered hash (the filter is a no-op)."
  [conclusions]
  (let [hashes (sort (map :conclusion/hash (remove withdrawn? conclusions)))]
    (str "sha256:"
         (hc/domain-hash :evidence-collection
                         {:type :conclusion-collection
                          :conclusion-hashes (vec hashes)}))))

(defn verify-conclusion-support
  "Verify every :conclusion/supporting-theorem-hash against a theorem resolver,
   applying the transitive commitment rule:
     1. the referenced artifact is content-addressed (well-formed sha256);
     2. a verifier recomputes its hash (the resolver must return a theorem whose
        own :theorem/hash recomputes to the claimed hash);
     3. it is resolved (present) — a missing theorem fails;
     4. it cannot be substituted without verification failure.

   theorem-resolver — fn (sha256-ref) -> theorem-map-or-nil. A resolver that
   recomputes theorem hashes (e.g. re-validating the theorem artifact) is the
   verifier half of the rule.

   Returns {:valid? bool :errors [str] :resolved-theorems [theorem-map]}."
  [conclusion theorem-resolver]
  (let [results (mapv (fn [h]
                        (cond
                          (not (hash-ref/valid-sha256-ref? h))
                          {:hash h :ok? false :reason "malformed theorem hash"}

                          (nil? (theorem-resolver h))
                          {:hash h :ok? false :reason "theorem not resolvable"}

                          (not= h (:theorem/hash (theorem-resolver h)))
                          {:hash h :ok? false :reason "theorem hash does not recompute"}

                          :else
                          {:hash h :ok? true :theorem (theorem-resolver h)}))
                      (:conclusion/supporting-theorem-hashes conclusion))
        failed (filter #(not (:ok? %)) results)]
    {:valid? (empty? failed)
     :errors (mapv (fn [r] (str (:reason r) ": " (:hash r))) failed)
     :resolved-theorems (mapv :theorem (filter :ok? results))}))
