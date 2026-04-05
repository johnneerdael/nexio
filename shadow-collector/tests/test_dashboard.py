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
        "androidId": "android-id-shadow-1",
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

SAMPLE_BENCHMARK_ENVELOPE = {
    "sentAtMs": 1775276500000,
    "client": {
        "appVersion": "0.38",
        "buildType": "release",
        "deviceModel": "Google TV Streamer",
        "sdkInt": 34,
        "androidId": "android-id-benchmark-1",
    },
    "result": {
        "provider": "real_debrid",
        "measuredAtMs": 1775276400000,
        "summary": {
            "startupTimeMs": 3200,
            "sustainedThroughputMbps": 91.4,
            "transferredBytes": 734003200,
            "elapsedMs": 126000,
        },
        "terminationReason": "completed",
        "candidate": {
            "filename": "Movie.2024.2160p.REMUX.mkv",
            "sizeBytes": 73400320000,
            "host": "real-debrid.example",
        },
        "device": {
            "model": "Google TV Streamer",
            "manufacturer": "Google",
            "sdkInt": 34,
            "displayHdrTypes": ["dolby_vision", "hdr10"],
            "videoDecode": {
                "h264": {
                    "hardwareAccelerated": True,
                    "softwareOnlyAvailable": False,
                    "secureSupported": True,
                },
                "hevc": {
                    "hardwareAccelerated": True,
                    "softwareOnlyAvailable": False,
                    "secureSupported": True,
                },
            },
            "audioOutput": {
                "ac3": {"supported": True, "passthroughLikely": True},
                "eac3": {"supported": True, "passthroughLikely": True},
                "atmos": {"supported": True, "passthroughLikely": True},
                "truehd": {"supported": True, "passthroughLikely": True},
                "dts": {"supported": False, "passthroughLikely": False},
                "dtshd": {"supported": False, "passthroughLikely": False},
                "dtsx": {"supported": False, "passthroughLikely": False},
            },
            "evidence": {
                "hdr": {
                    "displayId": 0,
                    "rawSupportedHdrTypes": ["dolby_vision", "hdr10"],
                },
                "audio": {
                    "discoveryMode": "direct_playback_support",
                    "routedDeviceTypes": [],
                    "outputDevices": [],
                    "directProfiles": [],
                    "directPlaybackProbes": [
                        {
                            "bucket": "atmos",
                            "format": "e_ac3_joc",
                            "channelMask": 252,
                            "sampleRateHz": 48000,
                            "supportMode": "bitstream_supported",
                        }
                    ],
                },
                "video": {
                    "scannedDecoderCount": 2,
                    "decoders": [
                        {
                            "codecName": "c2.qti.hevc.decoder",
                            "mimeType": "video/hevc",
                            "hardwareAccelerated": True,
                            "softwareOnly": False,
                            "secureSupported": True,
                        }
                    ],
                },
            },
            "capturedAtMs": 1775276399000,
        },
        "session": {
            "benchmarkVersion": 2,
            "executionOrder": [
                {"phase": "startup", "order": ["direct", "optimized"]},
                {"phase": "sustained", "order": ["direct", "optimized"]},
            ],
            "totalElapsedMs": 126000,
        },
        "direct": {
            "startup": {"initialTtfbMs": 4200, "startupFailureRate": 0.0},
            "sustained": {
                "collectorVersion": 1,
                "averageThroughputMbps": 81.1,
                "actionable": True,
                "recoverableFailureCount": 0,
                "recoverableTimeoutCount": 0,
                "p10ThroughputMbps": 78.2,
                "p50ThroughputMbps": 81.7,
                "peakThroughputMbps": 89.0,
                "bytesTransferred": 734003200,
                "elapsedMs": 126000,
            },
            "seek": {"seekTtfbP50Ms": 950, "seekTtfbP95Ms": 1200, "seekTtfbP99Ms": 1500},
            "decision": {"safeSustainedBudgetMbps": 66.4, "actionable": True},
            "rawSamples": {"throughputWindowsMbps": [], "throughputBuckets": [], "seekSamples": []},
        },
        "optimized": {
            "startup": {"initialTtfbMs": 3100, "startupFailureRate": 0.0},
            "sustained": {
                "collectorVersion": 1,
                "averageThroughputMbps": 91.4,
                "actionable": True,
                "recoverableFailureCount": 0,
                "recoverableTimeoutCount": 0,
                "p10ThroughputMbps": 88.7,
                "p50ThroughputMbps": 92.0,
                "peakThroughputMbps": 101.2,
                "bytesTransferred": 734003200,
                "elapsedMs": 126000,
            },
            "seek": {"seekTtfbP50Ms": 640, "seekTtfbP95Ms": 870, "seekTtfbP99Ms": 1100},
            "decision": {"safeSustainedBudgetMbps": 75.1, "actionable": True},
            "configSnapshot": {
                "useParallelConnections": True,
                "parallelConnectionCount": 3,
                "parallelChunkSizeMb": 16,
            },
            "rawSamples": {"throughputWindowsMbps": [], "throughputBuckets": [], "seekSamples": []},
        },
        "comparison": {
            "sustainedWinner": "optimized",
            "seekWinner": "optimized",
            "stabilityWinner": "optimized",
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

    def ingest_benchmark_sample(self):
        response = self.client.post(
            "/api/v1/debrid-benchmark-results",
            headers={"Authorization": "Bearer write-token"},
            json=SAMPLE_BENCHMARK_ENVELOPE,
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
        self.assertEqual(event["client_cards"][4]["label"], "Android ID")
        self.assertEqual(event["client_cards"][4]["value"], "android-id-shadow-1")
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

    def test_completed_benchmark_result_can_be_ingested_and_exported(self):
        benchmark_id = self.ingest_benchmark_sample()

        response = self.client.get(
            "/api/v1/debrid-benchmark-results",
            headers={"Authorization": "Bearer read-token"},
        )

        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(1, payload["count"])
        self.assertEqual(benchmark_id, payload["items"][0]["id"])
        self.assertEqual("real_debrid", payload["items"][0]["provider"])
        self.assertEqual("android-id-benchmark-1", payload["items"][0]["client"]["androidId"])
        self.assertEqual("Google TV Streamer", payload["items"][0]["result"]["device"]["model"])
        self.assertEqual(
            "bitstream_supported",
            payload["items"][0]["result"]["device"]["evidence"]["audio"]["directPlaybackProbes"][0]["supportMode"],
        )
        self.assertEqual("optimized", payload["items"][0]["result"]["comparison"]["sustainedWinner"])

    def test_non_completed_benchmark_result_is_rejected(self):
        invalid_payload = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
                "terminationReason": "failed",
            },
        }

        response = self.client.post(
            "/api/v1/debrid-benchmark-results",
            headers={"Authorization": "Bearer write-token"},
            json=invalid_payload,
        )

        self.assertEqual(response.status_code, 422)

    def test_admin_clear_removes_shadow_and_benchmark_records(self):
        self.ingest_sample()
        self.ingest_benchmark_sample()

        response = self.client.post("/admin/clear")

        self.assertEqual(response.status_code, 200)
        shadow_response = self.client.get(
            "/api/v1/shadow-autoplay-events",
            headers={"Authorization": "Bearer read-token"},
        )
        benchmark_response = self.client.get(
            "/api/v1/debrid-benchmark-results",
            headers={"Authorization": "Bearer read-token"},
        )
        self.assertEqual(0, shadow_response.json()["count"])
        self.assertEqual(0, benchmark_response.json()["count"])

    def test_shadow_autoplay_events_remain_backward_compatible_without_android_id(self):
        payload = {
            **SAMPLE_ENVELOPE,
            "client": {
                key: value
                for key, value in SAMPLE_ENVELOPE["client"].items()
                if key != "androidId"
            },
        }

        response = self.client.post(
            "/api/v1/shadow-autoplay-events",
            headers={"Authorization": "Bearer write-token"},
            json=payload,
        )

        self.assertEqual(response.status_code, 200)
        listed = self.client.get(
            "/api/v1/shadow-autoplay-events",
            headers={"Authorization": "Bearer read-token"},
        ).json()
        self.assertEqual(1, listed["count"])
        self.assertEqual(None, listed["items"][0]["client"]["androidId"])

    def test_shadow_autoplay_events_can_filter_by_android_id(self):
        self.ingest_sample()
        second_payload = {
            **SAMPLE_ENVELOPE,
            "client": {**SAMPLE_ENVELOPE["client"], "androidId": "android-id-shadow-2"},
            "payload": {
                **SAMPLE_ENVELOPE["payload"],
                "request": {
                    **SAMPLE_ENVELOPE["payload"]["request"],
                    "requestId": "another-request",
                },
            },
        }
        response = self.client.post(
            "/api/v1/shadow-autoplay-events",
            headers={"Authorization": "Bearer write-token"},
            json=second_payload,
        )
        self.assertEqual(response.status_code, 200)

        filtered = self.client.get(
            "/api/v1/shadow-autoplay-events?android_id=android-id-shadow-2",
            headers={"Authorization": "Bearer read-token"},
        ).json()

        self.assertEqual(1, filtered["count"])
        self.assertEqual("android-id-shadow-2", filtered["items"][0]["client"]["androidId"])

    def test_shadow_autoplay_android_id_filter_applies_before_offset(self):
        first_payload = {
            **SAMPLE_ENVELOPE,
            "client": {**SAMPLE_ENVELOPE["client"], "androidId": "android-id-a"},
            "payload": {
                **SAMPLE_ENVELOPE["payload"],
                "request": {
                    **SAMPLE_ENVELOPE["payload"]["request"],
                    "requestId": "request-a",
                },
            },
        }
        second_payload = {
            **SAMPLE_ENVELOPE,
            "client": {**SAMPLE_ENVELOPE["client"], "androidId": "android-id-b"},
            "payload": {
                **SAMPLE_ENVELOPE["payload"],
                "request": {
                    **SAMPLE_ENVELOPE["payload"]["request"],
                    "requestId": "request-b",
                },
            },
        }
        third_payload = {
            **SAMPLE_ENVELOPE,
            "client": {**SAMPLE_ENVELOPE["client"], "androidId": "android-id-a"},
            "payload": {
                **SAMPLE_ENVELOPE["payload"],
                "request": {
                    **SAMPLE_ENVELOPE["payload"]["request"],
                    "requestId": "request-c",
                },
            },
        }
        for payload in [first_payload, second_payload, third_payload]:
            response = self.client.post(
                "/api/v1/shadow-autoplay-events",
                headers={"Authorization": "Bearer write-token"},
                json=payload,
            )
            self.assertEqual(response.status_code, 200)

        filtered = self.client.get(
            "/api/v1/shadow-autoplay-events?android_id=android-id-a&offset=1&limit=1",
            headers={"Authorization": "Bearer read-token"},
        ).json()

        self.assertEqual(1, filtered["count"])
        self.assertEqual(2, filtered["total"])
        self.assertEqual("android-id-a", filtered["items"][0]["client"]["androidId"])

    def test_debrid_benchmark_results_can_filter_by_android_id(self):
        self.ingest_benchmark_sample()
        second_payload = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "android-id-benchmark-2"},
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
                "measuredAtMs": 1775276505000,
            },
        }
        response = self.client.post(
            "/api/v1/debrid-benchmark-results",
            headers={"Authorization": "Bearer write-token"},
            json=second_payload,
        )
        self.assertEqual(response.status_code, 200)

        filtered = self.client.get(
            "/api/v1/debrid-benchmark-results?android_id=android-id-benchmark-2",
            headers={"Authorization": "Bearer read-token"},
        ).json()

        self.assertEqual(1, filtered["count"])
        self.assertEqual("android-id-benchmark-2", filtered["items"][0]["client"]["androidId"])

    def test_benchmark_android_id_filter_applies_before_offset(self):
        payloads = [
            {
                **SAMPLE_BENCHMARK_ENVELOPE,
                "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "benchmark-id-a"},
                "result": {
                    **SAMPLE_BENCHMARK_ENVELOPE["result"],
                    "measuredAtMs": 1000,
                },
            },
            {
                **SAMPLE_BENCHMARK_ENVELOPE,
                "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "benchmark-id-b"},
                "result": {
                    **SAMPLE_BENCHMARK_ENVELOPE["result"],
                    "measuredAtMs": 2000,
                },
            },
            {
                **SAMPLE_BENCHMARK_ENVELOPE,
                "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "benchmark-id-a"},
                "result": {
                    **SAMPLE_BENCHMARK_ENVELOPE["result"],
                    "measuredAtMs": 3000,
                },
            },
        ]
        for payload in payloads:
            response = self.client.post(
                "/api/v1/debrid-benchmark-results",
                headers={"Authorization": "Bearer write-token"},
                json=payload,
            )
            self.assertEqual(response.status_code, 200)

        filtered = self.client.get(
            "/api/v1/debrid-benchmark-results?android_id=benchmark-id-a&offset=1&limit=1&sort=measured&direction=desc",
            headers={"Authorization": "Bearer read-token"},
        ).json()

        self.assertEqual(1, filtered["count"])
        self.assertEqual(2, filtered["total"])
        self.assertEqual("benchmark-id-a", filtered["items"][0]["client"]["androidId"])
        self.assertEqual(1000, filtered["items"][0]["measured_at_ms"])

    def test_event_detail_links_latest_benchmark_download_for_same_android_id(self):
        event_id = self.ingest_sample()
        older = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "android-id-shadow-1"},
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
                "measuredAtMs": 1000,
            },
        }
        newer = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "android-id-shadow-1"},
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
                "measuredAtMs": 2000,
            },
        }
        for payload in [older, newer]:
            response = self.client.post(
                "/api/v1/debrid-benchmark-results",
                headers={"Authorization": "Bearer write-token"},
                json=payload,
            )
            self.assertEqual(response.status_code, 200)

        response = self.client.get(f"/events/{event_id}")

        self.assertEqual(response.status_code, 200)
        html = response.text
        self.assertIn("Latest device benchmark", html)
        self.assertIn("/benchmarks/", html)
        self.assertIn("/download", html)

    def test_benchmark_download_returns_latest_matching_benchmark_json(self):
        event_id = self.ingest_sample()
        older = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "android-id-shadow-1"},
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
                "measuredAtMs": 1000,
            },
        }
        newer = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "client": {**SAMPLE_BENCHMARK_ENVELOPE["client"], "androidId": "android-id-shadow-1"},
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
                "measuredAtMs": 2000,
            },
        }
        inserted_ids = []
        for payload in [older, newer]:
            response = self.client.post(
                "/api/v1/debrid-benchmark-results",
                headers={"Authorization": "Bearer write-token"},
                json=payload,
            )
            self.assertEqual(response.status_code, 200)
            inserted_ids.append(response.json()["id"])

        detail = self.client.get(f"/events/{event_id}")
        self.assertEqual(detail.status_code, 200)
        expected_download_path = f"/benchmarks/{inserted_ids[-1]}/download"
        self.assertIn(expected_download_path, detail.text)

        download = self.client.get(expected_download_path)

        self.assertEqual(download.status_code, 200)
        payload = download.json()
        self.assertEqual(inserted_ids[-1], payload["id"])
        self.assertEqual(2000, payload["result"]["measuredAtMs"])

    def test_benchmark_result_without_device_capabilities_is_rejected(self):
        invalid_payload = {
            **SAMPLE_BENCHMARK_ENVELOPE,
            "result": {
                **SAMPLE_BENCHMARK_ENVELOPE["result"],
            },
        }
        invalid_payload["result"].pop("device")

        response = self.client.post(
            "/api/v1/debrid-benchmark-results",
            headers={"Authorization": "Bearer write-token"},
            json=invalid_payload,
        )

        self.assertEqual(response.status_code, 422)


if __name__ == "__main__":
    unittest.main()
