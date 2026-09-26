#!/usr/bin/env python3
"""Generate and verify the Phase 06 screen/state fixture and golden inventory."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
MATRIX = ROOT / "app/src/test/fixtures/phase06/screen-state-matrix.csv"
FIXTURE_INDEX = ROOT / "app/src/test/fixtures/phase06/fixture-index.json"
CONFIGS = (
    "dark-100",
    "dark-200",
    "light-100",
    "light-200",
    "monochrome",
)


INVENTORY = {
    "onboarding": [
        ("unpaired_default", "flows §4"),
        ("lan_permission_required", "design-profile: State vocabulary"),
        ("nearby_permission_maybe_required", "design-profile: State vocabulary"),
        ("saved_pairing_unavailable", "design-profile: State vocabulary"),
    ],
    "discovery": [
        ("searching", "flows §4"),
        ("one_result", "flows §4"),
        ("multiple_results_stable_order", "flows §4"),
        ("no_results", "flows §4"),
        ("no_local_network", "flows §4"),
        ("partial_bounded_scan", "flows §4"),
        ("failed_preserving_previous_results", "flows §4"),
        ("cancel_returns_immediately_discards_late_results", "flows §4"),
    ],
    "manual_address": [
        ("empty_fields", "flows §4"),
        ("field_validation_error", "flows §4, §11"),
        ("checking_device", "flows §4"),
        ("probe_failed_preserves_input", "flows §4, §11"),
    ],
    "candidate_details": [
        ("untrusted_identity_and_requested_scopes", "flows §5"),
        ("request_pairing_busy", "flows §5"),
    ],
    "scanner": [
        ("permission_prompt", "phase 06 plan: scanner UI"),
        ("camera_permission_denied", "phase 06 plan: scanner failure case"),
        ("scanning_fixture_viewfinder", "phase 06 plan: scanner UI; no live camera integration"),
        ("unrecognized_payload", "phase 06 plan: QR payload validation"),
        ("malformed_or_unsupported_payload", "phase 06 plan: QR payload validation"),
        ("expired_payload", "phase 06 plan: QR payload expiry"),
        ("payload_detected_target_summary", "phase 06 plan: QR payload pairing screen"),
    ],
    "pairing": [
        ("verification_code_waiting", "flows §5"),
        ("verification_code_200_reflow", "design-profile: AD5, R5"),
        ("verification_code_mismatch_hint", "flows §5"),
        ("payload_waiting_without_comparison_code", "phase 06 plan: QR payload"),
        ("verification_approved_continue_to_remote", "flows §5, protocol fixture: verification-approved"),
        ("approved_pair_status_timeout_keeps_pairing", "flows §5"),
        ("payload_request_approved", "protocol fixture: advanced-approved"),
        ("payload_request_pending", "protocol fixture: advanced-pending"),
        ("request_explicitly_cancelled_local_record_may_remain", "flows §5, protocol fixture: verification-cancelled"),
        ("approval_raced_with_local_cancel", "flows §5"),
        ("request_reply_not_confirmed", "design-profile: State vocabulary"),
        ("credential_delivery_or_save_unresolved", "design-profile: State vocabulary"),
        ("temporary_network_loss", "flows §5"),
        ("rate_limited", "flows §5"),
        ("rejected", "flows §5"),
        ("expired", "flows §5"),
        ("certificate_changed", "flows §5, design-profile: State vocabulary"),
        ("capacity_unavailable", "flows §5"),
        ("transport_channel_binding_unavailable", "protocol fixture: transport-binding-unavailable"),
        ("verification_code_conflict", "protocol fixture: verification-code-conflict"),
        ("verification_consumed_after_host_reload", "protocol fixture: verification-consumed-after-host-reload"),
        ("verification_scope_conflict", "protocol fixture: verification-scope-conflict"),
        ("pairing_session_invalid_or_missing", "protocol fixtures: verification-invalid-session, verification-session-required"),
        ("pairing_nonce_or_body_invalid", "protocol fixtures: invalid-nonce, malformed-json"),
        ("payload_invalid_or_not_found", "protocol fixtures: advanced-invalid-secret, advanced-not-found"),
        ("payload_scope_rejected", "protocol fixture: advanced-unapproved-scope"),
        ("payload_client_conflict", "protocol fixture: advanced-client-conflict"),
    ],
    "remote_overview": [
        ("first_read", "flows §7"),
        ("connected_fresh", "flows §6, design-profile: State vocabulary"),
        ("status_stale", "flows §7, design-profile: State vocabulary"),
        ("last_read_failed_unreachable", "flows §7, design-profile: State vocabulary"),
        ("remote_status_rate_limited", "protocol fixture: status/rate-limited"),
        ("steam_bridge_unavailable", "flows §7, design-profile: State vocabulary"),
        ("steam_bridge_not_ready", "protocol fixture: status/steam-bridge-not-ready"),
        ("credential_rejected", "flows §7, design-profile: State vocabulary"),
        ("device_identity_changed", "flows §7, design-profile: State vocabulary"),
        ("local_backend_unavailable", "flows §7, design-profile: State vocabulary"),
        ("permission_denied_for_capability", "flows §7"),
        ("capability_unverified_or_unsupported", "design-profile: State vocabulary"),
        ("active_preview_recovery_card", "flows §6, phase 06 plan"),
        ("unresolved_action_card", "flows §6, design-profile: State vocabulary"),
        ("operation_history_full", "flows §7, phase 06 plan"),
        ("no_recovery_mode_saved", "flows §9, phase 06 plan"),
        ("mutation_requested", "flows §13, design-profile: State vocabulary"),
        ("verified_action_result", "flows §13, design-profile: State vocabulary"),
        ("ambiguous_action_result", "flows §13, design-profile: State vocabulary"),
    ],
    "wake": [
        ("wake_not_configured", "flows §8"),
        ("sending_packet", "flows §8"),
        ("packet_sent_waiting_for_connection", "flows §8"),
        ("device_connected", "flows §8"),
        ("no_response_after_30_seconds", "flows §8"),
        ("packet_send_or_interface_failure", "flows §8"),
    ],
    "power": [
        ("power_options", "flows §8"),
        ("suspend_confirmation_cancel_first", "flows §8"),
        ("restart_confirmation_cancel_first", "flows §8"),
        ("shutdown_confirmation_cancel_first", "flows §8"),
        ("sending_action", "flows §8, §13"),
        ("action_accepted", "flows §8"),
        ("connection_lost_after_request_unconfirmed", "flows §8"),
        ("capability_unavailable", "flows §7"),
        ("missing_power_scope", "flows §7"),
        ("power_bridge_unavailable", "protocol fixture: power/bridge-unavailable"),
        ("power_forbidden_scope", "protocol fixture: power/forbidden-scope"),
        ("power_mutation_conflict", "protocol fixture: power/mutation-conflict"),
        ("power_request_id_conflict", "protocol fixture: power/request-id-conflict"),
        ("power_operation_failed_or_unknown_after_reload", "protocol fixtures: operations/power-failed, power-unknown-after-host-reload"),
    ],
    "sunshine": [
        ("monitoring_disabled", "flows §10"),
        ("provider_unavailable", "flows §10"),
        ("unknown_or_stale", "flows §10"),
        ("running", "flows §10, design-profile: State vocabulary"),
        ("restarting", "flows §10"),
        ("stopped_recovery_available", "flows §10, design-profile: State vocabulary"),
        ("recovery_requested", "flows §10"),
        ("recovery_failed_or_unknown", "flows §10"),
        ("recovery_provider_unavailable", "protocol fixture: sunshine-restart/provider-unavailable"),
        ("recovery_rejected_when_not_stopped", "protocol fixture: sunshine-restart/state-not-stopped"),
    ],
    "display": [
        ("loading_outputs", "flows §7, §9"),
        ("no_outputs", "flows §9"),
        ("single_output_current_and_saved_mode", "flows §9"),
        ("multiple_outputs_selector", "flows §9"),
        ("current_mode_selected", "flows §9"),
        ("valid_alternative_mode_selected", "flows §9"),
        ("refresh_rate_unknown", "flows §9"),
        ("display_bridge_unavailable", "flows §7, §9"),
        ("display_capability_unsupported", "flows §7, §9"),
        ("stale_output_or_target_generation", "flows §9"),
        ("preview_stale_output", "protocol fixture: display-preview/stale-target-output"),
        ("preview_stale_generation", "protocol fixture: display-preview/stale-target-generation"),
        ("another_operation_in_progress", "flows §7, phase 06 plan"),
        ("preview_applying", "flows §9"),
        ("preview_no_change", "protocol fixture: display-preview/no-change"),
        ("preview_forbidden_scope_or_bridge_unavailable", "protocol fixtures: display-preview/forbidden-scope, bridge-unavailable"),
        ("preview_active_countdown_and_revert_first", "flows §9, design-profile: R6"),
        ("preview_keep_requested", "flows §9, §13"),
        ("preview_keep_verified_readback", "flows §9, §13"),
        ("preview_keep_readback_unconfirmed", "flows §9, §13"),
        ("preview_confirmation_expired_or_not_found", "protocol fixtures: display-confirm/preview-expired, preview-not-found"),
        ("preview_confirmation_not_owner", "protocol fixture: display-confirm/not-preview-owner"),
        ("preview_confirmation_readback_not_confirmed", "protocol fixture: display-confirm/readback-not-confirmed"),
        ("preview_revert_requested", "flows §9, §13"),
        ("preview_reverted_verified", "flows §9"),
        ("preview_time_ended_checking_restoration", "flows §9, phase 06 plan"),
        ("connection_lost_during_preview", "flows §9, phase 06 plan"),
        ("restore_saved_requested", "flows §9"),
        ("restore_saved_verified", "flows §9"),
        ("restore_saved_failed_or_unconfirmed", "flows §9"),
        ("restore_profile_not_found", "protocol fixture: display-restore/profile-not-found"),
        ("restore_preview_expired_or_missing", "protocol fixtures: display-restore/preview-expired, preview-not-found"),
        ("no_recovery_profile", "flows §9, phase 06 plan"),
        ("save_current_visible_picture_confirmation", "flows §9, phase 06 plan"),
        ("save_current_confirmation_cancel_first", "flows §9, phase 06 plan: Cancel initially focused"),
        ("save_current_with_one_advertised_mode", "flows §9"),
        ("save_current_success_and_readback", "flows §9, §13"),
        ("save_current_readback_unconfirmed", "flows §9, §13"),
        ("save_current_stale_target", "flows §9"),
        ("save_current_bridge_unavailable", "flows §9"),
        ("save_current_mutation_conflict", "flows §9"),
        ("save_current_requires_visible_true", "protocol fixtures: display-save-current/visible-false, readback-not-confirmed"),
        ("old_host_cannot_save_current", "flows §9, phase 06 plan"),
        ("could_not_confirm_display_recovery", "flows §9, phase 06 plan"),
    ],
    "display_order": [
        ("available_order", "remote display order plan"),
        ("saved_order", "remote display order plan"),
        ("no_physical_inventory", "remote display order plan"),
        ("unsupported_host", "remote display order plan"),
        ("ambiguous_connector", "remote display order plan"),
        ("saved_output_disconnected", "remote display order plan"),
        ("ambiguous_display_identity", "protocol fixture: display-order-set/ambiguous-display-identity"),
        ("gamescope_output_unsupported", "protocol fixture: display-order-set/gamescope-output-unsupported"),
        ("gamescope_output_update_failed", "protocol fixtures: display-order-set/gamescope-output-update-failed, display-order-automatic/gamescope-output-update-failed"),
        ("stale_inventory", "remote display order plan"),
        ("mutation_conflict", "remote display order plan"),
        ("stale_display_target", "protocol fixture: display-order-set/stale-display-target"),
        ("invalid_duplicate_output_keys", "protocol fixture: display-order-set/invalid-duplicate-keys"),
        ("invalid_output_key", "protocol fixture: display-order-set/invalid-output-key"),
        ("invalid_extra_field", "protocol fixture: display-order-set/invalid-extra-field"),
        ("saved_for_next_session", "remote display order plan"),
        ("save_and_restart_requested", "remote display order plan"),
        ("save_and_restart_failed", "remote display order plan"),
        ("automatic_order_cleared", "remote display order plan"),
        ("automatic_order_failed", "remote display order plan"),
        ("automatic_order_mutation_conflict", "protocol fixture: display-order-automatic/mutation-conflict"),
    ],
    "connection_details": [
        ("paired_identity_scopes_and_fingerprint", "flows §11"),
        ("rename_local_alias", "flows §11"),
        ("edit_address_validation_error", "flows §11"),
        ("find_again_same_identity_address_updated", "flows §11"),
        ("find_again_identity_changed", "flows §11"),
        ("pair_another_device_staged_old_pairing_kept", "flows §11"),
        ("replace_pairing_confirmation_cancel_first", "flows §11"),
        ("replacement_cancel_or_failure_keeps_old_pairing", "flows §11"),
        ("remove_access_and_forget_confirmation", "flows §11"),
        ("forget_on_device_only_confirmation", "flows §11"),
        ("sanitized_diagnostics_export", "flows §11"),
    ],
}


FIELDS = ["screen", "scenario", "source", "contract_fixtures", "fixture_json"] + [
    f"golden_{config.replace('-', '_')}" for config in CONFIGS
]


def contract_fixture_refs(screen: str, source: str) -> list[str]:
    """Resolve the short contract-fixture labels to their pinned repository paths."""
    prefix = "protocol fixture:" if source.startswith("protocol fixture:") else "protocol fixtures:"
    if not source.startswith(prefix):
        return []
    labels = source.removeprefix(prefix).strip().replace(" and ", ", ").split(", ")
    default_directory = {
        "pairing": "pair-request",
        "remote_overview": "status",
        "power": "power",
        "sunshine": "sunshine-restart",
        "display": "display-preview",
        "display_order": "display-order-set",
    }[screen]
    refs: list[str] = []
    current_directory = default_directory
    for label in labels:
        if "/" in label:
            current_directory, filename = label.rsplit("/", maxsplit=1)
        else:
            filename = label
        refs.append(f"{current_directory}/{filename}.json")
    return refs


def fixture_document(row: dict[str, str]) -> dict[str, object]:
    refs = json.loads(row["contract_fixtures"])
    device_data: dict[str, object] = {
        "endpoint": "deck-1.invalid:18443",
        "hostId": "fixture-host-a2",
        "certificateFingerprint": "sha256:" + "00" * 32,
    }
    if row["screen"] in {"remote_overview", "display", "display_order", "power", "wake", "sunshine", "connection_details"}:
        device_data["localAlias"] = "Living-room Deck"
        device_data["grantedScopes"] = ["status.read", "power.control", "display.control"]
    elif row["screen"] == "pairing" and row["scenario"] in {
        "verification_approved_continue_to_remote",
        "payload_request_approved",
    }:
        device_data["grantedScopes"] = ["status.read", "power.control", "display.control"]
    return {
        "schemaVersion": 1,
        "fixtureOnly": True,
        "screen": row["screen"],
        "scenario": row["scenario"],
        "source": row["source"],
        "contractFixtures": refs,
        "sampleData": {
            "synthetic": True,
            "device": device_data,
            "pairing": {
                "clientName": "SteamOS Companion fixture",
                "requestedScopes": ["status.read", "power.control", "display.control"],
                "comparisonCode": "75332470",
                "payloadSecretIncluded": False,
            },
            "display": {
                "outputId": "fixture-output-1",
                "generation": 7,
                "name": "Fixture HDMI display",
                "currentModeId": "fixture-mode-current",
                "currentMode": {"width": 1920, "height": 1080, "refreshHz": 60.0},
                "savedModeId": "fixture-mode-saved",
                "savedMode": {"width": 1280, "height": 720, "refreshHz": 60.0},
                "modes": [
                    {"id": "fixture-mode-current", "width": 1920, "height": 1080, "refreshHz": 60.0},
                    {"id": "fixture-mode-saved", "width": 1280, "height": 720, "refreshHz": 60.0},
                ],
            },
            "discovery": {
                "candidateEndpoints": ["deck-a.invalid:18443", "deck-b.invalid:18443"],
            },
        },
    }


def matrix_rows() -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for screen, scenarios in INVENTORY.items():
        for scenario, source in scenarios:
            row = {
                "screen": screen,
                "scenario": scenario,
                "source": source,
                "contract_fixtures": json.dumps(contract_fixture_refs(screen, source), separators=(",", ":")),
                "fixture_json": f"app/src/test/fixtures/phase06/{screen}/{scenario}.json",
            }
            for config in CONFIGS:
                col = f"golden_{config.replace('-', '_')}"
                row[col] = (
                    f"app/src/test/screenshots/golden/phase06/{screen}/"
                    f"{scenario}/{config}.png"
                )
            rows.append(row)
    return rows


def write_matrix() -> None:
    MATRIX.parent.mkdir(parents=True, exist_ok=True)
    with MATRIX.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(matrix_rows())
    print(f"Wrote {len(matrix_rows())} screen/state rows to {MATRIX.relative_to(ROOT)}")


def write_fixtures() -> None:
    rows = matrix_rows()
    for row in rows:
        path = ROOT / row["fixture_json"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(fixture_document(row), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    index = {
        "schemaVersion": 1,
        "fixtures": [
            {
                "screen": row["screen"],
                "scenario": row["scenario"],
                "source": row["source"],
                "contractFixtures": json.loads(row["contract_fixtures"]),
                "resourcePath": row["fixture_json"].removeprefix("app/src/test/fixtures/"),
            }
            for row in rows
        ],
    }
    FIXTURE_INDEX.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE_INDEX.write_text(json.dumps(index, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {len(rows)} synthetic screen-state fixtures and the fixture index")


def check_matrix(check_goldens: bool = True) -> int:
    if not MATRIX.is_file():
        print(f"Missing matrix: {MATRIX.relative_to(ROOT)}", file=sys.stderr)
        return 1
    with MATRIX.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != FIELDS:
            print("Matrix columns do not match the required inventory schema", file=sys.stderr)
            return 1
        rows = list(reader)

    expected = matrix_rows()
    if rows != expected:
        print("Matrix does not exactly match the source inventory in this script", file=sys.stderr)
        return 1

    missing_fixtures: list[str] = []
    invalid_fixtures: list[str] = []
    missing_goldens: list[str] = []
    expected_index = {
        "schemaVersion": 1,
        "fixtures": [
            {
                "screen": row["screen"],
                "scenario": row["scenario"],
                "source": row["source"],
                "contractFixtures": json.loads(row["contract_fixtures"]),
                "resourcePath": row["fixture_json"].removeprefix("app/src/test/fixtures/"),
            }
            for row in rows
        ],
    }
    if not FIXTURE_INDEX.is_file():
        invalid_fixtures.append(str(FIXTURE_INDEX.relative_to(ROOT)))
    else:
        try:
            if json.loads(FIXTURE_INDEX.read_text(encoding="utf-8")) != expected_index:
                invalid_fixtures.append(str(FIXTURE_INDEX.relative_to(ROOT)))
        except (OSError, json.JSONDecodeError):
            invalid_fixtures.append(str(FIXTURE_INDEX.relative_to(ROOT)))

    for row in rows:
        fixture = ROOT / row["fixture_json"]
        if not fixture.is_file():
            missing_fixtures.append(row["fixture_json"])
        else:
            try:
                document = json.loads(fixture.read_text(encoding="utf-8"))
                if document != fixture_document(row):
                    invalid_fixtures.append(row["fixture_json"])
            except (OSError, json.JSONDecodeError):
                invalid_fixtures.append(row["fixture_json"])
        for reference in json.loads(row["contract_fixtures"]):
            if not (ROOT / "protocol/fixtures/contract" / reference).is_file():
                invalid_fixtures.append(f"{row['screen']}/{row['scenario']}: missing protocol fixture {reference}")
        if check_goldens:
            for config in CONFIGS:
                col = f"golden_{config.replace('-', '_')}"
                golden = ROOT / row[col]
                if not golden.is_file():
                    missing_goldens.append(row[col])

    if missing_fixtures or invalid_fixtures or missing_goldens:
        print(
            f"Phase 06 inventory: {len(rows)} screen/state rows; "
            f"{len(missing_fixtures)} fixture JSONs missing, {len(invalid_fixtures)} fixture/reference errors, "
            f"and {len(missing_goldens)} golden PNGs missing."
        )
        for path in missing_fixtures[:5] + invalid_fixtures[:5] + missing_goldens[:5]:
            print(f"  missing: {path}")
        return 1

    if check_goldens:
        print(
            f"Phase 06 inventory complete: {len(rows)} screen/state rows, "
            f"{len(rows)} fixtures, {len(rows) * len(CONFIGS)} goldens."
        )
    else:
        print(f"Phase 06 fixture inputs validated: {len(rows)} rows and {len(rows)} synthetic JSON files; protocol references exist.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="regenerate the checked-in matrix")
    parser.add_argument("--write-fixtures", action="store_true", help="write all synthetic fixture JSON inputs")
    parser.add_argument("--check-inputs", action="store_true", help="validate matrix and fixture inputs without requiring goldens")
    args = parser.parse_args()
    if args.write:
        write_matrix()
        return 0
    if args.write_fixtures:
        write_fixtures()
        return 0
    return check_matrix(check_goldens=not args.check_inputs)


if __name__ == "__main__":
    raise SystemExit(main())
