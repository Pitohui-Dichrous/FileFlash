/* Real HTTP integration against the same JVM transport shipped in the APK.
 * Run after gradlew :core:installDist. No browser/device claims are made here. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const root = path.resolve(__dirname, '..');
fs.mkdirSync(path.join(root, '.test-data'), { recursive: true });
const work = fs.mkdtempSync(path.join(root, '.test-data', 'large-'));
// Freeze the runtime for this invocation: a parallel APK build may replace installDist JARs.
const runtime = path.join(work, 'runtime');
fs.cpSync(path.join(root, 'core/build/install/core/lib'), runtime, { recursive: true });
const reportDir = path.join(root, '.test-data', 'reports');
fs.mkdirSync(reportDir, { recursive: true });
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java';
const chunkSize = 2 * 1024 * 1024;
const size = process.env.FILEFLASH_TEST_BYTES ? Number(process.env.FILEFLASH_TEST_BYTES) : 4 * 1024 ** 3 + 65539;
const block = crypto.randomBytes(chunkSize);
const blockSha = crypto.createHash('sha256').update(block).digest('hex');
let proc, base, session, ready, peakRss = 0, passed = false, networkRetries = 0;
const started = Date.now();
const checks = [];
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function start() {
  proc = spawn(java, ['-Xmx128m', '-cp', path.join(runtime, '*'), 'app.fileflash.core.DesktopServer', '--storage', work, '--assets', path.join(root, 'app/src/main/assets/web'), '--port', '0'], { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '', err = '';
  ready = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Server readiness timeout: ' + err)), 20000);
    proc.stderr.on('data', d => { err += d; fs.appendFileSync(path.join(work, 'server-error.log'), d); });
    proc.stdout.on('data', d => { out += d; const nl = out.indexOf('\n'); if (nl >= 0) { clearTimeout(timer); try { resolve(JSON.parse(out.slice(0, nl))); } catch(e) { reject(e); } } });
    proc.once('exit', code => { clearTimeout(timer); reject(Error('Server exited ' + code + ': ' + err)); });
  });
  base = ready.url;
  const pair = await api('POST', '/api/pair', { token: ready.pairToken, name: 'Integration' }, {}, false, 201);
  session = pair.session;
}
async function stop() { if (!proc || proc.exitCode != null) return; const done = once(proc, 'exit'); proc.kill('SIGKILL'); await done; }
async function api(method, route, body, headers = {}, auth = true, expected = 200) {
  const binary = Buffer.isBuffer(body);
  const request = () => fetch(base + route, { method, headers: { ...(auth ? { Authorization: 'Bearer ' + session } : {}), ...(body !== undefined ? { 'Content-Type': binary ? 'application/octet-stream' : 'application/json' } : {}), ...headers }, body: body === undefined ? undefined : binary ? body : JSON.stringify(body), signal: AbortSignal.timeout(90000) });
  let res;
  try { res = await request(); }
  catch (error) {
    // Only an identical, checksummed PUT can be replayed safely after a lost connection.
    // Keep a small explicit retry budget; a persistent failure still fails this run.
    if (method !== 'PUT' || networkRetries >= 3) throw new Error(method + ' ' + route + ' network failure', { cause: error });
    networkRetries++;
    console.log('Transient PUT connection failure; replaying identical chunk (' + networkRetries + '/3)');
    await new Promise(resolve => setTimeout(resolve, 500));
    res = await request();
  }
  const json = await res.json();
  assert.equal(res.status, expected, `${method} ${route} status ${res.status}: ${JSON.stringify(json)}`);
  return json;
}
async function main() {
  assert(Number.isSafeInteger(size) && size > 0);
  await start();
  await api('GET', '/api/state', undefined, {}, false, 401); checks.push('unauthenticated state rejected');
  const fullHasher = crypto.createHash('sha256');
  for (let offset = 0; offset < size; offset += chunkSize) fullHasher.update(block.subarray(0, Math.min(chunkSize, size - offset)));
  const sha256 = fullHasher.digest('hex');
  let upload = await api('POST', '/api/uploads', { name: '大文件 🧪 + #.bin', size, sha256, mime: 'application/octet-stream' }, {}, true, 201);
  const id = upload.id, key = upload.resumeKey;
  const headers = { 'X-Upload-Key': key };
  let offset = 0;
  async function send() {
    const chunk = block.subarray(0, Math.min(chunkSize, size - offset));
    upload = await api('PUT', '/api/uploads/' + id, chunk, { ...headers, 'X-Offset': String(offset), 'X-Chunk-SHA256': chunk.length === chunkSize ? blockSha : hash(chunk) });
    offset += chunk.length;
    assert.equal(upload.offset, offset);
    peakRss = Math.max(peakRss, process.memoryUsage().rss);
  }
  await send();
  const duplicate = await api('PUT', '/api/uploads/' + id, block.subarray(0, Math.min(chunkSize, size)), { ...headers, 'X-Offset': '0', 'X-Chunk-SHA256': size >= chunkSize ? blockSha : hash(block.subarray(0, size)) });
  assert.equal(duplicate.offset, offset); checks.push('lost-response duplicate chunk idempotent');
  if (offset < size) {
    await api('PUT', '/api/uploads/' + id, block.subarray(0, 17), { ...headers, 'X-Offset': String(offset), 'X-Chunk-SHA256': '0'.repeat(64) }, true, 422);
    await api('POST', '/api/uploads/' + id + '/complete', { sha256 }, headers, true, 409);
    checks.push('damaged chunk and premature completion rejected');
    for (let i = 0; i < 4 && offset < size; i++) await send();
    const oldSession = session;
    await stop();
    fs.appendFileSync(path.join(work, 'uploads', id + '.part'), Buffer.from('uncommitted crash tail'));
    await start();
    await api('GET', '/api/state', undefined, { Authorization: 'Bearer ' + oldSession }, false, 401);
    await api('GET', '/api/uploads/' + id, undefined, {}, true, 403);
    const recovered = await api('GET', '/api/uploads/' + id, undefined, headers);
    assert.equal(recovered.offset, offset);
    assert.equal(fs.statSync(path.join(work, 'uploads', id + '.part')).size, offset);
    checks.push('hard process restart: stale auth rejected, recovery key required, uncommitted tail truncated');
  }
  while (offset < size) {
    await send();
    if (offset % (256 * 1024 ** 2) === 0) console.log('Transferred ' + (offset / 1024 ** 2) + ' MiB');
  }
  const prefix = await api('GET', '/api/uploads/' + id + '/prefix', undefined, headers);
  assert.equal(prefix.offset, size); assert.equal(prefix.size, size); assert.equal(prefix.prefixSha256, sha256);
  checks.push('committed-prefix recovery SHA-256 beyond 4GiB matches streamed source');
  upload = await api('POST', '/api/uploads/' + id + '/complete', { sha256 }, headers);
  assert.equal(upload.status, 'complete'); assert.equal(upload.sha256, sha256);
  checks.push('complete upload SHA-256 verified by server');
  const state = await api('GET', '/api/state');
  const file = state.files.find(f => f.sha256 === sha256 && f.size === size);
  assert(file, 'completed file must be listed');
  const rangeStart = Math.max(0, size - 64), rangeEnd = size - 1;
  const ranged = await fetch(base + '/api/files/' + file.id, { headers: { Authorization: 'Bearer ' + session, Range: `bytes=${rangeStart}-${rangeEnd}` } });
  assert.equal(ranged.status, 206);
  assert.equal(ranged.headers.get('content-range'), `bytes ${rangeStart}-${rangeEnd}/${size}`);
  const expectedRange = Buffer.alloc(rangeEnd - rangeStart + 1);
  for (let i = 0; i < expectedRange.length; i++) expectedRange[i] = block[(rangeStart + i) % chunkSize];
  assert.deepEqual(Buffer.from(await ranged.arrayBuffer()), expectedRange);
  checks.push('HTTP Range offset ' + rangeStart + ' verified byte-for-byte');
  const ticket = await api('POST', '/api/files/' + file.id + '/ticket', {});
  let received = 0;
  const downloadHasher = crypto.createHash('sha256');
  await new Promise((resolve, reject) => {
    http.get(base + ticket.url, res => {
      assert.equal(res.statusCode, 200); assert.equal(Number(res.headers['content-length']), size);
      res.on('data', data => { received += data.length; downloadHasher.update(data); peakRss = Math.max(peakRss, process.memoryUsage().rss); });
      res.on('end', resolve); res.on('error', reject); res.on('aborted', () => reject(Error('Download aborted')));
    }).on('error', reject);
  });
  assert.equal(received, size); assert.equal(downloadHasher.digest('hex'), sha256);
  checks.push('full streamed download exact length and SHA-256');
  const text = '中文 English 😀\n第二行 <>& # +\n' + '长文字'.repeat(3000);
  await api('POST', '/api/texts', { text }, {}, true, 201);
  assert((await api('GET', '/api/state')).texts.some(t => t.text === text)); checks.push('Unicode multiline long text roundtrip');
  const report = { passed: true, bytes: size, sha256, checks, networkRetries, javaMaxHeapMiB: 128, nodePeakRssMiB: Math.round(peakRss / 1024 ** 2), elapsedSeconds: Math.round((Date.now() - started) / 1000), environment: 'Windows JVM real HTTP; not Android/iOS device validation' };
  fs.writeFileSync(path.join(reportDir, 'large.json'), JSON.stringify(report, null, 2));
  passed = true;
  console.log(JSON.stringify(report, null, 2));
}
main().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => {
  await stop();
  // Delete only the fresh directory created by this invocation, after the server exits.
  if (passed && process.env.FILEFLASH_KEEP_TEST_DATA !== '1' && path.dirname(path.resolve(work)) === path.join(root, '.test-data') && /^large-[A-Za-z0-9]+$/.test(path.basename(work))) fs.rmSync(work, { recursive: true, force: true });
});
