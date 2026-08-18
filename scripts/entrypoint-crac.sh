#!/bin/sh
set -e

echo "Terrakube CRaC Entrypoint starting..."

# Check if checkpoint files exist
if [ -d "/opt/crac-files" ] && [ "$(ls -A /opt/crac-files 2>/dev/null)" ]; then
    echo "Found CRaC snapshot in /opt/crac-files. Attempting restore..."
    exec java -XX:CRaCRestoreFrom=/opt/crac-files "$@" || {
        echo "CRaC restore failed (or missing host CRIU capabilities). Falling back to standard JVM launch..."
        exec java -jar /app/app.jar "$@"
    }
else
    echo "No CRaC snapshot found in /opt/crac-files. Launching standard JVM..."
    exec java -jar /app/app.jar "$@"
fi
