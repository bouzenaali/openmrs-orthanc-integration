#!/bin/bash
# Credential-free check that the force-password-change 500 is gone.
# Handler resolution happens before authentication, so an anonymous GET is enough.
set -uo pipefail
URL="${URL:-http://localhost:8080/openmrs/admin/users/changePassword.form}"
body=$(mktemp); trap 'rm -f "$body"' EXIT

code=$(curl -s -H 'Accept: text/html' -o "$body" -w '%{http_code}' "$URL")

if grep -q "No adapter for handler" "$body"; then
    echo "FAIL: $URL -> HTTP $code, still 'No adapter for handler'."
    echo "      The unpatched adminui mapping is active. Run apply.sh."
    exit 1
fi
if [ "$code" = "500" ]; then
    echo "FAIL: $URL -> HTTP 500 (different cause). Inspect: docker logs openmrs-app"
    exit 1
fi
if grep -q 'name="oldPassword"' "$body" && grep -q 'name="confirmPassword"' "$body"; then
    echo "PASS: $URL -> HTTP $code, legacyui change-password form served."
    exit 0
fi
echo "WARN: $URL -> HTTP $code, no adapter error but the legacy form was not recognised."
echo "      Not the original bug; check manually."
exit 2
