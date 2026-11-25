#!/usr/bin/env python3
"""Wasti AirPad desktop companion: authenticated local-network mouse and keyboard."""
from __future__ import annotations
import argparse
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import socket
import ssl
import sys
import time
from urllib.parse import urlencode
from datetime import datetime, timedelta, timezone
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID
from zeroconf import ServiceInfo, Zeroconf
import qrcode

keyboard = None
mouse = None


def load_input_backend():
    global keyboard, mouse
    if keyboard is None or mouse is None:
        from pynput import keyboard as keyboard_module, mouse as mouse_module
        keyboard = keyboard_module
        mouse = mouse_module

from protocol import MAX_MESSAGE_BYTES, ProtocolError, decode_message

APP_DIR = Path.home() / ".wasti-airpad"
CERT_FILE = APP_DIR / "host-cert.pem"
KEY_FILE = APP_DIR / "host-key.pem"
DEFAULT_PORT = 47777


def ensure_identity():
    APP_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    if CERT_FILE.exists() and KEY_FILE.exists():
        try:
            os.chmod(KEY_FILE, 0o600)
            os.chmod(CERT_FILE, 0o600)
        except OSError:
            pass
        return CERT_FILE.read_bytes()
    private_key = ec.generate_private_key(ec.SECP256R1())
    subject = issuer = x509.Name([
        x509.NameAttribute(NameOID.COMMON_NAME, socket.gethostname() or "Wasti AirPad Host"),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Wasti AirPad"),
    ])
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject).issuer_name(issuer)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=2))
        .not_valid_after(now + timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(private_key, hashes.SHA256())
    )
    KEY_FILE.write_bytes(private_key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ))
    cert_bytes = certificate.public_bytes(serialization.Encoding.PEM)
    CERT_FILE.write_bytes(cert_bytes)
    try:
        os.chmod(KEY_FILE, 0o600)
        os.chmod(CERT_FILE, 0o600)
    except OSError:
        pass
    return cert_bytes


def local_ipv4_addresses():
    addresses = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith(("127.", "169.254.")):
                addresses.add(address)
    except OSError:
        pass
    return sorted(addresses, key=lambda value: (
        ipaddress.ip_address(value).is_private is False, value
    ))


def register_discovery_service(port, fingerprint):
    addresses = [
        ipaddress.ip_address(value).packed
        for value in local_ipv4_addresses()
        if ipaddress.ip_address(value).is_private
    ]
    if not addresses:
        return None, None
    hostname = "".join(
        char for char in socket.gethostname() if char.isalnum() or char in "-."
    ) or "airpad-host"
    service = ServiceInfo(
        "_wastiairpad._tcp.local.",
        f"Wasti AirPad {socket.gethostname()}._wastiairpad._tcp.local.",
        addresses=addresses,
        port=port,
        properties={b"fingerprint": fingerprint.encode("ascii"), b"version": b"1"},
        server=f"{hostname}.local.",
    )
    discovery = Zeroconf()
    try:
        discovery.register_service(service, allow_name_change=True)
    except Exception:
        discovery.close()
        raise
    return discovery, service


def print_pairing_qr(address, port, pairing_code, fingerprint):
    payload = "wastiairpad://pair?" + urlencode({
        "host": address,
        "port": str(port),
        "code": pairing_code,
        "fp": fingerprint,
    })
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=1,
        border=2,
    )
    qr.add_data(payload)
    qr.make(fit=True)
    print(f"Scan to pair with {address}:")
    qr.print_ascii(invert=True)


def key_from_hid(code):
    load_input_backend()
    if 4 <= code <= 29:
        return keyboard.KeyCode.from_char(chr(ord("a") + code - 4))
    if 30 <= code <= 38:
        return keyboard.KeyCode.from_char("123456789"[code - 30])
    if code == 39:
        return keyboard.KeyCode.from_char("0")
    punctuation = {
        45: "-", 46: "=", 47: "[", 48: "]", 49: "\\", 51: ";",
        52: "'", 53: "`", 54: ",", 55: ".", 56: "/",
    }
    if code in punctuation:
        return keyboard.KeyCode.from_char(punctuation[code])
    special = {
        40: keyboard.Key.enter, 41: keyboard.Key.esc, 42: keyboard.Key.backspace,
        43: keyboard.Key.tab, 44: keyboard.Key.space, 57: keyboard.Key.caps_lock,
        70: keyboard.Key.print_screen, 71: keyboard.Key.scroll_lock, 72: keyboard.Key.pause,
        73: keyboard.Key.insert, 74: keyboard.Key.home, 75: keyboard.Key.page_up,
        76: keyboard.Key.delete, 77: keyboard.Key.end, 78: keyboard.Key.page_down,
        79: keyboard.Key.right, 80: keyboard.Key.left, 81: keyboard.Key.down, 82: keyboard.Key.up,
    }
    if 58 <= code <= 69:
        return getattr(keyboard.Key, f"f{code - 57}", None)
    return special.get(code)


def modifier_keys(mask):
    load_input_backend()
    mapping = (
        (0x01, keyboard.Key.ctrl_l), (0x02, keyboard.Key.shift_l),
        (0x04, keyboard.Key.alt_l), (0x08, getattr(keyboard.Key, "cmd_l", keyboard.Key.ctrl_l)),
        (0x10, keyboard.Key.ctrl_r), (0x20, keyboard.Key.shift_r),
        (0x40, keyboard.Key.alt_r), (0x80, getattr(keyboard.Key, "cmd_r", keyboard.Key.ctrl_r)),
    )
    return [key for bit, key in mapping if mask & bit]


