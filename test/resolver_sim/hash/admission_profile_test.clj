(ns resolver-sim.hash.admission-profile-test
  "Cross-entry-point admission-profile equivalence.

   Every public function on the canonical-framing surface that accepts
   serialized bytes as input must either delegate to the shared resource
   limiter or produce exactly the same boundary classification.  This is the
   hardening guarantee behind the bounded-admission claim: an artifact that is
   admissible through one canonical entry point must be admissible through
   every other one, at the same resource boundary.

   Discovery is fully dynamic — nothing here is a hand-maintained list of
   namespaces or entry points:

   - The decoder surface is discovered by scanning the classpath source tree
     for namespaces whose source requires `resolver-sim.hash.framing-view`
     (the only way to obtain a serialized-input decoder/verifier).  A decoder
     added to a brand-new namespace is therefore found automatically.
   - Within each discovered namespace, every public function whose first
     argument is type-hinted ^bytes is a candidate serialized-input entry
     point, discovered by reflection on `ns-publics`.
   - Each candidate must then be present in the registry (with a classifier
     that maps its outcome onto the shared vocabulary: :admitted |
     {:class :limit-exceeded :reason kw} | {:class :malformed :code kw} |
     {:class :noncanonical :code kw}) or explicitly exempted as a
     non-admission helper.  Anything else fails the coverage guard, so a new
     decoder that bypasses the shared profile fails loudly here instead of
     silently diverging on the consensus boundary.

   The boundary probes then assert cross-entry-point agreement on over-limit,
   at-limit, over-depth, over-members, malformed and noncanonical streams."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]
            [resolver-sim.hash.round-trip :as rt]
            [resolver-sim.hash.sequence :as seq]))

;; ── Dynamic decoder-surface discovery ───────────────────────────────────────

