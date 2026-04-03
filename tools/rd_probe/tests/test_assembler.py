from rd_probe.assembler import InOrderAssembler


def test_assembler_blocks_on_missing_leading_chunk_even_if_later_chunks_finish():
    assembler = InOrderAssembler(chunk_size=128)
    assembler.mark_chunk_complete(index=1, start=128, end=255)
    assert assembler.consumer_bytes_available == 0
    assert assembler.blocked_on_chunk == 0
