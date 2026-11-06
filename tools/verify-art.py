"""Verify the actual R8 GUI session classes on Android, without starting guest commands.

Example (after assembleRelease):
  python lyra_x11/tools/verify-art.py --sdk G:/sdk --serial emulator-5582 \
    --apk app/build/outputs/apk/release/app-release-unsigned.apk \
    --mapping app/build/outputs/mapping/release/mapping.txt

Requires Python, a JDK, Android build tools and an already connected test device.
Does not install the APK or modify the app's data. A rejected class fails the command.
"""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=Path, required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--mapping', type=Path, required=True)
parser.add_argument('--work', type=Path, default=Path('.cache/lyra-art-verify'))
args = parser.parse_args()

def version(path):
    return tuple(int(part) for part in path.name.replace('android-', '').split('.') if part.isdecimal())

sdk = args.sdk.resolve()
android = max((sdk/'platforms').glob('*/android.jar'), key=lambda p: version(p.parent))
build = max((sdk/'build-tools').iterdir(), key=version)
adb = sdk/'platform-tools'/('adb.exe' if sys.platform == 'win32' else 'adb')
apk = args.apk.resolve()
work = args.work.resolve()
classes = work/'classes'
dex = work/'dex'
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(exist_ok=True)

names = set()
current = None
session_method = re.compile(r'\bcom\.yukisoffd\.lyra\.x11\.GuiSessions?(?:\$\$[^.\s(]+)?\.[^\s(]+\(')
with args.mapping.open(encoding='utf-8') as mapping:
    for line in mapping:
        if not line[0].isspace() and not line.startswith('#') and ' -> ' in line and line.endswith(':\n'):
            current = line.rstrip().split(' -> ')[1][:-1]
            if line.startswith('com.yukisoffd.lyra.x11.GuiSession'):
                names.add(current)
        elif current and not line.lstrip().startswith('#') and session_method.search(line):
            # R8 may merge a task into a class named after a different component, or inline
            # session methods into its callers. Include those final classes as well.
            names.add(current)
if not names:
    raise RuntimeError('No GUI session classes found in the supplied R8 mapping')
class_list = work/'classes.txt'
class_list.write_text('\n'.join(sorted(names))+'\n', encoding='utf-8')

source = Path(__file__).with_name('GuiArtVerifier.java').resolve()
subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-encoding', 'UTF-8',
                '-classpath', str(android), '-d', str(classes), str(source)], check=True)
subprocess.run(['java', '-cp', str(build/'lib/d8.jar'), 'com.android.tools.r8.D8',
                '--min-api', '26', '--lib', str(android), '--output', str(dex),
                str(classes/'com/yukisoffd/lyra/x11/tools/GuiArtVerifier.class')], check=True)
jar = work/'verifier.jar'
with zipfile.ZipFile(jar, 'w') as output:
    output.write(dex/'classes.dex', 'classes.dex')

remote = '/data/local/tmp/lyra-gui-art'
target = remote+'/apk-'+hashlib.sha256(apk.read_bytes()).hexdigest()[:16]+'.apk'
helper = remote+'/verifier.jar'
listing = remote+'/classes.txt'
command = [str(adb), '-s', args.serial]
subprocess.run(command+['shell', 'mkdir', '-p', remote], check=True)
# Only task-owned files are made writable before replacing an earlier test copy.
subprocess.run(command+['shell', 'chmod', '644', helper, target, listing], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for local, destination in [(jar, helper), (apk, target), (class_list, listing)]:
        subprocess.run(command+['push', str(local), destination], check=True)
    subprocess.run(command+['shell', 'chmod', '444', helper, target], check=True)
    result = subprocess.run(command+['shell', 'CLASSPATH='+helper, 'app_process', '/system/bin',
                             'com.yukisoffd.lyra.x11.tools.GuiArtVerifier', target, listing],
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding='utf-8', errors='replace')
    (work/'art-verification.log').write_text(result.stdout, encoding='utf-8')
    print(result.stdout, end='')
    if result.returncode or 'ART_GUI_VERIFY' not in result.stdout or 'failures=0' not in result.stdout:
        sys.exit(1)
finally:
    subprocess.run(command+['shell', 'rm', '-f', helper, target, listing], check=False)
