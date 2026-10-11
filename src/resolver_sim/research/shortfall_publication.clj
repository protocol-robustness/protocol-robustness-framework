(ns resolver-sim.research.shortfall-publication
  "Signed, offline publication lineage around unchanged challenge/pilot artifacts.
   A pinned origin and retained reader state detect known rollback/forks, not
   unseen equivocation or global latestness. No network or key custody."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [buddy.core.codecs :as codecs]
            [resolver-sim.benchmark.signing :as signing]
            [resolver-sim.hash.canonical :as canonical]
            [resolver-sim.hash.reference :as reference]
            [resolver-sim.io.edn :as pretty]
            [resolver-sim.research.researcher-challenges :as challenges]
            [resolver-sim.research.researcher-challenges-cli :as cli]
            [resolver-sim.research.shortfall-pilot :as pilot])
  (:import [java.nio.channels FileChannel]
           [java.nio.file Files StandardOpenOption]
           [java.security MessageDigest]))

(def schema "researcher-shortfall-publication.v1")
(def files ["configuration.edn" "pilot.edn" "history.edn" "evidence.edn"])
(def controlled-schema "researcher-shortfall-controlled-publication.v1")
(def controlled-files ["configuration.edn" "pilot.edn" "control-policy.edn"
                       "history.edn" "evidence.edn"])

(defn- ensure! [condition reason]
  (when-not condition (throw (ex-info (name reason) {:reason reason}))))

(defn publication-root [publication]
  (reference/sha256-ref
   (canonical/domain-hash schema (dissoc publication :publication/root :publication/signature))))

(defn- envelope! [publication]
  (ensure! (= #{:artifact/schema :operator/public-key :source/revision-root
                :configuration/root :pilot/root :checkpoint :files
                :publication/sequence :publication/previous-root :publication/root}
              (set (keys (dissoc publication :publication/signature)))) :invalid-publication)
  (ensure! (and (= schema (:artifact/schema publication))
                (= (:operator/public-key publication)
                   (challenges/canonical-public-key (:operator/public-key publication)))
                (every? reference/valid-sha256-ref?
                        (map publication [:source/revision-root :configuration/root :pilot/root :publication/root]))
                (integer? (:publication/sequence publication))
                (<= 0 (:publication/sequence publication))
                (if (zero? (:publication/sequence publication))
                  (nil? (:publication/previous-root publication))
                  (reference/valid-sha256-ref? (:publication/previous-root publication)))
                (= (set files) (set (keys (:files publication))))
                (every? reference/valid-sha256-ref? (vals (:files publication)))
                (= #{:configuration/root :network/root :history/root :history/action-count}
                   (set (keys (:checkpoint publication))))
                (= (:configuration/root publication) (get-in publication [:checkpoint :configuration/root]))
                (every? reference/valid-sha256-ref?
                        (map (:checkpoint publication) [:configuration/root :network/root :history/root]))
                (integer? (get-in publication [:checkpoint :history/action-count]))
                (<= 0 (get-in publication [:checkpoint :history/action-count]))
                (= (:publication/root publication) (publication-root publication))) :publication-identity-mismatch)
  publication)

