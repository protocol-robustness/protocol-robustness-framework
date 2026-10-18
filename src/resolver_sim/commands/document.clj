(ns resolver-sim.commands.document
  "Small EDN request/output contract for protocol command adapters."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]))

(defn require! [condition message]
  (when-not condition (throw (ex-info message {:exit-code 2}))))

(defn closed! [value required optional]
  (require! (map? value) "Request must be an EDN map")
  (let [unknown (sort (set/difference (set (keys value)) (set/union required optional)))
        missing (sort (filter #(nil? (get value %)) required))]
    (require! (empty? unknown) (str "Unknown request field(s): " (pr-str (vec unknown))))
    (require! (empty? missing) (str "Missing required field(s): " (pr-str (vec missing)))))
  value)

(defn read-request [path]
  (require! (some? path) "Specify --input FILE or --input - for EDN stdin")
  (let [text (slurp (if (= "-" path) *in* (io/file path)))
        reader (java.io.PushbackReader. (java.io.StringReader. text))
        eof (Object.)
        request (edn/read {:eof eof} reader)]
    (require! (map? request) "Request must be an EDN map")
    (require! (identical? eof (edn/read {:eof eof} reader)) "Expected exactly one EDN document")
    request))

(defn resolve-path
  "Request paths resolve relative to the request file, or cwd for stdin."
  [input path]
  (require! (and (string? path) (seq path)) "Expected a nonempty path string")
  (let [path (if (str/starts-with? path "~/")
               (str (System/getProperty "user.home") (subs path 1)) path)
        file (io/file path)
        base (if (= "-" input) (io/file (System/getProperty "user.dir"))
                 (.getParentFile (.getAbsoluteFile (io/file input))))]
    (.getCanonicalPath (if (.isAbsolute file) file (io/file base path)))))

(defn- json-value [x]
  (cond
    (keyword? x) (subs (str x) 1)
    (integer? x) (str x)
    (map? x) (into {} (map (fn [[k v]]
                             [(if (keyword? k) (subs (str k) 1) (str k))
                              (if (= :exit-code k) v (json-value v))])) x)
    (coll? x) (mapv json-value x)
    :else x))

(defn emit [opts result exit-code]
  (if (:json? opts) (println (json/write-str (json-value result))) (prn result))
  {:exit-code exit-code})

(defn execute [opts f]
  (let [request (try
                  (read-request (:input opts))
                  (catch Exception e
                    (binding [*out* *err*] (println (.getMessage e)))
                    ::invalid-request))]
    (if (= ::invalid-request request)
      (emit opts {:status :error :message "Invalid request"} 2)
      (try
        (f request)
        (catch Exception e
          (binding [*out* *err*] (println "Command failed:" (.getMessage e)))
          (emit opts {:status :error :message (.getMessage e)}
                (or (:exit-code (ex-data e)) 1)))))))
