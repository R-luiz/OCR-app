"""Unit tests for handler.py: its pure helpers and the orchestration in ``handler()``.

These deliberately avoid importing vLLM so they run on any machine, GPU or not — the
orchestration tests drive ``handler()`` with a fake engine:
``python -m pytest backend/test_handler.py``
"""

from __future__ import annotations

import base64
import dataclasses
import io
from types import SimpleNamespace

import pytest
from PIL import Image

import handler
from handler import (
    EMPTY_PAGE_RETRIES,
    EXTRA_ARG_NGRAM_WINDOW,
    IMAGE_TOKEN,
    NGRAM_SIZE,
    NGRAM_WINDOW_MULTI,
    NGRAM_WINDOW_SINGLE,
    PROMPT_SINGLE,
    InvalidInput,
    NoRepeatNGramLogitsProcessor,
    build_multi_prompt,
    decode_image,
    empty_page_indexes,
    page_windows,
    parse_request,
    request_ngram_processor,
)


def _png_data_url(size=(8, 8), color=(255, 0, 0)) -> str:
    buffer = io.BytesIO()
    Image.new("RGB", size, color).save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode()
    return f"data:image/png;base64,{encoded}"


def _bare_base64_jpeg(size=(8, 8)) -> str:
    buffer = io.BytesIO()
    Image.new("RGB", size, (0, 128, 255)).save(buffer, format="JPEG")
    return base64.b64encode(buffer.getvalue()).decode()


class TestDecodeImage:
    def test_accepts_data_url(self):
        image = decode_image(_png_data_url(size=(12, 6)))
        assert image.size == (12, 6)
        assert image.mode == "RGB"

    def test_accepts_bare_base64(self):
        assert decode_image(_bare_base64_jpeg()).size == (8, 8)

    def test_converts_rgba_to_rgb(self):
        buffer = io.BytesIO()
        Image.new("RGBA", (4, 4), (1, 2, 3, 128)).save(buffer, format="PNG")
        encoded = base64.b64encode(buffer.getvalue()).decode()
        assert decode_image(encoded).mode == "RGB"

    @pytest.mark.parametrize("value", ["", "   ", None, 42])
    def test_rejects_empty_or_non_string(self, value):
        with pytest.raises(InvalidInput):
            decode_image(value)

    def test_rejects_non_base64(self):
        with pytest.raises(InvalidInput):
            decode_image("not base64 !!!")

    def test_rejects_base64_that_is_not_an_image(self):
        with pytest.raises(InvalidInput):
            decode_image(base64.b64encode(b"plain text").decode())

    def test_rejects_data_url_with_no_payload(self):
        with pytest.raises(InvalidInput):
            decode_image("data:image/png;base64,")

    def test_rejects_oversized_payloads(self, monkeypatch):
        import handler

        monkeypatch.setattr(handler, "MAX_IMAGE_BYTES", 128)
        with pytest.raises(InvalidInput, match="limit is 128"):
            handler.decode_image(_png_data_url(size=(200, 200)))

    def test_rejects_a_decompression_bomb_before_allocating(self, monkeypatch):
        import handler

        # A 12000x12000 single-colour PNG compresses to well under a megabyte but
        # would allocate ~430 MB on load. The dimension check must reject it from
        # the header alone, without ever calling load().
        bomb = _png_data_url(size=(12000, 12000))
        monkeypatch.setattr(handler, "MAX_IMAGE_PIXELS", 10_000_000)

        with pytest.raises(InvalidInput, match="pixels"):
            handler.decode_image(bomb)

    def test_allows_images_within_the_pixel_budget(self):
        assert decode_image(_png_data_url(size=(1024, 1024))).size == (1024, 1024)


