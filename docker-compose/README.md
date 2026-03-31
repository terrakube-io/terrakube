# Terrakube Docker Compose

## Local Domains

We will be using following domains to run Terrakube with docker compose:

```shell
terrakube.platform.local
terrakube-api.platform.local
terrakube-registry.platform.local
terrakube-dex.platform.local
```

## HTTPS Local Certificates

Install [mkcert](https://github.com/FiloSottile/mkcert#installation) to generate the local certificates.

## Generate local CA certificate

```shell
mkcert -install
Created a new local CA 💥
The local CA is now installed in the system trust store! ⚡️
The local CA is now installed in the Firefox trust store (requires browser restart)! 🦊
```

## Create Docker Network

```bash
docker network create terrakube-network -d bridge --subnet 10.25.25.0/24 --gateway 10.25.25.254
```

We will be using `10.25.25.253` for our the traefik gateway

## Local DNS entries

Update the /etc/hosts file adding the following entries:

```bash
10.25.25.253 terrakube.platform.local
10.25.25.253 terrakube-api.platform.local
10.25.25.253 terrakube-registry.platform.local
10.25.25.253 terrakube-dex.platform.local
```

## Running Terrakube Locally with HTTPS

```bash
git clone https://github.com/AzBuilder/terrakube.git
cd terrakube/docker-compose
mkcert -key-file key.pem -cert-file cert.pem platform.local *.platform.local
CAROOT=$(mkcert -CAROOT)/rootCA.pem
cp $CAROOT rootCA.pem
docker-compose up -d --force-recreate
```

Terrakube will be available in the following URL:

* https://terrakube.platform.local
  * Username: admin@example.com
  * Password: admin

## Storage Backend Configuration

Terrakube supports both real AWS S3 and S3-compatible storage (MinIO, Wasabi, Backblaze B2, Cloudflare R2, etc.).
The default `.env` ships configured for the bundled MinIO container.

### Key `.env` variables

| Variable | Description | AWS S3 | MinIO / S3-compatible |
|---|---|---|---|
| `TK_OUTPUT_ENDPOINT` | Full endpoint URL | `""` (leave empty) | `http://terrakube-minio:9000` |
| `TK_OUTPUT_STORAGE_REGION` | AWS region or equivalent | `us-east-1`, `eu-west-1`, … | any value (e.g. `us-east-1`) |
| `TK_OUTPUT_FORCE_PATH_STYLE` | Enable path-style access | `false` | `true` |

> **Important:** Setting `TK_OUTPUT_ENDPOINT` to a real AWS regional URL (e.g. `https://s3.us-east-1.amazonaws.com`)
 > while expecting real AWS S3 behavior will cause a signature region mismatch error (`aws-global` vs `us-east-1`).
> Leave `TK_OUTPUT_ENDPOINT` empty for real AWS S3.

### Example: real AWS S3

```env
TK_OUTPUT_ACCESS_KEY=AKIA...
TK_OUTPUT_SECRET_KEY=...
TK_OUTPUT_BUCKET_NAME=my-terrakube-bucket
TK_OUTPUT_STORAGE_REGION=us-east-1
TK_OUTPUT_BUCKET_REGION=us-east-1
TK_OUTPUT_ENDPOINT=
TK_OUTPUT_FORCE_PATH_STYLE=false
```

### Example: MinIO (default)

```env
TK_OUTPUT_ACCESS_KEY=minioadmin
TK_OUTPUT_SECRET_KEY=minioadmin
TK_OUTPUT_BUCKET_NAME=sample
TK_OUTPUT_STORAGE_REGION=us-east-1
TK_OUTPUT_BUCKET_REGION=us-east-1
TK_OUTPUT_ENDPOINT=http://terrakube-minio:9000
TK_OUTPUT_FORCE_PATH_STYLE=true
```