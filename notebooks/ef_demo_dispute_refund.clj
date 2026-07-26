;; # Ethereum Foundation Demo — Protocol State Transition with Evidence
;;
;; **Scenario:** Escrow → Dispute → Resolution (Refund)
;;
;; **Framework:** Protocol Robustness Framework (PRF) — adversarial multi-actor scenario testing
;; for escrow and dispute-resolution protocols.
;;
;; **What this demonstrates:**
;; 1. A protocol state transition (`S03_dr3-dispute-refund`)
;; 2. Content-addressed evidence capture with canonical SHA-256 hashing
;; 3. Robustness claims verified against replay results and invariants
;; 4. Deterministic replay reproducibility
;;
;; **Data sources loaded at render time:**
;; - `scenarios/edn/S03_dr3-dispute-refund.edn` — scenario definition
;; - `prf-runs/<latest-run>/` — persisted evidence artifacts (from `bb run:scenario`)

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :fold :result :show}}
(ns notebooks.ef-demo-dispute-refund
  (:require [nextjournal.clerk :as clerk]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [resolver-sim.protocols.sew.types :as t]
            [resolver-sim.io.scenarios :as io-sc]
            [resolver-sim.hash.canonical :as hc]))

;; ===========================================================================
;; 1. Scenario Definition
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def scenario-path "scenarios/edn/S03_dr3-dispute-refund.edn")

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def scenario-data
  (-> (io-sc/load-scenario-file scenario-path)
      (update :events (fn [es]
                        (mapv (fn [e]
                                (assoc e
                                  :action-label
                                  (case (:action e)
                                    "create_escrow" "Create Escrow"
                                    "raise_dispute" "Raise Dispute"
                                    "execute_resolution" "Execute Resolution"
                                    (:action e))))
                              es)))))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :color "#e2e8f0" :padding "28px"
                :fontFamily "Inter, JetBrains Mono, sans-serif"}}
  [:h1 {:style {:marginTop 0 :color "#ffffff" :fontSize "36px"}}
   "Ethereum Foundation Demo"]
  [:p {:style {:color "#7ADDDC" :fontWeight 700 :fontSize "18px"}}
   "Protocol State Transition — Escrow → Dispute → Resolution (Refund)"]
  [:div {:style {:display "grid" :gap "16px" :marginTop "20px"}}
   [:div {:style {:background "#1e293b" :border "1px solid #334155"
                  :borderRadius "8px" :padding "16px"}}
    [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Scenario: S03 — DR3 Dispute Refund"]
    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr 1fr 1fr" :gap "12px"}}
     [:div [:span {:style {:color "#94a3b8" :fontSize "12px"}} "schema-version"]
      [:br] [:span {:style {:color "#f8fafc"}} "1.0"]]
     [:div [:span {:style {:color "#94a3b8" :fontSize "12px"}} "initial-block-time"]
      [:br] [:span {:style {:color "#f8fafc"}} "1000"]]
     [:div [:span {:style {:color "#94a3b8" :fontSize "12px"}} "resolver-fee-bps"]
      [:br] [:span {:style {:color "#f8fafc"}} "150"]]
     [:div [:span {:style {:color "#94a3b8" :fontSize "12px"}} "max-dispute-duration"]
      [:br] [:span {:style {:color "#f8fafc"}} "2592000"]]]
    [:div {:style {:marginTop "12px" :display "flex" :gap "8px" :flexWrap "wrap"}}
     (for [a (:agents scenario-data)]
       [:span {:key (:id a)
               :style {:background "#0f172a" :border "1px solid #334155"
                       :borderRadius "999px" :padding "4px 12px" :fontSize "13px"}}
        [:span {:style {:color "#7ADDDC"}} (:id a)]
        [:span {:style {:color "#94a3b8"}} (str " (" (:address a) ")")]])]]]])

;; ── Protocol state machine ────────────────────────────────────────────────────

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "EscrowState Transition Graph"]
  [:p {:style {:color "#94a3b8" :fontSize "13px"}}
   "Authoritative state machine. Each arrow represents a valid protocol transition."]
  [:div {:style {:display "flex" :flexDirection "column" :gap "8px" :marginTop "12px"}}
   (for [[state targets] (sort t/allowed-transitions)
         :let [state-label (name state)
               color (case state
                       :none "#94a3b8" :pending "#f59e0b" :disputed "#ef4444"
                       :released "#22c55e" :refunded "#22c55e" :resolved "#8b5cf6")]]
     [:div {:style {:display "flex" :alignItems "center" :gap "8px"}}
      [:span {:style {:background color :color "#0f172a" :fontWeight 700
                      :padding "2px 10px" :borderRadius "4px" :fontSize "13px"
                      :minWidth "80px" :textAlign "center"}}
       state-label]
      [:span {:style {:color "#64748b"}} "→"]
      (if (seq targets)
        (for [t (sort targets)
              :let [tcolor (case t
                             :pending "#f59e0b" :disputed "#ef4444"
                             :released "#22c55e" :refunded "#22c55e" :resolved "#8b5cf6")]]
          [:span {:key (str state "-" t)
                  :style {:background tcolor :color "#0f172a" :fontWeight 700
                          :padding "2px 10px" :borderRadius "4px" :fontSize "13px"}}
           (name t)])
        [:span {:style {:color "#64748b" :fontSize "13px"}} "(terminal)"])])]])

