// Run with: node --test .github/scripts/
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { computeLabels, descriptionIsEmpty, sizeLabel, typeLabel } = require('./pr-labels.js');

const TEMPLATE = `## What changed and why?\n<!-- x -->\n## Testing\n<!-- req -->\n\`\`\`text\nRan:\nChecked:\n\`\`\`\n## Visual changes\n`;
const f = (filename, additions = 5, deletions = 0) => ({ filename, additions, deletions });
const base = { title: 'fix(api): thing', body: 'Fixes a real problem in the lock.\n## Testing\n```text\nRan: mvn test\n```', login: 'alice', association: 'NONE', draft: false };

test('untouched template counts as empty, filled one does not', () => {
  assert.equal(descriptionIsEmpty(TEMPLATE), true);
  assert.equal(descriptionIsEmpty(base.body), false);
});

test('size ignores lockfiles', () => {
  assert.equal(sizeLabel([f('ui/yarn.lock', 5000), f('a.java', 3)]), 'size/XS');
  assert.equal(sizeLabel([f('a.java', 600, 500)]), 'size/XL');
});

test('title types', () => {
  assert.equal(typeLabel('feat(ui)!: x'), 'type/feature');
  assert.equal(typeLabel('random title'), null);
  assert.equal(typeLabel('Bump x', 'dependabot[bot]'), 'type/deps');
});

test('maintainer touching scheduler gets priority/high, drafts do not', () => {
  const files = [f('api/src/main/java/io/terrakube/api/plugin/scheduler/A.java'), f('api/src/test/java/ATest.java')];
  const pr = { ...base, association: 'MEMBER' };
  const r = computeLabels(pr, files, ['area/scheduler']);
  assert.ok(r.add.includes('priority/high') && r.add.includes('author/maintainer'));
  assert.ok(!computeLabels({ ...pr, draft: true }, files, ['area/scheduler']).add.includes('priority/high'));
});

test('community member in a risky area is not auto-prioritised', () => {
  const r = computeLabels(base, [f('api/src/test/X.java')], ['area/security']);
  assert.ok(!r.add.includes('priority/high'));
});

test('bug fix linked to a bug issue is prioritised; needs-tests when no tests', () => {
  const files = [f('api/src/main/java/io/terrakube/api/Foo.java')];
  const r = computeLabels(base, files, [], { linkedIssueLabels: ['bug'] });
  assert.ok(r.add.includes('priority/high') && r.add.includes('needs-tests'));
});

test('stale owned labels are removed, manual labels are kept', () => {
  const r = computeLabels(base, [f('api/src/test/X.java')], ['size/XL', 'needs-tests', 'help wanted', 'priority/high']);
  assert.deepEqual(r.remove.sort(), ['needs-tests', 'priority/high', 'size/XL']);
});

test('bots are labelled but not nagged', () => {
  const r = computeLabels({ ...base, login: 'dependabot[bot]', association: 'CONTRIBUTOR', title: 'deps: bump x', body: '' }, [f('api/pom.xml')], ['security']);
  assert.ok(r.add.includes('author/bot') && r.add.includes('priority/high'));
  assert.ok(!r.add.includes('needs-description'));
});

test('breaking change', () => {
  assert.ok(computeLabels({ ...base, title: 'feat(api)!: drop x' }, [f('a.md')], []).add.includes('breaking-change'));
});
