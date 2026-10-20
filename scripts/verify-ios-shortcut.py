#!/usr/bin/env python3
"""Verify an exported Shortcut and compare its complete workflow with the source.

Install review dependencies with: python -m pip install python-aea cryptography
This works on Windows; it needs neither Apple's aea/aa tools nor signing keys.
It verifies the AEA signature, archive integrity, the bundled certificate chain
against Apple's pinned public Root CA G3, and every workflow value. It does not
execute the workflow or replace import/runtime validation on an iOS device.

Sources:
https://github.com/kinnay/AEA (MIT, independent AEA implementation)
https://www.apple.com/certificateauthority/AppleRootCA-G3.cer
https://developer.apple.com/documentation/applearchive/archiveheader/fieldkey-swift.struct
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.metadata
import json
from pathlib import Path
import plistlib
import struct
import sys


APPLE_ROOT_G3_SHA256 = "63343abfb89a6a03ebb57e9b3f5fa7be7c4f5c756f3017b3a8c488c3653e9179"
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_AUTH_BYTES = 1024 * 1024


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_bounded(path: Path) -> bytes:
    require(0 < path.stat().st_size <= MAX_FILE_BYTES, "Shortcut size is outside the review limit")
    return path.read_bytes()


def extract_workflow(archive: bytes) -> tuple[bytes, list[dict]]:
    """Parse AA01 lengths and DAT fields; never search blindly for bplist magic."""
    position, files, entries = 0, [], []
    widths = {"1": 1, "2": 2, "4": 4, "8": 8, "F": 4, "G": 20,
              "H": 32, "I": 48, "J": 64, "S": 8, "T": 12,
              "A": 2, "B": 4, "C": 8}
    while position < len(archive):
        require(len(entries) < 32, "Too many Apple Archive entries")
        require(archive[position:position + 4] in (b"AA01", b"YAA1"), "Invalid Apple Archive entry magic")
        require(position + 6 <= len(archive), "Truncated Apple Archive header")
        header_size = struct.unpack_from("<H", archive, position + 4)[0]
        end = position + header_size
        require(header_size >= 6 and end <= len(archive), "Invalid Apple Archive header length")
        cursor, fields, blobs = position + 6, {}, []
        while cursor < end:
            require(cursor + 4 <= end, "Truncated Apple Archive field")
            key = archive[cursor:cursor + 3].decode("ascii")
            subtype = chr(archive[cursor + 3])
            cursor += 4
            require(key not in fields, "Duplicate Apple Archive field")
            if subtype == "*":
                value = True
            elif subtype == "P":
                require(cursor + 2 <= end, "Truncated Apple Archive string length")
                size = struct.unpack_from("<H", archive, cursor)[0]
                cursor += 2
                require(cursor + size <= end, "Truncated Apple Archive string")
                value = archive[cursor:cursor + size].rstrip(b"\0").decode("utf-8")
                cursor += size
            else:
                require(subtype in widths, "Unsupported Apple Archive field subtype")
                size = widths[subtype]
                require(cursor + size <= end, "Truncated Apple Archive field value")
                value = int.from_bytes(archive[cursor:cursor + size], "little")
                cursor += size
                if subtype in "ABC":
                    blobs.append((key, value))
            fields[key] = value
        require(cursor == end, "Apple Archive header length mismatch")
        blob_cursor, data = end, None
        for key, size in blobs:
            require(blob_cursor + size <= len(archive), "Truncated Apple Archive blob")
            require(key == "DAT", "Unexpected blob in Shortcut archive")
            data = archive[blob_cursor:blob_cursor + size]
            blob_cursor += size
        kind, name = fields.get("TYP"), fields.get("PAT")
        require(kind in (ord("D"), ord("F")), "Shortcut archive contains a non-file entry")
        if kind == ord("F"):
            require(name == "Shortcut.wflow" and data is not None, "Unexpected file in Shortcut archive")
            files.append(data)
        else:
            require(name in ("", ".") and data is None, "Unexpected directory in Shortcut archive")
        entries.append({"path": name, "type": chr(kind), "dataBytes": len(data) if data else 0})
        position = blob_cursor
    require(position == len(archive) and len(files) == 1, "Shortcut archive must contain exactly one workflow")
    require(files[0].startswith(b"bplist00"), "Shortcut.wflow is not a binary plist")
    return files[0], entries


def verify_signed(data: bytes) -> tuple[dict, bytes, dict]:
    import aea
    from cryptography import x509
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    require(data[:4] == b"AEA1" and len(data) >= 12, "Expected a signed AEA1 Shortcut")
    profile, auth_size = struct.unpack_from("<II", data, 4)
    require(profile == 0, "Only unencrypted, signed AEA profile 0 is accepted")
    require(0 < auth_size <= MAX_AUTH_BYTES and 12 + auth_size <= len(data), "Invalid AEA auth-data length")
    metadata = plistlib.loads(data[12:12 + auth_size])
    require(isinstance(metadata, dict) and set(metadata) == {"SigningCertificateChain"}, "Unexpected signing metadata")
    chain_bytes = metadata["SigningCertificateChain"]
    require(isinstance(chain_bytes, list) and len(chain_bytes) == 3, "Expected leaf, intermediate and Apple root certificates")
    require(all(isinstance(item, bytes) for item in chain_bytes), "Invalid certificate encoding")
    require(digest(chain_bytes[-1]) == APPLE_ROOT_G3_SHA256, "Certificate root differs from Apple's pinned Root CA G3")
    chain = [x509.load_der_x509_certificate(item) for item in chain_bytes]
    now = dt.datetime.now(dt.timezone.utc)
    for index, certificate in enumerate(chain):
        require(certificate.not_valid_before_utc <= now <= certificate.not_valid_after_utc, "Signing certificate is outside its validity period")
        parent = chain[min(index + 1, len(chain) - 1)]
        certificate.verify_directly_issued_by(parent)
        constraints = certificate.extensions.get_extension_for_class(x509.BasicConstraints).value
        require(constraints.ca == (index > 0), "Unexpected certificate CA constraints")
        usage = certificate.extensions.get_extension_for_class(x509.KeyUsage).value
        require(usage.digital_signature if index == 0 else usage.key_cert_sign, "Certificate key usage does not permit signing")
    public_key = chain[0].public_key()
    require(isinstance(public_key, ec.EllipticCurvePublicKey) and isinstance(public_key.curve, ec.SECP256R1), "Expected an ECDSA P-256 Shortcut signing key")

    # Profile 0 root headers are authenticated but unencrypted. Bound advertised
    # sizes before invoking the decoder, then verify the signed/HMAC-protected data.
    root_offset = 12 + auth_size + 128 + 32 + 32 + 32
    require(root_offset + 48 <= len(data), "Truncated AEA root header")
    original_size, archive_size, segment_size, cluster_segments = struct.unpack_from("<QQII", data, root_offset)
    require(0 < original_size <= MAX_FILE_BYTES, "AEA expanded size is outside the review limit")
    require(archive_size == len(data), "AEA archive length mismatch or appended data")
    require(0 < segment_size <= MAX_FILE_BYTES and 0 < cluster_segments <= 4096, "AEA segment parameters exceed the review limit")
    pem = public_key.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo)
    archive = aea.decode(data, signature_pub=pem)
    require(len(archive) == original_size, "AEA expanded length mismatch")
    workflow_bytes, entries = extract_workflow(archive)
    workflow = plistlib.loads(workflow_bytes)
    require(isinstance(workflow, dict) and isinstance(workflow.get("WFWorkflowActions"), list), "Invalid Shortcut workflow")
    return workflow, workflow_bytes, {
        "profile": profile,
        "archiveBytes": len(archive),
        "archiveEntries": entries,
        "signingChainSha256": [digest(item) for item in chain_bytes],
        "appleRootCaG3Sha256": APPLE_ROOT_G3_SHA256,
        "leafCertificateExpiresUtc": chain[0].not_valid_after_utc.isoformat(),
        "signatureVerified": True,
        "archiveIntegrityVerified": True,
        "certificateChainVerified": True,
        "revocationChecked": False,
    }


def canonical(workflow: dict) -> bytes:
    # Binary plists may alias repeated container objects. Re-encoding an aliased
    # dictionary can therefore differ from an equal XML dictionary, even with
    # sort_keys=True. Compare a typed value tree, independent of object identity.
    def typed(value):
        if type(value) is dict:
            require(all(type(key) is str for key in value), "Non-string plist dictionary key")
            return ["dictionary", [[key, typed(value[key])] for key in sorted(value)]]
        if type(value) is list:
            return ["array", [typed(item) for item in value]]
        if type(value) is bool:
            return ["boolean", value]
        if type(value) is int:
            return ["integer", str(value)]
        if type(value) is float:
            return ["real", value.hex()]
        if type(value) is str:
            return ["string", value]
        if type(value) is bytes:
            return ["data", value.hex()]
        if type(value) is dt.datetime:
            return ["date", value.isoformat()]
        if type(value) is plistlib.UID:
            return ["uid", value.data]
        if value is None:
            return ["null"]
        raise ValueError("Unsupported plist value type")

    return json.dumps(typed(workflow), ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signed", type=Path, required=True)
    parser.add_argument("--unsigned", type=Path, required=True, help="Exact unsigned plist supplied to the signer")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--extract", type=Path, help="Optional reviewed XML workflow output")
    args = parser.parse_args()
    report = {"schemaVersion": 1, "passed": False, "signedFile": args.signed.name,
              "unsignedFile": args.unsigned.name, "scope": "Static signature, integrity and full workflow equality; no iOS execution or import check"}
    try:
        signed, unsigned = read_bounded(args.signed), read_bounded(args.unsigned)
        report.update(signedSha256=digest(signed), unsignedSha256=digest(unsigned))
        expected = plistlib.loads(unsigned)
        require(isinstance(expected, dict), "Unsigned workflow must be a plist dictionary")
        actual, workflow_bytes, envelope = verify_signed(signed)
        report.update(envelope)
        require(canonical(actual) == canonical(expected), "Signed workflow differs from the complete unsigned workflow")
        report.update(workflowMatchesUnsigned=True, workflowSha256=digest(canonical(actual)),
                      workflowPlistSha256=digest(workflow_bytes), actionCount=len(actual["WFWorkflowActions"]),
                      dependencies={name: importlib.metadata.version(name) for name in ("python-aea", "cryptography", "pyliblzfse", "lz4")},
                      checkedAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(), passed=True)
        if args.extract:
            args.extract.parent.mkdir(parents=True, exist_ok=True)
            args.extract.write_bytes(plistlib.dumps(actual, fmt=plistlib.FMT_XML, sort_keys=False))
    except Exception as error:
        report["error"] = f"{type(error).__name__}: {error}"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
