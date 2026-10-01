'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const path = require('node:path');
const { spawn } = require('node:child_process');
const client = path.join(__dirname, '../app/src/main/assets/forge/forge-build.cjs');
function run(args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [client, ...args], { env: { ...process.env, FORGE_BUILD_URL: '', FORGE_BUILD_TOKEN: '', ...env } });
    let out = '', err = '';
    child.stdout.on('data', data => out += data); child.stderr.on('data', data => err += data);
    child.on('error', reject); child.on('exit', code => resolve({ code, out, err }));
  });
}
test('CLI sends scoped options and preserves structured failure without printing credentials', async () => {
  let seen;
  const server = http.createServer((request, response) => {
    let body = ''; request.on('data', data => body += data);
    request.on('end', () => {
      seen = { method: request.method, path: request.url, auth: request.headers.authorization, body: JSON.parse(body) };
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ state: 'FAILED', diagnostics: [{ file: '/workspace/app/src/Main.java', line: 9, message: 'Missing type' }], remainingBuilds: 3 }));
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const token = 'fixture-session-credential';
    const result = await run(['--project', 'app', '--offline'], { FORGE_BUILD_URL: `http://127.0.0.1:${server.address().port}/v1/build`, FORGE_BUILD_TOKEN: token });
    assert.equal(result.code, 1); assert.equal(seen.method, 'POST'); assert.equal(seen.path, '/v1/build');
    assert.equal(seen.auth, `Bearer ${token}`); assert.deepEqual(seen.body, { project: 'app', offline: true });
    assert.equal(JSON.parse(result.out).diagnostics[0].line, 9);
    assert.ok(!(result.out + result.err).includes(token));
  } finally { await new Promise(resolve => server.close(resolve)); }
});
test('CLI rejects missing session and directory traversal before network access', async () => {
  assert.equal((await run([], {})).code, 2);
  const result = await run(['--project', '../outside'], { FORGE_BUILD_URL: 'http://127.0.0.1:1/v1/build', FORGE_BUILD_TOKEN: 'fixture' });
  assert.equal(result.code, 2); assert.match(result.err, /relative/);
});
