// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.33;

/// @notice Fixed-supply token for local researcher payout demonstrations.
/// @dev A minimal example token; it has no production controls or audit.
contract ResearcherDemoToken {
    string public constant name = "Researcher Demo Token";
    string public constant symbol = "RDT";
    uint8 public constant decimals = 18;
    uint256 public totalSupply;

    mapping(address => uint256) public balanceOf;
    mapping(address => mapping(address => uint256)) public allowance;

    event Transfer(address indexed from, address indexed to, uint256 value);
    event Approval(address indexed owner, address indexed spender, uint256 value);

    constructor(uint256 initialSupply) {
        totalSupply = initialSupply;
        balanceOf[msg.sender] = initialSupply;
        emit Transfer(address(0), msg.sender, initialSupply);
    }

    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount;
        emit Approval(msg.sender, spender, amount);
        return true;
    }

    function transfer(address recipient, uint256 amount) external returns (bool) {
        _transfer(msg.sender, recipient, amount);
        return true;
    }

    function transferFrom(address sender, address recipient, uint256 amount) external returns (bool) {
        uint256 permitted = allowance[sender][msg.sender];
        require(permitted >= amount, "allowance exceeded");
        if (permitted != type(uint256).max) allowance[sender][msg.sender] = permitted - amount;
        _transfer(sender, recipient, amount);
        return true;
    }

    function _transfer(address sender, address recipient, uint256 amount) private {
        require(recipient != address(0), "zero recipient");
        uint256 balance = balanceOf[sender];
        require(balance >= amount, "balance exceeded");
        unchecked {
            balanceOf[sender] = balance - amount;
            balanceOf[recipient] += amount;
        }
        emit Transfer(sender, recipient, amount);
    }
}