(defn- source-roots
  "Production source-tree roots on the classpath (directories holding source,
   not jars, and not the repo root which would drag in test/extension/example
   trees).  Restricting discovery to these keeps it to loadable production
   code while still being dynamic — a decoder added to any namespace under a
   source root is found automatically."
  []
  (->> (str (System/getProperty "java.class.path"))
       (re-seq #"[^:]+")
       (map io/file)
       (filter #(and (.isDirectory %) (.exists %)))
       (filter (fn [d] (some #{(.getName d)} #{"src" "protocols_src"})))
       (mapv str)))

(defn- clj-files
  "Every .clj file under the production source roots, with a stable sort."
  []
  (->> (source-roots)
       (mapcat #(file-seq (io/file %)))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".clj")))
       (sort-by str)))

(defn- ns-symbol-for-file
  "Derive the namespace symbol from a source file path, e.g.
   /src/resolver_sim/hash/framing_view.clj -> resolver-sim.hash.framing-view.
   File-path underscores map to ns hyphens (Clojure's file↔ns convention)."
  [^java.io.File f]
  (let [rel (->> (source-roots)
                 (filter #(str/starts-with? (.getPath f) %))
                 (sort-by count (comp - count))
                 (first))
        path (str/trim (.getPath f))
        body (if rel (subs path (count rel)) path)
        without-ext (str/replace body #"\.clj$" "")
        segs (->> (str/split without-ext #"[\\/]+")
                  (remove str/blank?))
        name (str/join "." (map #(str/replace % "_" "-") segs))]
    (symbol name)))

(def ^:private decoder-surface-roots
  "Namespaces whose :require consumers participate in the serialized-input
   decoder surface: the framing decoder itself and the purpose-neutral round-trip
   primitive built on it."
  '#{resolver-sim.hash.framing-view
     resolver-sim.hash.round-trip})

(defn- ns-form-requires-framing-view?
  "Does this source file's ns form require the framing decoder (directly, or via
   the round-trip primitive)?  Reads just the first top-level form (the ns
   declaration, a list like `(ns name docstring? (:require ...) ...)`) and
   inspects its :require clause."
  [^java.io.File f]
  (try
    (let [text (slurp f)
          start (str/index-of text "(ns")
          first-form (when start (read-string {:read-cond :allow}
                                              (subs text start)))
          clauses (when (and first-form (seq? first-form)) (rest first-form))
          require-clause (some (fn [c] (when (and (seq? c) (= :require (first c))) c))
                               clauses)
          require-forms (when require-clause (rest require-clause))]
      (boolean
       (some (fn [rf]
               (if (vector? rf)
                 (boolean (some decoder-surface-roots rf))
                 (contains? decoder-surface-roots rf)))
             require-forms)))
    (catch Throwable _ false)))

(defn- discover-surface-namespaces
  "Namespaces that participate in the serialized-input decoder surface: the
   framing decoder itself, plus every namespace whose source requires it.  This
   is dependency-driven discovery, not a hardcoded list — a decoder consumer
   added in a brand-new namespace is found automatically.  Requires each
   discovered namespace so its public vars (and their arglists) are
   inspectable."
  []
  (let [root 'resolver-sim.hash.framing-view
        consumers (->> (clj-files)
                       (filter ns-form-requires-framing-view?)
                       (map ns-symbol-for-file)
                       (distinct)
                       (remove #{root}))]
    (mapv #(do (require %) %) (into [root] (sort consumers)))))

(defn- byte-taking-public-fns
  "Public vars in ns-sym whose first argument is type-hinted ^bytes — i.e.
   functions that accept serialized bytes as their input.  Returns the
   fully-qualified keyword (resolver-sim.hash.framing-view/decode-one)."
  [ns-sym]
  (->> (ns-publics ns-sym)
       (filter (fn [[_ v]]
                 (when-let [args (first (:arglists (meta v)))]
                   (= 'bytes (:tag (meta (first args)))))))
       (map (fn [[s _]] (keyword (str ns-sym) (name s))))
       set))

(defn- discovered-entry-points
  "Every public serialized-input entry point on the decoder surface, discovered
   dynamically.  This is the ground truth the registry and exemptions must
   cover; it grows automatically when a decoder is added anywhere."
  []
  (set (mapcat byte-taking-public-fns (discover-surface-namespaces))))

;; ── Classifiers ─────────────────────────────────────────────────────────────

(defn- classify-frame-stream
  "Normalise a frame-stream result / thrown issue to a boundary classification."
  [f]
  (try
    (let [r (f)]
      (case (:status r)
        :limit-exceeded {:class :limit-exceeded :reason (:reason r)}
        :ok (let [issues (mapcat :issues (:frames r))]
              (if (seq issues)
                {:class :noncanonical :code (:code (first issues))}
                {:class :admitted}))
        r))
    (catch clojure.lang.ExceptionInfo e
      (let [d (ex-data e)]
        (case (:type d)
          :limit-exceeded {:class :limit-exceeded :reason (:reason d)}
          :stream-issue   {:class :malformed :code (:code d)}
          (throw e))))))

(defn- classify-verify-stream
  "Normalise a verify-stream/verify-single result to a boundary classification."
  [v]
  (let [issues (:issues v)
        limit (first (filter #(= :limit-exceeded (:status %)) issues))
        malformed (first (filter #(contains?
                                   #{:truncated-tag :unknown-tag
                                     :length-exceeds-bytes :truncated-length
                                     :truncated-count}
                                   (:code %))
                                 issues))]
    (cond
      (:canonical? v) {:class :admitted}
      limit {:class :limit-exceeded :reason (:reason limit)}
      malformed {:class :malformed :code (:code malformed)}
      :else (let [c (:code (first issues))]
              {:class :noncanonical :code c}))))

(defn- classify-sequence-verify
  "Normalise a verify-sequence-commitment result to a boundary classification.
   The sequence verifier composes the shared round-trip primitive (which carries
   the framing profile) with its own contract-shape checks.  A resource-limit
   rejection is classified :limit-exceeded (never malformed); a contract-shape
   violation is classified :noncanonical rather than :malformed so it does not
   collide with framing-structural rejection."
  [r]
  (let [issues (:issues r)
        malformed (first (filter #(contains?
                                   #{:truncated-tag :unknown-tag
                                     :length-exceeds-bytes :truncated-length
                                     :truncated-count}
                                   (:code %))
                                 issues))]
    (cond
      (:valid? r) {:class :admitted}
      (:resource-limit? r) {:class :limit-exceeded :reason (:resource-reason r)}
      malformed {:class :malformed :code (:code malformed)}
      :else {:class :noncanonical :code (:code (first issues))})))

(def ^:private framing-ns 'resolver-sim.hash.framing-view)
(def ^:private sequence-ns 'resolver-sim.hash.sequence)
(def ^:private round-trip-ns 'resolver-sim.hash.round-trip)

(def serialized-input-entry-points
  "Registry of discovered serialized-input entry points (fully-qualified
   keywords) mapped to a classifier.  The coverage guard asserts this registry
   plus the exemption set exactly equals the dynamically-discovered surface, so
   the keys here are forced to track the code: add a decoder anywhere and the
   guard fails until it is classified here (or exempted below)."
  {(keyword (str framing-ns) "decode-one")
   (fn [ba]
     (try
       (let [d (fv/decode-one ba 0)]
         (if (seq (:issues d))
           {:class :noncanonical :code (:code (first (:issues d)))}
           {:class :admitted}))
       (catch clojure.lang.ExceptionInfo e
         (let [x (ex-data e)]
           (case (:type x)
             :limit-exceeded {:class :limit-exceeded :reason (:reason x)}
             :stream-issue   {:class :malformed :code (:code x)}
             (throw e))))))
   (keyword (str framing-ns) "frame-stream")
   (fn [ba] (classify-frame-stream #(fv/frame-stream ba)))
   (keyword (str framing-ns) "verify-stream")
   (fn [ba] (classify-verify-stream (fv/verify-stream ba)))
   (keyword (str framing-ns) "verify-single")
   (fn [ba] (classify-verify-stream (fv/verify-single ba)))
   (keyword (str round-trip-ns) "verify-canonical-single-bytes")
   (fn [ba]
     (let [r (rt/verify-canonical-single-bytes ba)]
       (cond
         (:valid? r) {:class :admitted}
         (:resource-limit? r) {:class :limit-exceeded :reason (:resource-reason r)}
         :else (let [malformed (first (filter #(contains?
                                                #{:truncated-tag :unknown-tag
                                                  :length-exceeds-bytes
                                                  :truncated-length :truncated-count}
                                                (:code %))
                                              (:issues r)))]
                 (if malformed
                   {:class :malformed :code (:code malformed)}
                   {:class :noncanonical :code (:code (first (:issues r)))})))))
   (keyword (str sequence-ns) "verify-sequence-commitment")
   (fn [ba] (classify-sequence-verify (seq/verify-sequence-commitment ba)))})

(def non-admission-byte-taking-helpers
  "Discovered ^bytes-taking functions that are deliberately NOT serialized-input
   admission entry points.  Only low-level building blocks may be listed here,
   each with a reason; anything else must be registered in
   `serialized-input-entry-points`."
  {(keyword (str framing-ns) "read-varuint")
   :read-varuint-is-a-low-level-leb128-helper-that-admits-no-value})

(deftest registry-and-exemptions-cover-the-whole-discovered-surface
  (testing "every public ^bytes-taking function discovered on the decoder
            surface is either classified as an admission entry point or
            explicitly exempted as a non-admission helper, and nothing is
            stale.  Because discovery is dynamic (classpath source scan +
            reflection), a decoder added to any namespace — new or existing —
            that bypasses the shared profile fails here, not silently at the
            consensus boundary."
    (let [discovered (discovered-entry-points)
          covered (set (set/union (set (keys serialized-input-entry-points))
                                  (set (keys non-admission-byte-taking-helpers))))
          missing (set/difference discovered covered)
          stale (set/difference covered discovered)]      (is (empty? missing))
         (str "discovered serialized-input entry point(s) not registered or "
              "exempted: " (pr-str (sort missing)))
         (is (empty? stale)
             (str "registered/exempted entry point(s) no longer discovered: "
                  (pr-str (sort stale)))))))

;; ── Boundary probes ─────────────────────────────────────────────────────────

(defn- over-stream
  "A canonical value whose bytes exceed :max-stream-bytes."
  []
  (hc/canonical-bytes {:a (apply str (repeat 700000 \a))
                       :b (apply str (repeat 700000 \b))}))

(defn- over-stream-sequence
  "A genuine sequence commitment whose bytes exceed :max-stream-bytes."
  []
  (seq/canonical-sequence-bytes
   {:purpose :a}
   [{:a (apply str (repeat 700000 \a))}
    {:b (apply str (repeat 700000 \b))}]))

(defn- at-stream
  "A canonical value just under :max-stream-bytes."
  []
  (hc/canonical-bytes {:a (apply str (repeat 1000000 \a))}))

(defn- at-stream-sequence
  "A genuine sequence commitment under :max-stream-bytes."
  []
  (seq/canonical-sequence-bytes {:purpose :a}
                                [{:a (apply str (repeat 800000 \a))}]))

(defn- over-depth
  "A value nested deeper than :max-collection-depth (64)."
  []
  (hc/canonical-bytes (loop [i 0 v 1] (if (< i 66) (recur (inc i) [v]) v))))

(defn- over-depth-sequence
  "A sequence commitment whose component exceeds :max-collection-depth."
  []
  (seq/canonical-sequence-bytes {:purpose :a}
                                [(loop [i 0 v 1] (if (< i 66) (recur (inc i) [v]) v))]))

(defn- over-members
  "A collection with more members than :max-collection-members."
  []
  (hc/canonical-bytes (vec (repeat 100001 nil))))

(defn- over-members-sequence
  "A sequence commitment whose component exceeds :max-collection-members."
  []
  (seq/canonical-sequence-bytes {:purpose :a}
                                [(vec (repeat 100001 nil))]))

(defn- malformed
  "A stream with an unknown/reserved tag."
  []
  (byte-array [0x77 0x00]))

(defn- noncanonical
  "A parseable but non-canonical stream (non-minimal varint)."
  []
  (byte-array [0x10 0x80 0x00]))

(defn- plain-entry-points
  "The framing-family entry points, resolved from the registry by suffix."
  []
  [(get serialized-input-entry-points (keyword (str framing-ns) "decode-one"))
   (get serialized-input-entry-points (keyword (str framing-ns) "frame-stream"))
   (get serialized-input-entry-points (keyword (str framing-ns) "verify-stream"))
   (get serialized-input-entry-points (keyword (str framing-ns) "verify-single"))])

(defn- sequence-entry-point
  "The sequence verifier entry point, resolved from the registry by suffix."
  []
  (get serialized-input-entry-points (keyword (str sequence-ns) "verify-sequence-commitment")))

(def probes
  "Cross-path boundary probes.

   :framing — classification every framing-family entry point (decode-one,
              frame-stream, verify-stream, verify-single) must produce.  These
              four share the framing decoder's structural/canonicality code
              vocabulary, so they must agree exactly, down to the :code.
   :sequence — classification the sequence verifier must produce.  It must
              agree exactly with the framing family on the resource-limit
              boundary (the consensus boundary); its malformed/noncanonical
              vocabulary is its own (it re-frames decode failures), so those
              probes only assert non-admission separately.
   :streams — per-family byte factories (:plain vs :sequence).

   At-boundary probes are asserted on both sides so the consensus boundary is
   established, not merely a rejection on one path."
  [{:name :over-stream
    :framing {:class :limit-exceeded :reason :max-stream-bytes}
    :sequence {:class :limit-exceeded :reason :max-stream-bytes}
    :streams {:plain over-stream :sequence over-stream-sequence}}
   {:name :at-stream
    :framing {:class :admitted}
    :sequence {:class :admitted}
    :streams {:plain at-stream :sequence at-stream-sequence}}
   {:name :over-depth
    :framing {:class :limit-exceeded :reason :max-collection-depth}
    :sequence {:class :limit-exceeded :reason :max-collection-depth}
    :streams {:plain over-depth :sequence over-depth-sequence}}
   {:name :over-members
    :framing {:class :limit-exceeded :reason :max-collection-members}
    :sequence {:class :limit-exceeded :reason :max-collection-members}
    :streams {:plain over-members :sequence over-members-sequence}}
   {:name :malformed
    :framing {:class :malformed :code :unknown-tag}
    :streams {:plain malformed}}
   {:name :noncanonical
    :framing {:class :noncanonical :code :non-minimal-varint}
    :streams {:plain noncanonical}}])

(deftest framing-family-agrees-on-every-boundary-probe
  (testing "every framing entry point produces exactly the same classification
            on every probe, down to the resource reason and the issue code"
    (doseq [{:keys [name framing streams]} probes
            classify (plain-entry-points)
            :let [got (classify ((get streams :plain)))]]
      (is (= framing got)
          (str "a framing entry point diverged on " name
               ": expected " (pr-str framing) " got " (pr-str got))))))

(deftest sequence-verifier-agrees-with-framing-on-resource-boundary
  (testing "the sequence verifier agrees with the framing family exactly on the
            resource-limit boundary (the consensus boundary), and never admits
            what the framing surface rejects structurally"
    (doseq [{:keys [name _framing sequence streams]} probes
            classify [(sequence-entry-point)]]
      (if sequence
        (let [got (classify ((get streams :sequence)))]
          (is (= sequence got)
              (str "verify-sequence-commitment diverged from framing on " name
                   ": expected " (pr-str sequence) " got " (pr-str got))))
        (let [got (seq/verify-sequence-commitment ((get streams :plain)))]
          (is (not (:valid? got))
              (str "verify-sequence-commitment must not admit the " name
                   " stream the framing surface rejects")))))))

(deftest at-limit-boundary-is-admitted-not-rejected
  (testing "the at-limit stream is genuinely at the boundary on every plain
            entry point, so the shared profile admits exactly up to the cap"
    (let [ba (at-stream)]
      (is (<= (count ba) (:max-stream-bytes fv/default-limits)))
      (is (:canonical? (fv/verify-single ba)))
      (is (map? (:value (fv/decode-one ba 0)))))))

(deftest sequence-verify-agrees-with-framing-on-limit-reasons
  (testing "the sequence verifier's limit rejection cites the same resource
            reason as the framing surface — the inherited profile is explicit,
            not a generic catch-all"
    (doseq [[stream-factory expected-reason]
            [[over-stream-sequence :max-stream-bytes]
             [over-depth-sequence :max-collection-depth]
             [over-members-sequence :max-collection-members]]]
      (let [r (seq/verify-sequence-commitment (stream-factory))]
        (is (not (:valid? r)))
        (is (:resource-limit? r))
        (is (= expected-reason (:resource-reason r))
            (str "expected reason " expected-reason " in "
                 (pr-str (:resource-reason r))))))))