class TestParseRequest:
    def test_request_holds_only_the_images_and_the_long_context_flag(self):
        assert [f.name for f in dataclasses.fields(handler.OcrRequest)] == [
            "images",
            "long_context",
        ]

    def test_a_single_image_is_never_long_context(self):
        request = parse_request({"images": [_png_data_url()], "long_context": True})
        assert len(request.images) == 1
        assert request.long_context is False

    def test_multiple_images_default_to_long_context(self):
        request = parse_request({"images": [_png_data_url(), _png_data_url()]})
        assert request.long_context is True

    def test_long_context_can_be_turned_off_per_request(self):
        request = parse_request({
            "images": [_png_data_url(), _png_data_url()],
            "long_context": False,
        })
        assert request.long_context is False

    def test_long_context_can_be_asked_for_per_request(self, monkeypatch):
        monkeypatch.setattr(handler, "LONG_CONTEXT_DEFAULT", False)
        images = [_png_data_url(), _png_data_url()]
        assert parse_request({"images": images}).long_context is False
        assert parse_request({"images": images, "long_context": True}).long_context is True

    def test_unknown_keys_are_ignored(self):
        # Older clients still send settings the worker no longer has; none of it is an
        # error, and a bad value for a key that is gone must not fail the job.
        request = parse_request({
            "images": [_png_data_url(), _png_data_url()],
            "mode": "turbo",
            "dpi": "not a number",
            "something_new": {"a": 1},
        })
        assert len(request.images) == 2
        assert request.long_context is True

    @pytest.mark.parametrize("payload", [None, [], "nope", {"images": []}, {"images": "x"}])
    def test_rejects_malformed_payloads(self, payload):
        with pytest.raises(InvalidInput):
            parse_request(payload if isinstance(payload, dict) else {"images": payload})

    def test_rejects_missing_images(self):
        with pytest.raises(InvalidInput):
            parse_request({})

    def test_rejects_too_many_pages(self, monkeypatch):
        monkeypatch.setattr(handler, "MAX_PAGES", 2)
        with pytest.raises(InvalidInput, match="limit is 2"):
            handler.parse_request({"images": [_png_data_url()] * 3})


class TestNoRepeatNGramLogitsProcessor:
    def test_bans_the_token_that_completes_a_repeated_ngram(self):
        processor = NoRepeatNGramLogitsProcessor(ngram_size=3, window_size=64)
        # "1,2" was previously followed by 3, so 3 must be banned after the next "1,2".
        tokens = [1, 2, 3, 9, 1, 2]
        logits = [0.0] * 10
        processor(tokens, logits)
        assert logits[3] == float("-inf")
        assert all(logits[i] == 0.0 for i in range(10) if i != 3)

    def test_no_ban_without_a_repeat(self):
        processor = NoRepeatNGramLogitsProcessor(ngram_size=3, window_size=64)
        logits = [0.0] * 10
        processor([1, 2, 3, 4, 5, 6], logits)
        assert all(value == 0.0 for value in logits)

    def test_short_sequences_are_untouched(self):
        processor = NoRepeatNGramLogitsProcessor(ngram_size=35, window_size=128)
        logits = [0.0] * 10
        processor([1, 2, 3], logits)
        assert all(value == 0.0 for value in logits)

    def test_window_bounds_the_lookback(self):
        processor = NoRepeatNGramLogitsProcessor(ngram_size=3, window_size=4)
        # The 1,2,3 occurrence sits outside the 4-token window, so nothing is banned.
        logits = [0.0] * 10
        processor([1, 2, 3, 7, 8, 1, 2][-7:], logits)
        assert logits[3] == 0.0

    def test_bans_every_distinct_continuation(self):
        processor = NoRepeatNGramLogitsProcessor(ngram_size=2, window_size=64)
        logits = [0.0] * 10
        processor([5, 1, 5, 2, 5], logits)
        assert logits[1] == float("-inf")
        assert logits[2] == float("-inf")
        assert logits[0] == 0.0


class TestRequestNgramProcessor:
    """The V1 engine builds the processor per request from extra_args; this glue
    deciding when and how is the part that runs without a GPU."""

    def test_absent_extra_args_disables_the_processor(self):
        assert request_ngram_processor(None) is None
        assert request_ngram_processor({}) is None

    def test_window_from_extra_args_is_applied(self):
        processor = request_ngram_processor({EXTRA_ARG_NGRAM_WINDOW: 128})
        assert isinstance(processor, NoRepeatNGramLogitsProcessor)
        assert processor.window_size == 128
        assert processor.ngram_size == NGRAM_SIZE

    def test_window_arriving_as_string_is_coerced(self):
        # extra_args may round-trip through JSON serialization layers.
        processor = request_ngram_processor({EXTRA_ARG_NGRAM_WINDOW: "1024"})
        assert processor.window_size == 1024

    def test_built_processor_actually_bans(self):
        processor = request_ngram_processor({EXTRA_ARG_NGRAM_WINDOW: 64})
        processor.ngram_size = 2  # shrink for a compact fixture
        logits = [0.0] * 10
        processor([5, 1, 5], logits)
        assert logits[1] == float("-inf")


