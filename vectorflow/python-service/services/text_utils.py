"""Small text helpers shared by routers and services."""

SNIPPET_LENGTH = 200


def make_snippet(text: str, length: int = SNIPPET_LENGTH) -> str:
    """Collapse whitespace and trim to ``length`` characters on a word boundary."""
    text = " ".join(text.split())
    if len(text) <= length:
        return text
    cut = text[:length]
    space = cut.rfind(" ")
    if space > length * 0.6:
        cut = cut[:space]
    return cut.rstrip(" ,.;:") + "..."
