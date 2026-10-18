const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

function validateWav(file) {
    const size = fs.statSync(file).size;
    if (size < 46 || size > 32 * 1024 * 1024) throw new Error('The WAV file is incomplete or exceeds 32 MB.');
    const b = fs.readFileSync(file);
    if (b.toString('ascii', 0, 4) !== 'RIFF' || b.toString('ascii', 8, 12) !== 'WAVE' || b.readUInt32LE(4) + 8 !== b.length)
        throw new Error('Invalid RIFF/WAV file size.');
    let fmt = false, data = false, signal = false, pos = 12;
    while (pos < b.length) {
        if (pos + 8 > b.length) throw new Error('The WAV file is truncated.');
        const id = b.toString('ascii', pos, pos + 4), n = b.readUInt32LE(pos + 4);
        const end = pos + 8 + n, next = end + (n & 1);
        if (next > b.length) throw new Error('The WAV file contains incomplete data.');
        if (id === 'fmt ') {
            if (fmt || n < 16 || b.readUInt16LE(pos + 8) !== 1 || b.readUInt16LE(pos + 10) !== 1
                || b.readUInt32LE(pos + 12) !== 48000 || b.readUInt32LE(pos + 16) !== 96000
                || b.readUInt16LE(pos + 20) !== 2 || b.readUInt16LE(pos + 22) !== 16)
                throw new Error('AVAS requires 48 kHz mono 16-bit PCM audio.');
            fmt = true;
        } else if (id === 'data') {
            if (!fmt || data || n < 2 || (n & 1)) throw new Error('Invalid PCM audio data.');
            data = true;
            for (let i = pos + 8; i < end; i++) if (b[i] !== 0) signal = true;
        }
        pos = next;
    }
    if (!fmt || !data || !signal) throw new Error('Empty or silent audio is not allowed for AVAS.');
    return { size, hash: crypto.createHash('sha256').update(b).digest('hex') };
}

