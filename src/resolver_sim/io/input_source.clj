(ns resolver-sim.io.input-source
  "Stable input references for filesystem and classpath-backed executable data."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [resolver-sim.io.resource-path :as resource-path])
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(defn source
  "Resolve `ref` without coercing classpath resources to filesystem paths."
  [ref]
  (let [ref (str ref)
        classpath-ref (cond
                        (str/starts-with? ref "classpath:") ref
                        (str/starts-with? ref "resource:") (str "classpath:" (subs ref (count "resource:")))
                        :else nil)
        file-ref (when-not classpath-ref ref)
        resource-path (some-> classpath-ref (subs (count "classpath:")))
        file-path (when (and file-ref (str/starts-with? file-ref "file:"))
                    (subs file-ref (count "file:")))
        bare-file (when (and file-ref (not file-path)) (io/file file-ref))]
    (cond
      classpath-ref
      (if-let [url (io/resource resource-path)]
        {:input/type :classpath :input/ref classpath-ref :input/resource-path resource-path
         :input/display-name (last (str/split resource-path #"/")) :input/url url}
        (throw (ex-info "Classpath input not found" {:input/ref classpath-ref})))

      file-path
      (let [file (io/file file-path)]
        (if (.isFile file)
          {:input/type :file :input/ref ref :input/path (.getCanonicalPath file)
           :input/display-name (.getName file)}
          (throw (ex-info "Filesystem input not found" {:input/ref ref}))))

      (.isFile bare-file)
      {:input/type :file :input/ref ref :input/path (.getCanonicalPath bare-file)
       :input/display-name (.getName bare-file)}

      :else
      (if-let [url (io/resource file-ref)]
        {:input/type :classpath :input/ref (str "classpath:" file-ref)
         :input/resource-path file-ref
         :input/display-name (last (str/split file-ref #"/")) :input/url url}
        (throw (ex-info "Input not found" {:input/ref ref}))))))

(defn open-stream [input]
  (case (:input/type input)
    :classpath (io/input-stream (:input/url input))
    :file (io/input-stream (io/file (:input/path input)))
    (throw (ex-info "Unsupported input source" {:input input}))))

(defn read-bytes [input]
  (with-open [stream (open-stream input)]
    (.readAllBytes stream)))

(defn sha256 [input]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest ^bytes (read-bytes input))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn loadable-ref
  "Return a reference that scenario/benchmark loaders can read directly:
   classpath inputs are exposed as resource: URLs, filesystem inputs pass
   through with their original ref."
  [input]
  (let [ref (:input/ref input)]
    (if (str/starts-with? ref "classpath:")
      (str "resource:" (subs ref (count "classpath:")))
      ref)))

(defn freeze-source
  "Freeze source bytes from an InputSource once, returning a byte vector."
  [input]
  (read-bytes input))

(defn snapshot!
  "Materialize an InputSource to `destination`, returning immutable
   content provenance (path, sha256, byte count)."
  [input destination]
  (let [target (io/file (str destination))
        hash (sha256 input)
        bytes (read-bytes input)]
    (.mkdirs (.getParentFile target))
    (with-open [out (io/output-stream target)] (.write out ^bytes bytes))
    {:input/origin (:input/ref input)
     :input/snapshot (.getPath target)
     :input/sha256 hash
     :input/bytes (alength ^bytes bytes)}))
