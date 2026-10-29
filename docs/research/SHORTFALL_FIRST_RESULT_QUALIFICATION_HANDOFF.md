# Shortfall first result qualification handoff

## Claim and scope

This offline example compares the canonical allocation results for one fixed
request under `:unallocated` and `:redistribute-cap-excess`. The exact inputs
and result are retained in `examples/research-resolution/shortfall/`.

The bounded claim is: for case root
`sha256:7f26595fb757d544afd670c977666289d2e9a1813435e7de1044bfbf6c1210ba`,
the maximum absolute per-claim allocation difference is 10 units. The
comparison artifact root is
`sha256:4b888dd111024a343d6fc38a7ac90a18958b39eacd0576c0e0fdab994a1cddf0`;
the method root is
`sha256:840e8eb65ea36fce98edcf2d43feb8082be4e0fad7ee36e85daee770187b0c45`.
The affected row is `:claim/charlie` (19 versus 27). This says nothing about
incentive compatibility, safety of capital movements, or all possible
allocation inputs.

## Reproduction

Run the PRF method verifier, then compare its per-row results against the
separately implemented portable contract oracle:

```sh
clojure -M -m resolver-sim.research.shortfall-pilot-cli compare \
  examples/research-resolution/shortfall/case-001-unallocated.edn \
  examples/research-resolution/shortfall/case-001-redistribute.edn

clojure -M -e '
(require (quote [clojure.edn :as edn])
         (quote [resolver-sim.pro-rata-contract.contract :as oracle]))
(let [dir "examples/research-resolution/shortfall/"
      requests (mapv #(edn/read-string (slurp (str dir %)))
                     ["case-001-unallocated.edn" "case-001-redistribute.edn"])
      comparison (edn/read-string
                  (slurp (str dir "case-001-comparison.edn")))
      row-allocations (fn [result]
                        (into {} (map (juxt :row/id :allocated) (:rows result))))
      independent (mapv oracle/allocate requests)]
  (assert (= (mapv row-allocations (:results comparison))
             (mapv row-allocations independent)))
  (assert (= 10 (:value comparison)))
  (println "independent allocation replay matched"))'
```

The two source implementations produce different allocation artifact hashes,
so the independent check compares the per-row allocation semantics rather
than asserting cross-implementation hash identity. The primary comparison
artifact retains complete canonical results and witnesses. Its replay can be
checked with:

```sh
clojure -M -e '
(require (quote [clojure.edn :as edn])
         (quote [resolver-sim.research.shortfall-pilot :as pilot]))
(let [artifact (edn/read-string
                (slurp "examples/research-resolution/shortfall/case-001-comparison.edn"))]
  (assert (= artifact (pilot/validate-comparison! artifact)))
  (println "primary artifact replay matched"))'
```

Pinned library inputs in `deps.edn` are `prf/canonical` at
`5e2b520feca812b049ba397b7b3681963c66a48c` and
`prf/pro-rata-allocation` at `e0788f911e4fba3a27544cbccbb95d59d7daf2f7`.
The primary method source SHA-256 is
`3bf96900a3e858f436db73b23c50a44644813b507c3520ff043b871f78157621`.
Portable oracle source SHA-256 values are
`efb59158f98218d2167b9e74e6c1765ac960af2606f51316c1f124439dc84ac0`
(`contract.clj`) and
`084c95728df1e9ac1afd9d7408e46664263e90d971ae4ffaa99474528d5292fe`
(`canonical.clj`). These file digests identify this checkout's sources; they
are not externally authenticated release registrations.

## Qualification dimensions

| Dimension | Current evidence | Qualification |
|---|---|---|
| Claim arithmetic | Retained canonical requests, allocator results, invariants, and replay | Verified within this bounded case |
| Independent reproduction | Portable contract implementation matches each per-claim allocation | Independently reproduced; implementation and release provenance still need an external trust anchor |
| Research policy approval | No signed approval receipt from an identified legitimate policy owner | Unknown / not approved |
| Researcher ratification | No authenticated researcher decisions for this target | Absent |
| Decision history completeness | No frozen close or independently committed complete submission history | Unknown; a reconstructed threshold alone cannot prove no dissent was withheld |
| Operational authority | Not requested or included in this research claim | Not granted |

This example is a fresh input pair and is not either published teaching
calibration pair. It is not yet an authority-bearing research qualification.

## External inputs required to qualify it

1. **Governance bootstrap:** a named policy owner, evidence for that owner's
   authority, and a signed, frozen governance snapshot whose root is
   independently trusted. The bootstrap must not derive its legitimacy from
   the research ratification policy it is approving.
2. **Policy approval:** explicit approval of the exact content-addressed
   research policy, including the initial member roster, threshold,
   challenge/dissent handling, and version. Supply the eligible researcher
   principal IDs and their verified active public-key bindings. The proposed
   3-member / 2-of-3 model is only a candidate until explicitly approved.
3. **Method registration:** an identified verifier/release custodian must
   authenticate the source and dependency roots above (or provide a frozen
   release manifest that supersedes them) and attest that this verifier is
   trusted for this bounded arithmetic claim.
4. **Researcher decisions:** after the exact target and policy are frozen,
   collect genuine signed decisions from eligible members. Do not generate
   substitute keys or infer identity/eligibility from distinct keys alone.
5. **Round closure:** provide an authenticated close record that commits to
   the complete decision history, states how late submissions and challenges
   are handled, and resolves any material challenge. Until such a mechanism is
   supplied, report decision-history completeness as unknown.

The repository already has policy-approval receipts and signed researcher
decisions for the policy-approval path. Reuse those after the external roots,
roster, policy, and signatures exist. No such approval, identity binding, or
method registration is included in this handoff.

## Integration gate

The artifact verifier now reports artifact-declared trust roots as
`:unverified`; root-list consistency is not external authentication. It also
reports authority and history completeness separately as `:unverified`.
This preserves valid offline reconstruction while preventing the demo or this
example from implying approved governance, complete deliberation, or protocol
authority.