class TestPromptPlaceholders:
    """vLLM binds each supplied image to its own <image> placeholder and asserts if one
    is missing, so the count must track the page count exactly. A single placeholder
    with several images failed every multi-page job with
    "Failed to apply prompt replacement for mm_items['image'][1]"."""

    @pytest.mark.parametrize("pages", [1, 2, 4, 16, 64])
    def test_one_placeholder_per_page(self, pages):
        assert build_multi_prompt(pages).count(IMAGE_TOKEN) == pages

    def test_instruction_follows_the_placeholders(self):
        prompt = build_multi_prompt(3)
        assert prompt == IMAGE_TOKEN * 3 + "Multi page parsing."

    def test_never_emits_zero_placeholders(self):
        # A prompt with no image token cannot bind any image at all.
        assert build_multi_prompt(0).count(IMAGE_TOKEN) == 1


class TestEmptyPageIndexes:
    """The response names pages that produced nothing, so a caller is never handed a
    short document with no indication that part of the input is missing."""

    def test_reports_blank_pages_by_index(self):
        assert empty_page_indexes(["a", "  ", "c", ""]) == [1, 3]

    def test_no_blanks_reports_nothing(self):
        assert empty_page_indexes(["a", "b"]) == []


class TestPageWindows:
    def test_exact_multiple(self):
        assert page_windows(24, 12) == [range(0, 12), range(12, 24)]

    def test_remainder_is_balanced_rather_than_left_in_a_stub(self):
        assert page_windows(13, 12) == [range(0, 7), range(7, 13)]
        assert [len(w) for w in page_windows(25, 12)] == [9, 8, 8]

    def test_count_up_to_the_size_is_one_window(self):
        assert page_windows(5, 12) == [range(0, 5)]
        assert page_windows(12, 12) == [range(0, 12)]

    def test_one_page(self):
        assert page_windows(1, 12) == [range(0, 1)]

    def test_no_pages(self):
        assert page_windows(0, 12) == []

    def test_size_one_gives_a_window_per_page(self):
        assert page_windows(3, 1) == [range(0, 1), range(1, 2), range(2, 3)]

    @pytest.mark.parametrize("size", [1, 2, 3, 7, 12])
    def test_windows_tile_the_pages_with_the_fewest_balanced_windows(self, size):
        for count in range(1, 70):
            windows = page_windows(count, size)
            assert [i for w in windows for i in w] == list(range(count))
            assert len(windows) == -(-count // size)
            lengths = [len(w) for w in windows]
            assert max(lengths) <= size
            assert max(lengths) - min(lengths) <= 1

    @pytest.mark.parametrize("size", [0, -3])
    def test_rejects_a_size_below_one(self, size):
        with pytest.raises(ValueError):
            page_windows(5, size)


# --------------------------------------------------------------------------------------
# handler() with a fake engine
# --------------------------------------------------------------------------------------


def _completion(text, finish_reason="stop"):
    """What vLLM's generate() returns per prompt: ``.outputs[0].text`` and friends."""
    return SimpleNamespace(outputs=[SimpleNamespace(text=text, finish_reason=finish_reason)])


class FakeEngine:
    """Stands in for vLLM's ``LLM``.

    ``script`` holds one entry per expected generate() call, in order: the list of
    completions to return, or an exception to raise. Every call is recorded.
    """

    def __init__(self):
        self.script = []
        self.calls = []

    def generate(self, prompts, sampling):
        self.calls.append(SimpleNamespace(prompts=prompts, ngram_window=sampling))
        if not self.script:
            raise AssertionError("unexpected generate() call")
        step = self.script.pop(0)
        if isinstance(step, Exception):
            raise step
        return step


@pytest.fixture
def engine(monkeypatch):
    fake = FakeEngine()
    monkeypatch.setattr(handler, "get_engine", lambda: fake)
    # The real one imports vLLM. Here the "sampling params" are just the n-gram window,
    # which the fake engine records per call.
    monkeypatch.setattr(handler, "_sampling_params", lambda ngram_window: ngram_window)
    return fake


def _job(pages, **extra):
    """A job of ``pages`` images, each a different width so a test can tell them apart."""
    return {
        "input": {
            "images": [_png_data_url(size=(8 + i, 8)) for i in range(pages)],
            **extra,
        }
    }


def _raw(*texts):
    """Raw model output: one ``<PAGE>`` and one text block per page."""
    return "".join(f"<PAGE><|det|>text [0, 0, 1, 1]<|/det|>{text}" for text in texts)


def _widths(call):
    """Widths of the images of each prompt in a generate() call."""
    return [[image.size[0] for image in p["multi_modal_data"]["image"]] for p in call.prompts]


def _assert_every_image_has_a_placeholder(engine):
    for call in engine.calls:
        for prompt in call.prompts:
            assert prompt["prompt"].count(IMAGE_TOKEN) == len(prompt["multi_modal_data"]["image"])


class TestHandlerSinglePage:
    def test_converts_the_layout_stream_to_markdown(self, engine):
        engine.script = [[_completion(
            "<PAGE><|det|>title [88, 85, 445, 134]<|/det|>INVOICE\n"
            "<|det|>text [88, 160, 445, 190]<|/det|>Total: 10 EUR"
        )]]

        result = handler.handler(_job(1))

        expected = "# INVOICE\n\nTotal: 10 EUR"
        assert result["markdown"] == expected
        assert result["pages"] == [{"index": 0, "markdown": expected}]
        assert result["empty_pages"] == []
        assert result["page_mode"] == "per_page"
        assert result["model"] == handler.MODEL_NAME
        assert isinstance(result["elapsed_ms"], int)
        assert "warnings" not in result

    def test_uses_the_single_prompt_and_window(self, engine):
        engine.script = [[_completion(_raw("x"))]]

        handler.handler(_job(1))

        (call,) = engine.calls
        assert call.ngram_window == NGRAM_WINDOW_SINGLE == 128
        (prompt,) = call.prompts
        assert prompt["prompt"] == PROMPT_SINGLE
        assert _widths(call) == [[8]]

    def test_output_with_the_special_tokens_already_stripped(self, engine):
        # What the deployed worker was seen to return.
        engine.script = [[_completion("<PAGE>title [88, 85, 445, 134]INVOICE")]]

        assert handler.handler(_job(1))["markdown"] == "# INVOICE"

    def test_a_single_image_stays_on_the_single_page_path_when_long_context_is_asked(self, engine):
        engine.script = [[_completion(_raw("x"))]]

        result = handler.handler(_job(1, long_context=True))

        assert engine.calls[0].prompts[0]["prompt"] == PROMPT_SINGLE
        assert result["page_mode"] == "per_page"

    def test_settings_older_clients_still_send_are_ignored(self, engine):
        engine.script = [[_completion(_raw("x"))]]

        result = handler.handler(_job(1, mode="single", dpi=300))

        assert result["markdown"] == "x"

    def test_a_truncated_page_gets_a_warning(self, engine):
        engine.script = [[_completion(_raw("partial"), "length")]]

        result = handler.handler(_job(1))

        assert result["markdown"] == "partial"
        assert result["warnings"] == ["page 1: output was cut off at the context limit"]

    def test_a_completion_without_a_finish_reason_is_not_truncated(self, engine):
        engine.script = [[SimpleNamespace(outputs=[SimpleNamespace(text=_raw("x"))])]]

        assert "warnings" not in handler.handler(_job(1))

    def test_inference_failure_is_an_error(self, engine):
        engine.script = [RuntimeError("gpu fell off the bus")]

        assert handler.handler(_job(1)) == {
            "error": "inference failed: RuntimeError: gpu fell off the bus"
        }

    def test_a_completion_count_that_disagrees_is_an_error(self, engine):
        engine.script = [[_completion("a")]]

        result = handler.handler(_job(2, long_context=False))

        assert result == {
            "error": "inference failed: RuntimeError: expected 2 completions, got 1"
        }


class TestHandlerInvalidInput:
    @pytest.mark.parametrize(
        "job",
        [
            {},
            {"input": "nope"},
            {"input": {}},
            {"input": {"images": []}},
            {"input": {"images": ["not base64 !!!"]}},
        ],
    )
    def test_is_an_error_and_never_reaches_the_engine(self, engine, job):
        result = handler.handler(job)

        assert list(result) == ["error"]
        assert result["error"]
        assert engine.calls == []


class TestHandlerLongContext:
    def test_matching_page_markers_give_exact_pages_in_one_call(self, engine):
        engine.script = [[_completion(_raw("one", "two", "three"))]]

        result = handler.handler(_job(3))

        (call,) = engine.calls
        assert call.ngram_window == NGRAM_WINDOW_MULTI == 1024
        (prompt,) = call.prompts
        assert prompt["prompt"] == IMAGE_TOKEN * 3 + "Multi page parsing."
        assert _widths(call) == [[8, 9, 10]]
        assert result["page_mode"] == "per_page"
        assert result["pages"] == [
            {"index": 0, "markdown": "one"},
            {"index": 1, "markdown": "two"},
            {"index": 2, "markdown": "three"},
        ]
        assert result["markdown"] == "one\n\ntwo\n\nthree"
        assert result["empty_pages"] == []
        assert "warnings" not in result

    def test_multiple_images_go_to_long_context_unless_the_default_is_off(self, engine, monkeypatch):
        monkeypatch.setattr(handler, "LONG_CONTEXT_DEFAULT", False)
        engine.script = [[_completion(_raw("one")), _completion(_raw("two"))]]

        handler.handler(_job(2))

        assert [p["prompt"] for p in engine.calls[0].prompts] == [PROMPT_SINGLE] * 2

    def test_a_blank_page_between_markers_is_identified_without_inserting_text(self, engine):
        engine.script = [[_completion("<PAGE>one<PAGE><PAGE>three")]]

        result = handler.handler(_job(3))

        assert result["page_mode"] == "per_page"
        assert result["empty_pages"] == [1]
        assert result["pages"][1] == {"index": 1, "markdown": ""}
        assert result["markdown"] == "one\n\nthree"
        # `empty_pages` is the one channel for this; warnings would say it twice.
        assert "warnings" not in result

    @pytest.mark.parametrize("partial", ["<PAGE>one<PAGE>two", "# Some output", ""])
    def test_inexact_page_boundaries_trigger_a_page_by_page_pass(self, engine, partial):
        engine.script = [
            [_completion(partial)],
            [_completion(_raw("one")), _completion(_raw("two")), _completion(_raw("three"))],
        ]

        result = handler.handler(_job(3))

        assert result["pages"] == [
            {"index": 0, "markdown": "one"},
            {"index": 1, "markdown": "two"},
            {"index": 2, "markdown": "three"},
        ]
        assert result["markdown"] == "one\n\ntwo\n\nthree"
        assert result["empty_pages"] == []
        assert "page by page" in result["warnings"][0]

    def test_a_window_cut_off_at_the_context_limit_is_parsed_page_by_page(self, engine):
        engine.script = [
            [_completion(_raw("one", "tw"), "length")],
            [_completion(_raw("one")), _completion(_raw("two")), _completion(_raw("three"))],
        ]

        result = handler.handler(_job(3))

        assert len(engine.calls) == 2
        assert [c.ngram_window for c in engine.calls] == [NGRAM_WINDOW_MULTI, NGRAM_WINDOW_SINGLE]
        assert [p["prompt"] for p in engine.calls[1].prompts] == [PROMPT_SINGLE] * 3
        assert _widths(engine.calls[1]) == [[8], [9], [10]]
        assert result["page_mode"] == "per_page"
        assert result["markdown"] == "one\n\ntwo\n\nthree"
        assert len(result["warnings"]) == 1
        warning = result["warnings"][0]
        assert "pages 1-3" in warning
        assert "page by page" in warning
        assert "context limit" in warning

    def test_long_context_raising_falls_back_page_by_page_for_every_remaining_window(
        self, engine, monkeypatch
    ):
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 2)
        engine.script = [
            RuntimeError("boom"),
            [_completion(_raw("one")), _completion(_raw("two"))],
            [_completion(_raw("three")), _completion(_raw("four"))],
        ]

        result = handler.handler(_job(4))

        # Long context was attempted once, on the first window, and not again.
        assert [len(c.prompts) for c in engine.calls] == [1, 2, 2]
        assert engine.calls[0].prompts[0]["prompt"] == IMAGE_TOKEN * 2 + "Multi page parsing."
        for call in engine.calls[1:]:
            assert [p["prompt"] for p in call.prompts] == [PROMPT_SINGLE] * 2
            assert call.ngram_window == NGRAM_WINDOW_SINGLE
        assert [_widths(c) for c in engine.calls[1:]] == [[[8], [9]], [[10], [11]]]
        assert engine.script == []
        assert result["page_mode"] == "per_page"
        assert result["markdown"] == "one\n\ntwo\n\nthree\n\nfour"
        assert len(result["warnings"]) == 1
        assert "RuntimeError: boom" in result["warnings"][0]
        assert "context does not carry across pages" in result["warnings"][0]

    def test_a_failing_fallback_is_an_error(self, engine):
        engine.script = [RuntimeError("boom"), RuntimeError("still boom")]

        assert handler.handler(_job(3)) == {
            "error": "inference failed: RuntimeError: still boom"
        }

    def test_long_context_can_be_turned_off_per_request(self, engine):
        engine.script = [[_completion(_raw("one")), _completion(_raw("two")), _completion(_raw("three"))]]

        result = handler.handler(_job(3, long_context=False))

        (call,) = engine.calls
        assert call.ngram_window == NGRAM_WINDOW_SINGLE
        assert [p["prompt"] for p in call.prompts] == [PROMPT_SINGLE] * 3
        assert result["page_mode"] == "per_page"
        assert result["markdown"] == "one\n\ntwo\n\nthree"

    def test_more_pages_than_one_window_go_in_windows_of_the_sizes_page_windows_gives(
        self, engine, monkeypatch
    ):
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 3)
        sizes = [len(w) for w in page_windows(7, 3)]
        assert sizes == [3, 2, 2]
        engine.script = [
            [_completion(_raw("p1", "p2", "p3"))],
            [_completion(_raw("p4", "p5"))],
            [_completion(_raw("p6", "p7"))],
        ]

        result = handler.handler(_job(7))

        assert [_widths(c) for c in engine.calls] == [[[8, 9, 10]], [[11, 12]], [[13, 14]]]
        assert [len(_widths(c)[0]) for c in engine.calls] == sizes
        _assert_every_image_has_a_placeholder(engine)
        assert result["page_mode"] == "per_page"
        assert [p["markdown"] for p in result["pages"]] == [f"p{i}" for i in range(1, 8)]
        assert [p["index"] for p in result["pages"]] == list(range(7))
        assert result["markdown"] == "\n\n".join(f"p{i}" for i in range(1, 8))

    def test_a_window_of_one_page_takes_the_single_page_path(self, engine, monkeypatch):
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 2)
        engine.script = [
            [_completion(_raw("p1", "p2"))],
            [_completion(_raw("p3"))],
        ]

        result = handler.handler(_job(3))

        assert [c.ngram_window for c in engine.calls] == [NGRAM_WINDOW_MULTI, NGRAM_WINDOW_SINGLE]
        assert engine.calls[1].prompts[0]["prompt"] == PROMPT_SINGLE
        assert _widths(engine.calls[1]) == [[10]]
        assert result["markdown"] == "p1\n\np2\n\np3"

    def test_blank_pages_are_numbered_within_the_whole_document(self, engine, monkeypatch):
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 3)
        engine.script = [
            [_completion(_raw("p1", "p2", "p3"))],
            [_completion("<PAGE>p4<PAGE><PAGE>p6")],
        ]

        result = handler.handler(_job(6))

        assert result["empty_pages"] == [4]
        assert result["pages"][4] == {"index": 4, "markdown": ""}
        assert result["markdown"] == "p1\n\np2\n\np3\n\np4\n\np6"
        assert "warnings" not in result

    def test_inexact_window_does_not_erase_empty_page_indexes_from_other_windows(
        self, engine, monkeypatch
    ):
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 3)
        engine.script = [
            [_completion("<PAGE>p1<PAGE><PAGE>p3")],  # exact, with a blank page 2
            [_completion(_raw("p4", "p5"))],  # two markers for three pages
            [_completion(_raw("p4")), _completion(_raw("p5")), _completion(_raw("p6"))],
        ]

        result = handler.handler(_job(6))

        assert result["markdown"] == "p1\n\np3\n\np4\n\np5\n\np6"
        assert result["empty_pages"] == [1]
        assert result["pages"][5] == {"index": 5, "markdown": "p6"}
        assert "pages 4-6" in result["warnings"][0]

    def test_a_window_without_boundaries_does_not_stop_the_others_being_exact(
        self, engine, monkeypatch
    ):
        # Truncation in the second window falls back to exact per-page pages, so the
        # document as a whole keeps its boundaries.
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 3)
        engine.script = [
            [_completion(_raw("p1", "p2", "p3"))],
            [_completion(_raw("p4", "p5"), "length")],
            [_completion(_raw("p4")), _completion(_raw("p5")), _completion(_raw("p6"))],
        ]

        result = handler.handler(_job(6))

        assert result["page_mode"] == "per_page"
        assert [p["markdown"] for p in result["pages"]] == [f"p{i}" for i in range(1, 7)]
        assert len(result["warnings"]) == 1
        assert "pages 4-6" in result["warnings"][0]

    def test_truncated_pages_in_a_per_page_fallback_are_named_by_their_place_in_the_document(
        self, engine, monkeypatch
    ):
        monkeypatch.setattr(handler, "LONG_CONTEXT_WINDOW_PAGES", 2)
        engine.script = [
            [_completion(_raw("p1", "p2"))],
            RuntimeError("boom"),
            [_completion(_raw("p3")), _completion(_raw("p4"), "length")],
        ]

        result = handler.handler(_job(4))

        assert result["warnings"][-1] == "page 4: output was cut off at the context limit"