;; ===========================================================================
;; 2. Event Timeline
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def state-path
  {"create_escrow"       {:label "⇢ pending"  :color "#f59e0b"}
   "raise_dispute"       {:label "⇢ disputed" :color "#ef4444"}
   "execute_resolution"  {:label "⇢ refunded" :color "#22c55e"}})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Event Timeline"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "Three events drive the protocol state transition from :none through :pending
    and :disputed to :refunded."]
  [:div {:style {:display "flex" :flexDirection "column" :gap "4px"}}
   (for [i (range (count (:events scenario-data)))
         :let [ev (nth (:events scenario-data) i)
               sp (get state-path (:action ev))]
         :when sp]
     [:div {:style {:display "grid" :gridTemplateColumns "40px 90px 120px 200px"
                    :gap "8px" :padding "6px" :alignItems "center"
                    :background (if (even? i) "#0f172a" "#1e293b")
                    :borderRadius "4px" :fontSize "13px"}}
      [:span {:style {:color "#64748b"}} (str "E" (:seq ev))]
      [:span {:style {:color "#94a3b8"}} (str (:time ev) "s")]
      [:span {:style {:color "#7ADDDC"}} (:action-label ev)]
      [:span {:style {:color (:color sp) :fontWeight 700}} (:label sp)]])
   ;; Arrow showing the full path
   [:div {:style {:textAlign "center" :marginTop "8px" :color "#334155" :fontSize "14px"}}
    "none → pending → disputed → refunded"]]])

