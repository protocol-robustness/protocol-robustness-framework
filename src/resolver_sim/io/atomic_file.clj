(ns resolver-sim.io.atomic-file
  "Atomic text-file persistence shared by runtime and lifecycle reports.

   Use replace? false for immutable evidence artifacts."
  (:require [clojure.java.io :as io])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException FileAlreadyExistsException
            FileSystemException Files LinkOption StandardCopyOption StandardOpenOption]))

(defn write-text!
  "Write text through a same-directory temporary file and publish it.

   With :replace? false, an existing target is preserved and the operation
   fails. Returns the normalized absolute target Path."
  ([path content] (write-text! path content {}))
  ([path content {:keys [replace?] :or {replace? true}}]
   (let [target (.toAbsolutePath (.normalize (.toPath (io/file (str path)))))
         parent (.getParent target)]
     (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
     (let [temp (Files/createTempFile parent
                                      (str "." (.getFileName target) ".")
                                      ".tmp"
                                      (make-array java.nio.file.attribute.FileAttribute 0))]
       (try
         (Files/write temp (.getBytes (str content) StandardCharsets/UTF_8)
                      (into-array StandardOpenOption [StandardOpenOption/TRUNCATE_EXISTING
                                                      StandardOpenOption/WRITE]))
         (if replace?
           (try
             (Files/move temp target
                         (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                         StandardCopyOption/REPLACE_EXISTING]))
             (catch AtomicMoveNotSupportedException _
               (Files/move temp target
                           (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
           (try
             ;; A hard link provides atomic create-if-absent publication on
             ;; filesystems that support it; fallback moves also omit replace.
             (Files/createLink target temp)
             (catch FileAlreadyExistsException error
               (throw error))
             (catch UnsupportedOperationException _
               (Files/move temp target (make-array java.nio.file.CopyOption 0)))
             (catch FileSystemException _
               (if (Files/exists target (make-array LinkOption 0))
                 (throw (FileAlreadyExistsException. (str target)))
                 (Files/move temp target (make-array java.nio.file.CopyOption 0))))))
         (finally
           (Files/deleteIfExists temp)))
       target))))
