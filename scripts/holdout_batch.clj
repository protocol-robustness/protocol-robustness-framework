(ns scripts.holdout-batch
  "G9c holdout batch verifier.")
(require '[clojure.data.json :as json]
         '[resolver-sim.conformance.bundle :as bundle]
         '[resolver-sim.conformance.crypto :as crypto]
         '[resolver-sim.conformance.json :as json-scan])

(defn- unhex [h]
  (byte-array (map (fn [[a b]] (Integer/parseInt (str a b) 16))
                   (re-seq #"[0-9a-f]{2}" h))))

(defn- bundle-result [path]
  (let [text (slurp path)
        duplicate (json-scan/duplicate-json-key text)]
    (if duplicate
      {:status "rejected" :claimable false}
      (let [r (bundle/verify-bundle (json/read-str text :key-fn keyword))]
        {:status (if (= :pass (:status r)) "pass" "rejected")
         :claimable (boolean (:claimable? r))}))))

(defn- crypto-result [path]
  (let [m (json/read-str (slurp path) :key-fn keyword)
        m (-> m
              (update :signature/preimage unhex)
              (update :signature/value unhex)
              (update :signer/public-key unhex)
              (update :signer/id keyword)
              (update :signature/algorithm keyword)
              (update :signature/domain keyword)
              (update :artifact-kind keyword)
              (update :trust-policy/keys
                      (fn [ks] (into {}
                                     (map (fn [[sid info]]
                                            [sid (-> info
                                                     (update :key/status keyword)
                                                     (update :key/authorised-kinds #(mapv keyword %)))])
                                          ks)))))]
    {:status (if (= :pass (:verification/status (crypto/verify-signature m))) "pass" "rejected")
     :claimable (boolean (= :pass (:verification/status (crypto/verify-signature m))))}))

(def dir (or (first *command-line-args*) "etc/conformance/holdout"))
(def manifest (json/read-str (slurp (str dir "/manifest.json")) :key-fn keyword))

(doseq [c manifest
        :when (some #{(keyword "clojure")} (map keyword (:required_verifiers c)))]
  (let [r (if (= "crypto" (:kind c))
            (crypto-result (str dir "/" (:path c)))
            (bundle-result (str dir "/" (:path c))))]
    (println (str (:case_id c) "|" (:status r) "|" (:claimable r)))))
