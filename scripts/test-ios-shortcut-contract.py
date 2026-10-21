#!/usr/bin/env python3
"""Audit every configured FileFlash action slot against extracted engine classes.

The small checked-in contract fixture comes from Apple WorkflowKit/ActionKit
metadata independently extracted by shortcutkit, plus explicit structural and
legacy UI exceptions. This is an offline serialization/dataflow check, not an
iOS execution test. It runs from a source ZIP without Git or third-party modules.
"""

import argparse
import copy
import hashlib
import json
import plistlib
from pathlib import Path
import re
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "scripts/shortcut/fixtures/parameter-contract.json"
SOURCE = ROOT / "scripts/shortcut/FileFlash.unsigned.plist"
PREFIX = "is.workflow.actions."


class ContractError(ValueError):
    pass


def need(condition, message):
    if not condition:
        raise ContractError(message)


def params(action):
    return action["WFWorkflowActionParameters"]


def ident(action):
    return action["WFWorkflowActionIdentifier"]


def valid_uuid(value):
    need(type(value) is str, "UUID must be a string")
    try:
        need(str(uuid.UUID(value)).lower() == value.lower(), "UUID is not canonical")
    except (ValueError, AttributeError) as error:
        raise ContractError("Invalid UUID") from error


class Auditor:
    def __init__(self, fixture):
        self.fixture = fixture
        self.producers = {}
        self.blocks = []
        self.rows, self.errors, self.unchecked = [], [], []
        self.references = []
        self.dictionary_entries = 0
        self.index, self.action_id, self.field = -1, "", ""

    def raw_variable(self, value):
        need(type(value) is dict, "Variable must be a dictionary")
        kind = value.get("Type")
        if kind == "ActionOutput":
            need(set(value) == {"Type", "OutputUUID", "OutputName"},
                 "Action-output variable has unknown/missing fields")
            valid_uuid(value["OutputUUID"])
            need(type(value["OutputName"]) is str and value["OutputName"], "Missing output display name")
            producer = self.producers.get(value["OutputUUID"])
            need(producer is not None, "Output UUID is unknown or a forward/self reference")
            producer_scope = producer["scope"]
            current_scope = [item["group"] for item in self.blocks]
            need(current_scope[:len(producer_scope)] == producer_scope,
                 "Action output escaped its repeat/conditional scope")
            self.references.append({"action": self.index, "parameter": self.field,
                                    "producer": producer["index"], "outputUuid": value["OutputUUID"]})
        elif kind == "ExtensionInput":
            need(set(value) == {"Type"}, "Shortcut input variable has unknown fields")
        elif kind == "Variable":
            need(set(value) == {"Type", "VariableName"}, "Named variable has unknown/missing fields")
            need(value["VariableName"] == "Repeat Item", "Unreviewed named-variable source")
            need(sum(item["identifier"] == PREFIX + "repeat.each" for item in self.blocks) == 1,
                 "Repeat Item must be bound inside the one active file loop")
        else:
            raise ContractError("Unreviewed variable kind: " + str(kind))

    def attachment(self, value):
        need(type(value) is dict and set(value) == {"Value", "WFSerializationType"},
             "Variable slot requires an attachment envelope")
        need(value["WFSerializationType"] == "WFTextTokenAttachment",
             "Variable slot requires WFTextTokenAttachment")
        self.raw_variable(value["Value"])

    def text(self, value):
        if type(value) is str:
            need("\ufffc" not in value, "Literal text has an unbound object-replacement character")
            return
        need(type(value) is dict and set(value) == {"Value", "WFSerializationType"},
             "Text slot requires literal string or text-token envelope")
        need(value["WFSerializationType"] == "WFTextTokenString",
             "Dynamic text slot requires WFTextTokenString, not a bare variable attachment")
        inner = value["Value"]
        need(type(inner) is dict and set(inner) <= {"string", "attachmentsByRange"} and "string" in inner,
             "Text-token value has unknown/missing fields")
        text = inner["string"]
        need(type(text) is str, "Text-token string is not text")
        attachments = inner.get("attachmentsByRange", {})
        need(type(attachments) is dict, "Text-token ranges must be a dictionary")
        offset, positions = 0, set()
        for char in text:
            if char == "\ufffc":
                positions.add(offset)
            offset += len(char.encode("utf-16-le")) // 2
        seen = set()
        for key, variable in attachments.items():
            need(type(key) is str, "Text-token range key is not a string")
            match = re.fullmatch(r"\{(\d+), (\d+)\}", key)
            need(match is not None and int(match[2]) == 1, "Invalid UTF-16 attachment range")
            position = int(match[1])
            need(position in positions and position not in seen, "UTF-16 range does not bind one placeholder")
            seen.add(position)
            self.raw_variable(variable)
        need(seen == positions, "Text-token placeholder lacks a UTF-16 variable binding")

    def dictionary(self, value, slot):
        need(type(value) is dict and set(value) == {"Value", "WFSerializationType"} and
             value["WFSerializationType"] == "WFDictionaryFieldValue", "Dictionary slot requires WFDictionaryFieldValue")
        inner = value["Value"]
        need(type(inner) is dict and set(inner) == {"WFDictionaryFieldValueItems"} and
             type(inner["WFDictionaryFieldValueItems"]) is list, "Dictionary state has unknown/missing fields")
        allowed = slot.get("allowedValueTypes", [0])
        for item in inner["WFDictionaryFieldValueItems"]:
            need(type(item) is dict and set(item) == {"WFKey", "WFItemType", "WFValue"},
                 "Dictionary entry has unknown/missing fields")
            need(type(item["WFItemType"]) is int and item["WFItemType"] in allowed,
                 "Dictionary entry type is not permitted by this parameter")
            # FileFlash's configured JSON/header dictionaries use only text
            # entries. Future numeric/nested/file states require their own audit.
            need(item["WFItemType"] == 0, "Unreviewed configured dictionary entry class")
            self.text(item["WFKey"])
            self.text(item["WFValue"])
            self.dictionary_entries += 1

    def slot(self, key, action_contract):
        if key == "UUID":
            return {"kind": "action-uuid"}
        if self.action_id in (PREFIX + "conditional", PREFIX + "repeat.each"):
            if key == "GroupingIdentifier":
                return {"kind": "block-uuid"}
            if key == "WFControlFlowMode":
                return {"kind": "block-mode"}
        if self.action_id == PREFIX + "conditional":
            if key == "WFInput":
                return {"kind": "conditional-subject", "class": "WFVariableConditionalSubjectState"}
            if key == "WFCondition":
                return {"kind": "conditional-operator"}
        if key in action_contract["slots"]:
            return {"kind": "engine-parameter", **action_contract["slots"][key]}
        legacy = self.fixture["legacyUiSlots"].get(self.action_id, {})
        if key in legacy:
            return legacy[key]
        self.unchecked.append({"action": self.index, "identifier": self.action_id, "parameter": key})
        raise ContractError("Unknown configured parameter; no class or explicit exception")

    def value(self, value, slot):
        kind = slot["kind"]
        if kind in ("action-uuid", "block-uuid"):
            valid_uuid(value)
        elif kind == "block-mode":
            need(type(value) is int and value in (0, 1, 2), "Invalid block mode")
        elif kind == "conditional-operator":
            need(type(value) is int and value in self.fixture["conditionalSubjectOperators"],
                 "Unreviewed conditional operator requiring additional operand classes")
        elif kind == "conditional-subject":
            need(type(value) is dict and set(value) == {"Type", "Variable"} and value["Type"] == "Variable",
                 "Conditional input requires its special Variable wrapper")
            self.attachment(value["Variable"])
        elif kind == "legacy-ui-boolean-true":
            need(value is True, "Only the reviewed legacy UI flag Advanced=true is allowed")
        elif kind == "engine-parameter":
            cl = slot["class"]
            if cl in ("WFTextInputParameter", "WFRegexFieldParameter"):
                self.text(value)
            elif cl == "WFVariablePickerParameter":
                self.attachment(value)
            elif cl == "WFDictionaryParameter":
                self.dictionary(value, slot)
            elif cl in ("WFSwitchParameter", "WFExpandingParameter"):
                need(type(value) is bool, "FileFlash configured switch/expanding flag must be boolean")
            elif cl in ("WFEnumerationParameter", "WFCustomIntentEnumerationParameter"):
                need(type(value) is str, "FileFlash enumeration must be a literal string")
                if "items" in slot:
                    need(value in slot["items"], "Value is outside the extracted enumeration")
            elif cl == "WFNumberFieldParameter":
                need(type(value) is int and value > 0, "FileFlash match-group index must be a positive integer")
            else:
                self.unchecked.append({"action": self.index, "identifier": self.action_id,
                                       "parameter": self.field, "class": cl})
                raise ContractError("No validator for configured parameter class " + cl)
        else:
            raise ContractError("Unknown parameter-contract kind")

    def action_rules(self, action):
        p = params(action)
        if self.action_id == PREFIX + "downloadurl":
            need("WFURL" in p and "WFHTTPMethod" in p, "HTTP action lacks an explicit URL/method")
            if "WFHTTPHeaders" in p:
                need(p.get("ShowHeaders") is True, "Configured headers must have ShowHeaders enabled")
            body = p.get("WFHTTPBodyType")
            for field, expected in (("WFRequestVariable", "File"), ("WFJSONValues", "JSON"), ("WFFormValues", "Form")):
                if field in p:
                    need(body == expected, "Body field is disabled by the configured body type")
            if body == "File":
                need("WFRequestVariable" in p, "File body is missing its variable slot")
        if self.action_id == PREFIX + "getvalueforkey" and p.get("WFGetDictionaryValueType") == "Value":
            need("WFInput" in p and "WFDictionaryKey" in p, "Dictionary lookup has missing required input/key")
        if self.action_id in (PREFIX + "getitemname", PREFIX + "urlencode"):
            need("WFInput" in p, "Name/encoding action has no explicit input")

    def run(self, document):
        actions = document["WFWorkflowActions"]
        for self.index, action in enumerate(actions):
            self.action_id = ident(action)
            contract = self.fixture["actions"].get(self.action_id)
            if contract is None:
                self.errors.append({"action": self.index, "error": "Unknown configured action identifier"})
                self.unchecked.append({"action": self.index, "identifier": self.action_id})
                continue
            p = params(action)
            for self.field, value in p.items():
                row = {"action": self.index, "identifier": self.action_id, "parameter": self.field}
                try:
                    definition = self.slot(self.field, contract)
                    row.update(kind=definition["kind"], parameterClass=definition.get("class"),
                               stateClass=definition.get("stateClass"))
                    self.value(value, definition)
                    row["passed"] = True
                except (ContractError, UnicodeError) as error:
                    row.update(passed=False, error=str(error))
                    self.errors.append({"action": self.index, "parameter": self.field, "error": str(error)})
                self.rows.append(row)
            try:
                self.action_rules(action)
                action_uuid = p.get("UUID")
                valid_uuid(action_uuid)
                need(action_uuid not in self.producers, "Duplicate action UUID")
                self.producers[action_uuid] = {"index": self.index,
                                               "scope": [item["group"] for item in self.blocks]}
                if self.action_id in (PREFIX + "conditional", PREFIX + "repeat.each"):
                    mode, group = p.get("WFControlFlowMode"), p.get("GroupingIdentifier")
                    valid_uuid(group)
                    if mode == 0:
                        if self.action_id == PREFIX + "conditional":
                            need("WFCondition" in p and "WFInput" in p, "Conditional start lacks a subject/operator")
                        self.blocks.append({"identifier": self.action_id, "group": group})
                    else:
                        need(self.blocks and self.blocks[-1] == {"identifier": self.action_id, "group": group},
                             "Control-flow group/identifier does not match its open block")
                        if mode == 2:
                            self.blocks.pop()
            except ContractError as error:
                self.errors.append({"action": self.index, "error": str(error)})
        if self.blocks:
            self.errors.append({"error": "Unclosed control-flow blocks"})
        return {"passed": not self.errors and not self.unchecked,
                "actionCount": len(actions), "configuredParameterCount": len(self.rows),
                "engineParameterCount": sum(row.get("kind") == "engine-parameter" for row in self.rows),
                "variableReferenceCount": len(self.references), "nestedDictionaryEntryCount": self.dictionary_entries,
                "parameters": self.rows, "variableReferences": self.references,
                "errors": self.errors, "uncheckedFields": self.unchecked}


