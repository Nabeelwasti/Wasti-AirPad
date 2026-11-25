import unittest
from urllib.parse import parse_qs, urlparse

from host import build_pairing_uri, is_allowed_peer_address
from protocol import ProtocolError, check_pairing_code, decode_message


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
