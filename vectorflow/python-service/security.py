"""Service-to-service authentication.

The Python service is an internal backend: the gateway is responsible for
user authentication.  When ``INTERNAL_API_KEY`` is set, every ``/api`` request
must carry the same value in the ``X-Internal-API-Key`` header, so the
service can't be used directly to bypass the gateway's auth and roles.
"""

import secrets
from typing import Optional

from fastapi import Header, HTTPException, status

from config import get_settings

INTERNAL_API_KEY_HEADER = "X-Internal-API-Key"


async def verify_internal_api_key(
    x_internal_api_key: Optional[str] = Header(default=None, alias=INTERNAL_API_KEY_HEADER),
) -> None:
    expected = get_settings().internal_api_key
    if not expected:
        return
    if not x_internal_api_key or not secrets.compare_digest(x_internal_api_key, expected):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid internal API key",
        )