def audit(document, fixture):
    return Auditor(fixture).run(document)


def filename_binding(document):
    actions = document["WFWorkflowActions"]
    names = [a for a in actions if ident(a) == PREFIX + "getitemname"]
    encoders = [a for a in actions if ident(a) == PREFIX + "urlencode"]
    need(len(names) == len(encoders) == 1, "Expected one name-to-encoding chain")
    item = params(names[0])["WFInput"]
    need(item == {"Value": {"Type": "Variable", "VariableName": "Repeat Item"},
                  "WFSerializationType": "WFTextTokenAttachment"}, "Name must read the active Repeat Item")
    encoded_input = params(encoders[0])["WFInput"]
    need(encoded_input.get("WFSerializationType") == "WFTextTokenString", "Encoder input is not dynamic text")
    inner = encoded_input["Value"]
    need(inner.get("string") == "\ufffc" and set(inner.get("attachmentsByRange", {})) == {"{0, 1}"},
         "Filename text must have exactly one UTF-16 placeholder")
    reference = inner["attachmentsByRange"]["{0, 1}"]
    need(reference.get("Type") == "ActionOutput" and reference.get("OutputUUID") == params(names[0])["UUID"],
         "Encoded filename is not bound to Get Name's output UUID")
    need(params(encoders[0]).get("WFEncodeMode") == "Encode", "Filename must be URL encoded")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unsigned", type=Path, default=SOURCE)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    fixture_bytes, source_bytes = FIXTURE.read_bytes(), args.unsigned.read_bytes()
    fixture, document = json.loads(fixture_bytes), plistlib.loads(source_bytes)
    checks = []

    def check(name, callback):
        try:
            callback()
            checks.append({"name": name, "passed": True})
        except Exception as error:
            checks.append({"name": name, "passed": False, "error": str(error)})

    result = audit(document, fixture)
    check("Every configured action slot, including nested dictionaries and references, has a validated class",
          lambda: need(result["passed"], str(result["errors"])))
    check("Filename is UTF-16 token text bound to Get Name of the active Repeat Item", lambda: filename_binding(document))

    def reject_old():
        old = fixture["published017Regression"]
        need(old["sourceSha256"] == "ec226fdc01e2750286e7674592e16d85e877e8a36a1db534dce0d83de3fbd720",
             "Historical fixture is not the published 0.1.7 source")
        changed = copy.deepcopy(document)
        encoder = next(a for a in changed["WFWorkflowActions"] if ident(a) == PREFIX + "urlencode")
        params(encoder)["WFInput"] = old["encoder"]["WFWorkflowActionParameters"]["WFInput"]
        rejected = audit(changed, fixture)
        need(not rejected["passed"] and any(row.get("parameter") == "WFInput" and
             "WFTextTokenString" in row.get("error", "") for row in rejected["errors"]),
             "Published bare-attachment encoder was not rejected for its actual text-slot mismatch")

    check("Real published 0.1.7 encoder attachment is rejected specifically as a text-slot mismatch", reject_old)

    def exported_reference():
        p = fixture["exportedEncodeReference"]["action"]["WFWorkflowActionParameters"]
        need(p["WFEncodeMode"] == "Encode" and p["WFInput"]["WFSerializationType"] == "WFTextTokenString",
             "Independent exported Encode action does not corroborate text-token encoding")
        state = fixture["serializedStateExamples"]["WFVariableStringParameterState"]["variable:ActionOutput"]
        need(state["WFSerializationType"] == "WFTextTokenString", "Engine's text state evidence changed")

    check("Independent exported Encode action and actual text-state serialization corroborate the repaired slot", exported_reference)

    def unknown_slot():
        changed = copy.deepcopy(document)
        params(changed["WFWorkflowActions"][0])["UnreviewedParameter"] = "x"
        rejected = audit(changed, fixture)
        need(not rejected["passed"] and rejected["uncheckedFields"], "Unknown configured field was silently skipped")

    check("Unknown configured fields fail and appear in uncheckedFields", unknown_slot)

    def wrong_variable_slot():
        changed = copy.deepcopy(document)
        name = next(a for a in changed["WFWorkflowActions"] if ident(a) == PREFIX + "getitemname")
        params(name)["WFInput"] = fixture["serializedStateExamples"]["WFVariableStringParameterState"]["variable:MyVar"]
        need(not audit(changed, fixture)["passed"], "Text state incorrectly accepted in a variable-picker slot")

    check("A text token cannot replace a variable-picker attachment", wrong_variable_slot)

    def utf16_range(valid):
        changed = copy.deepcopy(document)
        server = next(a for a in changed["WFWorkflowActions"] if ident(a) == PREFIX + "text.match.getgroup")
        ref = {"Type": "ActionOutput", "OutputUUID": params(server)["UUID"], "OutputName": "Server"}
        params(changed["WFWorkflowActions"][-1])["WFAlertActionTitle"] = {
            "Value": {"string": "\U0001f600\ufffc", "attachmentsByRange": {"{2, 1}" if valid else "{1, 1}": ref}},
            "WFSerializationType": "WFTextTokenString"}
        outcome = audit(changed, fixture)
        need(outcome["passed"] is valid, "UTF-16 surrogate pair offset was not validated")

    check("UTF-16 offset after a supplementary character is accepted", lambda: utf16_range(True))
    check("A code-point offset incorrectly used as a UTF-16 offset is rejected", lambda: utf16_range(False))

    def wrong_enum():
        changed = copy.deepcopy(document)
        encoder = next(a for a in changed["WFWorkflowActions"] if ident(a) == PREFIX + "urlencode")
        params(encoder)["WFEncodeMode"] = "URL Encode"
        need(not audit(changed, fixture)["passed"], "Undeclared enum literal was accepted")

    check("Encode mode must be a real extracted enumeration literal", wrong_enum)

    def malformed_header():
        changed = copy.deepcopy(document)
        request = next(a for a in changed["WFWorkflowActions"] if ident(a) == PREFIX + "downloadurl")
        entries = params(request)["WFHTTPHeaders"]["Value"]["WFDictionaryFieldValueItems"]
        entries[0]["WFValue"] = {"Value": {"Type": "ExtensionInput"}, "WFSerializationType": "WFTextTokenAttachment"}
        need(not audit(changed, fixture)["passed"], "Header value bypassed nested text-state validation")

    check("Nested header values cannot hide a bare media/variable attachment", malformed_header)

    def invalid_loop_scope():
        changed = copy.deepcopy(document)
        name = next(a for a in changed["WFWorkflowActions"] if ident(a) == PREFIX + "getitemname")
        changed["WFWorkflowActions"].remove(name)
        changed["WFWorkflowActions"].insert(0, name)
        need(not audit(changed, fixture)["passed"], "Repeat Item was accepted outside its file-loop scope")

    check("Repeat Item cannot be used outside its loop", invalid_loop_scope)
    report = {"passed": result["passed"] and all(item["passed"] for item in checks),
              "unsignedSha256": hashlib.sha256(source_bytes).hexdigest(),
              "fixtureSha256": hashlib.sha256(fixture_bytes).hexdigest(),
              **{key: value for key, value in result.items() if key != "passed"},
              "checks": checks, "checkCount": len(checks),
              "metadataSources": fixture["metadataSources"],
              "scope": "Offline per-parameter class/serialization, nested value, UUID and loop/conditional scope validation. Independent extracted Apple engine metadata plus explicit structural/legacy exceptions; no Git/network required. Does not execute iOS, Shortcuts, media export or network transfer."}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(("PASS" if report["passed"] else "FAIL") + ": " + str(result["actionCount"]) + " actions / " +
              str(result["configuredParameterCount"]) + " configured slots / " + str(len(checks)) + " contract regressions.")
        for item in checks:
            if not item["passed"]:
                print(item["name"] + ": " + item["error"], file=sys.stderr)
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
