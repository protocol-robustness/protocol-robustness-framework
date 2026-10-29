(ns resolver-sim.commands.researcher-ux
  "Researcher-facing read and preparation commands over existing PRF artifacts."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [resolver-sim.benchmark.research-conclusion :as conclusion]
            [resolver-sim.benchmark.researcher-force-authorisation :as decisions]
            [resolver-sim.commands.boundary :as boundary]
            [resolver-sim.research.integer-sum-verifier :as integer-sum]
            [resolver-sim.research.policy-approval :as policy-approval]
            [resolver-sim.research.resolution-view :as resolution-view]))

(defn- invoke [command f]
  (try
    {:exit-code 0 :result (f)}
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println command ":" (.getMessage e)))
      {:exit-code (or (:exit-code (ex-data e)) 1) :message (.getMessage e)})
    (catch Exception e
      (binding [*out* *err*] (println command ":" (.getMessage e)))
      {:exit-code 1 :message (.getMessage e)})))

(defn- read-input [path]
  (boundary/read-edn path))

(defn- print-result [value]
  (pprint/pprint value)
  value)

(defn- question-definition [artifact]
  (cond-> (or (:research-definition artifact) artifact)
    (:research-definition/root artifact)
    (assoc :research-definition/root (:research-definition/root artifact))))

(defn question-list [{:keys [input]}]
  (invoke "research question list"
          #(let [catalog (read-input input)
                 questions (if (map? catalog)
                             (or (:questions catalog) (:research/questions catalog))
                             catalog)]
             (when-not (and (sequential? questions) (every? map? questions))
               (boundary/fail :invalid-input "Input must be a vector or {:questions [...]}"))
             (print-result
              {:research/questions
               (mapv (fn [q]
                       (select-keys (question-definition q)
                                    [:research/id :research/title :research/question
                                     :research-definition/root :research/status]))
                     questions)}))))

(defn question-show [{:keys [input cmd/args]}]
  (invoke "research question show"
          #(let [catalog (read-input input)
                 questions (if (map? catalog)
                             (or (:questions catalog) (:research/questions catalog))
                             catalog)
                 question-id (first args)
                 question (some (fn [q]
                                  (when (= (str question-id)
                                           (str (:research/id (question-definition q)))) q))
                                questions)]
             (when-not question
               (boundary/fail :unknown-question "Question ID was not found in the supplied catalog"
                              {:research/id question-id}))
             (print-result (assoc (question-definition question)
                                  :research-definition/root
                                  (:research-definition/root question))))))

(defn evidence-inspect [{:keys [input]}]
  (invoke "research evidence inspect"
          #(let [artifact (read-input input)]
             (when-not (map? artifact)
               (boundary/fail :invalid-input "Evidence input must be an EDN map"))
             (print-result
              {:inspection/status :loaded-not-verified
               :artifact/root (or (:artifact/root artifact)
                                 (:evidence/root artifact)
                                 (:method/evidence-root artifact))
               :artifact/schema (or (:artifact/schema artifact) (:schema/version artifact))
               :artifact/fields (vec (sort (keys artifact)))
               :artifact artifact}))))

(defn conclusion-verify [{:keys [input]}]
  (invoke "research conclusion verify"
          #(let [{:keys [conclusion theorems method-evidence
                         trusted-method-verifier-root]} (read-input input)
                 theorem-index (cond
                                (map? theorems) theorems
                                (sequential? theorems) (into {} (map (juxt :theorem/hash identity) theorems))
                                :else {})
                 resolver (fn [root] (get theorem-index root))
                 method-verifier (when method-evidence
                                   (integer-sum/method-verifier method-evidence))
                 result (conclusion/qualify-conclusion
                         conclusion resolver method-verifier
                         trusted-method-verifier-root)]
             (print-result result)
             (when-not (= :valid (:structure-status result))
               (throw (ex-info "Conclusion structure is invalid" {:result result})))
             result)))

