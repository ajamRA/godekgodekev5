"""Durable server-owned lock selection; file acknowledgements are not acoustic proof."""
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import threading
import time
import uuid


class LockSelectionStore:
    MAX_WAV = 576044 + 65536

    def __init__(self, directory, base_url, target):
        self.directory = Path(directory)
        self.path = self.directory / 'lock_state.json'
        self.base_url, self.target = base_url.rstrip('/'), target
        self.lock = threading.RLock()
        self.observed = None

    def load(self):
        with self.lock:
            if not self.path.exists():
                return {'active': 'original', 'pending_deploy': None, 'sounds': {}}
            # Corrupt state must never silently select factory/default audio.
            return json.loads(self.path.read_text())

    def save(self, state):
        with self.lock:
            self.directory.mkdir(parents=True, exist_ok=True)
            fd, name = tempfile.mkstemp(prefix='.lock_state_', dir=self.directory)
            try:
                with os.fdopen(fd, 'w') as out:
                    json.dump(state, out, indent=2)
                    out.flush()
                    os.fsync(out.fileno())
                os.chmod(name, 0o644)
                os.replace(name, self.path)
            finally:
                if os.path.exists(name):
                    os.unlink(name)

    def _new_selection(self, state, key, migrated=False):
        if not isinstance(key, str) or not re.fullmatch(r'[a-z0-9_]{1,32}', key) or key == 'active':
            raise ValueError('Invalid sound key')
        info = state.get('sounds', {}).get(key)
        if not info or info.get('file') != 'lock_' + key + '.wav':
            raise ValueError('Sound unavailable')
        source = self.directory / info['file']
        if source.is_symlink() or not source.is_file() or not 44 < source.stat().st_size <= self.MAX_WAV:
            raise ValueError('Sound unavailable or too large')
        data = source.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        # Pin this choice to immutable bytes even if an upload later reuses its name.
        version_dir = self.directory / 'versions' / digest
        version_dir.mkdir(parents=True, exist_ok=True)
        snapshot = version_dir / info['file']
        if not snapshot.exists():
            fd, name = tempfile.mkstemp(prefix='.sound_', dir=version_dir)
            try:
                with os.fdopen(fd, 'wb') as out:
                    out.write(data); out.flush(); os.fsync(out.fileno())
                os.chmod(name, 0o644)
                os.replace(name, snapshot)
            finally:
                if os.path.exists(name): os.unlink(name)
        if snapshot.is_symlink() or hashlib.sha256(snapshot.read_bytes()).hexdigest() != digest:
            raise ValueError('Immutable selection checksum failed')
        revision = max(time.time_ns() // 1000000, int(state.get('selection', {}).get('revision', 0)) + 1)
        return {'deploy_id': 'lock_' + uuid.uuid4().hex, 'revision': revision, 'sound_key': key,
                'file': info['file'], 'target': self.target, 'sha256': digest,
                'audio_url': self.base_url + '/static/locksounds/versions/' + digest + '/' + info['file'],
                'requested_at_ms': None if migrated else int(time.time() * 1000)}

    def desired(self):
        with self.lock:
            state = self.load()
            selection = state.get('selection')
            if not selection or selection.get('sound_key') != state.get('active'):
                if state.get('active') not in state.get('sounds', {}): return None
                selection = self._new_selection(state, state['active'], migrated=True)
                state['selection'] = selection
                # Migrate any legacy pending request to a unique, hash-pinned revision.
                if state.get('pending_deploy'): state['pending_deploy'] = copy.deepcopy(selection)
                self.save(state)
            return copy.deepcopy(selection)

    def select(self, key):
        with self.lock:
            state = self.load()
            selection = self._new_selection(state, key)
            state.update(active=key, selection=selection, pending_deploy=copy.deepcopy(selection))
            self.save(state)
            return selection

    def report(self, report):
        if not isinstance(report, dict): return
        with self.lock:
            selected = self.desired()
            if not selected: return
            if report.get('deploy_id') != selected['deploy_id'] or report.get('revision') != selected['revision']:
                return
            if report.get('source_sha256') != selected['sha256']: return
            target_hash = report.get('target_sha256')
            canonical_hash = report.get('canonical_sha256')
            synchronized = (report.get('file_committed') is True and report.get('service_mode') is False
                            and isinstance(target_hash, str) and re.fullmatch(r'[0-9a-f]{64}', target_hash)
                            and target_hash == canonical_hash)
            record = {k: report.get(k) for k in ('deploy_id', 'revision', 'source_sha256', 'target_sha256',
                      'canonical_sha256', 'radar_restarted', 'reload_deferred', 'service_mode')}
            record.update(sound_key=selected['sound_key'], file_committed=bool(synchronized), playback_verified=False)
            self.observed = {**record, 'observed_at_ms': int(time.time() * 1000)}
            state = self.load()
            if state.get('confirmed') != record or (synchronized and state.get('pending_deploy')):
                state['confirmed'] = record
                if synchronized: state['pending_deploy'] = None
                self.save(state)

    def status(self):
        with self.lock:
            selected = self.desired()
            observed = self.observed
            fresh = observed and 0 <= int(time.time() * 1000) - observed['observed_at_ms'] <= 15000
            synced = bool(selected and fresh and observed['deploy_id'] == selected['deploy_id']
                          and observed['file_committed'])
            return {'desired_sound': selected['sound_key'] if selected else None,
                    'revision': selected['revision'] if selected else None,
                    'requested_at_ms': selected.get('requested_at_ms') if selected else None,
                    'confirmed_sound': observed['sound_key'] if synced else None,
                    'sync_status': 'file_synced' if synced else 'awaiting_car',
                    'playback_verified': False,
                    'reload_deferred': observed.get('reload_deferred') if synced else None}
