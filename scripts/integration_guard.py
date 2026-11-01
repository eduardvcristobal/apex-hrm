#!/usr/bin/env python3
"""Fail-closed, dependency-free static integration guard for Apex HRM.

Run: python3 scripts/integration_guard.py [--root PATH] [--json]
This complements (and does not replace) Flyway, Spring MVC, and live authorization tests.
"""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

VERSION = re.compile(r"^V(\d+(?:\.\d+)*)__([A-Za-z0-9_-]+)\.sql$")
MAPPING = re.compile(r"@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping|RequestMapping)\s*\((.*?)\)", re.S)
LITERAL = re.compile(r'"([^"]+)"')
METHODS = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT", "PatchMapping": "PATCH", "DeleteMapping": "DELETE"}

def migration_issues(root):
    directory = root / "backend/src/main/resources/db/migration"
    issues = []
    if not directory.is_dir():
        return [f"Missing Flyway migration directory: {directory}"], {}
    versions = defaultdict(list)
    for path in sorted(directory.iterdir()):
        if path.is_file() and path.suffix == ".sql":
            match = VERSION.fullmatch(path.name)
            if not match:
                issues.append(f"Invalid Flyway versioned filename: {path.name}")
            else:
                versions[match.group(1)].append(path.name)
    for version, names in sorted(versions.items()):
        if len(names) > 1:
            issues.append(f"Flyway version V{version} duplicated: {', '.join(names)}")
    # No demand for consecutive numbers: Flyway permits gaps.
    return issues, {k: v for k, v in sorted(versions.items())}

def java_routes(root):
    base = root / "backend/src/main/java"
    routes = defaultdict(list)
    if not base.is_dir():
        return {}, [f"Missing backend Java source directory: {base}"]
    for path in sorted(base.rglob("*.java")):
        source = path.read_text(encoding="utf-8")
        if "@RestController" not in source:
            continue
        header = source.split("public class ", 1)[0].split("public record ", 1)[0]
        class_prefix = ""
        for match in MAPPING.finditer(header):
            if match.group(1) == "RequestMapping":
                strings = LITERAL.findall(match.group(2))
                if strings:
                    class_prefix = strings[0]
        for match in MAPPING.finditer(source[len(header):], len(header)):
            kind, args = match.groups()
            if kind == "RequestMapping":
                continue  # method-specific RequestMapping needs semantic parsing
            paths = LITERAL.findall(args)
            for suffix in paths or [""]:
                full = "/" + "/".join(part.strip("/") for part in (class_prefix, suffix) if part.strip("/"))
                routes[(METHODS[kind], full)].append(str(path.relative_to(root)))
    return routes, []

def route_issues(root):
    routes, errors = java_routes(root)
    for (method, path), sources in sorted(routes.items()):
        if len(sources) > 1:
            errors.append(f"Duplicate controller mapping {method} {path}: {', '.join(sources)}")
    return errors, len(routes)

def security_issues(root):
    path = root / "backend/src/main/java/com/apex/hrm/platform/security/SecurityConfig.java"
    if not path.is_file():
        return [f"Missing security matcher configuration: {path}"]
    content = path.read_text(encoding="utf-8")
    findings = []
    for group in ("/api/v1/learning/**", "/api/v1/performance/**", "/api/payroll/**", "/api/v1/personnel/**"):
        if group not in content:
            findings.append(f"SecurityConfig missing expected module matcher: {group}")
    if '.anyRequest().denyAll()' not in content:
        findings.append("SecurityConfig missing final deny-all policy")
    if '"/api/v1/**").denyAll()' not in content:
        findings.append("SecurityConfig missing unmatched v1 deny-all policy")
    # A matcher presence check is not a proof of authorization: verify with HTTP tests.
    return findings

def audit(root):
    migrations, versions = migration_issues(root)
    routes, route_count = route_issues(root)
    security = security_issues(root)
    return {"ok": not (migrations or routes or security), "migration_versions": len(versions),
            "controller_routes": route_count, "issues": {"flyway": migrations, "api": routes, "security": security},
            "limitations": ["Static Java annotation scan: does not resolve composed annotations, RequestMapping(method=...), or runtime handler registration.",
                            "Permission checks only enforce baseline matcher presence and deny-all; authenticated multi-role HTTP tests remain mandatory.",
                            "Run Flyway migrations on fresh and existing PostgreSQL databases to certify SQL and checksums."]}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    result = audit(args.root.resolve())
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"Flyway versions: {result['migration_versions']}; discovered controller routes: {result['controller_routes']}")
        for category, issues in result["issues"].items():
            for issue in issues:
                print(f"FAIL [{category}] {issue}")
        print("Static integration guard: PASS" if result["ok"] else "Static integration guard: FAIL")
    return 0 if result["ok"] else 1

if __name__ == "__main__":
    raise SystemExit(main())
