#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

QA_ROOT = Path(__file__).resolve().parent
if str(QA_ROOT) not in sys.path:
    sys.path.insert(0, str(QA_ROOT))

from senpqa.benchmark import compare_files, validate_benchmark_report, validate_stage_report
from senpqa.common import QaError, load_json, write_json
from senpqa.corpus import DEFAULT_LOCK_PATH, load_context, selected_records, validate_corpus
from senpqa.emulator import EmulatorConfig, run_emulator_harness
from senpqa.golden import evaluate_result, generate_fixtures, validate_fixture_directory
from senpqa.pose_overlay import render_pose_overlays
from senpqa.visuals import generate_visual_artifacts


def _print(value: Any) -> None:
    print(json.dumps(value, indent=2, sort_keys=True))


def _context(args: argparse.Namespace):
    return load_context(
        Path(args.lock),
        manifest_path=Path(args.manifest).resolve() if getattr(args, "manifest", None) else None,
        root=Path(args.root).resolve() if getattr(args, "root", None) else None,
        verify_manifest_hash=not getattr(args, "allow_manifest_change", False),
    )


def command_corpus_validate(args: argparse.Namespace) -> dict[str, Any]:
    report = validate_corpus(
        _context(args),
        selected_only=args.selected_only,
        ids=args.id,
        hash_files=not args.no_hash,
    )
    if args.output:
        write_json(Path(args.output), report)
    return report


def command_corpus_list(args: argparse.Namespace) -> dict[str, Any]:
    context = _context(args)
    rows = []
    for selection, record in selected_records(context, args.id):
        rows.append(
            {
                "id": selection["id"],
                "label": selection.get("label"),
                "relative_path": record.relative_path,
                "codec": record.video_codec,
                "orientation": record.orientation,
                "duration_ms": round(record.duration_sec * 1000),
            }
        )
    return {"manifest": str(context.manifest_path), "root": str(context.root), "selections": rows}


def command_visual_generate(args: argparse.Namespace) -> dict[str, Any]:
    context = _context(args)
    validate_corpus(context, selected_only=True, ids=args.id, hash_files=not args.no_hash)
    return generate_visual_artifacts(
        context,
        Path(args.output_dir),
        ids=args.id,
        ffmpeg=args.ffmpeg,
        ffprobe=args.ffprobe,
        include_pairs=not args.no_pairs,
    )


def command_overlay_render(args: argparse.Namespace) -> dict[str, Any]:
    return render_pose_overlays(
        Path(args.pose_json),
        Path(args.output_dir),
        background=Path(args.background) if args.background else None,
        ffmpeg=args.ffmpeg,
    )


def command_golden_generate(args: argparse.Namespace) -> dict[str, Any]:
    paths = generate_fixtures(Path(args.directory))
    return {"ok": True, "fixtures": [str(path.resolve()) for path in paths]}


def command_golden_validate(args: argparse.Namespace) -> dict[str, Any]:
    return validate_fixture_directory(Path(args.directory), verify_determinism=not args.skip_determinism)


def command_golden_evaluate(args: argparse.Namespace) -> dict[str, Any]:
    return evaluate_result(load_json(Path(args.fixture)), load_json(Path(args.result)))


def command_benchmark_validate_stage(args: argparse.Namespace) -> dict[str, Any]:
    return {"ok": True, "report": validate_stage_report(load_json(Path(args.report)))}


def command_benchmark_validate_report(args: argparse.Namespace) -> dict[str, Any]:
    return {"ok": True, "report": validate_benchmark_report(load_json(Path(args.report)))}


def command_benchmark_compare(args: argparse.Namespace) -> dict[str, Any]:
    return compare_files(
        Path(args.baseline),
        Path(args.candidate),
        Path(args.gates),
        Path(args.output) if args.output else None,
    )


def command_emulator_run(args: argparse.Namespace) -> dict[str, Any]:
    return run_emulator_harness(
        EmulatorConfig(
            apk=Path(args.apk),
            test_apk=Path(args.test_apk),
            package=args.package,
            runner=args.runner,
            output_dir=Path(args.output_dir),
            test_package=args.test_package,
            adb=args.adb,
            emulator_helper=args.emulator_helper,
            avd=args.avd,
            expected_api=args.expected_api,
            boot_timeout_sec=args.boot_timeout,
            start_emulator=not args.no_start,
            remote_artifact=args.remote_artifact,
        )
    )


