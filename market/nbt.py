"""Minimal reader for the NBT blobs Hypixel puts in `item_bytes`.

Hypixel sends auction items as base64 → gzip → NBT (still the old 1.8 layout):
    {i: [ {id: short, Count: byte, tag: {ExtraAttributes: {id: "HYPERION", ...}, display: {...}}} ]}
Only reading is needed, so this is a small recursive parser with no dependencies.
"""
from __future__ import annotations

import base64
import gzip
import io
import struct

_END, _BYTE, _SHORT, _INT, _LONG, _FLOAT, _DOUBLE, _BYTE_ARRAY, _STRING, _LIST, _COMPOUND, _INT_ARRAY, _LONG_ARRAY = range(13)


class _Reader:
    def __init__(self, data: bytes):
        self.buf = io.BytesIO(data)

    def read(self, n: int) -> bytes:
        b = self.buf.read(n)
        if len(b) != n:
            raise ValueError("truncated NBT")
        return b

    def u8(self) -> int:
        return self.read(1)[0]

    def i16(self) -> int:
        return struct.unpack(">h", self.read(2))[0]

    def i32(self) -> int:
        return struct.unpack(">i", self.read(4))[0]

    def string(self) -> str:
        n = struct.unpack(">H", self.read(2))[0]
        return self.read(n).decode("utf-8", errors="replace")

    def payload(self, tag: int):
        if tag == _BYTE:
            return struct.unpack(">b", self.read(1))[0]
        if tag == _SHORT:
            return self.i16()
        if tag == _INT:
            return self.i32()
        if tag == _LONG:
            return struct.unpack(">q", self.read(8))[0]
        if tag == _FLOAT:
            return struct.unpack(">f", self.read(4))[0]
        if tag == _DOUBLE:
            return struct.unpack(">d", self.read(8))[0]
        if tag == _BYTE_ARRAY:
            return self.read(self.i32())
        if tag == _STRING:
            return self.string()
        if tag == _LIST:
            inner = self.u8()
            n = self.i32()
            return [self.payload(inner) for _ in range(max(n, 0))]
        if tag == _COMPOUND:
            out = {}
            while True:
                t = self.u8()
                if t == _END:
                    return out
                name = self.string()
                out[name] = self.payload(t)
        if tag == _INT_ARRAY:
            n = self.i32()
            return list(struct.unpack(f">{n}i", self.read(4 * n)))
        if tag == _LONG_ARRAY:
            n = self.i32()
            return list(struct.unpack(f">{n}q", self.read(8 * n)))
        raise ValueError(f"unknown NBT tag {tag}")


def parse_nbt(raw: bytes) -> dict:
    """Parse an (uncompressed) NBT document; returns the root compound."""
    r = _Reader(raw)
    tag = r.u8()
    if tag != _COMPOUND:
        raise ValueError("root is not a compound")
    r.string()  # root name, usually ""
    return r.payload(_COMPOUND)


def decode_item_bytes(item_bytes) -> list[dict]:
    """Decode Hypixel `item_bytes` (str, or {"type":0,"data":str}) into a list of item compounds."""
    if isinstance(item_bytes, dict):
        item_bytes = item_bytes.get("data", "")
    raw = gzip.decompress(base64.b64decode(item_bytes))
    root = parse_nbt(raw)
    items = root.get("i", [])
    return [it for it in items if isinstance(it, dict)]
