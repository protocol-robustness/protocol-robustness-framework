(ns resolver-sim.commands.scenario-safety
  "Run-root ownership and public-bundle sensitivity checks."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files FileAlreadyExistsException StandardCopyOption]))

(def ^:private lock-name ".run.lock")
(def ^:private secret-patterns
  [#"-----BEGIN (?:RSA |EC |OPENSSH |)?PRIVATE KEY-----"
   #"(?i)(?:api[_-]?key|password|secret|private[_-]?key|access[_-]?token)\s*[:=]"
   #"(?i)authorization:\s*bearer\s+" ])

(defn acquire-lock! [run-root]
  (let [root (io/file (str run-root)) lock (io/file root lock-name)]
    (.mkdirs root)
    (try
      (Files/createFile (.toPath lock) (make-array java.nio.file.attribute.FileAttribute 0))
      lock
      (catch FileAlreadyExistsException _
        (throw (ex-info "Run root is already in use" {:run/root (.getPath root) :lock (.getPath lock)}))))))

(defn release-lock! [lock]
  (when (and lock (.exists (io/file lock))) (.delete (io/file lock))))

(defn- text-file? [file]
  (boolean (re-find #"\.(json|edn|md|txt|csv)$" (.getName (io/file file)))))

(defn scan-public-bundle! [run-root]
  (let [root (io/file (str run-root))
        forbidden #{".run.lock" ".run-state" "completion.json"}
        findings (->> (file-seq root)
                      (filter #(.isFile %))
                      (remove #(contains? forbidden (.getName %)))
                      (filter text-file?)
                      (mapcat (fn [file]
                                (let [body (slurp file)]
                                  (keep (fn [pattern]
                                          (when (re-find pattern body)
                                            {:path (.getPath file) :pattern (str pattern)}))
                                        secret-patterns))))
                      vec)]
    (when (seq findings)
      (throw (ex-info "Public bundle sensitivity scan failed" {:findings findings})))
    {:profile :public :findings []}))

(defn write-sensitivity-report!
  "Persist the pre-finalization export decision so it can be registered with the bundle."
  [manifest-dir result]
  (let [target (io/file (str manifest-dir) "sensitivity-report.json")
        temp (io/file (str (.getPath target) ".tmp"))
        report {"schema_version" "sensitivity-report.v1"
                "profile" (name (:profile result))
                "decision" "allowed"
                "findings" (:findings result [])}]
    (.mkdirs (.getParentFile target))
    (spit temp (json/write-str report))
    (Files/move (.toPath temp) (.toPath target)
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
    report))
