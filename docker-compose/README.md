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