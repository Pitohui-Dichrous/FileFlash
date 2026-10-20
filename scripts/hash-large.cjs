// Exercise the browser's real incremental SHA implementation beyond 32-bit byte/bit counters.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const SHA256 = require('../app/src/main/assets/web/sha256.js');
const size = 4 * 1024 ** 3 + 65539;
const chunk = Buffer.alloc(2 * 1024 ** 2, 0xA7);
const expected = crypto.createHash('sha256'), actual = new SHA256();
const start = Date.now();
for (let offset = 0; offset < size; offset += chunk.length) {
  const data = chunk.subarray(0, Math.min(chunk.length, size - offset));
  expected.update(data); actual.update(data);
  if (offset && offset % (512 * 1024 ** 2) === 0) console.log('Hashed ' + offset / 1024 ** 2 + ' MiB');
}
const digest = actual.hex();
assert.equal(digest, expected.digest('hex'));
const report = { passed: true, bytes: size, sha256: digest, elapsedSeconds: Math.round((Date.now() - start) / 1000), implementation: 'APK bundled browser sha256.js compared with Node crypto, bounded 2MiB input' };
const output = path.resolve(__dirname, '../.test-data/reports/browser-sha-large.json');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
