import hashlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

import host
from host import build_pairing_uri, is_allowed_peer_address
from protocol import ProtocolError, check_pairing_code, check_trusted_client_token, decode_message


class ProtocolTests(unittest.TestCase):
    def test_mouse_message(self):
        raw = b'{"type":"mouse","buttons":1,"dx":12,"dy":-9,"wheel":1}\n'
        self.assertEqual(decode_message(raw), {
            "type": "mouse", "buttons": 1, "dx": 12, "dy": -9, "wheel": 1,
        })

    def test_key_message(self):
        raw = b'{"type":"key","modifiers":8,"key":4}\n'
        self.assertEqual(decode_message(raw), {
            "type": "key", "modifiers": 8, "key": 4,
        })

    def test_ping_message(self):
        self.assertEqual(decode_message(b'{"type":"ping"}\\n'), {"type": "ping"})

    def test_release_message(self):
        self.assertEqual(decode_message(b'{"type":"release"}\n'), {"type": "release"})

    def test_rejects_unknown_message_type(self):
        with self.assertRaises(ProtocolError):
            decode_message(b'{"type":"shell","command":"whoami"}\n')

    def test_rejects_out_of_range_mouse_buttons(self):
        with self.assertRaises(ProtocolError):
            decode_message(b'{"type":"mouse","buttons":99,"dx":0,"dy":0,"wheel":0}\n')

    def test_rejects_boolean_as_integer(self):
        with self.assertRaises(ProtocolError):
            decode_message(b'{"type":"key","modifiers":true,"key":4}\n')

    def test_pairing_code_rate_limit_and_recovery(self):
        state = {"failures": 0, "locked_until": 0.0}
        for now in range(10, 15):
            self.assertFalse(check_pairing_code("00000000", "12345678", state, now=float(now)))
        self.assertGreater(state["locked_until"], 14.0)
        self.assertFalse(check_pairing_code("12345678", "12345678", state, now=20.0))
        self.assertTrue(check_pairing_code(
            "12345678", "12345678", state, now=state["locked_until"] + 0.1
        ))
        self.assertEqual(state["failures"], 0)

    def test_trusted_client_token_authentication_and_lockout(self):
        token = "A" * 43
        digest = hashlib.sha256(token.encode("ascii")).hexdigest()
        state = {"failures": 0, "locked_until": 0.0}
        self.assertTrue(check_trusted_client_token(token, {digest}, state, now=1.0))
        self.assertEqual(state["failures"], 0)
        self.assertFalse(check_trusted_client_token("bad-token", {digest}, state, now=2.0))
        self.assertEqual(state["failures"], 1)

    def test_trusted_client_store_persists_hashes_not_bearer_tokens(self):
        token = "B" * 43
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            store_file = directory / "trusted-clients.json"
            with patch.object(host, "APP_DIR", directory), patch.object(
                host, "TRUSTED_CLIENTS_FILE", store_file
            ):
                trusted = set()
                host.save_trusted_client_token(token, trusted)
                digest = hashlib.sha256(token.encode("ascii")).hexdigest()
                self.assertEqual(trusted, {digest})
                self.assertEqual(host.load_trusted_client_hashes(), {digest})
                self.assertNotIn(token, store_file.read_text(encoding="utf-8"))
                host.forget_all_trusted_clients()
                self.assertFalse(store_file.exists())

    def test_pairing_rejects_malformed_code_and_expected_value(self):
        state = {"failures": 0, "locked_until": 0.0}
        self.assertFalse(check_pairing_code("1234567", "12345678", state, now=1.0))
        self.assertFalse(check_pairing_code("12345678", "１２３４５６７８", state, now=2.0))
        self.assertEqual(state["failures"], 2)

    def test_mouse_message_enforces_all_numeric_bounds(self):
        invalid_messages = [
            b'{"type":"mouse","buttons":0,"dx":32768,"dy":0,"wheel":0}\n',
            b'{"type":"mouse","buttons":0,"dx":0,"dy":-32769,"wheel":0}\n',
            b'{"type":"mouse","buttons":0,"dx":0,"dy":0,"wheel":128}\n',
        ]
        for raw in invalid_messages:
            with self.subTest(raw=raw), self.assertRaises(ProtocolError):
                decode_message(raw)

    def test_non_ascii_pairing_code_is_rejected_without_crashing(self):
        state = {"failures": 0, "locked_until": 0.0}
        self.assertFalse(check_pairing_code("１２３４５６７８", "12345678", state, now=1.0))
        self.assertEqual(state["failures"], 1)

    def test_partial_mouse_press_failure_keeps_successful_button_tracked_for_release(self):
        left, right, middle = object(), object(), object()
        fake_mouse_module = SimpleNamespace(Button=SimpleNamespace(left=left, right=right, middle=middle))
        fake_key = SimpleNamespace(
            ctrl_l=object(), shift_l=object(), alt_l=object(),
            ctrl_r=object(), shift_r=object(), alt_r=object(),
        )
        fake_keyboard_module = SimpleNamespace(
            Key=fake_key,
            KeyCode=SimpleNamespace(from_char=lambda value: value),
        )

        class FakeMouseController:
            def __init__(self):
                self.pressed = []
                self.released = []

            def press(self, button):
                self.pressed.append(button)
                if len(self.pressed) == 2:
                    raise RuntimeError("simulated second-button failure")

            def release(self, button):
                self.released.append(button)

            def move(self, dx, dy):
                pass

            def scroll(self, dx, dy):
                pass

        controller = FakeMouseController()
        held = set()
        with patch.object(host, "mouse", fake_mouse_module), patch.object(host, "keyboard", fake_keyboard_module):
            with self.assertRaises(RuntimeError):
                host.apply_input(
                    {"type": "mouse", "buttons": 3, "dx": 0, "dy": 0, "wheel": 0},
                    controller, object(), held,
                )
            self.assertEqual(len(held), 1)
            host.apply_input({"type": "release"}, controller, object(), held)

        self.assertEqual(held, set())
        self.assertEqual(len(controller.released), 1)
        self.assertEqual(controller.released[0], next(iter(held), controller.pressed[0]))

    def test_companion_accepts_only_local_peer_addresses(self):
        self.assertTrue(is_allowed_peer_address("192.168.1.25"))
        self.assertTrue(is_allowed_peer_address("10.0.0.8"))
        self.assertTrue(is_allowed_peer_address("127.0.0.1"))
        self.assertFalse(is_allowed_peer_address("8.8.8.8"))
        self.assertFalse(is_allowed_peer_address("203.0.113.10"))
        self.assertFalse(is_allowed_peer_address("not-an-ip"))

    def test_qr_pairing_uri_contains_required_values(self):
        uri = build_pairing_uri("192.168.1.20", 47777, "12345678", "a" * 64)
        parsed = urlparse(uri)
        query = parse_qs(parsed.query)
        self.assertEqual(parsed.scheme, "wastiairpad")
        self.assertEqual(parsed.netloc, "pair")
        self.assertEqual(query["host"], ["192.168.1.20"])
        self.assertEqual(query["port"], ["47777"])
        self.assertEqual(query["code"], ["12345678"])
        self.assertEqual(query["fp"], ["a" * 64])

    def test_rejects_oversized_or_unframed_input(self):
        with self.assertRaises(ProtocolError):
            decode_message(b"x" * 4097)
        with self.assertRaises(ProtocolError):
            decode_message(b'{"type":"release"}')


if __name__ == "__main__":
    unittest.main()
