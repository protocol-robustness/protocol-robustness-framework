Prompt for agent: dispute-resolution robustness scenario suite

You are working on the Sew Protocol / robustness workbench.

Your task is to design and implement a comprehensive scenario suite that validates the robustness of Sew’s dispute-resolution subsystem under adversarial, economic, timing, governance, and integration stress.

This should not be a generic “happy path” test suite. Treat it as a protocol-adversarial validation suite inspired by current decentralized dispute-resolution research and real protocol patterns from Kleros, UMA, optimistic oracles, arbitration systems, Schelling-point voting, challenge windows, appeals, and economic security models.

The output should be executable, reproducible evidence: deterministic traces, invariant checks, expected outcomes, threat tags, confidence tiers, and clear failure explanations.

Context

Sew uses protected transfers with non-custodial escrow, dispute initiation, resolver decisions, appeal/escalation paths, and eventual settlement. The protocol’s key guarantees include:

active escrows must remain governed by their creation-time configuration;
governance changes must only affect future escrows;
funds must not be redirected by governance, resolvers, integrations, or emergency controls;
dispute paths must have bounded duration and explicit appeal windows;
resolver incentives must make malicious resolution economically unattractive;
settlement should credit claimable balances rather than autopushing funds;
disputes may eventually use Kleros or another decentralized arbitration backstop;
challenge / appeal / watchdog-style flows may be introduced to let third parties challenge bad resolutions.

Build scenarios that test these guarantees as a living evidence suite.

Research-informed scenario categories

Use the following sources of inspiration:

Kleros-style decentralized justice: randomly selected jurors/resolvers, coherent voting incentives, appeal rounds, increasing jury size/cost, bribe resistance, and immutable procedure.
UMA-style optimistic oracle design: bonded assertions, challenge/liveness windows, proposer/disputer incentives, escalation to a backstop oracle/arbitration layer, and cost-of-corruption vs profit-from-corruption reasoning.
Schelling-point and oracle attack literature: p+epsilon bribery, direct bribes, external incentives, whale/collusion attacks, low-participation attacks, and strategic abstention.
Real dispute integrations: prediction markets, insurance claims, bridges, registries, ecommerce disputes, evidence challenges, and “anyone can challenge” watchdog models.
Sew-specific invariants: per-escrow immutability, deterministic state machine, conservation of funds, pull-based settlement, bounded emergency powers, appeal windows, resolver capacity, and governance forward-only upgrades.
Deliverables

Produce:

A scenario catalogue grouped by threat model.
Deterministic trace fixtures for each high-priority scenario.
Scenario metadata including:
scenario-id
threat-tags
actors
preconditions
event-sequence
expected-terminal-state
expected-ledger-effects
economic-assumptions
falsifies-if
confidence-tier
Invariant checks for each scenario.
A coverage report mapping scenarios to protocol guarantees.
A short “validation gaps” section listing anything not yet modeled.
A social/shareable workbench summary suitable for Clerk notebook rendering.

Prioritize deterministic replay first. Monte Carlo or stochastic sweeps can be added after the deterministic baseline is complete.

Required scenario suite
A. Baseline dispute lifecycle scenarios

Implement these first to ensure all adversarial tests have a clean reference baseline.

A1. No-dispute protected transfer release

A protected transfer reaches auto-release or manual release without dispute.

Validate:

principal is credited only to the intended recipient;
no resolver or dispute module receives funds;
no appeal state is created;
release cannot be replayed;
claimable balance increases exactly once.
A2. Buyer/sender raises dispute before release

The sender raises a valid dispute before the release deadline.

Validate:

escrow enters disputed flow;
release path is blocked while disputed;
resolver assignment is recorded;
all funds remain conserved;
timeout clocks are initialized correctly.
A3. Recipient raises dispute / counterparty dispute path

The recipient or counterparty triggers a dispute where allowed.

Validate:

the protocol treats dispute initiation symmetrically where intended;
unauthorized actors cannot initiate where not intended;
dispute metadata is bound to the correct protected transfer ID.
A4. Resolver decides refund

Resolver rules for refund.

Validate:

funds become claimable by sender;
recipient cannot withdraw principal;
resolver payment, if any, is credited through the correct pull ledger;
no direct autopush occurs.
A5. Resolver decides release

Resolver rules for release.

Validate:

funds become claimable by recipient;
sender cannot withdraw principal;
resolver incentive accounting is correct;
repeated settlement calls are idempotent.
A6. Split / partial resolution

