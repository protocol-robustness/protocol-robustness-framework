(ns resolver-sim.protocols.sew.narrative
  "Pure functions for generating human-readable scenario run outlines.

   Takes replay results (trace entries enriched with :params) and produces
   formatted narrative strings. No I/O; callers in invariant-runner handle
   printing.

   Entry points:
     scenario-outline  — full outline map for one replay result
     trace-line        — single formatted line for one trace entry"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Normalization helpers
;; ---------------------------------------------------------------------------

(defn- norm
  "Normalize action name: convert underscores to hyphens."
  [action]
  (-> (name action) (str/replace "_" "-")))

(defn- wf-lbl
  "Short workflow label, e.g. wf#0."
  [n]
  (when (some? n) (str "wf#" n)))

(defn- wf-from
  "Extract workflow-id from params or extra."
  [params extra]
  (or (:workflow-id params) (:workflow-id extra)))

(defn- addr->label
  "Return the shortest useful label for an address: use the agent id if
   available from the address→id index, else strip the leading '0x'."
  [addr addr-idx]
  (or (get addr-idx addr) (when addr (str/replace addr #"^0x" ""))))

;; ---------------------------------------------------------------------------
;; Action detail strings
;; ---------------------------------------------------------------------------

(defn- describe-create-escrow
  [{:keys [params extra world result addr-idx]}]
  (let [wf     (wf-from params extra)
        amt    (:amount params)
        token  (or (:token params) "?")
        to-raw (or (:to params) "?")
        to     (addr->label to-raw addr-idx)]
    (if (= result :ok)
      (format "wf#%s  %s %s → %s" (or wf "?") (or amt "?") token to)
      (format "%s %s → %s" (or amt "?") token to))))

(defn- describe-execute-resolution
  [{:keys [params extra world]}]
  (let [wf      (wf-from params extra)
        is-rel  (get params :is-release true)
        state   (get-in world [:live-states wf])
        verdict (if is-rel "release" "refund")
        suffix  (case state
                  :resolved  " → released"
                  :refunded  " → refunded"
                  :disputed  " → pending"
                  "")]
    (str (wf-lbl wf) "  " verdict suffix)))

(defn- describe-escalate-dispute
  [{:keys [params extra]}]
  (let [wf (wf-from params extra)
        lv (:new-level extra)]
    (str (wf-lbl wf) (if lv (format "  → level %d" (int lv)) "  escalated"))))

(defn- describe-generic
  "Fallback: show wf label if applicable, then action."
  [{:keys [action params extra]}]
  (let [wf (wf-from params extra)]
    (str (when wf (str (wf-lbl wf) "  ")) (norm action))))

(defn- describe-detail
  "Return a concise detail string for a trace entry context map."
  [{:keys [action params extra world result addr-idx] :as ctx}]
  (let [wf (wf-from params extra)
        na (norm action)]
    (case na
      "create-escrow"
      (describe-create-escrow ctx)

      "raise-dispute"
      (str (wf-lbl wf) "  pending → disputed")

      "execute-resolution"
      (describe-execute-resolution ctx)

      "execute-pending-settlement"
      (let [state (get-in world [:live-states wf])]
        (str (wf-lbl wf) "  → " (if state (name state) "?")))

      "release"
      (str (wf-lbl wf) "  → released")

      ("sender-cancel" "recipient-cancel")
      (str (wf-lbl wf) "  → cancelled")

      "auto-cancel-disputed"
      (str (wf-lbl wf) "  timeout → cancelled")

      "escalate-dispute"
      (describe-escalate-dispute ctx)

      "rotate-dispute-resolver"
      (let [new-r (addr->label (:new-resolver extra) addr-idx)]
        (str (wf-lbl wf) "  resolver → " (or new-r "?")))

      "challenge-resolution"
      (str (wf-lbl wf) "  challenged")

      "submit-evidence"
      (str (wf-lbl wf) "  evidence submitted")

      "register-stake"
      (format "%s %s staked" (or (:amount params) "?") (or (:token params) "USDC"))

      "withdraw-stake"
      (format "%s %s withdrawn" (or (:amount params) "?") (or (:token params) "USDC"))

      "withdraw-escrow"
      (str (wf-lbl wf) "  funds claimed")

      "withdraw-fees"
      "protocol fees withdrawn"

      "set-paused"
      (str "protocol " (if (get params :paused? true) "PAUSED" "UNPAUSED"))

      "automate-timed-actions"
      (str (wf-lbl wf) "  auto-timed-check")

      "trigger-accrue"
      (str (wf-lbl wf) "  yield accrued")

      "propose-fraud-slash"
      (let [res (addr->label (:resolver-addr params) addr-idx)]
        (format "slash %s on %s" (or (:amount params) "?") (or res "?")))

      "execute-fraud-slash"
      "fraud slash executed"

      "appeal-slash"
      (str (wf-lbl wf) "  slash appealed")

      "resolve-appeal"
      (str (wf-lbl wf) "  appeal " (if (:upheld? params) "upheld" "dismissed"))

      "register-resolver-bond"
      (format "bond stable=%s sew=%s" (or (:stable params) 0) (or (:sew params) 0))

      "register-senior-bond"
      (format "senior bond max=%s" (or (:coverage-max params) "?"))

      "delegate-to-senior"
      (let [s (addr->label (:senior-addr params) addr-idx)]
        (format "delegated %s to senior %s" (or (:coverage params) "?") (or s "?")))

      "set-token-liquidity-crunch"
      (format "%s liquidity-crunch %s"
              (or (:token params) "?") (if (get params :active? true) "ON" "OFF"))

      "set-yield-risk"
      (format "yield-risk %s %s=%s"
              (or (:module-id params) "?") (or (:token params) "?")
              (or (:liquidity-mode params) "?"))

      "claim-deferred-yield"
      "deferred yield claimed"

      ;; Fallback
      (describe-generic ctx))))

;; ---------------------------------------------------------------------------
;; Status rendering
;; ---------------------------------------------------------------------------

(defn- fmt-status
  "Return a short status indicator for a trace entry."
  [{:keys [result error violations]}]
  (case result
    :ok               "✓"
    :invariant-violated (str "✗ VIOLATED " (str/join "," (keys violations)))
    :rejected         (if error
                        (str "✗ :" (name error))
                        "✗ rejected")
    "?"))

;; ---------------------------------------------------------------------------
;; Public: trace-line
;; ---------------------------------------------------------------------------

(defn trace-line
  "Format a single trace entry as one human-readable narrative line.
   agents-by-id  — map of agent-id → agent map (from :agents in result)
   addr-idx      — map of agent-address → agent-id (built by scenario-outline)"
  [entry agents-by-id addr-idx]
  (let [{:keys [seq time agent action params extra world result error violations]} entry
        na       (norm (or action "?"))
        detail   (describe-detail {:action action :params (or params {})
                                   :extra  (or extra {})  :world  world
                                   :result result :addr-idx addr-idx})
        status   (fmt-status {:result result :error error :violations violations})]
    (format "  #%-3d t=%-8d %-12s %-28s %-30s %s"
            (or seq 0) (or time 0) (or agent "?") na detail status)))

;; ---------------------------------------------------------------------------
;; Public: scenario-outline
;; ---------------------------------------------------------------------------

(defn scenario-outline
  "Generate a human-readable outline for one replay result.

   display-name — string shown as the section heading (e.g. \"S02  dr3-dispute-release\")
   result       — replay result map from replay-with-protocol

   Returns {:header String :lines [String] :footer String :separator String}."
  [display-name result]
  (let [agents      (:agents result [])
        agents-idx  (into {} (map (juxt :id identity) agents))
        addr-idx    (into {} (map (fn [a] [(:address a) (:id a)]) agents))
        trace       (:trace result [])
        metrics     (:metrics result {})
        outcome     (:outcome result)
        steps       (count trace)
        reverts     (get metrics :reverts 0)
        violations  (get metrics :invariant-violations 0)

        w        72
        separator (apply str (repeat w "─"))
        pad-len  (max 0 (- w 6 (count (str display-name))))
        header   (str "  ── " display-name " " (apply str (repeat pad-len "─")))
        lines    (mapv #(trace-line % agents-idx addr-idx) trace)

        outcome-sym (if (= :pass outcome) "✓ PASS" "✗ FAIL")
        footer (str "  " outcome-sym
                    (format "  %d step%s  %d revert%s"
                            steps    (if (= 1 steps)    "" "s")
                            reverts  (if (= 1 reverts)  "" "s"))
                    (when (pos? violations)
                      (format "  %d violation%s" violations
                              (if (= 1 violations) "" "s"))))]
    {:header    header
     :lines     lines
     :footer    footer
     :separator separator}))
