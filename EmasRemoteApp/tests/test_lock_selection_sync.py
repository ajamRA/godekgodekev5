import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

module_path = Path(__file__).resolve().parents[1] / 'server/lock_selection_store.py'
spec = importlib.util.spec_from_file_location('lock_selection_store', module_path)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class LockSyncTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for key in ('mario', 'guru'):
            (self.root / ('lock_' + key + '.wav')).write_bytes(b'RIFF' + key.encode() * 20)
        self.store = module.LockSelectionStore(self.root, 'https://fixture.invalid', 'fixture.wav')
        self.store.save({'active':'mario','pending_deploy':None,'sounds':{
            k:{'file':'lock_'+k+'.wav'} for k in ('mario','guru')}})

    def report(self, selected, **changes):
        value = dict(deploy_id=selected['deploy_id'],revision=selected['revision'],
                     source_sha256=selected['sha256'],target_sha256='a'*64,canonical_sha256='a'*64,
                     file_committed=True,service_mode=False,radar_restarted=True,reload_deferred=False)
        value.update(changes); return value

    def test_offline_choice_persists_and_is_returned_after_ack(self):
        chosen = self.store.select('guru')
        reopened = module.LockSelectionStore(self.root, 'https://fixture.invalid', 'fixture.wav')
        self.assertEqual(chosen, reopened.desired())
        self.assertEqual('awaiting_car', reopened.status()['sync_status'])
        reopened.report(self.report(chosen))
        self.assertIsNone(reopened.load()['pending_deploy'])
        self.assertEqual(chosen, reopened.desired())
        self.assertEqual('file_synced', reopened.status()['sync_status'])
        self.assertFalse(reopened.status()['playback_verified'])

    def test_latest_choice_wins_and_old_ack_cannot_clear_it(self):
        with patch.object(module.time, 'time_ns', return_value=1000000000):
            old = self.store.select('guru'); latest = self.store.select('guru')
        self.assertNotEqual(old['deploy_id'], latest['deploy_id'])
        self.assertGreater(latest['revision'], old['revision'])
        self.store.report(self.report(old))
        self.assertEqual(latest, self.store.load()['pending_deploy'])

    def test_snapshot_does_not_change_when_named_upload_is_replaced(self):
        chosen = self.store.select('guru')
        snapshot = self.root / 'versions' / chosen['sha256'] / 'lock_guru.wav'
        original = snapshot.read_bytes()
        (self.root/'lock_guru.wav').write_bytes(b'new-upload'*20)
        self.assertEqual(chosen, self.store.desired())
        self.assertEqual(original, snapshot.read_bytes())
        latest = self.store.select('guru')
        self.assertNotEqual(latest['sha256'], chosen['sha256'])

    def test_bad_or_service_mode_report_never_acknowledges(self):
        chosen = self.store.select('mario')
        for changes in ({'target_sha256':'b'*64},{'service_mode':True},{'source_sha256':'b'*64},
                        {'revision':0},{'file_committed':False}):
            self.store.report(self.report(chosen, **changes))
            self.assertIsNotNone(self.store.load()['pending_deploy'])
            self.assertEqual('awaiting_car', self.store.status()['sync_status'])

    def test_stale_report_does_not_claim_online_synced(self):
        chosen = self.store.select('guru'); self.store.report(self.report(chosen))
        self.store.observed['observed_at_ms'] -= 16000
        self.assertEqual('awaiting_car', self.store.status()['sync_status'])

    def test_unchanged_reports_do_not_rewrite_state(self):
        chosen = self.store.select('guru'); self.store.report(self.report(chosen))
        before = self.store.path.stat().st_mtime_ns
        self.store.report(self.report(chosen))
        self.assertEqual(before, self.store.path.stat().st_mtime_ns)

    def test_corrupt_state_fails_closed(self):
        self.store.path.write_text('{broken')
        with self.assertRaises(json.JSONDecodeError): self.store.desired()

    def test_migration_preserves_old_choice_with_unknown_request_time(self):
        chosen = self.store.desired()
        self.assertEqual('mario', chosen['sound_key'])
        self.assertIsNone(chosen['requested_at_ms'])
        self.assertEqual(chosen, self.store.desired())

    def test_traversal_is_rejected(self):
        with self.assertRaises(ValueError): self.store.select('../guru')

if __name__ == '__main__': unittest.main()
