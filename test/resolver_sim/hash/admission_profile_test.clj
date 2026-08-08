(ns resolver-sim.hash.admission-profile-test
  "Cross-entry-point admission-profile equivalence.

   Every public function in the canonical-framing surface that accepts
   serialized bytes as input must either delegate to the shared resource
   limiter or produce exactly the same boundary classification.  This is the
   hardening guarantee behind the bounded-admission claim: an artifact that is
   admissible through one canonical entry point must be admissible through
   every other one, at the same resource boundary.

   Two complementary mechanisms:

   - An explicit registry of serialized-input entry points, each reduced to a
     canonical classification: :admitted | {:class :limit-exceeded :reason kw}
     | {:class :malformed :code kw} | {:class :noncanonical :code kw}.
   - A coverage guard that scans the public vars of the owning namespaces for
     any function whose first argument is type-hinted ^bytes, and fails if
     such a function is not present in the registry.  A future decoder added
     without routing through the shared profile therefore fails loudly here
     instead of silently diverging on the consensus boundary."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.framing-view :as fv]
            [resolver-sim.hash.sequence :as seq]))

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
   The sequence contract adds its own shape checks on top of the framing
   profile; a non-contract value is classified :noncanonical rather than
   :malformed so it does not collide with framing-structural rejection."
  [r]
  (let [errs (:errors r)]
    (cond
      (:valid? r) {:class :admitted}
      (some #(re-find #"inadmissible under the admission profile" %) errs)
      (let [reason (some #(second (re-find #"/ :([a-z-]+) \(limit" %)) errs)]
        {:class :limit-exceeded :reason (keyword reason)})
      (some #(re-find #"commitment decode failed" %) errs)
      {:class :malformed :code :decode-failed}
      :else {:class :noncanonical})))

(def serialized-input-entry-points
  "Registry of every public serialized-input entry point on the canonical
   framing surface.  Each value maps the entry point's outcome onto the shared
   boundary-classification vocabulary.  The coverage guard below asserts this
   registry is complete against the ^bytes-taking public functions of the
   owning namespaces."
  {:decode-one
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
   :frame-stream
   (fn [ba] (classify-frame-stream #(fv/frame-stream ba)))
   :verify-stream
   (fn [ba] (classify-verify-stream (fv/verify-stream ba)))
   :verify-single
   (fn [ba] (classify-verify-stream (fv/verify-single ba)))
   :verify-sequence-commitment
   (fn [ba] (classify-sequence-verify (seq/verify-sequence-commitment ba)))})

(def ^:private ^:const serialized-input-namespaces
  '[resolver-sim.hash.framing-view resolver-sim.hash.sequence])

(defn- byte-taking-public-fns
  "Public vars in ns-sym whose first argument is type-hinted ^bytes — i.e.
   functions that accept serialized bytes as their input.  Returns keywords."
  [ns-sym]
  (->> (ns-publics ns-sym)
       (filter (fn [[_ v]]
                 (when-let [args (first (:arglists (meta v)))]
                   (= 'bytes (:tag (meta (first args)))))))
       (map (comp keyword name key))
       set))

(def ^:private ^:const non-admission-byte-taking-helpers
  "Public ^bytes-taking functions on the framing surface that are NOT
   serialized-input admission entry points, and so are deliberately absent
   from `serialized-input-entry-points`.
   - :read-varuint — a low-level LEB128 helper that returns [value next-pos]
     for one varint; it cannot admit a value, apply no resource profile, and is
     used only as a building block by the bounded decoder.  Adding any NEW
     byte-taking public function here requires an explicit reason; anything
     not listed must be registered in the registry."
  #{:read-varuint})

(deftest registry-covers-every-byte-taking-public-entry-point
  (testing "every public ^bytes-taking function on the framing surface is
            either registered as an admission entry point or explicitly
            exempted as a non-admission helper — a new decoder added without
            routing through the shared profile fails here, not silently at the
            consensus boundary"
    (let [registered (set (keys serialized-input-entry-points))
          found (set (mapcat byte-taking-public-fns serialized-input-namespaces))
          missing (set/difference found (set/union registered non-admission-byte-taking-helpers))
          stale (set/difference (set/union registered non-admission-byte-taking-helpers) found)]
      (is (empty? missing)
          (str "serialized-input entry point(s) missing from the registry "
               "and not exempted as non-admission helpers: "
               (pr-str (sort missing))))
      (is (empty? stale)
          (str "registry/exemption names no longer byte-taking: "
               (pr-str (sort stale)))))))

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

(def entry-families
  "Which entry points consume the :plain stream family vs the :sequence family."
  {:plain #{:decode-one :frame-stream :verify-stream :verify-single}
   :sequence #{:verify-sequence-commitment}})

(deftest framing-family-agrees-on-every-boundary-probe
  (testing "every framing entry point produces exactly the same classification
            on every probe, down to the resource reason and the issue code"
    (doseq [{:keys [name framing streams]} probes
            ep (:plain entry-families)
            :let [classify (get serialized-input-entry-points ep)
                  got (classify ((get streams :plain)))]]
      (is (= framing got)
          (str "entry point " ep " diverged on " name
               ": expected " (pr-str framing) " got " (pr-str got))))))

(deftest sequence-verifier-agrees-with-framing-on-resource-boundary
  (testing "the sequence verifier agrees with the framing family exactly on the
            resource-limit boundary (the consensus boundary), and never admits
            what the framing surface rejects structurally"
    (doseq [{:keys [name framing sequence streams]} probes]
      (if sequence
        (let [classify (get serialized-input-entry-points :verify-sequence-commitment)
              got (classify ((get streams :sequence)))]
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
        (is (some #(re-find (re-pattern (str "\\b" (name expected-reason) "\\b")) %)
                  (:errors r))
            (str "expected reason " expected-reason " in "
                 (pr-str (:errors r))))))))
