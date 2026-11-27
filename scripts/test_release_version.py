import unittest
from release_version import resolve


class ReleaseRulesTest(unittest.TestCase):
    def check(self, subject, ref_type="branch", ref_name="main"):
        return resolve(subject, ref_type, ref_name, "1.2.3")

    def test_prefixes(self):
        self.assertEqual(self.check("release-v1.2.3: Controls"), {"tag": "v1.2.3", "prerelease": "false"})
        self.assertEqual(self.check("beta-v1.2.3 Test controls"), {"tag": "beta-v1.2.3", "prerelease": "true"})
        self.assertEqual(self.check("release-v1.2.3")["tag"], "v1.2.3")

    def test_regular_commits_and_body_do_not_trigger(self):
        for subject in ("Fix controls", "Fix controls\n\nrelease-v1.2.3", "docs: beta-v1.2.3"):
            self.assertIsNone(self.check(subject))

    def test_invalid_or_mismatched_versions(self):
        for subject in ("release-v1.2", "beta-v1.2.3.4", "release-v01.2.3", "release-v1.2.3evil", "release-v1.2.4"):
            with self.assertRaises(ValueError, msg=subject):
                self.check(subject)

    def test_tags(self):
        for tag, beta in (("v1.2.3", "false"), ("beta-v1.2.3", "true")):
            self.assertEqual(self.check("Update", "tag", tag), {"tag": tag, "prerelease": beta})
        with self.assertRaises(ValueError):
            self.check("", "tag", "v1.2.4")
