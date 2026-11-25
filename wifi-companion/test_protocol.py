import unittest
from protocol import ProtocolError, decode_message


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

    def test_rejects_oversized_or_unframed_input(self):
        with self.assertRaises(ProtocolError):
            decode_message(b"x" * 4097)
        with self.assertRaises(ProtocolError):
            decode_message(b'{"type":"release"}')


if __name__ == "__main__":
    unittest.main()
