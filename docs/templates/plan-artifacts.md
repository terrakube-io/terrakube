# Plan artifacts

Each step of a job runs in a fresh clone, usually on a different executor pod,
and the apply step applies the saved plan. A file the plan step built, such as a
Lambda zip from `data "archive_file"` or a before-script, is not in the apply
step's clone, so a resource that points at it fails with "no such file or
directory".

Plan artifacts copy those files from the plan step to the apply step.

Terraform reads a data source once, during plan, and `terraform apply <planfile>`
does not read it again. The provider opens the file a resource points at
(`filename = "build/function.zip"`) while applying, so the file has to exist there
with the same bytes the plan hashed. `path.module` and `path.root` are relative
paths and work in a new clone. `path.cwd` and `abspath()` put an absolute path in
the plan, which will not match.

Where you can, build the package once in CI and reference it by S3 key and hash.
Nothing then has to be carried.

## Choose the files

List globs on the plan step of a template:

```yaml
flow:
  - type: terraformPlan
    step: 100
    artifacts:
      - "build/*.zip"
  - type: terraformApply
    step: 200
```

or set the `TERRAKUBE_PLAN_ARTIFACTS` environment variable on a workspace or
organization. When it is set it replaces the template's list, and an empty value
turns the feature off for that workspace. Separate patterns with new lines or
commas.

```text
TERRAKUBE_PLAN_ARTIFACTS = dist/*.zip,layers/**
```

* Patterns are relative to the working directory and use Java glob syntax. `*`
  stays within a directory and `**` crosses them, so use `**/*.zip` for zips in
  any subdirectory.
* `${VAR}` is replaced from the job's environment variables. A pattern that uses a
  variable that is unset or empty is dropped.
* A pattern that matches no file fails the plan. Prefix it with `optional:` if that
  is expected.
* `.git`, `.terraform`, credential files, backend overrides, the plan file, the raw
  `plan.json` and policy inputs (`.terrakube-policies`, both can hold sensitive
  values) and symbolic links are never packaged. Write generated files somewhere else, for
  example `output_path = "${path.root}/build/report.zip"`. The archive provider
  creates the directory.

A workspace that sets neither is unaffected.

## What happens

1. After a plan with changes in a job that has a later step, the executor packs the
   matching files, stores them, and records the location and sha256 on the job.
   The plan output lists the files and the sha256. If the files cannot be saved,
   for example because a limit is exceeded or the storage is down, the plan fails.
2. The apply step downloads the bundle, checks the sha256 and unpacks it before
   anything else runs, then prints the sha256 it restored. A missing bundle or a
   mismatch fails the apply.
3. The bundle is deleted after a successful apply and when the job ends for any
   reason. Deleting a workspace deletes its bundles. A job the scheduler closes on
   its time limit does not trigger this, so add a lifecycle rule as well.

## Limits and storage

* `ExecutorArtifactsMaxBytes` (default 512 MiB) limits the total size of the files,
  and `ExecutorArtifactsMaxFiles` (default 10000) their number. These are the
  environment variables of the executor; the Spring properties are
  `io.terrakube.executor.artifacts.max-bytes` and `io.terrakube.executor.artifacts.max-files`.
* Bundles are streamed and never held in memory. Unpacking accepts only regular
  files and directories, never writes outside the clone, and keeps the executable
  bit.
* Bundles are stored at `plan-artifacts/<organization>/<workspace>/<job>/<step>/plan-artifacts.tar.gz`
  in the state bucket, container or directory, apart from state and plans.
* Give the executor read, write and delete on that prefix (S3: `s3:PutObject`,
  `s3:GetObject`, `s3:DeleteObject` on `<bucket>/plan-artifacts/*`). Without it the
  plan fails.
* Add a lifecycle rule that expires the prefix. Keep it longer than the longest
  approval wait, or the apply fails with "missing from the bucket" and the run
  needs a new plan.
* Watch usage with `terrakube.executor.artifacts{outcome}` (count) and
  `terrakube.executor.artifacts.bytes` (size of each bundle saved).
* With local storage, the apply only finds the bundle on another executor if
  `~/.terraform-spring-boot/local` is shared between them.
* A bundle can contain secrets from the build. Protect the bucket as you do for
  plan files.

## Upgrading

Upgrade the API first (it adds two nullable columns to `job`), then the executors,
which need `terrakube-spring-boot-starter` 1.11.0 or later. An executor that is
ahead of the API still works for every job that does not use the feature.
