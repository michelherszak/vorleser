import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
import io

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_internal import BASE, PlayClient, PlayError, publish
from release_version import version_code


class FakePlay:
    def __init__(self, existing=7, failure=None, returned_code=1001):
        self.calls = []
        self.existing = existing
        self.failure = failure
        self.returned_code = returned_code

    def request(self, method, path, *, payload=None, file=None):
        self.calls.append((method, path, payload, file))
        if self.failure and self.failure in path:
            raise PlayError('Simulated Play failure')
        if method == 'POST' and path == BASE:
            return {'id': 'edit-123'}
        if method == 'GET' and path.endswith('/tracks'):
            return {'tracks': [{'track': 'internal'}, {'track': 'production', 'releases': [{'versionCodes': [str(self.existing)]}]}]}
        if method == 'GET':
            return {}
        if '/bundles?' in path:
            return {'versionCode': self.returned_code}
        return {}


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.bundle = Path(self.directory.name) / 'app.aab'
        self.mapping = Path(self.directory.name) / 'mapping.txt'
        self.bundle.write_bytes(b'bundle fixture')
        self.mapping.write_text('mapping fixture')

    def test_upload_only_changes_internal_track_and_commits_without_interrupting_review(self):
        client = FakePlay()
        publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'completed')
        updates = [(path, data) for method, path, data, _ in client.calls if method == 'PUT']
        self.assertEqual(updates, [(BASE + '/edit-123/tracks/internal', {
            'track': 'internal', 'releases': [{'name': '1.1.0', 'versionCodes': ['1001'], 'status': 'completed'}],
        })])
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', client.calls[-1][1])
        self.assertFalse(any(method == 'DELETE' for method, *_ in client.calls))

    def test_draft_stays_a_draft(self):
        client = FakePlay()
        publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'draft')
        update = next(data for method, _, data, _ in client.calls if method == 'PUT')
        self.assertEqual('draft', update['releases'][0]['status'])

    def test_duplicate_or_lower_code_is_rejected_before_upload(self):
        for existing in (1001, 1002):
            client = FakePlay(existing=existing)
            with self.assertRaises(PlayError):
                publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'completed')
            self.assertFalse(any(file for _, _, _, file in client.calls))
            self.assertEqual('DELETE', client.calls[-1][0])

    def test_upload_failure_discards_edit_without_committing(self):
        client = FakePlay(failure='/bundles?')
        with self.assertRaises(PlayError):
            publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'completed')
        self.assertEqual('DELETE', client.calls[-1][0])
        self.assertFalse(any(':commit' in path for _, path, *_ in client.calls))

    def test_wrong_uploaded_version_never_updates_track(self):
        client = FakePlay(returned_code=42)
        with self.assertRaises(PlayError):
            publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'completed')
        self.assertFalse(any(method == 'PUT' for method, *_ in client.calls))

    def test_review_failure_is_reported_without_automatic_retry(self):
        client = FakePlay(failure=':commit')
        with self.assertRaises(PlayError):
            publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'completed')
        self.assertEqual(1, sum(':commit' in path for _, path, *_ in client.calls))
        self.assertEqual('DELETE', client.calls[-1][0])

    def test_missing_files_fail_before_any_google_call(self):
        self.mapping.unlink()
        client = FakePlay()
        with self.assertRaises(ValueError):
            publish(client, self.bundle, self.mapping, 1001, '1.1.0', 'completed')
        self.assertEqual([], client.calls)

    def test_version_codes_increase_and_remain_in_play_limits(self):
        self.assertLess(version_code('1000', '1'), version_code('1000', '2'))
        for base, run in [('invalid', '1'), ('-1', '1'), ('1000', '0'), ('2100000000', '1')]:
            with self.assertRaises(ValueError):
                version_code(base, run)

    def test_api_errors_do_not_print_token(self):
        token = 'test-sensitive-token'
        body = json.dumps({'error': {'message': 'Denied ' + token}}).encode()
        with patch('publish_internal.urlopen', side_effect=HTTPError('https://example.invalid', 403, '', {}, io.BytesIO(body))):
            with self.assertRaises(PlayError) as error:
                PlayClient(token).request('GET', BASE)
        self.assertNotIn(token, str(error.exception))
        self.assertIn('403', str(error.exception))


if __name__ == '__main__':
    unittest.main()
