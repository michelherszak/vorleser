"""Upload a signed bundle through the Play Edits API, restricted to this app's internal track."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

PACKAGE = 'de.vorleser.app'
TRACK = 'internal'
ORIGIN = 'https://androidpublisher.googleapis.com'
BASE = f'/androidpublisher/v3/applications/{PACKAGE}/edits'


class PlayError(RuntimeError):
    pass


class PlayClient:
    def __init__(self, token):
        if not token or any(c.isspace() for c in token):
            raise ValueError('A Google Play access token is required.')
        self.token = token

    def request(self, method, path, *, payload=None, file=None):
        data = file.read_bytes() if file else (json.dumps(payload).encode() if payload is not None else None)
        headers = {'Authorization': f'Bearer {self.token}'}
        if data is not None:
            headers['Content-Type'] = 'application/octet-stream' if file else 'application/json'
        request = Request(ORIGIN + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=120) as response:
                body = response.read()
                return json.loads(body) if body else {}
        except HTTPError as error:
            body = error.read().decode(errors='replace')
            try:
                message = json.loads(body)['error']['message']
            except (ValueError, KeyError, TypeError):
                message = 'Google Play rejected the request.'
            raise PlayError(f'Google Play HTTP {error.code}: {str(message).replace(self.token, "[redacted]")}') from None


def publish(client, bundle, mapping, code, name, status):
    if status not in ('draft', 'completed') or not 1 <= code <= 2_100_000_000:
        raise ValueError('Invalid release status or version code.')
    if not name.strip() or len(name) > 100 or any(ord(c) < 32 or ord(c) == 127 for c in name):
        raise ValueError('Invalid release name.')
    if not bundle.is_file() or bundle.suffix != '.aab' or not mapping.is_file():
        raise ValueError('An Android App Bundle and its R8 mapping file are required.')
    edit_id = str(client.request('POST', BASE, payload={})['id'])
    if not re.fullmatch(r'[A-Za-z0-9_-]+', edit_id):
        raise PlayError('Google Play returned an invalid edit ID.')
    edit = f'{BASE}/{quote(edit_id, safe="")}'
    committed = False
    try:
        tracks = client.request('GET', edit + '/tracks').get('tracks', [])
        if not any(track.get('track') == TRACK for track in tracks):
            raise PlayError('Create the internal testing track and initial app setup in Play Console first.')
        bundles = client.request('GET', edit + '/bundles').get('bundles', [])
        apks = client.request('GET', edit + '/apks').get('apks', [])
        used = [int(item['versionCode']) for item in bundles + apks]
        used += [int(value) for track in tracks for release in track.get('releases', [])
                 for value in release.get('versionCodes', [])]
        if used and code <= max(used):
            raise PlayError('Version code must exceed the existing Play versions. Increase PLAY_VERSION_CODE_BASE and start a new run.')
        uploaded = client.request('POST', '/upload' + edit + '/bundles?uploadType=media', file=bundle)
        if int(uploaded['versionCode']) != code:
            raise PlayError('Uploaded bundle version does not match the calculated version code.')
        # AABs produced by AGP already embed their mapping; also attach it explicitly for diagnostics.
        client.request('POST', f'/upload{edit}/apks/{code}/deobfuscationFiles/proguard?uploadType=media', file=mapping)
        client.request('PUT', edit + '/tracks/' + TRACK, payload={
            'track': TRACK,
            'releases': [{'name': name, 'versionCodes': [str(code)], 'status': status}],
        })
        # Do not cancel an unrelated review already running in Play Console.
        client.request('POST', edit + ':commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW')
        committed = True
    finally:
        if not committed:
            try:
                client.request('DELETE', edit)
            except Exception:
                print('Could not discard the edit. Check Play Console before retrying.', file=sys.stderr)
    print(f'Committed {PACKAGE} version {code} to {TRACK} with status {status}.')
    if status == 'draft':
        print('This draft is not distributed to testers; finish it in Play Console.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--mapping', type=Path, required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--release-name', required=True)
    parser.add_argument('--status', choices=('draft', 'completed'), default='draft')
    args = parser.parse_args()
    try:
        publish(PlayClient(os.environ.get('PLAY_ACCESS_TOKEN')), args.bundle, args.mapping,
                args.version_code, args.release_name, args.status)
    except (PlayError, ValueError, OSError, KeyError) as error:
        # Never dump request headers, tokens or full API responses to public Actions logs.
        raise SystemExit(str(error)) from None


if __name__ == '__main__':
    main()
