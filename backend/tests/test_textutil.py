from app.textutil import chunk_page, validate_citations, parse_json_loose


def test_chunk_respects_size_and_keeps_text():
    text = "\n\n".join(f"Paragraph {i} " + "x" * 300 for i in range(10))
    chunks = chunk_page(text, size=800)
    assert all(len(c) <= 800 for c in chunks) and len(chunks) > 1
    assert "Paragraph 9" in chunks[-1]


def test_long_paragraph_is_split():
    chunks = chunk_page("y" * 5000, size=1200, overlap=100)
    assert len(chunks) >= 4 and all(len(c) <= 1200 for c in chunks)


def test_invalid_citations_removed():
    clean, used = validate_citations("Doji signals indecision [S1][S7].", 2)
    assert used == [1] and "[S7]" not in clean


def test_json_fences():
    assert parse_json_loose('```json\n{"a": 1}\n```') == {"a": 1}
