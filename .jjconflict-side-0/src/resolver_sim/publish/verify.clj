(ns resolver-sim.publish.verify
  "Verification of a published artifact directory.

   A published directory is a directory that was promoted by the out-of-process
   publisher and therefore contains `publication.json` — the signed
   artifact-publish certificate. This module verifies, independently of the
   publisher:

     1. the certificate signature against a trusted key with the
        :artifact-publisher role;
     2. that the certificate's manifest commitment binds the exact bytes of the
        promoted tree (every file re-hashed, publication.json excluded);
     3. that every path the certificate declares as required is present.

   An in-process or unsigned promotion never satisfies verification, because
   the manifest commitment cannot be reproduced without the certificate's
   binding hash, and the signature cannot be forged without the publisher key."
  (:require [clojure.java.io :as io]
            [resolver-sim.publish.contract :as contract]
            [resolver-sim.publish.manifest :as manifest]
            [resolver-sim.signed-external-decision :as sed]))

(def publication-file "publication.json")

(defn- files-under
  "All regular files under a directory, recursively."
  [dir]
  (let [root (io/file dir)]
    (if (.isFile root)
      [root]
      (mapcat (fn [^java.io.File f]
                (if (.isDirectory f) (files-under f) [f]))
              (.listFiles root)))))

(defn entries-under
  "Manifest entries ({:path :sha256}) for every file in a directory except the
   publication certificate itself, with paths relative to the directory."
  [dir]
  (let [root (.toPath (io/file dir))]
    (->> (files-under dir)
         (remove (fn [^java.io.File f]
                   (= publication-file (str (.relativize root (.toPath f))))))
         (map (fn [^java.io.File f]
                {:path (str (.relativize root (.toPath f)))
                 :sha256 (manifest/file-sha256 f)}))
         (vec))))

(defn verify-publication
  "Verify a published artifact directory against a trust policy.

   dir: the promoted directory containing publication.json
   trust-policy: {:trusted-keys [{:key/id :key/public :key/role :artifact-publisher
                                  :key/status :active}]}

   Returns {:valid? true :run-id <...>} or {:valid? false :reason kw :detail ...}.
   Fail-closed on any missing/malformed certificate, signature failure,
   manifest-commit mismatch, or missing required path."
  [dir trust-policy]
  (let [pub-file (io/file dir publication-file)]
    (cond
      (not (and (.exists pub-file) (.isFile pub-file)))
      {:valid? false :reason :publication-missing
       :detail (str "no " publication-file " in " dir)}

      :else
      (let [cert (try (read-string (slurp pub-file))
                      (catch Exception e
                        (throw (ex-info "malformed publication certificate"
                                        {:reason :malformed-certificate
                                         :cause (.getMessage e)}))))]
        (cond
          (not= contract/decision-kind (:artifact/kind cert))
          {:valid? false :reason :unexpected-certificate-kind
           :detail (:artifact/kind cert)}

          :else
          (let [v (sed/verify-envelope cert contract/decision-domain
                                       trust-policy :artifact-publisher)]
            (if-not (:valid? v)
              {:valid? false :reason (:reason v) :detail (:detail v)}
              ;; Re-hash the promoted tree (excluding publication.json) and bind
              ;; it to the certificate's manifest commitment.
              (let [entries (entries-under dir)
                    commit (manifest/manifest-commit (:publish/run-id cert) entries)
                    required-set (set (:publish/required cert))
                    entry-paths (set (map :path entries))
                    missing-required (vec (sort (remove entry-paths required-set)))]
                (cond
                  (not= commit (:publish/manifest-commit cert))
                  {:valid? false :reason :manifest-commit-mismatch
                   :detail {:certificate (:publish/manifest-commit cert)
                            :recomputed commit}}

                  (seq missing-required)
                  {:valid? false :reason :required-path-missing
                   :detail {:paths missing-required}}

                  :else
                  {:valid? true
                   :run-id (:publish/run-id cert)
                   :entry-count (count entries)})))))))))
