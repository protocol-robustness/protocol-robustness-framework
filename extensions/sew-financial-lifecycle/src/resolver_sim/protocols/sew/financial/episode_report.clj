(ns resolver-sim.protocols.sew.financial.episode-report
  "P5 — insolvency episode report (a RECONSTRUCTION artifact, not a state machine).

   The report is DERIVED from committed source artifacts — assessment roots,
   lifecycle event roots, response decision roots, transition roots — and is
   reproducible: a verifier recomputes both the metrics and :report-root from
   the ordered source artifacts. Metrics can never disagree with history.

   Three distinguishable notions are preserved even though the report exposes
   the first two:
     Economic robustness     — what happened economically.
     Response robustness     — how well the protocol reacted once risk became
                               observable.
     Enforcement integrity   — whether actual transitions matched the decisions
                               (exposed via :transition-authorization-compliance
                               and :unauthorized-transitions).

   SEMANTICS
   - Episode onset = the first lifecycle transition away from :healthy;
     :impairment-onset and :insolvency-onset are recorded separately within the
     SAME episode (an impairment followed by insolvency is not two episodes).
   - Durations use committed assessment/lifecycle cutpoints. An episode that has
     not recovered is explicitly open-ended/as-of, never silently measured to
     report-generation time.
   - Relapse = recovery progress followed by renewed impairment/insolvency
     WITHIN the same episode — not simply several consecutive insolvent
     assessments.
   - decision-compliance = decisions correctly followed / enforceable decisions.
   - transition-authorization-compliance = protected transitions with a valid
     matching :permit / protected transitions.
   - Restriction activation latency = episode onset → first restrictive
     lifecycle state. Protective-action latency = impairment/insolvency onset →
     first actual protective transition. First-denial latency = impairment/
     insolvency onset → first prohibited attempt that was denied (nil if nobody
     attempted a prohibited action — that is not a failure).
   - EVIDENCE-AVAILABILITY RULE: P5 counts only attempted actions for which an
     attempt/decision artifact exists. A denied or invalid response-decision is
     evidence of an attempt; a hypothetical direct mutation blocked before any
     committed artifact cannot be counted. No counts are manufactured from
     absence.")

(def episode-report-version "insolvency-episode-report.v1")

(defn- sha-256-of
  "Stable sha-256 over an ordered seq of strings."
  [lines]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [line lines]
      (.update md (.getBytes (str line "\n") "UTF-8")))
    (let [sb (StringBuilder.)]
      (doseq [b (.digest md)]
        (.append sb (format "%02x" (bit-and b 0xff))))
      (.toString sb))))

(defn report-root
  "Content-addressed root of a report body (version + episode id + subject +
   sources + both robustness maps). Two reports with identical committed
   sources and identical derivations have identical roots."
  [report-body]
  (sha-256-of (map pr-str (sort-by pr-str (seq report-body)))))

;; ── Economic robustness ──────────────────────────────────────────────────────

(defn- next-states
  [lifecycle-state]
  (mapv :next-lifecycle-state (:episode/events lifecycle-state)))

(defn- event-ats
  [lifecycle-state]
  (mapv :event/at (:episode/events lifecycle-state)))

(defn- first-index-where
  [pred coll]
  (first (keep-indexed (fn [i x] (when (pred x) i)) coll)))

