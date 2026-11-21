// Computes the trust / size / type / hygiene / priority labels for a pull request.
//
// SECURITY: this runs under `pull_request_target` with a write token. It must only
// read PR metadata through the API. Never check out or execute code from the PR.
'use strict';

const OWNED_PREFIXES = ['author/', 'size/', 'type/'];
const OWNED_FLAGS = ['needs-tests', 'needs-description', 'breaking-change', 'priority/high'];

const HIGH_RISK_AREAS = ['area/security', 'area/db-migration', 'area/scheduler'];

// name -> [color, description]. Created on demand if the repository does not have them.
const LABELS = {
  'author/maintainer': ['0e8a16', 'Authored by a maintainer'],
  'author/contributor': ['1d76db', 'Authored by a returning contributor'],
  'author/first-time': ['7057ff', 'First contribution to this repository'],
  'author/bot': ['bfdadc', 'Authored by an automation bot'],
  'size/XS': ['c2e0c6', 'Fewer than 10 lines changed'],
  'size/S': ['c2e0c6', '10-49 lines changed'],
  'size/M': ['fbca04', '50-249 lines changed'],
  'size/L': ['f9a03f', '250-999 lines changed'],
  'size/XL': ['d93f0b', '1000 or more lines changed, consider splitting'],
  'type/feature': ['a2eeef', 'New functionality'],
  'type/fix': ['d73a4a', 'Bug fix'],
  'type/docs': ['0075ca', 'Documentation only'],
  'type/deps': ['0366d6', 'Dependency update'],
  'type/refactor': ['5319e7', 'Refactor, performance or style change'],
  'type/chore': ['ededed', 'Tests, CI, build or maintenance'],
  'needs-tests': ['e99695', 'Source changed without test changes'],
  'needs-description': ['e99695', 'PR description or testing section is empty'],
  'breaking-change': ['b60205', 'Contains a breaking change'],
  'priority/high': ['b60205', 'Review soon: high-risk area, bug fix or security update'],
  'area/db-migration': ['5319e7', 'Touches Liquibase changelogs'],
  'area/security': ['b60205', 'Touches authentication, tokens or authorization'],
  'area/scheduler': ['5319e7', 'Touches job scheduling, queueing or locking'],
  'area/rbac': ['5319e7', 'Touches teams and permissions'],
  'area/policy': ['5319e7', 'Touches OPA policy governance'],
  'area/notifications': ['5319e7', 'Touches notifications and webhooks'],
  'area/openapi': ['5319e7', 'Touches the OpenAPI spec'],
  'area/ci': ['ededed', 'Touches CI, containers or dev environment'],
};

const SIZE_IGNORED = [/(^|\/)yarn\.lock$/, /(^|\/)package-lock\.json$/, /\.lock$/, /(^|\/)openapi-spec\//, /\.snap$/];

function authorLabel(login, association) {
  if (/\[bot\]$/.test(login || '')) return 'author/bot';
  if (['OWNER', 'MEMBER', 'COLLABORATOR'].includes(association)) return 'author/maintainer';
  if (['FIRST_TIME_CONTRIBUTOR', 'FIRST_TIMER'].includes(association)) return 'author/first-time';
  if (association === 'CONTRIBUTOR') return 'author/contributor';
  return null;
}

function sizeLabel(files) {
  const lines = files
    .filter((f) => !SIZE_IGNORED.some((re) => re.test(f.filename)))
    .reduce((n, f) => n + f.additions + f.deletions, 0);
  if (lines < 10) return 'size/XS';
  if (lines < 50) return 'size/S';
  if (lines < 250) return 'size/M';
  if (lines < 1000) return 'size/L';
  return 'size/XL';
}

function typeLabel(title, login) {
  if (/^dependabot\[bot\]$|^renovate\[bot\]$/.test(login || '')) return 'type/deps';
  const m = /^(\w+)(?:\([^)]*\))?!?:/.exec(title || '');
  if (!m) return null;
  switch (m[1].toLowerCase()) {
    case 'feat': case 'feature': return 'type/feature';
    case 'fix': case 'hotfix': return 'type/fix';
    case 'docs': return 'type/docs';
    case 'deps': return 'type/deps';
    case 'refactor': case 'perf': case 'style': return 'type/refactor';
    case 'chore': case 'ci': case 'build': case 'test': case 'tests': return 'type/chore';
    default: return null;
  }
}

function isBreaking(title, body) {
  return /^\w+(\([^)]*\))?!:/.test(title || '') || /^BREAKING[ -]CHANGE:/m.test(body || '');
}

