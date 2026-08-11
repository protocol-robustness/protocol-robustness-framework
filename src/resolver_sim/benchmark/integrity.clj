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
   commitment. Post-hash fields (signature, key path) are excluded so the
   committed hash survives signing."
  [bundle]
  (dissoc bundle :timestamp :evidence/hash :evidence/signature
          :evidence/public-key-path))

(defn verify-bundle-hash
  "Verify current bundles and pre-v2 bundles whose hash excluded post-hash
   run metadata and certification."
  [bundle]
  (let [stored-hash (:evidence/hash bundle)
        current-hash (hc/hash-with-intent {:hash/intent :bundle-root}
                                          (hashable-evidence bundle))
        legacy-hash (hc/hash-with-intent {:hash/intent :bundle-root}
                                         (dissoc (hashable-evidence bundle)
                                                 :run/manifest
                                                 :benchmark-certification))]
    (cond
      (hc/intent-hash= current-hash stored-hash)
      {:hash-ok? true :scheme :current :computed-hash current-hash}

      (hc/intent-hash= legacy-hash stored-hash)
      {:hash-ok? true :scheme :legacy-v1 :computed-hash legacy-hash}

      :else
      {:hash-ok? false :scheme nil :computed-hash current-hash})))

(defn verify-evidence-bundle!
  "Fail-closed integrity gate for any consumer that treats bundle fields as
   authoritative. Throws when the committed :evidence/hash is absent or does
   not recompute from the persisted bundle content; returns the bundle when
   verification succeeds."
  [bundle]
  (let [{:keys [hash-ok? scheme computed-hash]} (verify-bundle-hash bundle)
        stored-hash (:evidence/hash bundle)]
    (when-not hash-ok?
      (throw (ex-info "Evidence bundle integrity check failed"
                      {:integrity/failure :bundle-root-mismatch
                       :reason (if (nil? stored-hash)
                                 :missing-evidence-hash
                                 :computed-hash-mismatch)
                       :stored-hash stored-hash
                       :computed-hash computed-hash
                       :scheme scheme})))
    bundle))
