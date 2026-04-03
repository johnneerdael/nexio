from rd_probe.pcap import build_tcpdump_command


def test_pcap_controller_builds_tcpdump_command_for_host_filter():
    cmd = build_tcpdump_command(output="capture.pcap", host="example.com")
    assert "tcpdump" in cmd[0]
