(ns resolver-sim.coprocessor.source
  "Resolve the exact extracted coprocessor checkout without doing network work."
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- find-resolver [^java.io.File start]
  (loop [dir (.getCanonicalFile start)]
    (cond
      (nil? dir) nil
      (.isFile (java.io.File. dir "scripts/resolve-prf-coprocessor.sh"))
      (java.io.File. dir "scripts/resolve-prf-coprocessor.sh")
      :else (recur (.getParentFile dir)))))

(defn- prf-root []
  (let [configured (System/getenv "PRF_ROOT")
        source-resource (io/resource "resolver_sim/coprocessor/source.clj")
        resource-root (when (= "file" (some-> source-resource .getProtocol))
                        (let [source-file (io/file (.toURI source-resource))]
                          (-> source-file .getParentFile .getParentFile .getParentFile .getParentFile)))
        starts (remove nil? [(when configured (io/file configured))
                             resource-root
                             (io/file (System/getProperty "user.dir"))])]
    (some (fn [start]
            (when-let [resolver (find-resolver start)]
              (.getParentFile (.getParentFile resolver))))
          starts)))

(defn root
  "Return the pinned coprocessor checkout path. Checkout preparation is explicit
  via scripts/setup-prf-coprocessor.sh; this resolver only validates it."
  []
  (let [root (prf-root)
        resolver (when root (io/file root "scripts/resolve-prf-coprocessor.sh"))]
    (when-not resolver
      (throw (ex-info "Set PRF_ROOT or run with the PRF checkout on the classpath"
                      {:user.dir (System/getProperty "user.dir")
                       :prf-root (System/getenv "PRF_ROOT")})))
    (let [{:keys [exit out err]} (shell/sh "bash" (.getPath resolver))]
      (when-not (zero? exit)
        (throw (ex-info (str "Pinned prf-coprocessor is not prepared: " (str/trim err))
                        {:exit exit})))
      (str/trim out))))
