#!/usr/bin/env python3
"""
Configuration for Terrakube Registry Performance Benchmark Suite.
Defines endpoints, concurrency stages (10, 25, 50 workers), image versions, and cluster parameters.
"""

import os
from dataclasses import dataclass, field
from typing import List, Dict

@dataclass
class EndpointConfig:
    name: str
    controller: str
    method: str
    path: str
    authenticated: bool
    expected_status: int
    description: str

@dataclass
class BenchmarkConfig:
    # Cluster and SSH connection
    ssh_host: str = "user@192.168.1.150"
    kubectl_cmd: str = "microk8s kubectl"
    registry_base_url: str = "https://terrakube-reg.microk8s.net"
    prometheus_url: str = "http://kube-prometheus-stack-prometheus.monitoring.svc.cluster.local:9090"
    otel_metrics_port: int = 9464
    k8s_namespace: str = "terrakube"
    deployment_name: str = "terrakube-registry"

    # Container Images to Compare
    baseline_image: str = "azbuilder/open-registry:2.33.0-beta.10"
    candidate_image: str = "azbuilder/open-registry:2.33.0-beta.10-alpaquita"

    # Concurrency and Load Stages: 5, 10, 25 workers
    warmup_duration_seconds: int = 15
    stage_duration_seconds: int = 20
    concurrency_stages: List[int] = field(default_factory=lambda: [5, 10, 25])



    # Output paths
    output_dir: str = field(default_factory=lambda: os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "benchmark-results")))

    # 7 Registry Endpoints across the 4 Controllers
    endpoints: List[EndpointConfig] = field(default_factory=lambda: [
        EndpointConfig(
            name="well_known",
            controller="WellKnownWebServiceImpl",
            method="GET",
            path="/.well-known/terraform.json",
            authenticated=False,
            expected_status=200,
            description="Service discovery (.well-known/terraform.json)"
        ),
        EndpointConfig(
            name="module_versions",
            controller="ModuleWebServiceImpl",
            method="GET",
            path="/terraform/modules/v1/aws/vpc/aws/versions",
            authenticated=True,
            expected_status=200,
            description="List module versions (DB query + JSON response)"
        ),
        EndpointConfig(
            name="module_download_redirect",
            controller="ModuleWebServiceImpl",
            method="GET",
            path="/terraform/modules/v1/aws/vpc/aws/5.7.2/download",
            authenticated=True,
            expected_status=204,
            description="Module download redirect (DB write + X-Terraform-Get header)"
        ),
        EndpointConfig(
            name="module_zip_stream",
            controller="ModuleWebServiceImpl",
            method="GET",
            path="/terraform/modules/v1/download/aws/vpc/aws/5.7.2/module.zip",
            authenticated=False,
            expected_status=200,
            description="Download module binary zip (SeaweedFS S3 storage stream)"
        ),
        EndpointConfig(
            name="provider_versions",
            controller="ProviderWebServiceImpl",
            method="GET",
            path="/terraform/providers/v1/simple/benchmark/versions",
            authenticated=True,
            expected_status=200,
            description="List provider versions & platforms (GraphQL/DB query)"
        ),
        EndpointConfig(
            name="provider_download_meta",
            controller="ProviderWebServiceImpl",
            method="GET",
            path="/terraform/providers/v1/simple/benchmark/1.0.0/download/linux/amd64",
            authenticated=True,
            expected_status=200,
            description="Provider package download metadata (SHA256 & GPG keys)"
        ),
        EndpointConfig(
            name="readme_markdown",
            controller="ReadMeWebServiceImpl",
            method="GET",
            path="/terraform/readme/v1/aws/vpc/aws/5.7.2/download",
            authenticated=True,
            expected_status=200,
            description="Module README extraction (In-memory zip uncompress + MD read)"
        ),
    ])

DEFAULT_CONFIG = BenchmarkConfig()