Resolver produces a split outcome, if supported.

Validate:

split percentages sum exactly to principal plus accrued yield policy;
rounding cannot leak or create value;
dust handling is deterministic;
both parties receive only their entitled balances.
B. Appeal-window and escalation scenarios

Kleros-style appeal mechanics are explicitly important because appeals are a core defence against small-panel bribery and bad early rulings.

B1. Appeal inside valid appeal window

A losing party appeals just before the deadline.

Validate:

appeal is accepted at deadline - 1;
escalation state is created;
prior decision is not final;
appeal bond is locked correctly.
B2. Appeal exactly at boundary

Test appeal at deadline, deadline + 1, and same-block ordering around the deadline.

Validate:

boundary semantics are explicit;
there is no off-by-one ambiguity;
ordering cannot finalize and appeal the same dispute inconsistently.
B3. Appeal after finalization attempt

One actor tries to finalize, another tries to appeal in same block / adjacent block.

Validate:

deterministic ordering rules;
no double terminal state;
state cannot become both finalized and appealed.
B4. Multi-round escalation cost curve

A dispute escalates through multiple rounds.

Validate:

appeal costs increase according to configured curve;
each round’s resolver/jury size/cost is correctly snapshotted;
cost growth makes repeated malicious appeals expensive;
max rounds or max duration is enforced.
B5. Appeal exhaustion

Actor runs out of funds or chooses not to fund the next appeal.

Validate:

last valid ruling becomes final only after the proper window;
partially funded appeal logic is correct;
refunds/forfeits are allocated as specified.
B6. Anyone-can-appeal / sponsored appeal

A third-party sponsor, watchdog, insurer, platform, or DAO funds the appeal.

Validate:

appeal rights do not depend only on the harmed party having capital;
sponsor bond accounting is separate from party entitlement;
sponsor cannot redirect settlement funds;
successful challenge/appeal bounty is paid only under valid conditions.
C. Bribery, corruption, and collusion scenarios

These should directly model known Schelling-point and decentralized justice risks.

C1. Direct resolver bribe

A malicious party offers a resolver a bribe greater than the honest resolver reward.

Validate:

expected malicious profit is negative under configured slashing/detection assumptions;
protocol-integrity monitor or appeal path makes corruption unprofitable above the modeled detection threshold;
the scenario records the detection probability required to deter attack.
C2. p+epsilon-style external bribery

Model an external smart-contract bribe that rewards actors for voting/resolving against truth depending on expected majority behavior.

Validate:

the simulation can represent external incentives;
equilibrium/regret analysis identifies when the honest strategy is no longer dominant;
scenario is marked red/amber if the protocol relies on assumptions outside its control.
C3. Small-panel corruption followed by appeal

A low-cost early resolver/panel is corrupted, then an honest actor appeals to a higher-cost layer.

Validate:

early corruption does not immediately drain funds;
appeal path prevents finality before challenge window closes;
attacker cost compounds across rounds;
final honest outcome is reachable if appeal assumptions hold.
C4. Corruption across all escalation layers

Attacker corrupts resolver, senior resolver, and backstop layer.

Validate:

cost-of-corruption is compared with profit-from-corruption;
scenario fails if profit_from_corruption > cost_of_corruption;
report includes minimum bond/stake/slash settings required to restore safety.
C5. Resolver cartel controls capacity

A cartel controls a large share of active resolver capacity.

Validate:

routing/capacity limits reduce concentration;
dispute assignment does not route all high-value disputes to the cartel;
cartel expected value is bounded;
repeated reversals/slashing degrade cartel future capacity or score.
C6. Strategic abstention / timeout griefing

Resolvers avoid ruling to force timeout, delay, or escalation.

Validate:

timeout penalties exist;
liveness is bounded;
resolver non-performance cannot freeze funds beyond max dispute duration;
fallback routing/escalation activates correctly.
C7. Vote buying through identity linkage

If resolver/juror identity or vote proofs are exposed, model targeted bribery or retaliation.

Validate:

scenario documents whether the current design assumes resolver privacy;
public resolver identities do not create unmodeled safety claims;
if privacy is out of scope, mark the assumption clearly.
D. Optimistic-challenge and liveness-window scenarios

UMA-style optimistic systems depend heavily on challenge windows, bond sizing, and disputer incentives.

D1. False assertion goes unchallenged

A malicious resolver/proposer asserts a false outcome and nobody disputes.

