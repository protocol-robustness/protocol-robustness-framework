# Extracted repository ownership and pins

Repository ownership follows artifact function. Pins identify exact source; a
pin does not imply package publication, release qualification, or proof-system
qualification.

| Repository | PRF integration pin | Role and qualification |
|---|---|---|
| [`prf-canonical`](https://github.com/protocol-robustness/prf-canonical) | `deps.edn`: `5e2b520feca812b049ba397b7b3681963c66a48c` | Shared canonical encodings and roots; source-compatibility qualified. |
| [`prf-pro-rata-allocation`](https://github.com/protocol-robustness/prf-pro-rata-allocation) | `deps.edn`: `e0788f911e4fba3a27544cbccbb95d59d7daf2f7` | Portable allocation API and semantics; source-qualified, release remains unqualified. |
| [`prf-effect-transition-contract`](https://github.com/protocol-robustness/prf-effect-transition-contract) | **No PRF consumer pin**; exploratory remote `main`: `123836c296943665c08d8dc8341ec7f220750086` (`cfa399…` is a separate exploratory branch) | Effect-transition candidate. It is not an active PRF dependency, remains unreviewed, and is not release-qualified. |
| [`prf-coprocessor`](https://github.com/protocol-robustness/prf-coprocessor) | `data/prf/external-repositories.json`: `e2e4e9b25622c52eb028fc373a66e81b3982e933` | Rust witness kernel, SP1 guests/hosts and proof scripts. Source extracted; release and SP1/Solidity proof qualification remain unqualified. |

The coprocessor consumes `prf-canonical` at
`5e2b520feca812b049ba397b7b3681963c66a48c` and
`prf-pro-rata-allocation` at `48e0b362748cd39d09994a8aa1496c668199aa74`.
That allocator pin is distinct from PRF's Clojure allocator pin above: PRF's
pin exposes the frozen allocation fixtures as classpath resources, while the
coprocessor pin contains the same allocation implementation and fixtures in
its source/test layout. Do not describe the revision sets as aligned without
rechecking their trees and cross-repository conformance.

## Local checkout

`make setup-coprocessor` explicitly creates or validates the pinned checkout
under `${XDG_CACHE_HOME:-~/.cache}/prf/coprocessor/<revision>`. It does not
modify an existing PRF checkout. Evaluation and resolver code only validate an
already prepared checkout; they do not perform Git or network operations.
`PRF_COPROCESSOR_ROOT` may select a clean checkout at the exact pin.

The source snapshot provenance and its golden vector identity are recorded in
[`prf-coprocessor/PROVENANCE.md`](https://github.com/protocol-robustness/prf-coprocessor/blob/e2e4e9b25622c52eb028fc373a66e81b3982e933/PROVENANCE.md).
