const fs = require('fs');
const { spawn } = require('child_process');
const { once } = require('events');
const { Transform } = require('stream');
const { pipeline } = require('stream/promises');

// exec-out does not reliably propagate remote exit codes on every ADB version.
// The remote command appends a status trailer after the known-size binary image.
// Never write that trailer to disk or infer success from EOF alone.
async function streamBackup({
    command, args, destination, expectedBytes, onProgress = () => {},
    timeoutMs = 24 * 60 * 60 * 1000,
    idleTimeoutMs = 5 * 60 * 1000,
    sampleIntervalMs = 500
}) {
    if (!Number.isSafeInteger(expectedBytes) || expectedBytes <= 0) {
        throw new Error('A verified, positive partition byte count is required.');
    }
    if (fs.existsSync(destination)) {
        throw new Error(`An image already exists at ${destination}. Move it before starting a new backup.`);
    }
    const partial = destination + '.partial';
    const output = fs.createWriteStream(partial, { flags: 'wx' });
    await once(output, 'open');

    let child;
    let timer;
    let sampler;
    let failure = null;
    let bytesRead = 0;
    let trailer = '';
    let stderr = '';
    let previousBytes = 0;
    let previousTime = performance.now();
    let lastActivity = previousTime;
    const controller = new AbortController();
    const stop = error => {
        if (!failure) failure = error;
        controller.abort();
        if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    };
    const report = (initial = false) => {
        const now = performance.now();
        const bytesTransferred = output.bytesWritten;
        const delta = bytesTransferred - previousBytes;
        if (delta > 0) lastActivity = now;
        const bytesPerSecond = initial ? null : delta * 1000 / Math.max(1, now - previousTime);
        onProgress({ bytesTransferred, bytesPerSecond });
        previousBytes = bytesTransferred;
        previousTime = now;
        if (now - lastActivity >= idleTimeoutMs) {
            stop(new Error(`No transfer activity for ${Math.round(idleTimeoutMs / 1000)} seconds. Partial image retained at ${partial}.`));
        }
    };

    const image = new Transform({
        transform(chunk, encoding, callback) {
            const imageLength = Math.min(chunk.length, expectedBytes - bytesRead);
            if (imageLength > 0) {
                bytesRead += imageLength;
                this.push(imageLength === chunk.length ? chunk : chunk.subarray(0, imageLength));
            }
            if (chunk.length > imageLength) {
                if (trailer.length + chunk.length - imageLength > 80) {
                    return callback(new Error('Excess data or invalid remote backup status after the image.'));
                }
                trailer += chunk.subarray(imageLength).toString('ascii');
            }
            callback();
        },
        flush(callback) {
            if (bytesRead !== expectedBytes) {
                return callback(new Error(`Backup byte count is short: received ${bytesRead}, expected ${expectedBytes}.`));
            }
            const status = /^\nEMAS_BACKUP_EXIT:(\d+)\n$/.exec(trailer);
            if (!status) return callback(new Error('Remote backup status is missing or invalid; the connection may have been interrupted.'));
            if (Number(status[1]) !== 0) return callback(new Error(`Remote dd failed with exit status ${status[1]}.`));
            callback();
        }
    });

    try {
        report(true);
        child = spawn(command, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
        const exited = new Promise(resolve => {
            child.once('error', error => { stop(error); resolve(); });
            child.once('close', (code, signal) => {
                if (code !== 0) stop(new Error(`Backup process failed (${signal || code}).${stderr ? ' ' + stderr.trim() : ''}`));
                resolve();
            });
        });
        child.stderr.on('data', chunk => {
            if (stderr.length < 8192) stderr += chunk.toString('utf8').slice(0, 8192 - stderr.length);
        });
        timer = setTimeout(() => stop(new Error(`Backup exceeded its ${Math.round(timeoutMs / 3600000)}-hour time limit. Partial image retained at ${partial}.`)), timeoutMs);
        sampler = setInterval(() => report(), sampleIntervalMs);
        const copied = pipeline(child.stdout, image, output, { signal: controller.signal })
            .catch(error => { stop(error); });
        await Promise.all([copied, exited]);
        clearTimeout(timer);
        clearInterval(sampler);
        onProgress({ bytesTransferred: output.bytesWritten, bytesPerSecond: 0 });
        if (failure) throw failure;
        const stored = await fs.promises.stat(partial);
        if (stored.size !== expectedBytes) {
            throw new Error(`Stored image byte count does not match: ${stored.size}/${expectedBytes}.`);
        }
        const file = await fs.promises.open(partial, 'r+');
        try { await file.sync(); } finally { await file.close(); }
        await fs.promises.rename(partial, destination);
        onProgress({ bytesTransferred: stored.size, bytesPerSecond: 0 });
        return stored.size;
    } catch (error) {
        stop(error);
        output.destroy();
        onProgress({ bytesTransferred: output.bytesWritten, bytesPerSecond: 0 });
        throw failure;
    } finally {
        clearTimeout(timer);
        clearInterval(sampler);
    }
}

module.exports = { streamBackup };
