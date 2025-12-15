#!/bin/bash

# Script to initialize Terrakube development environment
# This is automatically run before building the devcontainer (e.g., in GitHub Codespaces)
# It handles:
# - Creating Docker network if it doesn't exist
# - Generating SSL certificates if they don't exist

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Initializing Terrakube development environment..."
echo ""

# ============================================================================
# Step 1: Create Docker Network
# ============================================================================

# Load environment variables
if [ -f ".env" ]; then
    set -a
    source .env
    set +a
fi

NETWORK_NAME="${EXTERNAL_NETWORK_NAME:-terrakube-network}"
SUBNET="${NETWORK_SUBNET:-10.25.25.0/24}"
GATEWAY="${NETWORK_GATEWAY:-10.25.25.254}"

# Check if Docker is available
if ! command -v docker >/dev/null 2>&1; then
    echo "⚠ Docker command not found. Skipping network creation."
    echo "  The network will need to be created manually or by the devcontainer."
else
    echo "Checking Docker network '$NETWORK_NAME'..."

    # Check if network exists
    if docker network inspect "$NETWORK_NAME" >/dev/null 2>&1; then
        echo "✓ Docker network '$NETWORK_NAME' already exists."
    else
        echo "Creating Docker network '$NETWORK_NAME'..."
        if docker network create "$NETWORK_NAME" \
            -d bridge \
            --subnet "$SUBNET" \
            --gateway "$GATEWAY" 2>&1; then
            echo "✓ Docker network '$NETWORK_NAME' created successfully!"
        else
            echo "⚠ Failed to create Docker network. This may cause issues during container startup."
            echo "  You can create it manually with:"
            echo "  docker network create $NETWORK_NAME -d bridge --subnet $SUBNET --gateway $GATEWAY"
        fi
    fi
fi

echo ""

# ============================================================================
# Step 2: Generate SSL Certificates
# ============================================================================

# Check if certificates already exist
if [ -f "rootCA.pem" ] && [ -f "cert.pem" ] && [ -f "key.pem" ]; then
    echo "✓ SSL certificates already exist. Skipping generation."
else
    # Check if OpenSSL is available
    if ! command -v openssl >/dev/null 2>&1; then
        echo "✗ OpenSSL command not found. Cannot generate certificates."
        echo "  Please install OpenSSL or generate certificates manually."
        exit 1
    fi

    echo "Generating self-signed SSL certificates..."

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
    openssl req -x509 -new -nodes -newkey rsa:2048 -keyout rootCA-key.pem -sha256 -days 3650 -out rootCA.pem -config openssl-ca.cnf 2>&1 | grep -v "^\." || true

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
    openssl req -new -nodes -newkey rsa:2048 -keyout key.pem -out server.csr -config openssl-server.cnf 2>&1 | grep -v "^\." || true

    echo "3. Signing server certificate with Root CA..."
    openssl x509 -req -in server.csr -CA rootCA.pem -CAkey rootCA-key.pem -CAcreateserial -out cert.pem -days 365 -sha256 -extfile openssl-server.cnf -extensions req_ext >/dev/null 2>&1

    # Clean up temporary files
    echo "4. Cleaning up temporary files..."
    rm -f openssl-ca.cnf openssl-server.cnf server.csr rootCA.pem.srl

    # Verify certificates were created
    if [ -f "rootCA.pem" ] && [ -f "cert.pem" ] && [ -f "key.pem" ]; then
        echo "✓ Self-signed certificates generated successfully!"
        echo "  - rootCA.pem: Root CA certificate"
        echo "  - cert.pem: Server certificate"
        echo "  - key.pem: Server private key"
    else
        echo "✗ Certificate generation failed. Some certificate files are missing."
        echo "  Please check for errors above or generate certificates manually."
        exit 1
    fi
fi

echo ""
echo "================================================================"
echo "✓ Terrakube development environment initialized successfully!"
echo "================================================================"
echo ""
echo "Note: Self-signed certificates will cause browser security warnings."
echo "      This is expected for local development."
echo ""
