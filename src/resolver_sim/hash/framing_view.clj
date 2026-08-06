(ns resolver-sim.hash.framing-view
  "Make the consecutive-concatenation invariant of CANONICAL_HASH_SPEC_V1 §9
   visible and auditable.

   A stream built by concatenating canonical encodings is prefix-free: every
   value begins with a single-byte type tag and carries its own length/count
   prefix, so reading from offset 0 determines one and only one ordered
   sequence of components — with no gaps, overlaps, or alternative boundary
   choices.

   This namespace decodes a stream and returns *where every byte lives*:
   which component it belongs to, its nested semantic path, its role
   (`:tag` / `:len` / `:count` / `:payload`), and the canonical type of the
   value holding it.  It is the visible counterpart to the black-box property
   tests in concat_properties_test.clj.

   The decoder here is deliberately independent of the reference encoder in
   resolver-sim.hash.canonical (it mirrors the test decoder), so the round
   trip through `decompose-values` is not circular.

   Two complementary entry points:

   - `frame-stream` / `decompose-values` are the *explanatory* decoder: they
     annotate bytes for inspection and enforce resource limits, but they do
     not reject non-canonical-but-parseable input.
   - `verify-stream` / `verify-single` are the *fail-closed* validator: a
     stream may be mechanically parseable without being a valid canonical
     encoding, so they reject truncated tags/lengths/counts, unknown or
     reserved tags, non-minimal varints, declared lengths exceeding remaining
     bytes, collection counts inconsistent with their contents, noncanonical
     map ordering, duplicate canonical map keys, and non-canonical map key
     types.  The explanatory decoder is never the acceptance path."
  (:require [resolver-sim.hash.canonical :as hc])
  (:import [java.util Arrays]))

(def tag-names
  "Canonical type tag byte → human keyword."
  {0x00 :null
   0x01 :boolean
   0x02 :boolean
   0x10 :integer
   0x20 :string
   0x22 :keyword
   0x30 :vector
   0x31 :map})

(def role-names
  "Byte role keyword → single-character badge."
  {:tag "T" :len "L" :count "C" :payload "." :unknown "?"})

(def default-limits
  "Resource limits applied by the explanatory and validating decoders.
   Hostile framing data can use valid-looking lengths and counts to cause
   memory or CPU exhaustion; lengths are validated before allocation."
  {:max-stream-bytes       (* 1024 1024)
   :max-component-count    10000
   :max-payload-bytes      (* 1024 1024)
   :max-collection-depth   64
   :max-collection-members 100000})

(defn- byte-int
  "Unsigned byte value at offset."
  [^bytes ba i]
  (bit-and (int (aget ba i)) 0xFF))

