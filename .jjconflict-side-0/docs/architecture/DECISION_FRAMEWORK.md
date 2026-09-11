# Decision Framework: Reinterpret Your 99% Confidence

> **Context note (2026-05-21):** This document was written when Phase P Lite was
> approximately 2 weeks of future work and overall observability-adjusted confidence
> was estimated at ~55%. Many of the phases described as "upcoming" (P, Q, R, S, T,
> and beyond through AI) have since been implemented. The **structural calibration
> model** in this document — the distinction between bond-mechanics confidence and
> observability-adjusted confidence, and the falsification path — remains valid and
> useful. The specific timeline estimates, "currently 55%", and "2 weeks" figures
> are planning-era estimates and should not be read as current status.
> For current phase coverage see `docs/SYSTEM_OVERVIEW.md` and `docs/ROBUSTNESS_FRAMEWORK.md`.

## What You Have (Validated at 99%+)

**Mechanism security under perfect observability**:
- If truth is knowable and universally observable
- If disputes are uniform difficulty
- If resolvers are independent
- If detection is unambiguous
- → Then your bond/slashing mechanics are robust

**Evidence**: Phases G–O, 147,500+ trials, comprehensive stress testing

**Confidence**: 99% ✅

---

## What You Don't Have (Unvalidated)

**Mechanism security under realistic observability**:
- When truth is ambiguous (evidence games)
- When disputes have heterogeneous difficulty
- When resolvers are correlated (herding)
- When detection is probabilistic and time-consuming
- → Then robustness is uncertain

**What's Missing**: Phase P Lite (2 weeks), then Phases Q–T (8 weeks)

**Confidence**: Currently 55%, could reach 85%+ after Phase P Lite

---

## The Falsification Path: Phase P Lite

### What Phase P Lite Does (2 weeks of work)

Adds three modules that are most likely to break the 99% claim:

1. **Difficulty distribution** (70% easy, 25% medium, 5% hard)
   - Honest accuracy: easy=95%, hard=50%
   - Detection: easy=10%, hard=2%
   - Attacker targets hard cases

2. **Effort budget** (100 time units per epoch)
   - Lazy strategy costs less effort than honest
   - Under load (200 disputes), lazy becomes profitable
   - Honest degradation: 100% accuracy → 20% under load

3. **Panel majority** (n=3 with correlation parameter rho)
   - rho=0: independent (current model)
   - rho=0.5: moderate herding
   - rho=0.9: strong cascades
   - Herding incentive: deviance = perceived risk +20%

### Expected Outcomes

**Scenario A (Unlikely): Robustness Confirmed**
- Dominance ratio stays > 1.5x even at:
  - rho = 0.8 (strong herding)
  - heavy load (200 disputes, 100 effort)
  - hard-case targeting
- **Result**: 90%+ confidence, proceed to mainnet
- **Action**: Implement Phase Q–T at lower priority

**Scenario B (Most Likely): Brittleness Discovered**
- Dominance ratio inverts at moderate conditions:
  - rho > 0.4 causes cascades
  - Heavy load (200 disputes) breaks lazy/honest incentives
  - Hard-case targeting becomes profitable
- **Result**: 65% confidence, requires adjustment
- **Action**: Choose redesign option (A/B/C below)

**Scenario C (Possible): Fundamental Vulnerability**
- Dominance ratio inverts even at rho=0 and light load
- Multiple failure modes reinforce
- **Result**: <50% confidence, system redesign needed
- **Action**: Halt mainnet launch, redesign mechanism

---

## If Phase P Lite Finds Brittleness: Three Options

### Option A: Redesign Mechanism (Long Term)

**What to change**:
1. Multi-level adjudication (jury for hard cases, escalation on disagreement)
2. Reputation-weighted slashing (prevent herding, reward difficulty-appropriate accuracy)
3. Evidence oracles (external truth commitment before judgment)
4. Correlated-error penalties (reduce advantage of capturing cohorts)

**Timeline**: 4–8 weeks
**Confidence gain**: 90%+
**Mainnet delay**: 2 months

**Best for**: Systems where robustness > speed-to-market

---

### Option B: Add Parameters (Medium Term)

**What to change**:
1. Increase bond size for hard disputes (2–3x multiplier)
2. Reduce correlation amplification (cap rho effect at 30%)
3. Implement effort rewards (pay resolvers for verification depth)
4. Escalation thresholds (hard cases go to jury if disagreement)

**Timeline**: 2–3 weeks
**Confidence gain**: 75–80%
**Mainnet delay**: 1 week

**Best for**: Systems where 75%+ confidence is acceptable + monitoring

---

### Option C: Accept and Monitor (Fast Track)

**What to accept**:
- System is robust on bond mechanics (99%)
- System may be brittle on hard cases, load effects, herding (65% confidence)
- Real attacks will reveal weaknesses faster than simulation

