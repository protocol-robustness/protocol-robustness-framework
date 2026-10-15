^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold}}
(ns notebooks.evm-dependency-provenance
  "Walks the pinned EVM libraries through the retained PRF partial-fill bundle."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [nextjournal.clerk :as clerk])
  (:import [java.security MessageDigest]))

;; # EVM dependency provenance
;;
;; This is a reproducibility walkthrough for the PRF → `prf-evm` integration.
;; It checks the retained bundle's file hashes and displays the exact library
;; submodule revisions recorded by the qualification. It does not claim that
;; the archived EVM source tarball contains the submodule source bytes, or that
;; this notebook independently rebuilt the Solidity contracts.

(def evidence-dir "examples/partial-fill/evidence")

(defn- read-json [path]
  (with-open [reader (io/reader path)]
    (json/read reader :key-fn keyword)))

(defn- sha256-file [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [input (io/input-stream file)]
      (let [buffer (byte-array 16384)]
        (loop []
          (let [read-count (.read input buffer)]
            (when (pos? read-count)
              (.update digest buffer 0 read-count)
              (recur))))))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(def manifest (read-json (str evidence-dir "/manifest.json")))
(def toolchain (read-json (str evidence-dir "/toolchain.json")))
(def manifest-artifacts (concat (:inputs manifest) (:retained_evidence manifest)))

(defn- artifact-file [manifest-path]
  (let [prefix "evidence/partial-fill/"]
    (when-not (str/starts-with? manifest-path prefix)
      (throw (ex-info "Unexpected partial-fill evidence path" {:path manifest-path})))
    (io/file "examples/partial-fill/evidence"
             (subs manifest-path (count prefix)))))

(def artifact-checks
  (mapv (fn [{:keys [path sha256]}]
          (let [file (artifact-file path)
                actual (when (.isFile file) (sha256-file file))]
            {:path path
             :expected sha256
             :actual actual
             :status (cond
                       (nil? actual) :missing
                       (= sha256 actual) :match
                       :else :mismatch)}))
        manifest-artifacts))

(def bundle-integrity-valid?
  (and (seq artifact-checks)
       (every? #(= :match (:status %)) artifact-checks)))
(def mismatched-artifacts (filterv #(not= :match (:status %)) artifact-checks))

(def library-pins
  [{:library "forge-std"
    :repository "foundry-rs/forge-std"
    :revision (get-in toolchain [:dependencies :forge-std])}
   {:library "openzeppelin-contracts"
    :repository "OpenZeppelin/openzeppelin-contracts"
    :revision (get-in toolchain [:dependencies :openzeppelin-contracts])}
   {:library "sp1-contracts"
    :repository "succinctlabs/sp1-contracts"
    :revision (get-in toolchain [:dependencies :sp1-contracts])}])

(def evm-root (System/getenv "PRF_EVM_ROOT"))
(def live-submodule-status
  (when (and (some? evm-root) (.isFile (io/file evm-root ".gitmodules")))
    (let [{:keys [exit out]} (shell/sh "git" "-C" evm-root "submodule" "status" "--"
                                       "lib/forge-std" "lib/openzeppelin-contracts" "lib/sp1-contracts")]
      (when (zero? exit)
        (into {}
              (keep (fn [line]
                      (let [[revision path] (str/split (str/trim line) #"\s+" 3)
                            state (subs line 0 1)]
                        (when (and revision path (re-matches #"[0-9a-f]{40}" revision))
                          [(last (str/split path #"/"))
                           {:revision revision
                            :state (if (= state " ") :matches-superproject (keyword state))}]))))
              (str/split-lines out))))))

(def dependency-pin-comparisons
  (mapv (fn [{:keys [library revision]}]
          (let [{live-revision :revision live-state :state} (get live-submodule-status library)]
            {:library library
             :recorded revision
             :live live-revision
             :checkout-state live-state
             :status (cond
                       (nil? live-revision) :not-checked
                       (not= revision live-revision) :mismatch
                       (= live-state :matches-superproject) :match
                       :else :checkout-differs-from-superproject)}))
        library-pins))

^{:nextjournal.clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:fontFamily "system-ui, sans-serif" :maxWidth "1000px" :margin "auto"}}
  [:h1 "Pinned EVM libraries, bound into PRF evidence"]
  [:p "The Solidity project uses Git submodules. The partial-fill qualification records their exact commit IDs in its toolchain evidence; the PRF bundle manifest then hashes that record and the retained source/evidence files."]
  [:div {:style {:padding "14px 18px" :border "1px solid #334155" :borderRadius "8px"
                 :background (if bundle-integrity-valid? "#ecfdf5" "#fef2f2")}}
   [:strong (if bundle-integrity-valid? "Bundle file hashes match" "Bundle has artifact drift")]
   [:span (str " — " (- (count artifact-checks) (count mismatched-artifacts))
               "/" (count artifact-checks) " entries match")]]])

;; ## Library revisions
;;
;; These are immutable source revisions, not version labels. If `PRF_EVM_ROOT`
;; points to a `prf-evm` checkout, the table compares retained evidence with
;; its initialized submodules; otherwise it reports that comparison as
;; unavailable rather than inferring a match.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Library" "Upstream repository" "Recorded commit" "Live checkout" "Comparison"]
  :rows (mapv (fn [{:keys [library repository]} {:keys [recorded live checkout-state status]}]
                [library repository (or recorded "missing") (or live "not checked")
                 (if checkout-state
                   (str (name status) " (" (name checkout-state) ")")
                   (name status))])
              library-pins dependency-pin-comparisons)})

;; ## What the evidence bundle checks
;;
;; Every path is checked byte-for-byte against `manifest.json`; mismatches are
;; shown rather than hidden. The current retained bundle has a README mismatch,
;; so it is intentionally reported as stale rather than passed off as a clean
;; qualification. The pins below are read from the retained toolchain file.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Evidence item" "Integrity status" "SHA-256"]
  :rows (->> artifact-checks
             (filter #(or (str/ends-with? (:path %) "/toolchain.json")
                          (str/ends-with? (:path %) "/manifest.json")
                          (str/ends-with? (:path %) "/evm-source.tar.gz")
                          (str/ends-with? (:path %) "/prf-source.tar.gz")))
             (mapv (fn [{:keys [path actual status]}]
                     [path (name status) actual])))})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(when (seq mismatched-artifacts)
  (clerk/table
   {:head ["Artifact drift" "Manifest SHA-256" "Current file SHA-256"]
    :rows (mapv (fn [{:keys [path expected actual]}] [path expected actual])
                mismatched-artifacts)}))

;; ## Build context and scope
;;
;; The manifest records the compiler and Foundry versions used for this local
;; run. This is dependency/build provenance, not proof of malicious-host
;; resistance or public-chain execution.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Build component" "Recorded value"]
  :rows [["Solidity compiler" (:solc toolchain)]
         ["Foundry" (:forge toolchain)]
         ["SP1 SDK" (:sp1_sdk toolchain)]
         ["EVM base revision" (:evm_base_revision toolchain)]
         ["Qualification scope" (:scope toolchain)]]})

;; ## Reproduce and interpret
;;
;; From the PRF repository root, regenerate a fresh qualification bundle with
;; the documented `examples/partial-fill/run-local.sh` command. The script
;; reads the neighboring `prf-evm` checkout (or `PRF_EVM_ROOT`), records its
;; submodule revisions, and writes a new manifest. This notebook checks the
;; retained bundle only; it does not run Foundry/SP1 or fetch dependencies.
;; Set `PRF_EVM_ROOT` to a `prf-evm` checkout before starting Clerk to compare
;; the three direct library pins with `git submodule status` automatically.
;;
;; The EVM source archive retains `.gitmodules` but deliberately does not embed
;; the submodule working trees. Consequently, this retained bundle binds the
;; claimed commit IDs and archives the owning source, but independent source
;; verification requires fetching those exact commits and checking out the
;; submodules. The project's own qualification/build command performs that
;; stronger check.

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/table
 {:head ["Boundary" "Notebook establishes" "Not established here"]
  :rows [["Manifest integrity" "Per-file SHA-256 matches/mismatches are reported" "Authenticity of the manifest signer"]
         ["Library identity" "Recorded submodule commits are manifest-bound and optionally compared live" "Submodule source bytes embedded in the archive"]
         ["Build provenance" "Compiler, Foundry and SP1 SDK versions are recorded" "A fresh build or hermetic toolchain"]
         ["Execution" "Local qualification scope is retained" "Public-chain execution, host integrity or confidentiality"]]})
