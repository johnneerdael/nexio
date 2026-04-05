from datetime import datetime, timezone

from rd_probe.premiumize_api import PremiumizeItem, PremiumizeItemDetails
from rd_probe.premiumize_candidate_resolver import PremiumizeCandidateResolver


class FakePremiumizeClient:
    def list_items(self):
        return [
            PremiumizeItem(
                id="older",
                name="Older.Movie.mkv",
                size_bytes=20 * 1024**3,
                created_at=datetime(2024, 1, 1, tzinfo=timezone.utc),
                mime_type="video/x-matroska",
            ),
            PremiumizeItem(
                id="newer",
                name="Newer.Movie.mkv",
                size_bytes=20 * 1024**3,
                created_at=datetime(2024, 1, 2, tzinfo=timezone.utc),
                mime_type="video/x-matroska",
            ),
            PremiumizeItem(
                id="small",
                name="Small.Movie.mp4",
                size_bytes=2 * 1024**3,
                created_at=datetime(2024, 1, 3, tzinfo=timezone.utc),
                mime_type="video/mp4",
            ),
        ]

    def get_item_details(self, item_id: str):
        return PremiumizeItemDetails(
            id=item_id,
            name=f"{item_id}.mkv",
            size_bytes=20 * 1024**3,
            stream_link=f"https://streaming.example.com/{item_id}.mkv",
            link=f"https://link.example.com/{item_id}.mkv",
            mime_type="video/x-matroska",
            created_at=datetime(2024, 1, 2, tzinfo=timezone.utc),
        )


def test_automatic_selection_prefers_large_playable_item_and_newest_tiebreak():
    resolver = PremiumizeCandidateResolver(FakePremiumizeClient())

    result = resolver.resolve_large_candidate()

    assert result.source == "premiumize_item"
    assert result.source_id == "newer"
    assert result.size_bytes and result.size_bytes > 10 * 1024**3


def test_item_override_uses_exact_item():
    resolver = PremiumizeCandidateResolver(FakePremiumizeClient())

    result = resolver.resolve(item_id="older")

    assert result.source == "premiumize_item"
    assert result.source_id == "older"
    assert result.explicit_override is True