**What to monitor**:
1. Hard case performance (detection rate vs. model)
2. Load effects (accuracy under queue > 10)
3. Herding signals (vote correlation, consensus on false cases)
4. Slashing distribution (who gets slashed, patterns)

**Timeline**: Ship now
**Confidence now**: 55%
**Confidence after 6 weeks**: 75–85% (with real data)

**Best for**: Governance-aligned systems that can iterate quickly

---

## Decision Matrix: Your Situation

| Factor | Implication |
|--------|------------|
| **Current confidence on bond mechanics**: 99% | ✅ Mechanism is sound |
| **Current confidence on realistic conditions**: 55% | ⚠️ Uncertainty remains |
| **Phase P Lite time cost**: 2 weeks | ✅ Fast falsification |
| **Expected to find brittleness**: 70% | ⚠️ Likely |
| **Can you iterate post-mainnet?**: ? | ? Depends on governance speed |
| **Do you have real attack data yet?**: No | ❌ Simulation-only |

**If governance can iterate fast**: **Option C (Monitor)**
- Launch mainnet now
- Phase P Lite in parallel (weeks 1–2)
- Phase Q–T based on real data + test results
- Reach 85%+ by month 2

**If governance is slow**: **Option B (Add Parameters)**
- Phase P Lite immediately (weeks 1–2)
- If brittleness found: implement parameter tweaks (week 3–4)
- Launch with 75%+ confidence
- Phase Q–T post-launch

**If you need 90%+ before launch**: **Option A (Redesign)**
- Phase P Lite immediately
- Full redesign if needed (weeks 3–8)
- Multi-level adjudication + reputation system
- Launch with 90%+ confidence (2 months)

---

## Recommended Path: Hybrid (Monitor + Phase P Lite)

### Week 1: Parallel Work
- **Track A**: Deploy mainnet with monitoring dashboard + current sim results
- **Track B**: Implement Phase P Lite (dispute difficulty + effort budget)

### Week 2: Decision Point
- Phase P Lite results available
- Real-world attack data from mainnet emerging
- Governance votes on next steps

### Outcome Based on Week 2 Data
1. **Phase P Lite shows robustness + mainnet stable**: → Option C (Monitor only)
2. **Phase P Lite shows brittleness + mainnet attacked**: → Option B (Add parameters)
3. **Phase P Lite shows brittleness + mainnet stable so far**: → Option A (Redesign) or Option C (Monitor longer)

---

## Talking Points for Stakeholders

### For Governance
**Current Status**:
- "Bond mechanics are validated at 99% confidence"
- "Information/coordination realism is being tested via Phase P Lite"
- "Mainnet deployment can proceed with monitoring"
- "If test results show brittleness, redesign will follow"

### For Security Auditors
**Key Claim**: "We're NOT claiming system is perfectly robust. We're claiming bond mechanics are sound under known assumptions. We're actively testing where those assumptions break."

**Evidence**:
- Phases G–O: bond/slashing mechanics validated ✅
- TRUTH_MODEL_ANALYSIS: assumptions documented ✅
- PHASE_P_LITE: falsification plan ready ✅
- Monitoring dashboard: real-world data collection ready ✅

### For Risk Managers
**Mitigation Strategy**:
1. Bond mechanics are proven (99%) → capital risk is low
2. Information/coordination gaps are documented → risk is known, not hidden
3. Phase P Lite will quickly identify edge cases → can adjust before damage
4. Monitoring dashboard reduces blind spots → can detect attacks early
5. Governance can iterate → can fix issues post-launch

---

## Final Recommendation

**Go with Hybrid Path**:

1. **Launch mainnet NOW** (Week 0)
   - Bond mechanics are genuinely sound
   - Monitoring dashboard catches real attacks
   - Governance can iterate

2. **Run Phase P Lite in parallel** (Weeks 1–2)
   - Fastest path to falsification
   - Real data converges with simulation data
   - Better decision-making by Week 2

3. **At Week 2 decision point**:
   - If robust + stable: → minimal redesign needed
   - If brittle + attacked: → quick fix (parameters)
   - If brittle + stable: → design phase 2 properly

4. **Reach 80%+ confidence by Month 2**:
   - Real world + simulation aligned
   - Issues understood and mitigated
   - Path to 90%+ clear

This approach gives you the best of both worlds:
- ✅ Launch speed (bonds are proven)
- ✅ Risk mitigation (real attack data)
- ✅ Fast iteration (parallel testing)
- ✅ Evidence-driven redesign (not guessing)

---

**Bottom Line**:

Your 99% confidence claim is **accurate for bond mechanics** under perfect observability. That's the hardest part. The remaining 45% (from 55% to 100%) is about **realistic information/coordination dynamics**, which Phase P Lite will falsify in 2 weeks.

Use that time to launch, monitor, learn, and decide.

