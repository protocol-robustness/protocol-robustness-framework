(ns resolver-sim.io.fixtures
  "Fixture loading and fixture-reference resolution for scenarios.
   Owns the mapping from fixture keywords to file paths and the
   resolution of :protocol-params-ref into effective protocol-params.

   Extracted from sim/fixtures.clj to keep io-level code from
   depending on simulation-level namespaces.

   The fixture reference system provides:
     - A `resolve-protocol-params-ref` function that loads a fixture
       file by keyword and merges it with inline scenario params
     - SHA-256 content hashing for reproducibility
     - Validation helpers for fixture ref integrity"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [resolver-sim.io.resource-path :as rp]
            [resolver-sim.util.deep-merge :as dm])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Fixture key → file path mapping
;; ---------------------------------------------------------------------------

(defn fixture-key->path
  "Convert a fixture keyword to a relative file path.
   Examples:
     :protocol/kleros    → data/fixtures/protocol/kleros.edn
     :traces/s18-kleros   → data/fixtures/traces/s18-kleros.trace.json
   Bare keywords (no namespace) go to data/fixtures/<name>.edn.
   Singular fixture namespaces are mapped to plural directory names
   so that identity keys (e.g. :state/id) resolve correctly."
  [k]
  (let [ns  (namespace k)
        nm  (name k)
        ext (if (= ns "traces") ".trace.json" ".edn")
        dir (cond (= ns "state") "states"
                  (= ns "actor") "actors"
                  (= ns "token") "tokens"
                  (= ns "threshold") "thresholds"
                  (= ns "suite") "suites"
                  :else ns)]
    (if dir
      (str "data/fixtures/" dir "/" nm ext)
      (str "data/fixtures/" nm ext))))

(def allowed-fixture-namespaces
  "Set of valid fixture namespace strings.
   Both singular and plural forms are included to support
   fixture-ref keywords (plural, matching directory names) and
   identity-key namespaces (singular, used in fixture data files).
   Used by valid-fixture-ref? and fixture-ref? (sim layer).
   Add new fixture types here — both layers reference this single source."
  #{"protocol" "state" "states" "actor" "actors" "authority"
    "token" "tokens" "threshold" "thresholds" "suites" "traces"})

(defn valid-fixture-ref?
  "True when k is a fixture reference keyword with a known namespace."
  [k]
  (boolean (and (keyword? k) (namespace k)
                (contains? allowed-fixture-namespaces (namespace k)))))

(defn fixture-exists?
  "True when the fixture file for keyword k exists on disk or classpath.
   Checks both resource:// and filesystem paths.
   Returns false for invalid refs and missing files — never nil."
  [k]
  (boolean (when (valid-fixture-ref? k)
             (let [path          (fixture-key->path k)
                   resource-path (str "resource:" path)]
               (or (rp/path-exists? resource-path)
                   (.exists (io/file path)))))))

(defn valid-fixture-reference?
  "Full validity check: known namespace AND file exists.
   Returns true/false — does not throw.
   Use for pre-flight validation before suite execution."
  [k]
  (boolean (and (valid-fixture-ref? k) (fixture-exists? k))))

;; ---------------------------------------------------------------------------
;; Fixture file loading
;; ---------------------------------------------------------------------------

(defn load-fixture
  "Load a fixture by keyword. Tries classpath resource first, then filesystem.
   Supports EDN (.edn) and JSON (.json / .trace.json) formats.
   Returns the parsed Clojure data."
  [k]
  (let [path          (fixture-key->path k)
        resource-path (str "resource:" path)]
    (if (rp/path-exists? resource-path)
      (let [content (rp/slurp-path resource-path)]
        (if (.endsWith path ".json")
          (json/read-str content :key-fn keyword)
          (edn/read-string content)))
      (let [f (io/file path)]
        (if (.exists f)
          (with-open [r (io/reader f)]
            (if (.endsWith path ".json")
              (json/read r :key-fn keyword)
              (edn/read (java.io.PushbackReader. r))))
          (throw (ex-info "Fixture not found"
                          {:key k :path path :resource-path resource-path})))))))

;; ---------------------------------------------------------------------------
;; Fixture reference normalization
;; ---------------------------------------------------------------------------

(defn normalize-fixture-ref
  "Accept :protocol/kleros or \"protocol/kleros\", always return keyword.
   For JSON scenarios the ref comes as a string; for EDN it can be a keyword."
  [ref]
  (cond
    (keyword? ref) ref
    (string? ref)  (keyword ref)
    :else (throw (ex-info "Invalid fixture ref — must be keyword or string"
                          {:ref ref :type (type ref)}))))

;; ---------------------------------------------------------------------------
;; Content hashing
;; ---------------------------------------------------------------------------

(defn fixture-content-hash
  "SHA-256 hex digest of fixture file content.
   Used for evidence provenance — lets reviewers verify which fixture
   version was used even after the file changes.
   Returns nil if the file cannot be read."
  [path]
  (try
    (let [content (rp/slurp-path (str "resource:" path))
          digest  (MessageDigest/getInstance "SHA-256")]
      (.update digest (.getBytes content "UTF-8"))
      (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest))))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; Fixture reference resolution
;; ---------------------------------------------------------------------------