(defn- read-varuint*
  "Decode a LEB128 varuint starting at pos, enforcing stream bounds.
   Returns {:value bigint :end long :minimal? bool} or nil when the varuint
   is truncated (no terminating byte within the stream)."
  [^bytes ba pos total]
  (loop [pos pos place (bigint 1) acc (bigint 0) bytes 0]
    (if (>= pos total)
      nil
      (let [b (byte-int ba pos)
            acc (+' acc (*' (bigint (bit-and b 0x7F)) place))]
        (if (zero? (bit-and b 0x80))
          {:value acc :end (long (inc pos)) :bytes (inc bytes)
           :minimal? (or (= 1 (inc bytes))
                         (not (zero? (bit-and b 0x7F))))}
          (recur (inc pos) (*' place 128) acc (inc bytes)))))))

(defn read-varuint
  "Public LEB128 varuint decode.  Returns [value next-pos] with the value kept
   arbitrary-precision and next-pos a long, or nil when truncated."
  [^bytes ba pos]
  (let [vu (read-varuint* ba pos (count ba))]
    (when vu [(:value vu) (:end vu)])))

(defn- zigzag-decode
  "Inverse of the encoder's ZigZag mapping: even→n/2, odd→-(n+1)/2."
  [n]
  (if (even? n)
    (quot n 2)
    (-' (quot (inc n) 2))))

(defn- sha256-hex
  "Lowercase hex SHA-256 of a byte array (payload commitment)."
  [^bytes bs]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        d (.digest md bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- byte-compare
  "Unsigned lexicographic byte-array comparison."
  [^bytes a ^bytes b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (if (= i n)
        (compare (count a) (count b))
        (let [x (byte-int a i) y (byte-int b i)]
          (if (= x y) (recur (inc i)) (compare x y)))))))

(defn- entry
  "Role annotation for one byte: its structural role, nested semantic path,
   the canonical type of the value holding it, and its collection slot."
  [path role type slot]
  {:role role :path path :type type :slot slot})

(defn- range-entries
  "Annotate every byte offset in [start end)."
  [start end path role type slot]
  (into {} (map (fn [o] [o (entry path role type slot)])) (range start end)))

(defn- slice
  [^bytes ba from to]
  (Arrays/copyOfRange ba (int from) (int to)))

(defn- throw-stream-issue
  [code offset role data]
  (throw (ex-info "canonical stream issue"
                  (merge {:type :stream-issue :code code :offset offset :role role}
                         data))))

(defn- throw-limit
  [reason limits offset role]
  (throw (ex-info "canonical decoder resource limit exceeded"
                  {:type :limit-exceeded :code :limit-exceeded
                   :reason reason :limit (get limits reason)
                   :offset offset :role role})))

(defn- minimality-issues
  [vu start role path]
  (if (:minimal? vu)
    []
    [{:code :non-minimal-varint :offset start :role role :path path}]))

(defn- decode-one*
  "Decode one canonical component starting at byte offset pos.

   Returns {:tag int, :tag-name keyword, :value v, :next first-byte-after,
            :prefix-end offset where the payload starts,
            :roles {offset {:role :path :type :slot}},
            :issues [{:code :offset :role :path ...}],
            :map-keys [ordered decoded keys] | nil,
            :map-key-bytes [hex canonical key bytes] | nil,
            :payload-hash hex sha256 of the payload bytes | nil}.

   Structural failures (truncation, unknown tags, declared lengths exceeding
   remaining bytes) and resource-limit failures throw structured ex-info."
  [^bytes ba pos total depth limits path slot]
  (when (>= pos total)
    (throw-stream-issue :truncated-tag pos :tag {}))
  (let [tag (byte-int ba pos)
        tag-pos pos
        pos (inc pos)
        type (tag-names tag)
        _ (when-not type
            (throw-stream-issue :unknown-tag tag-pos :tag {:tag tag}))]
    (case tag
      0x00 {:tag tag :tag-name type :value nil :next pos :prefix-end pos
            :roles {tag-pos (entry path :tag type slot)}
            :issues [] :map-keys nil :map-key-bytes nil :payload-hash nil}
      0x01 {:tag tag :tag-name type :value false :next pos :prefix-end pos
            :roles {tag-pos (entry path :tag type slot)}
            :issues [] :map-keys nil :map-key-bytes nil :payload-hash nil}
      0x02 {:tag tag :tag-name type :value true :next pos :prefix-end pos
            :roles {tag-pos (entry path :tag type slot)}
            :issues [] :map-keys nil :map-key-bytes nil :payload-hash nil}
      0x10 (let [vu (read-varuint* ba pos total)
                 _ (when-not vu (throw-stream-issue :truncated-length pos :len {}))
                 end (:end vu)]
             {:tag tag :tag-name type :value (zigzag-decode (:value vu))
              :next end :prefix-end pos
              :roles (merge {tag-pos (entry path :tag type slot)}
                            (range-entries pos end path :len type slot))
              :issues (minimality-issues vu pos :len path)
              :map-keys nil :map-key-bytes nil
              :payload-hash (sha256-hex (slice ba pos end))})
      0x20 (let [vu (read-varuint* ba pos total)
                 _ (when-not vu (throw-stream-issue :truncated-length pos :len {}))
                 len (long (:value vu))
                 end (:end vu)
                 payload-end (+ end len)]
             (when (> len (:max-payload-bytes limits))
               (throw-limit :max-payload-bytes limits pos :payload))
             (when (> payload-end total)
               (throw-stream-issue :length-exceeds-bytes end :payload
                                   {:declared len :available (- total end)}))
             (let [s (String. ba (int end) (int len) "UTF-8")]
               {:tag tag :tag-name type :value s :next payload-end
                :prefix-end end
                :roles (merge {tag-pos (entry path :tag type slot)}
                              (range-entries pos end path :len type slot)
                              (range-entries end payload-end path :payload type slot))
                :issues (minimality-issues vu pos :len path)
                :map-keys nil :map-key-bytes nil
                :payload-hash (sha256-hex (slice ba end payload-end))}))
      0x22 (let [vu (read-varuint* ba pos total)
                 _ (when-not vu (throw-stream-issue :truncated-length pos :len {}))
                 len (long (:value vu))
                 end (:end vu)
                 payload-end (+ end len)]
             (when (> len (:max-payload-bytes limits))
               (throw-limit :max-payload-bytes limits pos :payload))
             (when (> payload-end total)
               (throw-stream-issue :length-exceeds-bytes end :payload
                                   {:declared len :available (- total end)}))
             (let [s (String. ba (int end) (int len) "UTF-8")]
               {:tag tag :tag-name type :value (keyword s) :next payload-end
                :prefix-end end
                :roles (merge {tag-pos (entry path :tag type slot)}
                              (range-entries pos end path :len type slot)
                              (range-entries end payload-end path :payload type slot))
                :issues (minimality-issues vu pos :len path)
                :map-keys nil :map-key-bytes nil
                :payload-hash (sha256-hex (slice ba end payload-end))}))
      0x30 (let [vu (read-varuint* ba pos total)
                 _ (when-not vu (throw-stream-issue :truncated-count pos :count {}))
                 n (long (:value vu))
                 count-end (:end vu)
                 tag-role (entry path :tag type slot)
                 count-entries (range-entries pos count-end path :count type slot)]
             (when (> n (:max-collection-members limits))
               (throw-limit :max-collection-members limits pos :count))
             (when (> depth (:max-collection-depth limits))
               (throw-limit :max-collection-depth limits pos :collection))
             (let [{vals :value next :next child-roles :roles child-issues :issues}
                   (loop [i 0 pos count-end acc []
                          roles (merge {tag-pos tag-role} count-entries)
                          issues (minimality-issues vu pos :count path)]
                     (if (= i n)
                       {:value (vec acc) :next pos :roles roles :issues issues}
                       (let [{v :value next :next r :roles rs :issues}
                             (decode-one* ba pos total (inc depth) limits
                                          (conj path i) :element)]
                         (recur (inc i) next (conj acc v)
                                (merge roles r) (into issues rs)))))]
               {:tag tag :tag-name type :value vals :next next
                :prefix-end count-end :roles child-roles :issues child-issues
                :map-keys nil :map-key-bytes nil
                :payload-hash (sha256-hex (slice ba count-end next))}))
      0x31 (let [vu (read-varuint* ba pos total)
                 _ (when-not vu (throw-stream-issue :truncated-count pos :count {}))
                 n (long (:value vu))
                 count-end (:end vu)
                 tag-role (entry path :tag type slot)
                 count-entries (range-entries pos count-end path :count type slot)]
             (when (> n (:max-collection-members limits))
               (throw-limit :max-collection-members limits pos :count))
             (when (> depth (:max-collection-depth limits))
               (throw-limit :max-collection-depth limits pos :collection))
             (let [{m :value next :next child-roles :roles child-issues :issues
                    korder :keys kbytes :key-bytes}
                   (loop [i 0 pos count-end acc {}
                          roles (merge {tag-pos tag-role} count-entries)
                          issues (minimality-issues vu pos :count path)
                          prev nil keys [] key-bytes []]
                     (if (= i n)
                       {:value acc :next pos :roles roles :issues issues
                        :keys keys :key-bytes key-bytes}
                       (let [{k :value next1 :next k-roles :roles k-issues :issues}
                             (decode-one* ba pos total (inc depth) limits
                                          (conj path (* 2 i)) :map-key)
                             key-ok? (or (string? k) (keyword? k))
                             kb (hc/canonical-bytes k)
                             kb-hex (apply str (map #(format "%02x"
                                                             (bit-and % 0xff))
                                                    kb))
                             order-issue (cond
                                           (not key-ok?)
                                           [{:code :noncanonical-map-key-type
                                             :offset pos :role :map-key
                                             :path (conj path (* 2 i)) :key k}]
                                           (nil? prev)
                                           []
                                           (zero? (byte-compare prev kb))
                                           [{:code :duplicate-map-key
                                             :offset pos :role :map-key
                                             :path (conj path (* 2 i)) :key k}]
                                           (pos? (byte-compare prev kb))
                                           [{:code :noncanonical-map-order
                                             :offset pos :role :map-key
                                             :path (conj path (* 2 i)) :key k}]
                                           :else [])
                             {v :value next2 :next v-roles :roles v-issues :issues}
                             (decode-one* ba next1 total (inc depth) limits
                                          (conj path (inc (* 2 i))) :map-value)]
                         (recur (inc i) next2 (assoc acc k v)
                                (merge roles k-roles v-roles)
                                (into (into (into issues k-issues) order-issue) v-issues)
                                kb (conj keys k)
                                (conj key-bytes kb-hex)))))]
               {:tag tag :tag-name type :value m :next next
                :prefix-end count-end :roles child-roles :issues child-issues
                :map-keys korder :map-key-bytes kbytes
                :payload-hash (sha256-hex (slice ba count-end next))})))))

(defn decode-one
  "Decode one canonical component starting at byte offset pos (public wrapper).
   Returns {:tag :tag-name :value :next :prefix-end :roles :issues
            :map-keys :map-key-bytes :payload-hash}.  Throws structured
   ex-info on truncation, unknown tags, or resource-limit failures."
  [^bytes ba pos]
  (decode-one* ba pos (count ba) 0 default-limits [1] nil))

(defn concat-bytes
  "Consecutive byte-array concatenation."
  [bas]
  (let [total (reduce + (map count bas))
        out (byte-array total)]
    (loop [idx 0 bas bas]
      (when (seq bas)
        (System/arraycopy ^bytes (first bas) 0 out idx (count (first bas)))
        (recur (+ idx (count (first bas))) (rest bas))))
    out))

(defn frame-stream
  "Walk a concatenated canonical byte stream and return its ordered frames.

   Returns {:status :ok
            :frames [{:component int :offset int :next int :prefix-end int
                      :tag int :tag-name keyword :value v
                      :issues [...] :map-keys [...] :payload-hash hex} ...]
            :roles  {byte-offset {:role kw :path [int...] :type kw
                                  :slot kw :component int}}
            :total-bytes int}
   or {:status :limit-exceeded :reason kw :limit n :offset int :role kw}
   when a configured resource limit is exceeded.

   Options: {:limits {...}} merges over default-limits."
  [^bytes ba & [opts]]
  (let [limits (merge default-limits (:limits opts))
        total (count ba)]
    (if (> total (:max-stream-bytes limits))
      {:status :limit-exceeded :reason :max-stream-bytes
       :limit (:max-stream-bytes limits) :offset 0 :role :stream}
      (loop [pos 0 frames [] acc-roles {} i 1]
        (if (= pos total)
          {:status :ok :frames frames :roles acc-roles :total-bytes pos}
          (if (> i (:max-component-count limits))
            {:status :limit-exceeded :reason :max-component-count
             :limit (:max-component-count limits) :offset pos :role :component}
            (let [decoded (try (decode-one* ba pos total 0 limits [i] nil)
                               (catch clojure.lang.ExceptionInfo e
                                 (if (= :limit-exceeded (:type (ex-data e)))
                                   e
                                   (throw e))))]
              (if (instance? clojure.lang.ExceptionInfo decoded)
                (assoc (ex-data decoded) :status :limit-exceeded)
                (let [{:keys [value next tag tag-name roles issues map-keys
                              map-key-bytes payload-hash prefix-end]} decoded
                      annotated (into {}
                                      (map (fn [[o en]] [o (assoc en :component i)]))
                                      roles)]
                  (recur next
                         (conj frames {:component i :offset pos :next next
                                       :prefix-end prefix-end
                                       :tag tag :tag-name tag-name :value value
                                       :issues issues :map-keys map-keys
                                       :map-key-bytes map-key-bytes
                                       :payload-hash payload-hash})
                         (merge acc-roles annotated)
                         (inc i)))))))))))

(defn decompose-values
  "Encode each value canonically, concatenate the encodings, then frame the
   stream.

   Returns {:status :ok
            :values [v...]
            :component-bytes [byte-array...]
            :stream byte-array
            :frames [...] :roles {...} :total-bytes int}
   or the frame-stream {:status :limit-exceeded ...} result.

   `:stream` is exactly encode(v1) || encode(v2) || … || encode(vN).
   Options: {:limits {...}}."
  [values & [opts]]
  (let [component-bytes (mapv hc/canonical-bytes values)
        stream (concat-bytes component-bytes)
        {:keys [status] :as framed} (frame-stream stream opts)]
    (if (= :limit-exceeded status)
      framed
      (assoc framed :values (vec values)
             :component-bytes component-bytes :stream stream))))

(defn byte-table
  "Per-byte annotation vector, aligned with :stream bytes, for rendering.

   Each entry {:offset int :hex string :ascii char :role keyword
               :path [int...] :type keyword :slot keyword
               :component int :boundary? bool}.

   When :redact-payload? is true, payload bytes are masked ('··'); a per
   component payload commitment is still available via :payload-hash on the
   frame."
  [decomposed & [opts]]
  (let [{:keys [stream roles frames]} decomposed
        redact? (:redact-payload? opts)
        starts (into {} (map (fn [f] [(:offset f) true])) frames)]
    (mapv (fn [i]
            (let [b (byte-int stream i)
                  e (get roles i)
                  payload? (= :payload (:role e))
                  masked (and redact? payload?)]
              {:offset i
               :hex (if masked "··" (format "%02x" b))
               :ascii (if masked \·
                          (if (and (<= 33 b) (<= b 126)) (char b) \.))
               :role (or (:role e) :unknown)
               :path (or (:path e) [])
               :type (:type e)
               :slot (:slot e)
               :component (:component e)
               :boundary? (contains? starts i)}))
          (range (count stream)))))

(defn dump-lines
  "Render the framed stream as aligned text lines.

   One row per component: component index, offset span, tag, decoded value,
   then the hex of that component with a per-byte role badge underneath
   (T=tag, L=len, C=count, .=payload).  Component boundaries fall exactly
   between rows, which is the consecutive-concatenation invariant made
   visible.

   When :redact? is true, payload bytes are masked and decoded values are
   replaced by a per-component sha256 payload commitment."
  [decomposed & [opts]]
  (let [{:keys [frames roles stream]} decomposed
        redact? (:redact? opts)]
    (mapv
     (fn [f]
       (let [span (range (:offset f) (:next f))
             hex (mapv (fn [i]
                         (let [e (get roles i)
                               masked (and redact? (= :payload (:role e)))]
                           (if masked "··" (format "%02x" (byte-int stream i)))))
                       span)
             badge (mapv (fn [i] (get role-names (get-in roles [i :role]) "?")) span)
             label (if (and redact? (:payload-hash f))
                     (str "#<redacted payload sha256:" (:payload-hash f) ">")
                     (pr-str (:value f)))]
         (format "%s  %-9s  [%s]  %s"
                 (str "#" (:component f))
                 (name (:tag-name f))
                 (str (apply str (interpose " " hex))
                      " / " (apply str (interpose "  " badge)))
                 label)))
     frames)))

(defn verify-stream
  "Fail-closed validation of a canonical stream.

   A stream may be mechanically parseable without being a valid canonical
   encoding.  Structural failures abort the walk; canonicality violations are
   collected per component.

   Returns {:well-framed? bool        — every byte parses under known tags
            :fully-consumed? bool     — no trailing bytes after the last frame
            :canonical? bool          — well-framed AND fully consumed AND no
                                        canonicality issues
            :component-count int
            :consumed-bytes int
            :total-bytes int
            :issues [{:code ... :offset ... :role ... :reason ...}]}

   Fail-closed checks include: truncated tags/lengths/counts/payloads, unknown
   or reserved tags, overlong or non-minimal varints, declared lengths
   exceeding remaining bytes, collection counts inconsistent with their
   contents, noncanonical map ordering, duplicate canonical map keys,
   non-canonical map key types, and resource limits."
  [^bytes ba]
  (let [total (count ba)
        result (try (frame-stream ba)
                    (catch clojure.lang.ExceptionInfo e
                      (when (= :stream-issue (:type (ex-data e)))
                        {:stream-error (ex-data e)})))
        failed (or (:stream-error result)
                   (when (= :limit-exceeded (:status result)) result))
        well-framed? (nil? failed)
        component-count (if well-framed? (count (:frames result)) 0)
        structural (cond
                     (:stream-error result) [(:stream-error result)]
                     (= :limit-exceeded (:status result)) [result]
                     :else [])
        issues (vec (concat structural (mapcat :issues (:frames result))))
        consumed (cond
                   (:stream-error result) (:offset (:stream-error result))
                   (= :limit-exceeded (:status result)) (:offset result)
                   :else (:total-bytes result))]
    {:well-framed? well-framed?
     :fully-consumed? (and well-framed? (= consumed total))
     :canonical? (and well-framed? (= consumed total) (empty? issues))
     :component-count component-count
     :consumed-bytes consumed
     :total-bytes total
     :issues issues}))

(defn verify-single
  "Fail-closed validation that the stream is exactly one canonical value.
   Adds :single? (exactly one component) and redefines :canonical? for the
   single-object context so that a stream containing trailing bytes is never
   reported as a canonical single value."
  [^bytes ba]
  (let [r (verify-stream ba)
        single? (and (:well-framed? r) (= 1 (:component-count r)))]
    (assoc r :single? single?
           :canonical? (and (:canonical? r) single?))))

(defn verify-values
  "Encode a sequence of canonical values and verify the stream is canonical
   and fully consumed.  Returns the verify-stream result."
  [values]
  (verify-stream (concat-bytes (map hc/canonical-bytes values))))
