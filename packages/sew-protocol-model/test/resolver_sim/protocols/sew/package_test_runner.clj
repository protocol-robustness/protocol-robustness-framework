(ns resolver-sim.protocols.sew.package-test-runner
  "Run the Sew model package tests with its in-tree yield integration fixture."
  (:require [clojure.java.io :as io]
            [clojure.test :as test]
            [resolver-sim.protocols.sew.yield-extension]))

(defn- test-namespaces []
  (->> (file-seq (io/file "test"))
       (filter #(and (.isFile %) (.endsWith (.getName %) "_test.clj")))
       (keep (fn [file]
              (with-open [reader (java.io.PushbackReader. (io/reader file))]
                (when-let [form (read reader false nil)]
                  (second form)))))
       sort
       vec))

(defn -main [& _]
  (let [namespaces (test-namespaces)]
    (doseq [namespace namespaces]
      (require namespace))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (System/exit (+ fail error)))))
