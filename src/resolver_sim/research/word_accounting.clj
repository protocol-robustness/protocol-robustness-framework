(ns resolver-sim.research.word-accounting
  "Research fixture for stealing single-word evidence work before additive
   accounting. The closed row grammar declares inputs; it does not validate an
   appeal bond, withdrawal, custody claim, or incentive entitlement."
  (:require [clojure.string :as str]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.research.accelerated-accounting :as accounting]
            [resolver-sim.research.runner-participation :as participation]))

(def ^:const document-schema "research-accounting-word-document.v1")
(def ^:const plan-schema "research-word-partition-plan.v1")
(def ^:const unit-schema "research-word-work-unit.v1")
(def ^:const assignment-schema "research-word-assignment.v1")
(def ^:const observation-schema "research-word-observation.v1")
(def ^:const reconciliation-schema "research-word-reconciliation.v1")
(def ^:const basis-schema "research-word-accounting-basis.v1")
(def ^:const tokenizer :java-non-whitespace-utf16-v1)
(def ^:private row-pattern
  #"(:[A-Za-z][A-Za-z0-9._-]*/[A-Za-z][A-Za-z0-9._-]*) (-?[0-9]+) (sha256:[0-9a-f]{64}) (sha256:[0-9a-f]{64})")

(defn- root [domain body] (hash-ref/sha256-ref (hc/domain-hash domain body)))
(defn- root? [x] (hash-ref/valid-sha256-ref? x))
(defn- canonical-root? [body key domain]
  (= (get body key) (try (root domain (dissoc body key)) (catch Exception _ nil))))

(defn- token-index [text]
  (let [matcher (re-matcher #"\S+" text)]
    (loop [index 0 out []]
      (if (.find matcher)
        (recur (inc index)
               (conj out {:word/index index :word/start (.start matcher)
                          :word/end (.end matcher) :word/text (.group matcher)}))
        out))))

(defn build-document [text]
  (when-not (and (string? text) (seq text) (seq (token-index text)))
    (throw (ex-info "Invalid word-accounting document" {})))
  (let [body {:artifact/schema document-schema :document/text text
              :document/tokenizer tokenizer :document/words (token-index text)}]
    (assoc body :research-accounting-word-document/root
           (root :research-accounting-word-document-v1 body))))

(defn valid-document? [document]
  (and (= document-schema (:artifact/schema document))
       (= tokenizer (:document/tokenizer document))
       (string? (:document/text document))
       (= (:document/words document) (token-index (:document/text document)))
       (seq (:document/words document))
       (canonical-root? document :research-accounting-word-document/root
                        :research-accounting-word-document-v1)))

(defn- unit-root [unit] (root :research-word-work-unit-v1
                             (dissoc unit :research-word-work-unit/root)))

(defn build-plan [document]
  (when-not (valid-document? document)
    (throw (ex-info "Invalid source document for word plan" {})))
  (let [units (mapv (fn [word]
                      (let [body {:artifact/schema unit-schema
                                  :research-accounting-word-document/root
                                  (:research-accounting-word-document/root document)
                                  :word/index (:word/index word)
                                  :word/start (:word/start word)
                                  :word/end (:word/end word)}]
                        (assoc body :research-word-work-unit/root (unit-root body))))
                    (:document/words document))
        body {:artifact/schema plan-schema
              :research-accounting-word-document/root
              (:research-accounting-word-document/root document)
              :word/tokenizer tokenizer :word/work-units units
              :word/coverage :every-token-exactly-once}]
    (assoc body :research-word-partition-plan/root
           (root :research-word-partition-plan-v1 body))))

(defn valid-plan? [document plan]
  (and (valid-document? document)
       (= plan (try (build-plan document) (catch Exception _ nil)))))

(defn- eligible-runner? [runner]
  (and (:valid? (participation/validate-participation runner))
       (:participation/opted-in? runner)
       (:participation/available? runner)
       (contains? (set (:runner/capabilities runner)) :capability/word-span)
       (contains? (set (:participation/job-classes runner)) :job/research-word-span)))

(defn assignment-root [assignment]
  (root :research-word-assignment-v1
        (dissoc assignment :research-word-assignment/root)))

(defn steal-next
  "An eligible idle runner takes the lowest-index unassigned word. Assignment
   order changes forensic roots; the reconstructed text and accounting basis
   are independent of runner and completion order."
  [document plan runner assignments]
  (when-not (and (valid-plan? document plan)
                 (every? #(= assignment-schema (:artifact/schema %)) assignments)
                 (every? (set (map :research-word-work-unit/root
                                   (:word/work-units plan)))
                         (map :research-word-work-unit/root assignments))
                 (every? #(and (= (:research-word-assignment/root %)
                                  (assignment-root %))
                               (= (:research-word-partition-plan/root plan)
                                  (:research-word-partition-plan/root %)))
                         assignments)
                 (= (count assignments)
                    (count (set (map :research-word-work-unit/root assignments)))))
    (throw (ex-info "Invalid word steal state" {})))
  (when (eligible-runner? runner)
    (let [claimed (set (map :research-word-work-unit/root assignments))
          unit (first (remove #(contains? claimed (:research-word-work-unit/root %))
                              (:word/work-units plan)))]
      (when unit
        (let [body {:artifact/schema assignment-schema
                    :research-word-partition-plan/root (:research-word-partition-plan/root plan)
                    :research-word-work-unit/root (:research-word-work-unit/root unit)
                    :runner-participation/root (:runner-participation/root runner)
                    :runner/reference (:runner/reference runner)
                    :runner/site-id (:runner/site-id runner)}]
          (assoc body :research-word-assignment/root (assignment-root body)))))))

(defn- observation-root [observation]
  (root :research-word-observation-v1
        (dissoc observation :research-word-observation/root)))

(defn observe-word [document plan assignment runner]
  (let [unit (some #(when (= (:research-word-work-unit/root %)
                            (:research-word-work-unit/root assignment)) %)
                   (:word/work-units plan))
        word (when unit (nth (:document/words document) (:word/index unit) nil))]
    (when-not (and (valid-plan? document plan) unit word (eligible-runner? runner)
                   (= assignment-schema (:artifact/schema assignment))
                   (= (:research-word-partition-plan/root plan)
                      (:research-word-partition-plan/root assignment))
                   (= (:runner-participation/root runner)
                      (:runner-participation/root assignment))
                   (= (:runner/reference runner) (:runner/reference assignment))
                   (= (:runner/site-id runner) (:runner/site-id assignment))
                   (= (:research-word-assignment/root assignment)
                      (assignment-root assignment)))
      (throw (ex-info "Invalid word observation lineage" {})))
    (let [body {:artifact/schema observation-schema
                :research-accounting-word-document/root
                (:research-accounting-word-document/root document)
                :research-word-partition-plan/root (:research-word-partition-plan/root plan)
                :research-word-work-unit/root (:research-word-work-unit/root unit)
                :research-word-assignment/root (:research-word-assignment/root assignment)
                :runner-participation/root (:runner-participation/root runner)
                :word/index (:word/index word) :word/start (:word/start word)
                :word/end (:word/end word) :word/text (:word/text word)
                :observation/method :recomputed-from-declared-text}]
      (assoc body :research-word-observation/root (observation-root body)))))

