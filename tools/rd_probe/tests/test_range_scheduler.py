from rd_probe.range_scheduler import RangeScheduler


def test_scheduler_assigns_expected_ranges_for_parallel_workers():
    scheduler = RangeScheduler(file_size=1024, chunk_size=128, parallelism=4)
    assigned = [scheduler.next_range() for _ in range(4)]
    assert assigned == [(0, 127), (128, 255), (256, 383), (384, 511)]
