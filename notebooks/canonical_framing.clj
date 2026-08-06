;; # Consecutive Concatenation, Made Visible
;;
;; **Audience:** Protocol reviewers, conformance testers, implementers of the
;; canonical hash ABI.
;;
;; **Purpose:** Make the consecutive-concatenation invariant of
;; [CANONICAL_HASH_SPEC_V1 §9](/docs/specs/evidence/CANONICAL_HASH_SPEC_V1.md)
;; *visible*. The invariant:
;;
;; > A byte stream formed by concatenating canonical encodings
;; > `encode(v1) || encode(v2) || … || encode(vN)` decodes to **one and only
;; > one** ordered sequence of components.
;;
;; This holds because every canonical encoding is prefix-free: it begins with
;; a single-byte type tag and carries its own length/count prefix, so reading
;; from offset 0 determines each component boundary uniquely — with no gaps,
;; overlaps, or alternative boundary choices.
;;
;; The property tests in `test/resolver_sim/hash/concat_properties_test.clj`
;; assert this black-box. This notebook opens the box: every byte is coloured
;; by its role (`tag` / `len` / `count` / `payload`) and every component
;; boundary is drawn, so the decoding walk is directly inspectable.
;;
;; Backing namespace: `resolver-sim.hash.framing-view`.

^{:nextjournal.clerk/toc true
  :nextjournal.clerk/dark-mode true
  :nextjournal.clerk/visibility {:code :hide}}
(ns notebooks.canonical-framing
  (:require [nextjournal.clerk :as clerk]
            [clojure.string :as str]
            [resolver-sim.hash.framing-view :as fv]
            [resolver-sim.hash.canonical :as hc]))

^{::clerk/visibility {:code :hide :result :hide}}
(def role-colors
  {:tag    "#3b82f6"
   :len    "#22c55e"
   :count  "#a855f7"
   :payload "#475569"})

^{::clerk/visibility {:code :hide :result :hide}}
(def role-labels
  {:tag    "tag"
   :len    "len"
   :count  "count"
   :payload "payload"})

^{::clerk/visibility {:code :hide :result :hide}}
(defn render-frames
  "Render one framed stream as a set of component cards + a decode-walk table."
  [d & [opts]]
  (let [{:keys [frames roles]} d
        byte-table (fv/byte-table d opts)]
    [:div
     ;; ── decode-walk table ──────────────────────────────────────────────
     [:h3 {:style {:color "#e2e8f0" :fontFamily "monospace" :marginTop "8px"}}
      "Decode walk — each boundary is forced by [tag][prefix]"]
     [:table {:style {:borderCollapse "collapse" :fontFamily "monospace"
                      :fontSize "12px" :width "100%" :marginBottom "20px"}}
      [:thead
       [:tr {:style {:color "#94a3b8" :textAlign "left" :borderBottom "1px solid #334155"}}
        [:th {:style {:padding "6px"}} "component"]
        [:th {:style {:padding "6px"}} "offset"]
        [:th {:style {:padding "6px"}} "tag"]
        [:th {:style {:padding "6px"}} "prefix"]
        [:th {:style {:padding "6px"}} "decoded value"]
        [:th {:style {:padding "6px"}} "next offset"]]]
      [:tbody
       (for [{:keys [component offset next tag-name value]} frames]
         (let [role (get roles offset {})
               prefix-role (cond
                             (= :len (:role role)) "length"
                             (= :count (:role role)) "count"
                             :else "—")
               boundary-fixed? (not= :— prefix-role)]
           [:tr {:key component
                 :style {:color "#e2e8f0" :borderBottom "1px solid #1e293b"}}
            [:td {:style {:padding "6px"}} (str "#" component)]
            [:td {:style {:padding "6px"}} (str offset)]
            [:td {:style {:padding "6px"}} (str (name tag-name))]
            [:td {:style {:padding "6px" :color "#a855f7"}}
             (if boundary-fixed? (str prefix-role " ⇒ boundary fixed") "no prefix")]
            [:td {:style {:padding "6px" :color "#f59e0b"}} (pr-str value)]
            [:td {:style {:padding "6px"}} (str next)]]))]]

     ;; ── per-component byte cards ───────────────────────────────────────
     [:h3 {:style {:color "#e2e8f0" :fontFamily "monospace" :marginTop "8px"}}
      "Byte layout — colour = role, semantic path below, boundaries between components"]
     (for [f frames]
       (let [span (range (:offset f) (:next f))
             cells (mapv (fn [i]
                           (let [{:keys [hex role path type slot]} (nth byte-table i)]
                             {:i i :hex hex :role role :path path :type type :slot slot}))
                         span)]
         [:div {:key (:component f)
                :style {:background "#0f172a" :border "1px solid #1e293b"
                        :borderRadius "6px" :padding "10px 12px"
                        :marginBottom "10px" :fontFamily "monospace" :fontSize "12px"}}
          [:div {:style {:color "#94a3b8" :marginBottom "8px"}}
           [:span {:style {:color "#22c55e" :fontWeight 700}}
            (str "#" (:component f) "  " (name (:tag-name f)))
            "  "]
           [:span {:style {:color "#e2e8f0"}}
            (str "offsets " (:offset f) "–" (dec (:next f))
                 "   →   " (pr-str (:value f)))]]
          (when (and (= :map (:tag-name f)) (seq (:map-keys f)))
            [:div {:style {:color "#a855f7" :marginBottom "8px"}}
             (str "canonical key order: " (pr-str (:map-keys f))
                  "   key bytes: " (apply str (interpose " " (:map-key-bytes f))))])
          [:div {:style {:display "flex" :gap "4px" :flexWrap "wrap"}}
           (for [{:keys [i hex role path]} cells]
             [:div {:key i
                    :style {:background (get role-colors role "#1e293b")
                            :borderRadius "4px" :padding "3px 5px"
                            :textAlign "center"}}
              [:div {:style {:color "#ffffff" :fontWeight 700}} hex]
              [:div {:style {:color "rgba(255,255,255,0.7)" :fontSize "9px"}}
               (str (get role-labels role "?")
                    (when (seq path) (str " " (apply str (interpose "," path)))))]])]]))]))

