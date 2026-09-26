#!/usr/bin/env python3
"""
High-Performance Concurrent Load Generator for Terrakube Benchmark Suite.
Executes stepped load testing against Registry endpoints and records latency percentiles.
"""

import time
import ssl
import math
import statistics
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional, Tuple

from config import EndpointConfig

@dataclass
class StageResult:
    endpoint_name: str
    controller: str
    concurrency: int
    duration_seconds: float
    total_requests: int
    successful_requests: int
    failed_requests: int
    status_codes: Dict[int, int]
    rps: float
    latency_p50_ms: float
    latency_p90_ms: float
    latency_p95_ms: float
    latency_p99_ms: float
    latency_avg_ms: float
    latency_min_ms: float
    latency_max_ms: float
    latency_stddev_ms: float
    total_bytes_transferred: int
    throughput_bytes_sec: float = 0.0
    error_rate_pct: float = 0.0
    error_samples: List[str] = field(default_factory=list)

def _percentile(sorted_list: List[float], pct: float) -> float:
    if not sorted_list:
        return 0.0
    k = (len(sorted_list) - 1) * (pct / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_list[int(k)]
    d0 = sorted_list[int(f)] * (c - k)
    d1 = sorted_list[int(c)] * (k - f)
    return d0 + d1

class LoadGenerator:
    def __init__(self, base_url: str = "https://terrakube-reg.microk8s.net", jwt_token: str = ""):
        self.base_url = base_url.rstrip("/")
        self.jwt_token = jwt_token
        self.ssl_context = ssl.create_default_context()
        self.ssl_context.check_hostname = False
        self.ssl_context.verify_mode = ssl.CERT_NONE

    def _execute_worker_loop(self, url: str, headers: Dict[str, str], end_time: float) -> List[Tuple[float, float, int, int, Optional[str]]]:
        """
        Worker thread loop running until end_time.
        Returns list of (start_ts, latency_ms, status_code, byte_count, error_msg).
        """
        results = []
        while time.time() < end_time:
            t_req_start = time.time()
            t0 = time.perf_counter()
            req = urllib.request.Request(url, headers=headers)
            try:
                with urllib.request.urlopen(req, context=self.ssl_context, timeout=5) as resp:
                    data = resp.read()
                    elapsed_ms = (time.perf_counter() - t0) * 1000.0
                    results.append((t_req_start, elapsed_ms, resp.status, len(data), None))
            except urllib.error.HTTPError as e:
                elapsed_ms = (time.perf_counter() - t0) * 1000.0
                results.append((t_req_start, elapsed_ms, e.code, 0, None))
            except Exception as ex:
                elapsed_ms = (time.perf_counter() - t0) * 1000.0
                results.append((t_req_start, elapsed_ms, 0, 0, str(ex)))
        return results

    def run_stage(self, endpoint: EndpointConfig, concurrency: int, duration_seconds: int, warmup_exclusion_seconds: int = 3) -> StageResult:
        """
        Executes a load testing stage with `concurrency` concurrent workers for `duration_seconds`.
        Discards the first `warmup_exclusion_seconds` of requests to filter out JIT/connection warm-up spikes.
        """
        warmup_ex = min(warmup_exclusion_seconds, max(0, duration_seconds - 1)) if duration_seconds > 1 else 0
        if warmup_ex > 0:
            print(f"    -> Running {endpoint.name} ({endpoint.controller}) @ {concurrency} workers for {duration_seconds}s (ignoring first {warmup_ex}s warmup)...")
        else:
            print(f"    -> Running {endpoint.name} ({endpoint.controller}) @ {concurrency} workers for {duration_seconds}s...")

        url = f"{self.base_url}{endpoint.path}"
        headers = {
            "User-Agent": "Terrakube-Benchmark/1.0",
            "Accept": "application/json, application/octet-stream, */*",
        }
        if endpoint.authenticated and self.jwt_token:
            headers["Authorization"] = f"Bearer {self.jwt_token}"

        start_time = time.time()
        end_time = start_time + duration_seconds

        all_records = []
        with ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [
                executor.submit(self._execute_worker_loop, url, headers, end_time)
                for _ in range(concurrency)
            ]
            for f in futures:
                all_records.extend(f.result())

        total_elapsed = time.time() - start_time
        effective_start = start_time + warmup_ex
        effective_duration = max(total_elapsed - warmup_ex, 0.001)

        # Filter out records during warmup exclusion window
        measured_records = [
            (lat_ms, status, byte_cnt, err)
            for (req_start, lat_ms, status, byte_cnt, err) in all_records
            if req_start >= effective_start
        ] if warmup_ex > 0 else [
            (lat_ms, status, byte_cnt, err)
            for (_, lat_ms, status, byte_cnt, err) in all_records
        ]

        total_requests = len(measured_records)

        latencies = []
        status_codes = {}
        successful_requests = 0
        failed_requests = 0
        total_bytes = 0
        error_samples = []

        for lat_ms, status, byte_cnt, err in measured_records:
            latencies.append(lat_ms)
            status_codes[status] = status_codes.get(status, 0) + 1
            total_bytes += byte_cnt

            if status == endpoint.expected_status or (endpoint.expected_status == 200 and status in (200, 204)):
                successful_requests += 1
            else:
                failed_requests += 1
                if err and len(error_samples) < 5:
                    error_samples.append(err)

        latencies.sort()
        rps = round(total_requests / effective_duration, 2) if effective_duration > 0 else 0.0
        p50 = round(_percentile(latencies, 50), 2)
        p90 = round(_percentile(latencies, 90), 2)
        p95 = round(_percentile(latencies, 95), 2)
        p99 = round(_percentile(latencies, 99), 2)
        avg = round(statistics.mean(latencies), 2) if latencies else 0.0
        min_lat = round(min(latencies), 2) if latencies else 0.0
        max_lat = round(max(latencies), 2) if latencies else 0.0
        stddev = round(statistics.stdev(latencies), 2) if len(latencies) > 1 else 0.0
        throughput_bytes_sec = round(total_bytes / effective_duration, 2) if effective_duration > 0 else 0.0
        error_rate_pct = round((failed_requests / total_requests * 100), 2) if total_requests > 0 else 0.0

        mb_sec = throughput_bytes_sec / (1024 * 1024)
        print(f"       [Result] RPS: {rps:.1f} | Throughput: {mb_sec:.2f} MB/s | p50: {p50:.1f}ms | p95: {p95:.1f}ms | p99: {p99:.1f}ms | Errors: {failed_requests}/{total_requests} ({error_rate_pct}%)")

        return StageResult(
            endpoint_name=endpoint.name,
            controller=endpoint.controller,
            concurrency=concurrency,
            duration_seconds=round(effective_duration, 2),
            total_requests=total_requests,
            successful_requests=successful_requests,
            failed_requests=failed_requests,
            status_codes=status_codes,
            rps=rps,
            latency_p50_ms=p50,
            latency_p90_ms=p90,
            latency_p95_ms=p95,
            latency_p99_ms=p99,
            latency_avg_ms=avg,
            latency_min_ms=min_lat,
            latency_max_ms=max_lat,
            latency_stddev_ms=stddev,
            total_bytes_transferred=total_bytes,
            throughput_bytes_sec=throughput_bytes_sec,
            error_rate_pct=error_rate_pct,
            error_samples=error_samples
        )
