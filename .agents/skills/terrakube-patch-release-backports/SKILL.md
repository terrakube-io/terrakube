---
name: terrakube-patch-release-backports
description: End-to-end workflow for discovering, validating, cherry-picking, testing, and opening backport pull requests from main to Terrakube release branches (release/<major>.<minor>.X) for patch milestones.
---

# Terrakube Patch Release Backport Workflow

This skill documents the standard operational procedure for backporting merged bugfix pull requests from `main` to a maintenance release branch (e.g., `release/2.34.X`) for a patch milestone (e.g., `2.34.3`).

---

## 1. Release Branch & Tag Conventions

In Terrakube repositories:
* **Release Branches:** Track maintenance releases using `.X` notation: `release/<major>.<minor>.X` (e.g., `release/2.33.X`, `release/2.34.X`).
* **Release Tags:** Mark specific releases using full semver: `<major>.<minor>.<patch>` (e.g., `2.34.0`, `2.34.2`, `2.34.0-rc.1`).
* **Base Target:** All backport branches must branch off `origin/release/<major>.<minor>.X` and submit pull requests targeting base branch `release/<major>.<minor>.X`. NEVER attempt to target or branch from a tag.

---

## 2. Milestone Discovery & Pull Request Qualification

Query the GitHub API to fetch all items associated with the target milestone:

```bash
curl -s "https://api.github.com/repos/terrakube-io/terrakube/issues?milestone=<milestone_id>&state=all&per_page=100"
```

### Qualification Criteria:
1. **Pull Requests Only:** Item contains the `pull_request` key. Ignore issues (even if closed).
2. **Merged to Main:** The PR details (`/pulls/<pr_number>`) must confirm `merged == true` and `base.ref == "main"`.
3. **Skip Open PRs:** Any PR with `state == "open"` or `merged == false` must be deferred until it merges to `main`.
4. **Identify Commit SHA:** Extract the merge commit (`merge_commit_sha` or the single squash commit on `main`).

---

## 3. Pre-Flight Cherry-Pick Audit

Before creating a backport branch, verify whether the commit was already cherry-picked onto the release branch:

```bash
git log origin/release/<major>.<minor>.X --grep="#<pr_number>" --oneline
```

If a commit already exists on `release/<major>.<minor>.X` with `(cherry picked from commit <sha>)` or references the PR number:
* **Skip backporting** that PR.
* Document that the fix is already present in the release branch.

---

## 4. Backport Execution Workflow

For each qualified commit:

### Step 1: Create Dedicated Backport Branch
Always scope git commands to the `terrakube` repository subfolder:
```bash
git checkout -B backport/<pr_number>-<slug>-<major>.<minor>.X origin/release/<major>.<minor>.X
```

### Step 2: Cherry-Pick with Origin Traceability
Always pass `-x` to append `(cherry picked from commit <sha>)` to the commit message:
```bash
git cherry-pick -x <commit_sha>
```

### Step 3: Run Targeted Component Tests
* **UI Changes:**
  ```bash
  yarn --cwd ui test <TestFile>.test.tsx --watchAll=false
  ```
* **API / Executor Changes:**
  Execute targeted unit tests using Maven inside devcontainer or test environment.

### Step 4: Push Branch to Origin
```bash
git push -u origin backport/<pr_number>-<slug>-<major>.<minor>.X
```

### Step 5: Create Pull Request via GitHub CLI
```bash
gh pr create \
  --repo terrakube-io/terrakube \
  --base release/<major>.<minor>.X \
  --head backport/<pr_number>-<slug>-<major>.<minor>.X \
  --milestone "<milestone_name>" \
  --title "[Backport <major>.<minor>] <original_title>" \
  --body "Backport of #<original_pr_number> to release/<major>.<minor>.X.\n\nFixes #<issue_number>."
```

### Step 6: Restore Local Git State
```bash
git checkout <original_branch>
```
