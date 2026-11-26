"""Converts Unlimited-OCR v1's raw layout stream into Markdown.

The model does not emit Markdown. It emits one stream of layout blocks, each opened by
a detection marker, with ``<PAGE>`` between pages::

    <PAGE><|det|>title [88, 85, 445, 134]<|/det|>INVOICE
    <|det|>text [88, 160, 445, 190]<|/det|>Body text
    <|det|>table [88, 200, 445, 400]<|/det|><table>...</table>

Baidu's own post-processing (``remove_det`` in the model README) strips the markers;
this goes further and produces headings, GFM tables and page boundaries. Pure standard
library, so it imports on any machine and is what the unit tests exercise.
"""

from __future__ import annotations

import re
from html.parser import HTMLParser

# Special tokens use an ASCII bar or its fullwidth twin depending on the tokenizer.
_BAR = r"[|｜]"


def _open(name: str) -> str:
    return rf"<{_BAR}\s*(?i:{name})\s*{_BAR}>"


def _close(name: str) -> str:
    return rf"<{_BAR}\s*/\s*(?i:{name})\s*{_BAR}>"


# The label set Baidu documents for v2, which v1 shares. Only these are recognised in
# the bare form below: that form has no marker to anchor it, so a label outside this
# list would let ordinary text such as "see [1, 2, 3, 4]" be eaten as a declaration.
_KNOWN_LABELS = (
    "text", "title", "header", "footer", "page_number", "image", "figure", "chart",
    "seal", "table", "image_caption", "image_footnote", "page_footnote", "equation",
    "ref_text", "aside_text", "algorithm",
)
_NUMBER = r"-?\d+(?:\.\d+)?"
_BBOX = rf"\[\s*{_NUMBER}(?:\s*[,，]\s*{_NUMBER}){{3}}\s*\]"

# One alternative per way a block can be declared. Inside the markers the content is
# limited to [^<]: a declaration never contains a tag, and the limit stops an unclosed
# marker from swallowing text up to some later, unrelated closing marker.
_MARKER_RE = re.compile(
    # <|ref|>label<|/ref|>, optionally followed by <|det|>[[x1,y1,x2,y2]]<|/det|>
    rf"{_open('ref')}(?P<ref>[^<]*){_close('ref')}"
    rf"(?:\s*{_open('det')}[^<]*{_close('det')})?"
    # <|det|>label [bbox]<|/det|>
    rf"|{_open('det')}(?P<det>[^<]*){_close('det')}"
    # What is left once the special tokens were stripped (or a det marker that never
    # closed): a known label and a bbox at the start of a line.
    rf"|^[ \t]*(?:{_open('det')}[ \t]*)?"
    rf"(?P<bare>{'|'.join(sorted(_KNOWN_LABELS, key=len, reverse=True))})[ \t]*{_BBOX}",
    re.MULTILINE,
)
_LABEL_RE = re.compile(r"\s*([A-Za-z_][A-Za-z0-9_]*)")

_PAGE_RE = re.compile(r"<\s*/?\s*PAGE\s*\d*\s*>", re.IGNORECASE)
_SPECIAL_TOKEN_RE = re.compile(
    rf"<{_BAR}[^|｜<>]*{_BAR}>|<image>|{_PAGE_RE.pattern}", re.IGNORECASE
)
_EXTRA_NEWLINES_RE = re.compile(r"\n{3,}")
_TABLE_RE = re.compile(r"<table\b.*?(?:</table\s*>|\Z)", re.IGNORECASE | re.DOTALL)

_IMAGE_LABELS = frozenset({"image", "figure", "picture", "chart", "img", "seal"})
_PLACEHOLDERS = frozenset({"[non-text]", "[unreadable]", "[image]", "[figure]"})

# HTML lets a span be arbitrarily large; this is the cap the HTML standard itself uses
# for colspan, so a corrupt value cannot ask for an unbounded grid.
_MAX_SPAN = 1000
_MAX_TABLE_GRID_CELLS = 20_000


def split_pages(raw: str) -> list[str]:
    """The text of each page of ``raw``, split on ``<PAGE>`` markers.

    Blank pages between markers are kept as empty strings so page numbering does not
    shift. Text before the first marker is a page of its own unless it is blank, which
    is the usual case since the model opens with a marker. Without any marker the whole
    text is one page.
    """
    segments = [segment.strip() for segment in _PAGE_RE.split(raw)]
    if len(segments) == 1:
        return segments
    if not segments[0]:
        del segments[0]
    return segments


