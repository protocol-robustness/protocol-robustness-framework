(ns resolver-sim.protocols.sew.financial.normalization-fixedpoint-test
  "FIXED-POINT (idempotency) checks for the insolvency normalization helpers.

   Every normalization step must be a fixed point: applying it a second time
   must not change the result, otherwise concatenated roots and action roots
   become unstable across re-normalization (a decision could bind one root while
   enforcement recomputes another).

   Also guards against UNINTENTIONAL TOKEN-SPECIFIC CONCATENATION: no
   normalization or attribution may be hardcoded to a single token."
  (:require [clojure.test :refer [deftest is testing]]
            [resolver-sim.protocols.sew.financial.liabilities :as liab]
            [resolver-sim.protocols.sew.financial.lifecycle :as fl]
            [resolver-sim.protocols.sew.types :as t]))

;; ── Token-key normalization is a fixed point ─────────────────────────────────

(deftest token-key-normalization-is-a-fixed-point
  (testing "string and keyword token maps converge to the SAME normalized rows"
    (let [string-world (assoc (t/empty-world 1000) :total-held {"USDC" 100 "DAI" 200 "BTC" 0})
          keyword-world (assoc (t/empty-world 1000) :total-held {:USDC 100 :DAI 200 :BTC 0})
          rows-a (liab/asset-liability-rows string-world nil)
          rows-b (liab/asset-liability-rows keyword-world nil)]
      (is (= #{:USDC :DAI :BTC} (set (keys rows-a))))
      (is (= rows-a rows-b)
          "normalization reaches a fixed point: string and keyword inputs are one map"))))

(deftest slash-credit-token-is-configurable-not-hardcoded
  (testing "slash credits concatenate into the world's actual stable-asset token"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:slash-credit-liabilities "0xRes"] 300))
          default (liab/slash-credit-liability-by-token world)
          dai-world (assoc-in world [:params :solvency/stable-token] "DAI")
          dai (liab/slash-credit-liability-by-token dai-world)
          dai-kw (assoc-in world [:params :solvency/stable-token] :DAI)
          dai-kw-res (liab/slash-credit-liability-by-token dai-kw)]
      (is (= {:USDC 300} default) "defaults to :USDC (documented stable asset)")
      (is (= {:DAI 300} dai) "string \"DAI\" normalizes into the :DAI bucket")
      (is (= {:DAI 300} dai-kw-res) "keyword :DAI and string \"DAI\" concatenate identically"))))

(deftest slash-credit-concatenates-with-the-same-tokens-liabilities
  (testing "REGRESSION: a DAI slash-credit must be matched against DAI, not USDC"
    (let [world (-> (t/empty-world 1000)
                    (assoc-in [:params :solvency/stable-token] "DAI")
                    (assoc :total-held {"DAI" 1000 "USDC" 500})
                    (assoc-in [:escrow-transfers 0]
                              {:token "DAI" :amount-after-fee 500 :escrow-state :pending})
                    (assoc-in [:slash-credit-liabilities "0xRes"] 300))
          {:keys [per-token]} (liab/economic-liability-set world)]
      (is (= 800 (get per-token :DAI)) "DAI escrow + DAI slash-credit concatenate")
      (is (nil? (get per-token :USDC)) "USDC has no obligations and is untouched"))))

;; ── Action normalization is a fixed point ────────────────────────────────────

(deftest normalize-action-is-a-fixed-point
  (testing "re-normalizing a canonical action preserves params/effects/attributes/root"
    (let [a {:action "create_escrow"
             :params {:token "USDC" :to "0xseller2" :amount 1000
                      :custom-resolver "0xresolver"}}
          once (fl/normalize-action a)
          twice (fl/normalize-action once)]
      (is (= once twice) "normalize-action is a fixed point")
      (is (= {:token "USDC" :beneficiary "0xseller2" :amount 1000
              :resolver "0xresolver"}
             (:action/params twice))
          "canonical :beneficiary/:resolver survive re-normalization")
      (is (= (:action/root once) (:action/root twice))
          "the committed action root is stable across re-normalization"))))

(deftest action-root-is-stable-under-re-normalization
  (let [a {:action "create_escrow"
           :params {:token "USDC" :to "0xseller2" :amount 1000
                    :custom-resolver "0xresolver"}}
        root-raw (fl/action-root a)
        root-norm (fl/action-root (fl/normalize-action a))]
    (is (= root-raw root-norm)
        "a decision and its enforcement recompute the SAME action root")))

(deftest normalizing-different-beneficiaries-keeps-distinct-roots
  (testing "a canonical-vs-scenario key spelling must not collapse distinct actions"
    (let [scenario {:action "create_escrow"
                    :params {:token "USDC" :to "0xa" :amount 1000}}
          canonical {:action/type :create-escrow
                     :action/params {:token "USDC" :beneficiary "0xb" :amount 1000}}]
      (is (not= (fl/action-root scenario) (fl/action-root canonical))
          "different beneficiaries → different roots")))
  (testing "the SAME action expressed in scenario or canonical spelling is one root"
    (let [a {:action "create_escrow"
             :params {:token "USDC" :to "0xseller2" :amount 1000
                      :custom-resolver "0xresolver"}}
          b {:action/type :create-escrow
             :action/params {:token "USDC" :beneficiary "0xseller2" :amount 1000
                             :resolver "0xresolver"}}]
      (is (= (fl/action-root a) (fl/action-root b))
          "scenario and canonical spellings of the same economic action unify"))))

