(ns resolver-sim.composition.command-lineage
  "Thin, domain-neutral primitive for command composition anchored on immutable
  shared-state identity.

  Three identities, kept sharp:

    1. Shared-state identity -- an immutable {:kind :shared-state :ref sha256} ref.
    2. Combination identity -- built-with-includes over a canonical member set;
       order-independent, duplicate refs rejected (not silently canonicalised).
    3. Consecutive concatenation -- A then B where join-state is DERIVED as
       resulting-state(A) == input-state(B); order matters and A then B differs
       from B then A.

  Structural terminal rule: a cancel-and-terminate appended after a terminal
  head rejects as :predecessor-terminal; an identical terminal replay is
  recognised at admission as :already-terminated without appending a new element.

  Reuse (no new crypto): resolver-sim.hash.canonical (domain-hash, with lineage-
  specific string domain tags aligned with composition/contract and composition/plan)
  and resolver-sim.hash.reference. The primitive validates references and commits
  roots; it does not derive or interpret world state behind a shared-state root."
  (:require [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]))

;; ── domain tags (lineage-specific strings, no central registry edits) ──────────

(def combination-domain-tag
  "Domain tag for a command-built-with-includes combination commitment."
  "PRF_COMMAND_LINEAGE_COMBINATION_V1")

(def command-domain-tag
  "Domain tag for a command-lineage command root."
  "PRF_COMMAND_LINEAGE_COMMAND_V1")

(def concatenation-domain-tag
  "Domain tag for a consecutive-command-concatenation root."
  "PRF_COMMAND_LINEAGE_CONCATENATION_V1")

(def termination-domain-tag
  "Domain tag for a command-termination receipt root."
  "PRF_COMMAND_LINEAGE_TERMINATION_V1")

;; ── schema ids ──────────────────────────────────────────────────────────────────

(def combination-schema
  "Schema id for a command-built-with-includes combination record."
  "command-combination-lineage.v1")

(def command-schema
  "Schema id for a command-lineage command record."
  :command/composition-lineage.v1)

(def concatenation-schema
  "Schema id for a consecutive-command-concatenation record."
  :concatenation/consecutive-command-concatenation.v1)

(def termination-schema
  "Schema id for a command-termination receipt record."
  :command-termination.v1)

(def terminator-action
  "Action marking a command as the structural terminator of a lineage."
  :cancel-and-terminate)

(def ^:const member-kinds
  "Permitted :kind values for a combination member."
  #{:shared-state :command :concatenation})

;; ── members ─────────────────────────────────────────────────────────────────────

(defn valid-member?
  "True when m is a typed dependency {:kind kw :ref sha256-ref} with a known kind
  and a syntactically valid root reference."
  [m]
  (and (map? m)
       (contains? member-kinds (:kind m))
       (hash-ref/valid-sha256-ref? (:ref m))))

(defn duplicate-member-refs
  "Return a sorted vector of :ref values appearing more than once, or nil if none."
  [members]
  (let [dups (->> (or members [])
                  (map :ref)
                  (frequencies)
                  (filter (fn [[_ n]] (> n 1)))
                  (map first)
                  sort
                  vec)]
    (not-empty dups)))

(defn canonical-members
  "Validate and canonicalise a member collection.
  Throws ex-info with :reason :duplicate-included-member if any :ref repeats, or
  :invalid-member for a malformed entry. Returns a vector sorted by [:kind :ref]
  so permuting members cannot change the combination identity."
  [members]
  (let [members (vec (or members []))]
    (when-let [dups (duplicate-member-refs members)]
      (throw (ex-info "duplicate member ref in combination"
                      {:reason :duplicate-included-member
                       :duplicates dups})))
    (doseq [m members]
      (when-not (valid-member? m)
        (throw (ex-info "invalid member in combination"
                        {:reason :invalid-member
                         :member m}))))
    (vec (sort-by (juxt :kind :ref) members))))

;; ── combination (command-built-with-includes) ────────────────────────────────────

(defn combination-projection
  "Canonical projection of a combination: the schema and the canonicalised member
  set. Members are validated and sorted; duplicates throw."
  [members]
  {:combination/schema combination-schema
   :combination/members (canonical-members members)})

(defn combination-root
  "Content-addressed root of a command-built-with-includes combination over the
  canonical member set {S, A, B, ...}. Order-independent. Duplicate member refs
  throw (:duplicate-included-member). Returns a canonical sha256 reference."
  [members]
  (hash-ref/sha256-ref
   (hc/domain-hash combination-domain-tag (combination-projection members))))

(defn combination-root-valid?
  "Validate a declared combination root against its members. Returns
  {:valid? bool :reason kw ...}. Does not throw: structural member problems
  surface as their underlying reason."
  [declared members]
  (try
    (let [recomputed (combination-root members)]
      (if (= recomputed declared)
        {:valid? true}
        {:valid? false :reason :combination-root-mismatch
         :declared declared :computed recomputed}))
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :detail (ex-message e)})))

