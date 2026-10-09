#!/bin/bash
# Credential-free check that the French login page no longer shows the
# placeholder string. The login page is public, so an anonymous GET is enough.
# Note: uiframework picks the response type from Accept, so text/html is required.
set -uo pipefail
URL="${URL:-http://localhost:8080/openmrs/login.htm}"
body=$(mktemp); trap 'rm -f "$body"' EXIT

code=$(curl -s -H 'Accept: text/html' -H 'Accept-Language: fr' -o "$body" -w '%{http_code}' "$URL")
button=$(grep -o 'id="loginButton"[^>]*value="[^"]*"' "$body" | grep -o 'value="[^"]*"' | head -1)

if [ "$code" != "200" ]; then
    echo "FAIL: $URL -> HTTP $code. Is openmrs-app up? docker logs openmrs-app"
    exit 1
fi
if echo "$button" | grep -q '{0}'; then
    echo "FAIL: login button still renders a placeholder: $button"
    echo "      The unpatched French bundle is active. Run apply.sh."
    exit 1
fi
if echo "$button" | grep -q 'value="Se connecter"'; then
    echo "PASS: login button renders $button"
    grep -o 'Impossible de se connecter ?' "$body" >/dev/null \
        && echo "PASS: cannotLogin link renders the corrected wording" \
        || echo "WARN: cannotLogin wording not found (check the locale served)"
    exit 0
fi
echo "WARN: $URL -> HTTP $code, no placeholder but the button reads $button"
echo "      Not the original bug; check the session locale."
exit 2
