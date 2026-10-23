import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_page


class ReleasePageTest(unittest.TestCase):
    def setUp(self):
        self.release = {"id": 123, "tag_name": "v1.0.18", "draft": False, "prerelease": False,
            "html_url": "https://github.com/Tsohnle95/Luno-Mobile/releases/tag/v1.0.18",
            "body": "**Full Changelog**: original compare link",
            "assets": [{"id": 456, "name": "Luno-Mobile-v1.0.18.apk", "size": 56 * 1024 * 1024,
                "digest": "sha256:" + "a" * 64,
                "browser_download_url": "https://github.com/Tsohnle95/Luno-Mobile/releases/download/v1.0.18/Luno-Mobile-v1.0.18.apk"}]}

    def test_direct_download_requirements_checksum_and_original_history(self):
        body = release_page.render(self.release, "Tsohnle95/Luno-Mobile", 29)
        self.assertIn('href="' + self.release["assets"][0]["browser_download_url"] + '"', body)
        self.assertIn("Android 10 or later", body)
        self.assertIn("56.0 MB", body)
        self.assertIn("a" * 64, body)
        self.assertIn(self.release["body"], body)
        self.assertIn("Recover missing song titles", body)

    def test_refresh_does_not_duplicate_the_page_or_its_history(self):
        first = release_page.render(self.release, "Tsohnle95/Luno-Mobile", 29)
        self.release["body"] = first
        self.assertEqual(first, release_page.render(self.release, "Tsohnle95/Luno-Mobile", 29))

    def test_wrong_repository_asset_and_invalid_tag_are_rejected(self):
        self.release["assets"][0]["browser_download_url"] = "https://example.com/app.apk"
        with self.assertRaises(ValueError):
            release_page.render(self.release, "Tsohnle95/Luno-Mobile", 29)
        self.release["tag_name"] = "../other"
        with self.assertRaises(ValueError):
            release_page.render(self.release, "Tsohnle95/Luno-Mobile", 29)

    def test_refresh_only_patches_name_and_body(self):
        after = copy.deepcopy(self.release)
        with patch.object(release_page, "api", side_effect=[self.release, after]) as api, \
                patch.object(release_page, "minimum_sdk", return_value=29):
            release_page.update_latest("Tsohnle95/Luno-Mobile")
        args = api.call_args_list[1].args
        self.assertEqual(args[:2], ("Tsohnle95/Luno-Mobile", "releases/123"))
        self.assertEqual(set(args[2]), {"name", "body"})

    def test_unpublished_release_is_not_modified(self):
        self.release["draft"] = True
        with patch.object(release_page, "api", return_value=self.release) as api:
            with self.assertRaises(ValueError):
                release_page.update_latest("Tsohnle95/Luno-Mobile")
        self.assertEqual(api.call_count, 1)