Validate:

protocol records this as a real risk, not a green pass;
minimum monitoring/disputer participation assumptions are explicit;
affected funds are bounded by caps or challenge window policy.
D2. False assertion challenged successfully

A watchdog disputes a false assertion within liveness.

Validate:

challenge is accepted;
malicious actor’s bond is slashed/forfeited as specified;
challenger receives refund/bounty;
final settlement follows corrected outcome.
D3. Honest assertion griefed by frivolous dispute

A bad-faith challenger disputes an honest resolution.

Validate:

challenger bond is forfeited;
honest actor is compensated where intended;
griefing cost exceeds delay benefit under modeled assumptions;
dispute spam cannot create unbounded operational load.
D4. Low bond attack

Bond is smaller than the expected profit from a false resolution.

Validate:

scenario fails explicitly;
report recommends minimum bond as function of escrow value, monitoring probability, and appeal cost;
unsafe parameter regions are marked.
D5. Excessively long liveness

Challenge window is so long that honest users suffer unacceptable capital lockup.

Validate:

UX/economic delay cost is measured;
funds remain safe but liveness quality degrades;
report distinguishes safety from usability.
D6. Excessively short liveness

Challenge window is too short for realistic monitoring.

Validate:

bad resolution can pass before watchdogs react;
minimum safe liveness is derived from detection/response assumptions;
report flags this as an economic/security issue, not just UX.
D7. Same-block challenge/finalize race

One transaction finalizes while another challenges.

Validate:

deterministic ordering;
no double settlement;
no challenge accepted after terminal finality;
no finality before the liveness condition is satisfied.
E. Evidence integrity and ambiguity scenarios

Decentralized dispute systems fail not only from malicious actors but also ambiguous evidence and unclear rules.

E1. Missing evidence

A dispute is raised with insufficient evidence.

Validate:

resolver can rule according to predefined default rules;
evidence absence does not break state transitions;
rules are explicit and not ad hoc.
E2. Conflicting evidence

Both parties submit plausible but contradictory evidence.

Validate:

resolution can proceed;
scenario records confidence level;
appeal path remains available;
no funds move until terminal outcome.
E3. Evidence submitted after deadline

A party submits important evidence after the evidence window.

Validate:

late evidence is accepted or rejected according to fixed rules;
resolver cannot secretly alter procedure;
appeal metadata records late-evidence status if relevant.
E4. Evidence hash mismatch

Evidence pointer exists, but content hash does not match.

Validate:

invalid evidence is flagged;
resolver decision cannot rely on tampered evidence without explicit annotation;
scenario report distinguishes evidence-layer failure from protocol accounting failure.
E5. Ambiguous terms / subjective dispute

Dispute depends on ambiguous off-chain interpretation.

Validate:

protocol does not claim deterministic truth where none exists;
resolver discretion is bounded by documented rule set;
confidence tier is lower than objective disputes;
appeal/backstop is available.
E6. Evidence censorship / unavailable storage

IPFS/Arweave/HTTP evidence is unavailable during dispute.

Validate:

dispute can pause, proceed, or reject according to predefined rules;
max dispute duration still holds;
no indefinite freeze.
F. Economic parameter robustness scenarios

These scenarios should produce parameter recommendations, not only pass/fail results.

F1. Detection probability sweep

Sweep detection/challenge probability from 0% to 100%.

Validate:

find minimum detection probability required to make malicious resolution negative EV;
compare against current assumed threshold;
output chart/table for workbench.
F2. Bond size sweep

Sweep challenge bond and appeal bond as basis points of escrow value.

Validate:

identify unsafe zones where corruption or griefing is profitable;
identify over-secured zones where legitimate appeals become inaccessible;
recommend launch bounds.
F3. Resolver reward sweep

Sweep resolver compensation.

Validate:

too-low rewards increase abstention/collusion risk;
too-high rewards enable dispute farming;
equilibrium zone is reported.
F4. Escrow value concentration

A small number of high-value escrows appear among many low-value escrows.

Validate:

high-value disputes trigger stronger security parameters;
resolver capacity/routing adapts;
cost-of-corruption remains above profit-from-corruption.
F5. Repeated attacker over multiple epochs

Same adversary attacks across many disputes.

Validate:

reputation/EMA/slashing effects compound;
attacker cannot profit by alternating honest and malicious behavior;
multi-epoch expected value remains negative.
F6. Dispute farming

