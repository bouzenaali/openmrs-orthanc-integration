"""Convert the UTF-8 draft bundles into ASCII .properties with \\uXXXX escapes."""
import pathlib

HERE = pathlib.Path(__file__).resolve().parent
DEST = HERE.parent / "omod" / "src" / "main" / "resources"
SRC = HERE / "i18n-src"

HEADERS = {
    "en": (
        "# medreport - English messages (the module's fallback bundle).\n"
        "# ASCII with backslash-u escapes: java.util.Properties reads .properties as\n"
        "# ISO-8859-1, so escaping is the only encoding-independent way to ship non-ASCII.\n"
        "# Regenerate with scripts/escape_props.py rather than editing escapes by hand.\n\n"
    ),
    "fr": (
        "# medreport - messages francais (langue par defaut des rapports).\n"
        "# ASCII avec echappements backslash-u - voir messages.properties.\n\n"
    ),
    "ar": (
        "# medreport - Arabic messages (right-to-left rendering is handled by the\n"
        "# report renderer, not by this bundle).\n"
        "# ASCII with backslash-u escapes - see messages.properties.\n\n"
    ),
}

NAMES = {"en": "messages.properties", "fr": "messages_fr.properties", "ar": "messages_ar.properties"}

DEST.mkdir(parents=True, exist_ok=True)
for lang, name in NAMES.items():
    text = (SRC / f"msg_{lang}.properties").read_text(encoding="utf-8")
    escaped = "".join(c if ord(c) < 128 else "\\u%04x" % ord(c) for c in text)
    (DEST / name).write_text(HEADERS[lang] + escaped, encoding="ascii")
    print(f"{name}: {len(text)} chars -> {len(escaped)} escaped")
