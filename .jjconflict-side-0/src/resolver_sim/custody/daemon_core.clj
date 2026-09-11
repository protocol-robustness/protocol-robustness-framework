(ns resolver-sim.custody.daemon-core
  "AUTH-K4 custody daemon core — pure, testable signing/refusal decision.

   The daemon owns the governed private key in a separate process and its OWN
   accepted authoritative state (AUTH-K4-S). It signs only a closed, frozen
   governed-authority-signing-request.v1 whose :authority-state/root equals the
   daemon's accepted head, and refuses otherwise. Currentness and material are
   taken from the daemon's own accepted state — never from a caller-exported
   snapshot.

   AUTH-K4-H: historical-state signing is refused — a request bound to a
   non-current state is not used to mint a new signature."
  (:require [resolver-sim.benchmark.review-governance :as governance]
            [resolver-sim.benchmark.governed-authority-state :as state]
            [resolver-sim.benchmark.governed-authority-signing-request :as k3]
            [resolver-sim.custody.contract :as contract]))

(defn held-key-for
  "Return the held-key entry for [principal-id, signing-key-id], or nil."
  [keyring principal-id signing-key-id]
  (get keyring [principal-id signing-key-id]))

(defn- recompute-request-roots
  "Recompute G/KS/RR roots from the material bodies."
  [material]
  (let [gov (:authority-material/review-governance material)
        ks  (:authority-material/signer-key-set material)
        round (:authority-material/review-round material)]
    {:review-governance/root (governance/governance-root gov)
     :signer-key-set/root (state/signer-key-set-root ks)
     :review-round/root (state/review-round-material-root round)}))

(defn- material-valid?
  "The daemon's accepted material recomputes to its committed roots."
  [material]
  (let [recomputed (recompute-request-roots material)]
    (and (= (:review-governance/root material) (:review-governance/root recomputed))
         (= (:signer-key-set/root material) (:signer-key-set/root recomputed))
         (= (:review-round/root material) (:review-round/root recomputed)))))

(defn signing-decision
  "Decide whether to sign `request` under the daemon's accepted `mirror`
   (:accepted/head + :accepted/material) and its `keyring`.

   Returns {:action :sign :digest ... :private-key ... :public-key-hex ...} or
   {:action :refuse :reason kw}.

   Sign-time rules (all must hold):
     - request is a closed frozen K3 request;
     - :authority-state/root EQUALS the daemon's accepted head (historical new
       signing refused — AUTH-K4-H);
     - request G/KS/RR roots equal those derived from the daemon's accepted
       material;
     - the accepted material recomputes to its committed roots;
     - [principal,key-id] is held, provisioned, operationally enabled;
     - held public key exactly equals the committed signer-key-set public key;
     - principal is constituted; key is governance-eligible; purpose matches.
   The 32-byte K3 signing digest is always derived internally."
  [request mirror keyring]
  (if-not (contract/custody-request? request)
    {:action :refuse :reason :request/malformed}
    (let [material (:accepted/material mirror)]
      (if-not material
        {:action :refuse :reason :authority/state-unavailable}
        (if (not= (:accepted/head mirror) (:authority-state/root request))
          {:action :refuse :reason :authority/state-stale}
          (let [roots (recompute-request-roots material)
                material-ok? (material-valid? material)
                roots-match? (and (= (:review-governance/root request) (:review-governance/root roots))
                                  (= (:signer-key-set/root request) (:signer-key-set/root roots))
                                  (= (:review-round/root request) (:review-round/root roots)))]
            (if-not roots-match?
              {:action :refuse :reason :authority/material-mismatch}
              (if-not material-ok?
                {:action :refuse :reason :authority/material-mismatch}
                (let [principal-id (:principal/id request)
                      key-id (:signing-key/id request)
                      held (held-key-for keyring principal-id key-id)]
                  (cond
                    (nil? held) {:action :refuse :reason :key/not-held}
                    (false? (:enabled? held)) {:action :refuse :reason :key/disabled}
                    (not (contains? (set (map :researcher/id (:review-round/members (:authority-material/review-round material))))
                                    principal-id))
                    {:action :refuse :reason :principal/not-constituted}
                    (not (governance/position-key-valid?
                          (:authority-material/review-governance material) principal-id key-id))
                    {:action :refuse :reason :key/not-governance-eligible}
                    (not= (:purpose request)
                          (:review-round/purpose (:authority-material/review-round material)))
                    {:action :refuse :reason :purpose/mismatch}
                    (not= (:public-key-hex held)
                          (:signing-key/public-key
                           (some #(when (and (= (:researcher/id %) principal-id)
                                             (= (:signing-key/id %) key-id)) %)
                                 (:signer-key-set/entries (:authority-material/signer-key-set material)))))
                    {:action :refuse :reason :key/public-mismatch}
                    :else
                    (let [root (k3/signing-request-root request)
                          digest (k3/signing-digest-bytes root)]
                      {:action :sign
                       :digest digest
                       :signing-request/root root
                       :private-key (:private-key held)
                       :public-key-hex (:public-key-hex held)})))))))))))