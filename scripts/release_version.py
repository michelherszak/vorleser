"""Allocate a monotonically increasing version code for new workflow runs."""
import os
from pathlib import Path


def version_code(base, run_number):
    if not str(base).isdigit() or not str(run_number).isdigit() or int(run_number) < 1:
        raise ValueError('Version base and workflow run number must be non-negative integers; run number must be positive.')
    code = int(base) + int(run_number)
    if not 1 <= code <= 2_100_000_000:
        raise ValueError('Calculated version code is outside Google Play limits.')
    return code


def main():
    name = os.environ['VERSION_NAME']
    if not name.strip() or len(name) > 100 or any(ord(c) < 32 or ord(c) == 127 for c in name):
        raise ValueError('Invalid version name.')
    code = version_code(os.environ['VERSION_BASE'], os.environ['GITHUB_RUN_NUMBER'])
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        output.write(f'version_code={code}\n')
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as summary:
        summary.write(f'Android version code: **{code}**.\n')
    print(f'Android version code: {code}')


if __name__ == '__main__':
    main()