;; ===========================================================================
;; 3. Evidence Artifacts (from persisted run)
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn find-latest-run-dir [slug-prefix]
  (let [runs-dir (io/file "prf-runs")]
    (when (.exists runs-dir)
      (->> (.listFiles runs-dir)
           (filter #(.startsWith (.getName %) slug-prefix))
           (sort-by #(.lastModified %) >)
           first))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn load-json [path]
  (try
    (-> path slurp (json/read-str :key-fn keyword))
    (catch java.io.FileNotFoundException _ nil)
    (catch Exception _ nil)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn find-s03-evidence-files
  "Search multiple locations for S03 evidence files. Returns nil if none found."
  []
  (let [locations [(when-let [d (find-latest-run-dir "run-")]
                     [(io/file d "event-evidence")
                      (io/file d "chain-cursor-final.json")
                      (io/file d "evidence-registry.json")
                      (io/file d "evidence-nodes")])
                   [(io/file "results/test-artifacts/event-evidence")
                    (io/file "results/test-artifacts/chain-cursor-final.json")
                    (io/file "results/test-artifacts/evidence-registry.json")
                    (io/file "results/test-artifacts/evidence-nodes")]]
        ;; Check each location for S03 evidence
        s03-pattern #"s03"]
    (loop [locs locations]
      (when (seq locs)
        (let [[ev-dir cc-path reg-path evn-dir] (first locs)]
          (if (and ev-dir (.exists ev-dir))
            (let [all-files (sort (.listFiles ev-dir))
                  s03-files (filter #(re-find s03-pattern (.getName %)) all-files)
                  ;; Load evidence-nodes and find commitment root
                  commitment-root (when (and evn-dir (.exists evn-dir))
                                    (let [node-files (.listFiles evn-dir)]
                                      (some (fn [f]
                                              (let [node (edn/read-string (slurp f))]
                                                (when (= :evidence/commitment-root
                                                         (get-in node [:execution :execution-id]))
                                                  node)))
                                            (vec node-files))))]
              (if (seq s03-files)
                {                 :run-dir (str (.getParentFile (.getParentFile ev-dir)))
                 :event-evidence-dir (.getPath ev-dir)
                 :chain-cursor (when (.exists cc-path) (load-json (.getPath cc-path)))
                 :registry (when (.exists reg-path) (load-json (.getPath reg-path)))
                 :evidence-root (when (and (.exists cc-path) cc-path)
                                  (get (load-json (.getPath cc-path)) :cursor/final-self-hash))
                 :evidence-count (count s03-files)
                 :event-evidence-files (mapv load-json (map #(.getPath %) s03-files))
                 :commitment-root commitment-root}
                (recur (rest locs))))
            (recur (rest locs))))))))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def persisted-evidence
  (delay (find-s03-evidence-files)))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [ev @persisted-evidence]
  (clerk/html
   [:div {:style {:background "#1e293b" :border "1px solid #334155"
                  :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
    [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Evidence Artifacts"]

    (if (nil? ev)
      [:div {:style {:color "#64748b" :fontSize "13px" :padding "12px"
                     :background "#0f172a" :borderRadius "6px"}}
       [:p "No persisted evidence artifacts found. Run the scenario to generate them:"]
       [:pre {:style {:background "#020617" :padding "8px" :borderRadius "4px"
                      :fontSize "13px" :marginTop "8px"}}
        "bb run:scenario scenarios/edn/S03_dr3-dispute-refund.edn -a"]]

      [:div
       [:div {:style {:display "grid" :gridTemplateColumns "repeat(4, 1fr)" :gap "12px"
                      :marginBottom "16px"}}
        [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"}}
         [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"}}
          "Evidence Count"]
         [:div {:style {:fontSize "20px" :fontWeight 800 :color "#f8fafc"}}
          (:evidence-count ev)]]
        [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"}}
         [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"}}
          "Run Dir"]
         [:div {:style {:fontSize "14px" :fontWeight 600 :color "#f8fafc"}}
          (:run-dir ev)]]
        [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"}}
         [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"}}
          "Chain Cursor Final Seq"]
         [:div {:style {:fontSize "20px" :fontWeight 800 :color "#f8fafc"}}
          (get-in ev [:chain-cursor :cursor/final-seq] "?")]]
        [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"}}
         [:div {:style {:color "#94a3b8" :fontSize "11px" :textTransform "uppercase"}}
          "Forensic Evidence Root"]
         [:div {:style {:fontSize "12px" :fontWeight 600 :color "#22c55e"
                        :fontFamily "monospace" :overflow "hidden" :textOverflow "ellipsis"}}
          (str (subs (:evidence-root ev "") 0 24) "...")]]]

       ;; Event evidence records
       [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
        "Event Evidence Records"]
       (for [record (:event-evidence-files ev)]
         [:div {:key (:evidence/hash record)
                :style {:background "#0f172a" :border "1px solid #334155"
                        :borderRadius "6px" :padding "10px" :marginBottom "8px"}}
          [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                         :marginBottom "4px"}}
           [:span {:style {:color "#7ADDDC" :fontWeight 700 :fontSize "13px"}}
            (:evidence/type record)]
           [:span {:style {:color "#94a3b8" :fontSize "11px"}}
            (str "seq " (:evidence/chain-seq record))]]
          [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "4px" :fontSize "12px"}}
           [:div {:style {:color "#64748b"}}
            "self-hash: " [:span {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "11px"}}
                           (str (subs (:evidence/hash record "") 0 16) "...")]]
           [:div {:style {:color "#64748b"}}
            "prev-hash: " [:span {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}}
                            (str (subs (:evidence/chain-prev-hash record "") 0 16) "...")]]
           [:div {:style {:color "#64748b"}}
            "before-hash: " [:span {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}
                                    :title (:world/before-hash record)}
                             (str (subs (or (:world/before-hash record) "") 0 16) "...")]]
           [:div {:style {:color "#64748b"}}
            "after-hash: " [:span {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}
                                   :title (:world/after-hash record)}
                            (str (subs (or (:world/after-hash record) "") 0 16) "...")]]]
          ;; Show attribution context
          (when-let [ctx (:evidence/context record)]
            [:div {:style {:marginTop "6px" :padding "4px 6px" :background "#020617"
                           :borderRadius "4px" :fontSize "11px" :color "#94a3b8"}}
             (str "subject: " (get ctx "subject/type") "/" (get ctx "subject/id")
                  " action: " (get ctx "action/type")
                  " reason: " (get ctx "evidence/reason"))])])

       ;; Chain cursor summary
       [:h4 {:style {:color "#f8fafc" :marginTop "16px" :marginBottom "8px" :fontSize "14px"}}
        "Chain Cursor"]
       (when-let [cc (:chain-cursor ev)]
         [:div {:style {:background "#0f172a" :padding "10px" :borderRadius "6px"
                        :border "1px solid #334155" :fontSize "12px"}}
          [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "8px"}}
           [:div [:span {:style {:color "#64748b"}} "final-seq: "
                  [:span {:style {:color "#e2e8f0"}} (str (:cursor/final-seq cc))]]]
           [:div [:span {:style {:color "#64748b"}} "total-captured: "
                  [:span {:style {:color "#e2e8f0"}} (str (:cursor/total-captured cc))]]]
           [:div {:style {:gridColumn "1 / -1"}}
            [:span {:style {:color "#64748b"}} "final-self-hash: "
             [:span {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "11px"}}
               (:cursor/final-self-hash cc)]]]]])

       ;; Commitment root
       [:div {:style {:marginTop "16px"
                      :background "#0f172a" :border "1px solid #334155"
                      :borderRadius "6px" :padding "12px"}}
        [:h4 {:style {:color "#7ADDDC" :marginTop 0 :marginBottom "8px" :fontSize "14px"}}
         "Commitment Root"]
        (if-let [cr (:commitment-root ev)]
          [:div {:style {:fontSize "12px" :display "grid" :gridTemplateColumns "1fr 1fr" :gap "6px"}}
           [:div {:style {:color "#64748b"}}
            "node-hash: " [:span {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "11px"}}
                           (str (subs (:node-hash cr "") 0 16) "...")]]
           [:div {:style {:color "#64748b"}}
            "parent: " [:span {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}}
                        (-> cr :parent-hashes first (subs 0 24) (str "..."))]]
           [:div {:style {:color "#64748b"}}
            "bootstrap: " [:span {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}}
                           (-> cr :bootstrap-roots first (subs 0 30) (str "..."))]]
           [:div {:style {:color "#64748b"}}
            "status (construction): " [:span {:style {:color "#fbbf24"}}
                                       (str (get-in cr [:result :status]))]]
           [:div {:style {:color "#64748b" :gridColumn "1 / -1"}}
            "execution-status: " [:span {:style (if (= :pass (get-in cr [:outputs :execution/status]))
                                                  {:color "#22c55e"}
                                                  {:color "#ef4444"})}
                                  (str (get-in cr [:outputs :execution/status]))]]
           (when-let [br (get-in cr [:outputs :bundle/root-hash])]
             [:div {:style {:color "#64748b" :gridColumn "1 / -1"}}
              "bundle-root-hash: " [:span {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "11px"}}
                                     br]])]
          [:div {:style {:color "#64748b" :fontSize "13px" :padding "4px 0"}}
           "No commitment root found. Run the scenario with evidence-node emission to generate one."])]])]))