;; ---
;; ## 1. A mixed sequence
;;
;; Six different component types concatenated end-to-end. The stream below is
;; exactly `encode(1) || encode("active") || encode(:a/b) || encode([1 2])
;; || encode({:x 1})` — no separators, no terminator. The decoder still
;; recovers the sequence *and* the boundaries, because every component is
;; self-framing.

^{::clerk/visibility {:code :fold :result :show}}
(def worked
  (fv/decompose-values [1 "active" :a/b [1 2] {:x 1}]))

^{::clerk/visibility {:code :hide :result :show}}
(render-frames worked)

;; ### 1.1 The raw stream, as bytes
;;
;; The full concatenated byte stream (hex). Note that a byte stream alone
;; *looks* ambiguous — the framing is what makes it unambiguous.

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "6px"
                :fontFamily "monospace" :fontSize "13px" :color "#e2e8f0"}}
  (apply str (map (fn [b] (format "%02x " (bit-and b 0xff)))) (:stream worked))
  [:div {:style {:color "#64748b" :fontSize "11px" :marginTop "6px"}}
   (str (:total-bytes worked) " bytes · "
        (count (:frames worked)) " components · one forced decode")]])

;; ### 1.2 Why a vector is *not* naive concatenation
;;
;; A vector is framed as `[0x30][count][element…element]`. Its canonical bytes
;; are therefore never equal to `encode(a)||encode(b)||encode(c)` — the count
;; prefix and the nested type tags change the byte layout. This is the
;; `framing` property from the test suite, shown as bytes:

^{::clerk/visibility {:code :fold :result :show}}
(def vec-demo
  (fv/decompose-values [[1 2 3]]))

^{::clerk/visibility {:code :hide :result :show}}
(render-frames vec-demo)

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:display "grid" :gridTemplateColumns "1fr 1fr" :gap "12px"
                :fontFamily "monospace" :fontSize "12px"}}
  [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"
                 :color "#e2e8f0"}}
   [:div {:style {:color "#22c55e" :fontWeight 700}} "canonical-bytes([1 2 3])"]
   [:div (apply str (map (fn [b] (format "%02x " (bit-and b 0xff)))) (:stream vec-demo))]]
  [:div {:style {:background "#0f172a" :padding "12px" :borderRadius "6px"
                 :color "#e2e8f0"}}
   [:div {:style {:color "#ef4444" :fontWeight 700}} "encode(1)||encode(2)||encode(3)"]
   [:div (apply str (map (fn [b] (format "%02x " (bit-and b 0xff))))
                (fv/concat-bytes (map hc/canonical-bytes [1 2 3])))]]])

;; ## 2. Re-slicing the stream anywhere: only one answer
;;
;; If we cut the stream at *any* component boundary and decode both halves,
;; the left half yields the components before the cut and the right half
;; yields the components after it — no reconstruction from context is needed.
;; Cutting *inside* a component fails loudly, because the prefix-free framing
;; admits no partial component. Try each boundary in the walk table above.

;; ## 3. Extremes
;;
;; Null, booleans, zero, `Long/MIN_VALUE` (whose ZigZag form needs more than
;; 64 bits), multibyte UTF-8, and nested structures all frame the same way:

