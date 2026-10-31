#!/usr/bin/env python3
"""Prepare a private development key and verify distributable APKs; never generate keys."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PIN = ROOT / "android/development-signing.sha256"
SECRET_NAME = "ROCKS_ANDROID_SIGNING"


def expected_certificate():
    value = PIN.read_text().strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Invalid pinned development certificate")
    return value


def decode_bundle(raw):
    try:
        value = json.loads(raw)
        required = {"version", "keystore_base64", "store_password", "key_password", "key_alias", "certificate_sha256"}
        if not isinstance(value, dict) or set(value) != required or type(value["version"]) is not int or value["version"] != 1:
            raise ValueError()
        if value["certificate_sha256"] != expected_certificate():
            raise ValueError()
        if not isinstance(value["key_alias"], str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", value["key_alias"]):
            raise ValueError()
        for field in ("store_password", "key_password"):
            password = value[field]
            if not isinstance(password, str) or not 1 <= len(password) <= 1024 or any(c in password for c in "\r\n\0"):
                raise ValueError()
        if not isinstance(value["keystore_base64"], str) or len(value["keystore_base64"]) > 180000:
            raise ValueError()
        key = base64.b64decode(value["keystore_base64"], validate=True)
        if not key or len(key) > 128 * 1024:
            raise ValueError()
        return value, key
    except (ValueError, TypeError, KeyError):
        # Do not echo JSON or secrets through parser exceptions or workflow logs.
        raise ValueError("Signing bundle is invalid or differs from the pinned certificate") from None


def output(name, value):
    target = os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a") as stream:
            stream.write(f"{name}={value}\n")


def prepare(args):
    raw = args.secret_file.read_text() if args.secret_file else os.environ.get(SECRET_NAME, "")
    if not raw:
        output("configured", "false")
        if args.require:
            raise ValueError(f"Configure repository secret {SECRET_NAME}; refusing a temporary signing key")
        print("No private signing configuration: build unsigned intermediates only; public publishing is disabled.")
        return
    value, key = decode_bundle(raw)
    if os.environ.get("GITHUB_ACTIONS") == "true":
        for field in ("keystore_base64", "store_password", "key_password"):
            print("::add-mask::" + value[field], flush=True)
    directory = Path(tempfile.mkdtemp(prefix="rocks-private-signing-", dir=os.environ.get("RUNNER_TEMP", "/tmp")))
    path = directory / "development.p12"
    with open(path, "wb", opener=lambda name, flags: os.open(name, flags, 0o600)) as stream:
        stream.write(key)
    path.chmod(0o600)
    tool = Path(os.environ["JAVA_HOME"]) / "bin/keytool" if os.environ.get("JAVA_HOME") else shutil.which("keytool")
    env = dict(os.environ, ROCKS_ANDROID_STORE_PASSWORD=value["store_password"])
    result = subprocess.run([str(tool), "-exportcert", "-keystore", str(path), "-alias", value["key_alias"],
        "-storepass:env", "ROCKS_ANDROID_STORE_PASSWORD"], env=env, capture_output=True)
    if result.returncode or hashlib.sha256(result.stdout).hexdigest() != expected_certificate():
        path.unlink(missing_ok=True)
        raise ValueError("Private keystore does not match the pinned certificate")
    variables = {"ROCKS_ANDROID_KEYSTORE": str(path), "ROCKS_ANDROID_KEY_ALIAS": value["key_alias"],
        "ROCKS_ANDROID_STORE_PASSWORD": value["store_password"], "ROCKS_ANDROID_KEY_PASSWORD": value["key_password"]}
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a") as stream:
            for name, item in variables.items():
                stream.write(f"{name}={item}\n")
    output("configured", "true")
    print("Private signing configuration verified. Certificate SHA-256: " + expected_certificate())


def verify(args):
    for apk in args.apk:
        result = subprocess.run([str(args.apksigner), "verify", "--verbose", "--print-certs", str(apk)],
            capture_output=True, text=True)
        certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$", result.stdout, re.MULTILINE)
        if result.returncode or len(certificates) != 1 or certificates[0].lower() != expected_certificate():
            raise ValueError("APK signature verification failed or uses an unexpected certificate: " + apk.name)
        print(apk.name + ": verified fixed certificate " + expected_certificate())
    output("certificate_sha256", expected_certificate())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    first = sub.add_parser("prepare")
    first.add_argument("--require", action="store_true")
    first.add_argument("--secret-file", type=Path)
    second = sub.add_parser("verify")
    second.add_argument("--apksigner", type=Path, required=True)
    second.add_argument("--apk", type=Path, action="append", required=True)
    args = parser.parse_args()
    try:
        prepare(args) if args.command == "prepare" else verify(args)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
