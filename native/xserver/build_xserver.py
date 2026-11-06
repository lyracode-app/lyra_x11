"""Offline Android X server build. Pinned upstream sources and generated parser are bundled."""
import argparse,hashlib,json,os,pathlib,shutil,subprocess,sys,tarfile

HERE=pathlib.Path(__file__).resolve().parent
MODULE=HERE.parents[1]
def run(*args,**kwargs): subprocess.run([str(x) for x in args],check=True,**kwargs)
def edit(path,old,new,count=-1):
    text=path.read_text(encoding='utf-8')
    if old not in text: raise RuntimeError(f'Missing upstream anchor in {path}: {old[:70]}')
    path.write_text(text.replace(old,new,count),encoding='utf-8',newline='\n')

def prepare():
    staging=HERE/'build/unpacked'; staging.mkdir(parents=True,exist_ok=True)
    hashes=json.loads((HERE/'sources.json').read_text())
    for archive in sorted((HERE/'upstream').glob('*.tar.gz')):
        assert hashlib.sha256(archive.read_bytes()).hexdigest()==hashes['archives'][archive.name]
        with tarfile.open(archive) as tar: tar.extractall(staging,filter='data')
    source=HERE/'build/source'
    shutil.copytree(staging/'termux-x11/lorie/src/main/cpp',source,ignore=shutil.ignore_patterns('.git'))
    for entry in staging.iterdir():
        if entry.name!='termux-x11':
            destination=source/entry.name
            if destination.exists(): destination.rmdir() # Empty gitlink placeholder.
            shutil.copytree(entry,destination)
    # Apply the pinned upstream patches once, fail rather than silently building an unpatched tree.
    targets={'xserver':'xserver','libepoxy':'libepoxy','pixman':'pixman','xkbcomp':'xkbcomp','xkbfile':'libxkbfile','x11':'libx11','Xtrans':'libxtrans'}
    patch=shutil.which('patch')
    if not patch and os.name=='nt': patch=str(pathlib.Path(shutil.which('git')).resolve().parents[1]/'usr/bin/patch.exe')
    for name,directory in targets.items():
        file=source/'patches'/f'{name}.patch'
        if file.exists(): run(patch,'--batch','--forward','-p1','-i',file,cwd=source/directory)
    cmake=source/'CMakeLists.txt'
    edit(cmake,'project(lorie)','project(lyra_xserver LANGUAGES C CXX)')
    edit(cmake,'find_package(BISON REQUIRED)','')
    begin=cmake.read_text().index('function(target_apply_patch')
    end=cmake.read_text().index('endfunction()',begin)+len('endfunction()')
    value=cmake.read_text(); cmake.write_text(value[:begin]+'function(target_apply_patch)\nendfunction()'+value[end:])
    for name in ('xorgproto','xshmfence'):
        file=source/'recipes'/f'{name}.cmake'
        text=file.read_text()
        if name=='xorgproto': text=text.replace('execute_process(COMMAND ${CMAKE_COMMAND} -E create_symlink "${CMAKE_CURRENT_SOURCE_DIR}/libxtrans" "${CMAKE_CURRENT_BINARY_DIR}/X11/Xtrans")','file(COPY "${CMAKE_CURRENT_SOURCE_DIR}/libxtrans/" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/X11/Xtrans")')
        else: text=text.replace('execute_process(COMMAND ${CMAKE_COMMAND} -E create_symlink "${CMAKE_CURRENT_SOURCE_DIR}/libxshmfence/src/xshmfence.h" "${CMAKE_CURRENT_BINARY_DIR}/X11/xshmfence.h")','configure_file("${CMAKE_CURRENT_SOURCE_DIR}/libxshmfence/src/xshmfence.h" "${CMAKE_CURRENT_BINARY_DIR}/X11/xshmfence.h" COPYONLY)')
        file.write_text(text)
    file=source/'recipes/xkbcomp.cmake'; text=file.read_text(); begin=text.index('add_custom_command('); end=text.index('add_library(xkbcomp STATIC')
    text=text[:begin]+'''configure_file("${CMAKE_CURRENT_SOURCE_DIR}/generated/ks_tables.h" "${CMAKE_CURRENT_BINARY_DIR}/ks_tables.h" COPYONLY)
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/generated/xkbparse.c" "${CMAKE_CURRENT_BINARY_DIR}/xkbparse.c" COPYONLY)
'''+text[end:]; file.write_text(text)
    edit(file, '        "libx11/include/X11"\n', '')
    with file.open('a') as out: out.write('\ntarget_compile_options(xkbcomp PRIVATE "-iquote${CMAKE_CURRENT_SOURCE_DIR}/libx11/include/X11")\n')
    shutil.copytree(HERE/'generated',source/'generated')
    file=source/'recipes/xserver.cmake'
    edit(file,'add_library(Xlorie SHARED','add_library(lyra_xserver SHARED')
    edit(file,'Xlorie','lyra_xserver')
    edit(file,'target_link_libraries(lyra_xserver "','target_link_libraries(lyra_xserver PRIVATE "')
    edit(file,'"lorie/activity.cpp"','"lyra/client.cpp"\n        "lyra/surfaces.cpp"\n        "lyra/probe.cpp"')
    edit(file,'"lorie/cmdentrypoint.cpp"','"lyra/server.cpp"')
    with file.open('a') as out: out.write('''
target_link_options(lyra_xserver PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
target_compile_options(lyra_xserver PRIVATE -fno-omit-frame-pointer)
target_include_directories(lyra_xserver SYSTEM PRIVATE "${ANDROID_NDK}/sources/cxx-stl/system/include")
add_executable(lyra_xserver_exec lyra/exec.c)
target_link_libraries(lyra_xserver_exec lyra_xserver)
target_link_options(lyra_xserver_exec PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
''')
    # Native entry points do not load Termux's Java app, global TCP discovery, or app_process.
    original=(source/'lorie/cmdentrypoint.cpp').read_text()
    header=original[:original.index('static int argc = 0;')].replace('#include "lorie.h"','#include "../lorie/lorie.h"')
    core=original[original.index('static Bool handleTouchEvent'):original.index('// Preloaded by rendererCanRelockWhileLocked')]
    networking=original[original.index('bool lorieConnectionAlive'):original.index('static jobject getXConnection')]
    edit(source/'lorie/lorie.h','    EVENT_HEARTBEAT,','    EVENT_HEARTBEAT,\n    EVENT_KEYSYM,\n    EVENT_FRAME,')
    edit(source/'lorie/lorie.h','    uint8_t type;\n    struct {','    uint8_t type;\n    struct { uint8_t t, down; uint32_t sym; } keysym;\n    struct {',count=1)
    core=core.replace('        switch(e.type) {','''        switch(e.type) {
            case EVENT_FRAME:
                lorieFrameTick(); break;
            case EVENT_KEYSYM: {
                uintptr_t value = ((uintptr_t)e.keysym.sym << 1) | (e.keysym.down ? 1 : 0);
                QueueWorkProc(+[](ClientPtr, void *arg) -> Bool {
                    uintptr_t v=(uintptr_t)arg;
                    lorieKeysymKeyboardEvent((KeySym)(v >> 1), v & 1);
                    mieqProcessInputEvents(); return TRUE;
                }, nullptr, (void*)value);
                lorieWakeServer(); break;
            }''')
    (source/'lyra').mkdir()
    (source/'lyra/server_core.inc').write_text(header+'\nvolatile int conn_fd = -1;\nextern DeviceIntPtr lorieMouse, lorieTouch, lorieKeyboard, loriePen, lorieEraser;\nextern ScreenPtr pScreenPtr;\nextern "C" int ucs2keysym(long ucs);\nextern "C" void lorieKeysymKeyboardEvent(KeySym, int);\nextern "C" void lorieFrameTick(void);\nstruct xorg_list registeredBuffers;\n'+core+networking)
    return source

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--ndk',type=pathlib.Path,required=True); parser.add_argument('--cmake',default='cmake'); parser.add_argument('--ninja',default='ninja'); parser.add_argument('--abi',action='append')
    args=parser.parse_args(); source=HERE/'build/source'
    if not source.exists(): source=prepare()
    for name in ('server.cpp','client.cpp','surfaces.cpp','probe.cpp','hooks.h','exec.c'): shutil.copyfile(HERE/name,source/'lyra'/name)
    # Auditable, deterministic DDX/renderer integration changes.
    from adapt import adapt
    adapt(source)
    ndk=args.ndk.resolve(); host='windows-x86_64' if os.name=='nt' else 'linux-x86_64'; suffix='.exe' if os.name=='nt' else ''
    manifest={}
    for abi in args.abi or ['arm64-v8a','x86_64']:
        build=HERE/'build'/abi
        run(args.cmake,'-S',source,'-B',build,'-G','Ninja',f'-DPython3_EXECUTABLE={pathlib.Path(sys.executable).as_posix()}',f'-DCMAKE_MAKE_PROGRAM={pathlib.Path(args.ninja).resolve().as_posix()}',f'-DCMAKE_TOOLCHAIN_FILE={ndk.as_posix()}/build/cmake/android.toolchain.cmake',f'-DANDROID_ABI={abi}','-DANDROID_PLATFORM=android-26','-DANDROID_STL=none')
        run(args.cmake,'--build',build,'--target','lyra_xserver_exec','--parallel','6')
        target=MODULE/'src/main/jniLibs'/abi; target.mkdir(parents=True,exist_ok=True)
        for name,artifact in [('liblyra_xserver.so','liblyra_xserver.so'),('liblyra_xserver_exec.so','lyra_xserver_exec')]:
            output=target/name; temporary=target/(name+'.incoming'); shutil.copyfile(build/artifact,temporary)
            run(ndk/'toolchains/llvm/prebuilt'/host/'bin'/('llvm-strip'+suffix),'--strip-unneeded',temporary)
            os.replace(temporary,output)
            manifest[f'{abi}/{name}']=hashlib.sha256(output.read_bytes()).hexdigest()
    (HERE/'binaries.json').write_text(json.dumps(manifest,indent=2)+'\n')

if __name__=='__main__': main()
