#!/usr/bin/env python3
"""Build the auditable, device-independent FileFlash share-sheet shortcut.

Default: regenerate the unsigned XML plist. --check checks reproducibility and
the privacy/transport invariants without network access. --sign explicitly sends
only that public template to a signing service; never supply a paired device URL.
The signed result must separately pass verify-ios-shortcut.py before shipping.
"""

import argparse
import hashlib
import json
import plistlib
import re
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "scripts/shortcut/FileFlash.unsigned.plist"
OUTPUT = ROOT / "app/src/main/assets/web/FileFlash.shortcut"
NAMESPACE = uuid.UUID("f823c9e9-2246-4d82-8a1e-cf97184a5df6")
OCTET = r"(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
LAN_IP = (rf"(?:10\.{OCTET}\.{OCTET}\.{OCTET}|"
          rf"192\.168\.{OCTET}\.{OCTET}|"
          rf"172\.(?:1[6-9]|2[0-9]|3[01])\.{OCTET}\.{OCTET}|"
          rf"169\.254\.{OCTET}\.{OCTET})")
PORT = r"(?:[1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])"
# ICU and Python both support \A and \z only on newer versions. The (?![\s\S])
# end assertion is strict on both, unlike $ which permits a trailing newline.
QR_PATTERN = rf"\A(http://{LAN_IP}:{PORT})/#pair=([A-Za-z0-9_-]{{32}})(?![\s\S])"


def uid(name):
    return str(uuid.uuid5(NAMESPACE, name)).upper()


def output_value(name):
    return {"Type": "ActionOutput", "OutputUUID": uid(name), "OutputName": name}


def attachment(value):
    return {"Value": value, "WFSerializationType": "WFTextTokenAttachment"}


def output(name):
    return attachment(output_value(name))


def text(*parts):
    """Use Shortcuts' UTF-16 attachment offsets, preserving binary file inputs."""
    value = ""
    references = {}
    for part in parts:
        if isinstance(part, dict):
            index = len(value.encode("utf-16-le")) // 2
            references[f"{{{index}, 1}}"] = part
            value += "\ufffc"
        else:
            value += part
    return {"Value": {"string": value, "attachmentsByRange": references},
            "WFSerializationType": "WFTextTokenString"}


def dictionary(items):
    return {"Value": {"WFDictionaryFieldValueItems": [
        {"WFItemType": 0, "WFKey": text(key),
         "WFValue": value if isinstance(value, dict) else text(value)}
        for key, value in items.items()
    ]}, "WFSerializationType": "WFDictionaryFieldValue"}