(defn build-combination
  "Assemble a self-describing combination record from its members."
  [members]
  (let [canon (canonical-members members)
        base {:combination/schema combination-schema
              :combination/built-with-includes canon}]
    (assoc base :combination/root (combination-root members))))

(defn combination-valid?
  "Structural validity check for a combination record. Does not resolve member
  refs; that is a resolver-backed concern."
  [c]
  (and (map? c)
       (= combination-schema (:combination/schema c))
       (vector? (:combination/built-with-includes c))
       (every? valid-member? (:combination/built-with-includes c))
       (nil? (duplicate-member-refs (:combination/built-with-includes c)))
       (hash-ref/valid-sha256-ref? (:combination/root c))))

;; ── state accessors for chain elements ─────────────────────────────────────────
;; Commands and termination receipts carry input/resulting state roots directly.
;; A bare combination is not a chain element (no transition). Concatenations
;; derive their input/resulting from resolved left/right elements inside the
;; concatenation verifier.

(defn input-state-root
  "input-state-root of a command or termination receipt."
  [elem]
  (cond
    (= command-schema (:command/schema elem))       (:command/input-state-root elem)
    (= termination-schema (:termination/schema elem)) (:termination/input-state-root elem)
    :else nil))

(defn resulting-state-root
  "resulting-state-root of a command or termination receipt. For a command this
  is its declared resulting-state; for a termination receipt it is the
  final-state-root."
  [elem]
  (cond
    (= command-schema (:command/schema elem))       (:command/resulting-state-root elem)
    (= termination-schema (:termination/schema elem)) (:termination/final-state-root elem)
    :else nil))

(defn element-root
  "The self-identity root of any rooted element."
  [elem]
  (or (:command/root elem)
      (:combination/root elem)
      (:concatenation/root elem)
      (:termination/root elem)))

