(ns scripts.scenarios.check-direct-writes
  "Static CI rule: flag direct writes to :total-held, :held/positions,
   and :resolver-stakes outside an explicit allowlist.

   These keys must only be mutated through canonical accounting functions.
   Direct assoc-in/update-in/assoc/update bypasses the invariant layer.

   The allowlist distinguishes two kinds of writer:

     :canonical                    — canonical custody/registry mutation entry
                                     points (add-held, sub-held, register-stake,
                                     ...). Their writes are independently
                                     authorised.
     :completeness-clearing-escape — direct-write escape hatches that MUST set
                                     :held-adjustments/complete? to false as a
                                     side effect (adversarial-accrue,
                                     apply-pro-rata-propagation). The gate
                                     statically verifies that each such entry
                                     contains the required completeness-clearing
                                     operation; the behavioural suites verify the
                                     production paths. It is not a generic
                                     authorised direct-write location, and the
                                     check is not a full control-flow proof.
     :replay-reconstruction        — independent reconstruction from the ledger
                                     (replay-held-adjustment-state). Not a
                                     mutation entry point.

   Every entry identifies the exact fully-qualified var and its expected
   behaviour/justification. Matching is by exact var, never by namespace or a
   loose source pattern.

   The private ledger/index mutator update-ledger-index is NOT in the allowlist.
   It is recognised separately as the sole canonical private mutator (see
   canonical-private-ledger-mutator): the gate verifies it is declared private
   and has exactly one detected direct caller, `adjust-held`. This is a static
   detection of direct source references, not a proof that indirect or dynamic
   invocation cannot occur.

   Exit 0 when clean, 1 on violation.

   Allowlist format:
     {var-symbol {:behaviour <behaviour> :justification <string>} ...}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private protected-keys
  "State keys that must not be written to directly."
  [":total-held" ":held/positions" ":resolver-stakes"])

(def ^:private write-keywords
  "Clojure core forms that perform direct state mutation."
  ["assoc-in" "update-in" "assoc" "update"])

