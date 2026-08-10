#!/usr/bin/env clojure
(ns scripts.check-load
  "Load-all integrity gate.

  Requires every reachable namespace under the supplied source roots and
  reports any that fail to load. This catches silent source corruption where
  a file still compiles standalone but is missing symbols referenced by its
  consumers (e.g. a truncated file losing a defn) -- the failure mode that
  broke Clerk notebook boot on the assurance-lab host.

  Usage:
    clojure -M:with-sew:check -m scripts.check-load
    clojure -M:with-sew:check -m scripts.check-load --roots src protocols_src

  Exit code is non-zero if any namespace fails to load. A short reason string
  (first line of the exception) is printed per failure."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as find]))

(def ^:private default-roots ["src" "protocols_src"])

(defn- root-set [args]
  (let [args (or args [])]
    (let [i (.indexOf (vec args) "--roots")]
      (if (neg? i)
        default-roots
        (vec (drop (inc i) args))))))

(defn- test-namespace? [ns-sym]
  (some #{"test"} (clojure.string/split (str ns-sym) #"\.")))

(defn- file->ns [root ^java.io.File file]
  (let [root-path (.getCanonicalPath (io/file root))
        fpath     (.getCanonicalPath file)
        rel       (subs fpath (inc (count root-path)))]
    (-> (str/replace rel #"\.clj$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- source-files [roots]
  (for [root roots
        file (find/find-clojure-sources-in-dir (io/file root))
        :when (not (some #{"test"} (str/split (str (.getPath file)) #"/")))]
    [root file]))

(defn- find-and-require-all! [roots]
  (let [pairs (source-files roots)
        nss   (map (fn [[r f]] (file->ns r f)) pairs)]
    (println (str "check:load -- found " (count nss) " namespaces across "
                  (str/join ", " roots)))
    (letfn [(attempt [ns-sym]
              (try
                (require ns-sym)
                {:ok true}
                (catch Throwable t
                  {:ok false
                   :reason (or (some-> t .getMessage str/trim (str/split-lines) first)
                               (.getName (class t)))})))]
      (reduce (fn [{:keys [ok failures]} ns-sym]
                (if-let [r (attempt ns-sym)]
                  (if (:ok r)
                    {:ok (inc ok) :failures failures}
                    {:ok ok :failures (conj failures {:ns ns-sym :reason (:reason r)})})
                  {:ok ok :failures failures}))
              {:ok 0 :failures []}
              nss))))

(defn -main [& args]
  (let [{:keys [total ok failures]} (find-and-require-all! (root-set args))]
    (println (str "check:load -- OK " ok "/" total))
    (doseq [{:keys [ns reason]} failures]
      (println (str "  FAIL " ns " -- " reason)))
    (flush)
    (if (empty? failures)
      (println "check:load -- PASS")
      (do (println (str "check:load -- FAILED " (count failures)))
          (shutdown-agents)
          (System/exit 1)))))