Two colluding parties create fake escrows and disputes to extract resolver incentives, protocol subsidies, appeal rewards, or watchdog bounties.

Validate:

rewards cannot exceed real economic stake;
self-dealing is unprofitable;
protocol fee/subsidy settings are robust.
G. Resolver assignment and workload scenarios
G1. Dispute flood

Attacker creates many low-value disputes to overload resolvers.

Validate:

capacity limits hold;
high-value disputes are not starved;
liveness degradation is bounded;
fees make spam expensive.
G2. Targeted resolver exhaustion

Attacker tries to route disputes to a specific resolver until they timeout.

Validate:

routing randomness or workload caps prevent targeting;
timeout penalties are fair;
honest resolver is not slashable due to protocol-induced overload.
G3. Resolver rotation during active dispute

Governance or admin rotates resolver set after dispute creation.

Validate:

active dispute keeps its snapshotted resolver path unless explicitly designed otherwise;
new resolver config affects only future disputes;
no governance sandwich attack.
G4. Resolver unavailable mid-dispute

Assigned resolver disappears.

Validate:

fallback path activates;
funds are not frozen indefinitely;
resolver bond/reputation is penalized;
max dispute duration remains enforced.
G5. Resolver reversal rate spike

A resolver’s decisions are repeatedly overturned on appeal.

Validate:

score/EMA decreases;
future capacity decreases;
slashing or review path activates;
report surfaces this as a protocol-integrity signal.
H. Governance and upgrade attack scenarios

Sew-specific forward-only governance guarantees are central and should be heavily tested.

H1. Governance changes resolver module during active dispute

Validate:

active dispute remains bound to creation-time module snapshot;
settlement cannot use the new module;
future escrows use the new module only after timelock execution.
H2. Governance changes appeal window mid-dispute

Validate:

active dispute keeps original appeal window;
no party is deprived of already-promised appeal rights;
no extension can be used to grief active escrows unless explicitly allowed.
H3. Governance changes fee/bond parameters mid-dispute

Validate:

active dispute keeps original fee/bond settings;
no retroactive bond increase traps a party;
no retroactive decrease enables cheap corruption.
H4. Emergency pause during dispute

Validate:

pause blocks new risky operations as intended;
existing non-disputed release/cancel/unwind paths still behave as specified;
disputed funds are not redirected;
pause expiry and unpause rules are enforced.
H5. Repeated emergency pauses

Validate:

max pauses per rolling window is enforced;
pause cannot be chained forever;
dispute max duration semantics remain clear.
H6. Governance sandwich

Attacker creates dispute, passes governance change, then tries to settle under changed rules.

Validate:

impossible for active escrow;
trace proves snapshot isolation;
invariant: no active protected transfer can have its resolution policy mutated.
I. Settlement, accounting, and withdrawal scenarios
I1. Settlement credits pull ledger only

Validate:

no direct ETH/ERC20 autopush to parties during dispute settlement;
claimable balances are correct;
failed recipient fallback cannot block settlement.
I2. Reentrant withdrawal attempt

Malicious recipient contract attempts reentrancy during withdrawal.

Validate:

balance is decremented before external transfer or guarded;
no double withdrawal;
no state corruption.
I3. Fee-on-transfer / non-standard ERC20

Token transfers less than expected or returns unusual values.

Validate:

accounting does not assume naïve ERC20 behavior unless token whitelist enforces it;
unsupported tokens fail safely;
conservation invariant reflects actual received amounts.
I4. Yield accrued during dispute

Escrow earns yield while disputed.

Validate:

yield policy is snapshotted;
yield distribution follows terminal outcome;
protocol yield fee is correct;
no party can manipulate dispute duration solely to capture excess yield.
I5. Appeal bond refund and slash accounting

Validate:

winning appeal funder receives correct refund/bounty;
losing appeal funder forfeits according to rules;
protocol fee, resolver payment, and party settlement ledgers are distinct.
I6. Multi-claim ledger isolation

One actor has claimable funds across multiple escrows and ledgers.

Validate:

withdrawing one claim does not affect another;
token/address/workflow keys are correct;
no cross-escrow leakage.
J. Backstop arbitration integration scenarios

These should model Kleros or another external arbitrator without assuming it is always honest.

J1. Escalation to Kleros-style backstop

Validate:

dispute is escalated with correct metadata;
local settlement is blocked while awaiting backstop;
final ruling is consumed exactly once;
ruling maps correctly to Sew outcome.
J2. Backstop delayed ruling

