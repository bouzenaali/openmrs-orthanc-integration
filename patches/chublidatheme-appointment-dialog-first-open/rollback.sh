#!/bin/bash
# Restore the unpatched theme. Presentation only; no restart needed.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${CONTAINER:-openmrs-app}"
LIVE=/usr/local/tomcat/webapps/openmrs/WEB-INF/view/module/chublidatheme/resources/styles/chu-theme.css
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT

docker cp "$DIR/chublidatheme-omod-1.0.7.omod.original-backup" "$CONTAINER:/usr/local/tomcat/.OpenMRS/modules/chublidatheme-omod-1.0.7.omod"
docker exec "$CONTAINER" chown root:1000 /usr/local/tomcat/.OpenMRS/modules/chublidatheme-omod-1.0.7.omod
( cd "$W" && unzip -o -q "$DIR/chublidatheme-omod-1.0.7.omod.original-backup" web/module/resources/styles/chu-theme.css )
docker cp "$W/web/module/resources/styles/chu-theme.css" "$CONTAINER:$LIVE"
echo "rolled back; the served stylesheet no longer carries the rule:"
curl -s "http://localhost:8080/openmrs/moduleResources/chublidatheme/styles/chu-theme.css" | grep -c "allAppointmentTypesModal" || true
