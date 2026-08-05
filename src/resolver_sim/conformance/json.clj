(ns resolver-sim.conformance.json
  "Strict JSON input guard (CR-004, G9c resource safety).

   clojure.data.json resolves duplicate object keys to the last value, silently
   changing a canonical preimage.  Every conformance verifier therefore rejects
   duplicate keys.  This scanner walks the raw text and returns the first
   duplicate key it finds (or nil); the caller then rejects the bundle with a
   typed :duplicate-json-key result instead of guessing.")

(defn- unescape-key
  "Best-effort unescape of a JSON string body for key comparison."
  [^String s]
  (loop [i 0 out (StringBuilder.)]
    (if (>= i (count s))
      (str out)
      (let [ch (.charAt s i)]
        (if (= ch \\)
          (let [esc (.charAt s (inc i))
                next-i (if (= esc \u)
                         (let [hex (subs s (+ i 2) (min (+ i 6) (count s)))]
                           (.append out (char (Integer/parseInt hex 16)))
                           (+ i 6))
                         (do (.append out (case esc \n \newline \t \tab \r \return \b \backspace \f \formfeed esc))
                             (+ i 2)))]
            (recur next-i out))
          (recur (inc i) (.append out ch)))))))

(def ^:private duplicate-key-message "conformance.duplicate-json-key")

(defn- key-end
  "Index just past the closing quote of the string beginning at i (s[i] == \\\")."
  [^String s i]
  (loop [j (inc i)]
    (let [ch (.charAt s j)]
      (cond
        (= ch \\) (recur (+ j 2))
        (= ch \") (inc j)
        :else (recur (inc j))))))

(def max-nesting-depth 64)

(defn nesting-too-deep?
  "True when the JSON contains object/array nesting deeper than the resource
   limit.  Mirrors the JS verifier's :nesting-too-deep limit."
  [^String s]
  (let [n (count s)]
    (letfn [(ws [i] (loop [i i] (if (and (< i n) (Character/isWhitespace (.charAt s i))) (recur (inc i)) i)))
            (num-end [i] (loop [i i] (if (and (< i n)
                                              (not (Character/isWhitespace (.charAt s i)))
                                              (not (contains? #{\, \} \]} (.charAt s i))))
                                       (recur (inc i)) i)))
            (value-end [i depth]
              (let [ch (.charAt s i)]
                (cond
                  (= ch \{) (object-end (inc i) (inc depth))
                  (= ch \[) (array-end (inc i) (inc depth))
                  (= ch \") (key-end s i)
                  (or (Character/isDigit ch) (= ch \-)) (num-end (inc i))
                  (.startsWith s "true" i) (+ i 4)
                  (.startsWith s "false" i) (+ i 5)
                  (.startsWith s "null" i) (+ i 4)
                  :else (throw (ex-info "malformed" {})))))
            (object-end [i depth]
              (if (> depth max-nesting-depth)
                (throw (ex-info "nesting-too-deep" {}))
                (loop [i (ws i)]
                  (let [ch (if (< i n) (.charAt s i) \space)]
                    (cond
                      (= ch \}) (inc i)
                      (= ch \") (let [k-end (key-end s i)
                                      colon (ws k-end)]
                                  (value-end (ws (inc colon)) depth))
                      :else (recur (inc i)))))))
            (array-end [i depth]
              (if (> depth max-nesting-depth)
                (throw (ex-info "nesting-too-deep" {}))
                (loop [i (ws i)]
                  (if (and (< i n) (not= \] (.charAt s i)))
                    (value-end (ws i) depth)
                    (inc i)))))]
      (try
        (value-end (ws 0) 0)
        false
        (catch clojure.lang.ExceptionInfo e
          (if (= "nesting-too-deep" (.getMessage e)) true false))))))

(defn duplicate-json-key
  "Return the first duplicate object key in the JSON text, or nil.  Malformed
   JSON reports nil (the caller's parser decides)."
  [^String s]
  (let [n (count s)]
    (letfn [(ws [i] (loop [i i] (if (and (< i n) (Character/isWhitespace (.charAt s i))) (recur (inc i)) i)))
            (num-end [i] (loop [i i] (if (and (< i n)
                                              (not (Character/isWhitespace (.charAt s i)))
                                              (not (contains? #{\, \} \]} (.charAt s i))))
                                       (recur (inc i)) i)))
            (value-end [i]
              (let [ch (.charAt s i)]
                (cond
                  (= ch \{) (object-end (inc i))
                  (= ch \[) (array-end (inc i))
                  (= ch \") (key-end s i)
                  (or (Character/isDigit ch) (= ch \-)) (num-end (inc i))
                  (.startsWith s "true" i) (+ i 4)
                  (.startsWith s "false" i) (+ i 5)
                  (.startsWith s "null" i) (+ i 4)
                  :else (throw (ex-info "malformed" {})))))
            (object-end [i]
              (loop [i (ws i) seen #{}]
                (let [ch (if (< i n) (.charAt s i) \space)]
                  (cond
                    (= ch \}) (inc i)
                    (= ch \") (let [k-end (key-end s i)
                                    key (unescape-key (subs s (inc i) (dec k-end)))
                                    colon (ws k-end)]
                                (if (contains? seen key)
                                  (throw (ex-info duplicate-key-message {:key key}))
                                  (let [v-end (value-end (ws (inc colon)))
                                        nxt (ws v-end)
                                        after-comma (if (and (< nxt n) (= \, (.charAt s nxt))) (ws (inc nxt)) nxt)]
                                    (recur after-comma (conj seen key)))))
                    :else (throw (ex-info "malformed" {}))))))
            (array-end [i]
              (loop [i (ws i)]
                (if (and (< i n) (not= \] (.charAt s i)))
                  (let [v (value-end (ws i))
                        nxt (ws v)
                        after-comma (if (and (< nxt n) (= \, (.charAt s nxt))) (ws (inc nxt)) nxt)]
                    (recur after-comma))
                  (inc i))))]
      (try
        (value-end (ws 0))
        nil
        (catch clojure.lang.ExceptionInfo e
          (if (= duplicate-key-message (.getMessage e)) (:key (ex-data e)) nil))))))