;; ── Conflicting alias spellings fail closed ──────────────────────────────────

(deftest conflicting-alias-spellings-are-rejected
  (testing "both :to and :beneficiary present and UNEQUAL → reject, never precedence"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fl/normalize-action {:action "create_escrow"
                                       :params {:token "USDC" :to "0xseller1"
                                                :beneficiary "0xseller2" :amount 1000}}))))
  (testing "both :custom-resolver and :resolver present and UNEQUAL → reject"
    (is (thrown? clojure.lang.ExceptionInfo
                 (fl/normalize-action {:action "create_escrow"
                                       :params {:token "USDC" :to "0xseller2" :amount 1000
                                                :custom-resolver "0xaaa" :resolver "0xbbb"}}))))
  (testing "only legacy / only canonical / both-equal all normalize"
    (let [legacy {:action "create_escrow"
                  :params {:token "USDC" :to "0xseller2" :amount 1000
                           :custom-resolver "0xresolver"}}
          canonical {:action/type :create-escrow
                     :action/params {:token "USDC" :beneficiary "0xseller2" :amount 1000
                                     :resolver "0xresolver"}}
          both-equal {:action/type :create-escrow
                      :action/params {:token "USDC" :to "0xseller2" :beneficiary "0xseller2"
                                      :amount 1000 :custom-resolver "0xresolver"
                                      :resolver "0xresolver"}}]
      (is (= (fl/action-root legacy) (fl/action-root both-equal)))
      (is (= (fl/action-root canonical) (fl/action-root both-equal))
          "both-equal collapses to the same economic identity as legacy and canonical"))))

;; ── Canonical form is a TRUE fixed point for every supported action ──────────

(def all-action-types
  [:create-escrow :open-escrow :post-bonds :withdraw :settle :settle-only
   :allow-repayments :register-claims :allow-recapitalization
   :enter-resolution :declare-terminal :no-economic-effect])

(deftest canonical-form-is-a-true-fixed-point-for-every-action
  (testing "normalize-action(canonical) == canonical for every supported action"
    (doseq [t all-action-types]
      (let [c (fl/normalize-action {:action/type t})]
        (is (fl/canonical-action? c) (str "canonical? " t))
        (is (= c (fl/normalize-action c)) (str "true fixed point for " t))
        (is (= (:action/root c) (fl/action-root c)) (str "stable root for " t))))))

(deftest every-action-converges-from-keyword-to-canonical-root
  (testing "a bare keyword and its canonical form share one root (convergence)"
    (doseq [t all-action-types]
      (is (= (fl/action-root t) (fl/action-root {:action/type t}))
          (str "keyword converges to canonical root for " t)))))

(deftest economically-different-actions-keep-separate-roots
  (testing "separation: different types/amounts are different economic identities"
    (is (not= (fl/action-root :create-escrow) (fl/action-root :withdraw)))
    (is (not= (fl/action-root {:action/type :withdraw :action/params {:amount 100}})
              (fl/action-root {:action/type :withdraw :action/params {:amount 200}})))))

;; ── Strict token-ID grammar ──────────────────────────────────────────────────

(deftest strict-token-id-grammar
  (testing "accepted spellings unify into one bucket"
    (let [canonical-world (assoc (t/empty-world 1000) :total-held {:USDC 100})
          expected (liab/economic-liability-set canonical-world)]
      (doseq [s ["USDC" "usdc" " USDC " ":USDC"]]
        (is (= expected (liab/economic-liability-set
                         (assoc (t/empty-world 1000) :total-held {s 100})))
            (str "string spelling '" s "' unifies with :USDC")))))
  (testing "distinct / malformed token identities are rejected, never aliased"
    (doseq [bad ["foo/USDC" "" " " "US-DC" :foo/USDC]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (liab/asset-liability-rows
                    (assoc (t/empty-world 1000) :total-held {bad 100}) nil))
          (str "token '" bad "' is rejected")))))

;; ── Stable token source is committed ─────────────────────────────────────────

(deftest stable-token-source-is-committed-in-the-artifact
  (testing "the liability artifact commits the selected stable token AND its source"
    (let [configured (-> (t/empty-world 1000)
                         (assoc-in [:params :solvency/stable-token] "DAI")
                         (assoc-in [:slash-credit-liabilities "0xRes"] 300))
          legacy (-> (t/empty-world 1000)
                     (assoc-in [:slash-credit-liabilities "0xRes"] 300))
          art-configured (liab/liability-artifact configured)
          art-legacy (liab/liability-artifact legacy)]
      (is (= {:token :DAI :source :configured}
             (:liability-set/stable-token art-configured)))
      (is (= {:token :USDC :source :legacy-default}
             (:liability-set/stable-token art-legacy))
          "absence of :solvency/stable-token is committed as :legacy-default, never silent")
      (is (not= (:liability-set/root art-configured) (:liability-set/root art-legacy))
          "a configured vs legacy stable token yields a different committed root"))))