def add_corpus_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--lock", default=str(DEFAULT_LOCK_PATH))
    parser.add_argument("--manifest")
    parser.add_argument("--root")
    parser.add_argument("--allow-manifest-change", action="store_true")
    parser.add_argument("--id", action="append", default=[])


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Senp.ai0 Wave 1 QA verification CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    corpus = subparsers.add_parser("corpus")
    corpus_sub = corpus.add_subparsers(dest="corpus_command", required=True)
    corpus_validate = corpus_sub.add_parser("validate")
    add_corpus_options(corpus_validate)
    corpus_validate.add_argument("--selected-only", action="store_true")
    corpus_validate.add_argument("--no-hash", action="store_true")
    corpus_validate.add_argument("--output")
    corpus_validate.set_defaults(func=command_corpus_validate)
    corpus_list = corpus_sub.add_parser("list")
    add_corpus_options(corpus_list)
    corpus_list.set_defaults(func=command_corpus_list)

    visual = subparsers.add_parser("visual")
    visual_sub = visual.add_subparsers(dest="visual_command", required=True)
    visual_generate = visual_sub.add_parser("generate")
    add_corpus_options(visual_generate)
    visual_generate.add_argument("--output-dir", required=True)
    visual_generate.add_argument("--ffmpeg", default="ffmpeg")
    visual_generate.add_argument("--ffprobe", default="ffprobe")
    visual_generate.add_argument("--no-hash", action="store_true")
    visual_generate.add_argument("--no-pairs", action="store_true")
    visual_generate.set_defaults(func=command_visual_generate)

    overlay = subparsers.add_parser("overlay")
    overlay_sub = overlay.add_subparsers(dest="overlay_command", required=True)
    overlay_render = overlay_sub.add_parser("render")
    overlay_render.add_argument("--pose-json", required=True)
    overlay_render.add_argument("--output-dir", required=True)
    overlay_render.add_argument("--background")
    overlay_render.add_argument("--ffmpeg", default="ffmpeg")
    overlay_render.set_defaults(func=command_overlay_render)

    golden = subparsers.add_parser("golden")
    golden_sub = golden.add_subparsers(dest="golden_command", required=True)
    golden_generate = golden_sub.add_parser("generate")
    golden_generate.add_argument("--directory", default=str(QA_ROOT / "fixtures" / "golden"))
    golden_generate.set_defaults(func=command_golden_generate)
    golden_validate = golden_sub.add_parser("validate")
    golden_validate.add_argument("--directory", default=str(QA_ROOT / "fixtures" / "golden"))
    golden_validate.add_argument("--skip-determinism", action="store_true")
    golden_validate.set_defaults(func=command_golden_validate)
    golden_evaluate = golden_sub.add_parser("evaluate")
    golden_evaluate.add_argument("--fixture", required=True)
    golden_evaluate.add_argument("--result", required=True)
    golden_evaluate.set_defaults(func=command_golden_evaluate)

    benchmark = subparsers.add_parser("benchmark")
    benchmark_sub = benchmark.add_subparsers(dest="benchmark_command", required=True)
    stage = benchmark_sub.add_parser("validate-stage")
    stage.add_argument("--report", required=True)
    stage.set_defaults(func=command_benchmark_validate_stage)
    bench_validate = benchmark_sub.add_parser("validate-report")
    bench_validate.add_argument("--report", required=True)
    bench_validate.set_defaults(func=command_benchmark_validate_report)
    compare = benchmark_sub.add_parser("compare")
    compare.add_argument("--baseline", required=True)
    compare.add_argument("--candidate", required=True)
    compare.add_argument("--gates", required=True)
    compare.add_argument("--output")
    compare.set_defaults(func=command_benchmark_compare)

    emulator = subparsers.add_parser("emulator")
    emulator_sub = emulator.add_subparsers(dest="emulator_command", required=True)
    emulator_run = emulator_sub.add_parser("run")
    emulator_run.add_argument("--apk", required=True)
    emulator_run.add_argument("--test-apk", required=True)
    emulator_run.add_argument("--package", default="com.senp.qa.smoke")
    emulator_run.add_argument("--test-package", help="Instrumentation APK application id; defaults to <package>.test")
    emulator_run.add_argument("--runner", default="com.senp.qa.smoke.test.SmokeInstrumentation")
    emulator_run.add_argument(
        "--remote-artifact",
        default="/sdcard/Android/data/com.senp.qa.smoke/files/senp-qa-smoke.json",
        help="Device JSON artifact to pull after instrumentation",
    )
    emulator_run.add_argument("--output-dir", required=True)
    emulator_run.add_argument("--adb", default="adb")
    emulator_run.add_argument("--emulator-helper", default="senp-emulator")
    emulator_run.add_argument("--avd", default="senp_api35")
    emulator_run.add_argument("--expected-api", type=int, default=35)
    emulator_run.add_argument("--boot-timeout", type=int, default=480)
    emulator_run.add_argument("--no-start", action="store_true")
    emulator_run.set_defaults(func=command_emulator_run)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        result = args.func(args)
    except QaError as exc:
        _print({"ok": False, "error": exc.as_dict()})
        return 1
    _print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
