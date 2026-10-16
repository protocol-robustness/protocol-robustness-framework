(ns resolver-sim.deployment.source-identity
  "Verify a scoped source lock against the current checkout. The expected root
  and source root must come from an independently trusted deployment context."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files Path]
           [java.security MessageDigest]))

(defn- sha256-hex [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- safe-source-file ^Path [^Path root relative-path]
  (when-not (and (string? relative-path) (not (empty? relative-path))
                 (not (.isAbsolute (Path/of relative-path (make-array String 0))))
                 (not-any? #{"." ".."} (str/split relative-path #"/")))
    (throw (ex-info "source lock contains an unsafe relative path" {:path relative-path})))
  (let [root-real (.toRealPath root (make-array java.nio.file.LinkOption 0))
        file (.normalize (.resolve root relative-path))]
    (when-not (.startsWith file (.normalize root))
      (throw (ex-info "source lock path escapes the configured source root" {:path relative-path})))
    (when-not (Files/isRegularFile file (make-array java.nio.file.LinkOption 0))
      (throw (ex-info "source lock entry is missing or not a regular file" {:path relative-path})))
    (let [real (.toRealPath file (make-array java.nio.file.LinkOption 0))]
      (when-not (.startsWith real root-real)
        (throw (ex-info "source lock entry resolves outside the configured source root" {:path relative-path})))
      real)))

(defn verify-source-lock
  "Recompute every locked file digest and the sorted path-NUL-digest-LF root.
  Input keys: :source-root, :source-lock-path, :expected-tree-sha256,
  :expected-parent. Returns verified identities or throws on any mismatch."
  [{:keys [source-root source-lock-path expected-tree-sha256 expected-parent]}]
  (when-not (and (string? expected-tree-sha256)
                 (re-matches #"[0-9a-f]{64}" expected-tree-sha256)
                 (string? expected-parent)
                 (re-matches #"[0-9a-f]{40}" expected-parent))
    (throw (ex-info "trusted source identity is incomplete or malformed" {})))
  (let [root (.normalize (.toAbsolutePath (.toPath (io/file source-root))))
        lock (json/read-str (slurp (io/file source-lock-path)) :key-fn keyword)
        prf (:prf lock)
        entries (:sourceTreeEntries prf)]
    (when-not (and (vector? entries) (seq entries)
                   (= expected-tree-sha256 (:sourceTreeSha256 prf))
                   (= expected-parent (:jjWorkingCopyParent prf)))
      (throw (ex-info "release lock identity differs from the independently trusted source identity"
                      {:declared-tree (:sourceTreeSha256 prf)
                       :expected-tree expected-tree-sha256
                       :declared-parent (:jjWorkingCopyParent prf)
                       :expected-parent expected-parent})))
    (let [paths (mapv :path entries)]
      (when-not (= paths (vec (sort paths)))
        (throw (ex-info "source lock entries must be sorted by path" {})))
      (when-not (= (count paths) (count (distinct paths)))
        (throw (ex-info "source lock contains duplicate paths" {})))
      (let [records
            (mapv (fn [{:keys [path sha256]}]
                    (when-not (and (string? sha256) (re-matches #"[0-9a-f]{64}" sha256))
                      (throw (ex-info "source lock contains a malformed file digest" {:path path})))
                    (let [actual (sha256-hex (Files/readAllBytes (safe-source-file root path)))]
                      (when-not (= sha256 actual)
                        (throw (ex-info "source file differs from its locked digest"
                                        {:path path :expected sha256 :actual actual})))
                      (str path "\u0000" sha256 "\n")))
                  entries)
            computed (sha256-hex (.getBytes (apply str records) java.nio.charset.StandardCharsets/UTF_8))]
        (when-not (= expected-tree-sha256 computed)
          (throw (ex-info "source lock tree digest does not recompute"
                          {:expected expected-tree-sha256 :actual computed})))
        {:source-tree-sha256 computed
         :source-parent expected-parent
         :source-file-count (count entries)}))))
