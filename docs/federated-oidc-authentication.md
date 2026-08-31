# Federated OIDC authentication

Terrakube can trust short-lived JWTs issued by an external OpenID Connect provider. The external
token is sent directly as the Bearer token; there is no separate token-exchange endpoint and no
Terrakube PAT needs to be stored by the workload.

Terrakube verifies the token signature and standard lifetime/issuer claims using the provider's
OIDC discovery metadata. It then requires the configured audience and every configured claim
condition to match. A matching credential maps the workload to an existing Terrakube team, whose
permissions continue to control the resources and operations the workload can use.

## Configure Terrakube

1. Create a Terrakube team for the workload and grant only the required organization, workspace,
   module, provider, or job permissions.
2. Open **Organization settings → Security → Federated Credentials** and create a credential.
3. Set **Terrakube team name** to the exact name of the team from step 1.
4. Set the issuer and audience expected in the workload token.
5. Add at least one claim condition that identifies the repository or Kubernetes ServiceAccount.

OIDC discovery and the provider's JWKS endpoint must be reachable from both the Terrakube API and,
when the private registry is used, the Terrakube registry service. Claim matching is exact; wildcard
values are not supported.

## GitHub Actions

Use these credential values for GitHub-hosted Actions:

| Field | Value |
| --- | --- |
| Issuer URL | `https://token.actions.githubusercontent.com` |
| Audience | `terrakube` (or another value chosen for this Terrakube deployment) |
| Recommended claim | `repository` = `OWNER/REPOSITORY` |

Add narrower conditions such as `ref` or `environment` when only a particular branch or protected
environment should have access. The workflow must explicitly request `id-token: write`, request the
same audience configured in Terrakube, and send the returned JWT to the API:

```yaml
name: Terrakube plan

on:
  pull_request:

permissions:
  contents: read
  id-token: write

jobs:
  plan:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5

      - name: Call Terrakube with a GitHub OIDC token
        env:
          TERRAKUBE_URL: https://terrakube.example.com
        run: |
          TERRAKUBE_TOKEN="$(
            curl --fail-with-body --silent --show-error \
              --header "Authorization: Bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" \
              "${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=terrakube" \
              | jq --raw-output '.value'
          )"
          echo "::add-mask::${TERRAKUBE_TOKEN}"
          curl --fail-with-body \
            --header "Authorization: Bearer ${TERRAKUBE_TOKEN}" \
            --header "Accept: application/vnd.api+json" \
            "${TERRAKUBE_URL}/api/v1/organization"
```

The OIDC token is scoped to one job and expires automatically. Do not write it to logs or persist it
as an artifact.

## Kubernetes

The Kubernetes ServiceAccount issuer must expose standards-compliant OIDC discovery and JWKS
metadata at the configured issuer URL. Create a credential using:

| Field | Value |
| --- | --- |
| Issuer URL | the cluster's configured ServiceAccount issuer |
| Audience | `terrakube` |
| Recommended claim | `sub` = `system:serviceaccount:automation:terrakube-client` |

Project a short-lived, audience-bound ServiceAccount token into the Pod instead of using the default
API-server token:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: terrakube-client
  namespace: automation
---
apiVersion: v1
kind: Pod
metadata:
  name: terrakube-client
  namespace: automation
spec:
  serviceAccountName: terrakube-client
  automountServiceAccountToken: false
  containers:
    - name: client
      image: curlimages/curl:latest
      command: ["sh", "-c"]
      args:
        - |
          while true; do
            curl --fail-with-body \
              --header "Authorization: Bearer $(cat /var/run/secrets/terrakube/token)" \
              --header "Accept: application/vnd.api+json" \
              https://terrakube.example.com/api/v1/organization
            sleep 300
          done
      volumeMounts:
        - name: terrakube-token
          mountPath: /var/run/secrets/terrakube
          readOnly: true
  volumes:
    - name: terrakube-token
      projected:
        sources:
          - serviceAccountToken:
              path: token
              audience: terrakube
              expirationSeconds: 3600
```

The kubelet rotates projected tokens. Long-running clients must read the token file again before
each request (or on a short interval) instead of caching its initial contents.

## Troubleshooting

- `401 Unauthorized`: verify the issuer URL exactly matches the token's `iss`, the configured
  audience is present in `aud`, discovery/JWKS is reachable, and the token has not expired.
- `403 Forbidden` or an empty JSON: verify every claim condition and confirm the credential name
  exactly matches a Terrakube team with access to the requested resource.
- Decode a token payload only in a secure local environment. The payload is useful for inspecting
  `iss`, `aud`, and provider-specific claims, but decoding does not verify the signature.
