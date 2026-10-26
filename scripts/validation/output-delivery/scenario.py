#!/usr/bin/env python3
"""Scenarios for the delivery of step output. Python standard library only.

kill-mid-step: a step prints a line a second, the executor is killed with SIGKILL after the first part of
the output reached storage, and the Console must still show what was printed, marked incomplete, once the
reconciliation sweep has failed the job.
"""
import base64, hashlib, hmac, json, os, signal, sys, time, urllib.error, urllib.request, uuid

API = os.environ["API_URL"]
ORG = "d9b58bd3-f3fc-4056-a026-1163297e80a8"  # the "simple" organization, seeded by the demo profile
STORAGE = os.environ["STORAGE_DIR"]


def token():
    key = base64.urlsafe_b64decode(os.environ["PAT_SECRET"] + "=" * (-len(os.environ["PAT_SECRET"]) % 4))
    enc = lambda d: base64.urlsafe_b64encode(d).rstrip(b"=")
    now = int(time.time())
    head = enc(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    body = enc(json.dumps({
        "iss": "Terrakube", "sub": "Validation (Token)", "aud": "Terrakube", "jti": str(uuid.uuid4()),
        "email": "validation@example.com", "email_verified": True, "name": "Validation (Token)",
        "groups": ["TERRAKUBE_DEVELOPERS"], "iat": now, "exp": now + 3600}).encode())
    return (head + b"." + body + b"." + enc(hmac.new(key, head + b"." + body, hashlib.sha256).digest())).decode()


TOKEN = token()


def call(method, path, body=None, raw=False):
    request = urllib.request.Request(
        API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/vnd.api+json",
                 "Accept": "*/*" if raw else "application/vnd.api+json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
            if raw:
                return response.status, dict(response.headers), data
            return json.loads(data) if data else {}
    except urllib.error.HTTPError as error:
        if raw:
            return error.code, dict(error.headers), error.read()
        raise SystemExit(f"{method} {path} -> {error.code} {error.read().decode()[:500]}")


def until(what, timeout, probe, interval=2):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(interval)
    raise SystemExit(f"timed out after {timeout}s waiting for {what}")


def check(condition, message):
    print(("ok    " if condition else "FAIL  ") + message)
    if not condition:
        raise SystemExit(1)


def start_slow_job():
    flow = ("flow:\n  - type: customScripts\n    step: 100\n    name: slow\n    commands:\n"
            "      - runtime: BASH\n        priority: 100\n        before: true\n        script: |\n"
            "          for i in $(seq 1 600); do echo \"line $i of the slow step\"; sleep 1; done\n")
    template = call("POST", f"/api/v1/organization/{ORG}/template", {"data": {"type": "template", "attributes": {
        "name": "validation-slow", "description": "validation", "version": "1.0.0",
        "tcl": base64.b64encode(flow.encode()).decode()}}})["data"]["id"]
    workspace = call("POST", f"/api/v1/organization/{ORG}/workspace", {"data": {"type": "workspace", "attributes": {
        "name": "validation-" + str(int(time.time())), "source": os.environ["REPO_URL"], "branch": "main",
        "terraformVersion": "1.5.7", "iacType": "terraform"}}})["data"]["id"]
    job = call("POST", f"/api/v1/organization/{ORG}/job", {"data": {"type": "job", "attributes": {
        "templateReference": template}, "relationships": {"workspace": {"data": {
            "type": "workspace", "id": workspace}}}}})["data"]["id"]
    return job


def job_and_step(job):
    data = call("GET", f"/api/v1/organization/{ORG}/job/{job}?include=step")
    steps = [s for s in data.get("included", []) if s["type"] == "step"]
    return data["data"]["attributes"]["status"], (steps[0] if steps else None)


def parts_written(job):
    folder = os.path.join(STORAGE, ORG, job)
    if not os.path.isdir(folder):
        return []
    return sorted(os.path.join(root, f) for root, _, files in os.walk(folder) for f in files if f.endswith(".part"))


def kill_mid_step():
    job = start_slow_job()
    print("job", job)
    until("the step to run", 120, lambda: job_and_step(job)[1] and job_and_step(job)[0] == "running")
    until("a part of the output in storage", 90, lambda: parts_written(job))
    status, step = job_and_step(job)
    check(status == "running", "the job is running when the executor is killed")
    os.kill(int(os.environ["EXECUTOR_PID"]), signal.SIGKILL)
    final = until("the job to fail", 180, lambda: job_and_step(job)[0] in ("failed", "completed", "cancelled") and job_and_step(job)[0])
    check(final == "failed", f"the job fails through the reconciliation sweep (was {final})")
    step = job_and_step(job)[1]
    code, headers, body = call("GET", f"/tfoutput/v1/organization/{ORG}/job/{job}/step/{step['id']}", raw=True)
    text = body.decode("utf-8", "replace")
    check(code == 200, f"the Console answers 200 (was {code})")
    check(text.startswith("line 1 of the slow step"), "the Console starts with the first line that was printed")
    check(headers.get("X-Terrakube-Output-Incomplete") == "true", "the response is marked X-Terrakube-Output-Incomplete")
    check("no-store" in headers.get("Cache-Control", ""), "the incomplete response is not cacheable")
    print("delivery state of the step:", step["attributes"].get("deliveryState"))


SCENARIOS = {"kill-mid-step": kill_mid_step}

if __name__ == "__main__":
    SCENARIOS[sys.argv[1]]()
