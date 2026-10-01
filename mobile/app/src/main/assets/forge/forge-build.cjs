'use strict';
// This client only requests the Android service; no compiler or Gradle runs in the guest.
const http = require('node:http');
const path = require('node:path');
const args = process.argv.slice(2);
let offline = false;
let project = '.';
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--offline') offline = true;
  else if (args[i] === '--project' && args[i + 1]) project = args[++i];
  else { console.error('Usage: node /pocket-bridge/forge-build.cjs [--project relative/path] [--offline]'); process.exit(2); }
}
const endpoint = process.env.FORGE_BUILD_URL;
const token = process.env.FORGE_BUILD_TOKEN;
if (!endpoint || !token) { console.error('No active Forge native build session.'); process.exit(2); }
const target = new URL(endpoint);
if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' || target.pathname !== '/v1/build') {
  console.error('Invalid Forge build endpoint.'); process.exit(2);
}
if (path.isAbsolute(project) || project.split(/[\\/]/).includes('..')) {
  console.error('Project path must be relative to the session workspace.'); process.exit(2);
}
const body = JSON.stringify({ project, offline });
const request = http.request(target, {
  method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
}, response => {
  const chunks = []; let size = 0;
  response.on('data', chunk => {
    size += chunk.length;
    if (size > 2 * 1024 * 1024) { request.destroy(new Error('Build response exceeds limit')); return; }
    chunks.push(chunk);
  });
  response.on('end', () => {
    try {
      const result = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      console.log(JSON.stringify(result, null, 2));
      process.exitCode = response.statusCode === 200 && result.state === 'SUCCEEDED' ? 0 : 1;
    } catch (_) { console.error('Invalid native build response.'); process.exitCode = 2; }
  });
});
request.setTimeout(11 * 60 * 1000, () => request.destroy(new Error('Native build timed out')));
request.on('error', error => { console.error(`Native build request failed: ${error.message}`); process.exitCode = 2; });
request.end(body);
