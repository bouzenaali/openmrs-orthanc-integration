#!/bin/bash
# Install the patched referenceapplication module (French login strings) and restart OpenMRS.
# Re-run this if the .OpenMRS volume is recreated or the module is replaced.
#
# PRODUCTION-IMPACTING: restarts openmrs-app, ~2-3 min of downtime.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTAINER="${CONTAINER:-openmrs-app}"

echo "==> installing patched referenceapplication-2.12.0.omod into $CONTAINER (.OpenMRS volume)"
docker cp "$DIR/referenceapplication-2.12.0.omod" "$CONTAINER:/usr/local/tomcat/.OpenMRS/modules/referenceapplication-2.12.0.omod"
docker exec "$CONTAINER" chown root:1000 /usr/local/tomcat/.OpenMRS/modules/referenceapplication-2.12.0.omod
docker exec "$CONTAINER" md5sum /usr/local/tomcat/.OpenMRS/modules/referenceapplication-2.12.0.omod

echo "==> restarting $CONTAINER (expect ~2-3 min downtime)"
docker restart "$CONTAINER" >/dev/null

echo "==> waiting for OpenMRS to come up"
for i in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 http://localhost:8080/openmrs/login.htm || true)
    [ "$code" = "200" ] && { echo "    up after ~$((i*5))s"; break; }
    sleep 5
done

exec "$DIR/verify.sh"
