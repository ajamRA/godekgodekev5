const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Writable } = require('node:stream');
const { streamBackup } = require('./backup-transfer');

async function fixture(t) {
    const dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), 'emas-transfer-'));
    t.after(() => fs.promises.rm(dir, { recursive: true, force: true }));
    return path.join(dir, 'synthetic.img');
}

function source(destination, script, expectedBytes, extra = {}) {
    return {
        command: process.execPath,
        args: ['-e', script],
        destination,
        expectedBytes,
        timeoutMs: 5000,
        idleTimeoutMs: 3000,
        sampleIntervalMs: 30,
        ...extra
    };
}

const trailer = "process.stdout.write('\\nEMAS_BACKUP_EXIT:0\\n');";

test('streams binary bytes unchanged, strips remote status and promotes only a complete image', async t => {
    const destination = await fixture(t);
    const samples = [];
    await streamBackup(source(destination,
        `process.stdout.write(Buffer.from([0, 10, 13, 255, 0])); ${trailer}`, 5,
        { onProgress: sample => samples.push(sample) }));
    assert.deepEqual(await fs.promises.readFile(destination), Buffer.from([0, 10, 13, 255, 0]));
    assert.equal(fs.existsSync(destination + '.partial'), false);
    assert.equal(samples[0].bytesTransferred, 0);
    assert.equal(samples[0].bytesPerSecond, null);
    assert.equal(samples.at(-1).bytesTransferred, 5);
});

test('samples actual throughput and reports zero, not stale throughput, during a stall', async t => {
    const destination = await fixture(t);
    const samples = [];
    const script = `process.stdout.write(Buffer.alloc(65536)); setTimeout(() => { process.stdout.write(Buffer.alloc(65536)); ${trailer} }, 450);`;
    await streamBackup(source(destination, script, 131072, { onProgress: sample => samples.push(sample) }));
    assert.ok(samples.some(s => s.bytesTransferred > 0 && s.bytesPerSecond > 0));
    assert.ok(samples.some(s => s.bytesTransferred === 65536 && s.bytesPerSecond === 0));
});

test('short reads retain partial bytes and never create a complete image', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination,
        `process.stdout.write(Buffer.alloc(8)); ${trailer}`, 64)), /byte count|status|short/i);
    assert.equal(fs.existsSync(destination), false);
    assert.ok((await fs.promises.stat(destination + '.partial')).size < 64);
});

test('excess binary bytes are rejected even with a successful status trailer', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination,
        `process.stdout.write(Buffer.alloc(65)); ${trailer}`, 64)), /status|excess/i);
    assert.equal(fs.existsSync(destination), false);
});

test('missing remote status on disconnect cannot become success after exactly the expected bytes', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination, 'process.stdout.write(Buffer.alloc(64));', 64)), /status/i);
    assert.equal(fs.existsSync(destination), false);
    assert.equal((await fs.promises.stat(destination + '.partial')).size, 64);
});

test('remote dd failure is rejected even when byte count matches and adb exits zero', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination,
        "process.stdout.write(Buffer.alloc(64)); process.stdout.write('\\nEMAS_BACKUP_EXIT:1\\n');", 64)), /remote.*1/i);
    assert.equal(fs.existsSync(destination), false);
});

test('local adb process failure is rejected even when byte count and remote status match', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination,
        `process.stdout.write(Buffer.alloc(64)); ${trailer} process.exitCode = 7;`, 64)), /process.*7/i);
    assert.equal(fs.existsSync(destination), false);
});

test('spawn failure retains a partial file without promoting it', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup({ ...source(destination, '', 64), command: path.join(path.dirname(destination), 'missing-binary') }), /ENOENT/);
    assert.equal(fs.existsSync(destination), false);
    assert.equal(fs.existsSync(destination + '.partial'), true);
});