function descriptionIsEmpty(body) {
  const text = (body || '').replace(/<!--[\s\S]*?-->/g, '');
  const testing = /^##\s*Testing\s*$([\s\S]*?)(?=^##\s|(?![\s\S]))/im.exec(text);
  if (testing) {
    const filled = testing[1].replace(/```\w*/g, '').replace(/^\s*(Ran|Checked):/gim, '').trim();
    if (!filled) return true;
  }
  return text.replace(/^#+.*$/gm, '').replace(/```\w*/g, '').replace(/^\s*(Ran|Checked):/gim, '').trim().length < 20;
}

function lacksTests(files) {
  const isTest = (p) => /\/src\/test\//.test(p) || /\.(test|spec)\.[jt]sx?$/.test(p) || /(^|\/)__tests__\//.test(p);
  const isSource = (p) =>
    (/^(api|executor|registry)\/.*\/src\/main\/java\/.*\.java$/.test(p) || /^(api|executor|registry)\/src\/main\/java\/.*\.java$/.test(p)) ||
    /^ui\/src\/.*\.[jt]sx?$/.test(p);
  const names = files.map((f) => f.filename);
  return names.some(isSource) && !names.some(isTest);
}

/**
 * @param {object} pr   { title, body, login, association, draft }
 * @param {Array}  files [{ filename, additions, deletions }]
 * @param {string[]} currentLabels labels currently on the PR (after path labeling)
 * @param {object} extra { linkedIssueLabels: string[] }
 * @returns {{ add: string[], remove: string[] }}
 */
function computeLabels(pr, files, currentLabels, extra = {}) {
  const desired = new Set();
  const author = authorLabel(pr.login, pr.association);
  const type = typeLabel(pr.title, pr.login);
  const bot = author === 'author/bot';

  if (author) desired.add(author);
  desired.add(sizeLabel(files));
  if (type) desired.add(type);
  if (isBreaking(pr.title, pr.body)) desired.add('breaking-change');
  if (!bot && descriptionIsEmpty(pr.body)) desired.add('needs-description');
  if (!bot && (type === 'type/feature' || type === 'type/fix') && lacksTests(files)) desired.add('needs-tests');

  if (!pr.draft) {
    const highRisk = currentLabels.some((l) => HIGH_RISK_AREAS.includes(l));
    const fixesBug = type === 'type/fix' && (extra.linkedIssueLabels || []).includes('bug');
    const securityUpdate = bot && currentLabels.includes('security');
    if ((author === 'author/maintainer' && highRisk) || fixesBug || securityUpdate) desired.add('priority/high');
  }

  const owned = (l) => OWNED_PREFIXES.some((p) => l.startsWith(p)) || OWNED_FLAGS.includes(l);
  return {
    add: [...desired].filter((l) => !currentLabels.includes(l)),
    remove: currentLabels.filter((l) => owned(l) && !desired.has(l)),
  };
}

async function ensureLabels({ github, context }) {
  const { owner, repo } = context.repo;
  const existing = new Set((await github.paginate(github.rest.issues.listLabelsForRepo, { owner, repo, per_page: 100 })).map((l) => l.name));
  for (const [name, [color, description]] of Object.entries(LABELS)) {
    if (existing.has(name)) continue;
    try {
      await github.rest.issues.createLabel({ owner, repo, name, color, description });
    } catch (e) {
      if (e.status !== 422) throw e; // already exists (race)
    }
  }
}

const WELCOME_MARKER = '<!-- pr-labeler:welcome -->';

async function run({ github, context, core }) {
  const { owner, repo } = context.repo;
  const p = context.payload.pull_request;
  const number = p.number;
  const pr = { title: p.title, body: p.body, login: p.user.login, association: p.author_association, draft: p.draft };

  const files = await github.paginate(github.rest.pulls.listFiles, { owner, repo, pull_number: number, per_page: 100 });
  const current = (await github.paginate(github.rest.issues.listLabelsOnIssue, { owner, repo, issue_number: number, per_page: 100 })).map((l) => l.name);

  let linkedIssueLabels = [];
  try {
    const res = await github.graphql(
      `query($owner:String!,$repo:String!,$n:Int!){repository(owner:$owner,name:$repo){pullRequest(number:$n){closingIssuesReferences(first:10){nodes{labels(first:20){nodes{name}}}}}}}`,
      { owner, repo, n: number },
    );
    linkedIssueLabels = res.repository.pullRequest.closingIssuesReferences.nodes.flatMap((i) => i.labels.nodes.map((l) => l.name));
  } catch (e) {
    core.warning(`Could not read linked issues: ${e.message}`);
  }

  const { add, remove } = computeLabels(pr, files, current, { linkedIssueLabels });
  core.info(`add=${JSON.stringify(add)} remove=${JSON.stringify(remove)}`);
  if (add.length) await github.rest.issues.addLabels({ owner, repo, issue_number: number, labels: add });
  for (const name of remove) {
    try {
      await github.rest.issues.removeLabel({ owner, repo, issue_number: number, name });
    } catch (e) {
      if (e.status !== 404) throw e;
    }
  }

  if (context.payload.action === 'opened' && authorLabel(pr.login, pr.association) === 'author/first-time') {
    const comments = await github.paginate(github.rest.issues.listComments, { owner, repo, issue_number: number, per_page: 100 });
    if (!comments.some((c) => (c.body || '').includes(WELCOME_MARKER))) {
      await github.rest.issues.createComment({
        owner, repo, issue_number: number,
        body: `${WELCOME_MARKER}\nThanks for your first contribution to Terrakube, @${pr.login}! A maintainer will take a look. In the meantime, please check the [contributing guide](https://github.com/${owner}/${repo}/blob/main/CONTRIBUTING.md) and make sure the Testing section of the description says what you ran.`,
      });
    }
  }
}

module.exports = { computeLabels, authorLabel, sizeLabel, typeLabel, isBreaking, descriptionIsEmpty, lacksTests, ensureLabels, run, LABELS };
