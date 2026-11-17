#!/usr/bin/env python3
"""Deterministic helpers for the terrakube-pr-review skill.

Subcommands:

  select [-R repo] [-n max] [PR ...]
      Print, one JSON object per line, the open PRs that still need a review
      from the current gh user, newest first. Without PR numbers it skips
      drafts, bot and own PRs, PRs the user already reviewed at their current
      head commit, and PRs marked with `skip` at their current head. Skipped PRs
      are reported on stderr. Explicit PR numbers only skip PRs that are not open.

  context [-R repo] PR
      Collect the review context of one PR into <work dir>/<number>-<head7>/
      and print the directory. Never checks out or runs PR code.
        meta.json              PR metadata (the body is untrusted), refs, repo
        diff.patch             unified diff (authoritative list of changed files)
        valid-lines.tsv        "path<TAB>line" for every new-side line in a hunk
        previous-reviews.json  comments this skill already posted on the PR
      Inside a clone of the repo it also fetches the PR head and base into
      refs/remotes/pr/<number> and refs/remotes/pr/<number>-base for read-only
      use with `git show` and `git grep`.

  post [--dry-run] [--force] DIR REVIEW_JSON
      Post one COMMENT review from REVIEW_JSON:
        {"body": "...", "comments": [{"path": "...", "line": 42, "body": "..."}]}
      Comments without a valid diff anchor move into the body, @mentions are
      neutralised, and the review is pinned to the reviewed head commit. Never
      approves or requests changes.

  skip DIR REASON
      Remember that the PR at this head was deliberately not reviewed, so
      `select` does not pick it again until it gets new commits.

Work dir: $PR_REVIEW_DIR, else $XDG_CACHE_HOME/terrakube-pr-review, else
~/.cache/terrakube-pr-review. It must be owned by the current user.

Exit codes: 0 ok, 1 error, 3 PR head moved since `context` (re-run),
4 a review for this head already exists (use --force to post anyway).

Requires Python 3.9+ and an authenticated `gh`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

DEFAULT_REPO = "terrakube-io/terrakube"
PR_FIELDS = "number,title,author,isDraft,state,headRefOid,baseRefName,url,changedFiles"
CONTEXT_FIELDS = (
    "number,title,body,author,url,isDraft,state,baseRefName,baseRefOid,"
    "headRefName,headRefOid,headRepositoryOwner,labels,additions,deletions,"
    "changedFiles,files"
)
MARKER_PREFIX = "<!-- terrakube-pr-review head="
HEAD_MOVED_EXIT = 3
ALREADY_POSTED_EXIT = 4

# Keep gh output plain and non-interactive regardless of the user's shell.
GH_ENV = {
    k: v
    for k, v in os.environ.items()
    if k not in {"GH_FORCE_TTY", "CLICOLOR_FORCE", "FORCE_COLOR"}
}
GH_ENV.update(
    {"GH_PAGER": "cat", "PAGER": "cat", "NO_COLOR": "1", "GH_PROMPT_DISABLED": "1"}
)


class CommandError(RuntimeError):
    def __init__(self, cmd: tuple[str, ...], code: int, stderr: str):
        super().__init__(f"command failed ({code}): {' '.join(cmd)}\n{stderr.strip()}")
        self.stderr = stderr


def run(*cmd: str, input_text: str | None = None, check: bool = True) -> str:
    result = subprocess.run(
        cmd,
        input=input_text,
        capture_output=True,
        text=True,
        check=False,
        env=GH_ENV,
    )
    if check and result.returncode != 0:
        raise CommandError(cmd, result.returncode, result.stderr)
    return result.stdout


def gh_json(*args: str):
    return json.loads(run("gh", *args))


def current_user() -> str:
    return run("gh", "api", "user", "--jq", ".login").strip()


def work_root() -> Path:
    if os.environ.get("PR_REVIEW_DIR"):
        root = Path(os.environ["PR_REVIEW_DIR"])
    else:
        cache = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
        root = Path(cache) / "terrakube-pr-review"
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = root.lstat()
    if stat.S_ISLNK(info.st_mode) or info.st_uid != os.getuid():
        sys.exit(
            f"refusing to use {root}: it must be a directory owned by the current user"
        )
    return root


def summary(pr: dict) -> dict:
    return {
        "number": pr["number"],
        "title": pr["title"],
        "author": pr["author"]["login"],
        "headRefOid": pr["headRefOid"],
        "baseRefName": pr["baseRefName"],
        "changedFiles": pr["changedFiles"],
        "url": pr["url"],
    }


def own_reviews(repo: str, number: int, me: str) -> list[dict]:
    out = run(
        "gh",
        "api",
        "--paginate",
        f"repos/{repo}/pulls/{number}/reviews",
        "--jq",
        f'.[] | select(.user.login == "{me}") | {{id, commit_id, state, body}}',
    )
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def has_marker(review: dict, head: str | None = None) -> bool:
    marker = MARKER_PREFIX + (head or "")
    return marker in (review.get("body") or "")


def is_real_review(review: dict, head: str) -> bool:
    """A submitted review on head; thread replies (empty body) and pending drafts do not count."""
    if review["commit_id"] != head or review.get("state") == "PENDING":
        return False
    return (
        has_marker(review)
        or review.get("state") in {"APPROVED", "CHANGES_REQUESTED"}
        or bool((review.get("body") or "").strip())
    )


# --- select -----------------------------------------------------------------


def load_skipped(root: Path) -> dict:
    path = root / "skipped.json"
    return json.loads(path.read_text()) if path.exists() else {}


def cmd_select(args: argparse.Namespace) -> int:
    if args.numbers:
        for number in args.numbers:
            pr = gh_json(
                "pr", "view", str(number), "-R", args.repo, "--json", PR_FIELDS
            )
            if pr["state"] != "OPEN":
                print(f"skip #{number}: {pr['state'].lower()}", file=sys.stderr)
                continue
            print(json.dumps(summary(pr)))
        return 0

    me = current_user()
    skipped = load_skipped(work_root())
    prs = gh_json(
        "pr",
        "list",
        "-R",
        args.repo,
        "--state",
        "open",
        "--limit",
        str(args.limit),
        "--json",
        PR_FIELDS,
    )
    if len(prs) >= args.limit:
        print(
            f"note: listed only the newest {args.limit} open PRs (raise --limit)",
            file=sys.stderr,
        )

    picked = 0
    for pr in sorted(prs, key=lambda p: p["number"], reverse=True):
        number, author, head = pr["number"], pr["author"], pr["headRefOid"]
        skip_key = f"{args.repo}#{number}@{head}"
        reason = None
        if pr["isDraft"]:
            reason = "draft"
        elif author.get("is_bot"):
            reason = f"bot author {author['login']}"
        elif author["login"] == me:
            reason = "own PR"
        elif skip_key in skipped:
            reason = f"marked skip at {head[:7]}: {skipped[skip_key]}"
        elif picked >= args.max:
            reason = f"over the per-run limit of {args.max} (rerun or pass -n)"
        elif any(is_real_review(r, head) for r in own_reviews(args.repo, number, me)):
            reason = f"already reviewed by {me} at {head[:7]}"
        if reason:
            print(f"skip #{number}: {reason}", file=sys.stderr)
            continue
        print(json.dumps(summary(pr)))
        picked += 1
    return 0


# --- context ----------------------------------------------------------------

HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@")


def unquote_path(raw: str) -> str:
    """Strip the b/ prefix and git's C-style quoting from a +++ header path."""
    if raw.startswith('"') and raw.endswith('"'):
        raw = raw[1:-1].encode("latin-1", "backslashreplace").decode("unicode_escape")
        raw = raw.encode("latin-1").decode("utf-8", "replace")
    return raw.removeprefix("b/")