test('inactivity timeout kills the source and retains the partial image', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination,
        'process.stdout.write(Buffer.alloc(64)); setInterval(() => {}, 1000);', 128,
        { idleTimeoutMs: 400, timeoutMs: 4000 })), /no transfer activity/i);
    assert.equal(fs.existsSync(destination), false);
    assert.ok((await fs.promises.stat(destination + '.partial')).size <= 64);
});

test('overall timeout also applies to a source that keeps sending bytes', async t => {
    const destination = await fixture(t);
    await assert.rejects(streamBackup(source(destination,
        'setInterval(() => process.stdout.write(Buffer.alloc(1024)), 25);', 1024 * 1024,
        { timeoutMs: 600, idleTimeoutMs: 3000 })), /time limit/i);
    assert.equal(fs.existsSync(destination), false);
});

test('existing partial files are retained without truncation', async t => {
    const destination = await fixture(t);
    await fs.promises.writeFile(destination + '.partial', 'previous attempt');
    await assert.rejects(streamBackup(source(destination, trailer, 1)), /EEXIST/);
    assert.equal(await fs.promises.readFile(destination + '.partial', 'utf8'), 'previous attempt');
});

test('write errors abort the source and never promote a complete image', async t => {
    const destination = await fixture(t);
    t.mock.method(fs, 'createWriteStream', () => {
        const output = new Writable({
            write(chunk, encoding, callback) { callback(new Error('Synthetic disk full')); }
        });
        output.bytesWritten = 0;
        process.nextTick(() => output.emit('open', 1));
        return output;
    });
    await assert.rejects(streamBackup(source(destination,
        'setInterval(() => process.stdout.write(Buffer.alloc(1024)), 20);', 1024 * 1024)), /Synthetic disk full/);
    assert.equal(fs.existsSync(destination), false);
});

test('preserves an existing complete image rather than replacing it', async t => {
    const destination = await fixture(t);
    await fs.promises.writeFile(destination, 'previous complete image');
    await assert.rejects(streamBackup(source(destination, trailer, 1)), /already exists/);
    assert.equal(await fs.promises.readFile(destination, 'utf8'), 'previous complete image');
});

test('status trailers split across stdout chunks are verified without leaking into the image', async t => {
    const destination = await fixture(t);
    const script = `process.stdout.write(Buffer.alloc(64, 42)); process.stdout.write('\\nEMAS_'); setTimeout(() => process.stdout.write('BACKUP_EXIT:0\\n'), 100);`;
    await streamBackup(source(destination, script, 64));
    assert.deepEqual(await fs.promises.readFile(destination), Buffer.alloc(64, 42));
});

test('backpressure from slow disk writes preserves every byte of a larger stream', async t => {
    const destination = await fixture(t);
    const createWriteStream = fs.createWriteStream;
    t.mock.method(fs, 'createWriteStream', (...args) => {
        const output = createWriteStream(...args);
        const write = output._write;
        output._write = function (chunk, encoding, callback) {
            setTimeout(() => write.call(this, chunk, encoding, callback), 3);
        };
        return output;
    });
    const script = `let n = 0; function pump() { while (n < 128) { n++; if (!process.stdout.write(Buffer.alloc(65536, 9))) return process.stdout.once('drain', pump); } ${trailer} } pump();`;
    await streamBackup(source(destination, script, 128 * 65536));
    assert.deepEqual(await fs.promises.readFile(destination), Buffer.alloc(128 * 65536, 9));
});

test('promotion errors leave a verified-length partial file, not a completed image', async t => {
    const destination = await fixture(t);
    t.mock.method(fs.promises, 'rename', async () => { throw new Error('Synthetic rename failure'); });
    await assert.rejects(streamBackup(source(destination,
        `process.stdout.write(Buffer.alloc(64)); ${trailer}`, 64)), /Synthetic rename failure/);
    assert.equal(fs.existsSync(destination), false);
    assert.equal((await fs.promises.stat(destination + '.partial')).size, 64);
});
