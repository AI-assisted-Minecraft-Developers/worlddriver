from scripts.pmcs.run_case import log_slice_since


def test_log_slice_since_marker():
    text = "a\nb\nc\nd\n"
    assert log_slice_since(text, 2) == "c\nd\n"


def test_log_slice_zero_returns_all():
    text = "a\nb\n"
    assert log_slice_since(text, 0) == "a\nb\n"
