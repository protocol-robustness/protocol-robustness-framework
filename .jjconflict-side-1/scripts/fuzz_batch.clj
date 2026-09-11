(ns scripts.fuzz-batch
  "G9c differential-fuzz batch verifier.")
(require '[clojure.data.json :as json]
         '[resolver-sim.conformance.bundle :as bundle]
         '[resolver-sim.conformance.json :as json-scan])

(defn- bundle-result [path]
  (let [text (slurp path)
        duplicate (json-scan/duplicate-json-key text)]
    (if duplicate
      {:status "rejected" :claimable false :root nil}
      (let [r (bundle/verify-bundle (json/read-str text :key-fn keyword))]
        {:status (case (:status r) :pass "pass" :unsupported-version "unsupported-version" "rejected")
         :claimable (boolean (:claimable? r))
         :root (get-in r [:derived-claim :claim/json-root])}))))

(def dir (or (System/getenv "FUZZ_CASES_DIR") "/tmp/opencode/fuzz"))
(def manifest (json/read-str (slurp (or (System/getenv "FUZZ_MANIFEST") (str dir "/manifest.json"))) :key-fn keyword))

(doseq [c manifest]
  (let [r (bundle-result (str dir "/" (:case_id c) ".json"))]
    (println (str (:case_id c) "|" (:status r) "|" (:claimable r) "|" (or (:root r) "")))))