^{::clerk/visibility {:code :fold :result :show}}
(def extremes
  (fv/decompose-values
   [nil false true 0 -1 Long/MIN_VALUE "\u043F\u0440\u0438\u0432\u0435\u0442"
    [1 [2 [3 [4]]] {:k "v" :j [1 2]} :a.b/c]]))

^{::clerk/visibility {:code :hide :result :show}}
(render-frames extremes)

;; ## 4. Nested paths and map ordering
;;
;; Every byte carries a semantic path (component index + collection indices)
;; and the canonical type of the value holding it.  Map frames expose the
;; canonical key order and the exact key bytes that determined sorting — useful
;; for investigating collection-ordering disagreements.  (The canonical bytes
;; sort keys; the original insertion order is not recoverable from the bytes.)

^{::clerk/visibility {:code :fold :result :show}}
(def map-demo
  (fv/decompose-values [{:zeta 1 :alpha 2 :mid 3}]))

^{::clerk/visibility {:code :hide :result :show}}
(render-frames map-demo)

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "6px"
                :fontFamily "monospace" :fontSize "12px" :color "#e2e8f0"}}
  [:div {:style {:color "#a855f7" :fontWeight 700}}
   "canonical key order: "
   (pr-str (get-in map-demo [:frames 0 :map-keys]))]
  [:div {:style {:color "#64748b" :marginTop "6px"}}
   "key bytes (hex, sorted ascending): "
   (apply str (interpose "  " (get-in map-demo [:frames 0 :map-key-bytes])))]])

;; ## 5. Named sequence framing (canonical-value-sequence.v1)
;;
;; Bare consecutive concatenation is prefix-free but *unbound*: the same bytes
;; can be parsed as different protocol objects.  New commitments use the named
;; contract from `resolver-sim.hash.sequence`:

^{::clerk/visibility {:code :fold :result :show}}
(require '[resolver-sim.hash.sequence :as seq])

^{::clerk/visibility {:code :fold :result :show}}
(def bound
  {:bytes (seq/canonical-sequence-bytes {:purpose :evidence-content} [1 "active"])
   :hash  (seq/sequence-hash {:purpose :evidence-content} [1 "active"])})

^{::clerk/visibility {:code :hide :result :show}}
(clerk/html
 [:div {:style {:background "#0f172a" :padding "14px" :borderRadius "6px"
                :fontFamily "monospace" :fontSize "12px" :color "#e2e8f0"}}
  [:div {:style {:color "#22c55e" :fontWeight 700}}
   "bound-sequence commitment"]
  [:div "contract: " "canonical-value-sequence.v1   purpose: :evidence-content   component-count: 2"]
  [:div {:style {:marginTop "6px"}}
   (apply str (map (fn [b] (format "%02x " (bit-and b 0xff)))) (:bytes bound))]
  [:div {:style {:color "#64748b" :marginTop "6px"}}
   "domain-separated hash: " (:hash bound)]])

;; ## 6. Redacted inspection
;;
;; Payloads and decoded values can be withheld while keeping type, length,
;; count, offsets, boundary reasoning, and a per-payload SHA-256 commitment.
;; Useful when the framing must be inspectable but the content is private:

^{::clerk/visibility {:code :fold :result :show}}
(def redacted-demo
  (fv/decompose-values ["top-secret-value" [1 2]]))

^{::clerk/visibility {:code :hide :result :show}}
(render-frames redacted-demo {:redact-payload? true})

^{::clerk/visibility {:code :fold :result :show}}
(clerk/html
 [:pre {:style {:background "#0f172a" :padding "14px" :borderRadius "6px"
                :fontFamily "monospace" :fontSize "12px" :color "#e2e8f0"
                :whiteSpace "pre"}}
  (clojure.string/join "\n" (fv/dump-lines redacted-demo {:redact? true}))])

;; ## 7. The invariant, three ways
;;
;; The property tests assert the same property from three independent angles:
;;
;; 1. **Round-trip** — `decode(canonical-bytes(v)) == v`
;; 2. **Sequence** — `decode(encode(v1)||…||encode(vN)) == [v1 … vN]`
;; 3. **Injectivity** — distinct component sequences ⇒ distinct byte streams
;; 4. **Prefix-free** — no canonical encoding is a proper prefix of another
;; 5. **Framing** — a vector's framing ≠ naive concatenation of its parts
;;
;; `verify-stream` / `verify-single` enforce the fail-closed side: a stream can
;; be parseable without being canonical (non-minimal varints, noncanonical map
;; order, duplicate keys, truncated lengths, unknown tags), and that is
;; rejected rather than merely displayed.
;;
;; Every frame card above is a witness for properties 2, 4, and 5 at the byte
;; level.
