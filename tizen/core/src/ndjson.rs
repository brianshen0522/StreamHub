//! Incremental NDJSON splitting for `/api/sources`, which writes one JSON
//! object per line as each source probe finishes. Chunks arrive split at
//! arbitrary byte positions, so a partial line is carried to the next push.

use serde_json::Value;

#[derive(Default, Debug)]
pub struct LineBuffer {
    pending: String,
}

impl LineBuffer {
    /// Feed a decoded text chunk; returns every complete line that parsed.
    /// A line that is not valid JSON is skipped rather than ending the stream.
    pub fn push(&mut self, chunk: &str) -> Vec<Value> {
        self.pending.push_str(chunk);
        let mut out = Vec::new();
        while let Some(i) = self.pending.find('\n') {
            let line: String = self.pending.drain(..=i).collect();
            if let Some(v) = parse(&line) {
                out.push(v);
            }
        }
        out
    }

    /// The stream closed: whatever is left is the last line, if it parses.
    pub fn finish(&mut self) -> Vec<Value> {
        let rest = std::mem::take(&mut self.pending);
        parse(&rest).into_iter().collect()
    }
}

fn parse(line: &str) -> Option<Value> {
    let line = line.trim();
    if line.is_empty() {
        return None;
    }
    serde_json::from_str(line).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_across_chunks() {
        let mut b = LineBuffer::default();
        assert!(b.push("{\"a\":").is_empty());
        let got = b.push("1}\n{\"b\":2}\n{\"c\"");
        assert_eq!(got.len(), 2);
        assert_eq!(b.push(":3}").len(), 0);
        assert_eq!(b.finish().len(), 1);
    }

    #[test]
    fn skips_garbage_and_blank_lines() {
        let mut b = LineBuffer::default();
        let got = b.push("\n\nnot json\n{\"ok\":true}\n");
        assert_eq!(got, vec![serde_json::json!({"ok": true})]);
    }

    #[test]
    fn multibyte_text_is_kept_whole() {
        let mut b = LineBuffer::default();
        let got = b.push("{\"label\":\"第01集\"}\n");
        assert_eq!(got[0]["label"], "第01集");
    }
}