(defn shared-state-ref
  "Extract the :shared-state :ref from a command's built-with-includes members,
  or nil if no shared-state member is present."
  [command]
  (let [members (:command/built-with-includes command [])]
    (some #(when (= :shared-state (:kind %)) (:ref %)) members)))

(defn find-shared-state-member
  "Return the shared-state member map from a command's members, or nil."
  [command]
  (let [members (:command/built-with-includes command [])]
    (some #(when (= :shared-state (:kind %)) %) members)))

;; ── command ─────────────────────────────────────────────────────────────────────

(defn command-projection
  "Canonical projection of a command: schema, action, state roots, the derived
  combination-root over its canonical members, and the canonical members.
  Excludes the self :command/root."
  [command]
  (let [members (:command/built-with-includes command [])]
    (assoc (select-keys command
                        [:command/schema
                         :command/action
                         :command/input-state-root
                         :command/resulting-state-root])
           :command/combination-root (combination-root members)
           :command/built-with-includes (canonical-members members))))

(defn command-root
  "Content-addressed root of a command. Throws on duplicate/invalid members."
  [command]
  (hash-ref/sha256-ref
   (hc/domain-hash command-domain-tag (command-projection command))))

(defn build-command
  "Assemble a command from {:command/action kw
   :command/input-state-root sha
   :command/resulting-state-root sha
   :command/built-with-includes [member ...]}.
  Canonicalises members (rejecting duplicates) and attaches :command/root.
  Fails fast on malformed state-root fields."
  [{:keys [command/action
           command/input-state-root
           command/resulting-state-root
           command/built-with-includes] :as opts}]
  (when-not (keyword? action)
    (throw (ex-info ":command/action must be a keyword"
                    {:reason :invalid-action :action action})))
  (doseq [root [:command/input-state-root :command/resulting-state-root]]
    (when-not (hash-ref/valid-sha256-ref? (get opts root))
      (throw (ex-info (str (name root) " must be a canonical sha256 reference")
                      {:reason :invalid-state-root
                       :field root
                       :value (get opts root)}))))
  (let [base {:command/schema command-schema
              :command/action action
              :command/input-state-root input-state-root
              :command/resulting-state-root resulting-state-root
              :command/built-with-includes (canonical-members built-with-includes)}]
    (assoc base :command/root (command-root base))))

(defn command-root-valid?
  "Validate a command's self-root by recompute-and-compare. Returns
  {:valid? bool :reason kw ...}. Does not throw: structural member problems
  surface as their underlying reason."
  [command]
  (try
    (let [recomputed (command-root command)]
      (if (= recomputed (:command/root command))
        {:valid? true}
        {:valid? false :reason :command-root-mismatch
         :declared (:command/root command) :computed recomputed}))
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :detail (ex-message e)})))

(defn valid-command?
  "Structural predicate: a command record with a valid schema, keyword action,
  valid root fields, and valid canonical members."
  [command]
  (and (map? command)
       (= command-schema (:command/schema command))
       (keyword? (:command/action command))
       (hash-ref/valid-sha256-ref? (:command/input-state-root command))
       (hash-ref/valid-sha256-ref? (:command/resulting-state-root command))
       (vector? (:command/built-with-includes command))
       (every? valid-member? (:command/built-with-includes command))
       (nil? (duplicate-member-refs (:command/built-with-includes command)))
       (hash-ref/valid-sha256-ref? (:command/root command))))

(defn terminator-command?
  "True when elem is a cancel-and-terminate command."
  [elem]
  (and (map? elem) (= terminator-action (:command/action elem))))

(defn verify-command
  "Full semantic verification of a command: structural validity (schema, action,
  state roots, member cardinality/refs, root hash). Returns {:valid? bool :issues [...]}."
  [command]
  (let [issues (atom [])]
    (when-not (valid-command? command)
      (swap! issues conj {:issue :invalid-command
                          :reason :invalid-command
                          :detail "Command failed structural validation"}))
    (let [root-check (command-root-valid? command)]
      (when-not (:valid? root-check)
        (swap! issues conj (assoc root-check :element (:command/root command)))))
    {:valid? (empty? @issues) :issues (vec @issues)}))

;; ── concatenation ──────────────────────────────────────────────────────────────

(defn concatenation-projection
  "Canonical projection of a concatenation record (excludes the self root)."
  [concatenation]
  (select-keys concatenation [:concatenation/schema
                              :concatenation/left
                              :concatenation/right
                              :concatenation/join-state]))

(defn concatenation-root
  "Content-addressed root of a concatenation."
  [concatenation]
  (hash-ref/sha256-ref
   (hc/domain-hash concatenation-domain-tag (concatenation-projection concatenation))))

(defn build-concatenation
  "Build a consecutive concatenation A then B.
  Requires resulting-state(A) == input-state(B); otherwise throws
  :predecessor-state-mismatch. join-state is DERIVED as resulting-state(A) and is
  never caller-supplied. left and right are command records."
  [left right]
  (let [left-res (:command/resulting-state-root left)
        right-in (:command/input-state-root right)]
    (when-not (= left-res right-in)
      (throw (ex-info "concatenation continuity violation: resulting-state(left) != input-state(right)"
                      {:reason :predecessor-state-mismatch
                       :left/root (:command/root left)
                       :right/root (:command/root right)
                       :left/resulting-state left-res
                       :right/input-state right-in})))
    (let [base {:concatenation/schema concatenation-schema
                :concatenation/left (:command/root left)
                :concatenation/right (:command/root right)
                :concatenation/join-state left-res}]
      (assoc base :concatenation/root (concatenation-root base)))))

(defn verify-concatenation
  "Verify a concatenation record against its resolved left and right commands.
  Returns {:valid? bool :issues [...]}. Checks schema, root integrity, left/right
  root bindings, child command validity, and re-derives the join-state fixed point."
  [concatenation left right]
  (let [issues (atom [])]
    (when-not (= concatenation-schema (:concatenation/schema concatenation))
      (swap! issues conj {:issue :invalid-concatenation-schema
                          :reason :invalid-concatenation-schema
                          :expected concatenation-schema
                          :actual (:concatenation/schema concatenation)}))
    (let [left-verify (verify-command left)]
      (when-not (:valid? left-verify)
        (swap! issues conj {:issue :left-command-invalid
                            :reason :invalid-command
                            :detail (:issues left-verify)})))
    (let [right-verify (verify-command right)]
      (when-not (:valid? right-verify)
        (swap! issues conj {:issue :right-command-invalid
                            :reason :invalid-command
                            :detail (:issues right-verify)})))
    (let [recomputed (concatenation-root (dissoc concatenation :concatenation/root))]
      (when-not (= recomputed (:concatenation/root concatenation))
        (swap! issues conj {:issue :concatenation-root-mismatch
                            :reason :concatenation-root-mismatch
                            :computed recomputed
                            :declared (:concatenation/root concatenation)})))
    (when-not (= (:command/root left) (:concatenation/left concatenation))
      (swap! issues conj {:issue :left-root-mismatch
                          :reason :left-root-mismatch
                          :expected (:concatenation/left concatenation)
                          :actual (:command/root left)}))
    (when-not (= (:command/root right) (:concatenation/right concatenation))
      (swap! issues conj {:issue :right-root-mismatch
                          :reason :right-root-mismatch
                          :expected (:concatenation/right concatenation)
                          :actual (:command/root right)}))
    (let [left-res (:command/resulting-state-root left)
          right-in (:command/input-state-root right)
          join  (:concatenation/join-state concatenation)]
      (when-not (= left-res right-in)
        (swap! issues conj {:issue :predecessor-state-mismatch
                            :reason :predecessor-state-mismatch
                            :left/resulting left-res
                            :right/input right-in}))
      (when-not (= join left-res)
        (swap! issues conj {:issue :join-state-mismatch
                            :reason :join-state-mismatch
                            :expected left-res
                            :actual join})))
    {:valid? (empty? @issues) :issues (vec @issues)}))

;; ── termination ────────────────────────────────────────────────────────────────

(defn termination-receipt-projection
  "Canonical projection of a termination receipt (excludes the self root)."
  [receipt]
  (select-keys receipt [:termination/schema
                        :termination/action
                        :termination/predecessor-root
                        :termination/input-state-root
                        :termination/final-state-root
                        :termination/status]))

(defn termination-root
  "Content-addressed root of a termination receipt."
  [receipt]
  (hash-ref/sha256-ref
   (hc/domain-hash termination-domain-tag (termination-receipt-projection receipt))))

(defn build-termination-command
  "Build a cancel-and-terminate command anchored on the current lineage head.
  input-state-root equals the head's resulting-state-root; the shared-state
  member carries that same head resulting-state-root; resulting-state-root equals
  terminal-state-root."
  [head terminal-state-root]
  (let [head-result (:command/resulting-state-root head)
        base {:command/schema command-schema
              :command/action terminator-action
              :command/input-state-root head-result
              :command/resulting-state-root terminal-state-root
              :command/built-with-includes [{:kind :shared-state :ref head-result}]}]
    (assoc base :command/root (command-root base))))

(defn build-termination-receipt
  "Derive the termination receipt from a cancel-and-terminate command and its
  immediate head command. Requires cancel-command to be the terminator action
  and cancel-command.input-state == head.resulting-state, else throws
  :not-a-terminator or :stale-termination-basis."
  [cancel-command head]
  (when-not (= terminator-action (:command/action cancel-command))
    (throw (ex-info "termination requires a cancel-and-terminate command"
                    {:reason :not-a-terminator
                     :action (:command/action cancel-command)})))
  (let [head-result (:command/resulting-state-root head)
        cancel-in   (:command/input-state-root cancel-command)]
    (when-not (= head-result cancel-in)
      (throw (ex-info "terminator was not built at the current lineage head"
                      {:reason :stale-termination-basis
                       :head-state head-result
                       :terminator-input cancel-in
                       :head-root (:command/root head)})))
    (let [base {:termination/schema termination-schema
                :termination/action terminator-action
                :termination/predecessor-root (:command/root head)
                :termination/input-state-root cancel-in
                :termination/final-state-root (:command/resulting-state-root cancel-command)
                :termination/status :terminated}]
      (assoc base :termination/root (termination-root base)))))

(defn termination-root-valid?
  "Validate a termination receipt's self-root by recompute-and-compare."
  [receipt]
  (try
    (let [recomputed (termination-root receipt)]
      (if (= recomputed (:termination/root receipt))
        {:valid? true}
        {:valid? false :reason :termination-root-mismatch
         :declared (:termination/root receipt) :computed recomputed}))
    (catch clojure.lang.ExceptionInfo e
      {:valid? false :reason (:reason (ex-data e)) :detail (ex-message e)})))

(defn termination-receipt?
  "True when elem is a command-termination receipt."
  [elem]
  (and (map? elem) (= termination-schema (:termination/schema elem))))

(defn terminal?
  "True when elem is a cancel-and-terminate command or its termination receipt."
  [elem]
  (or (terminator-command? elem) (termination-receipt? elem)))

(defn verify-termination-receipt
  "Semantically verify a termination receipt against its head command and the
  cancel-and-terminate command that produced it. Checks schema, root integrity,
  action, predecessor binding, input-state basis, three-way shared-state invariant,
  and final-state correspondence. Returns {:valid? bool :issues [...]}."
  [receipt head cancel-command]
  (let [issues (atom [])]
    (when-not (termination-receipt? receipt)
      (swap! issues conj {:issue :invalid-receipt
                          :reason :invalid-receipt
                          :detail "Not a termination receipt"}))
    (let [root-check (termination-root-valid? receipt)]
      (when-not (:valid? root-check)
        (swap! issues conj (assoc root-check :element (:termination/root receipt)))))
    (when-not (= (:termination/action receipt) terminator-action)
      (swap! issues conj {:issue :invalid-action
                          :reason :invalid-action
                          :expected terminator-action
                          :actual (:termination/action receipt)}))
    (when-not (= (:termination/predecessor-root receipt) (:command/root head))
      (swap! issues conj {:issue :predecessor-root-mismatch
                          :reason :predecessor-root-mismatch
                          :expected (:command/root head)
                          :actual (:termination/predecessor-root receipt)}))
    (let [head-res (:command/resulting-state-root head)
          receipt-in (:termination/input-state-root receipt)
          cancel-shared (shared-state-ref cancel-command)]
      (when-not (= head-res receipt-in)
        (swap! issues conj {:issue :stale-termination-basis
                            :reason :stale-termination-basis
                            :head-resulting head-res
                            :receipt-input receipt-in}))
      (when (some? head-res)
        (when-not (= head-res cancel-shared)
          (swap! issues conj {:issue :stale-termination-shared-state
                              :reason :stale-termination-shared-state
                              :head-resulting head-res
                              :terminator-shared-state cancel-shared}))))
    (when-not (= (:termination/final-state-root receipt)
                 (:command/resulting-state-root cancel-command))
      (swap! issues conj {:issue :final-state-mismatch
                          :reason :final-state-mismatch
                          :expected (:command/resulting-state-root cancel-command)
                          :actual (:termination/final-state-root receipt)}))
    {:valid? (empty? @issues) :issues (vec @issues)}))

;; ── lineage verification ────────────────────────────────────────────────────────

(defn verify-lineage
  "Verify an ordered lineage of command records. The final element may be a
  cancel-and-terminate terminator. Returns
  {:valid? bool :status kw :errors [...]}.

   status is one of:
     :ok                       -- no terminator, continuity holds
     :terminated               -- cleanly ends at a terminator
     :already-terminated       -- an identical terminator replay was recognized
     :predecessor-terminal     -- a successor appeared after a terminal head
     :stale-termination-basis  -- terminator input != current head resulting-state
     :stale-termination-shared-state -- terminator shared-state != head resulting-state
     :missing-termination-predecessor -- terminator with no preceding command
     :predecessor-state-mismatch -- a non-terminal step broke continuity
     :invalid-command          -- an element failed command verification

   append? is true when the lineage is valid and not an already-terminated replay;
   false otherwise (including when errors exist or replay is recognized)."
  [elements]
  (let [errors (atom [])
        terminal-root (atom nil)
        replayed? (atom false)]
    (loop [es (seq (vec elements)) head-result nil]
      (when-let [elem (first es)]
        (let [self (verify-command elem)]
          (when-not (:valid? self)
            (swap! errors conj {:issue :invalid-command
                                :reason :invalid-command
                                :element (element-root elem)
                                :detail (:issues self)})))
        (if (terminator-command? elem)
          (if (some? @terminal-root)
            (if (= (:command/root elem) @terminal-root)
              (reset! replayed? true)
              (swap! errors conj {:issue :predecessor-terminal
                                  :reason :predecessor-terminal
                                  :root (:command/root elem)
                                  :after @terminal-root}))
            (do
              (when (nil? head-result)
                (swap! errors conj {:issue :missing-termination-predecessor
                                    :reason :missing-termination-predecessor
                                    :root (:command/root elem)}))
              (when-let [hr head-result]
                (let [elem-input (:command/input-state-root elem)
                      elem-shared (shared-state-ref elem)]
                  (when-not (= hr elem-input)
                    (swap! errors conj {:issue :stale-termination-basis
                                        :reason :stale-termination-basis
                                        :head-resulting hr
                                        :terminator-input elem-input}))
                  (when (and (some? elem-shared) (not= hr elem-shared))
                    (swap! errors conj {:issue :stale-termination-shared-state
                                        :reason :stale-termination-shared-state
                                        :head-resulting hr
                                        :terminator-shared-state elem-shared}))))
              (reset! terminal-root (:command/root elem))))
          (do
            (when (some? @terminal-root)
              (swap! errors conj {:issue :predecessor-terminal
                                  :reason :predecessor-terminal
                                  :root (:command/root elem)
                                  :after @terminal-root}))
            (when (and (some? head-result)
                       (not= head-result (:command/input-state-root elem)))
              (swap! errors conj {:issue :predecessor-state-mismatch
                                  :reason :predecessor-state-mismatch
                                  :previous-result head-result
                                  :input (:command/input-state-root elem)}))))
        (recur (next es) (:command/resulting-state-root elem))))
    (let [status (if (seq @errors)
                   (or (:reason (first @errors)) :invalid)
                   (cond
                     (true? @replayed?) :already-terminated
                     (some? @terminal-root) :terminated
                     :else :ok))]
      {:valid?   (empty? @errors)
       :status   status
       :errors   (vec @errors)
       :append?  (and (empty? @errors) (not @replayed?))})))
