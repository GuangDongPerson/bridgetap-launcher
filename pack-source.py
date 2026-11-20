"""
Builds the source archive that is attached to a release.

The archive mirrors the repository root, so unpacking it and pushing the contents recreates the
project exactly:

    BridgeTap-Source-<version>/
        .gitattributes  .gitignore  CHANGELOG.md  LICENSE  THIRD-PARTY.md
        README.md  READMEzhcn.md  READMEzhtw.md  pack-source.py
        licenses/
        BridgeTap-Compat/
        BridgeTap-Launcher/

Build products, downloaded dependencies and machine-specific toolchains are left out; every one of
them can be rebuilt from what is inside the archive.

打源码包：产出的 zip 与仓库根目录同构，解压后直接推上去就是完整项目。

    BridgeTap-Source-<版本>/
        .gitattributes  .gitignore  CHANGELOG.md  LICENSE  THIRD-PARTY.md
        README.md  READMEzhcn.md  READMEzhtw.md  pack-source.py
        licenses/
        BridgeTap-Compat/
        BridgeTap-Launcher/

构建产物、下载来的依赖、跟本机绑定的工具链都不打进去：它们全都能从包里的源码重建。
"""
import json
import os
import zipfile

ROOT = os.path.abspath('.')

# Directories copied wholesale, minus the skip rules below
# 整目录拷贝的顶层目录，跳过规则见下
INCLUDE_DIRS = ['BridgeTap-Compat', 'BridgeTap-Launcher', 'licenses']

# Single files at the repository root
# 仓库根目录下的单文件
ROOT_FILES = [
    '.gitattributes',
    '.gitignore',
    'CHANGELOG.md',
    'LICENSE',
    'README.md',
    'READMEzhcn.md',
    'READMEzhtw.md',
    'THIRD-PARTY.md',
    'pack-source.py',
]

# Any path segment equal to one of these is pruned
# 路径里出现这些目录名就整枝跳过
SKIP_DIRS = {
    'node_modules', 'dist', 'dist-test', '.gradle', '.git', 'snapshots',
    '.tools', '.pack', '.workbuddy', '__pycache__', 'build',
}

# Individual files that can be regenerated and are large or platform bound
# 可重建且体积大 / 与平台绑定的单个文件
SKIP_FILES = {
    'BridgeTap-Compat/native/agent.jar',
    'BridgeTap-Compat/native/hud.jar',
    'BridgeTap-Compat/native/payload.dll',
    'BridgeTap-Launcher/vendor/launcher/BridgeTap.exe',
}

# Documentation screenshots that are actually referenced; the rest of docs/*.png is dev output
# 文档里真正引用到的截图；docs 下其余 png 属于开发过程产物
KEEP_PNG = {
    'color-light.png',
    'color-open.png',
    'icon-preview.png',
    'shot-devices.png',
    'theme-dark.png',
    'theme-light.png',
}


def keep(rel):
    if rel in SKIP_FILES:
        return False
    # MiSans cannot be redistributed; scripts/fetch-fonts.js pulls it back before a build
    # MiSans 不能随仓库分发，构建前由 scripts/fetch-fonts.js 取回
    if '/assets/fonts/misans/' in rel:
        return False
    # electron-builder needs build/icon.ico and build/icon.png at packaging time
    # electron-builder 打包要用 build/icon.ico 与 build/icon.png
    if rel.startswith('BridgeTap-Launcher/build/'):
        return True
    if any(part in SKIP_DIRS for part in rel.split('/')):
        return False
    if '/docs/' in rel and rel.lower().endswith('.png') and os.path.basename(rel) not in KEEP_PNG:
        return False
    return True


def collect():
    entries = []
    for item in INCLUDE_DIRS:
        for base, dirs, files in os.walk(item):
            rel_base = os.path.relpath(base, ROOT).replace('\\', '/')
            # Do not prune BridgeTap-Launcher/build: it holds the packaging icons
            # 不要剪掉 BridgeTap-Launcher/build：里面是打包图标
            allow_build = rel_base == 'BridgeTap-Launcher'
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS or (allow_build and d == 'build')]
            for name in files:
                rel = os.path.relpath(os.path.join(base, name), ROOT).replace('\\', '/')
                if keep(rel):
                    entries.append(rel)
    return sorted(entries)


def main():
    with open('BridgeTap-Launcher/package.json', encoding='utf-8') as fh:
        version = json.load(fh)['version']
    name = 'BridgeTap-Source-' + version
    out = name + '.zip'

    entries = collect()
    roots = [f for f in ROOT_FILES if os.path.isfile(f)]
    missing = [f for f in ROOT_FILES if f not in roots]
    if missing:
        raise SystemExit('missing root file(s): ' + ', '.join(missing))

    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel in roots:
            z.write(rel, name + '/' + rel)
        for rel in entries:
            z.write(rel, name + '/' + rel)

    raw = sum(os.path.getsize(f) for f in roots) + sum(os.path.getsize(e) for e in entries)
    print('version : ' + version)
    print('files   : %d (+%d at root)' % (len(entries), len(roots)))
    print('raw     : %.1f MB' % (raw / 1024 / 1024))
    print('archive : %s  %.2f MB' % (out, os.path.getsize(out) / 1024 / 1024))


if __name__ == '__main__':
    main()
