(ns scripts.scenarios.add-key
  "CLI utility to add/update researchers in keys/owners.json"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn- get-fingerprint [pub-key-path]
  (let [result (shell/sh "sha256sum" pub-key-path)]
    (if (= 0 (:exit result))
      (first (str/split (:out result) #"\s+"))
      (throw (ex-info "Failed to compute fingerprint" {:error (:err result)})))))

(defn -main [& args]
  (let [arg-map (apply array-map args)
        researcher-id (first args)
        pub-key-path (second args)
        name (get arg-map "--name")
        email (get arg-map "--email")]
    (when (or (nil? researcher-id) (nil? pub-key-path) (nil? name) (nil? email))
      (println "Usage: bb keys:add <researcher_id> <path-to-pubkey> --name <name> --email <email>")
      (System/exit 1))

    (let [owners-file (io/file "fixtures/keys/owners.json")
          owners-data (json/read-str (slurp owners-file) :key-fn keyword)
          fingerprint (get-fingerprint pub-key-path)
          new-owner {:display_name name
                     :email email
                     :public_key pub-key-path
                     :key_fingerprint_sha256 fingerprint
                     :status "active"}
          updated-data (assoc-in owners-data [:owners (keyword researcher-id)] new-owner)]
      
      (spit owners-file (json/write-str updated-data {:indent true}))
      (println (str "Successfully added " researcher-id " to fixtures/keys/owners.json")))))
