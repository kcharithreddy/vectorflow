"""Extract plain text from uploaded files (PDF, DOCX and text formats)."""

import io
import logging
import re
from dataclasses import dataclass
from pathlib import PurePath

from exceptions import InvalidRequestError

logger = logging.getLogger(__name__)

TEXT_EXTENSIONS = {".txt", ".md", ".markdown", ".csv", ".json", ".log", ".html", ".htm", ".xml", ".rst"}
SUPPORTED_EXTENSIONS = {".pdf", ".docx"} | TEXT_EXTENSIONS


@dataclass
class ExtractedText:
    text: str
    pages: int | None  # PDF page count; None for other formats
    file_type: str


def extension(filename: str) -> str:
    return PurePath(filename or "").suffix.lower()


def extract_text(filename: str, data: bytes) -> ExtractedText:
    """Return the readable text of a file. CPU-bound: call via asyncio.to_thread."""
    ext = extension(filename)
    if ext not in SUPPORTED_EXTENSIONS:
        raise InvalidRequestError(
            f"Unsupported file type '{ext or 'unknown'}'. Supported: PDF, DOCX, "
            + ", ".join(sorted(e.lstrip(".").upper() for e in TEXT_EXTENSIONS))
        )
    if not data:
        raise InvalidRequestError("The file is empty")

    if ext == ".pdf":
        result = _extract_pdf(data)
    elif ext == ".docx":
        result = ExtractedText(_extract_docx(data), None, "docx")
    else:
        result = ExtractedText(_decode_text(data), None, ext.lstrip("."))

    text = normalize_whitespace(result.text)
    if not text:
        hint = " It may be a scanned PDF (images only) - OCR isn't supported yet." if ext == ".pdf" else ""
        raise InvalidRequestError(f"No readable text found in '{filename}'.{hint}")
    result.text = text
    return result


def _extract_pdf(data: bytes) -> ExtractedText:
    from pypdf import PdfReader
    from pypdf.errors import PdfReadError

    try:
        reader = PdfReader(io.BytesIO(data))
        if reader.is_encrypted:
            try:
                reader.decrypt("")  # many "protected" PDFs only restrict editing
            except Exception:
                raise InvalidRequestError("This PDF is password-protected")
        pages = []
        for page in reader.pages:
            try:
                pages.append(page.extract_text() or "")
            except Exception:  # one broken page shouldn't sink the whole file
                logger.warning("Could not extract a PDF page", exc_info=True)
                pages.append("")
        return ExtractedText("\n\n".join(pages), len(reader.pages), "pdf")
    except InvalidRequestError:
        raise
    except (PdfReadError, ValueError, KeyError) as exc:
        raise InvalidRequestError("The PDF could not be read - it may be corrupted") from exc


def _extract_docx(data: bytes) -> str:
    import zipfile

    from docx import Document

    try:
        doc = Document(io.BytesIO(data))
    except (zipfile.BadZipFile, KeyError, ValueError) as exc:
        raise InvalidRequestError("The DOCX file could not be read - it may be corrupted") from exc

    parts = [p.text for p in doc.paragraphs]
    for table in doc.tables:
        for row in table.rows:
            cells = []
            for cell in row.cells:
                value = cell.text.strip()
                if value and (not cells or cells[-1] != value):  # merged cells repeat
                    cells.append(value)
            if cells:
                parts.append(" | ".join(cells))
    return "\n".join(parts)


def _decode_text(data: bytes) -> str:
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):  # UTF-16 byte-order mark
        return data.decode("utf-16", errors="replace")
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("latin-1")  # never fails; right for most legacy Western files


def normalize_whitespace(text: str) -> str:
    """Tidy extracted text: unify newlines, drop NULs, collapse runs of blank lines and spaces."""
    text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\x00", "")
    text = re.sub(r"[ \t\f\v]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()
