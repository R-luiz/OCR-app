"""Unit tests for postprocess.py: raw Unlimited-OCR v1 layout stream -> Markdown.

Pure stdlib, no vLLM: ``python -m pytest backend/test_postprocess.py``
"""

from __future__ import annotations

import pytest

from postprocess import html_table_to_markdown, page_to_markdown, split_pages


class TestSplitPages:
    def test_no_marker_is_one_page(self):
        assert split_pages("# Just text\n\nbody") == ["# Just text\n\nbody"]

    def test_empty_input_is_one_blank_page(self):
        assert split_pages("") == [""]

    def test_leading_marker_does_not_create_a_blank_first_page(self):
        assert split_pages("<PAGE>one<PAGE>two") == ["one", "two"]

    def test_blank_text_before_the_first_marker_is_dropped(self):
        assert split_pages("  \n<PAGE>one") == ["one"]

    def test_text_before_the_first_marker_is_a_page(self):
        assert split_pages("preamble<PAGE>one") == ["preamble", "one"]

    def test_blank_middle_page_keeps_its_slot(self):
        # Dropping it would shift every later page's number.
        assert split_pages("<PAGE>one<PAGE><PAGE>three") == ["one", "", "three"]
        assert split_pages("<PAGE>one<PAGE>  \n <PAGE>three") == ["one", "", "three"]

    def test_a_marker_alone_is_one_blank_page(self):
        assert split_pages("<PAGE>") == [""]

    @pytest.mark.parametrize(
        "marker", ["<PAGE>", "<page>", "< PAGE >", "<PAGE 2>", "<PAGE2>", "</PAGE>", "< / Page 12 >"]
    )
    def test_marker_variants(self, marker):
        assert split_pages(f"{marker}one{marker}two") == ["one", "two"]

    def test_segments_are_stripped(self):
        assert split_pages("<PAGE>\n one \n<PAGE>\ttwo\n") == ["one", "two"]


