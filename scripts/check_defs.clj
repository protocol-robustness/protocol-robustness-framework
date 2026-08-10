#!/usr/bin/env clojure
(ns scripts.check-defs
  "Namespace def-inventory integrity check.

  Defense-in-depth layer that catches silent source truncation where a file
  still *loads* but has lost top-level definitions (the failure mode that
  broke the assurance-lab Clerk boot). For every source namespace it records
  the set of top-level defs and, in --check mode, verifies each def recorded
  in the golden inventory is still present.

  Two modes:
    --generate   Rebuild checks/ns-defs.edn from the current tree (run after
                 legitimately adding/removing defs).
    --check      (default) Compare current defs against the golden and fail
                 on any missing symbol.

  Usage:
    bb check:defs --generate
    bb check:defs
  Exit code is non-zero if any golden def is missing in check mode."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as find]))

(def ^:private default-roots ["src" "protocols_src"])
(def ^:private golden-path "checks/ns-defs.edn")

(defn- test-file? [file]
  (some #{"test"} (str/split (str (.getPath file)) #"/")))

(defn- source-pairs [roots]
  (for [root roots
        file (find/find-clojure-sources-in-dir (io/file root))
        :when (not (test-file? file))]
    [root file]))

(defn- file->ns [root ^java.io.File file]
  (let [root-path (.getCanonicalPath (io/file root))
        fpath     (.getCanonicalPath file)
        rel       (subs fpath (inc (count root-path)))]
    (-> (str/replace rel #"\.clj$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- read-forms [^java.io.File file]
  (try
    (let [rdr (clojure.lang.LineNumberingPushbackReader. (io/reader file))]
      (loop [a []]
        (let [f (read {:eof ::eof} rdr)]
          (if (= f ::eof) a (recur (conj a f))))))
    (catch Throwable _ [])))

(defn- def-symbol [form]
  (when (seq? form)
    (let [head (first form)]
      (when (contains? '#{def defn defn- defmacro defmacro- defonce defstruct} head)
        (when (symbol? (second form))
          (second form))))))

(defn- collect-defs [forms]
  (->> forms
       (keep def-symbol)
       (map str)
       distinct
       (sort)))

(defn- build-inventory [roots]
  (let [pairs (source-pairs roots)]
    (into (sorted-map)
          (map (fn [[root file]]
                 [(str (file->ns root file)) (collect-defs (read-forms file))]))
          pairs)))

(defn- write-golden! [inventory]
  (io/make-parents golden-path)
  (spit golden-path (with-out-str (prn inventory))))

(defn- load-golden []
  (if (.exists (io/file golden-path))
    (edn/read-string (slurp golden-path))
    (do (println (str "check:defs -- no golden at " golden-path
                      "; run: bb check:defs --generate"))
        (flush)
        (System/exit 1))))

(defn- -main [& args]
  (let [args  (or args [])
        i     (.indexOf (vec args) "--roots")
        roots (if (neg? i) default-roots (vec (drop (inc i) args)))]
    (if (some #{"--generate"} args)
      (do (write-golden! (build-inventory roots))
          (println (str "check:defs -- wrote " golden-path)))
      (let [golden (load-golden)
            current (build-inventory roots)
            missing (for [[ns-sym golden-defs] golden
                          def golden-defs
                          :when (not (contains? (set (get current ns-sym [])) def))]
                      {:ns ns-sym :def def})]
        (println (str "check:defs -- " (count golden) " namespaces, "
                      (reduce + 0 (map count (vals golden))) " golden defs"))
        (doseq [{:keys [ns def]} missing]
          (println (str "  MISSING " ns " :: " def)))
        (flush)
        (if (empty? missing)
          (println "check:defs -- PASS")
          (do (println (str "check:defs -- FAILED " (count missing)))
              (shutdown-agents)
              (System/exit 1)))))))
