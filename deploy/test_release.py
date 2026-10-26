import hashlib
import io
import json
import pathlib
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import release

REVISION = 'a' * 40

def web_artifact(extra=None):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w:gz') as archive:
        for name in ['index.html', 'app.js', 'api.js', 'model.js', 'style.css']:
            data = b'fixture'
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        if extra is not None: archive.addfile(extra)
    return output.getvalue()

class ReleaseTest(unittest.TestCase):
    def test_bad_checksum_and_oversized_stream_are_rejected(self):
        with self.assertRaises(ValueError): release.checked_payload(io.BytesIO(b'changed'), hashlib.sha256(b'original').hexdigest(), 100)
        with self.assertRaises(ValueError): release.checked_payload(io.BytesIO(b'x'*11), '0'*64, 10)

    def test_archive_paths_and_links_cannot_escape_web_directory(self):
        for name, kind in [('../outside', tarfile.REGTYPE), ('index.html', tarfile.SYMTYPE), ('.env', tarfile.REGTYPE)]:
            info = tarfile.TarInfo(name); info.type = kind; info.linkname = '/etc/majung/server.env'
            with tempfile.TemporaryDirectory() as temporary, self.assertRaises(ValueError):
                release.unpack_web(web_artifact(info), pathlib.Path(temporary))
                self.fail('unsafe archive accepted')

    def test_map_script_is_an_allowed_web_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            release.unpack_web(web_artifact(tarfile.TarInfo('map.js')), pathlib.Path(temporary))
            self.assertTrue((pathlib.Path(temporary) / 'map.js').is_file())

    def test_push_files_are_allowed_web_files(self):
        for name in ['push.js', 'proposal.js', 'sw.js', 'manifest.webmanifest']:
            with tempfile.TemporaryDirectory() as temporary:
                release.unpack_web(web_artifact(tarfile.TarInfo(name)), pathlib.Path(temporary))
                self.assertTrue((pathlib.Path(temporary) / name).is_file())

    def test_jar_must_contain_matching_built_revision(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr('BOOT-INF/classes/majung-release.txt', REVISION)
            archive.writestr('org/springframework/boot/loader/launch/JarLauncher.class', b'fixture')
        release.verify_jar(output.getvalue(), REVISION)
        with self.assertRaises(ValueError): release.verify_jar(output.getvalue(), 'b'*40)

    def test_failed_web_health_check_restores_previous_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            target = root/'web'; target.mkdir(); (target/'index.html').write_text('previous')
            with patch.object(release, 'RELEASE_ROOT', root/'releases'), patch.dict(release.TARGETS, web=target), patch.object(release, 'probe', side_effect=[False, True]):
                with self.assertRaisesRegex(RuntimeError, 'previous release restored'):
                    release.publish('web', REVISION, web_artifact(), {})
            self.assertEqual((target/'index.html').read_text(), 'previous')

    def test_successful_release_preserves_one_previous_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            target = root/'web'; target.mkdir(); (target/'index.html').write_text('previous')
            with patch.object(release, 'RELEASE_ROOT', root/'releases'), patch.dict(release.TARGETS, web=target), patch.object(release, 'probe', return_value=True):
                release.publish('web', REVISION, web_artifact(), {})
            self.assertEqual(json.loads((target/'release.json').read_text())['revision'], REVISION)
            self.assertEqual((root/'releases/web-previous/index.html').read_text(), 'previous')

if __name__ == '__main__': unittest.main()
