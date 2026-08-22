use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    Nil,
    Bool(bool),
    Int(i64),
    Str(String),
    /// (namespace, name) — nil namespace for unqualified keywords
    Keyword(Option<String>, String),
    Vec(Vec<Value>),
    Map(Vec<(Value, Value)>),
}

#[derive(Debug)]
pub struct ParseError(String);

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "EDN parse error: {}", self.0)
    }
}

impl std::error::Error for ParseError {}

pub fn parse(input: &str) -> Result<Value, ParseError> {
    let mut parser = Parser::new(input);
    parser.skip_ws();
    let v = parser.parse_value()?;
    Ok(v)
}

struct Parser<'a> {
    chars: Vec<char>,
    pos: usize,
    _marker: std::marker::PhantomData<&'a str>,
}

impl<'a> Parser<'a> {
    fn new(input: &'a str) -> Self {
        Parser {
            chars: input.chars().collect(),
            pos: 0,
            _marker: std::marker::PhantomData,
        }
    }

    fn peek(&self) -> Option<char> {
        self.chars.get(self.pos).copied()
    }

    fn peek_at(&self, offset: usize) -> Option<char> {
        self.chars.get(self.pos + offset).copied()
    }

    fn next(&mut self) -> Option<char> {
        let c = self.peek()?;
        self.pos += 1;
        Some(c)
    }

    fn skip_ws(&mut self) {
        while let Some(c) = self.peek() {
            if c.is_whitespace() || c == ',' {
                self.pos += 1;
            } else {
                break;
            }
        }
    }

    fn parse_value(&mut self) -> Result<Value, ParseError> {
        self.skip_ws();
        match self.peek() {
            None => Err(ParseError("unexpected end of input".into())),
            Some('{') => self.parse_map(),
            Some('[') => {
                self.next();
                self.parse_vec()
            }
            Some('#') => self.parse_tagged(),
            Some('"') => {
                let s = self.parse_string();
                Ok(Value::Str(s))
            }
            Some(':') => {
                let (ns, name) = self.parse_keyword();
                Ok(Value::Keyword(ns, name))
            }
            Some('t') => {
                if self.match_literal("true") {
                    Ok(Value::Bool(true))
                } else {
                    Err(ParseError("expected 'true'".into()))
                }
            }
            Some('f') => {
                if self.match_literal("false") {
                    Ok(Value::Bool(false))
                } else {
                    Err(ParseError("expected 'false'".into()))
                }
            }
            Some('n') => {
                if self.match_literal("nil") {
                    Ok(Value::Nil)
                } else {
                    Err(ParseError("expected 'nil'".into()))
                }
            }
            Some(c) if c.is_ascii_digit() || c == '-' || c == '+' => {
                Ok(Value::Int(self.parse_int()?))
            }
            Some(c) => Err(ParseError(format!("unexpected character: '{}'", c))),
        }
    }

    fn parse_map(&mut self) -> Result<Value, ParseError> {
        self.next(); // consume '{'
        let mut pairs: Vec<(Value, Value)> = Vec::new();
        loop {
            self.skip_ws();
            if self.peek() == Some('}') {
                self.next();
                break;
            }
            let key = self.parse_value()?;
            let val = self.parse_value()?;
            pairs.push((key, val));
        }
        Ok(Value::Map(pairs))
    }

    fn parse_tagged(&mut self) -> Result<Value, ParseError> {
        self.next(); // consume '#'
        match self.peek() {
            Some(':') => {
                self.next(); // consume ':'
                let default_ns = self.parse_symbol();
                self.skip_ws();
                if self.peek() != Some('{') {
                    return Err(ParseError("expected '{' after namespaced map tag".into()));
                }
                self.next(); // consume '{'
                self.parse_namespaced_map_body(&default_ns)
            }
            _ => Err(ParseError(
                "only namespaced maps (#:ns{...}) are supported as tags".into(),
            )),
        }
    }

