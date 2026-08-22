use crate::edn;
use sha2::{Digest, Sha256};

const TAG_NULL: u8 = 0x00;
const TAG_BOOL_FALSE: u8 = 0x01;
const TAG_BOOL_TRUE: u8 = 0x02;
const TAG_INT: u8 = 0x10;
const TAG_STRING: u8 = 0x20;
const TAG_KEYWORD: u8 = 0x22;
const TAG_ARRAY: u8 = 0x30;
const TAG_MAP: u8 = 0x31;

fn encode_varuint(n: u64) -> Vec<u8> {
    let mut result = Vec::new();
    let mut n = n;
    loop {
        let mut b = (n & 0x7f) as u8;
        n >>= 7;
        if n != 0 {
            b |= 0x80;
            result.push(b);
        } else {
            result.push(b);
            break;
        }
    }
    result
}

fn zigzag(n: i64) -> u64 {
    ((n << 1) ^ (n >> 63)) as u64
}

fn canonical_keyword_string(ns: &Option<String>, name: &str) -> String {
    match ns {
        Some(ns) => format!("{}/{}", ns, name),
        None => name.to_string(),
    }
}

pub fn canonical_bytes(v: &edn::Value) -> Vec<u8> {
    match v {
        edn::Value::Nil => vec![TAG_NULL],
        edn::Value::Bool(b) => vec![if *b { TAG_BOOL_TRUE } else { TAG_BOOL_FALSE }],
        edn::Value::Int(i) => {
            let z = zigzag(*i);
            let mut out = vec![TAG_INT];
            out.extend(encode_varuint(z));
            out
        }
        edn::Value::Str(s) => {
            let bs = s.as_bytes();
            let len = encode_varuint(bs.len() as u64);
            let mut out = vec![TAG_STRING];
            out.extend(len);
            out.extend_from_slice(bs);
            out
        }
        edn::Value::Keyword(ns, name) => {
            let s = canonical_keyword_string(ns, name);
            let bs = s.as_bytes();
            let len = encode_varuint(bs.len() as u64);
            let mut out = vec![TAG_KEYWORD];
            out.extend(len);
            out.extend_from_slice(bs);
            out
        }
        edn::Value::Vec(elements) => {
            let mut out = vec![TAG_ARRAY];
            out.extend(encode_varuint(elements.len() as u64));
            for e in elements {
                out.extend(canonical_bytes(e));
            }
            out
        }
        edn::Value::Map(pairs) => {
            let mut key_bytes: Vec<(Vec<u8>, &edn::Value)> =
                pairs.iter().map(|(k, v)| (canonical_bytes(k), v)).collect();
            key_bytes.sort_by(|a, b| a.0.cmp(&b.0));
            let mut out = vec![TAG_MAP];
            out.extend(encode_varuint(pairs.len() as u64));
            for (kb, v) in &key_bytes {
                out.extend_from_slice(kb);
                out.extend(canonical_bytes(v));
            }
            out
        }
    }
}

pub fn canonical_bytes_hex(v: &edn::Value) -> String {
    let bytes = canonical_bytes(v);
    bytes.iter().map(|b| format!("{:02x}", b)).collect()
}

pub fn domain_hash(domain_tag: &str, value: &edn::Value) -> String {
    let mut hasher = Sha256::new();
    hasher.update(domain_tag.as_bytes());
    hasher.update(canonical_bytes(value));
    let result = hasher.finalize();
    format!("sha256:{}", hex::encode(result))
}

pub fn sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("sha256:{}", hex::encode(hasher.finalize()))
}

pub fn parse_sha256_ref(s: &str) -> String {
    let s = s.trim();
    if let Some(rest) = s.strip_prefix("sha256:") {
        format!("sha256:{}", rest)
    } else {
        format!("sha256:{}", s)
    }
}

/// Compute domain_hash from a hex domain tag string (not keyword).
/// The Clojure `domain-hash` resolves keyword tags via `domain-tags` map,
/// but the fixtures store the resolved string tags.
pub fn domain_hash_str(domain_tag: &str, value: &edn::Value) -> String {
    domain_hash(domain_tag, value)
}
