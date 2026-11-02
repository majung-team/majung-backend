import contextlib
import io
import json
import pathlib
import tempfile
import unittest
import zipfile
import types
from unittest.mock import patch
import release

OLD = 'a' * 40
NEW = 'b' * 40

def jar(revision=NEW):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('BOOT-INF/classes/majung-release.txt', revision)
        archive.writestr('org/springframework/boot/loader/launch/JarLauncher.class', b'fixture')
    return buffer.getvalue()

class BlueGreenTest(unittest.TestCase):
    def prepare(self, root):
        slots = root/'slots'
        for name in ('blue', 'green'):
            (slots/name).mkdir(parents=True)
            (slots/name/'server.jar').write_bytes(b'previous')
        state = root/'state.json'; state.write_text(json.dumps({'active':'blue','revision':OLD}))
        route = root/'upstream'; route.write_text('reverse_proxy 127.0.0.1:8081\n')
        active = root/'active'; active.write_text('blue\n')
        return slots, state, route, active

    def run_case(self, checks, fail_reload=False):
        with tempfile.TemporaryDirectory() as temporary:
            slots,state,route,active = self.prepare(pathlib.Path(temporary))
            actions=[]
            reload_failed=False
            def command(*args):
                nonlocal reload_failed
                actions.append(args)
                if args == ('systemctl','reload','caddy') and fail_reload and not reload_failed:
                    reload_failed=True; raise RuntimeError('fixture reload failure')
            with patch.object(release,'SLOT_ROOT',slots),patch.object(release,'SLOT_STATE',state),\
                 patch.object(release,'UPSTREAM',route),patch.object(release,'ACTIVE_SLOT',active),\
                 patch.object(release,'run',side_effect=command),patch.object(release,'ensure_job_selector_readable'),patch.object(release,'probe',side_effect=checks) as probe,\
                 patch.object(release.time,'sleep'),contextlib.redirect_stdout(io.StringIO()):
                failure=None
                try: release.publish_blue_green(NEW,jar())
                except RuntimeError as error: failure=error
                return failure,json.loads(state.read_text()),route.read_text(),active.read_text(),actions,probe.call_args_list

    def test_success_switches_only_after_candidate_health_and_stops_previous(self):
        failure,state,route,active,actions,probes=self.run_case([True,True])
        self.assertIsNone(failure);self.assertEqual('green',state['active'])
        self.assertEqual(NEW,state['revision']);self.assertIn('8082',route);self.assertEqual('green\n',active)
        self.assertEqual(('systemctl','stop','majung@blue.service'),actions[-1])
        self.assertEqual('green',probes[0].kwargs['expected_slot'])
        self.assertEqual('green',probes[1].kwargs['expected_slot'])

    def test_unhealthy_candidate_never_reloads_caddy_or_stops_active(self):
        failure,state,route,active,actions,_=self.run_case([False])
        self.assertIsNotNone(failure);self.assertEqual('blue',state['active']);self.assertIn('8081',route)
        self.assertEqual('blue\n',active)
        self.assertNotIn(('systemctl','reload','caddy'),actions)
        self.assertNotIn(('systemctl','stop','majung@blue.service'),actions)

    def test_failed_public_check_restores_route_jobs_and_state(self):
        failure,state,route,active,actions,probes=self.run_case([True,False,True])
        self.assertIsNotNone(failure);self.assertEqual({'active':'blue','revision':OLD},state)
        self.assertIn('8081',route);self.assertEqual('blue\n',active)
        self.assertEqual('blue',probes[-1].kwargs['expected_slot'])
        self.assertNotIn(('systemctl','stop','majung@blue.service'),actions)
        self.assertEqual(('systemctl','stop','majung@green.service'),actions[-1])

    def test_reload_failure_restores_active_route(self):
        failure,state,route,active,actions,_=self.run_case([True,True],fail_reload=True)
        self.assertIsNotNone(failure);self.assertEqual('blue',state['active']);self.assertIn('8081',route)
        self.assertEqual('blue\n',active);self.assertNotIn(('systemctl','stop','majung@blue.service'),actions)

    def test_invalid_artifact_is_rejected_before_service_changes(self):
        with patch.object(release,'run') as command:
            with self.assertRaises(ValueError): release.publish_blue_green(NEW,jar(OLD))
            command.assert_not_called()

    def test_same_revision_on_previous_slot_does_not_pass_candidate_probe(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = json.dumps({'revision': OLD, 'slot': 'blue'}).encode()
        with patch.object(release.urllib.request, 'urlopen', return_value=response), patch.object(release.time, 'sleep'):
            self.assertFalse(release.probe('https://example.invalid/health', OLD, attempts=1, expected_slot='green'))

class JobSelectorAccessTest(unittest.TestCase):
    def test_directory_traversal_is_granted_without_exposing_private_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'config'
            directory.mkdir(mode=0o700)
            selector = directory / 'active-slot'; selector.write_text('blue\n'); selector.chmod(0o644)
            secret = directory / 'server.env'; secret.write_text('fixture'); secret.chmod(0o600)
            with patch.object(release, 'ACTIVE_SLOT', selector), patch.object(release.os, 'chown') as ownership, \
                 patch.object(release.pwd, 'getpwnam', return_value=types.SimpleNamespace(pw_gid=1001)), \
                 patch.object(release, 'run') as command:
                release.ensure_job_selector_readable()
            self.assertEqual(0o710, directory.stat().st_mode & 0o777)
            self.assertEqual(0o600, secret.stat().st_mode & 0o777)
            ownership.assert_called_once_with(directory, 0, 1001)
            command.assert_called_once_with('runuser', '-u', 'majung', '--', 'test', '-r', str(selector))

    def test_symlink_is_rejected_before_permission_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            target = root / 'target'; target.write_text('blue\n')
            selector = root / 'active-slot'; selector.symlink_to(target)
            with patch.object(release, 'ACTIVE_SLOT', selector), patch.object(release.os, 'chown') as ownership:
                with self.assertRaises(ValueError): release.ensure_job_selector_readable()
                ownership.assert_not_called()

    def test_private_environment_must_remain_unreadable_before_traversal_grant(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            selector = root / 'active-slot'; selector.write_text('blue\n')
            private = root / 'server.env'; private.write_text('fixture'); private.chmod(0o644)
            with patch.object(release, 'ACTIVE_SLOT', selector), patch.object(release.os, 'chown') as ownership:
                with self.assertRaises(ValueError): release.ensure_job_selector_readable()
                ownership.assert_not_called()

    def test_unreadable_selector_stops_deployment_before_service_actions(self):
        with tempfile.TemporaryDirectory() as temporary:
            slots, state, route, active = BlueGreenTest().prepare(pathlib.Path(temporary))
            with patch.object(release, 'SLOT_STATE', state), \
                 patch.object(release, 'ensure_job_selector_readable', side_effect=RuntimeError('unreadable')), \
                 patch.object(release, 'run') as command:
                with self.assertRaises(RuntimeError): release.publish_blue_green(NEW, jar())
                command.assert_not_called()
