// A minimal ZIP reader (central directory + stored/deflate), enough to check
// a .wgt without unzip in the Playwright image.
import fs from "node:fs";
import zlib from "node:zlib";

export function readZip(file) {
  const buf = fs.readFileSync(file);
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 65_557); i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`${file}: not a zip (no end of central directory)`);
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let n = 0; n < count; n += 1) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error("bad central directory");
    const method = buf.readUInt16LE(p + 10);
    const csize = buf.readUInt32LE(p + 20);
    const size = buf.readUInt32LE(p + 24);
    const nlen = buf.readUInt16LE(p + 28);
    const xlen = buf.readUInt16LE(p + 30);
    const clen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.toString("utf8", p + 46, p + 46 + nlen);
    entries.set(name, { name, method, csize, size, local });
    p += 46 + nlen + xlen + clen;
  }
  const read = (name) => {
    const e = entries.get(name);
    if (!e) return null;
    const lnlen = buf.readUInt16LE(e.local + 26);
    const lxlen = buf.readUInt16LE(e.local + 28);
    const start = e.local + 30 + lnlen + lxlen;
    const raw = buf.subarray(start, start + e.csize);
    if (e.method === 0) return Buffer.from(raw);
    if (e.method === 8) return zlib.inflateRawSync(raw);
    throw new Error(`${name}: compression method ${e.method}`);
  };
  return { entries, read, size: buf.length };
}
