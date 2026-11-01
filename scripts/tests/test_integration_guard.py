"""Regression tests for M7.1 static guard. Run: python3 -m unittest discover -s scripts/tests -v"""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from integration_guard import audit, migration_issues, route_issues, security_issues

class IntegrationGuardTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.migrations = self.root / "backend/src/main/resources/db/migration"
        self.migrations.mkdir(parents=True)
        self.java = self.root / "backend/src/main/java/com/example"
        self.java.mkdir(parents=True)
        self.security = self.root / "backend/src/main/java/com/apex/hrm/platform/security/SecurityConfig.java"
        self.security.parent.mkdir(parents=True, exist_ok=True)

    def test_duplicate_migration_versions_fail(self):
        (self.migrations / "V59__one.sql").write_text("-- first")
        (self.migrations / "V59__two.sql").write_text("-- second")
        errors, _ = migration_issues(self.root)
        self.assertTrue(any("V59 duplicated" in error for error in errors))

    def test_valid_migrations_allow_gaps_and_repeatables_are_not_in_scope(self):
        (self.migrations / "V1__start.sql").write_text("-- first")
        (self.migrations / "V58__learning.sql").write_text("-- second")
        errors, versions = migration_issues(self.root)
        self.assertEqual([], errors)
        self.assertEqual(2, len(versions))

    def test_malformed_versioned_sql_fails(self):
        (self.migrations / "V60_wrong_name.sql").write_text("-- incorrect")
        errors, _ = migration_issues(self.root)
        self.assertTrue(any("Invalid Flyway" in error for error in errors))

    def test_duplicate_rest_controller_mapping_fails(self):
        template = '@RestController\n@RequestMapping("/api/v1/demo")\npublic class Sample {\n@GetMapping("/item")\npublic String get(){return ""; }\n}'
        (self.java / "A.java").write_text(template.replace("Sample", "A"))
        (self.java / "B.java").write_text(template.replace("Sample", "B"))
        errors, total = route_issues(self.root)
        self.assertTrue(any("Duplicate controller mapping GET /api/v1/demo/item" in error for error in errors))
        self.assertEqual(1, total)

    def test_distinct_verbs_on_same_path_are_allowed(self):
        code = '@RestController\n@RequestMapping("/api")\npublic class A {\n@GetMapping("/item") void get(){}\n@PostMapping("/item") void post(){}\n}'
        (self.java / "A.java").write_text(code)
        errors, total = route_issues(self.root)
        self.assertEqual([], errors)
        self.assertEqual(2, total)

    def test_missing_security_matchers_fail(self):
        self.security.write_text('class SecurityConfig {}')
        errors = security_issues(self.root)
        self.assertGreaterEqual(len(errors), 5)

    def test_audit_fails_closed_without_java_or_security(self):
        self.security.unlink()
        self.assertFalse(audit(self.root)["ok"])

if __name__ == "__main__":
    unittest.main()