(defn- valid-observation? [document plan assignment observation]
  (let [unit (some #(when (= (:research-word-work-unit/root %)
                             (:research-word-work-unit/root assignment)) %)
                   (:word/work-units plan))]
   (and (= observation-schema (:artifact/schema observation))
       unit
       (= assignment-schema (:artifact/schema assignment))
       (= (:research-word-assignment/root assignment)
          (assignment-root assignment))
       (= (:research-word-assignment/root assignment)
          (:research-word-assignment/root observation))
       (= (:research-word-work-unit/root assignment)
          (:research-word-work-unit/root observation))
       (= (:runner-participation/root assignment)
          (:runner-participation/root observation))
       (= (:research-accounting-word-document/root document)
          (:research-accounting-word-document/root observation))
       (= (:research-word-partition-plan/root plan)
          (:research-word-partition-plan/root observation))
       (= :recomputed-from-declared-text (:observation/method observation))
       (= (select-keys unit [:word/index :word/start :word/end])
          (select-keys observation [:word/index :word/start :word/end]))
       (= (select-keys (nth (:document/words document) (:word/index observation) nil)
                       [:word/index :word/start :word/end :word/text])
          (select-keys observation [:word/index :word/start :word/end :word/text]))
       (= (:research-word-observation/root observation)
          (observation-root observation)))))

(defn ingest-observation [ingestion observation]
  (let [unit-root (:research-word-work-unit/root observation)
        prior (get ingestion unit-root)]
    (cond
      (nil? prior) (assoc ingestion unit-root observation)
      (= prior observation) ingestion
      :else (throw (ex-info "Conflicting word observation" {:word-unit/root unit-root})))))

