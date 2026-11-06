#!/usr/bin/env python3
"""Check docs/single-domain/route-table.yaml against the shipped single-domain routing.

Fails when:
  * two route-table entries for different components share a prefix;
  * the Compose single-domain Traefik rules do not route every entry to its component, or the
    registry router does not outrank the API router (its prefix sits under /api/);
  * a UI route's first path segment is owned by a backend on the shared host.

Standard library only; the route table uses a small YAML subset (see the file itself).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TABLE = ROOT / "docs/single-domain/route-table.yaml"
COMPOSE = ROOT / "docker-compose/docker-compose.single-domain.yml"
UI_ROUTES = ROOT / "ui/src/domain/Home/App.tsx"
SERVICE_COMPONENT = {
    "terrakube-api": "api",
    "terrakube-registry": "registry",
    "terrakube-dex": "dex",
    "terrakube-ui": "ui",
}


def load_routes():
    routes, current = [], None
    for line in TABLE.read_text().splitlines():
        line = line.split("#", 1)[0].rstrip()
        item = re.match(r"^  - prefix:\s*(\S+)$", line)
        if item:
            current = {"prefix": item.group(1)}
            routes.append(current)
            continue
        field = re.match(r"^    (match|component|status):\s*(\S+)$", line)
        if field and current is not None:
            current[field.group(1)] = field.group(2)
    return routes


def load_compose_rules():
    """component -> (set of ('Path'|'PathPrefix', value), priority) for the HTTPS routers."""
    rules, service = {}, None
    text = COMPOSE.read_text()
    for raw in text.splitlines():
        svc = re.match(r"^  (terrakube-[a-z]+):\s*$", raw)
        if svc:
            service = svc.group(1)
            continue
        if service not in SERVICE_COMPONENT:
            continue
        component = SERVICE_COMPONENT[service]
        entry = rules.setdefault(component, {"matchers": set(), "priority": None})
        prio = re.search(r"routers\.[a-z-]+-https\.priority:\s*(\d+)", raw)
        if prio:
            entry["priority"] = int(prio.group(1))
    # Rules may be folded over several lines; scan per service block.
    for block in re.split(r"(?m)^  (?=terrakube-[a-z]+:)", text):
        name = block.split(":", 1)[0].strip()
        if name not in SERVICE_COMPONENT:
            continue
        https_rule = re.search(r"routers\.[a-z-]+-https\.rule:\s*(>-\s*\n\s*)?(.+)", block)
        if https_rule:
            rules[SERVICE_COMPONENT[name]]["matchers"] = set(
                re.findall(r"(PathPrefix|Path)\(`([^`]+)`\)", https_rule.group(2)))
    return rules


def ui_first_segments():
    segments = set()
    for path in re.findall(r'path:\s*"(/[^"]*)"', UI_ROUTES.read_text()):
        first = path.strip("/").split("/", 1)[0]
        if first and not first.startswith(":") and first != "*":
            segments.add(first)
    return segments


def main():
    errors = []
    routes = load_routes()

    owners = {}
    for route in routes:
        prev = owners.setdefault(route["prefix"], route["component"])
        if prev != route["component"]:
            errors.append(f"prefix {route['prefix']} is owned by both {prev} and {route['component']}")

    rules = load_compose_rules()
    for route in routes:
        component, prefix = route["component"], route["prefix"]
        if component == "ui":
            continue
        kind = "Path" if route.get("match") == "exact" else "PathPrefix"
        if (kind, prefix) not in rules.get(component, {}).get("matchers", set()):
            errors.append(f"{COMPOSE.name}: no {kind}(`{prefix}`) rule routes to {component}")

    prio = {c: r.get("priority") for c, r in rules.items()}
    if not (prio.get("registry") or 0) > (prio.get("api") or 0):
        errors.append(f"{COMPOSE.name}: registry router priority must be higher than the API router")
    if prio.get("ui") is None or any((p or 0) <= prio["ui"] for c, p in prio.items() if c != "ui"):
        errors.append(f"{COMPOSE.name}: the UI router must have the lowest priority")

    backend_segments = {r["prefix"].strip("/").split("/", 1)[0] for r in routes if r["component"] != "ui"}
    for segment in sorted(ui_first_segments() & backend_segments):
        errors.append(f"UI route /{segment} is shadowed by a backend route on the shared host")

    for error in errors:
        print(f"route-table: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"route-table: OK ({len(routes)} routes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
