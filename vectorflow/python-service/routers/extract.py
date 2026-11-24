"""Text extraction router: turns an uploaded file into plain text for ingestion."""

import asyncio

from fastapi import APIRouter, Depends, File, UploadFile
from pydantic import BaseModel

from config import get_settings
from exceptions import InvalidRequestError
from security import verify_internal_api_key
from services.extraction import extract_text

router = APIRouter(prefix="/api", tags=["extraction"], dependencies=[Depends(verify_internal_api_key)])


class ExtractResponse(BaseModel):
    filename: str
    file_type: str
    text: str
    characters: int
    pages: int | None = None


@router.post("/extract", response_model=ExtractResponse)
async def extract(file: UploadFile = File(...)) -> ExtractResponse:
    """Extract readable text from a PDF, DOCX or text file (no OCR)."""
    max_bytes = get_settings().max_upload_bytes
    data = await file.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise InvalidRequestError(f"File is larger than {max_bytes // (1024 * 1024)} MB")

    filename = file.filename or "upload"
    result = await asyncio.to_thread(extract_text, filename, data)
    return ExtractResponse(
        filename=filename,
        file_type=result.file_type,
        text=result.text,
        characters=len(result.text),
        pages=result.pages,
    )