(defn reconcile-words [document plan assignments participants ingestion]
  (when-not (valid-plan? document plan)
    (throw (ex-info "Invalid word reconciliation plan" {})))
  (let [units (:word/work-units plan)
        required (set (map :research-word-work-unit/root units))
        by-unit (into {} (map (juxt :research-word-work-unit/root identity) assignments))
        by-participation (into {} (map (juxt :runner-participation/root identity) participants))
        supplied (set (keys ingestion))]
    (when-not (and (= required supplied)
                   (= required (set (keys by-unit)))
                   (= (count units) (count assignments))
                   (= (count participants) (count by-participation))
                   (every? (fn [assignment]
                             (let [runner (by-participation
                                           (:runner-participation/root assignment))]
                               (and (eligible-runner? runner)
                                    (= (:runner/reference runner)
                                       (:runner/reference assignment))
                                    (= (:runner/site-id runner)
                                       (:runner/site-id assignment)))))
                           assignments)
                   (every? (fn [[unit-root observation]]
                             (and (= unit-root (:research-word-work-unit/root observation))
                                  (valid-observation? document plan
                                                      (by-unit unit-root) observation)))
                           ingestion))
      (throw (ex-info "Incomplete or substituted word evidence"
                      {:missing (vec (sort (remove supplied required)))
                       :extra (vec (sort (remove required supplied)))})))
    (let [ordered (mapv #(ingestion (:research-word-work-unit/root %)) units)
          body {:artifact/schema reconciliation-schema
                :research-accounting-word-document/root
                (:research-accounting-word-document/root document)
                :research-word-partition-plan/root (:research-word-partition-plan/root plan)
                :research-word-observation/roots
                (vec (sort (map :research-word-observation/root ordered)))
                :word/semantic-root
                (root :research-word-sequence-v1
                      {:research-accounting-word-document/root
                       (:research-accounting-word-document/root document)
                       :word/texts (mapv :word/text ordered)})
                :word/texts (mapv :word/text ordered)}]
      (assoc body :research-word-reconciliation/root
             (root :research-word-reconciliation-v1 body)))))

(defn declared-accounting-request
  "Parse the complete verified word set as four-field fixture rows:
   `:category/name signed-integer subject-root claim-to-root`. Source identity
   binds the original document and row index. This does not admit the rows as
   economically valid bond/withdrawal/custody facts."
  [document plan assignments participants ingestion
   {:keys [accounting-domain cutpoint-root accounting-definition-root
           accounting-policy-root reduction-contract]}]
  ;; No caller-provided word projection or scalar values: revalidate the exact
  ;; observed constituent set before constructing the accounting input.
  (reconcile-words document plan assignments participants ingestion)
  (let [rows (->> (str/split-lines (:document/text document))
                  (remove str/blank?) vec)
        sources (mapv (fn [index row]
                        (let [[_ category amount subject claim-to]
                              (re-matches row-pattern row)]
                          (when-not (and category (root? subject) (root? claim-to))
                            (throw (ex-info "Invalid declared accounting row"
                                            {:row/index index})))
                          {:source/root
                           (root :research-declared-accounting-row-v1
                                 {:research-accounting-word-document/root
                                  (:research-accounting-word-document/root document)
                                  :row/index index :row/text row})
                           :accounting/category (keyword (subs category 1))
                           :accounting/value (bigint amount)
                           :accounting/subject-root subject
                           :claim-to/root claim-to}))
                      (range) rows)]
    (accounting/build-request
     {:accounting-domain accounting-domain :cutpoint-root cutpoint-root
      :accounting-definition-root accounting-definition-root
      :accounting-policy-root accounting-policy-root
      :reduction-contract reduction-contract :sources sources})))

(defn derive-word-accounting-basis
  "Return the semantic accounting request and a separate rooted forensic
   binding to every word observation. Runner order affects only the latter."
  [document plan assignments participants ingestion request-inputs]
  (let [reconciliation (reconcile-words document plan assignments participants ingestion)
        request (declared-accounting-request document plan assignments participants
                                             ingestion request-inputs)
        body {:artifact/schema basis-schema
              :research-accounting-word-document/root
              (:research-accounting-word-document/root document)
              :research-word-partition-plan/root
              (:research-word-partition-plan/root plan)
              :research-word-reconciliation/root
              (:research-word-reconciliation/root reconciliation)
              :research-word-observation/roots
              (:research-word-observation/roots reconciliation)
              :accounting-request/root (:accounting-request/root request)
              :accounting/source-roots (vec (sort (map :source/root
                                                     (:accounting/sources request))))
              :basis/meaning :declared-research-input-not-economic-admission}
        evidence (assoc body :research-word-accounting-basis/root
                        (root :research-word-accounting-basis-v1 body))]
    {:request request :word-reconciliation reconciliation :basis-evidence evidence}))

(defn valid-word-accounting-basis?
  "Validate the semantic request and forensic binding from their frozen
   constituent bodies rather than trusting a detached evidence root."
  [document plan assignments participants ingestion request-inputs basis]
  (= basis
     (try (derive-word-accounting-basis document plan assignments participants
                                        ingestion request-inputs)
          (catch Exception _ nil))))
