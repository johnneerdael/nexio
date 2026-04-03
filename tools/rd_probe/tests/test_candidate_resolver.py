from datetime import datetime, timezone

from rd_probe.candidate_resolver import CandidateResolver
from rd_probe.rd_api import RdDownload, RdResolvedLink, RdTorrent, RdTorrentFile, RdTorrentInfo


class FakeRdClient:
    def __init__(self):
        self.downloads = [
            RdDownload(
                id="download-1",
                filename="Small.mp4",
                size_bytes=2 * 1024**3,
                link="https://rd/link/small",
                direct_url="https://cdn/small.mp4",
                listed_at=datetime(2024, 1, 1, tzinfo=timezone.utc),
            )
        ]
        self.torrents = [
            RdTorrent(
                id="torrent-older",
                filename="Older.mkv",
                status="downloaded",
                listed_at=datetime(2024, 1, 1, tzinfo=timezone.utc),
            ),
            RdTorrent(
                id="torrent-newer",
                filename="Newer.mkv",
                status="downloaded",
                listed_at=datetime(2024, 1, 2, tzinfo=timezone.utc),
            ),
        ]

    def list_downloads(self, *, page: int = 1, limit: int = 200):
        return self.downloads

    def list_torrents(self, *, page: int = 1, limit: int = 200):
        return self.torrents

    def get_torrent_info(self, torrent_id: str):
        return RdTorrentInfo(
            id=torrent_id,
            filename=f"{torrent_id}.mkv",
            original_filename=f"{torrent_id}.mkv",
            files=(
                RdTorrentFile(id=1, path=f"/{torrent_id}.mkv", size_bytes=20 * 1024**3, selected=True),
            ),
            links=(f"https://rd/link/{torrent_id}",),
            listed_at=datetime(2024, 1, 1, tzinfo=timezone.utc),
        )

    def unrestrict_link(self, link: str, *, remote: int = 0):
        torrent_id = link.rsplit("/", 1)[-1]
        return RdResolvedLink(
            link=link,
            direct_url=f"https://cdn/{torrent_id}.mkv",
            filename=f"{torrent_id}.mkv",
            size_bytes=20 * 1024**3,
            host="107-4.download.real-debrid.com",
        )


def test_automatic_selection_prefers_large_playable_item_and_newest_tiebreak():
    resolver = CandidateResolver(FakeRdClient())

    result = resolver.resolve_large_candidate()

    assert result.filename.endswith(".mkv")
    assert result.size_bytes > 10 * 1024**3
    assert result.source_id == "torrent-newer"


def test_download_override_uses_exact_download():
    resolver = CandidateResolver(FakeRdClient())

    result = resolver.resolve(download_id="download-1")

    assert result.source == "download"
    assert result.explicit_override is True
    assert result.source_id == "download-1"
