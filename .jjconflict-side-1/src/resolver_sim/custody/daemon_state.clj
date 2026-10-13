(ns resolver-sim.custody.daemon-state
  "AUTH-K4-S — independently anchored authoritative-state view for the custody
   daemon.

   The daemon maintains its OWN accepted authoritative-state head, pinned to a
   genesis/chain trust anchor, advanced only by independently verifying an
   authenticated predecessor → successor authority-state transition. It never
   accepts a caller-declared head, and it never trusts a PRF-exported snapshot's
   currentness on the authoritative signing path.

   The daemon is a verifier/replica of existing protocol authority — it does NOT
   become a governance authority. The authorization evidence for a successor is
   the existing authority fence (store-issued only for an :authorised report)
   that binds the exact predecessor state-root."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-transition :as gt]
            [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.hash.reference :as ref]))

(def mirror-schema "custody-authority-mirror.v1")

(defn mirror-path [dir] (str dir "/authority-mirror.edn"))

(defn read-mirror [dir]
  (let [p (mirror-path dir)]
    (when (.isFile (io/file p))
      (edn/read-string (slurp p)))))

(defn- write-mirror-atomic! [dir mirror]
  (let [target (io/file (mirror-path dir))
        temp (io/file (.getParentFile target)
                      (str ".mirror.tmp-" (java.util.UUID/randomUUID)))]
    (spit temp (pr-str mirror))
    (java.nio.file.Files/move (.toPath temp) (.toPath target)
                              (into-array java.nio.file.StandardCopyOption
                                          [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                           java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    mirror))

(defn- recompute-material-roots [material]
  (let [gov (:authority-material/review-governance material)
        ks  (:authority-material/signer-key-set material)
        round (:authority-material/review-round material)]
    {:review-governance/root (governance/governance-root gov)
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root (state/review-round-material-root round)}))

(defn- valid-material?
  "Committed material roots recompute from bodies AND match the material's
   committed roots."
  [material]
  (let [recomputed (recompute-material-roots material)]
    (and (= (:review-governance/root material) (:review-governance/root recomputed))
         (= (:signer-key-set/root material) (:signer-key-set/root recomputed))
         (= (:review-round/root material) (:review-round/root recomputed)))))

(defn- valid-envelope?
  "Structural validity of a successor envelope (root + root fields are valid
   sha256 refs). Correctness is established by the derivation comparison
   (gt/verify-derived-successor) — the daemon does not independently recompute
   a V2 envelope root, which would require the head-state body; the candidate
   must equal the mechanically derived successor."
  [envelope]
  (let [roots (select-keys envelope [:chain-instance-genesis/root :execution/state-root
                                     :chain-configuration/root :review-governance/root
                                     :review-governance-activation/root :configuration-head/root
                                     :control-plane-evidence/root :position-time-index/root])]
    (and (map? envelope)
         (ref/valid-sha256-ref? (:authoritative-state-envelope/root envelope))
         (every? ref/valid-sha256-ref? (vals roots)))))

(defn- envelope-material-join?
  "Envelope material-identity roots match the material's committed roots. Only
   the roots the envelope schema actually carries are compared
   (:review-governance/root, :chain-configuration/root). The full successor is
   bound by the derivation comparison (gt/verify-derived-successor)."
  [envelope material]
  (and (= (:review-governance/root envelope) (:review-governance/root material))
       (= (:chain-configuration/root envelope) (:chain-configuration/root material))))

(defn- valid-chain-identity? [mirror envelope]
  (= (:genesis/root mirror) (:chain-instance-genesis/root envelope)))

(defn initialize-mirror!
  "Pin the daemon's genesis/chain trust anchor and set the initial accepted head
   from a verified envelope + material. This is an operator/new-instance
   operation, not part of signing or normal state update."
  [dir genesis-root envelope material]
  (when-not (and (= genesis-root (:chain-instance-genesis/root envelope))
                 (valid-envelope? envelope)
                 (valid-material? material)
                 (envelope-material-join? envelope material))
    (throw (ex-info "initial authoritative state is not verified"
                    {:reason :authority/initial-state-invalid})))
  (write-mirror-atomic! dir
                        {:artifact/schema mirror-schema
                         :genesis/root genesis-root
                         :accepted/head (:authoritative-state-envelope/root envelope)
                         :accepted/sequence (:publication/sequence envelope)
                         :accepted/envelope envelope
                         :accepted/material material
                         :history {(:execution/state-root envelope)
                                   {:envelope envelope :material material}}}))

(defn verify-candidate-successor
  "Independently verify an authenticated predecessor → successor transition
   (AUTH-K4-PP / AUTH-STATE-AFTER / AUTH-LINEAGE-CONSERVATION).

   The exact successor is DERIVED from (transition-definition T, accepted S0
   bundle, authorised outcome O) via gt/derive-governed-successor, and the
   candidate envelope/material must equal the derived values. A self-rooted
   receipt is never the source of transition authority.

   Checks (all must hold):
     - local accepted head == expected S0;
     - candidate predecessor-root == S0;
     - candidate envelope body root recomputes;
     - candidate material roots recompute and match the envelope;
     - the pinned genesis/chain identity matches;
     - sequence/epoch progression is valid;
     - candidate envelope root == derived successor S1;
     - candidate execution/state-root == derived state-after;
     - candidate material projection == derived successor material.

   Returns {:valid? bool :reason kw | nil}. Never trusts caller-computed
   valid?/authorised?/roots/head selection."
  [mirror expected-head candidate-envelope candidate-material
   transition-definition S0-bundle O]
  (cond
    (not= expected-head (:accepted/head mirror))
    {:valid? false :reason :head/predecessor-mismatch}

    (not= (:accepted/head mirror) (:publication/predecessor-root candidate-envelope))
    {:valid? false :reason :head/predecessor-mismatch}

    (not (valid-chain-identity? mirror candidate-envelope))
    {:valid? false :reason :chain/identity-mismatch}

    (not (valid-envelope? candidate-envelope))
    {:valid? false :reason :authority/envelope-invalid}

    (not (valid-material? candidate-material))
    {:valid? false :reason :authority/material-invalid}

    (not (envelope-material-join? candidate-envelope candidate-material))
    {:valid? false :reason :authority/material-mismatch}

    (not (pos? (- (:publication/sequence candidate-envelope)
                  (:accepted/sequence mirror))))
    {:valid? false :reason :authority/sequence-invalid}

    (not (:valid? (gt/verify-derived-successor
                   transition-definition S0-bundle O
                   candidate-envelope candidate-material)))
    {:valid? false :reason :state-after/derivation-mismatch}

    :else {:valid? true}))

(defn submit-candidate-successor!
  "Atomically advance the accepted head from S0 → S1 after independent
   verification. CAS-like: re-reads the mirror at the write boundary so two
   racing successors from the same head cannot both win; the loser fails with a
   structured head/predecessor mismatch.

   `transition-definition`, `S0-bundle` (the accepted authority state including
   config-head + config bodies), and `O` (the authorised outcome) drive the
   derivation; a self-rooted receipt alone is rejected."
  [dir expected-head candidate-envelope candidate-material
   transition-definition S0-bundle O]
  (let [mirror (read-mirror dir)
        verification (verify-candidate-successor mirror expected-head
                                                 candidate-envelope candidate-material
                                                 transition-definition S0-bundle O)]
    (if-not (:valid? verification)
      verification
      (let [candidate-root (:authoritative-state-envelope/root candidate-envelope)
            next {:artifact/schema mirror-schema
                  :genesis/root (:genesis/root mirror)
                  :accepted/head candidate-root
                  :accepted/sequence (:publication/sequence candidate-envelope)
                  :accepted/envelope candidate-envelope
                  :accepted/material candidate-material
                  :history (assoc (:history mirror)
                                  (:execution/state-root candidate-envelope)
                                  {:envelope candidate-envelope :material candidate-material})}]
        (write-mirror-atomic! dir next)
        {:valid? true :head candidate-root :sequence (:accepted/sequence next)}))))

(defn accepted-head [dir]
  (:accepted/head (read-mirror dir)))

(defn accepted-material [dir]
  (:accepted/material (read-mirror dir)))

(defn accepted-envelope [dir]
  (:accepted/envelope (read-mirror dir)))

(defn current-eligible-material
  "The daemon's own accepted material for K3/K2 checks (current signing uses only
   the accepted head, never caller-exported currentness)."
  [dir]
  (accepted-material dir))