def valid_comment_lines(diff: str) -> list[tuple[str, int]]:
    """New-side lines that GitHub accepts for inline comments (side RIGHT)."""
    lines: list[tuple[str, int]] = []
    path: str | None = None
    in_hunk = False
    new_line = 0
    for text in diff.splitlines():
        if text.startswith("diff --git "):
            path, in_hunk = None, False
            continue
        # File headers are only read before the first hunk of a file, so an
        # added line that starts with "++" is never taken for a header.
        if not in_hunk and text.startswith("+++ "):
            raw = text[4:].rstrip("\t")
            path = None if raw == "/dev/null" else unquote_path(raw)
            continue
        match = HUNK_RE.match(text)
        if match:
            new_line = int(match.group(1))
            in_hunk = True
            continue
        if not in_hunk or path is None:
            continue
        if text.startswith(("+", " ")) or text == "":
            lines.append((path, new_line))
            new_line += 1
        # "-" lines and "\ No newline at end of file" do not advance the new side.
    return lines


def matching_remote(repo: str) -> str | None:
    """Name of a git remote in the current directory that points at repo."""
    out = run("git", "remote", "-v", check=False)
    pattern = re.compile(
        rf"github\.com[:/]{re.escape(repo)}(?:\.git)?/?$", re.IGNORECASE
    )
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and pattern.search(parts[1]):
            return parts[0]
    return None


