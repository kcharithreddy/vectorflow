import io

import pytest
from docx import Document

from exceptions import InvalidRequestError
from services.extraction import extract_text, normalize_whitespace


def make_pdf(pages: list[str]) -> bytes:
    """Build a small, valid PDF with one line of Helvetica text per page."""
    objects: list[bytes] = []
    n = len(pages)
    page_ids = [3 + 2 * i for i in range(n)]
    font_id = 3 + 2 * n
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {n} >>".encode())
    for i, text in enumerate(pages):
        content_id = page_ids[i] + 1
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> /Contents {content_id} 0 R >>".encode()
        )
        stream = f"BT /F1 18 Tf 72 720 Td ({text}) Tj ET".encode()
        objects.append(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    out = io.BytesIO()
    out.write(b"%PDF-1.4\n")
    offsets = []
    for num, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(f"{num} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for off in offsets:
        out.write(f"{off:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def make_docx() -> bytes:
    doc = Document()
    doc.add_heading("Kubernetes Guide", level=1)
    doc.add_paragraph("A pod is the smallest deployable unit.")
    table = doc.add_table(rows=2, cols=2)
    table.cell(0, 0).text = "Object"
    table.cell(0, 1).text = "Purpose"
    table.cell(1, 0).text = "Deployment"
    table.cell(1, 1).text = "Manages replicas"
    buf = io.BytesIO()
    doc.save(buf)
    return buf.getvalue()


def test_pdf_text_and_page_count():
    result = extract_text("guide.pdf", make_pdf(["Pods run containers", "Services expose pods"]))
    assert result.file_type == "pdf"
    assert result.pages == 2
    assert "Pods run containers" in result.text
    assert "Services expose pods" in result.text


def test_docx_paragraphs_and_tables():
    result = extract_text("Guide.DOCX", make_docx())
    assert result.file_type == "docx"
    assert "Kubernetes Guide" in result.text
    assert "smallest deployable unit" in result.text
    assert "Deployment | Manages replicas" in result.text


def test_text_files_and_encodings():
    assert extract_text("notes.md", "# Title\r\n\r\n\r\n\r\nBody  text".encode()).text == "# Title\n\nBody text"
    assert extract_text("bom.txt", "﻿hello".encode("utf-8")).text == "hello"
    assert extract_text("latin.txt", "café".encode("latin-1")).text == "café"


def test_scanned_pdf_without_text_is_rejected_with_hint():
    with pytest.raises(InvalidRequestError, match="scanned"):
        extract_text("scan.pdf", make_pdf([""]))


@pytest.mark.parametrize("name,data,msg", [
    ("image.png", b"\x89PNG", "Unsupported file type"),
    ("noext", b"data", "Unsupported file type"),
    ("empty.txt", b"", "empty"),
    ("broken.pdf", b"%PDF-1.4 garbage", "could not be read"),
    ("broken.docx", b"not a zip", "could not be read"),
])
def test_bad_files(name, data, msg):
    with pytest.raises(InvalidRequestError, match=msg):
        extract_text(name, data)


def test_normalize_whitespace():
    assert normalize_whitespace("a \t b\x00\n\n\n\nc  \n d") == "a b\n\nc\nd"


def test_extract_endpoint(client):
    r = client.post("/api/extract", files={"file": ("guide.pdf", make_pdf(["Hello from a PDF"]), "application/pdf")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["pages"] == 1 and body["file_type"] == "pdf"
    assert "Hello from a PDF" in body["text"]
    assert body["characters"] == len(body["text"])


def test_extract_endpoint_errors(client):
    r = client.post("/api/extract", files={"file": ("x.exe", b"MZ", "application/octet-stream")})
    assert r.status_code == 400 and "Unsupported" in r.json()["detail"]
    assert client.post("/api/extract").status_code == 422  # no file


def test_extract_endpoint_size_limit(client):
    from config import get_settings
    get_settings().max_upload_bytes = 10
    try:
        r = client.post("/api/extract", files={"file": ("big.txt", b"x" * 50, "text/plain")})
    finally:
        get_settings.cache_clear()
    assert r.status_code == 400 and "larger than" in r.json()["detail"]
