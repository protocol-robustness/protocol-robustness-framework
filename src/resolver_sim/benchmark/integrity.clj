(ns resolver-sim.benchmark.integrity
  "Shared evidence-bundle integrity helpers: tolerant reading, bundle-root
   hash recomputation, and fail-closed verification.

   This namespace is the single source of truth for the :bundle-root
   commitment contract used by the runner (writer boundary), the CLI
   (bb benchmark:verify), and the report renderer (build-report).

   Contract: a committed :evidence/hash must recompute from the persisted
   artifact. Callers that derive authoritative conclusions from bundle
   fields (metrics, results, claim-results) MUST verify the bundle first
   via verify-evidence-bundle! — a supplied bundle confers no authority
   until its committed identity recomputes."
  (:require [resolver-sim.hash.canonical :as hc]
            [clojure.edn :as edn]))

(defn- sort-maps-for-hash
  "Recursively sort map keys for deterministic hashing. Used by verify-bundle-hash
   to match the write-time ordering."
  [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (sort-maps-for-hash v)]) x))
    (coll? x) (into (empty x) (map sort-maps-for-hash x))
    :else x))

(def ^:private object-tag-reader
  "LEGACY RUNTIME-VALUE COMPATIBILITY ONLY — not a permanent serialization design.

   Reads #object[...] tagged literals emitted by pr-str for non-portable
   Clojure objects (e.g. yield-module fns, java.time.Instant). Clojure function
   objects are not portable data: a reader can turn the textual form into an
   opaque placeholder vector, but it cannot reconstruct the original function
   or establish its identity. This reader exists so legacy bundles written
   before the writer-boundary migration remain readable for reproduction and
   export.

   Durable fix is at the WRITER boundary: evidence should serialize a stable
   yield-module identifier/version/config, not the runtime fn value. Until
   then, opaque object representations must not be relied upon in any field
   that influences admission or assurance. Note the committed :evidence/hash
   (:bundle-root) already normalizes runtime fns to a deterministic {:type :fn}
   marker via project-world-to-structure-view, so it is unaffected by this tag.

   Returns an unmistakable legacy sentinel map (never the raw vector), so it
   cannot accidentally satisfy domain code that expects sequential data and so
   admission/validation logic can categorically detect and reject it:
   {:legacy/runtime-object true
    :legacy/class <class-name>
    :legacy/printed-representation <pr-str output>}"
  (fn [[class-sym _hex printed :as v]]
    (if (vector? v)
      {:legacy/runtime-object true
       :legacy/class (when class-sym (str class-sym))
       :legacy/printed-representation printed}
      {:legacy/runtime-object true
       :legacy/printed-representation (str v)})))

(defn legacy-object?
  "True if x is a legacy sentinel emitted by object-tag-reader, i.e. the
   residue of a non-portable #object[...] runtime value read from a legacy
   evidence bundle. Such values must be categorically excluded from new
   evidence admission."
  [x]
  (and (map? x) (= true (:legacy/runtime-object x))))

