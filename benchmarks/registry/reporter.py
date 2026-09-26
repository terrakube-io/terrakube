#!/usr/bin/env python3
"""
Performance Analysis Report Compiler for Terrakube Registry.
Generates comprehensive Markdown report with summary tables, latency percentiles,
throughput (RPS), optional delta calculations (%), and telemetry insights.
Candidate image is fully optional — when omitted a baseline-only report is produced.
"""

import os
from datetime import datetime
from typing import Dict, Any, List, Optional


class ReportCompiler:
    def __init__(self, output_dir: str):
        self.output_dir = output_dir

    def _calc_delta(self, baseline: float, candidate: float, higher_is_better: bool = False) -> str:
        if baseline == 0:
            return "N/A"
        diff = ((candidate - baseline) / baseline) * 100
        sign = "+" if diff > 0 else ""
        if diff == 0:
            return "0.0%"
        if (higher_is_better and diff > 0) or (not higher_is_better and diff < 0):
            return f"**{sign}{diff:.1f}%** 🟢"
        else:
            return f"**{sign}{diff:.1f}%** 🔴"

    def compile_report(
        self,
        baseline_image: str,
        b_startup: float,
        b_size: float,
        b_telemetry: Dict[str, Any],
        b_results: List[Any],
        candidate_image: Optional[str] = None,
        c_startup: Optional[float] = None,
        c_size: Optional[float] = None,
        c_telemetry: Optional[Dict[str, Any]] = None,
        c_results: Optional[List[Any]] = None,
        registry_url: str = "https://terrakube-reg.microk8s.net",
        ssh_host: str = "user@192.168.1.150",
    ) -> str:
        report_path = os.path.join(self.output_dir, "REGISTRY_PERFORMANCE_ANALYSIS.md")
        now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S UTC")
        ab_mode = candidate_image is not None

        title = (
            f"# Terrakube Registry Performance Analysis: {baseline_image} vs {candidate_image}"
            if ab_mode
            else f"# Terrakube Registry Performance Analysis: {baseline_image}"
        )

        lines = [
            title,
            "",
            f"> **Report Generated**: {now_str}  ",
            f"> **Environment**: MicroK8s Remote Cluster (`{ssh_host}`)  ",
            f"> **Registry URL**: `{registry_url}`  ",
            f"> **Baseline Image**: `{baseline_image}`  ",
        ]
        if ab_mode:
            lines.append(f"> **Candidate Image**: `{candidate_image}`  ")
        lines += [
            "",
            "---",
            "",
            "## 1. Executive Summary",
            "",
        ]

        if ab_mode:
            lines += [
                "A comprehensive A/B performance evaluation was conducted comparing the baseline image against "
                "the candidate image across all 7 controller endpoints under stepped concurrency, "
                "while continuously gathering OpenTelemetry and Prometheus runtime metrics.",
                "",
                "### Key Highlights:",
            ]
            if b_size and c_size is not None:
                lines.append(f"- **Container Disk Footprint**: Candidate: **{c_size:.1f} MB** vs Baseline: **{b_size:.1f} MB** ({self._calc_delta(b_size, c_size, False)}).")
            if b_startup and c_startup is not None:
                lines.append(f"- **Cold Startup to Ready**: Candidate initialized in **{c_startup:.2f}s** vs **{b_startup:.2f}s** ({self._calc_delta(b_startup, c_startup, higher_is_better=False)}).")
            if c_telemetry:
                lines.append(f"- **JVM Heap Footprint Under Load**: Heap memory utilized was **{c_telemetry.get('heap_used_mb', 0):.1f} MB** vs **{b_telemetry.get('heap_used_mb', 0):.1f} MB** ({self._calc_delta(b_telemetry.get('heap_used_mb', 0), c_telemetry.get('heap_used_mb', 0), False)}).")
                lines.append(f"- **GC Pause Total Duration**: {c_telemetry.get('gc_duration_seconds_total', 0)*1000:.1f}ms (Candidate) vs {b_telemetry.get('gc_duration_seconds_total', 0)*1000:.1f}ms (Baseline).")
        else:
            lines += [
                "A baseline-only performance evaluation was conducted against all 7 controller endpoints "
                "under stepped concurrency, while continuously gathering OpenTelemetry and Prometheus runtime metrics.",
                "",
                "### Baseline Highlights:",
                f"- **Container Disk Footprint**: **{b_size:.1f} MB**." if b_size else "",
                f"- **Cold Startup to Ready**: **{b_startup:.2f}s**." if b_startup else "",
                f"- **JVM Heap Under Load**: **{b_telemetry.get('heap_used_mb', 0):.1f} MB** heap used.",
                f"- **GC Pause Total**: **{b_telemetry.get('gc_duration_seconds_total', 0)*1000:.1f} ms**.",
            ]

        lines += [
            "",
            "---",
            "",
            "## 2. Architecture & Benchmark Telemetry Pipeline",
            "",
            "```mermaid",
            "sequenceDiagram",
            "    autonumber",
            "    actor Harness as Python Benchmark Harness",
            "    participant Reg as Terrakube Registry Pod (:8075)",
            "    participant Otel as OpenTelemetry JavaAgent (:9464)",
            "    participant S3 as SeaweedFS S3 Storage",
            "    participant DB as CloudNativePG PostgreSQL",
            "    participant Prom as Prometheus (:9090)",
            "",
            "    Note over Harness,Prom: Stepped Load Generation (workers staged)",
            "    Harness->>Reg: HTTP Requests (WellKnown, Modules, Providers, ReadMe)",
            "    Reg->>DB: SQL Queries & Download Counter Updates",
            "    Reg->>S3: Binary Module Zip Streaming & In-Memory Unzip",
            "    Reg->>Otel: Record JVM, Memory, Threads & Latency Metrics",
            "    Otel->>Prom: Expose /metrics on Port 9464",
            "    Harness->>Prom: Query Prometheus Instant & Window Telemetry",
            "```",
            "",
            "---",
            "",
            "## 3. Container & Operating System Footprint",
            "",
        ]

        if ab_mode:
            lines += [
                "| Metric | Baseline | Candidate | Delta (%) | Status |",
                "| :--- | :--- | :--- | :--- | :--- |",
                f"| **Image Size on Disk** | `{b_size:.1f} MB` | `{c_size:.1f} MB` | {self._calc_delta(b_size, c_size, False)} | {'🟢 Smaller' if (c_size or 0) <= b_size else '🔴 Larger'} |",
                f"| **Cold Startup to Ready** | `{b_startup:.2f} s` | `{c_startup:.2f} s` | {self._calc_delta(b_startup, c_startup, False)} | {'🟢 Faster' if (c_startup or 0) <= b_startup else '🟡 Comparable'} |",
                f"| **Container RSS Working Set** | `{b_telemetry.get('container_memory_rss_mb', 0):.1f} MB` | `{(c_telemetry or {}).get('container_memory_rss_mb', 0):.1f} MB` | {self._calc_delta(b_telemetry.get('container_memory_rss_mb', 0), (c_telemetry or {}).get('container_memory_rss_mb', 0), False)} | {'🟢 Efficient' if (c_telemetry or {}).get('container_memory_rss_mb', 0) <= b_telemetry.get('container_memory_rss_mb', 0) else '🟡 Normal'} |",
            ]
        else:
            lines += [
                "| Metric | Baseline |",
                "| :--- | :--- |",
                f"| **Image Size on Disk** | `{b_size:.1f} MB` |",
                f"| **Cold Startup to Ready** | `{b_startup:.2f} s` |",
                f"| **Container RSS Working Set** | `{b_telemetry.get('container_memory_rss_mb', 0):.1f} MB` |",
            ]

        lines += [
            "",
            "---",
            "",
            "## 4. JVM Runtime & Garbage Collection Telemetry",
            "",
        ]

        if ab_mode:
            lines += [
                "| Metric | Baseline | Candidate | Delta (%) |",
                "| :--- | :--- | :--- | :--- |",
                f"| **JVM Heap Used** | `{b_telemetry.get('heap_used_mb', 0):.1f} MB` | `{(c_telemetry or {}).get('heap_used_mb', 0):.1f} MB` | {self._calc_delta(b_telemetry.get('heap_used_mb', 0), (c_telemetry or {}).get('heap_used_mb', 0), False)} |",
                f"| **JVM Heap Committed** | `{b_telemetry.get('heap_committed_mb', 0):.1f} MB` | `{(c_telemetry or {}).get('heap_committed_mb', 0):.1f} MB` | {self._calc_delta(b_telemetry.get('heap_committed_mb', 0), (c_telemetry or {}).get('heap_committed_mb', 0), False)} |",
                f"| **JVM Non-Heap (Metaspace)** | `{b_telemetry.get('non_heap_used_mb', 0):.1f} MB` | `{(c_telemetry or {}).get('non_heap_used_mb', 0):.1f} MB` | {self._calc_delta(b_telemetry.get('non_heap_used_mb', 0), (c_telemetry or {}).get('non_heap_used_mb', 0), False)} |",
                f"| **Total GC Pause Time** | `{b_telemetry.get('gc_duration_seconds_total', 0)*1000:.1f} ms` | `{(c_telemetry or {}).get('gc_duration_seconds_total', 0)*1000:.1f} ms` | {self._calc_delta(b_telemetry.get('gc_duration_seconds_total', 0), (c_telemetry or {}).get('gc_duration_seconds_total', 0), False)} |",
                f"| **Average GC Pause Duration** | `{b_telemetry.get('gc_avg_pause_ms', 0):.2f} ms` | `{(c_telemetry or {}).get('gc_avg_pause_ms', 0):.2f} ms` | {self._calc_delta(b_telemetry.get('gc_avg_pause_ms', 0), (c_telemetry or {}).get('gc_avg_pause_ms', 0), False)} |",
                f"| **Total GC Cycle Count** | `{b_telemetry.get('gc_count_total', 0)}` | `{(c_telemetry or {}).get('gc_count_total', 0)}` | {self._calc_delta(b_telemetry.get('gc_count_total', 0), (c_telemetry or {}).get('gc_count_total', 0), False)} |",
                f"| **Live Thread Count** | `{b_telemetry.get('thread_count', 0)}` | `{(c_telemetry or {}).get('thread_count', 0)}` | {self._calc_delta(b_telemetry.get('thread_count', 0), (c_telemetry or {}).get('thread_count', 0), False)} |",
            ]
        else:
            lines += [
                "| Metric | Baseline |",
                "| :--- | :--- |",
                f"| **JVM Heap Used** | `{b_telemetry.get('heap_used_mb', 0):.1f} MB` |",
                f"| **JVM Heap Committed** | `{b_telemetry.get('heap_committed_mb', 0):.1f} MB` |",
                f"| **JVM Non-Heap (Metaspace)** | `{b_telemetry.get('non_heap_used_mb', 0):.1f} MB` |",
                f"| **Total GC Pause Time** | `{b_telemetry.get('gc_duration_seconds_total', 0)*1000:.1f} ms` |",
                f"| **Average GC Pause Duration** | `{b_telemetry.get('gc_avg_pause_ms', 0):.2f} ms` |",
                f"| **Total GC Cycle Count** | `{b_telemetry.get('gc_count_total', 0)}` |",
                f"| **Live Thread Count** | `{b_telemetry.get('thread_count', 0)}` |",
            ]

        lines += [
            "",
            "---",
            "",
            "## 5. Per-Endpoint Benchmark Results across Concurrency Levels",
            "",
        ]

        # ── table header ──
        if ab_mode:
            lines += [
                "| Endpoint | Controller | Workers | Baseline RPS | Candidate RPS | RPS Delta | Baseline MB/s | Candidate MB/s | MB/s Delta | Baseline p95 (ms) | Candidate p95 (ms) | p95 Delta | Baseline p99 (ms) | Candidate p99 (ms) | Base Errors | Cand Errors |",
                "| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |",
            ]
            b_dict = {(r.endpoint_name, r.concurrency): r for r in b_results}
            c_dict = {(r.endpoint_name, r.concurrency): r for r in (c_results or [])}
            for (ep_name, conc), b_r in sorted(b_dict.items(), key=lambda x: (x[0][0], x[0][1])):
                c_r = c_dict.get((ep_name, conc), b_r)
                b_mb_s = (b_r.throughput_bytes_sec / (1024 * 1024)) if getattr(b_r, 'throughput_bytes_sec', 0) > 0 else ((b_r.total_bytes_transferred / b_r.duration_seconds) / (1024 * 1024) if b_r.duration_seconds > 0 else 0.0)
                c_mb_s = (c_r.throughput_bytes_sec / (1024 * 1024)) if getattr(c_r, 'throughput_bytes_sec', 0) > 0 else ((c_r.total_bytes_transferred / c_r.duration_seconds) / (1024 * 1024) if c_r.duration_seconds > 0 else 0.0)
                rps_delta = self._calc_delta(b_r.rps, c_r.rps, higher_is_better=True)
                mb_delta = self._calc_delta(b_mb_s, c_mb_s, higher_is_better=True)
                p95_delta = self._calc_delta(b_r.latency_p95_ms, c_r.latency_p95_ms, higher_is_better=False)
                lines.append(
                    f"| `{ep_name}` | `{b_r.controller}` | {conc} | {b_r.rps:.1f} | {c_r.rps:.1f} | {rps_delta} | {b_mb_s:.2f} | {c_mb_s:.2f} | {mb_delta} | {b_r.latency_p95_ms:.1f} | {c_r.latency_p95_ms:.1f} | {p95_delta} | {b_r.latency_p99_ms:.1f} | {c_r.latency_p99_ms:.1f} | {b_r.failed_requests}/{b_r.total_requests} | {c_r.failed_requests}/{c_r.total_requests} |"
                )
        else:
            lines += [
                "| Endpoint | Controller | Workers | RPS | MB/s | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | StdDev (ms) | Min/Max (ms) | Errors |",
                "| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |",
            ]
            b_dict = {(r.endpoint_name, r.concurrency): r for r in b_results}
            for (ep_name, conc), b_r in sorted(b_dict.items(), key=lambda x: (x[0][0], x[0][1])):
                b_mb_s = (b_r.throughput_bytes_sec / (1024 * 1024)) if getattr(b_r, 'throughput_bytes_sec', 0) > 0 else ((b_r.total_bytes_transferred / b_r.duration_seconds) / (1024 * 1024) if b_r.duration_seconds > 0 else 0.0)
                err_str = f"{b_r.failed_requests}/{b_r.total_requests} ({b_r.error_rate_pct:.1f}%)" if hasattr(b_r, 'error_rate_pct') else f"{b_r.failed_requests}/{b_r.total_requests}"
                lines.append(
                    f"| `{ep_name}` | `{b_r.controller}` | {conc} | {b_r.rps:.1f} | {b_mb_s:.2f} | {b_r.latency_p50_ms:.1f} | {b_r.latency_p90_ms:.1f} | {b_r.latency_p95_ms:.1f} | {b_r.latency_p99_ms:.1f} | {b_r.latency_stddev_ms:.1f} | {b_r.latency_min_ms:.1f}/{b_r.latency_max_ms:.1f} | {err_str} |"
                )

        lines += [
            "",
            "---",
            "",
            "## 6. Visual Performance Diagrams",
            "",
            "### Latency Percentiles (p50 / p90 / p95 / p99) per Endpoint",
            "_Grouped bar chart — one sub-panel per concurrency level, 4 bars (p50/p90/p95/p99) per endpoint._",
            "![Latency Comparison](file://" + os.path.join(self.output_dir, "latency_comparison_p95_p99.svg") + ")",
            "",
            "### Throughput (RPS) per Endpoint across Concurrency Levels",
            "_One sub-panel per concurrency level — RPS bar per endpoint._",
            "![Throughput Scaling](file://" + os.path.join(self.output_dir, "throughput_vs_concurrency.svg") + ")",
            "",
            "### Throughput (MB/s) per Endpoint across Concurrency Levels",
            "_One sub-panel per concurrency level — MB/s bar per endpoint._",
            "![Throughput Bytes](file://" + os.path.join(self.output_dir, "throughput_bytes_sec.svg") + ")",
            "",
            "### JVM Heap Memory & Garbage Collection — Over Time",
            "![JVM Footprint](file://" + os.path.join(self.output_dir, "jvm_memory_and_gc.svg") + ")",
            "",
            "### Container Footprint & RSS Memory — Over Time",
            "![Container Footprint](file://" + os.path.join(self.output_dir, "container_cpu_and_startup.svg") + ")",
            "",
            "---",
            "",
            "## 7. Conclusions & Observations",
            "",
        ]

        if ab_mode:
            lines += [
                "1. **Container Density & Resource Footprint**: Compare disk footprint and startup metrics above to evaluate deployment density trade-offs.",
                "2. **Throughput & Latency Resilience**: Review per-endpoint p95/p99 latency and RPS delta columns for regression or improvement signals.",
                "3. **JVM Runtime Behavior**: Time-series charts reveal GC pressure patterns and heap growth under sustained concurrency load.",
                "",
            ]
        else:
            lines += [
                "1. **Baseline Profile Established**: Use these results as the reference for future A/B candidate comparisons.",
                "2. **JVM Runtime Behavior**: Time-series charts reveal GC pressure patterns and heap growth under sustained concurrency load.",
                "3. **Re-run with Candidate**: Add `--candidate <image>` to run a comparative A/B analysis against this baseline.",
                "",
            ]

        report_content = "\n".join(lines)
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(report_content)
        return report_path
