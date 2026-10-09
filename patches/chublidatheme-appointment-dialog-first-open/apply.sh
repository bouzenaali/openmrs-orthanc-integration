#!/bin/bash
# Install the patched CHU Blida theme (first-open appointment-type dialog fix).
#
# No restart needed. The theme stylesheet is served straight off disk by
# openmrs-core's ModuleResourcesServlet, so writing the live copy makes the fix
# effective on the next page load; the patched .omod is installed alongside it
# so the fix survives the next restart (which re-expands the module).
#
# Classified: FILE CHANGE inside openmrs-app. Presentation only. Reversible by
# running rollback.sh.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${CONTAINER:-openmrs-app}"
LIVE=/usr/local/tomcat/webapps/openmrs/WEB-INF/view/module/chublidatheme/resources/styles/chu-theme.css

echo "==> installing patched chublidatheme-omod-1.0.7.omod into $CONTAINER (.OpenMRS volume)"
docker cp "$DIR/chublidatheme-omod-1.0.7.omod" "$CONTAINER:/usr/local/tomcat/.OpenMRS/modules/chublidatheme-omod-1.0.7.omod"
docker exec "$CONTAINER" chown root:1000 /usr/local/tomcat/.OpenMRS/modules/chublidatheme-omod-1.0.7.omod

echo "==> updating the live expanded stylesheet (takes effect without a restart)"
docker cp "$DIR/chu-theme.css.patched" "$CONTAINER:$LIVE"
docker exec "$CONTAINER" md5sum "$LIVE"

echo
echo "Browsers may hold the previous stylesheet for up to a couple of hours"
echo "(it is served with Last-Modified only). Ctrl+Shift+R clears it at once, or"
echo "bump chublidatheme.assetVersion in Administration > Settings > Chublidatheme"
echo "to change the ?v= query and force every client to refetch."
echo

exec "$DIR/verify.sh"