    fn parse_namespaced_map_body(&mut self, default_ns: &str) -> Result<Value, ParseError> {
        let mut pairs: Vec<(Value, Value)> = Vec::new();
        loop {
            self.skip_ws();
            if self.peek() == Some('}') {
                self.next();
                break;
            }
            let key = self.parse_value()?;
            let key = match &key {
                Value::Keyword(ns, name) => {
                    let full_ns = ns.as_deref().unwrap_or(default_ns);
                    Value::Keyword(Some(full_ns.to_string()), name.clone())
                }
                Value::Str(s) => Value::Str(s.clone()),
                _ => {
                    return Err(ParseError(format!(
                        "only keyword and string keys allowed in namespaced map, got {:?}",
                        key
                    )))
                }
            };
            let val = self.parse_value()?;
            pairs.push((key, val));
        }
        Ok(Value::Map(pairs))
    }

    fn parse_vec(&mut self) -> Result<Value, ParseError> {
        let mut elems = Vec::new();
        loop {
            self.skip_ws();
            if self.peek() == Some(']') {
                self.next();
                break;
            }
            elems.push(self.parse_value()?);
        }
        Ok(Value::Vec(elems))
    }

    fn parse_string(&mut self) -> String {
        self.next(); // consume opening '"'
        let mut result = String::new();
        while let Some(c) = self.next() {
            if c == '"' {
                break;
            }
            if c == '\\' {
                if let Some(escaped) = self.next() {
                    match escaped {
                        'n' => result.push('\n'),
                        't' => result.push('\t'),
                        'r' => result.push('\r'),
                        '"' => result.push('"'),
                        '\\' => result.push('\\'),
                        other => result.push(other),
                    }
                }
            } else {
                result.push(c);
            }
        }
        result
    }

    fn parse_int(&mut self) -> Result<i64, ParseError> {
        let start = self.pos;
        if matches!(self.peek(), Some('-') | Some('+')) {
            self.next();
        }
        while let Some(c) = self.peek() {
            if c.is_ascii_digit() {
                self.next();
            } else {
                break;
            }
        }
        let text: String = self.chars[start..self.pos].iter().collect();
        text.parse::<i64>()
            .map_err(|e| ParseError(format!("invalid integer: {}", e)))
    }

    fn parse_keyword(&mut self) -> (Option<String>, String) {
        self.next(); // consume ':'
        let start = self.pos;
        while let Some(c) = self.peek() {
            if c.is_alphanumeric() || c == '-' || c == '_' || c == '?' || c == '.' || c == '/' {
                self.pos += 1;
            } else {
                break;
            }
        }
        let text: String = self.chars[start..self.pos].iter().collect();
        match text.find('/') {
            Some(idx) => {
                let ns = &text[..idx];
                let name = &text[idx + 1..];
                (Some(ns.to_string()), name.to_string())
            }
            None => (None, text),
        }
    }

    fn parse_symbol(&mut self) -> String {
        let start = self.pos;
        while let Some(c) = self.peek() {
            if c.is_alphanumeric() || c == '-' || c == '_' || c == '?' || c == '.' {
                self.pos += 1;
            } else {
                break;
            }
        }
        self.chars[start..self.pos].iter().collect()
    }

    fn match_literal(&mut self, lit: &str) -> bool {
        let chars: Vec<char> = lit.chars().collect();
        for (i, &c) in chars.iter().enumerate() {
            if self.peek_at(i) != Some(c) {
                return false;
            }
        }
        self.pos += chars.len();
        true
    }
}

pub fn map_get<'a>(map: &'a Value, key: &str) -> Option<&'a Value> {
    match map {
        Value::Map(pairs) => {
            for (k, v) in pairs {
                match k {
                    Value::Keyword(_, kn) if kn == key => return Some(v),
                    Value::Keyword(Some(ns), kn) => {
                        if format!("{}/{}", ns, kn) == key {
                            return Some(v);
                        }
                    }
                    Value::Str(s) if s == key => return Some(v),
                    _ => {}
                }
            }
            None
        }
        _ => None,
    }
}

#[allow(dead_code)]
pub fn map_get_ns<'a>(map: &'a Value, ns: &str, name: &str) -> Option<&'a Value> {
    match map {
        Value::Map(pairs) => {
            for (k, v) in pairs {
                if let Value::Keyword(kns, kn) = k {
                    if kns.as_deref() == Some(ns) && kn == name {
                        return Some(v);
                    }
                }
            }
            None
        }
        _ => None,
    }
}

#[allow(dead_code)]
pub type SortedMap = BTreeMap<String, Value>;
