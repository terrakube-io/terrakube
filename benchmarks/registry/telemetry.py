#!/usr/bin/env python3
"""
Telemetry and Metrics Extractor for Terrakube Benchmark Suite.
Queries Prometheus and OpenTelemetry JavaAgent metrics via SSH or direct API.
Supports periodic time-series snapshot collection for rich chart rendering.
"""

import json
import subprocess
import threading
import time
import urllib.parse
from typing import Dict, Any, Optional, List


class TelemetryExtractor:
    def __init__(
        self,
        ssh_host: str = "user@192.168.1.150",
        prom_service: str = "http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090",
        kubectl_cmd: str = "microk8s kubectl",
    ):
        self.ssh_host = ssh_host
        self.prom_service = prom_service
        self.kubectl_cmd = kubectl_cmd

    def query_prometheus_instant(self, query: str) -> Optional[List[Dict[str, Any]]]:
        """
        Executes an instant PromQL query via curl in the releases mirror pod or cluster.
        """
        encoded_query = urllib.parse.quote(query)
        cmd = (
            f"{self.kubectl_cmd} exec -n terrakube deployment/terraform-releases-mirror"
            f" -c nginx -- curl -s '{self.prom_service}/api/v1/query?query={encoded_query}'"
        )
        full_cmd = f'ssh -o ConnectTimeout=10 {self.ssh_host} "{cmd}"'

        try:
            res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=15)
            if res.returncode == 0:
                data = json.loads(res.stdout)
                if data.get("status") == "success":
                    return data.get("data", {}).get("result", [])
        except Exception as e:
            print(f"[Warning] Prometheus query failed for '{query}': {e}")
        return None

    def capture_jvm_metrics(self) -> Dict[str, Any]:
        """
        Captures a comprehensive single-point snapshot of JVM memory, GC, CPU, and thread
        metrics for terrakube-registry.
        """
        metrics: Dict[str, Any] = {
            "heap_used_mb": 0.0,
            "heap_committed_mb": 0.0,
            "non_heap_used_mb": 0.0,
            "gc_count_total": 0,
            "gc_duration_seconds_total": 0.0,
            "gc_avg_pause_ms": 0.0,
            "thread_count": 0,
            "cpu_recent_utilization_pct": 0.0,
            "container_memory_rss_mb": 0.0,
            "container_cpu_millicores": 0.0,
        }

        # 1. Heap Used
        res = self.query_prometheus_instant('sum(jvm_memory_used_bytes{jvm_memory_type="heap"})')
        if res and len(res) > 0:
            val = float(res[0]["value"][1])
            metrics["heap_used_mb"] = round(val / (1024 * 1024), 2)

        # 2. Heap Committed
        res = self.query_prometheus_instant('sum(jvm_memory_committed_bytes{jvm_memory_type="heap"})')
        if res and len(res) > 0:
            val = float(res[0]["value"][1])
            metrics["heap_committed_mb"] = round(val / (1024 * 1024), 2)

        # 3. Non-Heap Used (Metaspace + Code Cache)
        res = self.query_prometheus_instant('sum(jvm_memory_used_bytes{jvm_memory_type="non_heap"})')
        if res and len(res) > 0:
            val = float(res[0]["value"][1])
            metrics["non_heap_used_mb"] = round(val / (1024 * 1024), 2)

        # 4. GC Duration & Counts
        res_sum = self.query_prometheus_instant("sum(jvm_gc_duration_seconds_sum)")
        res_cnt = self.query_prometheus_instant("sum(jvm_gc_duration_seconds_count)")
        if res_sum and len(res_sum) > 0 and res_cnt and len(res_cnt) > 0:
            sum_sec = float(res_sum[0]["value"][1])
            cnt = int(float(res_cnt[0]["value"][1]))
            metrics["gc_duration_seconds_total"] = round(sum_sec, 3)
            metrics["gc_count_total"] = cnt
            if cnt > 0:
                metrics["gc_avg_pause_ms"] = round((sum_sec / cnt) * 1000, 2)

        # 5. Thread Count
        res = self.query_prometheus_instant("sum(jvm_thread_count)")
        if res and len(res) > 0:
            metrics["thread_count"] = int(float(res[0]["value"][1]))

        # 6. CPU Recent Utilization
        res = self.query_prometheus_instant("avg(jvm_cpu_recent_utilization)")
        if res and len(res) > 0:
            metrics["cpu_recent_utilization_pct"] = round(float(res[0]["value"][1]) * 100, 2)

        # 7. Container Working Set Memory from cAdvisor
        res = self.query_prometheus_instant(
            'sum(container_memory_working_set_bytes{namespace="terrakube", container="terrakube-registry"})'
        )
        if res and len(res) > 0:
            metrics["container_memory_rss_mb"] = round(float(res[0]["value"][1]) / (1024 * 1024), 2)

        # 8. Container CPU Usage (millicores) from cAdvisor
        res = self.query_prometheus_instant(
            'sum(rate(container_cpu_usage_seconds_total{namespace="terrakube", container="terrakube-registry"}[1m])) * 1000'
        )
        if res and len(res) > 0:
            metrics["container_cpu_millicores"] = round(float(res[0]["value"][1]), 2)

        return metrics

    def capture_jvm_metrics_timeseries(
        self,
        interval_seconds: int = 10,
        stop_event: Optional[threading.Event] = None,
        snapshots: Optional[List[Dict[str, Any]]] = None,
        start_time: Optional[float] = None,
    ) -> List[Dict[str, Any]]:
        """
        Continuously captures JVM metric snapshots every `interval_seconds` until
        `stop_event` is set. Each snapshot is appended to `snapshots` (in-place) and
        also returned as the final list.

        Intended to be run inside a background thread:

            stop_evt = threading.Event()
            snapshots = []
            t = threading.Thread(
                target=telemetry.capture_jvm_metrics_timeseries,
                kwargs=dict(interval_seconds=10, stop_event=stop_evt,
                            snapshots=snapshots, start_time=time.time()),
                daemon=True,
            )
            t.start()
            # ... run load stages ...
            stop_evt.set()
            t.join(timeout=15)
        """
        if snapshots is None:
            snapshots = []
        if start_time is None:
            start_time = time.time()
        if stop_event is None:
            stop_event = threading.Event()

        while not stop_event.is_set():
            snapshot = self.capture_jvm_metrics()
            snapshot["t"] = round(time.time() - start_time, 1)
            snapshots.append(snapshot)
            # Wait for the interval or until stop is requested
            stop_event.wait(timeout=interval_seconds)

        return snapshots


if __name__ == "__main__":
    t = TelemetryExtractor()
    m = t.capture_jvm_metrics()
    print("Captured Live Metrics Snapshot:")
    print(json.dumps(m, indent=2))
