import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('offsite', pathlib.Path(__file__).with_name('offsite-backup.py'))
offsite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offsite)


class OffsiteBackupTest(unittest.TestCase):
    def test_another_service_target_is_rejected_before_any_command(self):
        env = dict(GCP_PROJECT_ID='fleaflea-511001', GCP_ZONE='asia-northeast3-a',
                   GCP_INSTANCE='fleaflea-dev', GCP_INSTANCE_ID='5245204336249082788',
                   GCP_SSH_USER='sa_test', GCP_SSH_HOST_KEY='ssh-ed25519 fixture')
        with patch.dict(offsite.os.environ, env, clear=True), patch.object(offsite, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'Unexpected backup target'):
                offsite.main()
            run.assert_not_called()

    def test_download_digest_detects_changed_archive(self):
        with tempfile.TemporaryDirectory() as folder:
            archive = pathlib.Path(folder) / 'backup'
            archive.write_bytes(b'PGDMP first')
            expected = offsite.digest(archive)
            archive.write_bytes(b'PGDMP other')
            self.assertNotEqual(expected, offsite.digest(archive))
