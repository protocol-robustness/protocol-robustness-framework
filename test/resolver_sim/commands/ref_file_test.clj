(ns resolver-sim.commands.ref-file-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [resolver-sim.commands.ref-file :as ref-file]
            [resolver-sim.hash.reference :as hr]))

(defn- tmp-file [content]
  (let [f (doto (java.io.File/createTempFile "ref-file-test" ".txt")
            (.deleteOnExit))]
    (spit f content)
    (.getPath f)))

(defn- tmp-refs-file [contents]
  (let [f (doto (java.io.File/createTempFile "ref-file-refs" ".txt")
            (.deleteOnExit))]
    (spit f contents)
    (.getPath f)))

;; ── compute mode ──────────────────────────────────────────────────────────────

(deftest compute-refs-prints-canonical-refs
  (let [p1 (tmp-file "hello")
        p2 (tmp-file "world")
        out (with-out-str
              (let [{:keys [exit-code message]}
                    (ref-file/run {:cmd/args [p1 p2]})]
                (is (zero? exit-code))
                (is (= "2 refs computed" message))))
        lines (str/split-lines out)]
    (is (= 2 (count lines)))
    (let [ref (first (str/split (first lines) #"  " 2))]
      (is (true? (hr/valid-sha256-ref? ref))))
    (is (= (str (hr/sha256-ref-file p1) "  " p1) (first lines)))
    (is (= (str (hr/sha256-ref-file p2) "  " p2) (second lines)))))

(deftest compute-refs-missing-file-fails
  (let [p (tmp-file "x")
        {:keys [exit-code]} (ref-file/run {:cmd/args [p hr/nonexistent-file-path]})]
    (is (= 1 exit-code))))

(deftest compute-refs-no-args-usage
  (let [{:keys [exit-code]} (ref-file/run {:cmd/args []})]
    (is (= 2 exit-code))))

;; ── check mode ───────────────────────────────────────────────────────────────

(deftest check-verifies-valid-refs-file
  (let [p (tmp-file "data")
        refs (tmp-refs-file (str (hr/sha256-ref-file p) "  " p "\n"))
        {:keys [exit-code]} (ref-file/run {:check refs})]
    (is (zero? exit-code))))

(deftest check-detects-tampered-file
  (let [p (tmp-file "data")
        refs (tmp-refs-file (str (hr/sha256-ref-file p) "  " p "\n"))]
    (spit p "tampered")
    (let [{:keys [exit-code]} (ref-file/run {:check refs})]
      (is (= 1 exit-code)))))

(deftest check-detects-missing-file
  (let [p (tmp-file "data")
        refs (tmp-refs-file (str (hr/sha256-ref-file p) "  "
                                 hr/nonexistent-file-path "\n"))
        {:keys [exit-code]} (ref-file/run {:check refs})]
    (is (= 1 exit-code))))

(deftest check-rejects-malformed-entry
  (let [refs (tmp-refs-file "not-a-ref  some/file\n")
        {:keys [exit-code]} (ref-file/run {:check refs})]
    (is (= 1 exit-code))))

(deftest check-missing-refs-file
  (let [{:keys [exit-code]} (ref-file/run {:check "/nonexistent/refs.txt"})]
    (is (= 2 exit-code))))
