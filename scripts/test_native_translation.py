#!/usr/bin/env python3
"""Run production text-layout policy regressions on a host or an ADB device."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', help='Build with the pinned NDK and run standalone on this device')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--audit-input', type=Path, help='Optional binary probes from the coverage audit')
    parser.add_argument('--index', type=Path, help='Compiled runtime index for --audit-input')
    args = parser.parse_args()
    if bool(args.audit_input) != bool(args.index) or (args.audit_input and not args.device):
        parser.error('--audit-input and --index require each other and --device')
    root = Path(__file__).resolve().parents[1]
    build = root / '.diagnostics' / 'native-translation-tests'
    build.mkdir(parents=True, exist_ok=True)
    source = root / 'scripts' / 'native_translation_test.cpp'
    if args.device:
        ndk = Path(os.environ['ANDROID_NDK_HOME'])
        host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
        compiler = ndk / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin' / (
            'clang++.exe' if os.name == 'nt' else 'clang++')
        executable = build / 'native-translation-test'
        subprocess.run([str(compiler), '--target=aarch64-linux-android26', '-std=c++17',
                        '-static-libstdc++', '-ffunction-sections', '-fdata-sections',
                        str(source), '-Wl,--gc-sections', '-llog', '-ldl',
                        '-o', str(executable)], check=True)
        remote = '/data/local/tmp/limbus-native-translation-test'
        adb = [args.adb, '-s', args.device]
        subprocess.run(adb + ['push', str(executable), remote], check=True)
        subprocess.run(adb + ['shell', 'chmod', '700', remote], check=True)
        remote_input = '/data/local/tmp/limbus-translation-probes.bin'
        remote_index = '/data/local/tmp/limbus-translation-index.bin'
        remote_report = '/data/local/tmp/limbus-translation-audit.tsv'
        try:
            if args.audit_input:
                subprocess.run(adb + ['push', str(args.audit_input), remote_input], check=True)
                subprocess.run(adb + ['push', str(args.index), remote_index], check=True)
                result = subprocess.run(adb + ['shell', remote, '--audit', remote_input, remote_index], check=False)
                if result.returncode == 0:
                    subprocess.run(adb + ['pull', remote_report, str(build / 'runtime-audit.tsv')], check=True)
                return result.returncode
            return subprocess.run(adb + ['shell', remote], check=False).returncode
        finally:
            for path in [remote, remote_input, remote_index, remote_report]:
                subprocess.run(adb + ['shell', 'rm', '-f', path], check=True)
    compiler = os.environ.get('CXX') or shutil.which('clang++') or shutil.which('g++')
    if not compiler:
        raise RuntimeError('Set CXX to a C++17 compiler, or pass --device and ANDROID_NDK_HOME')
    executable = build / ('native-translation-test.exe' if os.name == 'nt' else 'native-translation-test')
    subprocess.run([compiler, '-std=c++17', str(source), '-o', str(executable)], check=True)
    return subprocess.run([str(executable)], check=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