;; ===========================================================================
;; 4. Canonical Hashing Explanation
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Canonical Hashing Pipeline"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "Each protocol transition captures structured evidence: pre-state, post-state,
    inputs, and calculation context. Evidence is finalized with a domain-tagged
    SHA-256 hash and linked in a content-addressed chain."]

  [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, 1fr)" :gap "12px"}}
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"
                  :border "1px solid #334155"}}
    [:h4 {:style {:color "#f8fafc" :marginTop 0 :marginBottom "6px" :fontSize "14px"}}
     "domain-hash"]
    [:pre {:style {:color "#e2e8f0" :fontSize "12px" :background "#020617"
                   :padding "8px" :borderRadius "4px" :overflow "auto" :margin 0}}
     "SHA256(domain_tag || canonical_bytes(value))"]]

   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"
                  :border "1px solid #334155"}}
    [:h4 {:style {:color "#f8fafc" :marginTop 0 :marginBottom "6px" :fontSize "14px"}}
     "Domain Tags"]
    [:div {:style {:fontSize "12px" :color "#94a3b8"}}
     [:div "WORLD_STATE_V1 → world-structure"]
     [:div "EVIDENCE_V1 → evidence-content"]
     [:div "REGISTRY_V1 → evidence-registry"]
     [:div "DECISION_V1 → decision-evidence"]]]]

  [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"
                 :border "1px solid #334155" :marginTop "12px"}}
   [:h4 {:style {:color "#f8fafc" :marginTop 0 :marginBottom "6px" :fontSize "14px"}}
    "Typed Canonical Encoding"]
   [:div {:style {:fontSize "12px" :color "#94a3b8" :display "grid"
                  :gridTemplateColumns "1fr 1fr 1fr" :gap "4px"}}
    [:div "0x00 → null"][:div "0x10 → integer (zigzag LEB128)"][:div "0x20 → string"]
    [:div "0x22 → keyword"][:div "0x30 → array"][:div "0x31 → map (sorted keys)"]
    [:div "0x01 → true"][:div "0x02 → false"][:div "0x40 → byte-array"]]
   [:p {:style {:color "#64748b" :fontSize "11px" :marginTop "4px"}}
    "Map keys are sorted by their canonical byte representation for deterministic ordering."]]])

