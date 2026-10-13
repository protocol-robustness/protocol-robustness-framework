(ns resolver-sim.benchmark.distributed.result-store
  "Detached-result transport only. Stores do not claim work, grant leases,
  accept completion, or publish canonical benchmark artifacts."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.hash.canonical :as hc]
            [resolver-sim.hash.reference :as hash-ref]
            [resolver-sim.sensitivity.sentinel :as sentinel])
  (:import [java.nio.file Files]
           [java.util UUID]))

(defprotocol ResultStore
  (put-detached-result! [store detached-result]
    "Persist an immutable detached result and return its operational locator.")
  (get-detached-result [store result-ref]
    "Retrieve a previously stored detached result, or nil when unavailable.")
  (verify-detached-result! [store result-ref expected]
    "Read and verify a detached result against its accepted identity tuple."))

(defn sensitivity-root
  "Commit only deterministic sensitivity provenance. The coordinator uses this
   commitment to reject contradictory provenance for otherwise identical bytes."
  [provenance]
  (hash-ref/sha256-ref
   (hc/hash-with-intent {:hash/intent :evidence-content} provenance)))

(defn- require-detached-result! [result]
  (let [required [:result/root :result/manifest-root :sensitivity/provenance]
        missing (vec (remove #(contains? result %) required))]
    (when (seq missing)
      (throw (ex-info "Detached result is missing required identity fields"
                      {:reason :malformed-detached-result :missing missing})))
    (let [provenance (:sensitivity/provenance result)
          root (sensitivity-root provenance)]
      (when (and (:sensitivity/root result) (not= root (:sensitivity/root result)))
        (throw (ex-info "Detached result sensitivity commitment does not match provenance"
                        {:reason :sensitivity-root-mismatch
                         :expected (:sensitivity/root result) :actual root})))
      ;; A filesystem store is a private, run-scoped staging sink. This does
      ;; not authorize later S3/public transports, which must classify their
      ;; own sink explicitly.
      (sentinel/assert-export-allowed!
       (assoc provenance :artifact/root (:result/root result))
       {:sink :sealed-private-workspace})
      (assoc result :sensitivity/root root))))

(defn- safe-root-component [root]
  (when-not (and (string? root) (re-matches #"sha256:[0-9a-f]{64}" root))
    (throw (ex-info "Detached result root is not a canonical SHA-256 reference"
                    {:reason :invalid-detached-result-root :root root})))
  (subs root (count "sha256:")))

(defrecord FilesystemResultStore [root]
  ResultStore
  (put-detached-result! [_ result]
    (let [result (require-detached-result! result)
          component (safe-root-component (:result/root result))
          dir (io/file root component)
          target (io/file dir "detached-result.edn")
          temp (io/file dir (str ".detached-result.tmp-" (UUID/randomUUID)))
          payload (pr-str result)]
      (.mkdirs dir)
      (spit temp payload)
      (try
        ;; createLink is an atomic create-if-absent operation: unlike a move
        ;; implementation it cannot silently replace an accepted object.
        (Files/createLink (.toPath target) (.toPath temp))
        (catch java.nio.file.FileAlreadyExistsException _
          (when-not (= payload (slurp target))
            (throw (ex-info "Content-addressed result root has incompatible detached payload"
                            {:reason :detached-result-root-conflict
                             :result/root (:result/root result)}))))
        (finally (Files/deleteIfExists (.toPath temp))))
      {:result/ref (str component "/detached-result.edn")
       :result/root (:result/root result)
       :result/manifest-root (:result/manifest-root result)
       :sensitivity/root (:sensitivity/root result)}))
  (get-detached-result [_ result-ref]
    (when (and (string? result-ref)
               (not (.isAbsolute (io/file result-ref)))
               (not (.contains result-ref "..")))
      (let [file (io/file root result-ref)]
        (when (.isFile file) (edn/read-string (slurp file))))))
  (verify-detached-result! [this result-ref expected]
    (let [actual (some-> (get-detached-result this result-ref) require-detached-result!)]
      (when-not actual
        (throw (ex-info "Detached result reference is missing or unreadable"
                        {:reason :detached-result-unavailable :result/ref result-ref})))
      (let [identity (select-keys actual [:result/root :result/manifest-root :sensitivity/root])]
        (when-not (= identity (select-keys expected (keys identity)))
          (throw (ex-info "Detached result does not match accepted identity"
                          {:reason :detached-result-identity-mismatch
                           :expected (select-keys expected (keys identity))
                           :actual identity})))
        actual))))

(defn filesystem-store
  "A private/run-scoped staging ResultStore for same-host workers. It is not a
   public artifact sink and never denotes canonical publication."
  [root]
  (->FilesystemResultStore (io/file root)))
