(ns dev.tests
  (:require
   [clojure.test :as test]
   [clojure.string :as str]
   [dev.explore :as explore]))

(defn test-namespaces
  []
  (->> (explore/all-project-ns)
       (filter #(str/includes? (str %) "-test"))
       vec))

(defn require-test-namespaces!
  []
  (doseq [ns-sym (test-namespaces)]
    (try
      (require ns-sym)
      (catch Throwable e
        (tap> {:type :test-ns/require-failed
               :ns ns-sym
               :error (.getMessage e)}))))
  :loaded)

(defn run-all-tests
  []
  (require-test-namespaces!)
  (apply test/run-tests (test-namespaces)))

(defn run-tests-matching
  [needle]
  (require-test-namespaces!)
  (let [needle (str/lower-case (str needle))
        matches (->> (test-namespaces)
                     (filter #(str/includes? (str/lower-case (str %)) needle))
                     vec)]
    (apply test/run-tests matches)))
