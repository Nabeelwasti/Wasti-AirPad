"""Dependency-free message validator for the AirPad desktop companion."""
from __future__ import annotations
import hmac
import json
import time
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


def check_pairing_code(supplied: str, expected: str, state: dict, now: float | None = None) -> bool:
    """Constant-time pairing check with a connection-wide 60-second lockout."""
    current = time.monotonic() if now is None else now
    if current < state.get("locked_until", 0.0):
        return False
    candidate = supplied if isinstance(supplied, str) else str(supplied)
    valid_candidate = candidate.isascii() and len(candidate) == 8 and candidate.isdigit()
    valid_expected = isinstance(expected, str) and expected.isascii() and len(expected) == 8 and expected.isdigit()
    # Compare fixed-length ASCII buffers so malformed input does not create a
    # length-dependent early exit in compare_digest.
    candidate_bytes = candidate.encode("ascii") if valid_candidate else b"\\x00" * 8
    expected_bytes = expected.encode("ascii") if valid_expected else b"\\xff" * 8
    if hmac.compare_digest(candidate_bytes, expected_bytes) and valid_expected:
        state["failures"] = 0
        state["locked_until"] = 0.0
        return True
    failures = int(state.get("failures", 0)) + 1
    if failures >= 5:
        state["failures"] = 0
        state["locked_until"] = current + 60.0
    else:
        state["failures"] = failures
    return False
