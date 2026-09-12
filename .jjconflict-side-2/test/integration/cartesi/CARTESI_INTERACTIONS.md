# Explainer: Verifiable On-Chain Simulation with Cartesi

This document explains how we use Cartesi Rollups to run complex, verifiable simulations of the Sew dispute resolution protocol directly on the blockchain.

## 1. The Big Picture
Normally, blockchains are limited in the complexity of code they can run. Running a full statistical simulation in a traditional Smart Contract (Solidity) would be impossibly expensive and slow.

**Cartesi** changes this by providing a **virtual computer (RISC-V)** that lives on the blockchain. This computer can run standard software like the **JVM (Java Virtual Machine)** and **Clojure**.

---

## 2. How the Interaction Works

The interaction follows a simple loop: **Input → Execute → Result.**

### Step A: The Input (The "Scenario")
A user or an automated agent sends a "Scenario" to the blockchain. This is a JSON file containing a sequence of protocol events (e.g., "Buyer creates escrow", "Seller raises dispute").

*   **On-Chain Action:** A transaction is sent to the `InputBox` smart contract.
*   **What it means:** You are officially "queuing" a simulation to be performed by the decentralized network.

### Step B: The Execution (The "Advance")
The Cartesi node detects the new input and passes it into the **Cartesi Machine**. Inside this machine, our Clojure code is waiting.

1.  **Decoding:** The machine converts the blockchain's binary data back into a readable scenario.
2.  **Simulation:** The deterministic kernel replays every event, checking protocol invariants (rules) at every step.
3.  **Validation:** It confirms if the protocol behaved correctly (e.g., "Was money lost?", "Was an illegal state reached?").

### Step C: The Result (The "Notice")
Once the simulation finishes, the machine emits a **Notice**. 

*   **What it means:** A Notice is a verifiable proof of the simulation's outcome. It contains the final pass/fail status and a detailed execution trace.
*   **Verification:** Because the machine is deterministic, anyone can re-run the same input and get the *exact same* hash. If the results differ, the network rejects the "cheat."

---

## 3. Key Interaction Types

| Interaction | Name | Purpose | Data Flow |
| :--- | :--- | :--- | :--- |
| **Transaction** | `Advance State` | Run a new simulation. | User → Blockchain → Cartesi Machine |
| **Notification** | `Notice` | Confirm simulation success. | Cartesi Machine → Blockchain → User |
| **Query** | `Inspect State` | Check cumulative metrics & history. | User → Cartesi Node (No Gas Cost) |
| **Error Log** | `Report` | Debugging & Inspection results. | Cartesi Machine → Node Logs / UI |

---

## 4. Usability & Inspection Tools

We provide a unified CLI tool `cartesi_cli.py` to simplify interactions.

### Send a Simulation Scenario
```bash
python3 cartesi_cli.py send scenario_min.json
```

### Inspect Cumulative Metrics
To see total simulations, pass rates, and aggregate stats:
```bash
python3 cartesi_cli.py inspect metrics
```

### View Recent History
To see the last 10 simulation IDs and their outcomes:
```bash
python3 cartesi_cli.py inspect history
```

### Fetch All Results (Notices)
To see the full execution traces of all completed simulations:
```bash
python3 cartesi_cli.py notices
```

---

## 5. Why This Matters
By using Cartesi, we achieve **Verifiable Correctness**:
1.  **High Complexity:** We run the *exact same* Clojure code used in research, not a simplified version.
2.  **Trustless:** You don't have to trust our servers; you trust the math of the RISC-V VM.
3.  **Auditability:** Every protocol update can be "smoke-tested" by running the full invariant suite on-chain before being accepted.
4.  **Persistent Visibility:** The DApp maintains its own history and metrics, making it easy to monitor protocol health directly from the blockchain state.
