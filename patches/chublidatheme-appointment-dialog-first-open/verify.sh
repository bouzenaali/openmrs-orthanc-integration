#!/bin/bash
# Credential-free check that the fix is being served. The theme stylesheet is
# public, so an anonymous GET is enough; the dialog itself sits behind a login.
set -uo pipefail
URL="${URL:-http://localhost:8080/openmrs/moduleResources/chublidatheme/styles/chu-theme.css}"
css=$(mktemp); trap 'rm -f "$css"' EXIT

code=$(curl -s -o "$css" -w '%{http_code}' "$URL")
if [ "$code" != "200" ]; then
    echo "FAIL: $URL -> HTTP $code. Is openmrs-app up, and is chublidatheme started?"
    exit 1
fi
if ! grep -q '#allAppointmentTypesModal.ng-hide' "$css"; then
    echo "FAIL: the served stylesheet does not contain the fix. Run apply.sh."
    exit 1
fi
for prop in 'display: block !important' 'visibility: hidden' 'left: -9999px' 'animation: none !important'; do
    grep -A6 '#allAppointmentTypesModal.ng-hide' "$css" | grep -qF "$prop" || {
        echo "FAIL: rule present but '$prop' is missing - the block is incomplete."
        exit 1
    }
done
echo "PASS: $URL serves the first-open dialog fix (HTTP $code, $(wc -c < "$css") bytes)."
echo "      To confirm in the UI: log in, open Programmation des rendez-vous >"
echo "      Gerer les tranches de rendez-vous, and click 'Afficher tous les types'"
echo "      on a freshly loaded page. The Services dialog must be centred with"
echo "      its 'Fermer' button visible on the FIRST click."
exit 0
