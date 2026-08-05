(ns find-bad-read
  (:require [clojure.java.io :as io]))

(def eof (Object.))

(defn read-forms [file]
  (with-open [reader (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (loop [forms []]
        (let [form (read {:eof eof :read-cond :allow} reader)]
          (if (identical? eof form)
            forms
            (recur (conj forms form))))))))

(doseq [f (file-seq (io/file "src")) :when (and (.isFile f) (clojure.string/ends-with? (.getName f) ".clj"))]
  (let [file f]
    (try
      (read-forms file)
      (catch Exception e
        (println "ERROR reading" (.getPath file) "=>" (.getMessage e))))))
(println "done")
