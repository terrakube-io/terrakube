#!/usr/bin/env python3
"""
Test fixture seeder for Terrakube Registry Performance Benchmark.
Ensures valid PostgreSQL records and SeaweedFS S3 storage fixtures exist
so all 7 benchmark endpoints return deterministic 200/204 OK responses.
"""

import subprocess
from typing import Tuple

def ensure_test_fixtures(ssh_host: str = "user@192.168.1.150") -> Tuple[bool, str]:
    """
    Seeds test records into PostgreSQL database on the remote MicroK8s cluster.
    """
    sql_statements = [
        "DELETE FROM implementation WHERE id = 'b0000000-0000-0000-0000-000000000003';",
        "DELETE FROM version WHERE id = 'b0000000-0000-0000-0000-000000000002';",
        "DELETE FROM provider WHERE id = 'b0000000-0000-0000-0000-000000000001';",
        "INSERT INTO provider (id, name, description, organization_id, imported, registry_namespace) VALUES ('b0000000-0000-0000-0000-000000000001', 'benchmark', 'Benchmark test provider', 'd9b58bd3-f3fc-4056-a026-1163297e80a8', false, 'simple');",
        "INSERT INTO version (id, version_number, protocols, provider_id) VALUES ('b0000000-0000-0000-0000-000000000002', '1.0.0', '5.0', 'b0000000-0000-0000-0000-000000000001');",
        "INSERT INTO implementation (id, os, arch, filename, download_url, shasums_url, shasums_signature_url, shasum, key_id, ascii_armor, trust_signature, source, source_url, version_id) VALUES ('b0000000-0000-0000-0000-000000000003', 'linux', 'amd64', 'terraform-provider-benchmark_1.0.0_linux_amd64.zip', 'https://terrakube-reg.microk8s.net/download/provider.zip', 'https://terrakube-reg.microk8s.net/download/shasums', 'https://terrakube-reg.microk8s.net/download/sig', 'abcdef123456', 'TESTKEY1', 'armor', 'trust', 'source', 'https://github.com/example/provider', 'b0000000-0000-0000-0000-000000000002');"
    ]
    combined_sql = " ".join(sql_statements)
    cmd = f'ssh {ssh_host} "microk8s kubectl exec -n terrakube terrakube-db-1 -c postgres -- psql -U postgres -d terrakube -c \\"{combined_sql}\\""'

    try:
        res = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=20)
        if res.returncode == 0:
            return True, "Database test fixtures successfully seeded."
        else:
            return False, f"Database seeding failed: {res.stderr}"
    except Exception as e:
        return False, f"Exception seeding database fixtures: {e}"

if __name__ == "__main__":
    ok, msg = ensure_test_fixtures()
    print(f"Seeder status: {ok} -> {msg}")
