# Setup Terrakube Dynamic Credentials (AWS)

## Requirements

Make sure to mount your public and private key to the API container as explained [here](https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials#generate-public-and-private-key)

> Mare sure the private key is in ***"pkcs8"*** format

Validate the following endpoints are working:

- https://terrakube-api.mydomain.com/.well-known/jwks
- https://terrakube-api.mydomain.com/.well-known/openid-configuration

Set terraform variables using: ***"variables.auto.tfvars"***

```terraform
terrakube_token = "TERRAKUBE_PERSONAL_ACCESS_TOKEN"
terrakube_api_hostname = "TERRAKUBE-API.MYCLUSTER.COM"
terrakube_federated_credentials_audience="aws.workload.identity"
terrakube_organization_name="simple"
terrakube_workspace_name = "dynamic-workspace-aws"
aws_region = "us-east-1"
```

> To generate the API token check [here](https://docs.terrakube.io/user-guide/organizations/api-tokens)

Run Terraform apply to create all the federated credential setup in GCP and a sample workspace in terrakube for testing

To test the following terraform code can be used:


```terraform
terraform {

  cloud {
    organization = "terrakube_organization_name"
    hostname = "terrakube-api.mydomain.com"

    workspaces {
      name = "terrakube_workspace_name"
    }
  }
}

provider "aws" {


}

# The role created by this setup is scoped by the terrakube:workspace session tag,
# so it can only access the prefix that matches the workspace name.
# The bucket "my-terrakube-bucket" is expected to already exist.
resource "aws_s3_object" "example" {
  bucket  = "my-terrakube-bucket"
  key     = "dynamic-workspace-aws/example.txt"
  content = "hello from terrakube dynamic credentials"
}
```

## Session tags (ABAC)

The AWS web identity token issued by Terrakube also carries AWS session tags. This lets you scope IAM roles with `aws:PrincipalTag` (attribute-based access control) instead of creating one role per workspace.

The following tags are sent as transitive principal tags:

- `terrakube:org` - organization name
- `terrakube:workspace` - workspace name
- `terrakube:project` - project name (only when the workspace belongs to a project)

Two things are required in the role trust policy:

- The `sts:TagSession` action must be allowed, otherwise the assume call fails.
- A `ForAllValues:StringEquals` condition on `aws:TagKeys` restricts which tag keys are accepted. This prevents Terrakube from setting any tag key other than the ones listed. The `main.tf` in this folder already applies this guardrail:

The `main.tf` in this folder uses a wildcard `sub` so a single role serves every workspace of the organization, and relies on the session tag to scope access per workspace:

```json
"Action": [
  "sts:AssumeRoleWithWebIdentity",
  "sts:TagSession"
],
"Condition": {
  "StringEquals": {
    "terrakube-api.mydomain.com:aud": "aws.workload.identity"
  },
  "StringLike": {
    "terrakube-api.mydomain.com:sub": "organization:my-org:workspace:*"
  },
  "ForAllValues:StringEquals": {
    "aws:TagKeys": [
      "terrakube:org",
      "terrakube:workspace",
      "terrakube:project"
    ]
  }
}
```

Once the tags are set, you can reference them in permission policies. For example, this policy (used in `main.tf`) allows access only to the S3 prefix that matches the caller workspace:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": "arn:aws:s3:::my-terrakube-bucket/${aws:PrincipalTag/terrakube:workspace}/*"
    }
  ]
}
```

You can also restrict the accepted tag values (not only the keys) with `aws:RequestTag/<key>` in the trust policy if you want to pin a role to a specific organization or workspace.