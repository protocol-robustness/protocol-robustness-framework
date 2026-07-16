(ns resolver-sim.commands.scenario-safety
  "Run-root ownership and public-bundle sensitivity checks."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.commands.run-lifecycle :as lifecycle])
  (:import [java.nio.file Files FileAlreadyExistsException StandardCopyOption]))

(def ^:private lock-name ".run.lock")
(def ^:private secret-patterns
  [#"-----BEGIN (?:RSA |EC |OPENSSH |)?PRIVATE KEY-----"
   #"(?i)(?:api[_-]?key|password|secret|private[_-]?key|access[_-]?token)\s*[:=]"
   #"(?i)authorization:\s*bearer\s+" ])

(defn acquire-lock!
  "Compatibility delegate; canonical callers use lifecycle/acquire-run-lock!."
  [run-root]
  (lifecycle/acquire-run-lock! run-root nil :scenario))

(defn release-lock! [lock]
  "Compatibility delegate; canonical callers use lifecycle/release-run-lock!."
  (lifecycle/release-run-lock! lock))

(defn- text-file? [file]
  (boolean (re-find #"\.(json|edn|md|txt|csv)$" (.getName (io/file file)))))

(defn- sensitivity-findings [run-root]
  (let [root (io/file (str run-root))
        forbidden #{".run.lock" ".run-state" "completion.json"}]
    (->> (file-seq root)
         (filter #(.isFile %))
         (remove #(contains? forbidden (.getName %)))
         (filter text-file?)
         (mapcat (fn [file]
                   (let [body (slurp file)]
                     (keep (fn [pattern]
                             ;; Reports identify the location and detector only;
                             ;; they never reproduce the matched secret value.
                             (when (re-find pattern body)
                               {:path (.getPath file) :pattern (str pattern)}))
                           secret-patterns))))
         vec)))

(defn scan-public-bundle! [run-root]
  (let [findings (sensitivity-findings run-root)]
    (when (seq findings)
      (throw (ex-info "Public bundle sensitivity scan failed" {:findings findings})))
    {:profile :public :decision :allowed :findings []}))

(defn scan-internal-bundle!
  "Scan an internal bundle without blocking approved retention. Findings are
   sanitized metadata and the resulting report explicitly marks the bundle as
   internal-only whenever restricted-looking content is present."
  [run-root]
  (let [findings (sensitivity-findings run-root)]
    {:profile :internal
     :decision (if (seq findings) :internal-retention :allowed)
     :findings findings}))

(defn write-sensitivity-report!
  "Persist the pre-finalization export decision so it can be registered with the bundle."
  [manifest-dir result]
  (let [target (io/file (str manifest-dir) "sensitivity-report.json")
        temp (io/file (str (.getPath target) ".tmp"))
        report {"schema_version" "sensitivity-report.v1"
                "profile" (name (:profile result))
                "decision" (name (or (:decision result) :allowed))
                "findings" (:findings result [])}]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str report))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    report))
