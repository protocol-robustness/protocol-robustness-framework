(ns resolver-sim.deployment.source-identity-fixture
  "Hermetic, test-only source-lock fixture for deployment preflight tests."
  (:require [clojure.data.json :as json])
  (:import [java.nio.file Files]
           [java.security MessageDigest]))

(defn- sha256-hex [^bytes bytes]
  (apply str (map #(format "%02x" (bit-and 0xff %))
                  (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn create-lock-fixture
  "Create a temporary one-file source tree and a matching lock document.
  This is test data only; it does not represent an approved release identity."
  []
  (let [root (Files/createTempDirectory "prf-source-lock-fixture"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        source (.resolve root "source.clj")
        lock (.resolve root "source-lock.json")
        parent (apply str (repeat 40 "c"))
        content "(ns source.fixture)\n"
        _ (Files/write source (.getBytes content java.nio.charset.StandardCharsets/UTF_8)
                       (make-array java.nio.file.OpenOption 0))
        file-digest (sha256-hex (Files/readAllBytes source))
        tree (sha256-hex (.getBytes (str "source.clj\u0000" file-digest "\n")
                                    java.nio.charset.StandardCharsets/UTF_8))]
    (spit (.toFile lock)
          (json/write-str {:prf {:jjWorkingCopyParent parent
                                 :sourceTreeSha256 tree
                                 :sourceTreeEntries [{:path "source.clj" :sha256 file-digest}]}}))
    ;; JVM deleteOnExit runs in reverse registration order.
    (.deleteOnExit (.toFile root))
    (.deleteOnExit (.toFile source))
    (.deleteOnExit (.toFile lock))
    {:source-revision parent
     :source-tree-sha256 tree
     :source-root (str root)
     :source-lock-path (str lock)}))
