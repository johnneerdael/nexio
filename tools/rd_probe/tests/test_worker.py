from urllib.error import URLError

from rd_probe.worker import _classify_error_kind


def test_classify_timeout_message_as_inactivity_timeout():
    assert _classify_error_kind(Exception("The read operation timed out")) == "inactivity_timeout"


def test_classify_connection_reset_as_connection_reset():
    assert _classify_error_kind(ConnectionResetError("Connection reset by peer")) == "connection_reset"


def test_classify_urllib_wrapped_connection_reset():
    assert _classify_error_kind(URLError(ConnectionResetError("Connection reset by peer"))) == "connection_reset"


def test_classify_plain_url_error():
    assert _classify_error_kind(URLError("Name or service not known")) == "url_error"
