import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import openrouter_translation_benchmark as bench


class OpenRouterTranslationBenchmarkTest(unittest.TestCase):
    def test_build_payload_forces_groq_and_disables_reasoning(self):
        payload = bench.build_payload("meta-llama/llama-3.3-70b-instruct", "hello")

        self.assertEqual(payload["model"], "meta-llama/llama-3.3-70b-instruct")
        self.assertEqual(payload["provider"], {"only": ["groq"]})
        self.assertEqual(payload["reasoning"], {"effort": "none", "enabled": False})
        self.assertEqual(payload["temperature"], 0)
        self.assertIn("hello", payload["messages"][1]["content"])

    def test_parse_srt_cues_keeps_indices_timestamps_and_text(self):
        cues = bench.parse_srt(
            """1
00:00:02,000 --> 00:00:07,000
Hello there.

2
00:00:08,000 --> 00:00:13,000
Line one.
Line two.
"""
        )

        self.assertEqual(len(cues), 2)
        self.assertEqual(cues[0].index, "1")
        self.assertEqual(cues[0].timestamp, "00:00:02,000 --> 00:00:07,000")
        self.assertEqual(cues[1].text, "Line one.\nLine two.")

    def test_score_translation_rewards_structure_and_reference_similarity(self):
        candidate = """1
00:00:02,000 --> 00:00:07,000
Een Big DickUs-productie.
Proost Jeugdtrainingsschema

2
00:00:08,000 --> 00:00:13,000
Spaties toegevoegd.
Beter voor oude tv's
"""
        expected = """1
00:00:02,000 --> 00:00:07,000
Een Big DickUs-productie.
Proost Jeugdtrainingsschema

2
00:00:08,000 --> 00:00:13,000
Spaties toegevoegd.
Beter voor oude tv's
"""

        score = bench.score_translation(candidate, expected)

        self.assertEqual(score["candidate_cues"], 2)
        self.assertEqual(score["expected_cues"], 2)
        self.assertEqual(score["timestamp_accuracy"], 1.0)
        self.assertEqual(score["text_similarity"], 1.0)
        self.assertEqual(score["overall_accuracy"], 1.0)

    def test_write_successful_srt_files_skips_failed_models(self):
        rows = [
            {
                "model": "meta-llama/llama-3.3-70b-instruct",
                "run": 1,
                "ok": True,
                "translation": "```srt\n1\n00:00:02,000 --> 00:00:07,000\nHallo.\n```",
            },
            {
                "model": "openai/gpt-oss-20b",
                "run": 1,
                "ok": False,
                "translation": "1\n00:00:02,000 --> 00:00:07,000\nMislukt.\n",
            },
        ]

        with tempfile.TemporaryDirectory() as tmp:
            bench.write_successful_srt_files(Path(tmp), rows)
            written = sorted(Path(tmp).glob("*.srt"))

            self.assertEqual(len(written), 1)
            self.assertEqual(written[0].name, "meta-llama__llama-3.3-70b-instruct__run-1.srt")
            self.assertEqual(written[0].read_text(encoding="utf-8"), "1\n00:00:02,000 --> 00:00:07,000\nHallo.\n")
            self.assertEqual(rows[0]["srt_path"], str(written[0]))
            self.assertNotIn("srt_path", rows[1])


if __name__ == "__main__":
    unittest.main()
