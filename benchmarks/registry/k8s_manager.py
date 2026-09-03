#!/usr/bin/env python3
"""
Kubernetes and Container Lifecycle Manager for Terrakube Benchmark Suite.
Handles remote image switching via MicroK8s (or plain kubectl), measuring rollout
cold-start duration, and capturing pod metadata.
"""

import subprocess
import time
from typing import Tuple, Dict, Any, Optional


class K8sManager:
    def __init__(
        self,
        ssh_host: str = "user@192.168.1.150",
        namespace: str = "terrakube",
        deployment: str = "terrakube-registry",
        kubectl_cmd: str = "microk8s kubectl",
        registry_base_url: str = "https://terrakube-reg.microk8s.net",
    ):
        self.ssh_host = ssh_host
        self.namespace = namespace
        self.deployment = deployment
        self.kubectl_cmd = kubectl_cmd
        self.registry_base_url = registry_base_url

    def _run_cmd(self, cmd_str: str, timeout: int = 60) -> Tuple[int, str, str]:
        full_cmd = f'ssh -o ConnectTimeout=10 {self.ssh_host} "{cmd_str}"'
        res = subprocess.run(full_cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return res.returncode, res.stdout.strip(), res.stderr.strip()

    def get_current_image(self) -> str:
        cmd = (
            f"{self.kubectl_cmd} get deployment {self.deployment} -n {self.namespace}"
            f" -o jsonpath='{{.spec.template.spec.containers[0].image}}'"
        )
        code, out, _ = self._run_cmd(cmd)
        if code == 0:
            return out
        return ""

    def get_pod_name(self) -> str:
        cmd = (
            f"{self.kubectl_cmd} get pods -n {self.namespace}"
            f" -l app.kubernetes.io/name=terrakube"
            f" -l app.kubernetes.io/component={self.deployment}"
            f" -o jsonpath='{{.items[0].metadata.name}}'"
        )
        code, out, _ = self._run_cmd(cmd)
        if code == 0:
            return out
        return ""

    def deploy_image(self, image_tag: str, timeout_seconds: int = 120) -> Tuple[bool, float, str]:
        """
        Deploys an image tag to the registry deployment and measures time to readiness (cold start).
        Returns: (success, startup_seconds, pod_name)
        """
        current = self.get_current_image()
        print(f"[*] Deploying image: {image_tag} (Current: {current})")

        start_time = time.time()
        set_cmd = (
            f"{self.kubectl_cmd} set image deployment/{self.deployment}"
            f" {self.deployment}={image_tag} -n {self.namespace}"
        )
        code, _, err = self._run_cmd(set_cmd)
        if code != 0:
            return False, 0.0, f"Failed to set image: {err}"

        rollout_cmd = (
            f"{self.kubectl_cmd} rollout status deployment/{self.deployment}"
            f" -n {self.namespace} --timeout={timeout_seconds}s"
        )
        code, out, err = self._run_cmd(rollout_cmd, timeout=timeout_seconds + 10)
        elapsed = time.time() - start_time

        if code == 0 and "successfully rolled out" in out:
            pod_name = self.get_pod_name()
            print(f"[✓] Rollout complete in {elapsed:.2f}s! Active pod: {pod_name}")
            return True, elapsed, pod_name
        else:
            return False, elapsed, f"Rollout failed or timed out: {err or out}"

    def get_image_size_mb(self, image_tag: str) -> float:
        """
        Calculates image size in MB on the remote host via docker inspect.
        """
        cmd = f"docker inspect {image_tag} --format='{{{{.Size}}}}' 2>/dev/null || echo 0"
        code, out, _ = self._run_cmd(cmd)
        if code == 0 and out.isdigit():
            return round(int(out) / (1024 * 1024), 2)
        return 0.0

    def warm_up_pod(self, duration_seconds: int = 15):
        """
        Executes warm-up requests to trigger JIT compilation and class loading.
        Uses the registry_base_url configured at construction time.
        """
        import urllib.request
        import ssl

        target_url = f"{self.registry_base_url}/.well-known/terraform.json"
        print(f"[*] Warming up registry JVM for {duration_seconds}s...")
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        end_time = time.time() + duration_seconds
        req_count = 0
        while time.time() < end_time:
            try:
                req = urllib.request.Request(target_url, headers={"User-Agent": "Terrakube-Benchmark-Warmup"})
                with urllib.request.urlopen(req, context=ctx, timeout=2) as resp:
                    resp.read()
                    req_count += 1
            except Exception:
                pass
            time.sleep(0.05)
        print(f"[✓] Warmup complete ({req_count} requests executed).")