;; ===========================================================================
;; 5. Canonical Hashing — Live Demo
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn bytes->hex
  "Convert a byte array to a hex string."
  [^bytes ba]
  (let [sb (StringBuilder. (* 2 (count ba)))]
    (doseq [b ba]
      (.append sb (format "%02x" (bit-and b 0xFF))))
    (.toString sb)))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def example-evidence
  {:scenario/id "s03-dr3-dispute-refund"
   :event/seq 0
   :transition/id :escrow/create
   :world/before-hash nil
   :world/after-hash "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b"
   :evidence/type "escrow-created"
   :evidence/importance "core"
   :inputs {:token "USDC" :amount 4000 :resolver "0xresolver"}
   :attestations ["attestation:sha256:a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b"]})

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn compute-domain-hash
  "Compute domain-hash for a value with a given intent."
  [intent-kw value]
  (hc/hash-with-intent {:hash/intent intent-kw} value))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(def live-hash-results
  (delay
    (let [;; Typed encoding examples
          nil-bytes (hc/canonical-bytes nil)
          int-bytes (hc/canonical-bytes 42)
          str-bytes (hc/canonical-bytes "USDC")
          kw-bytes  (hc/canonical-bytes :escrow-state)
          map-bytes (hc/canonical-bytes {:token "USDC" :amount 4000})
          
          ;; Domain hash: evidence content
          evidence-hash-ev (compute-domain-hash :evidence-content example-evidence)
          
          ;; Same evidence map, different intent → different hash
          evidence-hash-reg (compute-domain-hash :registry example-evidence)
          
          ;; World structure hash on minimal world
          ;; (same data used by the protocol's world-state hashing)
          minimal-world {:escrow-transfers {0 {:token "USDC" :amount 4000 :escrow-state :pending}}
                         :total-held {"USDC" 3940}
                         :total-fees {"USDC" 60}
                         :block-time 1000}
          world-structure-hash (compute-domain-hash :world-structure minimal-world)
          
          ;; Determinism: same input → same hash
          world-hash-again (compute-domain-hash :world-structure minimal-world)
          
          ;; Different data → different hash
          other-world {:escrow-transfers {0 {:token "USDC" :amount 5000 :escrow-state :pending}}
                       :total-held {"USDC" 4940}
                       :total-fees {"USDC" 60}
                       :block-time 1000}
          other-world-hash (compute-domain-hash :world-structure other-world)]
      {:typed-encoding
       [{:label "nil"       :tag "0x00" :bytes (bytes->hex nil-bytes)}
        {:label "42"        :tag "0x10" :bytes (bytes->hex int-bytes)}
        {:label "\"USDC\""  :tag "0x20" :bytes (bytes->hex str-bytes)}
        {:label ":escrow-state" :tag "0x22" :bytes (bytes->hex kw-bytes)}
        {:label "{:token \"USDC\" :amount 4000}" :tag "0x31" :bytes (bytes->hex map-bytes)}]
       
       :domain-hashes
       [{:intent "evidence-content" :hash evidence-hash-ev}
        {:intent "registry"         :hash evidence-hash-reg}
        {:intent "world-structure"  :hash world-structure-hash}]
       
       :determinism
       {:world-hash-1 world-structure-hash
        :world-hash-2 world-hash-again
        :identical? (= world-structure-hash world-hash-again)
        :other-world-hash other-world-hash
        :different? (not= world-structure-hash other-world-hash)}})))

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [results @live-hash-results]
  (clerk/html
   [:div {:style {:background "#1e293b" :border "1px solid #334155"
                  :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
   [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Canonical Hashing — Live Demo"]
     [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
      "The following hashes are computed live from the scenario data using the same
       canonical hashing functions used during evidence capture. Every hash is
       deterministic and domain-separated."]
     [:div {:style {:background "#1e3a5f" :border "1px solid #1d4ed8" :borderRadius "6px"
                    :padding "8px" :marginBottom "12px" :fontSize "11px" :color "#93c5fd"}}
      "Evidence DAG nodes carry typed attestation references "
      "(\"attestation:sha256:<64-hex>\") instead of bare IDs, enabling "
      "unambiguous offline resolution through the attestation resolver."]

    ;; ── Typed Encoding ────────────────────────────────────────────────────────
    [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
     "Typed Canonical Encoding (hex dump)"]
    [:p {:style {:color "#94a3b8" :fontSize "12px" :marginBottom "8px"}}
     "Each Clojure value is encoded as type-tag + length-prefixed content bytes.
      The same encoding is used by SHA-256 domain-hash on every evidence record."]
    [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(250px, 1fr))"
                   :gap "6px" :marginBottom "16px"}}
     (for [e (:typed-encoding results)]
       [:div {:key (:label e)
              :style {:background "#0f172a" :padding "8px" :borderRadius "4px"
                      :border "1px solid #334155"}}
        [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"}}
         [:span {:style {:color "#7ADDDC" :fontWeight 600 :fontSize "12px"}} (:label e)]
         [:span {:style {:color "#94a3b8" :fontSize "10px" :fontFamily "monospace"}} (:tag e)]]
        [:div {:style {:color "#e2e8f0" :fontFamily "monospace" :fontSize "10px"
                       :marginTop "4px" :overflow "hidden" :textOverflow "ellipsis"}}
         (:bytes e)]])]

    ;; ── Domain-Separated Hashes ──────────────────────────────────────────────
    [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
     "Domain-Separated SHA-256 (same data, different intent)"]
    [:p {:style {:color "#94a3b8" :fontSize "12px" :marginBottom "8px"}}
     (str "The same evidence map is hashed with three different intents. "
          "Each intent uses a distinct domain tag prefix, producing completely "
          "different hash outputs from the same input data.")]
    [:div {:style {:display "grid" :gridTemplateColumns "1fr" :gap "6px" :marginBottom "16px"}}
     (for [d (:domain-hashes results)]
       [:div {:key (:intent d)
              :style {:display "grid" :gridTemplateColumns "200px 1fr" :gap "8px"
                      :background "#0f172a" :padding "8px" :borderRadius "4px"
                      :border "1px solid #334155" :alignItems "center"}}
        [:div {:style {:color "#7ADDDC" :fontWeight 600 :fontSize "12px"}}
         (str "hash-with-intent :" (:intent d))]
        [:div {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "11px"
                       :overflow "hidden" :textOverflow "ellipsis"}}
         (:hash d)]])]

    ;; ── World Hashes ─────────────────────────────────────────────────────────
    [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
     "World State Hashing"]
    [:p {:style {:color "#94a3b8" :fontSize "12px" :marginBottom "8px"}}
     "The protocol's world state is projected to a canonical structure view before
      hashing. This ensures that runtime-irrelevant state (closures, implementation
      details) does not affect the structural identity hash."]
    [:div {:style {:background "#0f172a" :padding "8px" :borderRadius "4px"
                   :border "1px solid #334155" :marginBottom "12px"}}
     [:div {:style {:color "#94a3b8" :fontSize "11px" :marginBottom "4px"}}
      (str "Input: minimal-world with escrow-transfer (USDC 4000 :pending), "
           "total-held 3940, block-time 1000")]
     [:div {:style {:display "flex" :gap "8px"}}
      [:span {:style {:color "#64748b" :fontSize "11px"}} "hash:"]
      [:span {:style {:color "#22c55e" :fontFamily "monospace" :fontSize "11px"}}
       (:world-structure (first (filter #(= "world-structure" (:intent %)) (:domain-hashes results))))]]]

    ;; ── Determinism ──────────────────────────────────────────────────────────
    [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
     "Determinism Verification"]
    [:p {:style {:color "#94a3b8" :fontSize "12px" :marginBottom "8px"}}
     "Same input always produces the same hash. Different input produces a
      different hash. This is the foundation of the evidence chain's integrity."]
    [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "8px"}}
     [:div {:style {:background (if (get-in results [:determinism :identical?]) "#052e16" "#450a0a")
                    :padding "10px" :borderRadius "6px"
                    :border (str "1px solid " (if (get-in results [:determinism :identical?]) "#166534" "#7f1d1d"))}}
      [:div {:style {:color "#94a3b8" :fontSize "11px"}} "Same world, hash twice"]
      [:div {:style {:fontFamily "monospace" :fontSize "10px" :color "#e2e8f0" :marginTop "4px"}}
       (str (subs (get-in results [:determinism :world-hash-1]) 0 16) "...")]
      [:div {:style {:fontFamily "monospace" :fontSize "10px" :color "#e2e8f0"}}
       (str (subs (get-in results [:determinism :world-hash-2]) 0 16) "...")]
      [:div {:style {:color "#22c55e" :fontSize "12px" :fontWeight 700 :marginTop "4px"}}
       (if (get-in results [:determinism :identical?]) "IDENTICAL ✓" "MISMATCH ✗")]]

     [:div {:style {:background (if (get-in results [:determinism :different?]) "#052e16" "#450a0a")
                    :padding "10px" :borderRadius "6px"
                    :border (str "1px solid " (if (get-in results [:determinism :different?]) "#166534" "#7f1d1d"))}}
      [:div {:style {:color "#94a3b8" :fontSize "11px"}} "Different world, different hash"]
      [:div {:style {:fontFamily "monospace" :fontSize "10px" :color "#e2e8f0" :marginTop "4px"}}
       (str (subs (get-in results [:determinism :world-hash-1]) 0 16) "...")]
      [:div {:style {:fontFamily "monospace" :fontSize "10px" :color "#e2e8f0"}}
       (str (subs (get-in results [:determinism :other-world-hash]) 0 16) "...")]
      [:div {:style {:color "#22c55e" :fontSize "12px" :fontWeight 700 :marginTop "4px"}}
       (if (get-in results [:determinism :different?]) "DIFFERENT ✓" "COLLISION ✗")]]]]))

;; ===========================================================================
;; 6. Robustness Claims (from persisted evidence)
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(defn claim-status [holds?]
  {:color (if holds? "#22c55e" "#ef4444")
   :label (if holds? "✓ PASS" "✗ FAIL")
   :bg (if holds? "#052e16" "#450a0a")
   :border (if holds? "#166534" "#7f1d1d")})

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(let [ev @persisted-evidence
      evidence-root (get-in ev [:chain-cursor :cursor/final-self-hash])
      root-valid? (and (string? evidence-root)
                       (re-matches #"[0-9a-f]{64}" evidence-root))
      chain-seq (get-in ev [:chain-cursor :cursor/final-seq] 0)
      claims
      [{:id :run-found
        :label "Persisted Run Found"
        :description "Evidence artifacts are available from a scenario run"
        :level 1
        :holds? (some? ev)
        :detail (if ev (:run-dir ev) "no run directory found")}
       {:id :evidence-root-present
        :label "Evidence Root Present"
        :description "Chain cursor has a final-self-hash"
        :level 1
        :holds? (some? evidence-root)
        :detail (or evidence-root "nil")}
       {:id :evidence-root-valid-hex
        :label "Evidence Root is Valid SHA-256 Hex"
        :description "64-character hex string matching SHA-256 output"
        :level 1
        :holds? root-valid?
        :detail (str "length: " (count (or evidence-root "")))}
       {:id :evidence-chain-non-empty
        :label "Evidence Chain Non-Empty"
        :description "At least one evidence record in the chain"
        :level 1
        :holds? (pos? chain-seq)
        :detail (str "chain seq: " chain-seq)}
       {:id :event-evidence-present
        :label "Event Evidence Files Present"
        :description "Individual evidence records for each protocol transition"
        :level 1
        :holds? (pos? (count (:event-evidence-files ev [])))
        :detail (str "files: " (count (:event-evidence-files ev [])))}
       {:id :registry-hashes-match-event-evidence
        :label "Registry Contains Event Evidence Hashes"
        :description "Evidence registry aggregates all event evidence hashes"
        :level 2
        :holds? (let [reg-hashes (set (:hashes ev))
                      ev-hashes (set (map :evidence/hash (:event-evidence-files ev)))]
                  (every? reg-hashes ev-hashes))
        :detail (str "registry: " (count (:hashes ev []))
                     " event: " (count (:event-evidence-files ev [])))}
       {:id :chain-linkage-consistent
        :label "Chain Linkage Consistent"
        :description "Each evidence record's chain-prev-hash matches the previous record's self-hash"
        :level 2
        :holds? (let [hashes (map :evidence/hash (:event-evidence-files ev []))]
                  (every? identity
                          (map (fn [record prev-hash]
                                 (= (:evidence/chain-prev-hash record) (or prev-hash "")))
                               (:event-evidence-files ev [])
                               (cons nil hashes))))
        :detail (str "records: " (count (:event-evidence-files ev [])))}]]
  (clerk/html
   [:div {:style {:background "#1e293b" :border "1px solid #334155"
                  :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
    [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Robustness Claims"]
    [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
     (str "Claims evaluated against persisted evidence artifacts. "
          "Level 1: structural/mechanical assertions. "
          "Level 2: semantic claims backed by artifact consistency.")]

    [:div {:style {:display "flex" :gap "12px" :alignItems "center" :marginBottom "16px"}}
     (let [all-hold? (every? :holds? claims)]
       [:span {:style {:fontSize "22px" :fontWeight 800
                       :color (if all-hold? "#22c55e" "#ef4444")}}
        (if all-hold? "ALL CLAIMS PASS"
          (str (count (remove :holds? claims)) " CLAIM FAILURES"))])
     [:span {:style {:color "#94a3b8" :fontSize "13px"}}
      (str "(" (count (filter :holds? claims)) "/" (count claims) " pass)")]]

    [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
     "Level 1 — Mechanical Claims"]
    [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(280px, 1fr))"
                   :gap "8px" :marginBottom "16px"}}
     (for [c (filter #(= 1 (:level %)) claims)]
       (let [s (claim-status (:holds? c))]
         [:div {:key (:id c)
                :style {:background (:bg s) :padding "10px" :borderRadius "6px"
                        :border (str "1px solid " (:border s))}}
          [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"}}
           [:span {:style {:color "#e2e8f0" :fontWeight 700 :fontSize "13px"}} (:label c)]
           [:span {:style {:fontWeight 700 :fontSize "14px" :color (:color s)}} (:label s)]]
          [:div {:style {:color "#94a3b8" :fontSize "11px" :marginTop "2px"}} (:description c)]
          [:div {:style {:color "#64748b" :fontSize "11px" :marginTop "4px" :fontFamily "monospace"}}
           (:detail c)]]))]

    [:h4 {:style {:color "#f8fafc" :marginBottom "8px" :fontSize "14px"}}
     "Level 2 — Consistency Claims"]
    [:div {:style {:display "grid" :gridTemplateColumns "repeat(auto-fill, minmax(280px, 1fr))"
                   :gap "8px"}}
     (for [c (filter #(= 2 (:level %)) claims)]
       (let [s (claim-status (:holds? c))]
         [:div {:key (:id c)
                :style {:background (:bg s) :padding "10px" :borderRadius "6px"
                        :border (str "1px solid " (:border s))}}
          [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"}}
           [:span {:style {:color "#e2e8f0" :fontWeight 700 :fontSize "13px"}} (:label c)]
           [:span {:style {:fontSize "11px" :color "#94a3b8"}} "L2"]]
          [:div {:style {:display "flex" :justifyContent "space-between" :alignItems "center"
                         :marginTop "2px"}}
           [:span {:style {:color "#64748b" :fontSize "12px"}} (:description c)]
           [:span {:style {:fontWeight 700 :fontSize "14px" :color (:color s)}} (:label s)]]
          [:div {:style {:color "#64748b" :fontSize "11px" :marginTop "4px" :fontFamily "monospace"}}
           (:detail c)]]))]]))

;; ===========================================================================
;; 7. Framework Architecture
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Framework Architecture"]
  [:p {:style {:color "#94a3b8" :fontSize "13px" :marginBottom "12px"}}
   "The Protocol Robustness Framework (PRF) is a general-purpose adversarial scenario
    testing engine. Key components used in this demo:"]
  [:div {:style {:display "grid" :gridTemplateColumns "repeat(2, 1fr)" :gap "12px"}}
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Protocol Adapter"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "Sew implements SimulationAdapter + optional EconomicModel, AnalysisModule."
     [:br] [:span {:style {:color "#64748b"}}
            "→ protocols_src/resolver_sim/protocols/sew.clj"]]]
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "State Machine"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "Pure Clojure port of Solidity StateManagementLibrary. Allowed-transitions graph + guard functions."
     [:br] [:span {:style {:color "#64748b"}}
            "→ protocols_src/resolver_sim/protocols/sew/state_machine.clj"]]]
    [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
     [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Evidence DAG Nodes"]
     [:div {:style {:color "#94a3b8" :fontSize "12px"}}
      "Canonical DAG-verifiable execution evidence nodes with content-addressed hashing, DAG parent-linking, and typed attestation references."
      [:br] [:span {:style {:color "#64748b"}}
             "→ src/resolver_sim/evidence/node.clj"]]]
    [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
     [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Evidence Chain"]
     [:div {:style {:color "#94a3b8" :fontSize "12px"}}
      "Linear hash chain of evidence records with seq/prev-hash/self-hash ordering. Backed by the DAG node abstraction for cross-referencing."
      [:br] [:span {:style {:color "#64748b"}}
             "→ src/resolver_sim/evidence/chain.clj"]]]
    [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
     [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Attestation DAG"]
     [:div {:style {:color "#94a3b8" :fontSize "12px"}}
      "DAG-verifiable attestation evidence nodes with typed references (attestation:sha256:<hash>). Parent-hash linking for chain-of-custody."
      [:br] [:span {:style {:color "#64748b"}}
             "→ src/resolver_sim/evidence/attestation_dag.clj"]]]
    [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
     [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Attestation Resolver"]
     [:div {:style {:color "#94a3b8" :fontSize "12px"}}
      "Parse typed references → registry lookup → hash match → type verify → optional signature check. Distinct error types for each failure mode."
      [:br] [:span {:style {:color "#64748b"}}
             "→ src/resolver_sim/evidence/attestation_resolver.clj"]]]
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Canonical Hash"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "Domain-tagged SHA-256 with typed canonical encoding (zigzag LEB128, sorted maps)."
     [:br] [:span {:style {:color "#64748b"}}
            "→ src/resolver_sim/hash/canonical.clj"]]]
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Invariant Engine"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "50+ protocol invariants per transition plus post-hoc. Solvency, states, bonds, disputes, governance."
     [:br] [:span {:style {:color "#64748b"}}
            "→ protocols_src/resolver_sim/protocols/sew/invariants.clj"]]]
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Deterministic Replay"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "Events applied sequentially against pure dispatch functions. Same input → same trace every time."
     [:br] [:span {:style {:color "#64748b"}}
            "→ src/resolver_sim/contract_model/replay.clj"]]]
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "Claim Evaluators"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "Two-level claim system: L1 mechanical (evidence root, replay result) + L2 invariant-backed."
     [:br] [:span {:style {:color "#64748b"}}
            "→ src/resolver_sim/benchmark/claims.clj"]]]
   [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px" :border "1px solid #334155"}}
    [:div {:style {:color "#7ADDDC" :fontWeight 700 :marginBottom "4px"}} "CLI Scenario Runner"]
    [:div {:style {:color "#94a3b8" :fontSize "12px"}}
     "Single-scenario and suite execution with evidence persistence, forensic claims, and DAG recording."
     [:br] [:span {:style {:color "#64748b"}}
            "→ src/resolver_sim/io/scenario_runner.clj"]]]]])

;; ===========================================================================
;; 8. Reproduce
;; ===========================================================================

^{:nextjournal.clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#1e293b" :border "1px solid #334155"
                :borderRadius "8px" :padding "16px" :marginBottom "20px"}}
  [:h3 {:style {:color "#7ADDDC" :marginTop 0}} "Reproduce"]
  [:p {:style {:color "#94a3b8" :fontSize "13px"}}
   "Run this scenario locally to regenerate evidence artifacts and verify the claims."]

  [:div {:style {:background "#0f172a" :borderRadius "6px" :padding "12px" :marginTop "8px"}}
   [:h4 {:style {:color "#f8fafc" :marginTop 0 :marginBottom "8px" :fontSize "14px"}}
    "CLI: Run Scenario"]
   [:pre {:style {:color "#e2e8f0" :fontSize "13px" :background "#020617"
                  :padding "12px" :borderRadius "4px" :overflow "auto"}}
    "bb run:scenario scenarios/edn/S03_dr3-dispute-refund.edn -a"]
   [:p {:style {:color "#94a3b8" :fontSize "12px" :marginTop "4px"}}
    "After running, reload this notebook to see the persisted evidence artifacts."]

   [:h4 {:style {:color "#f8fafc" :marginTop "16px" :marginBottom "8px" :fontSize "14px"}}
    "Verify Evidence Root"]
   [:pre {:style {:color "#e2e8f0" :fontSize "13px" :background "#020617"
                  :padding "12px" :borderRadius "4px" :overflow "auto"}}
    "cat prf-runs/run-*/chain-cursor-final.json | jq -r '.cursor/final-self-hash'"]

   [:h4 {:style {:color "#f8fafc" :marginTop "16px" :marginBottom "8px" :fontSize "14px"}}
    "Explore Evidence Registry"]
   [:pre {:style {:color "#e2e8f0" :fontSize "13px" :background "#020617"
                  :padding "12px" :borderRadius "4px" :overflow "auto"}}
    "cat prf-runs/run-*/evidence-registry.json | jq '{count: .evidence-count, hashes: .evidence-hashes}'"]

   [:h4 {:style {:color "#f8fafc" :marginTop "16px" :marginBottom "8px" :fontSize "14px"}}
    "Replay in Clojure REPL"]
   [:pre {:style {:color "#e2e8f0" :fontSize "13px" :background "#020617"
                  :padding "12px" :borderRadius "4px" :overflow "auto"}}
    "(require '[resolver-sim.protocols.sew :as sew])\n"
    "(require '[resolver-sim.io.scenarios :as io-sc])\n"
    "(def result (sew/replay-with-sew-protocol\n"
    "              (io-sc/load-scenario-file\n"
    "                \"scenarios/edn/S03_dr3-dispute-refund.edn\")\n"
    "              {:allow-dirty? true}))\n"
    "(:outcome result)              ;; → :pass\n"
    "(:events-processed result)     ;; → 3\n"
    "(get-in result [:metrics :invariant-violations])  ;; → 0"]]])