(defn resolution-show [{:keys [input]}]
  (invoke "research resolution show"
          #(let [view (read-input input)
                 validation (resolution-view/validate-view view)
                 result {:resolution/status (if (:valid? validation) :valid :invalid)
                         :resolution/errors (:errors validation)
                         :resolution/view view}]
             (print-result result)
             (when-not (:valid? validation)
               (throw (ex-info "Resolution view failed validation" validation)))
             result)))

(defn review-status [{:keys [input]}]
  (invoke "research review status"
          #(let [{:keys [approval trusted-authority-root]} (read-input input)
                 result (policy-approval/verify-policy-approval
                         approval {:trusted-authority-root trusted-authority-root})
                 status (if (:valid? result) (:status result) :invalid)]
             (print-result {:review/status status
                            :review/valid? (:valid? result)
                            :review/errors (:errors result)
                            :review/ratification (:ratification result)
                            :review/operational-authority? false})
             (when-not (:valid? result)
               (throw (ex-info "Policy approval receipt failed verification" result))))))

(defn policy-approval-prepare [{:keys [input]}]
  (invoke "research policy approval prepare"
          #(print-result (policy-approval/prepare-policy-approval (read-input input)))))

(defn policy-approval-sign [{:keys [input key researcher-id]}]
  (invoke "research policy approval sign"
          #(let [{:keys [approval/signing-requests]} (read-input input)
                 request (some (fn [r]
                                 (when (= (str researcher-id)
                                          (str (:researcher/id r))) r))
                               signing-requests)]
             (when-not request
               (boundary/fail :unknown-researcher
                              "No signing request for this researcher ID"))
             (boundary/required-value "--key" key)
             (print-result
              (decisions/build-signed-decision-v2
               (:researcher/id request)
               (:authorisation/id request)
               (:authorisation/request-root request)
               (:review-round/hash request)
               (:outcome/root request)
               :approve key
               :signing-key-id (:signing-key/id request))))))

(defn policy-approval-verify [{:keys [input]}]
  (invoke "research policy approval verify"
          #(let [{:keys [approval trusted-authority-root]} (read-input input)
                 result (policy-approval/verify-policy-approval
                         approval {:trusted-authority-root trusted-authority-root})]
             (print-result result)
             (when-not (:valid? result)
               (throw (ex-info "Policy approval receipt failed verification" result)))
             result)))

(defn export-ro-crate [{:keys [input output format]}]
  (invoke "research export"
          #(do
             (when-not (= "ro-crate" format)
               (boundary/fail :invalid-option "--format must be ro-crate"))
             (boundary/required-value "--output" output)
             (let [source (io/file (boundary/required-value "--input" input))
                   _ (when-not (.isFile source)
                       (boundary/fail :missing-input "--input must name an artifact file"))
                   crate-dir (io/file output)
                   _ (.mkdirs crate-dir)
                   artifact-file (io/file crate-dir (.getName source))
                   _ (io/copy source artifact-file)
                   metadata {"@context" "https://w3id.org/ro/crate/1.1/context"
                             "@graph" [{"@type" "CreativeWork"
                                        "@id" "ro-crate-metadata.json"
                                        "about" {"@id" "./"}}
                                       {"@id" "./" "@type" "Dataset"
                                        "name" (.getName crate-dir)
                                        "conformsTo" {"@id" "https://w3id.org/ro/crate/1.1"}
                                        "hasPart" [{"@id" (.getName source)}]}
                                       {"@id" (.getName source)
                                        "@type" "File"
                                        "name" (.getName source)}]}
                   metadata-file (io/file crate-dir "ro-crate-metadata.json")]
               (spit metadata-file (json/write-str metadata :escape-unicode false))
               (print-result {:export/status :created
                              :export/format "ro-crate-1.1"
                              :export/directory (.getCanonicalPath crate-dir)
                              :export/metadata (.getCanonicalPath metadata-file)})))))
