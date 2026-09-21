//! Versioned V2 source-assurance statement primitives.
//!
//! This module is intentionally assurance-only: it derives canonical roots and
//! 192-byte public values, but it does not authorize reserve or allocator state.

use serde_json::Value;
use sha3::{Digest, Keccak256};

pub const PREPARED_OPERATION_DOMAIN: &[u8] = b"prf.prepared-operation.v2";
pub const ECONOMIC_STATE_DOMAIN: &[u8] = b"prf.economic-reserve-state.v2";
pub const OBSERVATION_DOMAIN: &[u8] = b"prf.authoritative-reserve-observation.v2";
pub const PUBLIC_VALUES_DOMAIN: &[u8] =
    b"prf/onchain-shortfall-allocation-binding-public-values.v2";
pub const PUBLIC_VALUES_LEN: usize = 192;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PreparedOperationV2 {
    pub operation_id: [u8; 32],
    pub initiator: [u8; 32],
    pub chain_id: u64,
    pub reserve: [u8; 32],
    pub token: [u8; 32],
    pub allocator: [u8; 32],
    pub configuration_root: [u8; 32],
    pub configuration_epoch: u64,
    pub source_verifier_id: [u8; 32],
    pub execution_verifier_id: [u8; 32],
    pub source_program_vkey: [u8; 32],
    pub source_program_elf_sha256: [u8; 32],
    pub source_statement_schema: [u8; 32],
    pub source_public_values_schema: [u8; 32],
    pub execution_statement_schema: [u8; 32],
    pub execution_public_values_schema: [u8; 32],
    pub policy_root: [u8; 32],
    pub residual_recipient: [u8; 32],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EconomicReserveStateV2 {
    pub prepared_operation_root: [u8; 32],
    pub reserve: [u8; 32],
    pub token: [u8; 32],
    pub gross_balance: u128,
    pub reserved_before: u128,
    pub protected_capital: u128,
    pub available_amount: u128,
    pub availability_revision: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AuthoritativeReserveObservationV2 {
    pub prepared_operation_root: [u8; 32],
    pub reserve_state_commitment: [u8; 32],
    pub snapshot_id: u64,
    pub created_at_block: u64,
}

impl AuthoritativeReserveObservationV2 {
    pub fn root(&self) -> [u8; 32] {
        hash_words(
            OBSERVATION_DOMAIN,
            &[
                self.prepared_operation_root,
                self.reserve_state_commitment,
                word_u64(self.snapshot_id),
                word_u64(self.created_at_block),
            ],
        )
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceBoundPublicValuesV2 {
    pub prepared_operation_root: [u8; 32],
    pub reserve_state_commitment: [u8; 32],
    pub allocation_binding_root: [u8; 32],
    pub realized_statement_root: [u8; 32],
    pub operation_id: [u8; 32],
}

fn hash_words(domain: &[u8], words: &[[u8; 32]]) -> [u8; 32] {
    let mut hasher = Keccak256::new();
    hasher.update(Keccak256::digest(domain));
    for word in words {
        hasher.update(word);
    }
    hasher.finalize().into()
}

fn word_u64(value: u64) -> [u8; 32] {
    word_u128(value as u128)
}
fn word_u128(value: u128) -> [u8; 32] {
    let mut word = [0u8; 32];
    word[16..].copy_from_slice(&value.to_be_bytes());
    word
}

impl PreparedOperationV2 {
    pub fn root(&self) -> [u8; 32] {
        hash_words(
            PREPARED_OPERATION_DOMAIN,
            &[
                self.operation_id,
                self.initiator,
                word_u64(self.chain_id),
                self.reserve,
                self.token,
                self.allocator,
                self.configuration_root,
                word_u64(self.configuration_epoch),
                self.source_verifier_id,
                self.execution_verifier_id,
                self.source_program_vkey,
                self.source_program_elf_sha256,
                self.source_statement_schema,
                self.source_public_values_schema,
                self.execution_statement_schema,
                self.execution_public_values_schema,
                self.policy_root,
                self.residual_recipient,
            ],
        )
    }
}

impl EconomicReserveStateV2 {
    pub fn commitment(&self) -> [u8; 32] {
        hash_words(
            ECONOMIC_STATE_DOMAIN,
            &[
                self.prepared_operation_root,
                self.reserve,
                self.token,
                word_u128(self.gross_balance),
                word_u128(self.reserved_before),
                word_u128(self.protected_capital),
                word_u128(self.available_amount),
                word_u64(self.availability_revision),
            ],
        )
    }
}

pub fn derive_fixture(
    input: &Value,
) -> Result<
    (
        PreparedOperationV2,
        EconomicReserveStateV2,
        SourceBoundPublicValuesV2,
    ),
    String,
> {
    let p = input
        .get("prepared-operation")
        .ok_or("prepared-operation required")?;
    let bytes = |key: &str| parse_word(p[key].as_str().ok_or_else(|| format!("{key} required"))?);
    let number = |object: &Value, key: &str| {
        object[key]
            .as_str()
            .ok_or_else(|| format!("{key} required"))?
            .parse::<u128>()
            .map_err(|_| format!("invalid {key}"))
    };
    let prepared = PreparedOperationV2 {
        operation_id: bytes("operation-id")?,
        initiator: bytes("initiator")?,
        chain_id: number(p, "chain-id")? as u64,
        reserve: bytes("reserve")?,
        token: bytes("token")?,
        allocator: bytes("allocator")?,
        configuration_root: bytes("configuration-root")?,
        configuration_epoch: number(p, "configuration-epoch")? as u64,
        source_verifier_id: bytes("source-verifier-id")?,
        execution_verifier_id: bytes("execution-verifier-id")?,
        source_program_vkey: bytes("source-program-vkey")?,
        source_program_elf_sha256: bytes("source-program-elf-sha256")?,
        source_statement_schema: bytes("source-statement-schema")?,
        source_public_values_schema: bytes("source-public-values-schema")?,
        execution_statement_schema: bytes("execution-statement-schema")?,
        execution_public_values_schema: bytes("execution-public-values-schema")?,
        policy_root: bytes("policy-root")?,
        residual_recipient: bytes("residual-recipient")?,
    };
    let s = input
        .get("economic-reserve-state")
        .ok_or("economic-reserve-state required")?;
    let prepared_root = prepared.root();
    let state = EconomicReserveStateV2 {
        prepared_operation_root: prepared_root,
        reserve: prepared.reserve,
        token: prepared.token,
        gross_balance: number(s, "gross-balance")?,
        reserved_before: number(s, "reserved-before")?,
        protected_capital: number(s, "protected-capital")?,
        available_amount: number(s, "available-amount")?,
        availability_revision: number(s, "availability-revision")? as u64,
    };
    if state.reserved_before + state.protected_capital > state.gross_balance
        || state.available_amount
            != state.gross_balance - state.reserved_before - state.protected_capital
    {
        return Err("invalid economic reserve accounting".into());
    }
    let values = SourceBoundPublicValuesV2 {
        prepared_operation_root: prepared_root,
        reserve_state_commitment: state.commitment(),
        allocation_binding_root: parse_word(
            input["allocation-binding-root"]
                .as_str()
                .ok_or("allocation-binding-root required")?,
        )?,
        realized_statement_root: parse_word(
            input["realized-statement-root"]
                .as_str()
                .ok_or("realized-statement-root required")?,
        )?,
        operation_id: prepared.operation_id,
    };
    Ok((prepared, state, values))
}

fn parse_word(value: &str) -> Result<[u8; 32], String> {
    let raw = value.strip_prefix("0x").unwrap_or(value);
    let decoded = hex::decode(raw).map_err(|_| "invalid bytes32".to_owned())?;
    if decoded.len() > 32 {
        return Err("invalid bytes32".into());
    }
    let mut word = [0u8; 32];
    word[32 - decoded.len()..].copy_from_slice(&decoded);
    Ok(word)
}

impl SourceBoundPublicValuesV2 {
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(PUBLIC_VALUES_LEN);
        out.extend_from_slice(&Keccak256::digest(PUBLIC_VALUES_DOMAIN));
        out.extend_from_slice(&self.prepared_operation_root);
        out.extend_from_slice(&self.reserve_state_commitment);
        out.extend_from_slice(&self.allocation_binding_root);
        out.extend_from_slice(&self.realized_statement_root);
        out.extend_from_slice(&self.operation_id);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn word(n: u8) -> [u8; 32] {
        [n; 32]
    }

    #[test]
    fn emits_six_32_byte_public_values_words() {
        let values = SourceBoundPublicValuesV2 {
            prepared_operation_root: word(1),
            reserve_state_commitment: word(2),
            allocation_binding_root: word(3),
            realized_statement_root: word(4),
            operation_id: word(5),
        };
        let bytes = values.encode();
        assert_eq!(bytes.len(), PUBLIC_VALUES_LEN);
        assert_eq!(
            &bytes[..32],
            Keccak256::digest(PUBLIC_VALUES_DOMAIN).as_slice()
        );
    }

    #[test]
    fn fixture_derives_v2_assurance_statement() {
        let fixture: Value = serde_json::from_str(include_str!(
            "../tests/fixtures/onchain-shortfall-v2-assurance.json"
        ))
        .unwrap();
        let (prepared, state, values) = derive_fixture(&fixture).unwrap();
        assert_eq!(prepared.operation_id, values.operation_id);
        assert_eq!(prepared.root(), state.prepared_operation_root);
        assert_eq!(state.commitment(), values.reserve_state_commitment);
        assert_eq!(values.encode().len(), PUBLIC_VALUES_LEN);
    }

    #[test]
    fn golden_vector_is_stable() {
        let prepared = PreparedOperationV2 {
            operation_id: word(1),
            initiator: word(2),
            chain_id: 31337,
            reserve: word(3),
            token: word(4),
            allocator: word(5),
            configuration_root: word(6),
            configuration_epoch: 7,
            source_verifier_id: word(8),
            execution_verifier_id: word(9),
            source_program_vkey: word(10),
            source_program_elf_sha256: word(11),
            source_statement_schema: word(12),
            source_public_values_schema: word(13),
            execution_statement_schema: word(14),
            execution_public_values_schema: word(15),
            policy_root: word(16),
            residual_recipient: word(17),
        };
        let prepared_root = prepared.root();
        let state = EconomicReserveStateV2 {
            prepared_operation_root: prepared_root,
            reserve: word(3),
            token: word(4),
            gross_balance: 100,
            reserved_before: 20,
            protected_capital: 10,
            available_amount: 70,
            availability_revision: 17,
        };
        let commitment = state.commitment();
        let values = SourceBoundPublicValuesV2 {
            prepared_operation_root: prepared_root,
            reserve_state_commitment: commitment,
            allocation_binding_root: word(18),
            realized_statement_root: word(19),
            operation_id: word(1),
        }
        .encode();
        assert_eq!(
            hex::encode(prepared_root),
            "9a66110648a9eacae606759eef7844a13b51ae43c591b36a4a151d0e02df43b4"
        );
        assert_eq!(
            hex::encode(commitment),
            "e57f2af51e6c16fd0285c44ff2c99963819c6f5559d5b979de7c9c50026114ac"
        );
        assert_eq!(hex::encode(values), "a069c379e311996c73ea1f69edb070bfddbb573b770b3c5aec52bad4b255aa879a66110648a9eacae606759eef7844a13b51ae43c591b36a4a151d0e02df43b4e57f2af51e6c16fd0285c44ff2c99963819c6f5559d5b979de7c9c50026114ac121212121212121212121212121212121212121212121212121212121212121213131313131313131313131313131313131313131313131313131313131313130101010101010101010101010101010101010101010101010101010101010101");
    }

    #[test]
    fn authoritative_observation_binds_ratification_metadata() {
        let base = AuthoritativeReserveObservationV2 {
            prepared_operation_root: word(1),
            reserve_state_commitment: word(2),
            snapshot_id: 7,
            created_at_block: 100,
        };
        let mut changed = base.clone();
        changed.created_at_block = 101;
        assert_ne!(base.root(), changed.root());
        let mut different_snapshot = base.clone();
        different_snapshot.snapshot_id = 8;
        assert_ne!(base.root(), different_snapshot.root());
    }

    #[test]
    fn availability_revision_changes_commitment_even_when_state_returns() {
        let base = EconomicReserveStateV2 {
            prepared_operation_root: word(1),
            reserve: word(2),
            token: word(3),
            gross_balance: 100,
            reserved_before: 20,
            protected_capital: 10,
            available_amount: 70,
            availability_revision: 41,
        };
        let mut returned = base.clone();
        returned.availability_revision = 43;
        assert_ne!(base.commitment(), returned.commitment());
    }
}
