(ns scripts.scenarios.check-direct-writes
  "Static CI rule: flag direct writes to :total-held, :held/positions,
   and :resolver-stakes outside an explicit allowlist.

   These keys must only be mutated through canonical accounting functions.
   Direct assoc-in/update-in/assoc/update bypasses the invariant layer.

   Exit 0 when clean, 1 on violation.

   Allowlist format:
     #{'namespace/fn-name ...}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private protected-keys
  "State keys that must not be written to directly."
  [":total-held" ":held/positions" ":resolver-stakes"])

(def ^:private write-keywords
  "Clojure core forms that perform direct state mutation."
  ["assoc-in" "update-in" "assoc" "update"])

(def ^:private allowlist
  "Namespace-qualified fully qualified function names that are allowed
   to write to the protected keys.  Each entry is a string like
   'namespace/fn-name' matching the canonical ns/fn form."
  '#{resolver-sim.protocols.sew.accounting/update-ledger-index
     resolver-sim.assurance.custody/replay-held-adjustment-state
     resolver-sim.protocols.sew.registry/register-stake
     resolver-sim.protocols.sew.registry/withdraw-stake
     resolver-sim.protocols.sew.registry/slash-resolver-stake
     resolver-sim.protocols.sew.resolution/reverse-reversal-slash-on-vindication
     resolver-sim.protocols.sew.lifecycle/cancel-disputed-escrow-now
     resolver-sim.yield.modules.adversarial/adversarial-accrue})

(defn- read-ns-form
  [file]
  (let [content (slurp file)
        rdr (java.io.PushbackReader. (java.io.StringReader. content))]
    (loop []
      (let [form (try (read rdr) (catch Exception _ nil))]
        (if form
          (if (and (list? form) (= 'ns (first form)))
            form
            (recur))
          nil)))))

(defn- declared-ns
  [ns-form]
  (name (second ns-form)))

(defn- fn-name
  [form]
  (when (and (list? form)
             (#{'defn 'defn- 'defmethod} (first form)))
    (name (second form))))

(defn- extract-fn-names
  [content]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. content))]
    (loop [names #{}]
      (let [form (try (read rdr false ::eof) (catch Exception _ ::eof))]
        (if (= ::eof form)
          names
          (let [fname (fn-name form)]
            (recur (if fname (conj names fname) names))))))))

(defn- top-level-defn-positions
  [lines]
  (let [pat #"^\s*\(defn[-\s]+\S+"
        positions (atom {})]
    (doseq [[idx line] (map-indexed vector lines)
            :let [m (re-find pat line)]
            :when m
            :let [name (second (re-find #"\(defn[-\s]+(\S+)" line))]]
      (swap! positions assoc (inc idx) name))
    @positions))

(defn- has-write-form?
  [line]
  (let [lower-line (str/lower-case line)]
    (and (>= (.indexOf lower-line "(") 0)
     (some (fn [write-kw]
                 (let [idx (.indexOf lower-line write-kw)]
                   (when (>= idx 0)
                     (let [wc (+ idx (count write-kw))
                           post-char (when (< wc (count lower-line)) (nth lower-line wc))]
                       (and (or (nil? post-char)
                                (not (Character/isLetterOrDigit ^char post-char)))
                            (let [pre-char (when (> idx 0) (nth lower-line (dec idx)))]
                              (and (or (nil? pre-char)
                                       (Character/isWhitespace ^char pre-char)
                                       (= (int \() (int pre-char))
                                       (contains? #{\> \|} pre-char))
                                   (some (fn [key-str]
                                           (let [ki (.indexOf lower-line key-str idx)]
                                             (and (>= ki 0)
                                                  (<= ki (+ idx (count write-kw) 20)))))
                                         protected-keys)))))))
               ;; fn_W body (let_I) ends above — close fn_W before some_W's second arg
               )
               write-keywords))))

(defn- write-violation?
  [line]
  (and (not (str/starts-with? (str/triml line) ";"))
       (has-write-form? line)))

(defn- enclosing-fn
  "Find the closest preceding defn/defn-/defmethod name before line-number."
  [line-number defn-positions]
  (->> (sort (keys defn-positions))
       (filter #(<= % line-number))
       last
       (get defn-positions)))

(defn- read-top-level-forms
  "Read source as Clojure forms so multiline writes cannot evade the check."
  [file]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. (slurp file)))]
    (loop [forms []]
      (let [form (try (read rdr false ::eof) (catch Exception _ ::eof))]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn- write-call?
  [form]
  (and (list? form)
       (symbol? (first form))
       (contains? (set write-keywords) (name (first form)))
       (some #(contains? #{:total-held :held/positions :resolver-stakes} %)
             (tree-seq coll? seq form))))

(defn- direct-write-calls
  [form]
  (filter write-call? (tree-seq coll? seq form)))

(defn- check-file
  [file]
  (let [forms (read-top-level-forms file)
        ns-form (first (filter #(and (list? %) (= 'ns (first %))) forms))
        ns-str (when ns-form (declared-ns ns-form))]
    (if-not ns-str
      []
      (vec
       (mapcat
        (fn [form]
          (when-let [fn-name (fn-name form)]
            (let [qualified (symbol (str ns-str "/" fn-name))]
              (when-not (contains? allowlist qualified)
                (for [call (direct-write-calls form)]
                  {:file (.getPath file)
                   :ns ns-str
                   :fn fn-name
                   :line 0
                   :text (pr-str call)})))))
        forms)))))

(defn- source-files
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (filter #(.endsWith (.getName ^java.io.File %) ".clj"))
       (remove #(.contains (.getPath ^java.io.File %) "/test/"))))

(defn -main
  [& args]
  (let [roots (or (seq args) ["src" "protocols_src"])
        files (mapcat source-files roots)
        violations (vec (mapcat check-file files))]
    (if (empty? violations)
      (do (println (format "Direct-write check passed (%d files scanned)."
                           (count files)))
          (System/exit 0))
      (do (println "Direct-write violations (bypass canonical accounting):")
          (doseq [{:keys [file ns fn line text]} violations]
            (println (format "  %s/%s (%s:%d) — %s" ns fn file line text)))
          (println (format "\n%d violation(s) found. Add to allowlist in %s"
                           (count violations)
                           "scripts/scenarios/check_direct_writes.clj"))
          (System/exit 1)))))