(defn authenticate!
  "Authenticate signed metadata using an independently supplied operator key.
   This alone says nothing about bundle availability, replay or latestness."
  [pinned-key publication]
  (envelope! publication)
  (ensure! (= (challenges/canonical-public-key pinned-key) (:operator/public-key publication)) :wrong-publication-operator)
  (ensure! (and (string? (:publication/signature publication))
                (re-matches #"[0-9a-f]{128}" (:publication/signature publication))
                (signing/verify-signature-with-public-key-content
                 (:publication/root publication) (:publication/signature publication) pinned-key)) :invalid-publication-signature)
  publication)

(defn- material [directory]
  ;; Parse and digest the SAME captured bytes. Only four fixed public files are
  ;; read: no directory walk, private key discovery or arbitrary manifest paths.
  (let [captured (into {} (for [name files]
                            [name (Files/readAllBytes (.toPath (io/file directory name)))]))
        bodies (into {} (for [[name bytes] captured]
                          [name (edn/read-string (String. ^bytes bytes java.nio.charset.StandardCharsets/UTF_8))]))
        config (get bodies "configuration.edn") profile (get bodies "pilot.edn")
        history (get bodies "history.edn") evidence (get bodies "evidence.edn")]
    (ensure! (vector? history) :invalid-publication-history)
    (ensure! (map? evidence) :invalid-publication-evidence)
    (pilot/replay config profile history evidence)
    {:config config :profile profile :history history
     :checkpoint (challenges/history-checkpoint config history)
     :files (into {} (for [[name bytes] captured]
                       [name (reference/sha256-ref
                              (codecs/bytes->hex (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes)))]))}))

(defn- successor! [previous candidate]
  (ensure! (= (select-keys previous [:operator/public-key :source/revision-root :configuration/root :pilot/root])
              (select-keys candidate [:operator/public-key :source/revision-root :configuration/root :pilot/root]))
           :publication-scope-change)
  (ensure! (= (inc (:publication/sequence previous)) (:publication/sequence candidate)) :publication-rollback-or-sequence-gap)
  (ensure! (= (:publication/root previous) (:publication/previous-root candidate)) :publication-predecessor-mismatch))

(defn- history-extension! [previous {:keys [config history]}]
  (let [n (get-in previous [:checkpoint :history/action-count])]
    (ensure! (and (integer? n) (<= 0 n (count history))) :publication-history-rollback)
    (challenges/verify-history-checkpoint config (vec (take n history)) (:checkpoint previous))))

(defn build-publication
  "Derive publication commitments from captured public bundle files. A successor
   requires the signed predecessor. Revision root is declared deployment
   provenance, not a claimed hermetic implementation identity."
  [operator-key source-revision-root directory previous]
  (let [{:keys [config profile checkpoint files] :as bundle} (material directory)
        body {:artifact/schema schema :operator/public-key (challenges/canonical-public-key operator-key)
              :source/revision-root source-revision-root :configuration/root (:artifact/root config)
              :pilot/root (:artifact/root profile) :checkpoint checkpoint :files files
              :publication/sequence (if previous (inc (:publication/sequence previous)) 0)
              :publication/previous-root (:publication/root previous)}
        candidate (assoc body :publication/root (publication-root body))]
    (envelope! candidate)
    (when previous
      (authenticate! operator-key previous)
      (successor! previous candidate)
      (history-extension! previous bundle))
    candidate))

(defn sign-publication [publication private-key-path]
  (envelope! publication)
  (ensure! (not (contains? publication :publication/signature)) :already-signed-publication)
  (let [signed (assoc publication :publication/signature
                      (signing/sign-hash (:publication/root publication) private-key-path nil))]
    (authenticate! (:operator/public-key publication) signed)))

(defn verify-publication!
  "Check actual files, economic replay and checkpoint, then advance retained
   trust. Bootstrap additionally requires an independently pinned origin root.
   Exact replay is idempotent; an older or forked successor is rejected."
  [pinned-key origin-root previous candidate directory]
  (authenticate! pinned-key candidate)
  (let [{:keys [config profile checkpoint files] :as bundle} (material directory)]
    (ensure! (= [(:configuration/root candidate) (:pilot/root candidate) (:checkpoint candidate) (:files candidate)]
                [(:artifact/root config) (:artifact/root profile) checkpoint files]) :publication-material-mismatch)
    (if previous
      (do (authenticate! pinned-key previous)
          (when-not (= (:publication/root previous) (:publication/root candidate))
            (successor! previous candidate)
            (history-extension! previous bundle)))
      (ensure! (and (= origin-root (:publication/root candidate))
                    (zero? (:publication/sequence candidate))) :untrusted-publication-origin))
    (if (and previous (= (:publication/root previous) (:publication/root candidate)))
      previous
      candidate)))

(defn equivocation
  "Retain both signed conflicting successors. No majority/first-wins resolution.
   This checks signed metadata; it does not assert both bundles replay successfully."
  [pinned-key a b]
  (authenticate! pinned-key a)
  (authenticate! pinned-key b)
  (when (and (= (select-keys a [:configuration/root :pilot/root :publication/sequence :publication/previous-root])
                (select-keys b [:configuration/root :pilot/root :publication/sequence :publication/previous-root]))
             (not= (:publication/root a) (:publication/root b)))
    {:status :conflicting :reason :signed-publication-equivocation :publications [a b]}))

(defn advance-file!
  "Persist the reader's last trusted signed publication with the existing
   file-force/atomic-replace primitive under an OS lock. Missing state never
   silently reboots trust: it requires a pinned initial publication root."
  [pinned-key origin-root state-path candidate directory]
  (with-open [channel (FileChannel/open (.toPath (io/file (str state-path ".lock")))
                                        (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
              _lock (.lock channel)]
    (let [previous (when (.exists (io/file state-path)) (edn/read-string (slurp state-path)))
          _ (when (.exists (io/file state-path)) (ensure! (map? previous) :invalid-retained-trust-state))
          verified (verify-publication! pinned-key origin-root previous candidate directory)]
      (when-not (= previous verified) (cli/persist-edn! state-path verified))
      {:publication/root (:publication/root verified) :publication/sequence (:publication/sequence verified)
       :trust/status :verified :trust/changed? (not= previous verified)})))

(defn controlled-publication-root [publication]
  (reference/sha256-ref
   (canonical/domain-hash controlled-schema
                          (dissoc publication :publication/root :publication/signature))))

(defn- controlled-envelope! [publication]
  (ensure! (= #{:artifact/schema :operator/public-key :source/revision-root
                :configuration/root :base-pilot/root :pilot/root :control-policy/root
                :checkpoint :files :publication/sequence :publication/previous-root
                :publication/root}
              (set (keys (dissoc publication :publication/signature))))
           :invalid-controlled-publication)
  (ensure! (and (= controlled-schema (:artifact/schema publication))
                (= (:operator/public-key publication)
                   (challenges/canonical-public-key (:operator/public-key publication)))
                (every? reference/valid-sha256-ref?
                        (map publication [:source/revision-root :configuration/root :base-pilot/root
                                          :pilot/root :control-policy/root :publication/root]))
                (= #{:configuration/root :network/root :history/root :history/action-count
                     :control-policy/root}
                   (set (keys (:checkpoint publication))))
                (= (:configuration/root publication) (get-in publication [:checkpoint :configuration/root]))
                (= (:control-policy/root publication) (get-in publication [:checkpoint :control-policy/root]))
                (every? reference/valid-sha256-ref?
                        (map (:checkpoint publication) [:configuration/root :network/root :history/root]))
                (integer? (get-in publication [:checkpoint :history/action-count]))
                (<= 0 (get-in publication [:checkpoint :history/action-count]))
                (= (set controlled-files) (set (keys (:files publication))))
                (every? reference/valid-sha256-ref? (vals (:files publication)))
                (integer? (:publication/sequence publication))
                (<= 0 (:publication/sequence publication))
                (if (zero? (:publication/sequence publication))
                  (nil? (:publication/previous-root publication))
                  (reference/valid-sha256-ref? (:publication/previous-root publication)))
                (= (:publication/root publication) (controlled-publication-root publication)))
           :controlled-publication-identity-mismatch)
  publication)

(defn authenticate-controlled!
  [pinned-key publication]
  (controlled-envelope! publication)
  (ensure! (= (challenges/canonical-public-key pinned-key) (:operator/public-key publication))
           :wrong-publication-operator)
  (ensure! (and (string? (:publication/signature publication))
                (re-matches #"[0-9a-f]{128}" (:publication/signature publication))
                (signing/verify-signature-with-public-key-content
                 (:publication/root publication) (:publication/signature publication) pinned-key))
           :invalid-publication-signature)
  publication)

(defn- controlled-material [directory]
  (let [captured (into {} (for [name controlled-files]
                            [name (Files/readAllBytes (.toPath (io/file directory name)))]))
        bodies (into {} (for [[name bytes] captured]
                          [name (edn/read-string
                                 (String. ^bytes bytes java.nio.charset.StandardCharsets/UTF_8))]))
        config (get bodies "configuration.edn")
        base-profile (get bodies "pilot.edn")
        policy (get bodies "control-policy.edn")
        history (get bodies "history.edn")
        evidence (get bodies "evidence.edn")]
    (ensure! (and (vector? history) (map? evidence)) :invalid-controlled-publication-material)
    (let [activation-count (:activation/action-count policy)
          _ (ensure! (and (integer? activation-count) (<= 0 activation-count (count history)))
                     :invalid-control-activation)
          activation-history (subvec history 0 activation-count)
          controlled-profile (pilot/build-controlled-profile config base-profile policy activation-history)
          replay (pilot/replay-controlled config controlled-profile policy history evidence)]
      {:config config :base-profile base-profile :policy policy :profile controlled-profile
       :history history
       :checkpoint (challenges/controlled-history-checkpoint config policy history)
       :replay replay
       :files (into {} (for [[name bytes] captured]
                         [name (reference/sha256-ref
                                (codecs/bytes->hex
                                 (.digest (MessageDigest/getInstance "SHA-256") ^bytes bytes)))]))})))

(defn- controlled-successor! [previous candidate]
  (ensure! (= (select-keys previous [:operator/public-key :source/revision-root
                                     :configuration/root :base-pilot/root :pilot/root
                                     :control-policy/root])
              (select-keys candidate [:operator/public-key :source/revision-root
                                      :configuration/root :base-pilot/root :pilot/root
                                      :control-policy/root]))
           :publication-scope-change)
  (ensure! (= (inc (:publication/sequence previous)) (:publication/sequence candidate))
           :publication-rollback-or-sequence-gap)
  (ensure! (= (:publication/root previous) (:publication/previous-root candidate))
           :publication-predecessor-mismatch))

(defn- controlled-history-extension! [previous bundle]
  (let [n (get-in previous [:checkpoint :history/action-count])]
    (ensure! (and (integer? n) (<= 0 n (count (:history bundle))))
             :publication-history-rollback)
    (challenges/verify-controlled-history-checkpoint
     (:config bundle) (:policy bundle) (vec (take n (:history bundle))) (:checkpoint previous))))

(defn build-controlled-publication
  "Build the opt-in successor publication. V1 publication identity/file-set
   remains frozen; this schema explicitly commits the controlled profile and
   reviewer policy instead of treating either as an unrooted option."
  [operator-key source-revision-root directory previous]
  (let [{:keys [config base-profile policy profile checkpoint files] :as bundle}
        (controlled-material directory)
        body {:artifact/schema controlled-schema
              :operator/public-key (challenges/canonical-public-key operator-key)
              :source/revision-root source-revision-root
              :configuration/root (:artifact/root config)
              :base-pilot/root (:artifact/root base-profile)
              :pilot/root (:artifact/root profile)
              :control-policy/root (:artifact/root policy)
              :checkpoint checkpoint :files files
              :publication/sequence (if previous (inc (:publication/sequence previous)) 0)
              :publication/previous-root (:publication/root previous)}
        candidate (assoc body :publication/root (controlled-publication-root body))]
    (controlled-envelope! candidate)
    (when previous
      (authenticate-controlled! operator-key previous)
      (controlled-successor! previous candidate)
      (controlled-history-extension! previous bundle))
    candidate))

(defn sign-controlled-publication [publication private-key-path]
  (controlled-envelope! publication)
  (ensure! (not (contains? publication :publication/signature)) :already-signed-publication)
  (authenticate-controlled!
   (:operator/public-key publication)
   (assoc publication :publication/signature
          (signing/sign-hash (:publication/root publication) private-key-path nil))))

(defn verify-controlled-publication!
  [pinned-key origin-root previous candidate directory]
  (authenticate-controlled! pinned-key candidate)
  (let [{:keys [config base-profile policy profile checkpoint files] :as bundle}
        (controlled-material directory)]
    (ensure! (= [(:configuration/root candidate) (:base-pilot/root candidate)
                 (:pilot/root candidate) (:control-policy/root candidate)
                 (:checkpoint candidate) (:files candidate)]
                [(:artifact/root config) (:artifact/root base-profile) (:artifact/root profile)
                 (:artifact/root policy) checkpoint files])
             :publication-material-mismatch)
    (if previous
      (do (authenticate-controlled! pinned-key previous)
          (when-not (= (:publication/root previous) (:publication/root candidate))
            (controlled-successor! previous candidate)
            (controlled-history-extension! previous bundle)))
      (ensure! (and (= origin-root (:publication/root candidate))
                    (zero? (:publication/sequence candidate)))
               :untrusted-publication-origin))
    (if (and previous (= (:publication/root previous) (:publication/root candidate)))
      previous
      candidate)))

(defn advance-controlled-file!
  "Persist controlled-publication trust separately from V1 reader state."
  [pinned-key origin-root state-path candidate directory]
  (with-open [channel (FileChannel/open (.toPath (io/file (str state-path ".lock")))
                                        (into-array StandardOpenOption
                                                    [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
              _lock (.lock channel)]
    (let [previous (when (.exists (io/file state-path)) (edn/read-string (slurp state-path)))
          _ (when (.exists (io/file state-path))
              (ensure! (map? previous) :invalid-retained-trust-state))
          verified (verify-controlled-publication! pinned-key origin-root previous candidate directory)]
      (when-not (= previous verified) (cli/persist-edn! state-path verified))
      {:publication/root (:publication/root verified)
       :publication/sequence (:publication/sequence verified)
       :trust/status :verified :trust/changed? (not= previous verified)})))

(defn controlled-equivocation [pinned-key a b]
  (authenticate-controlled! pinned-key a)
  (authenticate-controlled! pinned-key b)
  (when (and (= (select-keys a [:operator/public-key :source/revision-root :configuration/root
                                :base-pilot/root :pilot/root
                                :control-policy/root :publication/sequence
                                :publication/previous-root])
                (select-keys b [:operator/public-key :source/revision-root :configuration/root
                                :base-pilot/root :pilot/root
                                :control-policy/root :publication/sequence
                                :publication/previous-root]))
             (not= (:publication/root a) (:publication/root b)))
    {:status :conflicting :reason :signed-publication-equivocation
     :publications [a b]}))

(defn- read-file [path] (edn/read-string (slurp path)))

(defn -main [& args]
  (let [[command & paths] args]
    (println
     (pretty/ppr-str
      (case command
        "prepare" (do (ensure! (<= 3 (count paths) 4) :invalid-prepare-arguments)
                      (let [[directory key revision previous] paths]
                        (build-publication (slurp key) revision directory (when previous (read-file previous)))))
        "sign" (do (ensure! (= 2 (count paths)) :invalid-sign-arguments)
                   (sign-publication (read-file (first paths)) (second paths)))
        "advance" (do (ensure! (<= 4 (count paths) 5) :invalid-advance-arguments)
                      (let [[directory key state candidate origin] paths]
                        (advance-file! (slurp key) origin state (read-file candidate) directory)))
        "equivocation" (do (ensure! (= 3 (count paths)) :invalid-equivocation-arguments)
                           (equivocation (slurp (first paths)) (read-file (second paths)) (read-file (nth paths 2))))
        "controlled-prepare" (do (ensure! (<= 3 (count paths) 4) :invalid-prepare-arguments)
                                 (let [[directory key revision previous] paths]
                                   (build-controlled-publication (slurp key) revision directory
                                                                 (when previous (read-file previous)))))
        "controlled-sign" (do (ensure! (= 2 (count paths)) :invalid-sign-arguments)
                              (sign-controlled-publication (read-file (first paths)) (second paths)))
        "controlled-advance" (do (ensure! (<= 4 (count paths) 5) :invalid-advance-arguments)
                                 (let [[directory key state candidate origin] paths]
                                   (advance-controlled-file! (slurp key) origin state
                                                             (read-file candidate) directory)))
        "controlled-equivocation" (do (ensure! (= 3 (count paths)) :invalid-equivocation-arguments)
                                      (controlled-equivocation (slurp (first paths))
                                                               (read-file (second paths))
                                                               (read-file (nth paths 2))))
        (throw (ex-info "prepare DIR OPERATOR_PUBLIC_KEY SOURCE_REVISION_ROOT [PREVIOUS] | sign PUBLICATION PRIVATE_KEY | advance DIR OPERATOR_PUBLIC_KEY TRUST_STATE PUBLICATION [PINNED_ORIGIN_ROOT] | equivocation OPERATOR_PUBLIC_KEY A B | controlled-prepare DIR OPERATOR_PUBLIC_KEY SOURCE_REVISION_ROOT [PREVIOUS] | controlled-sign PUBLICATION PRIVATE_KEY | controlled-advance DIR OPERATOR_PUBLIC_KEY TRUST_STATE PUBLICATION [PINNED_ORIGIN_ROOT] | controlled-equivocation OPERATOR_PUBLIC_KEY A B" {})))))))
