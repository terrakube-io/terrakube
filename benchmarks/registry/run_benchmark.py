#!/usr/bin/env python3
"""
Terrakube Registry Performance Benchmark Runner.
Automated CLI orchestrator for load-testing all 7 registry controller endpoints
with OpenTelemetry and Prometheus integration.

Supports:
  - Configurable registry URL (--registry-url)
  - Bearer token injection (--token), bypassing JWT generation
  - Flexible kubectl command (--kubectl-cmd)
  - Optional A/B candidate comparison (--candidate); omit for baseline-only mode
  - Time-series telemetry collection (periodic snapshots every 10s)
"""

import os
import sys
import time
import argparse
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from config import DEFAULT_CONFIG, BenchmarkConfig

from seeder import ensure_test_fixtures
from k8s_manager import K8sManager
from telemetry import TelemetryExtractor
from load_generator import LoadGenerator, StageResult
from visualizer import Visualizer
from reporter import ReportCompiler


def main():
    parser = argparse.ArgumentParser(
        description="Terrakube Registry Performance Benchmark Runner",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )

    # ── connection / auth ──────────────────────────────────────────────────
    parser.add_argument(
        "--registry-url",
        default=DEFAULT_CONFIG.registry_base_url,
        help="Registry base URL (e.g. https://terrakube-reg.microk8s.net)",
    )
    parser.add_argument(
        "--token",
        required=True,
        metavar="BEARER_TOKEN",
        help=(
            "Bearer token for authenticated endpoints (required). "
            "Tip: pass via environment variable: --token $TERRAKUBE_TOKEN_BENCHMARK"
        ),
    )
    parser.add_argument(
        "--ssh-host",
        default=DEFAULT_CONFIG.ssh_host,
        help="Remote MicroK8s SSH target (e.g. user@192.168.1.150)",
    )
    parser.add_argument(
        "--kubectl-cmd",
        default=DEFAULT_CONFIG.kubectl_cmd,
        metavar="CMD",
        help='kubectl command to use (e.g. "microk8s kubectl" or "kubectl")',
    )

    # ── images ─────────────────────────────────────────────────────────────
    parser.add_argument(
        "--baseline",
        default=DEFAULT_CONFIG.baseline_image,
        help="Baseline Docker image tag",
    )
    parser.add_argument(
        "--candidate",
        default=None,
        help=(
            "Candidate Docker image tag for A/B comparison. "
            "If omitted, only the baseline is tested (baseline-only mode)."
        ),
    )
    parser.add_argument(
        "--restore-image",
        default=DEFAULT_CONFIG.baseline_image,
        help="Image to restore when benchmark completes (ignored in baseline-only mode)",
    )

    # ── load profile ───────────────────────────────────────────────────────
    parser.add_argument("--workers", nargs="+", type=int, default=[5, 10, 25], help="Concurrency worker stages")
    parser.add_argument("--stage-duration", type=int, default=15, help="Duration in seconds per worker stage")
    parser.add_argument("--warmup", type=int, default=10, help="Warmup duration in seconds")
    parser.add_argument(
        "--warmup-exclusion",
        type=int,
        default=3,
        help="Duration in seconds discarded at the start of each stage to exclude JIT warm-up noise",
    )

    # ── execution control ──────────────────────────────────────────────────
    parser.add_argument("--skip-deploy", action="store_true", help="Skip kubectl set image rollouts (test active pod)")
    parser.add_argument("--quick", action="store_true", help="Quick mode: 5s per stage, 10 workers only")
    parser.add_argument("--output-dir", default="", help="Directory for reports and SVG diagrams")
    parser.add_argument(
        "--telemetry-interval",
        type=int,
        default=10,
        help="Seconds between background telemetry snapshots (time-series charts)",
    )

    args = parser.parse_args()

    # ── resolve settings ───────────────────────────────────────────────────
    output_dir = args.output_dir or os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "results")
    )
    os.makedirs(output_dir, exist_ok=True)

    stages = [10] if args.quick else args.workers
    stage_duration = 5 if args.quick else args.stage_duration
    warmup_duration = 5 if args.quick else args.warmup
    ab_mode = args.candidate is not None

    # ── banner ─────────────────────────────────────────────────────────────
    print("=" * 80)
    print("      TERRAKUBE REGISTRY PERFORMANCE ANALYSIS ENGINE")
    print("=" * 80)
    print(f"[*] Registry URL:      {args.registry_url}")
    print(f"[*] SSH Host:          {args.ssh_host}")
    print(f"[*] kubectl command:   {args.kubectl_cmd}")
    print(f"[*] Mode:              {'A/B Comparison' if ab_mode else 'Baseline-only'}")
    print(f"[*] Baseline Image:    {args.baseline}")
    if ab_mode:
        print(f"[*] Candidate Image:   {args.candidate}")
    print(f"[*] Concurrency Steps: {stages} workers")
    print(f"[*] Stage Duration:    {stage_duration}s per endpoint")
    print(f"[*] Output Directory:  {output_dir}")
    print("=" * 80)

    # ── 1. Test fixtures ───────────────────────────────────────────────────
    print("\n[Step 1/6] Validating and Seeding Test Fixtures...")
    ok, msg = ensure_test_fixtures(args.ssh_host)
    if ok:
        print(f"[✓] {msg}")
    else:
        print(f"[!] Seeding warning: {msg}")

    # ── 2. Authentication ──────────────────────────────────────────────────
    print("\n[Step 2/6] Preparing Bearer Token...")
    if not args.token.strip():
        print("[✗] ERROR: --token must not be empty. Aborting.")
        sys.exit(1)
    jwt_token = args.token
    print("[✓] Using provided --token as Bearer token.")

    # ── infrastructure objects ─────────────────────────────────────────────
    k8s = K8sManager(
        ssh_host=args.ssh_host,
        namespace=DEFAULT_CONFIG.k8s_namespace,
        deployment=DEFAULT_CONFIG.deployment_name,
        kubectl_cmd=args.kubectl_cmd,
        registry_base_url=args.registry_url,
    )
    telemetry = TelemetryExtractor(
        ssh_host=args.ssh_host,
        prom_service=DEFAULT_CONFIG.prometheus_url,
        kubectl_cmd=args.kubectl_cmd,
    )
    load_gen = LoadGenerator(base_url=args.registry_url, jwt_token=jwt_token)

    # ── helper: execute one full test battery ──────────────────────────────
    def run_image_test_battery(image_name: str) -> tuple:
        """
        Deploys (if requested), warms up, runs stepped load against all endpoints,
        and collects time-series telemetry snapshots in the background.

        Returns: (startup_sec, size_mb, final_metrics_snapshot, results, timeseries)
        """
        print("\n" + "-" * 75)
        print(f"[*] EXECUTING TEST BATTERY FOR: {image_name}")
        print("-" * 75)

        startup_sec = 0.0
        if not args.skip_deploy:
            success, startup_sec, info = k8s.deploy_image(image_name)
            if not success:
                print(f"[!] Error deploying {image_name}: {info}")
            time.sleep(3)
        else:
            print("[*] Skipping deployment rollout (--skip-deploy).")

        size_mb = k8s.get_image_size_mb(image_name)
        print(f"[*] Image disk size: {size_mb:.1f} MB")

        k8s.warm_up_pod(duration_seconds=warmup_duration)

        # ── start background telemetry thread ──────────────────────────────
        ts_snapshots: list = []
        stop_evt = threading.Event()
        ts_start = time.time()

        def _telemetry_worker():
            telemetry.capture_jvm_metrics_timeseries(
                interval_seconds=args.telemetry_interval,
                stop_event=stop_evt,
                snapshots=ts_snapshots,
                start_time=ts_start,
            )

        ts_thread = threading.Thread(target=_telemetry_worker, daemon=True)
        ts_thread.start()

        # ── stepped load ───────────────────────────────────────────────────
        warmup_ex = 1 if args.quick else args.warmup_exclusion
        results = []
        for ep in DEFAULT_CONFIG.endpoints:
            print(f"\n  >> Testing Endpoint: {ep.name} [{ep.method} {ep.path}]")
            for conc in stages:
                res = load_gen.run_stage(
                    ep,
                    concurrency=conc,
                    duration_seconds=stage_duration,
                    warmup_exclusion_seconds=warmup_ex,
                )
                results.append(res)

        # ── stop telemetry thread ──────────────────────────────────────────
        stop_evt.set()
        ts_thread.join(timeout=args.telemetry_interval + 5)

        # ── final point-in-time snapshot (for tabular report) ─────────────
        print("\n[*] Capturing final Prometheus & OpenTelemetry runtime metrics...")
        time.sleep(2)
        final_metrics = telemetry.capture_jvm_metrics()
        print(
            f"[✓] Heap: {final_metrics.get('heap_used_mb', 0)}MB | "
            f"GC Pause: {final_metrics.get('gc_duration_seconds_total', 0)*1000:.1f}ms | "
            f"RSS: {final_metrics.get('container_memory_rss_mb', 0)}MB | "
            f"Telemetry snapshots: {len(ts_snapshots)}"
        )

        return startup_sec, size_mb, final_metrics, results, ts_snapshots

    # ── 3. Baseline phase ──────────────────────────────────────────────────
    print("\n[Step 3/6] Starting Baseline Phase...")
    b_startup, b_size, b_telemetry, b_results, b_timeseries = run_image_test_battery(args.baseline)

    # ── 4. Candidate phase (optional) ─────────────────────────────────────
    c_startup = c_size = c_telemetry = c_results = c_timeseries = None
    if ab_mode:
        print("\n[Step 4/6] Starting Candidate Phase...")
        c_startup, c_size, c_telemetry, c_results, c_timeseries = run_image_test_battery(args.candidate)

        # Restore original image
        if not args.skip_deploy and args.restore_image:
            print(f"\n[*] Restoring deployment to {args.restore_image}...")
            k8s.deploy_image(args.restore_image)
    else:
        print("\n[Step 4/6] Skipped — baseline-only mode (no --candidate provided).")

    # ── 5. Charts ──────────────────────────────────────────────────────────
    print("\n[Step 5/6] Generating Visual Charts and Diagrams...")
    viz = Visualizer(output_dir)

    chart1 = viz.generate_latency_chart_svg(b_results, c_results)
    chart2 = viz.generate_throughput_chart_svg(b_results, c_results)
    chart2b = viz.generate_throughput_bytes_chart_svg(b_results, c_results)
    chart3 = viz.generate_jvm_memory_chart_svg(
        b_telemetry,
        c_telemetry,
        b_timeseries=b_timeseries,
        c_timeseries=c_timeseries,
    )
    chart4 = viz.generate_footprint_chart_svg(
        b_size_mb=b_size,
        b_start_s=b_startup,
        b_telemetry=b_telemetry,
        c_size_mb=c_size,
        c_start_s=c_startup,
        c_telemetry=c_telemetry,
        b_timeseries=b_timeseries,
        c_timeseries=c_timeseries,
    )
    print(f"[✓] Charts generated in: {output_dir}")

    # ── 6. Report ──────────────────────────────────────────────────────────
    print("\n[Step 6/6] Compiling Markdown Report...")
    reporter = ReportCompiler(output_dir)
    report_file = reporter.compile_report(
        baseline_image=args.baseline,
        b_startup=b_startup,
        b_size=b_size,
        b_telemetry=b_telemetry,
        b_results=b_results,
        candidate_image=args.candidate,
        c_startup=c_startup,
        c_size=c_size,
        c_telemetry=c_telemetry,
        c_results=c_results,
        registry_url=args.registry_url,
        ssh_host=args.ssh_host,
    )
    print(f"[✓] Analysis Report compiled: {report_file}")

    print("\n" + "=" * 80)
    print("                 BENCHMARK RUN COMPLETE!")
    print("=" * 80)
    print(f"Report File: {report_file}")
    print("=" * 80)


if __name__ == "__main__":
    main()