def fetch_ref(remote: str, source: str, ref: str, expected: str) -> bool:
    # --no-prune: with fetch.prune=true git would delete the ref it just wrote.
    run(
        "git",
        "fetch",
        "-q",
        "--force",
        "--no-prune",
        remote,
        f"{source}:{ref}",
        check=False,
    )
    return (
        run("git", "rev-parse", "--verify", "-q", ref, check=False).strip() == expected
    )


def previous_reviews(repo: str, number: int, me: str) -> dict:
    """Reviews this skill already posted (bodies hold out-of-diff findings) and their comments."""
    reviews = [r for r in own_reviews(repo, number, me) if has_marker(r)]
    if not reviews:
        return {"reviews": [], "comments": []}
    ids = {r["id"] for r in reviews}
    out = run(
        "gh",
        "api",
        "--paginate",
        f"repos/{repo}/pulls/{number}/comments",
        "--jq",
        ".[] | {review: .pull_request_review_id, path, line, original_line, body}",
    )
    comments = [json.loads(line) for line in out.splitlines() if line.strip()]
    return {
        "reviews": [
            {"id": r["id"], "commit_id": r["commit_id"], "body": r["body"]}
            for r in reviews
        ],
        "comments": [c for c in comments if c["review"] in ids],
    }


def cmd_context(args: argparse.Namespace) -> int:
    meta = gh_json(
        "pr", "view", str(args.number), "-R", args.repo, "--json", CONTEXT_FIELDS
    )
    head, base = meta["headRefOid"], meta["baseRefOid"]
    out_dir = work_root() / f"{args.number}-{head[:7]}"
    out_dir.mkdir(mode=0o700, exist_ok=True)

    meta["repo"] = args.repo
    meta["localRef"] = meta["localBaseRef"] = None
    remote = matching_remote(args.repo)
    if remote:
        if fetch_ref(
            remote, f"pull/{args.number}/head", f"refs/remotes/pr/{args.number}", head
        ):
            meta["localRef"] = f"pr/{args.number}"
        if fetch_ref(remote, base, f"refs/remotes/pr/{args.number}-base", base):
            meta["localBaseRef"] = f"pr/{args.number}-base"
    if not (meta["localRef"] and meta["localBaseRef"]):
        print(
            f"warning: PR head/base of #{args.number} not available locally; "
            "reviewers fall back to the GitHub contents API",
            file=sys.stderr,
        )

    try:
        diff = run(
            "gh", "pr", "diff", str(args.number), "-R", args.repo, "--color=never"
        )
    except CommandError as err:
        # GitHub refuses the diff of very large PRs (HTTP 406); build it locally.
        if not (meta["localRef"] and meta["localBaseRef"]):
            raise
        print(
            f"note: gh pr diff failed, using git diff instead ({err.stderr.strip()[:120]})",
            file=sys.stderr,
        )
        toplevel = run("git", "rev-parse", "--show-toplevel").strip()
        diff = run(
            "git",
            "-C",
            toplevel,
            "-c",
            "diff.noprefix=false",
            "-c",
            "diff.relative=false",
            "diff",
            "--no-color",
            "--no-ext-diff",
            "--no-textconv",
            "-U3",
            "-M",
            "--src-prefix=a/",
            "--dst-prefix=b/",
            f"{meta['localBaseRef']}...{meta['localRef']}",
        )

    (out_dir / "diff.patch").write_text(diff)
    (out_dir / "valid-lines.tsv").write_text(
        "".join(f"{path}\t{line}\n" for path, line in valid_comment_lines(diff))
    )
    (out_dir / "previous-reviews.json").write_text(
        json.dumps(previous_reviews(args.repo, args.number, current_user()), indent=2)
        + "\n"
    )
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    print(out_dir)
    return 0


