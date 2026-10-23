# Setup Terrakube Dynamic Credentials (OpenBao/Vault)

## Requirements

Make sure to mount your public and private key to the API container as explained [here](https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials#generate-public-and-private-key)

> Mare sure the private key is in ***"pkcs8"*** format

Validate the following endpoints are working:

- https://terrakube-api.mydomain.com/.well-known/jwks
- https://terrakube-api.mydomain.com/.well-known/openid-configuration

Running openbao in development mode

```shell
bao server -dev -dev-root-token-id="dev-only-token" 
```

Use the following openbao policy called `terrakube-policy.hcl`

```terraform
# Allow tokens to query themselves
path "auth/token/lookup-self" {
  capabilities = ["read"]
}

# Allow tokens to renew themselves
path "auth/token/renew-self" {
  capabilities = ["update"]
}

# Allow tokens to revoke themselves
path "auth/token/revoke-self" {
  capabilities = ["update"]
}

# Configure the actual secrets the token should have access to
path "shared/data/kv/creds" {
  capabilities = ["read"]
}
```

Create JWT Role using the file `vault-jwt-auth-role.json`
```json
{
  "policies": ["tfc-policy"],
  "role_type": "jwt",
  "bound_audiences": ["vault.workload.identity"],
  "bound_claims_type": "glob",
  "user_claim": "terrakube_workspace_name",
  "bound_claims": {
    "sub":"organization:simple:workspace:vault"
  },
  "token_ttl": "20m"
}
```

Adding test values, policy and jwt role to openbao with the following:
```shell
export VAULT_ADDR='http://localhost:8200'
bao login dev-only-token
bao auth enable userpass
bao write auth/userpass/users/terrakube password=p@ssw0rd
bao auth enable approle
bao write auth/approle/role/terrakube-role secret_id_ttl=10m token_ttl=20m token_max_ttl=30m
bao secrets enable -path shared -version 2 kv
bao kv put -mount shared kv/creds username=terrakube password=p@ssw0rd
bao auth enable jwt
bao write auth/jwt/config oidc_discovery_url="https://terrakube-api.platform.local" bound_issuer="https://terrakube-api.platform.local" 
bao policy write tfc-policy terrakube-policy.hcl
bao write auth/jwt/role/tfc-role @vault-jwt-auth-role.json
```

At the workspace level we need to define the following environment variables:

```shell
ENABLE_DYNAMIC_CREDENTIALS_VAULT=1
WORKLOAD_IDENTITY_VAULT_AUDIENCE=vault.workload.identity
VAULT_ADDR=http://localhost:8200
WORKLOAD_IDENTITY_VAULT_ROLE=tfc-role
```

![image](https://github.com/user-attachments/assets/ac15200f-5728-4a81-8aa6-69de5ad7bbb0)

Now running cli driven workflow with the following terraform code to test

```terraform
terraform {
  cloud {
    hostname = "terrakube-api.platform.local"
    organization = "simple"
    workspaces {
      name = "vault"
    }
  }
}

provider vault {
  skip_tls_verify = true
  skip_child_token = true
  }

data "vault_kv_secret_v2" "password" {
  mount = "shared"
  name  = "kv/creds"
}
```

It will show the following:

```shell
user@pop-os:~/git/terrakube/vault$ terraform plan

Running plan in Terraform Cloud. Output will stream here. Pressing Ctrl-C
will stop streaming the logs, but will not stop the plan running remotely.

Preparing the remote plan...

To view this run in a browser, visit:
https://terrakube-api.platform.local/app/simple/vault/runs/run-1

Waiting for the plan to start...

***************************************
Running Terraform PLAN
***************************************
data.vault_kv_secret_v2.password: Reading...
data.vault_kv_secret_v2.password: Read complete after 0s [id=shared/data/kv/creds]

No changes. Your infrastructure matches the configuration.
```

## Project claim

When the workspace belongs to a project, the JWT also carries a `terrakube_project_name` claim. You can bind on it in the JWT role to scope access by project, for example:

```json
"bound_claims": {
  "terrakube_project_name": "my-project"
}
```

The claim is only present when the workspace has a project, so a role that binds on it will reject tokens from workspaces that are not in that project.
