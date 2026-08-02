"""Unit tests for the pure helpers in handler.py.

These deliberately avoid importing vLLM so they run on any machine, GPU or not:
``python -m pytest backend/test_handler.py``
"""

from __future__ import annotations

import base64
import io

import pytest
from PIL import Image

from handler import (
    EXTRA_ARG_NGRAM_WINDOW,
    IMAGE_TOKEN,
    build_multi_prompt,
    EMPTY_PAGE_NOTE,
    empty_page_indexes,
    assemble_pages,
    join_pages,
    InvalidInput,
    NGRAM_SIZE,
    NoRepeatNGramLogitsProcessor,
    clean_output,
    decode_image,
    parse_request,
    request_ngram_processor,
    split_pages,
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
    def test_single_image_defaults_to_gundam_config(self):
        request = parse_request({"images": [_png_data_url()]})
        assert request.mode == "single"
        assert (request.base_size, request.image_size, request.crop_mode) == (1024, 640, True)
        assert request.prompt.endswith("document parsing.")
        assert request.ngram_window == 128

    def test_multiple_images_default_to_base_config(self):
        request = parse_request({"images": [_png_data_url(), _png_data_url()]})
        assert request.mode == "multi"
        assert (request.image_size, request.crop_mode) == (1024, False)
        assert request.prompt.endswith("Multi page parsing.")
        # Multi-page defaults to the long-context path, which is one whole-document
        # generation — what the wider repetition window was sized for.
        assert request.long_context is True
        assert request.ngram_window == 1024

    def test_long_context_can_be_turned_off_per_request(self):
        request = parse_request({
            "images": [_png_data_url(), _png_data_url()],
            "long_context": False,
        })
        assert request.long_context is False
        # Per-page inference is a series of single-page generations, so it takes the
        # single-page window rather than the whole-document one.
        assert request.ngram_window == 128

    def test_the_multi_prompt_carries_one_placeholder_per_page(self):
        """The model's processor asserts exactly this, splitting the prompt on the
        token to pair each text run with its image."""
        request = parse_request({"images": [_png_data_url()] * 3})
        assert request.prompt == "<image><image><image>Multi page parsing."

    def test_multi_mode_forces_flat_config_even_if_caller_asks_for_crops(self):
        request = parse_request(
            {
                "images": [_png_data_url(), _png_data_url()],
                "mode": "multi",
                "image_size": 640,
                "crop_mode": True,
            },
        )
        assert request.image_size == 1024
        assert request.crop_mode is False

    def test_single_mode_with_many_images_is_downgraded_with_a_warning(self):
        request = parse_request({"images": [_png_data_url()] * 3, "mode": "single"})
        assert request.mode == "multi"
        assert request.warnings

    def test_single_mode_honors_explicit_overrides(self):
        request = parse_request(
            {"images": [_png_data_url()], "mode": "single", "image_size": 1024, "crop_mode": False},
        )
        assert request.image_size == 1024
        assert request.crop_mode is False

    @pytest.mark.parametrize("payload", [None, [], "nope", {"images": []}, {"images": "x"}])
    def test_rejects_malformed_payloads(self, payload):
        with pytest.raises(InvalidInput):
            parse_request(payload if isinstance(payload, dict) else {"images": payload})

    def test_rejects_unknown_mode(self):
        with pytest.raises(InvalidInput):
            parse_request({"images": [_png_data_url()], "mode": "turbo"})

    def test_rejects_too_many_pages(self, monkeypatch):
        import handler

        monkeypatch.setattr(handler, "MAX_PAGES", 2)
        with pytest.raises(InvalidInput, match="limit is 2"):
            handler.parse_request({"images": [_png_data_url()] * 3})


class TestCleanOutput:
    def test_strips_special_tokens_and_whitespace(self):
        raw = "  <|begin|>## Title\n\nBody text<|end▁of▁sentence|>  "
        assert clean_output(raw) == "## Title\n\nBody text"

    def test_leaves_ordinary_markdown_untouched(self):
        markdown = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        assert clean_output(markdown) == markdown


class TestSplitPages:
    def test_single_page_wraps_whole_document(self):
        assert split_pages("# One", 1) == [{"index": 0, "markdown": "# One"}]

    def test_empty_single_page_yields_nothing(self):
        assert split_pages("", 1) == []

    def test_splits_on_form_feed(self):
        pages = split_pages("page one\f page two", 2)
        assert [p["markdown"] for p in pages] == ["page one", "page two"]

    def test_returns_empty_when_split_count_disagrees(self):
        # Signals "cannot split reliably"; callers fall back to the full markdown.
        assert split_pages("no delimiters here", 3) == []


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

    def test_parsed_multi_request_matches_its_page_count(self):
        for pages in (2, 5):
            request = parse_request({"images": [_png_data_url()] * pages})
            assert request.prompt.count(IMAGE_TOKEN) == len(request.images) == pages

    def test_parsed_single_request_has_exactly_one(self):
        request = parse_request({"images": [_png_data_url()]})
        assert request.prompt.count(IMAGE_TOKEN) == len(request.images) == 1


class TestAssemblePages:
    """Each page is inferred separately now, so boundaries are exact rather than
    recovered by splitting one blob on delimiters."""

    def test_one_entry_per_page_in_order(self):
        pages = assemble_pages(["page one", "page two", "page three"])
        assert [p["index"] for p in pages] == [0, 1, 2]
        assert [p["markdown"] for p in pages] == ["page one", "page two", "page three"]

    def test_special_tokens_are_stripped_per_page(self):
        pages = assemble_pages(["<|begin|>## A<|end▁of▁sentence|>", "  B  "])
        assert [p["markdown"] for p in pages] == ["## A", "B"]

    def test_blank_page_keeps_its_slot_so_numbering_survives(self):
        # Dropping an empty page would shift every later page's number.
        pages = assemble_pages(["first", "   ", "third"])
        assert [p["index"] for p in pages] == [0, 1, 2]
        assert pages[1]["markdown"] == ""
        assert pages[2]["markdown"] == "third"

    def test_no_pages_yields_no_entries(self):
        assert assemble_pages([]) == []


class TestJoinPages:
    def test_joins_in_order(self):
        assert join_pages(assemble_pages(["a", "b"])) == "a\n\nb"

    def test_blank_page_is_stated_rather_than_hidden(self):
        # Silently omitting it produced a short document that still read complete.
        joined = join_pages(assemble_pages(["a", "  ", "c"]))
        assert joined == "a\n\n" + EMPTY_PAGE_NOTE.format(number=2) + "\n\nc"
        assert "page 2" in joined

    def test_single_page_is_unchanged(self):
        assert join_pages(assemble_pages(["only"])) == "only"

    def test_all_blank_still_accounts_for_every_page(self):
        joined = join_pages(assemble_pages(["", "  "]))
        assert joined == (
            EMPTY_PAGE_NOTE.format(number=1) + "\n\n" + EMPTY_PAGE_NOTE.format(number=2)
        )


class TestEmptyPageIndexes:
    """The response names pages that produced nothing, so a caller is never handed a
    short document with no indication that part of the input is missing."""

    def test_reports_blank_pages_by_index(self):
        assert empty_page_indexes(assemble_pages(["a", "  ", "c", ""])) == [1, 3]

    def test_no_blanks_reports_nothing(self):
        assert empty_page_indexes(assemble_pages(["a", "b"])) == []

    def test_indexes_line_up_with_the_page_entries(self):
        pages = assemble_pages(["a", "", "c"])
        for index in empty_page_indexes(pages):
            assert pages[index]["markdown"] == ""