(defn resolve-protocol-params-ref
  "Resolve :protocol-params-ref in a scenario map.

   If the scenario has a :protocol-params-ref key, loads the referenced
   fixture, deep-merges with any inline :protocol-params, and replaces
   the scenario's :protocol-params with the merged result.

   Merge semantics:
     - Fixture values provide defaults
     - Inline scenario params override fixture values (deliberate perturbation)
     - Nested maps are merged recursively

   The resolved scenario gains:
     - :protocol-params — the effective merged params
     - :fixture-refs — provenance data for evidence capture
   The :protocol-params-ref key is removed.

   Returns the updated scenario map unchanged if no ref is present."
  [scenario]
  (if-let [ref (:protocol-params-ref scenario)]
    (let [fixture-ref   (normalize-fixture-ref ref)
          _             (when-not (valid-fixture-ref? fixture-ref)
                          (throw (ex-info "Invalid fixture ref namespace"
                                          {:ref fixture-ref
                                           :allowed allowed-fixture-namespaces
                                           :scenario-id (:scenario-id scenario)})))
          fixture-path  (fixture-key->path fixture-ref)
          fixture       (load-fixture fixture-ref)
          inline        (:protocol-params scenario {})
          effective     (dm/deep-merge fixture inline)
          ref-entry     {:slot :protocol-params
                         :ref  fixture-ref
                         :path fixture-path
                         :content-hash (fixture-content-hash fixture-path)}]
      (-> scenario
          (assoc :protocol-params effective)
          (assoc :effective-protocol-params effective)
          (update :fixture-refs (fnil conj []) ref-entry)
          (dissoc :protocol-params-ref)))
    scenario))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-fixture-ref
  "Validate a resolved fixture reference map. Returns nil or throws."
  [ref-entry]
  (when (map? ref-entry)
    (let [{:keys [slot ref path content-hash]} ref-entry]
      (when-not slot
        (throw (ex-info "Fixture ref missing :slot" {:ref-entry ref-entry})))
      (when-not ref
        (throw (ex-info "Fixture ref missing :ref" {:ref-entry ref-entry})))
      (when (and content-hash (not= 64 (count content-hash)))
        (throw (ex-info "Fixture ref content-hash unexpected length"
                        {:ref-entry ref-entry :content-hash content-hash}))))))

;; ---------------------------------------------------------------------------
;; Suite Discovery & Key-based entry points
;; ---------------------------------------------------------------------------

(defn list-suites
  "Read the suite registry and return a map of suite-key → metadata.
   Tries classpath resource first, then filesystem."
  []
  (let [manifest (or (try (rp/edn-read "resource:data/fixtures/suites/manifest.edn")
                          (catch Exception _ nil))
                     (edn/read-string (slurp "data/fixtures/suites/manifest.edn")))]
    (reduce-kv (fn [m k v]
                 (let [suite-file (:file v)
                       suite-data (or (try (rp/edn-read (str "resource:data/fixtures/suites/" suite-file))
                                           (catch Exception _ nil))
                                      (edn/read-string (slurp (str "data/fixtures/suites/" suite-file))))]
                   (assoc m k (select-keys suite-data [:suite/id :suite/title :suite/purpose
                                                       :suite/class :suite/criticality
                                                       :suite/prevents]))))
               {}
               manifest)))

(defn- fixture-ref? [x]
  (and (keyword? x) (namespace x)
       (contains? allowed-fixture-namespaces (namespace x))))

(defn run-suite-from-key
  "Load a fixture suite by keyword, compose it, resolve protocol-params,
   and delegate to the simulation-layer run-suite.
   Matches the legacy sim.fixtures/run-suite API for callers in the IO/shell layer."
  [suite-key mode protocol opts]
  (let [compose-suite (requiring-resolve 'resolver-sim.sim.fixtures/compose-suite)
        run-suite (requiring-resolve 'resolver-sim.sim.fixtures/run-suite)
        compose-loader (fn [k] (compose-suite k load-fixture))
        raw-fixture (load-fixture suite-key)
        suite (compose-loader raw-fixture)
        traces (mapv (fn [entry]
                       (if (and (map? entry) (contains? entry :trace))
                         (let [trace-ref (:trace entry)
                               trace (-> (compose-loader trace-ref)
                                         resolve-protocol-params-ref)]
                           {:trace trace
                            :expected-outcome (:expected-outcome entry)
                            :expected-halt-reason (:expected-halt-reason entry)})
                         (let [trace (-> (if (fixture-ref? entry)
                                           (compose-loader entry)
                                           entry)
                                         resolve-protocol-params-ref)]
                           {:trace trace})))
                     (:traces suite []))]
    (run-suite suite traces mode protocol opts)))

(defn minimise-suite-from-key
  "Load a fixture suite by keyword and delegate to the simulation-layer minimiser."
  [suite-key target-invariant protocol]
  (let [compose-suite (requiring-resolve 'resolver-sim.sim.fixtures/compose-suite)
        minimise-suite (requiring-resolve 'resolver-sim.sim.fixtures/minimise-suite)
        compose-loader (fn [k] (compose-suite k load-fixture))
        suite (compose-loader (load-fixture suite-key))]
    (minimise-suite suite target-invariant protocol)))
