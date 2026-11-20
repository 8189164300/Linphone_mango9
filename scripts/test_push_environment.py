import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "push_environment", Path(__file__).with_name("verify-push-environment.py")
)
push = importlib.util.module_from_spec(spec)
spec.loader.exec_module(push)


class PushEnvironmentTests(unittest.TestCase):
    def test_release_build_signed_for_development_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "mismatch"):
            push.verify_values({"Mango9APSEnvironment": "production"},
                               {"aps-environment": "development"})

    def test_distribution_export_of_development_configuration_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "mismatch"):
            push.verify_values({"Mango9APSEnvironment": "development"},
                               {"aps-environment": "production"})

    def test_matching_local_and_store_artifacts(self):
        for environment in ("development", "production"):
            with self.subTest(environment=environment):
                self.assertEqual(environment, push.verify_values(
                    {"Mango9APSEnvironment": environment},
                    {"aps-environment": environment}, environment))

    def test_store_check_rejects_matching_development_build(self):
        with self.assertRaisesRegex(ValueError, "Expected production"):
            push.verify_values({"Mango9APSEnvironment": "development"},
                               {"aps-environment": "development"}, "production")

    def test_missing_or_unexpanded_settings_are_rejected(self):
        for configured, signed in ((None, "development"), ("production", None),
                                   ("$(MANGO9_APS_ENVIRONMENT)", "development")):
            with self.subTest(configured=configured, signed=signed):
                with self.assertRaises(ValueError):
                    push.verify_values({"Mango9APSEnvironment": configured},
                                       {"aps-environment": signed})


if __name__ == "__main__":
    unittest.main()