def workflow():
    actions = []

    def add(identifier, name, **params):
        params["UUID"] = uid(name)
        actions.append({"WFWorkflowActionIdentifier": "is.workflow.actions." + identifier,
                        "WFWorkflowActionParameters": params})

    def fail_if_empty(name, value, message, error_response=None):
        group = uid(name + "-group")
        add("conditional", name + "-if", GroupingIdentifier=group,
            WFControlFlowMode=0, WFCondition=101,
            WFInput={"Type": "Variable", "Variable": value})
        detail = ""
        if error_response:
            add("getvalueforkey", name + " Error Detail", WFInput=error_response,
                WFGetDictionaryValueType="Value", WFDictionaryKey="error")
            detail = text(output_value(name + " Error Detail"))
        add("alert", name + "-error", WFAlertActionTitle=message,
            WFAlertActionMessage=detail, WFAlertActionCancelButtonShown=False)
        add("exit", name + "-stop")
        add("conditional", name + "-end", GroupingIdentifier=group,
            WFControlFlowMode=2,
            WFInput={"Type": "Variable", "Variable": value})

    def revoke_session(name):
        # Clear the implicit action stream and set only URL/method/headers. No
        # File, JSON or Form body parameter may refer to shared media here.
        add("nothing", name + " Clear Input")
        add("downloadurl", name, Advanced=True, ShowHeaders=True,
            WFURL=text(output_value("Server"), "/api/session"),
            WFHTTPMethod="DELETE",
            WFHTTPHeaders=dictionary({"Authorization": text("Bearer ", output_value("Session"))}))

    # QR camera is unavailable inside a share extension. Continue execution in
    # Apple's Shortcuts app before scanning or sending large media.
    add("dismisssiri", "Continue in Shortcuts")
    shared = attachment({"Type": "ExtensionInput"})
    fail_if_empty("Input", shared, "从照片或文件分享")
    add("scanbarcode", "Scan QR Code")
    add("text.match", "Pair QR Match", text=text(output_value("Scan QR Code")),
        WFMatchTextPattern=QR_PATTERN, WFMatchTextCaseSensitive=True)
    fail_if_empty("QR", output("Pair QR Match"), "二维码无效")
    add("text.match.getgroup", "Server", matches=output("Pair QR Match"),
        WFGetGroupType="Group At Index", WFGroupIndex=1)
    add("text.match.getgroup", "Pair Token", matches=output("Pair QR Match"),
        WFGetGroupType="Group At Index", WFGroupIndex=2)
    add("downloadurl", "Pair Response", Advanced=True, ShowHeaders=True,
        WFURL=text(output_value("Server"), "/api/pair"),
        WFHTTPMethod="POST", WFHTTPBodyType="JSON",
        WFJSONValues=dictionary({"token": text(output_value("Pair Token")), "name": "iPhone"}),
        WFHTTPHeaders=dictionary({"Content-Type": "application/json"}))
    add("getvalueforkey", "Session", WFInput=output("Pair Response"),
        WFGetDictionaryValueType="Value", WFDictionaryKey="session")
    fail_if_empty("Session", output("Session"), "连接失败", output("Pair Response"))
    repeat_group = uid("File Loop")
    add("repeat.each", "File Loop", WFControlFlowMode=0,
        GroupingIdentifier=repeat_group, WFInput=shared)
    item = attachment({"Type": "Variable", "VariableName": "Repeat Item"})
    add("getitemname", "File Name", WFInput=item)
    add("urlencode", "Encoded Name", WFInput=output("File Name"), WFEncodeMode="Encode")
    add("downloadurl", "Upload Response", Advanced=True, ShowHeaders=True,
        WFURL=text(output_value("Server"), "/api/shortcuts/files?name=", output_value("Encoded Name")),
        WFHTTPMethod="POST", WFHTTPBodyType="File", WFRequestVariable=item,
        WFHTTPHeaders=dictionary({"Authorization": text("Bearer ", output_value("Session")),
                                  "Content-Type": "application/octet-stream"}))
    add("getvalueforkey", "Upload Status", WFInput=output("Upload Response"),
        WFGetDictionaryValueType="Value", WFDictionaryKey="status")
    failure_group = uid("Upload Failure")
    add("conditional", "Upload Failure", GroupingIdentifier=failure_group,
        WFControlFlowMode=0, WFCondition=5, WFConditionalActionString="complete",
        WFInput={"Type": "Variable", "Variable": output("Upload Status")})
    add("getvalueforkey", "Upload Error Detail", WFInput=output("Upload Response"),
        WFGetDictionaryValueType="Value", WFDictionaryKey="error")
    revoke_session("Release Failed Session")
    add("alert", "Upload Error", WFAlertActionTitle="发送失败",
        WFAlertActionMessage=text(output_value("Upload Error Detail")),
        WFAlertActionCancelButtonShown=False)
    add("exit", "Upload Stop")
    add("conditional", "Upload Failure End", GroupingIdentifier=failure_group,
        WFControlFlowMode=2,
        WFInput={"Type": "Variable", "Variable": output("Upload Status")})
    # Do not retain the previous upload's JSON as a repeat result list.
    add("nothing", "Release Upload Result")
    add("repeat.each", "File Loop End", WFControlFlowMode=2,
        GroupingIdentifier=repeat_group)
    revoke_session("Release Session")
    add("alert", "Complete", WFAlertActionTitle="已发送",
        WFAlertActionMessage="", WFAlertActionCancelButtonShown=False)
    return {
        # Match the Apple signing runtime's metadata normalization. The downloaded
        # FileFlash.shortcut filename supplies the import name.
        "WFWorkflowClientVersion": "5037.0.17",
        "WFWorkflowMinimumClientVersion": 900,
        "WFWorkflowMinimumClientVersionString": "900",
        "WFWorkflowIcon": {"WFWorkflowIconStartColor": 4282601983,
                           "WFWorkflowIconGlyphNumber": 59511},
        "WFWorkflowTypes": ["ActionExtension"],
        "WFQuickActionSurfaces": [],
        "WFWorkflowInputContentItemClasses": ["WFGenericFileContentItem",
                                               "WFImageContentItem", "WFAVAssetContentItem"],
        "WFWorkflowOutputContentItemClasses": [],
        "WFWorkflowHasShortcutInputVariables": True,
        "WFWorkflowHasOutputFallback": False,
        "WFWorkflowNoInputBehavior": {"Name": "WFWorkflowNoInputBehaviorShowError",
                                      "Parameters": {"Error": "从照片或文件分享"}},
        "WFWorkflowImportQuestions": [],
        "WFWorkflowActions": actions,
    }