Validate:

protocol waits within bounded limits;
timeout/fallback behavior is explicit;
funds are not prematurely settled.
J3. Backstop returns unexpected ruling code

Validate:

unknown/invalid ruling fails safely;
no funds move to arbitrary address;
manual/emergency path, if any, is constrained.
J4. Backstop unavailable

Validate:

dispute does not remain permanently stuck without documented fallback;
governance cannot use fallback to steal or redirect funds;
user-facing status is clear.
J5. Backstop bribery/corruption model

Validate:

scenario models external arbitrator corruption cost;
Sew’s maximum value-at-risk per dispute is bounded;
cost-of-corruption/profit-from-corruption report includes backstop layer.
J6. Backstop appeal mismatch

Sew appeal window and external arbitrator appeal window differ.

Validate:

procedural deadlines are mapped correctly;
no party loses appeal rights due to integration mismatch;
finality only occurs after both systems are final.
K. Watchdog / protocol-integrity monitor scenarios

These are high priority if Sew introduces challengeResolution() or similar.

K1. Watchdog challenges bad resolver decision

Validate:

any address can challenge if designed;
challenge bond is required;
successful challenger receives refund/bounty;
resolver slash funds bounty where specified.
K2. Watchdog false challenge

Validate:

challenger bond is forfeited;
honest resolver receives compensation if specified;
false challenges are unprofitable.
K3. Watchdog cooldown

Same address repeatedly challenges.

Validate:

cooldown enforced;
repeat bond scaling applied;
attacker cannot grief cheaply through many challenges.
K4. Sybil watchdog bypass

Attacker uses many addresses to bypass cooldown.

Validate:

per-address cooldown limitation is documented;
economic bond still prevents cheap spam;
if Sybil is profitable, mark as validation failure or design gap.
K5. Watchdog capital constraint

Honest watchdog cannot afford bond on high-value dispute.

Validate:

access-to-justice assumption is explicit;
scenario explores sponsor/insurance/DAO-funded challenge;
report identifies minimum viable bounty/bond structure.
L. Cross-domain, MEV, and ordering scenarios
L1. MEV front-run of dispute initiation

Attacker sees a dispute transaction and tries to release/finalize first.

Validate:

dispute initiation before release deadline cannot be bypassed by ordering exploit if protocol intends protection;
boundary behavior is deterministic;
unsafe timing windows are documented.
L2. MEV front-run of appeal

Attacker tries to finalize before appeal transaction lands.

Validate:

appeal window semantics are robust;
users are not forced into impossible timing races;
same-block ordering is explicitly modeled.
L3. Cross-chain bridge delay

If protected transfer depends on bridged funds or external bridge messages.

Validate:

dispute timeline does not assume instant bridge finality;
external message delay cannot unlock funds incorrectly;
explicit release action remains required.
L4. Oracle timestamp manipulation

Block timestamp skew affects deadline, appeal, or liveness.

Validate:

tolerance bounds are modeled;
one-block skew cannot change expected outcome unfairly;
deadlines are robust to realistic validator timestamp control.
L5. Chain reorg around finality

A dispute or appeal transaction is included then reorged.

Validate:

off-chain monitors handle confirmation depth;
protocol state remains internally consistent;
evidence report states whether reorg modeling is in or out of scope.
M. Human/UX and procedural fairness scenarios

These are especially relevant for dispute resolution because technically valid processes can still be unfair.

M1. Party cannot afford appeal

Validate:

protocol records access-to-justice risk;
sponsored appeal path, if available, works;
no claim is made that appeal protection exists when economically inaccessible.
M2. Asymmetric information

One party has evidence the other cannot inspect.

Validate:

resolver can only rely on admissible/available evidence;
hidden evidence does not silently affect deterministic replay;
evidence provenance is included.
M3. Language / format mismatch

Evidence is submitted in unsupported language or file format.

Validate:

procedural handling is defined;
dispute does not halt indefinitely;
confidence tier reflects review limitations.
M4. Maliciously complex evidence

Party submits huge or confusing evidence to increase resolver cost.

Validate:

evidence size/time limits exist;
resolver compensation or rejection rules handle complexity;
griefing via evidence bloat is bounded.
Priority order

Implement in this order:

