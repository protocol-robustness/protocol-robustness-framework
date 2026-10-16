(ns resolver-sim.deployment.source-identity-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [resolver-sim.deployment.source-identity :as identity])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(defn- sha256-hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %))
                  (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(deftest source-lock-verification-binds-parent-tree-and-every-file
  (let [root (Files/createTempDirectory "prf-source-identity" (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve root "src/sample.clj")
        lock (.resolve root "source-lock.json")
        parent (apply str (repeat 40 "a"))
        content "(ns sample)\n"
        _ (Files/createDirectories (.getParent source) (make-array java.nio.file.attribute.FileAttribute 0))
        _ (Files/write source (.getBytes content java.nio.charset.StandardCharsets/UTF_8)
                       (make-array java.nio.file.OpenOption 0))
        file-digest (sha256-hex (Files/readAllBytes source))
        tree (sha256-hex (.getBytes (str "src/sample.clj\u0000" file-digest "\n")
                                    java.nio.charset.StandardCharsets/UTF_8))
        lock-value {:prf {:jjWorkingCopyParent parent
                          :sourceTreeSha256 tree
                          :sourceTreeEntries [{:path "src/sample.clj" :sha256 file-digest}]}}
        _ (spit (.toFile lock) (json/write-str lock-value))
        request {:source-root (str root) :source-lock-path (str lock)
                 :expected-tree-sha256 tree :expected-parent parent}]
    (is (= {:source-tree-sha256 tree :source-parent parent :source-file-count 1}
           (identity/verify-source-lock request)))
    (testing "changed source bytes reject the retained release lock"
      (spit (.toFile source) "(ns altered)\n")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"differs from its locked digest"
                            (identity/verify-source-lock request))))
    (testing "a trusted-context parent substitution rejects the release lock"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"release lock identity differs"
                            (identity/verify-source-lock
                             (assoc request :expected-parent (apply str (repeat 40 "b")))))))))

(deftest source-lock-path-traversal-is-rejected
  (let [root (Files/createTempDirectory "prf-source-identity-path" (make-array java.nio.file.attribute.FileAttribute 0))
        lock (.resolve root "source-lock.json")
        parent (apply str (repeat 40 "a"))
        tree (apply str (repeat 64 "0"))
        _ (spit (.toFile lock)
                (json/write-str {:prf {:jjWorkingCopyParent parent :sourceTreeSha256 tree
                                       :sourceTreeEntries [{:path "../outside" :sha256 tree}]}}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsafe relative path"
                          (identity/verify-source-lock
                           {:source-root (str root) :source-lock-path (str lock)
                            :expected-tree-sha256 tree :expected-parent parent})))))