# --- post -------------------------------------------------------------------

CODE_RE = re.compile(r"(```.*?```|`[^`\n]*`)", re.DOTALL)
MENTION_RE = re.compile(r"(?<![\w@])@(?=[A-Za-z0-9])")


MD_LINK_RE = re.compile(r"\[([^\]\n]*)\]\((https?://[^)\s]+)\)")
URL_RE = re.compile(r"https?://[^\s<>()`]*[^\s<>()`.,;:!?'\"]")
ALLOWED_URL_PREFIXES = (
    "https://github.com/terrakube-io/",
    "https://docs.terrakube.io/",
    "https://terrakube.io/",
)


def allowed_url(url: str) -> bool:
    return url.startswith(ALLOWED_URL_PREFIXES)


def defuse_prose(text: str) -> str:
    """Neutralise @mentions and unlink off-site URLs in prose."""
    text = MD_LINK_RE.sub(
        lambda m: (
            m.group(0) if allowed_url(m.group(2)) else f"{m.group(1)} (`{m.group(2)}`)"
        ),
        text,
    )
    text = "".join(
        part
        if i % 2
        else URL_RE.sub(
            lambda m: m.group(0) if allowed_url(m.group(0)) else f"`{m.group(0)}`", part
        )
        for i, part in enumerate(CODE_RE.split(text))
    )
    return MENTION_RE.sub("@\u200b", text)


def neutralise_mentions(text: str) -> str:
    """Make posted text inert: no @mentions and no off-site links; code is untouched."""
    parts = CODE_RE.split(text)
    return "".join(
        part if i % 2 else defuse_prose(part) for i, part in enumerate(parts)
    )


def anchor(comment: dict) -> tuple[str, int] | None:
    path, line = comment.get("path"), comment.get("line")
    if not isinstance(path, str) or not path:
        return None
    try:
        return path, int(line)
    except (TypeError, ValueError):
        return None


def render_outside(comments: list[dict]) -> str:
    sections = []
    for c in comments:
        key = anchor(c)
        where = f"{key[0]}:{key[1]}" if key else (c.get("path") or "general")
        body = str(c.get("body", "")).replace("```suggestion", "```")
        sections.append(f"#### `{where}`\n\n{body}")
    return "\n\n**Outside the diff**\n\n" + "\n\n".join(sections)


def build_payload(review: dict, valid: set[tuple[str, int]], head: str) -> dict:
    if not isinstance(review.get("body"), str):
        raise SystemExit("review JSON needs a string 'body'")
    inline, outside = [], []
    for comment in review.get("comments") or []:
        (inline if anchor(comment) in valid else outside).append(comment)

    body = review["body"].rstrip()
    if outside:
        body += render_outside(outside)
    return {
        "commit_id": head,
        "event": "COMMENT",
        "body": neutralise_mentions(body) + f"\n\n{MARKER_PREFIX}{head} -->",
        "comments": [
            {
                "path": c["path"],
                "line": int(c["line"]),
                "side": "RIGHT",
                "body": neutralise_mentions(str(c.get("body", ""))),
            }
            for c in inline
        ],
    }


