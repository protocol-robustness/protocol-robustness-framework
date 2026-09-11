(ns resolver-sim.compare.canonical
  "Canonical value and artifact comparison.

   Two values are canonically equivalent when their canonical byte encodings
   (CANONICAL_HASH_SPEC_V1 / the Binary Encoding ABI) are identical.  Because
   canonical encodings are type-tagged and length-prefixed, this equivalence
   is stricter than Clojure's = in the useful direction: :active and \"active\"
   never compare equal, while 1 and 1N (which encode identically) do.

   The namespace also provides a first-divergence diagnostic for non-equal
   values and file-level comparison for EDN and JSON artifacts.

   Usage:
     (canonical-equivalent? a b)   ; boolean
     (compare-values a b)          ; structured comparison
     (compare-files \"a.edn\" \"b.edn\")  ; file-level comparison"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]))

(def ^:const schema-version "canonical-comparison.v1")

(defn- bytes->hex
  "Lowercase hex encoding of a byte array (byte-order preserving, so
   lexicographic hex order equals unsigned lexicographic byte order)."
  [^bytes ba]
  (apply str (map (fn [b] (format "%02x" (bit-and (int b) 0xFF))) ba)))

(defn canonical-equivalent?
  "True when a and b produce byte-identical canonical encodings."
  [a b]
  (java.util.Arrays/equals ^bytes (hc/canonical-bytes a)
                           ^bytes (hc/canonical-bytes b)))

(defn content-hash
  "Domain-separated canonical content hash (EVIDENCE_RECORD_V1) of a value.
   Two canonically equivalent values always produce equal content hashes."
  [v]
  (hc/domain-hash v))

(defn canonical-key-order
  "Order a sequence of map keys by their full canonical byte encodings,
   matching the canonical map-ordering rule (CANONICAL_HASH_SPEC_V1 §6.8)."
  [ks]
  (sort-by #(bytes->hex (hc/canonical-bytes %)) ks))

(defn first-divergence
  "Walk two canonical-safe values in canonical order and return the first
   structural divergence, or nil when they are canonically equivalent.

   Returns {:path <key-or-index path>
            :kind <:type | :value | :map-key-missing-left
                   | :map-key-missing-right | :vector-length>
            :left <value or count>
            :right <value or count>}."
  [a b]
  (letfn [(walk [path a b]
            (cond
              (and (map? a) (map? b))
              (let [ks (canonical-key-order (distinct (concat (keys a) (keys b))))]
                (loop [ks ks]
                  (when-let [k (first ks)]
                    (let [in-a? (contains? a k)
                          in-b? (contains? b k)]
                      (cond
                        (and in-a? in-b?)
                        (or (walk (conj path k) (get a k) (get b k))
                            (recur (rest ks)))

                        in-a?  {:path (conj path k)
                                :kind :map-key-missing-right
                                :left (get a k) :right :missing}

                        :else  {:path (conj path k)
                                :kind :map-key-missing-left
                                :left :missing :right (get b k)})))))
              ;; NOTE: ISeq/List values are normalized by the encoder to
              ;; arrays; compare them element-wise like vectors.
              (and (sequential? a) (sequential? b) (not (map? a)) (not (map? b)))
              (if (not= (count a) (count b))
                {:path path :kind :vector-length :left (count a) :right (count b)}
                (loop [i 0]
                  (when (< i (count a))
                    (or (walk (conj path i) (nth a i) (nth b i))
                        (recur (inc i))))))

              (= a b)
              nil

              :else
              (let [same-type? (or (= (class a) (class b))
                                   (and (integer? a) (integer? b)))]
                {:path path
                 :kind (if same-type? :value :type)
                 :left a :right b})))]
    (walk [] a b)))

(defn compare-values
  "Canonical comparison of two values.

   Returns {:schema-version \"canonical-comparison.v1\"
            :equivalent? <bool>
            :left-hash <hex> :right-hash <hex>
            :divergence <map or nil>}."
  [a b]
  (let [equivalent? (canonical-equivalent? a b)]
    {:schema-version schema-version
     :equivalent? equivalent?
     :left-hash (content-hash a)
     :right-hash (content-hash b)
     :divergence (when-not equivalent? (first-divergence a b))}))

(defn parse-file
  "Parse an artifact file into a canonical-safe value.

   Format is resolved from the explicit :format option (:edn or :json) or
   inferred from the file extension. JSON is read with keyword keys so both
   sides of a comparison are normalized identically."
  [path & [{:keys [format]}]]
  (let [f (io/file path)]
    (when-not (.isFile f)
      (throw (ex-info "file not found" {:path path})))
    (let [idx (str/last-index-of path ".")
          ext (when (and idx (pos? idx) (< idx (dec (count path))))
                (str/lower-case (subs path (inc idx))))
          fmt (or format
                  (case ext
                    ("edn" "clj" "cljc") :edn
                    "json" :json
                    (throw (ex-info "cannot infer format; use --format edn|json"
                                    {:path path :extension ext}))))]
      (case fmt
        :edn  (edn/read-string (slurp f))
        :json (json/read-str (slurp f) :key-fn keyword)))))

(defn compare-files
  "Canonical comparison of two artifact files.

   Accepts optional {:format :edn | :json}. Returns the compare-values
   structure plus :left-file and :right-file."
  [path-a path-b & [{:keys [format]}]]
  (let [value-a (parse-file path-a {:format format})
        value-b (parse-file path-b {:format format})
        result  (compare-values value-a value-b)]
    (assoc result :left-file path-a :right-file path-b)))
