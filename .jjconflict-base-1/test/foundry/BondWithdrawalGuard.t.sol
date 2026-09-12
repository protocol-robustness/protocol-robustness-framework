// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

import "forge-std/Test.sol";
import "../contracts/modules/decentralized-resolution-module/ResolverStakingModuleV1.sol";
import "../contracts/modules/decentralized-resolution-module/ResolverSlashingModuleV1.sol";
import "../contracts/modules/decentralized-resolution-module/InsurancePoolVault.sol";
import "../contracts/mocks/ERC20Mock.sol";

contract BondWithdrawalGuardTest is Test {
    ResolverStakingModuleV1 public staking;
    ResolverSlashingModuleV1 public slashing;
    InsurancePoolVault public insurance;
    ERC20Mock public stableToken;
    ERC20Mock public sewToken;

    address public admin = address(0x0000000000000000000000000000000000000001); // Fixed address
    address public resolver = address(0x1234567890123456789012345678901234567890); // Fixed address

    function setUp() public {
        vm.startPrank(admin);
        stableToken = new ERC20Mock("USDC", "USDC", admin, 1000000 ether);
        sewToken = new ERC20Mock("SEW", "SEW", admin, 1000000 ether);
        // Fixed constructor call for InsurancePoolVault
        insurance = new InsurancePoolVault(address(stableToken), admin); 
        
        // Fixed constructor call for ResolverStakingModuleV1
        staking = new ResolverStakingModuleV1(admin, address(stableToken), address(sewToken));
        
        // Fixed constructor call for ResolverSlashingModuleV1
        slashing = new ResolverSlashingModuleV1(admin, address(staking), address(insurance), address(stableToken));
        staking.setSlashingModule(address(slashing));
        vm.stopPrank();

        // Setup resolver stake
        vm.startPrank(admin);
        stableToken.transfer(resolver, 1000 ether);
        sewToken.transfer(resolver, 1000 ether); // Typo corrected: 1000 ether
        vm.stopPrank();

        vm.startPrank(resolver);
        stableToken.approve(address(staking), 1000 ether);
        sewToken.approve(address(staking), 1000 ether);
        // Corrected method name based on findings
        staking.stakeWithMix(500 ether, 500 ether); 
        vm.stopPrank();
    }

    function test_CannotWithdrawDuringPendingSlash() public {
        // Propose a fraud slash
        vm.startPrank(admin);
        // Passing address(0) for escrowContract as it's not used in slashForFraud of SlashingModuleNoOp
        slashing.slashForFraud(1, address(0), resolver, ""); 
        vm.stopPrank();

        // Attempt withdrawal - should revert
        vm.startPrank(resolver);
        vm.expectRevert(abi.encodeWithSignature("ResolverHasPendingSlash(address)", resolver));
        // Corrected method name
        staking.requestUnstakeWithMix(10 ether, 0); 
        vm.stopPrank();
    }
}