// Both upload and preset operations invoke the same packaged Java transaction engine.
function createAvasManager({ adb, presetPath, tempDir }) {
    function slotNumber(slot) {
        const n = Number(slot);
        if (![1, 2, 3].includes(n)) throw new Error('The AVAS slot must be 1, 2, or 3.');
        return n;
    }
    function quote(value) { return "'" + String(value).replace(/'/g, "'\\''") + "'"; }
    async function shell(ip, command, timeout = 15000) {
        return adb(['-s', `${ip}:5555`, 'shell', command], timeout);
    }
    async function checked(ip, command, timeout) {
        const result = await shell(ip, command, timeout);
        if (!result.success) throw new Error('The AVAS command failed; it will not be retried automatically.');
        return result.stdout.trim();
    }
    async function verify(ip, remote, info) {
        const size = await checked(ip, `stat -c %s ${quote(remote)}`);
        const hash = await checked(ip, `sha256sum ${quote(remote)}`);
        if (size !== String(info.size) || hash.split(/\s+/)[0].toLowerCase() !== info.hash)
            throw new Error('The AVAS transfer is incomplete or the hash does not match; the active audio was not changed.');
    }
    async function push(ip, local, remote, info) {
        const result = await adb(['-s', `${ip}:5555`, 'push', local, remote], 120000);
        if (!result.success) throw new Error('The AVAS transfer failed; the active audio was not changed.');
        await verify(ip, remote, info);
    }
    async function preflight(ip) {
        if (typeof ip !== 'string' || !/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)
            || ip.split('.').some(n => Number(n) > 255)) throw new Error('Invalid IHU IP address.');
        if (await checked(ip, 'id -u') !== '0') throw new Error('AVAS requires root ADB access; the active audio was not changed.');
        if (await checked(ip, 'getprop ro.product.model') !== 'IHU801P') throw new Error('This AVAS package supports IHU801P only; other models are blocked.');
        const sdk = await checked(ip, 'getprop ro.build.version.sdk');
        if (!/^\d+$/.test(sdk) || Number(sdk) < 29) throw new Error('This AVAS package requires Android API 29 or newer.');
        await checked(ip, 'mkdir -p /data/local/avas/incoming /data/local/avas/requests /data/local/avas/tools');
    }
    async function engine(ip) {
        const local = presetPath('avas/avas_sound_daemon.jar');
        const manifestPath = presetPath('avas/manifest.json');
        if (!local || !manifestPath) throw new Error('The complete AVAS package was not found.');
        const bytes = fs.readFileSync(local);
        const info = { size: bytes.length, hash: crypto.createHash('sha256').update(bytes).digest('hex') };
        const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
        if (manifest.protocol_version !== 2 || manifest.files['avas_sound_daemon.jar'] !== info.hash)
            throw new Error('The AVAS engine manifest does not match; the operation was cancelled.');
        const remote = `/data/local/avas/tools/avas_${info.hash}.jar`;
        const exists = await shell(ip, `test -e ${quote(remote)}`);
        if (exists.success) await verify(ip, remote, info);
        else {
            const staged = `/data/local/avas/tools/stage_${crypto.randomUUID()}.jar`;
            await push(ip, local, staged, info);
            await checked(ip, `chmod 644 ${quote(staged)} && mv -n ${quote(staged)} ${quote(remote)}`);
            await verify(ip, remote, info);
        }
        return remote;
    }
    async function execute(ip, operation, request, jar) {
        fs.mkdirSync(tempDir, { recursive: true });
        const id = crypto.randomUUID();
        const local = path.join(tempDir, `avas_request_${id}.json`);
        const remote = `/data/local/avas/requests/${id}.json`;
        const bytes = Buffer.from(JSON.stringify(request));
        fs.writeFileSync(local, bytes, { flag: 'wx' });
        try {
            await push(ip, local, remote, { size: bytes.length, hash: crypto.createHash('sha256').update(bytes).digest('hex') });
            const result = await shell(ip, `CLASSPATH=${quote(jar)} /system/bin/app_process /system/bin com.emas.avas.AvasSoundDaemon --operation ${operation} --request-file ${quote(remote)}`, 120000);
            let reply;
            for (const line of (result.stdout || '').split(/\r?\n/)) {
                if (line.trim().startsWith('{')) { try { reply = JSON.parse(line); } catch (_) {} }
            }
            if (!reply || reply.protocol_version !== 2 && reply.ok !== false)
                throw new Error('The AVAS operation state is unknown. Check the IHU before retrying.');
            if (!result.success || reply.ok !== true) throw new Error(reply.error || 'The AVAS transaction failed; check the recovery status.');
            if (reply.mount_verified !== true || reply.service_running !== true || reply.playback_verified !== false)
                throw new Error('AVAS transaction verification is incomplete. Do not retry automatically.');
            return { success: true, ...reply, message: reply.reapply_on_boot
                ? 'The AVAS file and slot selection were saved, and the bind mount and service were verified. Reapplying after reboot requires this version of the boot helper to be installed; speaker playback has not been verified.'
                : 'The AVAS file is bind-mounted and the service is running. Speaker playback has not been verified; reapplying after reboot is not yet available.' };
        } finally { fs.unlinkSync(local); }
    }
    async function apply(ip, slot, local, name) {
        const n = slotNumber(slot);
        const info = validateWav(local);
        await preflight(ip);
        const jar = await engine(ip);
        const remote = `/data/local/avas/incoming/slot_${n}_${crypto.randomUUID()}.wav`;
        await push(ip, local, remote, info);
        return execute(ip, 'apply', { slot: n, file_path: remote, custom_name: name }, jar);
    }
    async function preset(ip, slot, choice, customAudioPath) {
        const n = slotNumber(slot);
        const names = { v8: 'clean_dodge_v8.wav', mario: 'clean_slot3_mario.wav', paddlepop: 'clean_paddlepop.wav' };
        function verifiedPackageAudio(relative) {
            const file = presetPath(`avas/${relative}`), manifestFile = presetPath('avas/manifest.json');
            if (!file || !manifestFile) throw new Error('The AVAS audio package is incomplete.');
            const info = validateWav(file);
            const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
            if (manifest.protocol_version !== 2 || !manifest.files || manifest.files[relative] !== info.hash)
                throw new Error('The packaged audio hash does not match; the original file was preserved.');
            return file;
        }
        if (choice === 'factory' || choice === 'original') {
            const track = n === 1 ? 'yinheyinfu.wav' : n === 2 ? 'space.wav' : 'pixel.wav';
            const local = verifiedPackageAudio(`factory_backup/${track}`);
            const info = validateWav(local);
            await preflight(ip);
            const jar = await engine(ip);
            const remote = `/data/local/avas/incoming/factory_${n}_${crypto.randomUUID()}.wav`;
            await push(ip, local, remote, info);
            return execute(ip, 'restore', { slot: n, backup_file: remote }, jar);
        }
        if (choice !== 'custom' && !Object.prototype.hasOwnProperty.call(names, choice)) throw new Error('Invalid AVAS preset.');
        const local = choice === 'custom' ? customAudioPath : verifiedPackageAudio(`presets/${names[choice]}`);
        if (!local || !fs.existsSync(local)) throw new Error('The AVAS audio file was not found.');
        return apply(ip, n, local, choice);
    }
    return { apply, preset };
}
module.exports = { createAvasManager, validateWav };
