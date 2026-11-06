"""Regenerate bundled build inputs on Linux (C compiler, Bison 3.8, xkeyboard-config 2.42).

Normal Android builds use the checked-in outputs and do not require these tools.
Pass the unpacked pinned Termux cpp source and an extracted xkb-data directory.
"""
import argparse, os, pathlib, subprocess, tempfile, zipfile

HERE=pathlib.Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--source',type=pathlib.Path,required=True)
parser.add_argument('--xkb',type=pathlib.Path,required=True)
parser.add_argument('--cc',default='cc')
parser.add_argument('--bison',default='bison')
args=parser.parse_args()
source=args.source.resolve()
out=HERE/'generated';out.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory() as tmp:
    makekeys=pathlib.Path(tmp)/'makekeys'
    subprocess.run([args.cc,str(source/'libx11/src/util/makekeys.c'),'-I'+str(source/'xorgproto/include'),'-o',str(makekeys)],check=True)
    with (out/'ks_tables.h').open('wb') as file:
        subprocess.run([makekeys,'keysymdef.h','XF86keysym.h','Sunkeysym.h','DECkeysym.h','HPkeysym.h'],cwd=source/'xorgproto/include/X11',stdout=file,check=True)
    subprocess.run([args.bison,'-o',str(out/'xkbparse.c'),'xkbcomp/xkbparse.y'],cwd=source,check=True)
assets=HERE.parents[1]/'src/main/assets/lyra_x11'
with zipfile.ZipFile(assets/'xkb-data.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(args.xkb.rglob('*')):
        if path.is_file():
            info=zipfile.ZipInfo('xkb/'+path.relative_to(args.xkb).as_posix(),(2024,7,2,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=0o100644<<16
            archive.writestr(info,path.read_bytes())
print('Regenerated inputs; review their changes and update sources.json checksums before rebuilding.')
