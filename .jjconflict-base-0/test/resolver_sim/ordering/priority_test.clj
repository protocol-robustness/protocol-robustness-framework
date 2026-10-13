(ns resolver-sim.ordering.priority-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.ordering.priority :as p]))

;; ── Fixtures ────────────────────────────────────────────────────────────

(def subjects
  [{:subject/id :claim/a :subject/kind :claim}
   {:subject/id :claim/b :subject/kind :claim}
   {:subject/id :claim/c :subject/kind :claim}])

(defn- classify-claim
  [subject]
  (case (:subject/id subject)
    :claim/a {:priority/tier 1 :priority/reason :secured-claim}
    :claim/b {:priority/tier 1 :priority/reason :secured-claim}
    :claim/c {:priority/tier 2 :priority/reason :subordinated-claim}))

(defn- build-order
  [& [overrides]]
  (p/build-priority-order
   (merge {:subjects subjects
           :classifier classify-claim
           :comparison-basis {:method :declared-tier :parameter-root "claims/v1"}}
          overrides)))

(def order (build-order))

(defn- exception-reason
  [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error
      (:reason (ex-data error)))))

;; ── Construction and artifact shape ─────────────────────────────────────

(deftest build-produces-expected-priority-classes
  (testing "the artifact answers which subjects must be considered first"
    (let [classes (p/priority-classes order)]
      (is (= [:claim/a :claim/b]
             (->> classes (filter #(= 0 (:priority/rank %))) first :members)))
      (is (= [:claim/c]
             (->> classes (filter #(= 1 (:priority/rank %))) first :members)))))
  (testing "the comparison basis and contract are committed"
    (is (= {:method :declared-tier :parameter-root "claims/v1"}
           (:comparison-basis order)))
    (is (= {:relation :total-preorder
            :reflexive? true :transitive? true
            :total-between-classes? true :ties-permitted? true}
           (:comparison-contract order)))
    (is (= {:method :declared-tier :comparator :ascending
            :tie-policy :equal-priority :unclassified-policy :reject}
           (:derivation order))))
  (testing "the artifact is content-addressed with an exact preimage"
    (is (string? (:artifact/content-hash order)))
    (is (= (pr-str (dissoc order :artifact/content-hash :artifact/preimage))
           (:artifact/preimage order))))
  (testing "the artifact is canonical-safe"
    (is (nil? (hc/validate-canonical-value! order)))))

(deftest build-is-permutation-invariant
  (testing "subject input order never affects the artifact"
    (let [forward (build-order)
          reversed (build-order {:subjects (vec (reverse subjects))})
          shuffled (build-order {:subjects [(nth subjects 2)
                                            (first subjects)
                                            (second subjects)]})]
      (is (= forward reversed))
      (is (= forward shuffled))
      (is (= (:artifact/content-hash forward)
             (:artifact/content-hash reversed))))))

(deftest build-is-deterministic-across-invocations
  (is (= order (build-order)))
  (is (= (:artifact/content-hash order) (:artifact/content-hash (build-order)))))

(deftest envelope-metadata-is-excluded-from-canonical-commitment
  (testing "envelope metadata does not affect content identity"
    (let [plain (build-order)
          with-id (build-order {:priority/id :my-order})
          with-metadata (build-order {:metadata {:priority/id :my-order :origin "review"}})]
      (is (= {:priority/id :my-order} (:artifact/metadata with-id)))
      (is (= {:priority/id :my-order :origin "review"} (:artifact/metadata with-metadata)))
      (is (= (:artifact/content-hash plain) (:artifact/content-hash with-id)))
      (is (= (:artifact/content-hash plain) (:artifact/content-hash with-metadata)))
      (is (= (:artifact/preimage plain) (:artifact/preimage with-id)))
      (is (empty? (p/priority-order-violations with-metadata)))
      (is (nil? (hc/validate-canonical-value! with-metadata)))))
  (testing "metadata tampering is not a canonical violation"
    (let [tampered (assoc order :artifact/metadata {:priority/id :forged})]
      (is (empty? (p/priority-order-violations tampered)))
      (is (every? :holds? (p/derived-claims tampered)))))
  (testing "metadata is not part of the committed body"
    (let [with-id (build-order {:priority/id :my-order})
          body (dissoc with-id :artifact/metadata :artifact/content-hash :artifact/preimage)]
      (is (not (str/includes? (:artifact/preimage with-id) "my-order")))
      (is (not (contains? body :artifact/metadata)))
      (is (nil? (:priority/id body)))
      (is (= body (dissoc order :artifact/content-hash :artifact/preimage))))))

(deftest build-rejects-invalid-requests
  (is (= :missing-classifier
         (exception-reason #(p/build-priority-order
                             {:subjects subjects
                              :comparison-basis {:method :declared-tier}}))))
  (is (= :missing-subject-id
         (exception-reason #(p/build-priority-order
                             {:subjects [{:subject/kind :claim}]
                              :classifier classify-claim
                              :comparison-basis {:method :declared-tier}}))))
  (is (= :duplicate-subject-id
         (exception-reason #(p/build-priority-order
                             {:subjects (concat subjects [(first subjects)])
                              :classifier classify-claim
                              :comparison-basis {:method :declared-tier}}))))
  (is (= :unknown-priority-method
         (exception-reason #(build-order {:comparison-basis {:method :made-up}}))))
  (is (= :unsupported-tie-policy
         (exception-reason #(build-order {:tie-policy :serial-tie}))))
  (is (= :unsupported-comparator
         (exception-reason #(build-order {:comparator :sideways}))))
  (is (= :invalid-priority-key-value
         (exception-reason #(build-order {:classifier (fn [_] {:priority/tier "high"})})))))

;; ── Query surface ───────────────────────────────────────────────────────

(deftest query-surface
  (is (= 0 (p/priority-rank order :claim/a)))
  (is (= 1 (p/priority-rank order :claim/c)))
  (is (nil? (p/priority-rank order :claim/nope)))
  (is (true? (p/equal-priority? order :claim/a :claim/b)))
  (is (false? (p/equal-priority? order :claim/a :claim/c)))
  (is (true? (p/higher-priority? order :claim/a :claim/c)))
  (is (false? (p/higher-priority? order :claim/c :claim/a)))
  (is (true? (p/lower-priority? order :claim/c :claim/a)))
  (is (= :higher (p/compare-priority order :claim/a :claim/c)))
  (is (= :equal (p/compare-priority order :claim/a :claim/b)))
  (is (= :lower (p/compare-priority order :claim/c :claim/a)))
  (is (= :unclassified (p/compare-priority order :claim/a :claim/nope)))
  (is (= :unclassified (p/compare-priority order :claim/nope :claim/a)))
  (is (= 0 (:priority/rank (p/priority-class order :claim/a))))
  (is (= [:claim/a :claim/b] (:members (p/priority-class order :claim/a))))
  (is (nil? (p/priority-class order :claim/nope)))
  (is (= 0 (:priority/rank (p/priority-class-by-rank order 0))))
  (is (nil? (p/priority-class-by-rank order 9)))
  (is (= 1 (:priority/rank (p/next-priority-class order 0))))
  (is (nil? (p/next-priority-class order 1))))

;; ── Required invariants ─────────────────────────────────────────────────

(deftest required-invariants-hold
  (testing "a freshly built artifact has no violations"
    (is (empty? (p/priority-order-violations order)))
    (is (true? (p/valid-priority-order? order))))
  (testing "membership completeness and exclusivity"
    (is (= (set (map :subject/id subjects))
           (set (mapcat :members (p/priority-classes order)))))
    (is (= (count (mapcat :members (p/priority-classes order)))
           (count (distinct (mapcat :members (p/priority-classes order)))))))
  (testing "dense canonical ranks"
    (is (= [0 1] (mapv :priority/rank (p/priority-classes order)))))
  (testing "stable equality: same tier, same class regardless of reason"
    (is (= (p/priority-class order :claim/a)
           (p/priority-class order :claim/b)))))

;; ── Tampering detection ─────────────────────────────────────────────────

(defn- violation-reasons
  [artifact]
  (set (map :reason (p/priority-order-violations artifact))))

(deftest tampering-is-detected
  (testing "member swap is detected"
    (let [tampered (assoc-in order [:priority-classes 0 :members]
                             [:claim/b :claim/a])]
      (is (contains? (violation-reasons tampered)
                     :priority-order/non-canonical-member-order))
      (is (contains? (violation-reasons tampered)
                     :priority-order/content-hash-mismatch))))
  (testing "member replacement is detected"
    (let [tampered (assoc-in order [:priority-classes 0 :members]
                             [:claim/a :claim/x])]
      (is (contains? (violation-reasons tampered)
                     :priority-order/incomplete-membership))
      (is (contains? (violation-reasons tampered)
                     :priority-order/extra-members))
      (is (contains? (violation-reasons tampered)
                     :priority-order/class-reconstruction-mismatch))))
  (testing "duplicate membership is detected"
    (let [tampered (assoc-in order [:priority-classes 1 :members]
                             [:claim/c :claim/c])]
      (is (contains? (violation-reasons tampered)
                     :priority-order/duplicate-membership))))
  (testing "empty class is detected"
    (let [tampered (assoc-in order [:priority-classes 1 :members] [])]
      (is (contains? (violation-reasons tampered)
                     :priority-order/empty-class))
      (is (contains? (violation-reasons tampered)
                     :priority-order/incomplete-membership))))
  (testing "non-dense ranks are detected"
    (let [tampered (assoc-in order [:priority-classes 0 :priority/rank] 5)]
      (is (contains? (violation-reasons tampered)
                     :priority-order/non-dense-ranks))
      (is (contains? (violation-reasons tampered)
                     :priority-order/classes-root-mismatch))))
  (testing "class key forgery is detected"
    (let [tampered (assoc-in order [:priority-classes 0 :priority/key]
                             {:priority/tier 99})]
      (is (contains? (violation-reasons tampered)
                     :priority-order/class-reconstruction-mismatch))
      (is (contains? (violation-reasons tampered)
                     :priority-order/members-root-mismatch))))
  (testing "class order swap is detected"
    (let [tampered (assoc order :priority-classes
                          (vec (reverse (:priority-classes order))))]
      (is (contains? (violation-reasons tampered)
                     :priority-order/class-reconstruction-mismatch))
      (is (contains? (violation-reasons tampered)
                     :priority-order/classes-root-mismatch))))
  (testing "content-hash forgery is detected"
    (is (contains? (violation-reasons (assoc order :artifact/content-hash "beef"))
                   :priority-order/content-hash-mismatch)))
  (testing "preimage forgery is detected"
    (is (contains? (violation-reasons (assoc order :artifact/preimage "beef"))
                   :priority-order/preimage-mismatch)))
  (testing "subject-priority-key tampering is detected"
    (let [rogue (assoc-in order [:subject-priority-keys 2 :priority/classified] false)]
      (is (contains? (violation-reasons rogue)
                     :priority-order/unclassified-subject-with-reject-policy)))
    (let [rogue (assoc-in order [:subject-priority-keys 2 :priority/key]
                          {:priority/tier "oops"})]
      (is (contains? (violation-reasons rogue)
                     :priority-order/class-reconstruction-failed)))
    (let [rogue (assoc-in order [:subject-priority-keys 2 :subject/id] :claim/nope)]
      (is (contains? (violation-reasons rogue)
                     :priority-order/missing-subject-priority-key))
      (is (contains? (violation-reasons rogue)
                     :priority-order/class-reconstruction-mismatch))))
  (testing "component root tampering is detected"
    (is (contains? (violation-reasons (assoc order :subject-set-root "beef"))
                   :priority-order/subject-set-root-mismatch))
    (is (contains? (violation-reasons (assoc order :comparison-basis-root "beef"))
                   :priority-order/comparison-basis-root-mismatch))
    (is (contains? (violation-reasons (assoc order :priority-classes-root "beef"))
                   :priority-order/classes-root-mismatch))))

;; ── Unclassified policies ───────────────────────────────────────────────

(defn- classify-with-unclassified
  [subject]
  (when-not (= :claim/c (:subject/id subject))
    {:priority/tier 1}))

(deftest unclassified-policy-reject-throws
  (is (= :unclassified-subjects
         (exception-reason #(p/build-priority-order
                             {:subjects subjects
                              :classifier classify-with-unclassified
                              :comparison-basis {:method :declared-tier}
                              :unclassified-policy :reject})))))

(deftest unclassified-policy-lowest-priority
  (let [o (p/build-priority-order
           {:subjects subjects
            :classifier classify-with-unclassified
            :comparison-basis {:method :declared-tier}
            :unclassified-policy :lowest-priority})]
    (is (empty? (p/priority-order-violations o)))
    (is (= 0 (p/priority-rank o :claim/a)))
    (is (= 1 (p/priority-rank o :claim/c)))
    (is (= {:priority/unclassified true}
           (-> (p/priority-class o :claim/c) :priority/key)))
    (is (= 1 (:priority/rank (p/priority-class o :claim/c))))
    (is (= :higher (p/compare-priority o :claim/a :claim/c)))))

(deftest unclassified-policy-highest-priority
  (let [o (p/build-priority-order
           {:subjects subjects
            :classifier classify-with-unclassified
            :comparison-basis {:method :declared-tier}
            :unclassified-policy :highest-priority})]
    (is (empty? (p/priority-order-violations o)))
    (is (= 0 (p/priority-rank o :claim/c)))
    (is (= 1 (p/priority-rank o :claim/a)))
    (is (= :higher (p/compare-priority o :claim/c :claim/a)))))

;; ── Comparator direction ────────────────────────────────────────────────

(deftest descending-comparator-flips-order
  (let [o (build-order {:comparator :descending})]
    (is (= 0 (p/priority-rank o :claim/c)))
    (is (= 1 (p/priority-rank o :claim/a)))
    (is (empty? (p/priority-order-violations o)))
    (is (= :higher (p/compare-priority o :claim/c :claim/a)))))

;; ── Priority methods ────────────────────────────────────────────────────

(deftest timestamp-method-orders-by-earliest-first
  (let [subjects [{:subject/id :a} {:subject/id :b} {:subject/id :c}]
        classifier (fn [s]
                     {:priority/timestamp
                      (case (:subject/id s)
                        :a "2024-03-01T10:00:00Z"
                        :b "2024-01-01T10:00:00Z"
                        :c "2024-02-01T10:00:00Z")})
        o (p/build-priority-order
           {:subjects subjects
            :classifier classifier
            :comparison-basis {:method :timestamp}})]
    (is (= [:b :c :a] (mapv first (map :members (p/priority-classes o)))))
    (is (empty? (p/priority-order-violations o)))))

(deftest timestamp-method-normalizes-instants-to-iso-strings
  (let [subjects [{:subject/id :a} {:subject/id :b}]
        classifier (fn [s]
                     {:priority/timestamp
                      (if (= :a (:subject/id s))
                        (java.time.Instant/parse "2024-01-01T00:00:00Z")
                        "2024-02-01T00:00:00Z")})
        o (p/build-priority-order
           {:subjects subjects
            :classifier classifier
            :comparison-basis {:method :timestamp}})]
    (is (empty? (p/priority-order-violations o)))
    (is (= 0 (p/priority-rank o :a)))
    (is (every? (fn [entry]
                  (string? (get-in entry [:priority/key :priority/timestamp])))
                (:subject-priority-keys o)))
    (is (nil? (hc/validate-canonical-value! o)))))

(deftest severity-method-orders-by-declared-rank
  (let [subjects [{:subject/id :a} {:subject/id :b} {:subject/id :c}]
        classifier (fn [s]
                     {:priority/severity
                      (case (:subject/id s)
                        :a 1 :b 3 :c 2)})
        o (p/build-priority-order
           {:subjects subjects
            :classifier classifier
            :comparison-basis {:method :severity}})]
    (is (= [:a :c :b] (mapv first (map :members (p/priority-classes o)))))
    (is (empty? (p/priority-order-violations o)))))

(deftest security-interest-method-uses-registered-field
  (let [subjects [{:subject/id :a} {:subject/id :b}]
        classifier (fn [s]
                     {:priority/security-interest-rank
                      (if (= :a (:subject/id s)) 0 1)})
        o (p/build-priority-order
           {:subjects subjects
            :classifier classifier
            :comparison-basis {:method :security-interest}})]
    (is (= 0 (p/priority-rank o :a)))
    (is (empty? (p/priority-order-violations o)))))

(deftest custom-method-registration-is-extension-backed
  (let [method {:method/name :greek-order
                :method/field :priority/letter
                :method/description "Declared Greek letter priority"
                :method/comparison-contract {:relation :total-preorder
                                             :reflexive? true :transitive? true
                                             :total-between-classes? true
                                             :ties-permitted? true}
                :method/group-key-fn (fn [key] {:priority/letter (:priority/letter key)})
                :method/compare-keys-fn
                (fn [left right]
                  (compare (get {:alpha 0 :beta 1 :gamma 2}
                                (:priority/letter left))
                           (get {:alpha 0 :beta 1 :gamma 2}
                                (:priority/letter right))))
                :method/validate-key-fn
                (fn [key]
                  (when-not (#{:alpha :beta :gamma} (:priority/letter key))
                    (throw (ex-info "invalid letter" {:reason :bad-letter}))))
                :method/parameter-projection
                (fn [_] {:method :greek-order})
                :method/evidence-projection
                (fn [_] {:method :greek-order})}
        _ (p/register-method! method)
        subjects [{:subject/id :a} {:subject/id :b}]
        classifier (fn [s] {:priority/letter (if (= :a (:subject/id s)) :gamma :alpha)})
        o (p/build-priority-order
           {:subjects subjects
            :classifier classifier
            :comparison-basis {:method :greek-order}})]
    (is (= :greek-order (:method/name (:greek-order p/priority-methods))))
    (is (= 1 (p/priority-rank o :a)))
    (is (empty? (p/priority-order-violations o)))))

;; ── Hash intent integration ─────────────────────────────────────────────

(deftest priority-hash-intent-is-registered-and-domain-separated
  (let [payload {:artifact/kind :priority-order :members [:a :b]}]
    (is (string? (hc/hash-with-intent {:hash/intent :priority-order-v1} payload)))
    (is (not= (hc/hash-with-intent {:hash/intent :priority-order-v1} payload)
              (hc/hash-with-intent {:hash/intent :pro-rata-allocation-result} payload)))
    (is (= "PRIORITY_ORDER_V1" (:intent/domain-tag (hc/resolve-intent :priority-order-v1))))))

;; ── Derived claims ──────────────────────────────────────────────────────

(deftest derived-claims-hold-on-valid-artifact
  (let [claims (p/derived-claims order)]
    (is (every? :holds? claims))
    (is (some #(= :priority-completeness (:claim/kind %)) claims))
    (is (some #(= :priority-policy-reproduction (:claim/kind %)) claims)))
  (is (true? (:holds? (p/equal-priority-claim order :claim/a :claim/b))))
  (is (false? (:holds? (p/equal-priority-claim order :claim/a :claim/c))))
  (is (true? (:holds? (p/strict-precedence-claim order :claim/a :claim/c))))
  (is (false? (:holds? (p/strict-precedence-claim order :claim/c :claim/a)))))

(deftest derived-claims-fail-when-arbitrated-by-other-policies
  (testing "claims reference the artifact without duplicating priority logic"
    (let [tampered (assoc order :artifact/content-hash "beef")]
      (is (false?
           (:holds? (first (filter #(= :priority-completeness (:claim/kind %))
                                   (p/derived-claims tampered)))))))))

;; ── Architecture boundary ───────────────────────────────────────────────

(def ^:private forbidden-primitive-dependencies
  "Namespaces the priority primitive must never depend on: liquidity, claim
   amount, accounting, or allocation implementation namespaces."
  ["resolver-sim.pro-rata"
   "resolver-sim.economics"
   "resolver-sim.claims"
   "resolver-sim.accounting"
   "resolver-sim.yield"
   "resolver-sim.financial"])

(defn- ns-source-path
  [ns-symbol]
  (-> (str ns-symbol)
      (str/replace "-" "_")
      (str/replace "." "/")
      (str ".clj")))

(defn- ns-source-requires
  "Extract the library names required by a namespace, parsed from its source
   ns declaration so both aliased and non-aliased requires are visible."
  [ns-symbol]
  (let [ns-form (edn/read-string (slurp (io/resource (ns-source-path ns-symbol))))
        require-clause (->> (filter seq? ns-form)
                            (some (fn [form]
                                    (when (= :require (first form))
                                      (rest form)))))
        entries (if (and (sequential? require-clause)
                         (every? sequential? require-clause))
                  require-clause
                  [require-clause])
        libs (for [entry entries]
               (cond
                 (vector? entry) (name (first entry))
                 (string? entry) (first (str/split entry #"\s+"))
                 (symbol? entry) (name entry)
                 :else nil))]
    (vec (remove nil? libs))))

(deftest primitive-namespace-has-no-allocation-or-liquidity-dependencies
  (let [requires (ns-source-requires 'resolver-sim.ordering.priority)
        violations (filterv (fn [lib]
                              (some (fn [forbidden]
                                      (or (= lib forbidden)
                                          (str/starts-with? lib (str forbidden "."))))
                                    forbidden-primitive-dependencies))
                            requires)]
    (is (empty? violations)
        (str "primitive depends on out-of-scope namespaces: " violations))
    (is (= ["clojure.set" "resolver-sim.hash.canonical"]
           (vec (sort requires))))))

;; ── Evidence reconstruction ─────────────────────────────────────────────

(deftest verifier-reconstructs-from-committed-keys-without-classifier
  (testing "the verifier regroups from stored per-subject keys and re-orders"
    (is (empty? (p/priority-order-violations order)))
    (testing "even when the classifier function is gone"
      (let [artifact (dissoc order :subjects :artifact/content-hash :artifact/preimage)]
        (is (= #{:priority-order/extra-members
                 :priority-order/content-hash-mismatch
                 :priority-order/preimage-mismatch
                 :priority-order/subject-set-root-mismatch}
               (violation-reasons artifact)))))))