def post_review(endpoint: str, payload: dict) -> str:
    return run(
        "gh",
        "api",
        "--method",
        "POST",
        endpoint,
        "--input",
        "-",
        "--jq",
        ".html_url",
        input_text=json.dumps(payload),
    ).strip()


def cmd_post(args: argparse.Namespace) -> int:
    ctx = Path(args.dir)
    meta = json.loads((ctx / "meta.json").read_text())
    repo, number, head = meta["repo"], meta["number"], meta["headRefOid"]

    valid = set()
    for row in (ctx / "valid-lines.tsv").read_text().splitlines():
        path, line = row.rsplit("\t", 1)
        valid.add((path, int(line)))

    review = json.loads(Path(args.review).read_text())
    payload = build_payload(review, valid, head)
    if args.dry_run:
        print(json.dumps(payload, indent=2))
        return 0

    current = run(
        "gh",
        "pr",
        "view",
        str(number),
        "-R",
        repo,
        "--json",
        "headRefOid",
        "--jq",
        ".headRefOid",
    ).strip()
    if current != head:
        print(
            f"PR #{number} moved from {head[:7]} to {current[:7]} since the review started; "
            "not posting. Re-run the review.",
            file=sys.stderr,
        )
        return HEAD_MOVED_EXIT

    if not args.force:
        mine = own_reviews(repo, number, current_user())
        if any(has_marker(r, head) for r in mine):
            print(
                f"PR #{number} already has your review at {head[:7]}; not posting (use --force).",
                file=sys.stderr,
            )
            return ALREADY_POSTED_EXIT

    endpoint = f"repos/{repo}/pulls/{number}/reviews"
    try:
        url = post_review(endpoint, payload)
    except CommandError as err:
        if "pending review" in err.stderr.lower():
            print(
                f"PR #{number}: you have an unsubmitted (pending) review on GitHub; "
                "submit or delete it, then post again.",
                file=sys.stderr,
            )
            return 1
        if "422" not in err.stderr or not payload["comments"]:
            raise
        # GitHub rejects the whole review when one anchor is not acceptable:
        # retry once with every comment in the body.
        print(
            "note: inline comments were rejected (422); posting them in the body",
            file=sys.stderr,
        )
        fallback = {"body": review["body"], "comments": review.get("comments") or []}
        url = post_review(endpoint, build_payload(fallback, set(), head))
    print(url)
    return 0


# --- skip -------------------------------------------------------------------


def cmd_skip(args: argparse.Namespace) -> int:
    meta = json.loads((Path(args.dir) / "meta.json").read_text())
    root = work_root()
    skipped = load_skipped(root)
    skipped[f"{meta['repo']}#{meta['number']}@{meta['headRefOid']}"] = args.reason
    tmp = root / f"skipped.json.{os.getpid()}"
    tmp.write_text(json.dumps(skipped, indent=2) + "\n")
    os.replace(tmp, root / "skipped.json")
    return 0


# --- cli --------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("select", help="list PRs that need a review")
    p.add_argument("-R", "--repo", default=DEFAULT_REPO)
    p.add_argument("-n", "--max", type=int, default=5, help="PRs per run (default 5)")
    p.add_argument(
        "--limit", type=int, default=200, help="open PRs to scan (default 200)"
    )
    p.add_argument("numbers", nargs="*", type=int)
    p.set_defaults(func=cmd_select)

    p = sub.add_parser("context", help="collect the review context of one PR")
    p.add_argument("-R", "--repo", default=DEFAULT_REPO)
    p.add_argument("number", type=int)
    p.set_defaults(func=cmd_context)

    p = sub.add_parser("post", help="post one COMMENT review")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument(
        "--force",
        action="store_true",
        help="post even if a review for this head exists",
    )
    p.add_argument("dir")
    p.add_argument("review")
    p.set_defaults(func=cmd_post)

    p = sub.add_parser("skip", help="do not select this PR again until it changes")
    p.add_argument("dir")
    p.add_argument("reason")
    p.set_defaults(func=cmd_skip)

    args = parser.parse_args()
    try:
        return args.func(args)
    except CommandError as err:
        print(err, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
