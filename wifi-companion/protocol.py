"""Dependency-free message validator for the AirPad desktop companion."""
from __future__ import annotations
import json
from typing import Any

MAX_MESSAGE_BYTES = 4096


class ProtocolError(ValueError):
    pass


def decode_message(raw: bytes) -> dict[str, Any]:
    if not raw or len(raw) > MAX_MESSAGE_BYTES or not raw.endswith(b"\n"):
        raise ProtocolError("Invalid message size or framing.")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("Invalid JSON message.") from exc
    if not isinstance(value, dict):
        raise ProtocolError("Message must be an object.")
    kind = value.get("type")
    if kind == "mouse":
        return {
            "type": kind,
            "buttons": _integer(value, "buttons", 0, 7),
            "dx": _integer(value, "dx", -32768, 32767),
            "dy": _integer(value, "dy", -32768, 32767),
            "wheel": _integer(value, "wheel", -127, 127),
        }
    if kind == "key":
        return {
            "type": kind,
            "modifiers": _integer(value, "modifiers", 0, 255),
            "key": _integer(value, "key", 0, 255),
        }
    if kind == "release":
        return {"type": kind}
    raise ProtocolError("Unsupported input message.")


def _integer(value: dict[str, Any], key: str, minimum: int, maximum: int) -> int:
    item = value.get(key)
    if isinstance(item, bool) or not isinstance(item, int):
        raise ProtocolError(f"{key} must be an integer.")
    if not minimum <= item <= maximum:
        raise ProtocolError(f"{key} is outside the permitted range.")
    return item
