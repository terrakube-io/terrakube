#!/bin/bash
set -e

# Script to generate self-signed certificates for Terrakube development environment
# This is automatically run if certificates don't exist (e.g., in GitHub Codespaces)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check if certificates already exist
if [ -f "rootCA.pem" ] && [ -f "cert.pem" ] && [ -f "key.pem" ]; then
    echo "Certificates already exist. Skipping generation."
    exit 0
fi

echo "Generating self-signed certificates for development..."

# Generate Root CA configuration
cat > openssl-ca.cnf << 'EOF'
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
x509_extensions = v3_ca

[dn]
C = US
ST = State
L = City
O = Terrakube Dev
CN = Terrakube Development CA

[v3_ca]
basicConstraints = CA:TRUE
keyUsage = digitalSignature, keyEncipherment, keyCertSign
EOF

# Generate Root CA
echo "1. Generating Root CA certificate..."
openssl req -x509 -new -nodes -newkey rsa:2048 -keyout rootCA-key.pem -sha256 -days 3650 -out rootCA.pem -config openssl-ca.cnf

# Generate Server certificate configuration
cat > openssl-server.cnf << 'EOF'
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = req_ext

[dn]
C = US
ST = State
L = City
O = Terrakube Dev
CN = platform.local

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = platform.local
DNS.2 = *.platform.local
DNS.3 = terrakube.platform.local
DNS.4 = terrakube-api.platform.local
DNS.5 = terrakube-registry.platform.local
DNS.6 = terrakube-dex.platform.local
DNS.7 = terrakube-executor.platform.local
EOF

# Generate Server certificate
echo "2. Generating server certificate and private key..."
openssl req -new -nodes -newkey rsa:2048 -keyout key.pem -out server.csr -config openssl-server.cnf

echo "3. Signing server certificate with Root CA..."
openssl x509 -req -in server.csr -CA rootCA.pem -CAkey rootCA-key.pem -CAcreateserial -out cert.pem -days 365 -sha256 -extfile openssl-server.cnf -extensions req_ext

# Clean up temporary files
echo "4. Cleaning up temporary files..."
rm -f openssl-ca.cnf openssl-server.cnf server.csr rootCA.pem.srl

echo "✓ Self-signed certificates generated successfully!"
echo "  - rootCA.pem: Root CA certificate"
echo "  - cert.pem: Server certificate"
echo "  - key.pem: Server private key"
echo ""
echo "Note: These are self-signed certificates for development only."
echo "Your browser will show a security warning - this is expected."