class TestHandlerPerPage:
    def test_all_pages_go_in_one_batch(self, engine):
        engine.script = [[_completion(_raw("one")), _completion(_raw("two"))]]

        result = handler.handler(_job(2, long_context=False))

        assert len(engine.calls) == 1
        assert _widths(engine.calls[0]) == [[8], [9]]
        _assert_every_image_has_a_placeholder(engine)
        assert result["pages"] == [
            {"index": 0, "markdown": "one"},
            {"index": 1, "markdown": "two"},
        ]

    def test_a_blank_page_is_retried_alone_then_reported(self, engine):
        engine.script = [
            [_completion(_raw("one")), _completion(""), _completion(_raw("three"))],
            *([[_completion("")]] * EMPTY_PAGE_RETRIES),
        ]

        result = handler.handler(_job(3, long_context=False))

        assert len(engine.calls) == 1 + EMPTY_PAGE_RETRIES
        for retry in engine.calls[1:]:
            assert _widths(retry) == [[9]]  # the second page, alone
        assert result["empty_pages"] == [1]
        assert result["pages"][1] == {"index": 1, "markdown": ""}
        assert result["markdown"] == "one\n\nthree"
        assert "warnings" not in result

    def test_a_page_that_succeeds_on_retry_is_not_reported(self, engine):
        engine.script = [
            [_completion(_raw("one")), _completion(""), _completion(_raw("three"))],
            [_completion(_raw("two"))],
        ]

        result = handler.handler(_job(3, long_context=False))

        assert len(engine.calls) == 2
        assert result["empty_pages"] == []
        assert result["markdown"] == "one\n\ntwo\n\nthree"
        assert "warnings" not in result

    def test_emptiness_is_judged_on_the_converted_markdown(self, engine):
        # Raw text that holds nothing but a dropped image block is an empty page.
        engine.script = [
            [_completion("<PAGE><|det|>image [0, 0, 9, 9]<|/det|>[non-text]")],
            *([[_completion("<PAGE><image>")]] * EMPTY_PAGE_RETRIES),
        ]

        result = handler.handler(_job(1))

        assert len(engine.calls) == 1 + EMPTY_PAGE_RETRIES
        assert result["empty_pages"] == [0]
        assert result["markdown"] == ""

    def test_a_truncated_page_is_named_by_its_number(self, engine):
        engine.script = [[
            _completion(_raw("one")),
            _completion(_raw("tw"), "length"),
            _completion(_raw("three")),
        ]]

        result = handler.handler(_job(3, long_context=False))

        assert result["warnings"] == ["page 2: output was cut off at the context limit"]
        assert result["markdown"] == "one\n\ntw\n\nthree"