class TestPageToMarkdown:
    def test_observed_sample_with_tokens_already_stripped(self):
        # What the deployed worker returned once its old cleanup had eaten the <|...|>.
        (page,) = split_pages("<PAGE>title [88, 85, 445, 134]INVOICE")
        assert page_to_markdown(page) == "# INVOICE"

    def test_det_blocks(self):
        raw = (
            "<|det|>title [88, 85, 445, 134]<|/det|>INVOICE\n"
            "<|det|>text [88, 160, 445, 190]<|/det|>Total due: 10 EUR\n"
            "continued line\n"
            "<|det|>footer [88, 900, 445, 930]<|/det|>Thank you"
        )
        assert page_to_markdown(raw) == (
            "# INVOICE\n\nTotal due: 10 EUR\ncontinued line\n\nThank you"
        )

    def test_markers_need_not_start_a_line(self):
        raw = "<|det|>text [1, 2, 3, 4]<|/det|>one<|det|>text [5, 6, 7, 8]<|/det|>two"
        assert page_to_markdown(raw) == "one\n\ntwo"

    def test_det_marker_without_a_bbox_or_a_label(self):
        assert page_to_markdown("<|det|>title<|/det|>Heading") == "# Heading"
        assert page_to_markdown("<|det|>[[1, 2, 3, 4]]<|/det|>just text") == "just text"
        assert page_to_markdown("<|det|><|/det|>just text") == "just text"

    def test_ref_and_det_form(self):
        raw = (
            "<|ref|>title<|/ref|><|det|>[[10, 20, 300, 60]]<|/det|>Annual report\n"
            "<|ref|>text<|/ref|><|det|>[[10, 80, 300, 120]]<|/det|>Revenue grew."
        )
        assert page_to_markdown(raw) == "# Annual report\n\nRevenue grew."

    def test_ref_label_without_a_det(self):
        assert page_to_markdown("<|ref|>title<|/ref|>Heading") == "# Heading"

    def test_bare_form_with_a_known_label(self):
        raw = "title [88, 85, 445, 134]INVOICE\ntext [88, 160, 445, 190]Body text"
        assert page_to_markdown(raw) == "# INVOICE\n\nBody text"

    def test_bare_form_for_a_label_with_an_underscore(self):
        assert page_to_markdown("image_caption [1, 2, 3, 4]Figure 1") == "Figure 1"

    def test_bare_form_behind_an_unclosed_det_marker(self):
        assert page_to_markdown("<|det|>title [1, 2, 3, 4]Heading") == "# Heading"

    def test_ordinary_bracketed_text_is_not_eaten(self):
        for text in (
            "see [1, 2, 3, 4]",
            "see [1, 2, 3, 4] and more",
            "[1, 2, 3, 4] opens the line",
            "the table [1, 2, 3, 4] is small",
            "context [1, 2, 3, 4]",
        ):
            assert page_to_markdown(text) == text

    def test_unknown_label_is_not_a_bare_declaration(self):
        assert page_to_markdown("footnote [1, 2, 3, 4]x") == "footnote [1, 2, 3, 4]x"

    def test_title_is_a_heading_with_collapsed_whitespace(self):
        raw = "<|det|>title [0, 0, 1, 1]<|/det|>  Chapter\n   One  \t of\nthree "
        assert page_to_markdown(raw) == "# Chapter One of three"

    def test_title_already_a_heading_is_kept(self):
        assert page_to_markdown("<|det|>title [0, 0, 1, 1]<|/det|>## Section") == "## Section"

    def test_empty_title_is_dropped(self):
        raw = "<|det|>title [0, 0, 1, 1]<|/det|>\n<|det|>text [0, 0, 1, 1]<|/det|>Body"
        assert page_to_markdown(raw) == "Body"

    @pytest.mark.parametrize("label", ["image", "figure", "picture", "chart", "img", "seal"])
    def test_image_blocks_are_dropped(self, label):
        raw = f"<|det|>text [0, 0, 1, 1]<|/det|>Before\n<|det|>{label} [0, 0, 9, 9]<|/det|>\n<|det|>text [0, 0, 1, 1]<|/det|>After"
        assert page_to_markdown(raw) == "Before\n\nAfter"

    @pytest.mark.parametrize(
        "placeholder", ["[non-text]", "[unreadable]", "[image]", "[figure]", "[Non-Text]", "  [IMAGE] "]
    )
    def test_placeholder_content_of_an_image_block_is_dropped(self, placeholder):
        raw = f"<|det|>image [0, 0, 9, 9]<|/det|>{placeholder}"
        assert page_to_markdown(raw) == ""

    def test_real_content_of_an_image_block_is_kept(self):
        raw = "<|det|>chart [0, 0, 9, 9]<|/det|>Sales by quarter"
        assert page_to_markdown(raw) == "Sales by quarter"

    def test_placeholder_text_in_a_text_block_is_kept(self):
        raw = "<|det|>text [0, 0, 9, 9]<|/det|>[image]"
        assert page_to_markdown(raw) == "[image]"

    def test_special_tokens_are_removed(self):
        raw = "<｜begin▁of▁sentence｜><|det|>text [0, 0, 1, 1]<|/det|>Body<｜end▁of▁sentence｜>"
        assert page_to_markdown(raw) == "Body"

    def test_fullwidth_bar_markers_are_blocks_too(self):
        raw = "<｜det｜>title [0, 0, 1, 1]<｜/det｜>Heading\n<｜ref｜>text<｜/ref｜><｜det｜>[[0, 0, 1, 1]]<｜/det｜>Body"
        assert page_to_markdown(raw) == "# Heading\n\nBody"

    def test_a_block_of_nothing_but_a_token_is_dropped(self):
        raw = "<|det|>text [0, 0, 1, 1]<|/det|>One\n<|det|>text [0, 0, 1, 1]<|/det|><|eos|>\n<|det|>text [0, 0, 1, 1]<|/det|>Two"
        assert page_to_markdown(raw) == "One\n\nTwo"

    def test_stray_image_and_page_tokens_are_removed(self):
        assert page_to_markdown("<image>Body<PAGE>") == "Body"

    def test_three_newlines_collapse_to_two(self):
        assert page_to_markdown("a\n\n\n\n\nb") == "a\n\nb"

    def test_plain_markdown_passes_through(self):
        markdown = "# Title\n\nSome *text* with `code`.\n\n- one\n- two\n\n> quote"
        assert page_to_markdown(markdown) == markdown

    def test_existing_gfm_table_passes_through(self):
        markdown = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        assert page_to_markdown(markdown) == markdown

    def test_blank_page_is_empty(self):
        assert page_to_markdown("") == ""
        assert page_to_markdown("  \n ") == ""

    def test_table_block_is_converted_and_surrounding_text_kept(self):
        raw = (
            "<|det|>table [0, 0, 9, 9]<|/det|>Results\n"
            "<table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>\n"
            "Source: lab\n"
            "<|det|>text [0, 0, 9, 9]<|/det|>After"
        )
        assert page_to_markdown(raw) == (
            "Results\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nSource: lab\n\nAfter"
        )

    def test_table_without_a_closing_tag_is_still_converted(self):
        # A completion cut off at the context limit ends mid-table.
        raw = "<|det|>table [0, 0, 9, 9]<|/det|><table><tr><td>a</td><td>b</td></tr><tr><td>1"
        assert page_to_markdown(raw) == "| a | b |\n| --- | --- |\n| 1 |  |"

    def test_two_tables_in_one_block(self):
        raw = "<|det|>table [0, 0, 9, 9]<|/det|><table><tr><td>a</td></tr></table><table><tr><td>b</td></tr></table>"
        assert page_to_markdown(raw) == "| a |\n| --- |\n\n| b |\n| --- |"

    def test_empty_table_in_a_block_leaves_only_the_text(self):
        raw = "<|det|>table [0, 0, 9, 9]<|/det|>Caption<table></table>"
        assert page_to_markdown(raw) == "Caption"


