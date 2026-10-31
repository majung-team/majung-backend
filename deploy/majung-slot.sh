#!/usr/bin/env sh
set -eu
case "${1:-}" in
  blue) port=8081; metrics=9081 ;;
  green) port=8082; metrics=9082 ;;
  *) exit 64 ;;
esac
export MAJUNG_SLOT="$1"
export MAJUNG_ACTIVE_SLOT_FILE=/etc/majung/active-slot
exec /usr/bin/java -Xms64m -Xmx320m -XX:MaxMetaspaceSize=192m \
  -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=64m \
  -jar "/opt/majung/slots/$1/server.jar" \
  --server.address=127.0.0.1 --server.port="$port" \
  --management.server.port="$metrics" --management.server.address=127.0.0.1