Baseline lifecycle: A1–A6.
Appeal/liveness boundaries: B1–B6, D1–D7.
Accounting and settlement invariants: I1–I6.
Governance snapshot attacks: H1–H6.
Resolver corruption/collusion: C1–C7.
Economic sweeps: F1–F6.
Watchdog/challenge scenarios: K1–K5.
Backstop arbitration integration: J1–J6.
Evidence ambiguity and human fairness: E1–E6, M1–M4.
MEV/cross-domain/reorg assumptions: L1–L5.

For each implemented scenario, produce both:

a deterministic minimal trace; and
one adversarial variant that attempts to break the expected invariant.
Required invariants

At minimum, every scenario must check:

State-machine invariants
no invalid state transition;
no double terminal state;
no transition after terminal settlement;
disputed escrow cannot be released through non-dispute path;
appeal cannot occur outside valid window;
finalization cannot occur before all challenge/appeal windows close.
Fund-conservation invariants
total escrowed principal plus yield equals claimable balances plus remaining locked balances plus protocol fees, accounting for explicit slashes/burns;
no funds are sent to unauthorized addresses;
no autopush during settlement;
no double withdrawal;
no cross-escrow balance leakage.
Governance invariants
active escrow configuration cannot be mutated;
active dispute configuration cannot be retroactively changed;
governance cannot redirect escrow funds;
emergency pause cannot create indefinite lock beyond defined rules.
Economic invariants
malicious resolver expected value should be negative under documented detection/slashing assumptions;
frivolous challenge expected value should be negative;
dispute farming expected value should be negative;
cost-of-corruption should exceed profit-from-corruption for configured value-at-risk;
economic assumptions must be visible in the scenario report.
Liveness invariants
every dispute reaches terminal state or documented fallback within max duration;
resolver timeout activates fallback;
appeal/challenge windows are neither skipped nor indefinitely extended;
pause/emergency controls cannot freeze funds forever.
Output format

Use this EDN-like structure for each scenario:

{:scenario-id :dispute/appeal-boundary-same-block
 :title "Appeal/finalize same-block boundary"
 :priority :p0
 :threat-tags #{:appeal-window :same-block-ordering :finality-race :liveness}
 :actors {:sender :alice
          :recipient :bob
          :resolver :resolver-1
          :attacker :bob}
 :preconditions {:escrow-value 1000
                 :appeal-window-seconds 86400
                 :resolver-decision :refund
                 :current-time :appeal-deadline}
 :event-sequence
 [{:step 1 :actor :resolver-1 :action :decide :outcome :refund}
  {:step 2 :actor :bob :action :finalize-attempt}
  {:step 3 :actor :alice :action :appeal-attempt}]
 :expected-terminal-state :appealed-or-finalized-deterministically
 :expected-ledger-effects {:principal :locked-until-terminal
                           :appeal-bond :locked-if-appeal-valid}
 :invariants [:no-double-terminal-state
              :no-settlement-before-finality
              :appeal-boundary-deterministic
              :fund-conservation]
 :economic-assumptions {}
 :falsifies-if ["both appeal and finalization succeed"
                "principal becomes withdrawable before final state"
                "ordering changes outcome nondeterministically"]
 :confidence-tier :high}

Also produce a coverage matrix:

{:guarantee :active-escrows-immutable
 :covered-by [:governance/change-resolver-active-dispute
              :governance/change-appeal-window-active-dispute
              :governance-sandwich]
 :status :covered
 :remaining-gaps []}

And a validation summary:

{:suite :dispute-resolution-robustness-v1
 :scenario-count 0
 :p0-count 0
 :passed 0
 :failed 0
 :amber 0
 :known-gaps []
 :unsafe-parameter-regions []
 :recommended-next-scenarios []}
Acceptance criteria

The task is complete only when:

every P0 scenario has a deterministic trace;
every trace has explicit expected terminal state and ledger effects;
every scenario has at least one falsification condition;
all fund movement is checked through pull-ledger accounting;
governance-forward-only behavior is tested against active disputes;
appeal/liveness boundary tests include t-1, t, t+1, and same-block ordering;
at least one scenario models p+epsilon bribery or equivalent external bribe;
at least one scenario models cost-of-corruption vs profit-from-corruption;
at least one scenario models a false optimistic assertion going unchallenged;
at least one scenario models a valid watchdog challenge;
all assumptions are surfaced in the report rather than hidden in code.

Do not mark a scenario green just because the trace completed. Green means the relevant invariant held under the stated assumptions. Amber means missing model coverage or dependence on an unvalidated assumption. Red means a demonstrated invariant or economic failure.