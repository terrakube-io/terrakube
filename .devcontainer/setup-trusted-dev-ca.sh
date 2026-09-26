#!/bin/sh
set -eu

node_extra_ca_bundle=/usr/local/share/ca-certificates/terrakube-extra-ca-bundle.pem
: > "$node_extra_ca_bundle"

trust_certificate() {
  certificate="$1"
  alias="$2"

  cp "$certificate" "/usr/local/share/ca-certificates/${alias}.crt"
  cat "$certificate" >> "$node_extra_ca_bundle"
  printf '\n' >> "$node_extra_ca_bundle"
  for keytool_binary in "$(command -v keytool)" "${JAVA_HOME:-}/bin/keytool"; do
    [ -x "$keytool_binary" ] || continue
    if "$keytool_binary" -list -cacerts -storepass changeit -alias "$alias" >/dev/null 2>&1; then
      "$keytool_binary" -delete -cacerts -storepass changeit -alias "$alias"
    fi
    "$keytool_binary" -importcert -noprompt -trustcacerts -cacerts -storepass changeit \
      -alias "$alias" -file "$certificate"
  done
}

# Trust the mkcert root used by Traefik so Java and Node inside the container can
# call the same local HTTPS routes as the host browser.
if [ -f /var/run/terrakube-mkcert/rootCA.pem ]; then
  trust_certificate /var/run/terrakube-mkcert/rootCA.pem terrakube-mkcert-ca
fi

corporate_ca_directory=/workspaces/terrakube/.devcontainer/certs
if [ -d "$corporate_ca_directory" ]; then
  find "$corporate_ca_directory" -maxdepth 1 -type f \( -name '*.crt' -o -name '*.pem' \) -print |
    while IFS= read -r certificate; do
      certificate_id=$(sha256sum "$certificate" | cut -d ' ' -f 1)
      trust_certificate "$certificate" "terrakube-corporate-ca-${certificate_id}"
    done
fi

update-ca-certificates

if [ "${1:-}" = "--refresh" ]; then
  exit 0
fi

exec sleep infinity
