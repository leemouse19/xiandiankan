# -*- coding: utf-8 -*-
"""inject.py：把 dex + X5 内核离线包塞进 base.apk，并把 resources.arsc 改 STORED。
享看电影 Phase 1 版：只有 dex + X5 离线内核 .tbs.apk（按 ABI 分目录），无 .so / 无 tvext。
"""
import glob, os, zipfile

ROOT = r"D:/WorkBuddy/apk/moontv"
SRC  = ROOT + "/out/base.apk"
DST  = ROOT + "/out/base2.apk"

z = zipfile.ZipFile(SRC)
with zipfile.ZipFile(DST, 'w', zipfile.ZIP_DEFLATED) as dst:
    for n in z.namelist():
        if n == "resources.arsc":
            # 安卓 11+ 必须不压缩，否则 -124 拒装
            dst.writestr(n, z.read(n), compress_type=zipfile.ZIP_STORED)
        else:
            dst.writestr(n, z.read(n))
    # 主 dex + 从 dex
    dexes = sorted(glob.glob(ROOT + "/dex/classes*.dex"))
    for d in dexes:
        dst.write(d, os.path.basename(d))
    # X5 离线包 .tbs.apk（内置 X5 内核主体）。按 ABI 分目录存放，运行时按进程位宽选对应内核。
    # 目标文件名统一为 tbs_core_release.tbs.apk（与 X5KernelManager.copyOfflinePackage 的 assets 路径一致）。
    # 源文件名带 _arm64 后缀仅用于构建侧区分，嵌入时必须去掉，否则运行时报 FileNotFound。
    TBS_ABI_MAP = {
        "tbs_core_release.tbs.apk": "armeabi_v7a",
        "tbs_core_release_arm64.tbs.apk": "arm64_v8a",
    }
    x5tbs = sorted(glob.glob(ROOT + "/x5kernel/*.tbs.apk"))
    for t in x5tbs:
        name = os.path.basename(t)
        abi = TBS_ABI_MAP.get(name, "armeabi_v7a")
        dst_name = "tbs_core_release.tbs.apk"
        dst.write(t, "assets/%s/%s" % (abi, dst_name))
        print("inject: X5 kernel package -> assets/%s/%s (from %s)" % (abi, dst_name, name))
    # X5 内核 .so（如有；内置离线包通常不带独立 .so，保留以兼容）
    x5sos = sorted(glob.glob(ROOT + "/x5kernel/*.so"))
    for s in x5sos:
        dst.write(s, "lib/armeabi-v7a/" + os.path.basename(s))
z.close()
os.replace(DST, SRC)

z = zipfile.ZipFile(SRC)
libs = [n for n in z.namelist() if n.startswith("lib/")]
assets = [n for n in z.namelist() if n.startswith("assets/")]
print("injected: entries=%d lib=%d assets=%d" % (len(z.namelist()), len(libs), len(assets)))
if libs:
    print("so total: %.1fMB" % (sum(z.getinfo(n).file_size for n in libs) / 1048576.0))
x5count = len([n for n in z.namelist() if 'tbs' in n.lower() or 'x5' in n.lower()])
print("x5 kernel entries: %d" % x5count)