def apply_input(message, mouse_controller, keyboard_controller, held_buttons):
    load_input_backend()
    kind = message["type"]
    if kind == "release":
        for button in tuple(held_buttons):
            try:
                mouse_controller.release(button)
            except Exception:
                pass
            held_buttons.discard(button)
        return
    if kind == "mouse":
        desired = set()
        if message["buttons"] & 1:
            desired.add(mouse.Button.left)
        if message["buttons"] & 2:
            desired.add(mouse.Button.right)
        if message["buttons"] & 4:
            desired.add(mouse.Button.middle)
        for button in desired - held_buttons:
            mouse_controller.press(button)
        for button in held_buttons - desired:
            mouse_controller.release(button)
        held_buttons.clear()
        held_buttons.update(desired)
        dx = max(-2048, min(2048, message["dx"]))
        dy = max(-2048, min(2048, message["dy"]))
        if dx or dy:
            mouse_controller.move(dx, dy)
        if message["wheel"]:
            mouse_controller.scroll(0, message["wheel"])
        return
    if kind == "key":
        pressed = []
        try:
            for modifier in modifier_keys(message["modifiers"]):
                keyboard_controller.press(modifier)
                pressed.append(modifier)
            key = key_from_hid(message["key"])
            if key is not None:
                keyboard_controller.press(key)
                pressed.append(key)
            time.sleep(0.012)
        finally:
            for item in reversed(pressed):
                try:
                    keyboard_controller.release(item)
                except Exception:
                    pass


def handle_client(tls_socket, pairing_code, auth_state):
    load_input_backend()
    held_buttons = set()
    mouse_controller = mouse.Controller()
    keyboard_controller = keyboard.Controller()
    with tls_socket:
        tls_socket.settimeout(8)
        stream = tls_socket.makefile("rwb")
        authenticated = False
        if time.monotonic() < auth_state["locked_until"]:
            return
        for _ in range(5):
            raw = stream.readline(MAX_MESSAGE_BYTES + 1)
            if not raw or len(raw) > MAX_MESSAGE_BYTES:
                return
            try:
                request = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                return
            supplied = request.get("code", "") if isinstance(request, dict) and request.get("type") == "auth" else ""
            if hmac.compare_digest(str(supplied), pairing_code):
                authenticated = True
                auth_state["failures"] = 0
                break
            auth_state["failures"] += 1
            if auth_state["failures"] >= 5:
                auth_state["failures"] = 0
                auth_state["locked_until"] = time.monotonic() + 60
                break
            stream.write(b'{"type":"denied"}\n')
            stream.flush()
        if not authenticated:
            return
        stream.write(b'{"type":"ready"}\n')
        stream.flush()
        tls_socket.settimeout(None)
        try:
            while True:
                raw = stream.readline(MAX_MESSAGE_BYTES + 1)
                if not raw:
                    break
                apply_input(decode_message(raw), mouse_controller, keyboard_controller, held_buttons)
        except Exception as error:
            print(f"Input connection closed: {error}", file=sys.stderr)
        finally:
            apply_input({"type": "release"}, mouse_controller, keyboard_controller, held_buttons)


def main():
    parser = argparse.ArgumentParser(description="Wasti AirPad local Wi-Fi desktop companion")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")

    cert_bytes = ensure_identity()
    fingerprint = hashlib.sha256(
        x509.load_pem_x509_certificate(cert_bytes).public_bytes(serialization.Encoding.DER)
    ).hexdigest()
    pairing_code = f"{secrets.randbelow(100_000_000):08d}"

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(str(CERT_FILE), str(KEY_FILE))

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", args.port))
    server.listen(4)
    server.settimeout(1.0)

    zeroconf = None
    service_info = None
    try:
        zeroconf, service_info = register_discovery_service(args.port, fingerprint)
        if zeroconf:
            print("Automatic discovery: enabled (DNS-SD/mDNS)")
        else:
            print("Automatic discovery unavailable; use the printed IP address.")
    except Exception as error:
        print(f"Automatic discovery unavailable: {error}")

    print("\nWasti AirPad Wi-Fi Companion")
    print("=" * 36)
    print(f"Computer: {socket.gethostname()}")
    print(f"Port: {args.port}")
    print(f"Pairing code: {pairing_code}")
    print("Certificate SHA-256 (enter this in AirPad):")
    print(fingerprint)
    print("Local IPv4 addresses:")
    private_addresses = [
        address for address in local_ipv4_addresses()
        if ipaddress.ip_address(address).is_private
    ]
    for address in local_ipv4_addresses():
        print(f"  {address}")
    if private_addresses:
        print("\nScan a pairing QR code with your phone camera:")
        for address in private_addresses:
            print_pairing_qr(address, args.port, pairing_code, fingerprint)
    print("\nAllow the app on Private networks only if Windows Firewall asks.")
    print("Do not forward this port on your router. Stop with Ctrl+C.\n")
    auth_state = {"failures": 0, "locked_until": 0.0}
    try:
        while True:
            try:
                connection, _ = server.accept()
            except socket.timeout:
                continue
            try:
                connection.settimeout(8)
                tls_socket = context.wrap_socket(connection, server_side=True)
                handle_client(tls_socket, pairing_code, auth_state)
            except (ssl.SSLError, OSError) as error:
                try:
                    connection.close()
                except OSError:
                    pass
                print(f"Connection rejected: {error}", file=sys.stderr)
            except Exception as error:
                try:
                    connection.close()
                except OSError:
                    pass
                print(f"Desktop input backend failed: {error}", file=sys.stderr)
    except KeyboardInterrupt:
        print("\nStopping Wasti AirPad companion.")
    finally:
        if zeroconf and service_info:
            try:
                zeroconf.unregister_service(service_info)
            except Exception:
                pass
            zeroconf.close()
        server.close()


if __name__ == "__main__":
    main()
