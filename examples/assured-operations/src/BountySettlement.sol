// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

/// @notice Controlled native-asset example for authenticated bounty funding
/// and a one-time direct beneficiary payout. This is an executable reference
/// example, not a production reward contract or legal identity registry.
contract BountySettlement {
    enum Lifecycle {
        None,
        Claimable,
        Settled
    }

    struct ReserveSource {
        address funder;
        uint256 originalAmount;
        uint256 available;
        uint256 sequence;
    }

    struct PayableRecord {
        bytes32 operationRoot;
        bytes32 fundingSourceRoot;
        address beneficiary;
        uint256 amount;
        uint256 sequence;
        Lifecycle lifecycle;
    }

    address public immutable fundingAuthority;
    address public immutable awardAuthority;
    uint256 public nextFundingSequence;
    uint256 public nextPayoutSequence;
    uint256 public totalLiabilities;
    uint256 public totalPaid;
    bool private entered;

    mapping(bytes32 => ReserveSource) public reserveSources;
    mapping(bytes32 => PayableRecord) public payables;

    event ReserveFunded(
        bytes32 indexed sourceRoot, address indexed funder, uint256 amount, uint256 sequence, uint256 balanceAfter
    );
    event BountyApproved(
        bytes32 indexed payableId,
        bytes32 indexed operationRoot,
        bytes32 indexed fundingSourceRoot,
        address beneficiary,
        uint256 amount,
        uint256 predecessorSequence,
        uint256 successorSequence
    );
    event BountyPaid(
        bytes32 indexed payableId,
        bytes32 indexed operationRoot,
        bytes32 indexed fundingSourceRoot,
        address beneficiary,
        uint256 amount,
        uint256 sequence,
        bytes32 predecessorRoot,
        bytes32 successorRoot,
        uint256 reserveBalanceBefore,
        uint256 reserveBalanceAfter,
        uint256 beneficiaryBalanceBefore,
        uint256 beneficiaryBalanceAfter
    );

    error Unauthorized();
    error InvalidAmount();
    error InvalidSource();
    error InvalidPayable();
    error AlreadySettled();
    error NotBeneficiary();
    error Reentrant();
    error TransferFailed();
    error BalanceDeltaMismatch();

    constructor(address fundingAuthority_, address awardAuthority_) {
        if (fundingAuthority_ == address(0) || awardAuthority_ == address(0)) {
            revert Unauthorized();
        }
        fundingAuthority = fundingAuthority_;
        awardAuthority = awardAuthority_;
    }

    /// @notice Deposit real native assets from the configured funding authority.
    /// The returned source root binds the chain, contract, funder, amount and
    /// unique sequence. A source root cannot be reused for another deposit.
    function fundReserve() external payable returns (bytes32 sourceRoot) {
        if (msg.sender != fundingAuthority) revert Unauthorized();
        if (entered) revert Reentrant();
        if (msg.value == 0) revert InvalidAmount();
        uint256 sequence = ++nextFundingSequence;
        sourceRoot = keccak256(abi.encode(block.chainid, address(this), msg.sender, msg.value, sequence));
        if (reserveSources[sourceRoot].funder != address(0)) revert InvalidSource();
        reserveSources[sourceRoot] = ReserveSource(msg.sender, msg.value, msg.value, sequence);
        emit ReserveFunded(sourceRoot, msg.sender, msg.value, sequence, address(this).balance);
    }

    /// @notice Approve a full bounty payable against one observed reserve deposit.
    /// Researcher identity is the beneficiary address and may be pseudonymous.
    function approveBounty(
        bytes32 payableId,
        bytes32 operationRoot,
        bytes32 fundingSourceRoot,
        address beneficiary,
        uint256 amount
    ) external {
        if (msg.sender != awardAuthority) revert Unauthorized();
        if (entered) revert Reentrant();
        if (payableId == bytes32(0) || operationRoot == bytes32(0) || beneficiary == address(0) || amount == 0) {
            revert InvalidPayable();
        }
        if (payables[payableId].lifecycle != Lifecycle.None) revert InvalidPayable();
        ReserveSource storage source = reserveSources[fundingSourceRoot];
        if (source.funder != fundingAuthority || source.available < amount) revert InvalidSource();
        source.available -= amount;
        payables[payableId] = PayableRecord({
            operationRoot: operationRoot,
            fundingSourceRoot: fundingSourceRoot,
            beneficiary: beneficiary,
            amount: amount,
            sequence: 1,
            lifecycle: Lifecycle.Claimable
        });
        totalLiabilities += amount;
        emit BountyApproved(payableId, operationRoot, fundingSourceRoot, beneficiary, amount, 0, 1);
    }

    /// @notice Beneficiary claims the entire payable once. Effects are applied
    /// before the call and revert atomically if transfer or balance checks fail.
    function claim(bytes32 payableId) external {
        if (entered) revert Reentrant();
        PayableRecord storage record = payables[payableId];
        if (record.lifecycle != Lifecycle.Claimable) revert AlreadySettled();
        if (msg.sender != record.beneficiary) revert NotBeneficiary();

        uint256 amount = record.amount;
        uint256 reserveBefore = address(this).balance;
        uint256 beneficiaryBefore = record.beneficiary.balance;
        if (amount == 0 || amount > reserveBefore) revert InvalidAmount();

        bytes32 predecessorRoot = payableRoot(payableId, record);
        uint256 sequence = ++nextPayoutSequence;
        record.lifecycle = Lifecycle.Settled;
        record.sequence++;
        totalLiabilities -= amount;
        totalPaid += amount;
        entered = true;
        (bool ok,) = payable(record.beneficiary).call{value: amount}("");
        if (!ok) revert TransferFailed();
        uint256 reserveAfter = address(this).balance;
        uint256 beneficiaryAfter = record.beneficiary.balance;
        if (reserveAfter != reserveBefore - amount || beneficiaryAfter != beneficiaryBefore + amount) {
            revert BalanceDeltaMismatch();
        }
        entered = false;
        bytes32 successorRoot = payableRoot(payableId, record);
        emit BountyPaid(
            payableId,
            record.operationRoot,
            record.fundingSourceRoot,
            record.beneficiary,
            amount,
            sequence,
            predecessorRoot,
            successorRoot,
            reserveBefore,
            reserveAfter,
            beneficiaryBefore,
            beneficiaryAfter
        );
    }

    function payableRoot(bytes32 payableId, PayableRecord memory record) public pure returns (bytes32) {
        return keccak256(
            abi.encode(
                "bounty-payable.native.v1",
                payableId,
                record.operationRoot,
                record.fundingSourceRoot,
                record.beneficiary,
                record.amount,
                record.sequence,
                record.lifecycle
            )
        );
    }

    function payableLifecycle(bytes32 payableId) external view returns (Lifecycle) {
        return payables[payableId].lifecycle;
    }
}