(def ^:private allowed-behaviours
  #{:canonical :replay-reconstruction :completeness-clearing-escape})

(def allowlist
  "Map of exact fully-qualified vars allowed to write to the protected keys,
   to their expected behaviour and justification.

   Entries are matched by exact var only — never by namespace or a loose source
   pattern. Internal derived-index helpers (e.g. update-ledger-index) must not
   appear here; see canonical-private-ledger-mutator."
  {'resolver-sim.protocols.sew.accounting/add-held
   {:behaviour :canonical
    :justification "canonical custody mutation entry point (authorised :in)"}
   'resolver-sim.protocols.sew.accounting/sub-held
   {:behaviour :canonical
    :justification "canonical custody mutation entry point (authorised :out)"}
   'resolver-sim.assurance.custody/replay-held-adjustment-state
   {:behaviour :replay-reconstruction
    :justification "independent pure reconstruction of :total-held/:held/positions from the ledger; not a mutation"}
   'resolver-sim.protocols.sew.registry/register-stake
   {:behaviour :canonical
    :justification "canonical :resolver-stakes mutation entry point"}
   'resolver-sim.protocols.sew.registry/withdraw-stake
   {:behaviour :canonical
    :justification "canonical :resolver-stakes mutation entry point"}
   'resolver-sim.protocols.sew.registry/slash-resolver-stake
   {:behaviour :canonical
    :justification "canonical :resolver-stakes slash mutation entry point"}
   'resolver-sim.protocols.sew.resolution/reverse-reversal-slash-on-vindication
   {:behaviour :canonical
    :justification "canonical reversal-slash custody mutation entry point"}
   'resolver-sim.protocols.sew.lifecycle/cancel-disputed-escrow-now
   {:behaviour :canonical
    :justification "canonical disputed-escrow cancellation entry point"}
   'resolver-sim.yield.modules.adversarial/adversarial-accrue
   {:behaviour :completeness-clearing-escape
    :justification "adversarial drain/bloat writes :total-held directly and must clear :held-adjustments/complete?"}
   'resolver-sim.yield.modules.liquid-lending/apply-pro-rata-propagation
   {:behaviour :completeness-clearing-escape
    :justification "pro-rata application writes :total-held directly and must clear :held-adjustments/complete?"}})

(def canonical-private-ledger-mutator
  "The sole canonical private ledger/index mutator.

   It must remain `defn-` (private), must NOT be allowlisted, and must have
   exactly one detected direct caller, `adjust-held`. Its writes are downstream
   of an authorised mutation, not independently authorised.

   NOTE: 'detected direct caller' means the static check finds one direct
   source reference inside `adjust-held`. It does not prove that indirect or
   dynamic invocation cannot occur."
  {:var   'resolver-sim.protocols.sew.accounting/update-ledger-index
   :ns    "resolver-sim.protocols.sew.accounting"
   :fn    "update-ledger-index"
   :caller "adjust-held"})

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

(defn- references-name?
  "True when `form`'s tree contains a symbol whose name is `name-str`.
   Matches by symbol name only, so an unqualified call in source is caught
   regardless of how the reference is qualified at the call site."
  [form name-str]
  (boolean (some (fn [n] (and (symbol? n) (= name-str (name n))))
                 (tree-seq coll? seq form))))

(defn check-file
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
              (when (and (not (contains? allowlist qualified))
                         (not= qualified (:var canonical-private-ledger-mutator)))
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

(defn- ns->path-forms
  [ns-name]
  (-> (str/replace ns-name "-" "_")
      (str/replace "." "/")
      (str ".clj")))

(defn find-source-file
  "Locate the .clj source file for `ns-name` under one of `roots`."
  [roots ns-name]
  (some (fn [root]
          (let [f (io/file root (ns->path-forms ns-name))]
            (when (.isFile f) f)))
        roots))

(defn clears-completeness-flag?
  "Statically detects (by source pattern) that `form`'s tree sets the
   :held-adjustments/complete? key (i.e. it appears as the final element of
   some path vector, as in (assoc-in x [:params :held-adjustments/complete?]
   false)).

   This is a source-level containment check: it establishes that the function
   contains the required completeness-clearing operation. It does NOT prove
   that every control-flow/return path clears the flag; the behavioural suites
   exercise the production paths separately."
  [form]
  (boolean
   (some (fn [node]
           (and (vector? node)
                (= :held-adjustments/complete? (last node))))
         (tree-seq coll? seq form))))

(defn validate-allowlist-entry
  "Check that an allowlisted var resolves to an existing function and satisfies
   its declared behaviour. For :completeness-clearing-escape entries the gate
   statically verifies the function contains the required
   :held-adjustments/complete? clearing operation (source-pattern check, not a
   control-flow proof).

   Returns nil on success, or a failure map on error."
  [sym]
  (let [behaviour (get-in allowlist [sym :behaviour])
        s (str sym)
        sep-idx (.lastIndexOf s (int \/))]
    (cond
      (nil? behaviour)
      {:check/status :failed
       :reason :allowlisted-var-unannotated
       :symbol sym
       :message (str "Allowlisted var " sym " is missing a :behaviour entry")}

      (not (contains? allowed-behaviours behaviour))
      {:check/status :failed
       :reason :allowlisted-var-invalid-behaviour
       :symbol sym
       :behaviour behaviour
       :message (str "Allowlisted var " sym " has unsupported behaviour " behaviour)}

      (neg? sep-idx)
      {:check/status :failed
       :reason :allowlisted-var-no-namespace
       :symbol sym
       :message (str "Allowlisted symbol " sym " has no namespace separator")}

      :else
      (let [ns-name (subs s 0 sep-idx)
            fname (subs s (inc sep-idx))
            ns-sym (symbol ns-name)]
        (try
          (require ns-sym)
          (if-let [v (resolve (symbol (str ns-name "/" fname)))]
            (if (fn? @v)
              (if (= :completeness-clearing-escape behaviour)
                (let [file (find-source-file ["src" "protocols_src"] ns-name)
                      form (some (fn [f] (when (= fname (fn-name f)) f))
                                 (when file (read-top-level-forms file)))]
                  (cond
                    (nil? file)
                    {:check/status :failed
                     :reason :escape-hatch-source-file-missing
                     :symbol sym
                     :message (str "Escape-hatch " sym " source file not found for ns " ns-name)}

                    (nil? form)
                    {:check/status :failed
                     :reason :escape-hatch-fn-not-found
                     :symbol sym
                     :message (str "Escape-hatch function " fname " not found in " ns-name)}

                    (not (clears-completeness-flag? form))
                    {:check/status :failed
                     :reason :escape-hatch-does-not-clear-completeness
                     :symbol sym
                     :message (str "Escape-hatch " sym " writes a protected key but does not clear "
                                   ":held-adjustments/complete?; it must remain a completeness-clearing escape hatch")}

                    :else nil))
                nil)
              {:check/status :failed
               :reason :allowlisted-var-not-a-function
               :symbol sym
               :var v
               :type (type @v)})
            {:check/status :failed
             :reason :allowlisted-var-unresolvable
             :symbol sym
             :message (str "Var " fname " not found in " ns-name)})
          (catch Exception e
            {:check/status :failed
             :reason :allowlisted-ns-unresolvable
             :symbol sym
             :message (str "Namespace " ns-name " cannot be required: " (.getMessage e))}))))))

(defn check-canonical-private-mutator
  "Verify the canonical private ledger/index mutator invariants:
     - it is declared `defn-` (private);
     - it is NOT allowlisted;
     - every detected direct reference to it lives inside `adjust-held`.

   The reference analysis is static and source-based: it inspects the
   top-level function forms of the mutator's namespace for a direct symbol
   reference to the mutator. It does not model indirect or dynamic invocation.

   Returns nil on success, or a failure map on error."
  [roots]
  (let [{:keys [var ns caller]} canonical-private-ledger-mutator
        fname (:fn canonical-private-ledger-mutator)
        file (find-source-file roots ns)
        forms (when file (read-top-level-forms file))
        named-forms (keep (fn [f] (when-let [n (fn-name f)] [n f])) forms)
        def-form (some (fn [[n f]] (when (= n fname) f)) named-forms)
        private? (and (list? def-form) (= 'defn- (first def-form)))
        refs (filter (fn [[n f]] (and n (not= n fname) (references-name? f fname)))
                     named-forms)
        callers (set (map first refs))]
    (cond
      (nil? file)
      {:check/status :failed
       :reason :private-mutator-file-missing
       :message (str "Private mutator source " (:ns canonical-private-ledger-mutator) " not found")}

      (contains? allowlist var)
      {:check/status :failed
       :reason :private-mutator-allowlisted
       :message (str var " must NOT be in the allowlist; it is a private downstream mutator")}

      (nil? def-form)
      {:check/status :failed
       :reason :private-mutator-not-found
       :message (str fname " not found in " (:ns canonical-private-ledger-mutator))}

      (not private?)
      {:check/status :failed
       :reason :private-mutator-not-private
       :message (str var " must be declared `defn-` (private)")}

      (not (contains? callers caller))
      {:check/status :failed
       :reason :private-mutator-not-reachable-from-adjust-held
       :message (str var " must have a detected direct caller " caller "; detected direct callers were " (pr-str callers))}

      (seq (remove #{caller} callers))
      {:check/status :failed
       :reason :private-mutator-extra-callers
       :message (str var " has detected direct callers other than " caller ": " (pr-str (remove #{caller} callers)))}

      :else nil)))

(defn -main
  [& args]
  (let [roots (or (seq args) ["src" "protocols_src"])
        files (mapcat source-files roots)
        allowlist-errors (vec (keep validate-allowlist-entry (keys allowlist)))
        private-mutator-error (check-canonical-private-mutator roots)
        violations (vec (mapcat check-file files))
        failures (concat allowlist-errors
                         (when private-mutator-error [private-mutator-error])
                         violations)]
    (if (seq failures)
      (do (println "Direct-write gate FAILED:")
          (doseq [e failures]
            (println (format "  %s — %s" (or (:reason e) (:symbol e)) (:message e (:text e)))))
          (doseq [{:keys [file ns fn line text]} violations]
            (println (format "  %s/%s (%s:%d) — %s" ns fn file line text)))
          (println (format "\n%d issue(s) found. Update the allowlist or fix the flagged writer in %s"
                           (count failures)
                           "scripts/scenarios/check_direct_writes.clj"))
          (System/exit 1))
      (do (println (format "Direct-write check passed (%d files scanned, %d allowlisted functions valid, private mutator + escape-hatch checks clean)."
                           (count files) (count allowlist)))
          (System/exit 0)))))
