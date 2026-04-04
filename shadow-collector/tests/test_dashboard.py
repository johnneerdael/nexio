import tempfile
import unittest
from pathlib import Path

from fastapi.testclient import TestClient

from app import main


SAMPLE_ENVELOPE = {
    "sentAtMs": 1775275508928,
    "client": {
        "appVersion": "0.35",
        "buildType": "release",
        "deviceModel": "Google TV Streamer",
        "sdkInt": 34,
    },
    "payload": {
        "event_version": 1,
        "event_type": "shadow_autoplay_decision",
        "request": {
            "requestId": "bb8ddad9-e612-4a40-ad3d-424cfbd56a8f",
            "videoId": "tt6741278:4:2",
            "contentType": "series",
            "title": "Invincible",
            "season": 4,
            "episode": 2,
            "runtimeMinutes": 56,
        },
        "benchmarksUsed": [
            {"provider": "real_debrid", "measuredAtMs": 1775275400000}
        ],
        "winners": [
            {
                "streamKey": "winner-1",
                "parsed": {
                    "serviceId": "RD",
                    "filename": "Invincible.2021.S04E02.1080p.AMZN.WEB-DL.DDP5.1.ENG.ITA.H265-TheBlackKing.mkv",
                    "folderName": "Invincible.2021.Season.04",
                    "sizeBytes": 826277888,
                    "durationMs": 3360000,
                    "runtimeSource": "request_runtime",
                    "resolution": "1080p",
                    "quality": "WEB-DL",
                    "videoCodec": "H265",
                    "audioTags": ["DD+"],
                    "audioChannels": ["5.1"],
                    "visualTags": [],
                    "languages": ["ENG", "ITA"],
                    "releaseGroup": "TheBlackKing",
                    "cached": True,
                },
                "provider": "real_debrid",
                "transport": "optimized",
                "finalScore": 48,
                "contentQualityScore": 32,
                "transportFitScore": 16,
                "suitabilityRatio": 0.0,
                "requiredMbps": 1.97,
                "safeBudgetMbps": 95.4,
                "resolution": "1080p",
                "hdrTags": [],
                "audioTags": ["DD+"],
                "breakdown": {
                    "averageBitrateMbps": 1.97,
                    "releaseType": "WEB-DL",
                    "lowQuality4k": False,
                    "content": {"resolutionPoints": 20},
                    "transport": {"provider": "real_debrid", "transport": "optimized", "suitabilityRatio": 0.0},
                },
            }
        ],
        "rejected": [
            {
                "streamKey": "rejected-1",
                "parsed": {
                    "serviceId": "PM",
                    "filename": "Invincible.2021.S04E02.2160p.Remux.mkv",
                    "folderName": "Invincible.2021.Season.04",
                    "sizeBytes": 16866297139,
                    "durationMs": 3360000,
                    "resolution": "2160p",
                    "audioTags": ["TrueHD"],
                    "visualTags": ["DV"],
                },
                "provider": "premiumize",
                "reasons": ["insufficient_transport_budget"],
            }
        ],
        "selected": {
            "streamKey": "winner-1",
                "parsed": {
                    "serviceId": "RD",
                    "filename": "Invincible.2021.S04E02.1080p.AMZN.WEB-DL.DDP5.1.ENG.ITA.H265-TheBlackKing.mkv",
                    "folderName": "Invincible.2021.Season.04",
                    "sizeBytes": 826277888,
                "durationMs": 3360000,
                "runtimeSource": "request_runtime",
                "resolution": "1080p",
                "quality": "WEB-DL",
                "videoCodec": "H265",
                "audioTags": ["DD+"],
                "audioChannels": ["5.1"],
                "visualTags": [],
                "languages": ["ENG", "ITA"],
                "releaseGroup": "TheBlackKing",
                "cached": True,
            },
            "provider": "real_debrid",
            "transport": "optimized",
            "finalScore": 48,
            "contentQualityScore": 32,
            "transportFitScore": 16,
            "suitabilityRatio": 0.0,
            "requiredMbps": 1.97,
            "safeBudgetMbps": 95.4,
            "resolution": "1080p",
            "hdrTags": [],
            "audioTags": ["DD+"],
            "breakdown": {
                "averageBitrateMbps": 1.97,
                "releaseType": "WEB-DL",
                "lowQuality4k": False,
                "content": {"resolutionPoints": 20},
                "transport": {"provider": "real_debrid", "transport": "optimized", "suitabilityRatio": 0.0},
            },
        },
    },
}


class ShadowCollectorDashboardTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        main.SQLITE_PATH = str(Path(self.tempdir.name) / "shadow_autoplay.db")
        main.ADMIN_USERNAME = "admin"
        main.ADMIN_PASSWORD = "secret"
        main.WRITE_TOKEN = "write-token"
        main.READ_TOKEN = "read-token"
        main.init_db()
        self.client = TestClient(main.app)
        self.client.post("/login", data={"username": "admin", "password": "secret"})

    def tearDown(self):
        self.client.close()
        self.tempdir.cleanup()

    def ingest_sample(self):
        response = self.client.post(
            "/api/v1/shadow-autoplay-events",
            headers={"Authorization": "Bearer write-token"},
            json=SAMPLE_ENVELOPE,
        )
        self.assertEqual(response.status_code, 200)
        return response.json()["id"]

    def test_build_event_view_extracts_dashboard_fields(self):
        summary = main.summarize(SAMPLE_ENVELOPE["payload"], main.ShadowAutoplayEnvelope(**SAMPLE_ENVELOPE))
        row = {"id": 7, **summary}
        event = main.build_event_view(row)

        self.assertEqual(event["selected"]["service"], "RD")
        self.assertEqual(event["selected"]["audio"], "DD+")
        self.assertEqual(event["selected"]["resolution"], "1080p")
        self.assertEqual(event["selected"]["folder_name"], "Invincible.2021.Season.04")
        self.assertIn("service=RD", event["result_line"])
        self.assertTrue(event["bitrate_chart"]["has_data"])

    def test_dashboard_renders_search_and_structured_columns(self):
        self.ingest_sample()
        response = self.client.get("/?q=Invincible")

        self.assertEqual(response.status_code, 200)
        html = response.text
        self.assertIn("name=\"q\"", html)
        self.assertIn("<th>Service</th>", html)
        self.assertIn("<th>File</th>", html)
        self.assertIn("<th>HDR</th>", html)
        self.assertIn("Invincible.2021.S04E02.1080p.AMZN.WEB-DL", html)
        self.assertIn("winner=real_debrid optimized service=RD", html)

    def test_detail_renders_bitrate_section_and_candidate_table(self):
        event_id = self.ingest_sample()
        response = self.client.get(f"/events/{event_id}")

        self.assertEqual(response.status_code, 200)
        html = response.text
        self.assertIn("Bitrate comparison", html)
        self.assertIn("Candidate comparison", html)
        self.assertIn("Debrid", html)
        self.assertIn("Res.", html)
        self.assertNotIn("Insufficient Transport Budget", html)
        self.assertIn("DD+", html)


if __name__ == "__main__":
    unittest.main()