(defn read-evidence-bundle
  "Reads an evidence bundle, tolerating the #object tagged literals that
   pr-str emits for non-portable Clojure objects (e.g. yield-module fns,
   java.time.Instant). These values are not round-trippable; verify/report
   only read the surrounding canonical map, so each tagged literal is kept as
   an inert legacy sentinel map (see legacy-object?)."
  [path]
  (edn/read-string {:readers {'object object-tag-reader}}
                   (slurp path)))

(defn hashable-evidence
  "Return the portion of an evidence bundle covered by the :bundle-root
  commitment. Post-hash/signature fields (:timestamp, :evidence/hash,
  :evidence/signature, :evidence/public-key-path) and operational
  materialization fields (:benchmark/artifact-index, :repo,
  :run/manifest/:manifest/at, :results/:scenario/artifacts) are excluded so the
  committed hash survives signing and location/timing drift.

  :creation/provenance is also EXCLUDED: creation provenance (in-band vs
  out-of-band) is committed in the outer envelope (canonical-integrity.v1),
  not in the semantic bundle root. This ensures that provenance variation
  never alters semantic identity.

  :evidence/commitment-version is deliberately NOT excluded: when present it is
  committed into the hash, binding the bundle to the commitment scheme used to
  interpret it — an evidence commitment binds both the evidence and the
  commitment semantics. Version-less historical bundles contain no such field,
  so their hash is unchanged."
  [bundle]
  ;; Execution directories, artifact-index locations, and wall-clock run
  ;; metadata describe one materialization of a package. Detached artifact
  ;; manifests in :results commit the actual canonical bytes; these operational
  ;; locations must not make the semantic benchmark root depend on chunking,
  ;; staging location, or run timing.
  ;; :repo metadata (commit, dirty?, lockfiles, remotes) is operational VCS
  ;; state captured at run start; it changes when artifacts from a prior run
  ;; are present in the working copy. VCS identity is captured semantically
  ;; via source-hash in runner-finalization, so :repo is excluded from the
  ;; bundle-root commitment to ensure serial == serial == parallel
  ;; reproducibility. The :repo map is still persisted in the evidence file
  ;; for audit.
  (-> bundle
       (dissoc :timestamp :evidence/hash :evidence/signature
               :evidence/public-key-path
               :benchmark/artifact-index :repo :creation/provenance)
      (cond-> (contains? bundle :run/manifest)
        (update :run/manifest #(dissoc % :manifest/at))
        (contains? bundle :results)
        (update :results #(mapv (fn [result]
                                  (dissoc result :scenario/artifacts))
                                %)))))
(defn commitment-version
  "Resolve the single commitment scheme a bundle declares, by its
  :evidence/commitment-version field.

  Current scheme  = hash over hashable-evidence as-is (includes :run/manifest
                    and :benchmark-certification).
  Legacy-v1 scheme = hash over hashable-evidence with :run/manifest and
                    :benchmark-certification removed (pre-v2 bundles).

  A declared version selects ONE scheme. A version-less bundle defaults to
  :current (the historical primary scheme). There is never a fallback: the
  rule is one artifact version -> one unambiguous commitment rule. An unknown
  version is :unsupported so the verifier fails closed instead of guessing."
  [bundle]
  (let [v (:evidence/commitment-version bundle)]
    (cond
      (= v "bundle-root.v1") :legacy-v1
      (or (= v "bundle-root.v2") (= v :current)) :current
      (nil? v) :current
      :else :unsupported)))

(defn- scheme-hash
  "Compute the bundle-root commitment hash for exactly one scheme, over
  hashable-evidence (which already excludes post-hash fields incl.
  :evidence/commitment-version). Mirrors the writer's projection so the
  recomputation is exact."
  [bundle scheme]
  (let [base (into (sorted-map) (hashable-evidence bundle))]
    (case scheme
      :current (hc/hash-with-intent {:hash/intent :bundle-root} base)
      :legacy-v1 (hc/hash-with-intent {:hash/intent :bundle-root}
                                      (into (sorted-map) (dissoc (hashable-evidence bundle)
                                                                 :run/manifest
                                                                 :benchmark-certification))))))

(defn verify-bundle-hash
  "Fail-closed integrity check: recompute the committed :evidence/hash against
  exactly the scheme its declared :evidence/commitment-version selects.

  Unlike the prior implementation (which opportunistically attempted the
  current scheme and then the legacy-v1 scheme and accepted whichever matched),
  this selects ONE scheme from the bundle's declared version and verifies
  against that only. version-less bundles default to :current. A mismatch or
  unsupported version fails closed — no cross-scheme fallback.

  Returns {:hash-ok? bool :scheme kw :computed-hash str :reason kw}."
  [bundle]
  (let [stored-hash (:evidence/hash bundle)
        scheme (commitment-version bundle)
        computed (when-not (= scheme :unsupported)
                   (scheme-hash bundle scheme))]
    (cond
      (= scheme :unsupported)
      {:hash-ok? false :scheme nil
       :computed-hash (scheme-hash bundle :current)
       :reason :unsupported-commitment-version
       :declared-version (:evidence/commitment-version bundle)}

      (nil? stored-hash)
      {:hash-ok? false :scheme scheme
       :computed-hash computed
       :reason :missing-evidence-hash}

      (some? computed)
      (let [hash-ok? (hc/intent-hash= computed stored-hash)]
        {:hash-ok? hash-ok?
         :scheme (when hash-ok? scheme)
         :computed-hash computed
         :reason (if hash-ok? :ok :computed-hash-mismatch)})

      :else
      {:hash-ok? false :scheme scheme
       :computed-hash computed
       :reason :unsupported-commitment-version})))

(defn verify-evidence-bundle!
  "Fail-closed integrity gate for any consumer that treats bundle fields as
  authoritative. Throws when the committed :evidence/hash is absent, declares
  an unsupported commitment version, or does not recompute from the persisted
  bundle content; returns the bundle when verification succeeds."
  [bundle]
  (let [{:keys [hash-ok? scheme computed-hash reason declared-version]} (verify-bundle-hash bundle)
        stored-hash (:evidence/hash bundle)]
    (when-not hash-ok?
      (throw (ex-info "Evidence bundle integrity check failed"
                      {:integrity/failure :bundle-root-mismatch
                       :reason (or reason
                                   (if (nil? stored-hash)
                                     :missing-evidence-hash
                                     :computed-hash-mismatch))
                       :stored-hash stored-hash
                       :computed-hash computed-hash
                       :scheme scheme
                       :declared-version declared-version})))
    bundle))
