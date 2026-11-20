(ns resolver-sim.dispute-review
  "Local, read-only PRF dispute review packages. These records carry no protocol authority."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc])
  (:import [java.nio.file Files Path FileAlreadyExistsException LinkOption]))

(def schema-version "prf-dispute-review.v0")
(def review-domain "PRF_DISPUTE_REVIEW_V0")
(def id-pattern #"^prf-dispute-review\.[A-Za-z0-9_-]+$")
(def trust-classes #{:locally-consistent :externally-authenticated :unestablished})
(def assessments #{:supported :contradicted :inconclusive :insufficient-evidence})

(defn root-dir []
  (let [xdg (System/getenv "XDG_DATA_HOME")
        home (System/getProperty "user.home")]
    (.toPath (io/file (or (not-empty xdg) (str home "/.local/share"))
                      "prf-dev" "dispute-reviews"))))

(defn- canonical-content [manifest]
  (dissoc manifest :review/canonical-root :review/instance-id))

(defn canonical-root [manifest]
  (str "sha256:" (hc/domain-hash review-domain (canonical-content manifest))))

(defn valid-id? [id] (and (string? id) (boolean (re-matches id-pattern id))))

(defn- as-path [value]
  (if (instance? Path value) value (.toPath (io/file value))))

(defn manifest-path [dir] (.resolve ^Path (as-path dir) "manifest.edn"))

(defn initialize!
  "Create an independent review directory and a canonical, incomplete manifest."
  [{:keys [target scope subject policy source-refs current-world evidence reviewer findings uncertainties status
           contradictions limitations reproduction attestation predecessors successors]}
   base-dir]
  (when-not (and (string? target) (not (str/blank? target))
                 (string? scope) (not (str/blank? scope)))
    (throw (ex-info "target and scope must be non-empty strings" {})))
  (Files/createDirectories ^Path base-dir (make-array java.nio.file.attribute.FileAttribute 0))
  (let [[id dir] (loop []
                   (let [candidate (str "prf-dispute-review." (str/replace (str (java.util.UUID/randomUUID)) #"-" ""))
                         path (.resolve ^Path base-dir candidate)]
                     (if (try
                           (Files/createDirectory path (make-array java.nio.file.attribute.FileAttribute 0))
                           true
                           (catch FileAlreadyExistsException _ false))
                       [candidate path]
                       (recur))))]
    (let [manifest {:review/schema schema-version
                    :review/instance-id id
                    :review/status (or status :incomplete)
                    :review/target target
                    :review/scope scope
                    :review/subject (or subject {:subject/kind :unspecified :subject/id nil
                                                 :subject/instance-id nil :subject/root nil
                                                 :subject/reference nil})
                    :review/policy (or policy {:id nil :reference nil :status :missing})
                    :review/source-refs (vec (or source-refs []))
                    :review/current-world (vec (or current-world []))
                    :review/evidence (vec (or evidence []))
                    :review/findings (vec (or findings []))
                    :review/uncertainties (vec (or uncertainties []))
                    :review/contradictions (vec (or contradictions []))
                    :review/limitations (vec (or limitations []))
                    :review/reproduction (vec (or reproduction []))
                    :review/verification {:schema :not-run :source-identities :not-run
                                          :evidence :not-run :reproducibility :not-run}
                    :review/reviewer (or reviewer {:name "unattributed"})
                    :review/attestation attestation
                    :review/predecessors (vec (or predecessors []))
                    :review/successors (vec (or successors []))}
          manifest (assoc manifest :review/canonical-root (canonical-root manifest))]
      (spit (.toFile (manifest-path dir)) (str (pr-str manifest) "\n"))
      {:id id :directory (str dir) :manifest manifest})))

(defn- read-manifest [dir]
  (let [f (.toFile (manifest-path dir))]
    (when-not (.isFile f) (throw (ex-info "manifest.edn not found" {:directory (str dir)})))
    (edn/read-string (slurp f))))

(defn- valid-manifest? [m]
  (and (map? m)
       (= schema-version (:review/schema m))
       (valid-id? (:review/instance-id m))
       (contains? #{:incomplete :complete} (:review/status m))
       (string? (:review/target m)) (not (str/blank? (:review/target m)))
       (string? (:review/scope m)) (not (str/blank? (:review/scope m)))
       (let [subject (:review/subject m)]
         (and (map? subject)
              (keyword? (:subject/kind subject))
              (or (nil? (:subject/id subject))
                  (and (string? (:subject/id subject)) (not (str/blank? (:subject/id subject)))))
              (or (nil? (:subject/instance-id subject))
                  (and (string? (:subject/instance-id subject)) (not (str/blank? (:subject/instance-id subject)))))
              (or (nil? (:subject/root subject))
                  (and (string? (:subject/root subject))
                       (re-matches #"^sha256:[0-9a-fA-F]{64}$" (:subject/root subject))))
              (or (nil? (:subject/reference subject)) (map? (:subject/reference subject)))))
       (let [policy (:review/policy m)]
         (and (map? policy)
              (contains? #{:pinned :missing} (:status policy))
              (or (nil? (:id policy))
                  (and (string? (:id policy)) (not (str/blank? (:id policy)))))
              (or (nil? (:reference policy)) (map? (:reference policy)))))
       (every? vector? (map m [:review/source-refs :review/current-world :review/evidence
                               :review/findings :review/uncertainties :review/contradictions
                               :review/limitations :review/reproduction :review/predecessors
                               :review/successors]))
       (every? (fn [finding]
                 (and (map? finding)
                      (string? (:id finding)) (not (str/blank? (:id finding)))
                      (string? (:claim finding)) (not (str/blank? (:claim finding)))
                      (contains? assessments (:assessment finding))
                      (contains? finding :expected) (contains? finding :observed)
                      (vector? (:evidence-refs finding))
                      (or (nil? (:counterexample finding))
                          (and (map? (:counterexample finding))
                               (contains? (:counterexample finding) :observation)
                               (vector? (:evidence-refs (:counterexample finding)))))
                      (or (not= :contradicted (:assessment finding))
                          (map? (:counterexample finding)))
                      (string? (:reproduction-id finding))
                      (contains? trust-classes (:trust-class finding))
                      (and (vector? (:coverage-limitations finding))
                           (seq (:coverage-limitations finding))
                           (every? string? (:coverage-limitations finding)))))
               (:review/findings m))
       (every? (fn [evidence]
                 (and (map? evidence) (contains? trust-classes (:trust-class evidence))))
               (:review/evidence m))
       (every? (fn [snapshot]
                 (and (map? snapshot) (some? (:observed-at snapshot))
                      (some? (:source-provenance snapshot))
                      (contains? trust-classes (:trust-class snapshot))
                      (vector? (:observations snapshot))))
               (:review/current-world m))
       (every? (fn [item]
                 (and (map? item)
                      (string? (:id item)) (not (str/blank? (:id item)))
                      (string? (:command item)) (not (str/blank? (:command item)))
                      (map? (:result-ref item))
                      (vector? (:input-refs item))
                      (contains? item :expected) (contains? item :observed)))
               (:review/reproduction m))
       (let [evidence-set (set (:review/evidence m))
             source-set (set (:review/source-refs m))
             reproduction-ids (set (map :id (:review/reproduction m)))
             finding-ids (map :id (:review/findings m))]
         (and (= (count finding-ids) (count (set finding-ids)))
              (= (count reproduction-ids) (count (:review/reproduction m)))
              (every? (fn [finding]
                        (and (every? evidence-set (:evidence-refs finding))
                             (every? evidence-set
                                     (get-in finding [:counterexample :evidence-refs]))
                             (contains? reproduction-ids (:reproduction-id finding))))
                      (:review/findings m))
              (every? (fn [item]
                        (every? source-set (:input-refs item)))
                      (:review/reproduction m))))))

(defn- safe-reference-file [review-dir ref]
  (let [path (or (:path ref) (:reference ref))
        root (.toRealPath (.toAbsolutePath (as-path review-dir)) (make-array LinkOption 0))
        candidate (when (string? path) (.normalize (.resolve ^Path root ^String path)))
        lexical-safe? (and candidate (.startsWith ^Path candidate ^Path root))
        exists? (and lexical-safe? (Files/exists ^Path candidate (make-array LinkOption 0)))
        real (when exists? (.toRealPath ^Path candidate (make-array LinkOption 0)))
        real-safe? (and real (.startsWith ^Path real ^Path root))]
    (cond
      (not (string? path)) {:status :absent :reason :no-local-path}
      (not lexical-safe?) {:status :failed :reason :path-outside-review}
      (not exists?) {:status :absent :reason :missing :candidate candidate}
      (not real-safe?) {:status :failed :reason :path-outside-review}
      :else {:status :candidate :candidate real})))

(defn- reference-check [review-dir ref]
  (let [resolved (when (map? ref) (safe-reference-file review-dir ref))
        file (when (= :candidate (:status resolved)) (.toFile ^Path (:candidate resolved)))]
    (cond
      (not (map? ref)) {:status :failed :reason :invalid-reference :reference ref}
      (not= :candidate (:status resolved)) (assoc (dissoc resolved :candidate) :reference ref)
      (not (.isFile file)) {:status :failed :reason :not-a-file :reference ref}
      (not (string? (:sha256 ref))) {:status :absent :reason :identity-unpinned :reference ref}
      (and (:sha256 ref) (not= (str/lower-case (:sha256 ref))
                               (let [d (java.security.MessageDigest/getInstance "SHA-256")
                                     bs (Files/readAllBytes (.toPath file))]
                                 (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest d bs))))))
      {:status :failed :reason :identity-mismatch :reference ref}
      :else {:status :passed :reference ref :resolved-file file})))

(defn- public-reference-check [check]
  (dissoc check :resolved-file))

(defn verify
  "Check structure, content identity, reference availability and declared reproduction checks."
  [dir]
  (let [m (read-manifest dir)
        schema (if (valid-manifest? m) :passed :failed)
        root (if (= (:review/canonical-root m) (canonical-root m)) :passed :failed)
        source-refs (concat (:review/source-refs m)
                            (keep identity [(get-in m [:review/subject :subject/reference])
                                            (get-in m [:review/policy :reference])]))
        source-checks (mapv #(reference-check dir %) source-refs)
        refs (concat (:review/evidence m) (mapcat :observations (:review/current-world m))
                     (keep :result-ref (:review/reproduction m)))
        checks (mapv #(reference-check dir %) refs)
        missing (count (filter #(= :absent (:status %)) checks))
        failed (count (filter #(= :failed (:status %)) checks))
        source-status (cond (empty? source-checks) :absent
                            (some #(= :failed (:status %)) source-checks) :failed
                            (some #(= :absent (:status %)) source-checks) :absent
                            :else :passed)
        policy-ref (get-in m [:review/policy :reference])
        policy-ref-status (if policy-ref (:status (reference-check dir policy-ref)) :absent)
        policy-id (get-in m [:review/policy :id])
        policy-status (cond
                        (= :missing (get-in m [:review/policy :status])) :absent
                        (= :failed policy-ref-status) :failed
                        (and (string? policy-id) (seq policy-id)
                             (= :passed policy-ref-status)) :passed
                        :else :absent)
        subject (get-in m [:review/subject])
        subject-identity-present? (every? #(some? (get subject %))
                                          [:subject/kind :subject/id :subject/instance-id :subject/root])
        subject-reference (:subject/reference subject)
        subject-ref-check (when subject-reference (reference-check dir subject-reference))
        subject-ref-status (:status subject-ref-check)
        subject-root-matches-reference? (= (some-> (:subject/root subject) str/lower-case)
                                           (when-let [digest (:sha256 subject-reference)]
                                             (str "sha256:" (str/lower-case digest))))
        subject-status (cond
                         (not subject-identity-present?) :absent
                         (= :failed subject-ref-status) :failed
                         (and (= :passed subject-ref-status)
                              (not subject-root-matches-reference?)) :failed
                         (= :passed subject-ref-status) :passed
                         :else :absent)
        repro (:review/reproduction m)
        repro-checks (mapv (fn [item]
                             (let [ref (:result-ref item)
                                   ref-check (reference-check dir ref)
                                   file (:resolved-file ref-check)
                                   recorded (when (and (= :passed (:status ref-check)) file)
                                              (try (edn/read-string (slurp file)) (catch Exception _ ::invalid)))]
                               (cond
                                 (not (and (contains? item :expected) (contains? item :observed)
                                           (= (:expected item) (:observed item))))
                                 {:status :failed :reason :expected-observed-mismatch}
                                 (= :absent (:status ref-check))
                                 {:status :absent :reason (:reason ref-check)}
                                 (= :failed (:status ref-check))
                                 {:status :failed :reason (:reason ref-check)}
                                 (= ::invalid recorded) {:status :failed :reason :recorded-result-invalid}
                                 (= (:observed item) recorded) {:status :passed}
                                 :else {:status :failed :reason :recorded-result-mismatch}))) repro)
        repro-status (cond (empty? repro) :absent
                           (some #(= :failed (:status %)) repro-checks) :failed
                           (every? #(= :passed (:status %)) repro-checks) :passed
                           :else :absent)
        complete? (and (= :passed schema) (= :passed root) (= :passed source-status)
                       (= :passed policy-status) (= :passed subject-status)
                       (zero? missing) (zero? failed)
                       (= :passed repro-status)
                       (seq (:review/findings m)) (not= :incomplete (:review/status m)))
        result {:review/id (:review/instance-id m)
                :schema schema :content-identity root
                :source-identities {:status source-status
                                    :checks (mapv public-reference-check source-checks)}
                :policy-identity {:status policy-status}
                :subject-identity {:status subject-status
                                   :subject/root (:subject/root subject)}
                :evidence {:passed (- (count checks) missing failed) :absent missing :failed failed
                           :references (mapv public-reference-check checks)}
                :trust-classification {:declared (frequencies
                                                  (concat (map :trust-class (:review/evidence m))
                                                          (map :trust-class (:review/current-world m))))
                                       :external-authentication :not-checked}
                :reproducibility {:status repro-status :checks repro-checks}
                :qualification (if complete? :qualified :incomplete)
                :authority :review-only}]
    result))