class TestHtmlTableToMarkdown:
    def test_simple_table(self):
        html = "<table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>"
        assert html_table_to_markdown(html) == "| a | b |\n| --- | --- |\n| 1 | 2 |"

    def test_th_header_and_sections_are_ignored(self):
        html = (
            "<table><thead><tr><th>Name</th><th>Qty</th></tr></thead>"
            "<tbody><tr><td>Bolt</td><td>4</td></tr></tbody></table>"
        )
        assert html_table_to_markdown(html) == "| Name | Qty |\n| --- | --- |\n| Bolt | 4 |"

    def test_unclosed_cells_and_rows(self):
        html = "<table><tr><th>a<th>b<tr><td>1<td>2</table>"
        assert html_table_to_markdown(html) == "| a | b |\n| --- | --- |\n| 1 | 2 |"

    def test_colspan(self):
        html = "<table><tr><td colspan=\"2\">wide</td><td>x</td></tr><tr><td>a</td><td>b</td><td>c</td></tr></table>"
        assert html_table_to_markdown(html) == (
            "| wide |  | x |\n| --- | --- | --- |\n| a | b | c |"
        )

    def test_rowspan(self):
        html = (
            "<table><tr><td rowspan=\"2\">tall</td><td>a</td></tr>"
            "<tr><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>"
        )
        assert html_table_to_markdown(html) == (
            "| tall | a |\n| --- | --- |\n|  | b |\n| c | d |"
        )

    def test_rowspan_and_colspan_together(self):
        html = (
            "<table><tr><td rowspan=\"2\" colspan=\"2\">box</td><td>r</td></tr>"
            "<tr><td>s</td></tr><tr><td>1</td><td>2</td><td>3</td></tr></table>"
        )
        assert html_table_to_markdown(html) == (
            "| box |  | r |\n| --- | --- | --- |\n|  |  | s |\n| 1 | 2 | 3 |"
        )

    def test_rowspan_past_the_last_row_is_cut(self):
        html = "<table><tr><td rowspan=\"9\">a</td><td>b</td></tr></table>"
        assert html_table_to_markdown(html) == "| a | b |\n| --- | --- |"

    @pytest.mark.parametrize("value", ["abc", "", "0", "-2", "1.5", "99999999"])
    def test_malformed_spans_count_as_one(self, value):
        html = f"<table><tr><td colspan=\"{value}\" rowspan=\"{value}\">a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>"
        assert html_table_to_markdown(html) == "| a | b |\n| --- | --- |\n| c | d |"

    def test_an_oversized_grid_keeps_cell_text_without_expanding_the_span(self):
        html = (
            '<table><tr><th colspan="1000" rowspan="1000">Heading</th></tr>'
            + "".join(f"<tr><td>Value {i}</td></tr>" for i in range(24))
            + "</table>"
        )
        assert html_table_to_markdown(html).splitlines() == [
            "Heading", *(f"Value {i}" for i in range(24)),
        ]

    def test_pipes_are_escaped(self):
        html = "<table><tr><td>a | b</td><td>c</td></tr></table>"
        assert html_table_to_markdown(html) == "| a \\| b | c |\n| --- | --- |"

    def test_br_and_newlines_become_one_space(self):
        html = "<table><tr><td>line one<br>line two<br/>three\nfour</td><td>x</td></tr></table>"
        assert html_table_to_markdown(html) == (
            "| line one line two three four | x |\n| --- | --- |"
        )

    def test_whitespace_is_collapsed_and_entities_unescaped(self):
        html = "<table><tr><td>  a   &amp;\t b&nbsp;c &lt;d&gt;  </td></tr></table>"
        assert html_table_to_markdown(html) == "| a & b c <d> |\n| --- |"

    def test_markup_inside_a_cell_keeps_its_text(self):
        html = "<table><tr><td><b>bold</b> and <i>italic</i></td></tr></table>"
        assert html_table_to_markdown(html) == "| bold and italic |\n| --- |"

    def test_ragged_rows_are_padded(self):
        html = "<table><tr><td>a</td><td>b</td><td>c</td></tr><tr><td>1</td></tr><tr><td>x</td><td>y</td></tr></table>"
        assert html_table_to_markdown(html) == (
            "| a | b | c |\n| --- | --- | --- |\n| 1 |  |  |\n| x | y |  |"
        )

    def test_empty_cells_stay_in_place(self):
        html = "<table><tr><td></td><td>b</td></tr><tr><td>1</td><td></td></tr></table>"
        assert html_table_to_markdown(html) == "|  | b |\n| --- | --- |\n| 1 |  |"

    def test_header_only_table(self):
        assert html_table_to_markdown("<table><tr><th>a</th><th>b</th></tr></table>") == (
            "| a | b |\n| --- | --- |"
        )

    @pytest.mark.parametrize("html", ["", "<table></table>", "<table><tr></tr></table>", "no table here"])
    def test_table_with_no_cells_is_empty(self, html):
        assert html_table_to_markdown(html) == ""

    def test_uppercase_tags(self):
        html = "<TABLE><TR><TD COLSPAN=2>a</TD></TR></TABLE>"
        assert html_table_to_markdown(html) == "| a |  |\n| --- | --- |"