(defn- count-relapses
  "Within-episode relapses: a renewed impairment/insolvency/terminal state that
   follows recovery progress (a healthy or recovering state) AFTER the episode
   had already left :healthy. The initial onset is NOT a relapse."
  [states]
  (let [n (count states)]
    (loop [i 0, ever-non-healthy? false, relapses 0]
      (if (>= i n)
        relapses
        (let [s (nth states i)
              prev (when (pos? i) (nth states (dec i)))]
          (if (contains? #{:impaired :insolvent :terminal} s)
            (let [re-entry? (and ever-non-healthy?
                                 (contains? #{:healthy :recovering} prev))]
              (recur (inc i) true (if re-entry? (inc relapses) relapses)))
            (recur (inc i) ever-non-healthy? relapses)))))))

(defn economic-robustness
  "Economic side of the episode, derived from the committed lifecycle event
   chain carried by the head lifecycle state.

   Returns:
     {:episode-onset          cutpoint of the first transition away from :healthy
      :impairment-onset       first :impaired assessment cutpoint (or nil)
      :insolvency-onset       first :insolvent assessment cutpoint (or nil)
      :terminal?              whether the episode reached :terminal
      :max-shortfall-by-token {token amount}
      :recovery-at            committed recovery cutpoint (or nil)
      :episode-open-ended?    true when the episode has not recovered
      :episode-duration       committed cutpoints, as-of if open-ended
      :relapse-count          within-episode regressions}"
  [lifecycle-state]
  (let [states (next-states lifecycle-state)
        ats (event-ats lifecycle-state)
        onset-idx (first-index-where #(not= :healthy %) states)
        impairment-idx (first-index-where #(= :impaired %) states)
        insolvency-idx (first-index-where #(= :insolvent %) states)
        terminal-idx (first-index-where #(= :terminal %) states)
        head-state (:lifecycle/state lifecycle-state)
        recovery-at (:episode/recovery-at lifecycle-state)
        onset-at (when onset-idx (nth ats onset-idx))
        last-at (last ats)
        recovered? (= :healthy head-state)
        duration (cond
                   (nil? onset-at) nil
                   recovered? (if recovery-at (- recovery-at onset-at) (- (or last-at onset-at) onset-at))
                   :else (- (or last-at onset-at) onset-at))]
    {:episode-onset onset-at
     :impairment-onset (when impairment-idx (nth ats impairment-idx))
     :insolvency-onset (when insolvency-idx (nth ats insolvency-idx))
     :terminal? (some? terminal-idx)
     :max-shortfall-by-token (:episode/max-shortfall-by-token lifecycle-state)
     :recovery-at recovery-at
     :episode-open-ended? (and (some? onset-idx) (not recovered?))
     :episode-duration duration
     :relapse-count (count-relapses states)}))

;; ── Response robustness ──────────────────────────────────────────────────────

(defn- first-restrictive-onset
  "The first cutpoint at which policy becomes restrictive (impairment or
   insolvency onset)."
  [econ]
  (let [onsets (keep identity [(:impairment-onset econ) (:insolvency-onset econ)])]
    (when (seq onsets) (apply min onsets))))

(defn- protective-transition?
  "A transition counts as protective when the executed action's declared effects
   include recovery or risk-reduction."
  [attempt]
  (and (:attempt/transition attempt)
       (some #(contains? (:attempt/effects %) %)
             [:recovery-operation :risk-reducing])))

(defn response-robustness
  "Response side of the episode.

   `attempts` is an ordered vector of committed attempt records:
     {:attempt/at <cutpoint>
      :attempt/effects <declared effect set>
      :attempt/decision <:permit|:deny|:invalid>
      :attempt/decision-root <hex>           ; committed decision artifact
      :attempt/transition <evidence|nil>}    ; nil = no transition occurred

   Only attempts WITH a committed artifact are counted (evidence-availability)."
  [attempts econ]
  (let [restrict-at (first-restrictive-onset econ)
        risk-attempts (filter #(contains? (:attempt/effects %) :risk-increasing) attempts)
        risk-denials (filter #(and (contains? (:attempt/effects %) :risk-increasing)
                                   (= :deny (:attempt/decision %))) attempts)
        invalid-attempts (filter #(= :invalid (:attempt/decision %)) attempts)
        unauthorized (filter #(and (:attempt/transition %)
                                   (not= :permit (:attempt/decision %))) attempts)
        denied-executions (filter #(and (= :deny (:attempt/decision %))
                                        (:attempt/transition %)) attempts)
        permit-no-transition (filter #(and (= :permit (:attempt/decision %))
                                           (nil? (:attempt/transition %))) attempts)
        enforceable (count (filter #(#{:permit :deny} (:attempt/decision %)) attempts))
        compliant (count (filter #(or (and (= :permit (:attempt/decision %)) (:attempt/transition %))
                                      (and (= :deny (:attempt/decision %)) (nil? (:attempt/transition %))))
                                 attempts))
        protected-transitions (count (filter :attempt/transition attempts))
        authorized-transitions (count (filter #(and (:attempt/transition %)
                                                    (= :permit (:attempt/decision %))) attempts))
        denied-ats (keep #(when (= :deny (:attempt/decision %)) (:attempt/at %)) attempts)
        protective-ats (keep #(when (protective-transition? %) (:attempt/at %)) attempts)
        first-denial-latency (when (and restrict-at (seq denied-ats))
                               (- (apply min denied-ats) restrict-at))
        protective-latency (when (and restrict-at (seq protective-ats))
                             (- (apply min protective-ats) restrict-at))
        restriction-latency (when (and (:episode-onset econ) restrict-at)
                              (- restrict-at (:episode-onset econ)))]
    {:risk-increasing-attempts (count risk-attempts)
     :risk-increasing-denials (count risk-denials)
     :invalid-authorization-attempts (count invalid-attempts)
     :unauthorized-transitions (count unauthorized)
     :denied-executions (count denied-executions)
     :permitted-without-transition (count permit-no-transition)
     :restriction-activation-latency restriction-latency
     :protective-action-latency protective-latency
     :first-denial-latency first-denial-latency
     :decision-compliance (when (pos? enforceable)
                            (/ (double compliant) (double enforceable)))
     :transition-authorization-compliance (when (pos? protected-transitions)
                                            (/ (double authorized-transitions)
                                               (double protected-transitions)))}))

;; ── Episode report (reconstruction artifact) ─────────────────────────────────

(defn episode-report
  "Build the reproducible insolvency episode report from the committed lifecycle
   head state (which carries the event chain) and the ordered attempt artifacts.

   The :report-root commits version + episode id + subject + all source roots +
   both robustness derivations, so a verifier recomputes everything from the
   ordered source artifacts."
  [subject lifecycle-state attempts]
  (let [events (:episode/events lifecycle-state)
        econ (economic-robustness lifecycle-state)
        resp (response-robustness attempts econ)
        body (-> {:episode-report/version episode-report-version
                  :episode/id (:episode/id lifecycle-state)
                  :episode/subject subject
                  :sources
                  {:assessment-roots (mapv :current-assessment-root events)
                   :lifecycle-event-roots (mapv :event-root events)
                   :response-decision-roots (mapv :attempt/decision-root attempts)
                   :transition-roots (keep (comp :transition/execution-root
                                                 :attempt/transition)
                                           attempts)}
                  :economic-robustness econ
                  :response-robustness resp})]
    (assoc body :report-root (report-root body))))