def validate(data):
    expected = workflow()
    if plistlib.loads(data) != expected:
        raise ValueError("Shortcut differs from the reproducible public template")
    actions = expected["WFWorkflowActions"]
    identifiers = [a["WFWorkflowActionIdentifier"] for a in actions]
    forbidden = ("base64", "convert", "encode.media", "getimages", "getlatestphotos", "selectphotos",
                 "clipboard", "savefile", "file.append", "runjavascript", "runshell")
    if any(any(term in identifier for term in forbidden) for identifier in identifiers):
        raise ValueError("Shortcut contains a prohibited conversion/persistence action")
    raw = [a["WFWorkflowActionParameters"] for a in actions
           if a["WFWorkflowActionParameters"].get("WFHTTPBodyType") == "File"]
    if len(raw) != 1 or raw[0]["WFRequestVariable"] != attachment(
            {"Type": "Variable", "VariableName": "Repeat Item"}):
        raise ValueError("Upload must send Repeat Item directly as a File body")
    deletions = [a["WFWorkflowActionParameters"] for a in actions
                 if a["WFWorkflowActionParameters"].get("WFHTTPMethod") == "DELETE"]
    if len(deletions) != 2 or any(any(key in action for key in (
            "WFHTTPBodyType", "WFRequestVariable", "WFJSONValues", "WFFormValues", "WFInput"))
            for action in deletions):
        raise ValueError("Session revocation must not configure a body or implicit media input")
    return {"actions": len(actions), "sourceSha256": hashlib.sha256(data).hexdigest(),
            "noEmbeddedDeviceAddressOrCredential": True,
            "rawFileBody": True, "shortcutRuntimeExternalServices": []}


def sign(data, provider, destination):
    # Exact equality above is intentional: arbitrary local workflows or inputs
    # cannot accidentally be sent to either external signing service.
    evidence = validate(data)
    if provider == "hubsign":
        url = "https://hubsign.routinehub.services/sign"
        body = json.dumps({"shortcutName": "FileFlash", "shortcut": data.decode("utf-8")}).encode()
        headers = {"Content-Type": "application/json", "User-Agent": "FileFlash-shortcut-builder/0.1.6"}
    else:
        url = "https://sign.shortcuty.app/api/v1/sign?mode=anyone"
        body = data
        headers = {"Content-Type": "application/octet-stream", "X-Filename": "FileFlash.shortcut",
                   "User-Agent": "FileFlash-shortcut-builder/0.1.6"}
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            signed = response.read(2 * 1024 * 1024 + 1)
            response_input_hash = response.headers.get("X-Shortcut-Input-SHA256")
            if response_input_hash and response_input_hash.lower() != evidence["sourceSha256"]:
                raise ValueError("Signing service input hash mismatch")
    except urllib.error.HTTPError as error:
        reason = error.read(400).decode("utf-8", errors="replace")
        raise RuntimeError(f"{provider}: HTTP {error.code}: {reason}") from error
    if len(signed) > 2 * 1024 * 1024 or not signed.startswith(b"AEA1"):
        raise ValueError("Signing response is not a bounded AEA1 shortcut")
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(signed)
    evidence.update({"provider": provider, "signedSha256": hashlib.sha256(signed).hexdigest(),
                     "signedBytes": len(signed), "payloadEquivalenceVerified": False,
                     "note": "Run verify-ios-shortcut.py; AEA1 magic alone is not signature verification."})
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="offline reproducibility/invariant check")
    parser.add_argument("--sign", choices=["hubsign", "shortcuty"], help="sign only the public template")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    data = plistlib.dumps(workflow(), fmt=plistlib.FMT_XML, sort_keys=False)
    if args.check:
        if not SOURCE.exists() or SOURCE.read_bytes() != data:
            raise ValueError("Unsigned source is missing or stale; regenerate it first")
    else:
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        SOURCE.write_bytes(data)
    evidence = validate(data)
    if args.sign:
        evidence = sign(data, args.sign, args.output)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        sys.exit(str(error))
