# Terrakube Docker Compose

This is the standalone full-stack Compose environment. For source development,
prefer the repository's Dev Container; it supplies debuggers and development
tooling.

## Prerequisites

- Docker Engine or Docker Desktop with the Compose plugin
- mkcert on the host

No custom Docker subnet, DNS server, or hosts-file entries are required. The
stack uses the loopback-reserved `*.localhost` names.

## Start

Trust mkcert's local CA once on the host:

```sh
mkcert -install
```

Generate the local certificate from this directory:

```sh
mkcert -key-file privkey.pem -cert-file fullchain.pem \
  localhost terrakube.localhost terrakube-api.localhost \
  terrakube-registry.localhost terrakube-executor.localhost \
  terrakube-dex.localhost
cp "$(mkcert -CAROOT)/rootCA.pem" rootCA.pem
docker compose up -d
```

Restart the browser after the one-time CA installation, then open
<https://terrakube.localhost> and sign in with `admin@example.com` / `admin`.

If organisation policy blocks `mkcert -install`, use the organisation's
approved process for trusting a development CA.

The generated certificate, private key, and root CA are local development
files and must not be committed.

## Storage backend

The bundled S3-compatible storage service is intended for local development
and demonstrations. Configure an appropriate storage backend for production.
