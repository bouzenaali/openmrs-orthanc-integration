#!/bin/sh
# Well-formedness check for every XML file in the module.
#
# Exists because a "--" inside an XML comment in webModuleApplicationContext.xml took the
# whole OpenMRS web context down: Spring refreshes every module's web context together, so
# one malformed file stops the entire application UI, not just this module. The build does
# not parse these files, and neither does packaging, so they reach the server unchecked.
# Run this before every deploy.
set -e
fail=0
for f in $(find . -name '*.xml' -not -path '*/target/*'); do
    if python3 -c "
import xml.parsers.expat, sys
xml.parsers.expat.ParserCreate().Parse(open('$f','rb').read(), True)
" 2>/tmp/xmlerr; then
        echo "  OK   $f"
    else
        echo "  FAIL $f -> $(cat /tmp/xmlerr | tail -1)"
        fail=1
    fi
done
[ $fail -eq 0 ] && echo "All XML well-formed." || { echo "Malformed XML; do NOT deploy."; exit 1; }