def page_to_markdown(raw_page: str) -> str:
    """One page of raw layout blocks as Markdown.

    Text with no markers at all passes through, so output that is already Markdown is
    only stripped.
    """
    matches = list(_MARKER_RE.finditer(raw_page))
    starts = [m.start() for m in matches] + [len(raw_page)]

    blocks = [_render_block(None, raw_page[: starts[0]])]
    for match, end in zip(matches, starts[1:]):
        blocks.append(_render_block(_label(match), raw_page[match.end() : end]))

    text = "\n\n".join(block for block in blocks if block)
    return _EXTRA_NEWLINES_RE.sub("\n\n", text).strip()


def _label(match: re.Match[str]) -> str | None:
    if match.group("bare"):
        return match.group("bare")
    if match.group("ref") is not None:
        label = match.group("ref").strip()
        return label.lower() or None
    found = _LABEL_RE.match(match.group("det"))
    return found.group(1).lower() if found else None


def _render_block(label: str | None, content: str) -> str:
    # Leftover special tokens are removed per block, before anything is judged empty,
    # so a block holding nothing but an end-of-sentence token is dropped like any other.
    content = _SPECIAL_TOKEN_RE.sub("", content).strip()

    if label in _IMAGE_LABELS and (not content or content.lower() in _PLACEHOLDERS):
        return ""
    if _TABLE_RE.search(content):
        return _TABLE_RE.sub(_table_replacement, content).strip()
    if label == "title":
        title = " ".join(content.split())
        if not title or title.startswith("#"):
            return title
        return f"# {title}"
    return content


def _table_replacement(match: re.Match[str]) -> str:
    table = html_table_to_markdown(match.group(0))
    return f"\n\n{table}\n\n" if table else ""


class _TableParser(HTMLParser):
    """Collects ``(text, colspan, rowspan)`` cells per row from a lenient parse.

    Model output is often cut off or sloppy, so a cell or row may never be closed:
    opening the next ``<td>``/``<tr>`` (or the end of input) closes whatever is open.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[tuple[str, int, int]]] = []
        self._row: list[tuple[str, int, int]] | None = None
        self._text: list[str] | None = None
        self._spans = (1, 1)

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self._end_row()
            self._row = []
        elif tag in ("td", "th"):
            self._end_cell()
            if self._row is None:
                self._row = []
            attributes = dict(attrs)
            self._spans = (_span(attributes.get("colspan")), _span(attributes.get("rowspan")))
            self._text = []
        elif tag == "br" and self._text is not None:
            self._text.append(" ")

    def handle_endtag(self, tag):
        if tag in ("td", "th"):
            self._end_cell()
        elif tag in ("tr", "table"):
            self._end_row()

    def handle_data(self, data):
        if self._text is not None:
            self._text.append(data)

    def finish(self) -> None:
        self.close()
        self._end_row()

    def _end_cell(self) -> None:
        if self._text is None or self._row is None:
            return
        text = " ".join("".join(self._text).split()).replace("|", "\\|")
        self._row.append((text, *self._spans))
        self._text = None

    def _end_row(self) -> None:
        self._end_cell()
        if self._row:
            self.rows.append(self._row)
        self._row = None


def _span(value: str | None) -> int:
    try:
        span = int((value or "1").strip())
    except ValueError:
        return 1
    return span if 1 <= span <= _MAX_SPAN else 1


def html_table_to_markdown(html: str) -> str:
    """One ``<table>`` as a GFM pipe table; the first row is the header.

    ``colspan`` and ``rowspan`` are honoured on a rectangular grid, with the positions a
    span covers left empty. If the grid would be disproportionately large, keep the
    transcribed cells as plain rows instead. A table with no cells yields an empty string.
    """
    parser = _TableParser()
    parser.feed(html)
    parser.finish()
    rows = parser.rows
    if not rows:
        return ""

    grid: dict[tuple[int, int], str] = {}
    covered: set[tuple[int, int]] = set()
    width = 0
    for r, row in enumerate(rows):
        c = 0
        for text, colspan, rowspan in row:
            while (r, c) in covered:
                c += 1
            # Limit the entire rectangle, not each span: a few wide cells can
            # otherwise create millions of synthetic cells with almost no text.
            if (c + colspan) * len(rows) > _MAX_TABLE_GRID_CELLS:
                return "\n".join(" | ".join(cell[0] for cell in row) for row in rows)
            # A rowspan past the last row is cut there, as browsers do.
            for dr in range(min(rowspan, len(rows) - r)):
                for dc in range(colspan):
                    covered.add((r + dr, c + dc))
            grid[(r, c)] = text
            c += colspan
            width = max(width, c)

    lines = [
        "| " + " | ".join(grid.get((r, c), "") for c in range(width)) + " |"
        for r in range(len(rows))
    ]
    lines.insert(1, "| " + " | ".join(["---"] * width) + " |")
    return "\n".join(lines)
