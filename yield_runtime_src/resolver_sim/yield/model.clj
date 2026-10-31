(ns resolver-sim.yield.model
  "Schemas and data shapes for the yield mechanism.")

;; Position accounting shape (share-price / liquidity-index model)
;;
;; At deposit: shares = principal / entry-index (entry share price at open).
;; At accrue:  current-value = shares × current-index
;;             unrealized-yield = current-value − principal
;;
;; `:yield/indices` in world stores the module's current share price /
;; exchange rate / liquidity index — one scalar multiplier per token.
;;
;; {:position/id [:yield/position owner-id module-id token]
;;  :owner/id owner-id
;;  :module/id module-id
;;  :token token
;;  :principal 0          ; deposited underlying amount (base units)
;;  :shares 0             ; position units minted at entry-index
;;  :entry-index 1.0      ; share price / index at deposit
;;  :current-index nil    ; last mark price (set by update-position-yield)
;;  :current-value nil    ; shares × current-index (set by update-position-yield)
;;  :realized-yield 0
;;  :unrealized-yield 0
;;  :yield-loss nil       ; active mark-to-market loss annotation
;;  :status :active}     ; :active, :unwinding, :unwound, :withdrawn

(defn make-position
  [{oid :owner/id mid :module/id :keys [token principal shares entry-index]
    :or {principal 0 shares 0 entry-index 1.0}}]
  {:position/id [:yield/position oid mid token]
   :owner/id oid
   :module/id mid
   :token token
   :principal principal
   :shares shares
   :entry-index entry-index
   :realized-yield 0
   :unrealized-yield 0
   :status :active})
