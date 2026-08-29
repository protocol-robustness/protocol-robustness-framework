(ns resolver-sim.benchmark.authority-state-snapshot-store-test
  (:require [clojure.test :refer [deftest is]]
            [resolver-sim.benchmark.authority-state-snapshot :as snapshot]
            [resolver-sim.benchmark.authority-state-snapshot-store :as store]
            [resolver-sim.io.content-addressed-store :as cas]))

(defn- root [suffix] (str "sha256:" (apply str (repeat (- 64 (count suffix)) "0")) suffix))
(def store-id (root "aa"))
(def genesis-root (root "bb"))

(defn- input []
  {:authority-store/format snapshot/store-format :authority-store/id store-id
   :chain-instance-genesis/root genesis-root :publication/version 1
   :current-authoritative-state-envelope/root (root "01") :current-configuration-head/root (root "02")
   :current-chain-configuration/root (root "03") :current-authority-semantics-policy/root (root "04")
   :current-governed-authority-semantics/root (root "05") :current-authority-material-manifest/root (root "06")
   :activation-lineage-index/root (root "07") :issued-fence-index/root (root "08")
   :terminal-result-index/root (root "09") :receipt-index/root (root "0a")})

(defn- prepare! [backend]
  (let [{:keys [snapshot dependency-manifest]} (snapshot/build-snapshot (input))
        objects (:objects backend)]
    (cas/put-if-absent! objects {:hash-reference (:authority-state-dependency-manifest/root dependency-manifest)
                                 :artifact dependency-manifest
                                 :verify #(-> % snapshot/validate-dependency-manifest :valid?)})
    (doseq [root (:dependency/roots dependency-manifest)]
      (cas/put-if-absent! objects {:hash-reference root :artifact {:root root}
                                   :verify #(= root (:root %))}))
    snapshot))

(def verify-object (fn [root object] (= root (:root object))))
(def verify-joins (fn [_ _] true))

(deftest durable-current-pointer-round-trips-across-store-instances
  (let [path (str (java.nio.file.Files/createTempDirectory "p1-snapshot-" (make-array java.nio.file.attribute.FileAttribute 0)))
        writer (store/open-store! path store-id genesis-root)
        candidate (prepare! writer)]
    (try
      (is (:published? (store/publish-current! writer candidate verify-object verify-joins)))
      (store/close-store! writer)
      (let [reader (store/open-store! path store-id genesis-root)]
        (try
          (is (= candidate (:snapshot (store/read-current! reader verify-object verify-joins))))
          (finally (store/close-store! reader))))
      (finally (try (store/close-store! writer) (catch Exception _))))))

(deftest writer-identity-and-corruption-fail-closed
  (let [path (str (java.nio.file.Files/createTempDirectory "p1-snapshot-" (make-array java.nio.file.attribute.FileAttribute 0)))
        writer (store/open-store! path store-id genesis-root)
        candidate (prepare! writer)]
    (try
      (is (= :authority-store-writer-locked
             (:reason (ex-data (try (store/open-store! path store-id genesis-root)
                                    (catch clojure.lang.ExceptionInfo error error))))))
      (is (:published? (store/publish-current! writer candidate verify-object verify-joins)))
      (store/close-store! writer)
      (spit (str path "/current.edn") "{:broken true}\n")
      (let [reopened (store/open-store! path store-id genesis-root)]
        (try
          (is (= :invalid-current-pointer
                 (:reason (ex-data (try (store/read-current! reopened verify-object verify-joins)
                                        (catch clojure.lang.ExceptionInfo error error))))))
          (finally (store/close-store! reopened))))
      (finally (try (store/close-store! writer) (catch Exception _))))))
