#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
核对 launcher_new.db 的 SCREEN_INFO 与 release/*.apk 是否一致。

桌面判断插件有没有更新，只认 SCREEN_INFO 里的 FILE_SIZE / MD5；
只换 APK 不改这两列，桌面会继续用 plugins_data 里的旧 dex。
这个脚本在 PC 上跑，不需要电视上有 sqlite3。

用法：
    python release/check-db.py                 # 只检查（默认 release/）
    python release/check-db.py --fix           # 把大小 / MD5 / 版本号 / 路径写回数据库
    python release/check-db.py --dir release --repo .
    python release/check-db.py --db release/launcher_new.db --fix

版本号从 <repo>/plugin-<name>/AndroidManifest.xml 读取；读不到就只比大小和 MD5。
"""

import argparse
import hashlib
import os
import re
import sqlite3
import sys

# APK 文件名 -> (包名, 显示名)
PLUGINS = [
    ("news",   "com.dsh.tvdesk.news",   "新闻"),
    ("live",   "com.dsh.tvdesk.live",   "直播"),
    ("douban", "com.dsh.tvdesk.douban", "豆瓣荐片"),
    ("radio",  "com.dsh.tvdesk.radio",  "电台"),
    ("stock",  "com.dsh.tvdesk.stock",  "股票"),
]

REQUIRED_COLS = [
    "TAG", "NAME", "PLUGIN_PATH", "VERSION_NAME", "SCREEN_ORDER",
    "SCREEN_TAG", "VERSION_CODE", "FILE_NAME", "PACKAGE_NAME",
    "SHOW_ON_TAB", "FILE_SIZE", "MD5",
]


def md5_file(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def read_manifest_version(repo, name):
    path = os.path.join(repo, "plugin-" + name, "AndroidManifest.xml")
    if not os.path.isfile(path):
        return None, None
    text = open(path, encoding="utf-8", errors="replace").read()
    code = re.search(r'android:versionCode="(\d+)"', text)
    vname = re.search(r'android:versionName="([^"]+)"', text)
    return (vname.group(1) if vname else None,
            code.group(1) if code else None)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="核对 launcher_new.db 与插件 APK")
    ap.add_argument("--dir", default=here, help="APK / 数据库所在目录（默认 release/）")
    ap.add_argument("--db", default=None, help="数据库路径（默认 <dir>/launcher_new.db）")
    ap.add_argument("--repo", default=os.path.dirname(here), help="仓库根目录（读 AndroidManifest.xml）")
    ap.add_argument("--fix", action="store_true", help="把不一致的大小 / MD5 / 版本号写回数据库")
    args = ap.parse_args()

    db = args.db or os.path.join(args.dir, "launcher_new.db")
    if not os.path.isfile(db):
        print("找不到数据库：%s" % db)
        return 2

    con = sqlite3.connect(db)
    con.row_factory = sqlite3.Row
    cols = [r[1] for r in con.execute("PRAGMA table_info(SCREEN_INFO)")]
    missing_cols = [c for c in REQUIRED_COLS if c not in cols]
    if missing_cols:
        print("数据库结构不完整，缺列：%s" % ", ".join(missing_cols))
        return 2

    rows = {}
    for r in con.execute("SELECT * FROM SCREEN_INFO"):
        if r["PACKAGE_NAME"]:
            rows[r["PACKAGE_NAME"]] = r

    print("数据库：%s" % db)
    print("APK 目录：%s" % os.path.abspath(args.dir))
    print("")
    print("%-8s %-8s %-8s %-8s %-34s %-22s %s" %
          ("插件", "版本", "vcode", "大小", "APK MD5", "数据库记录", "结果"))

    problems = []   # (label, message, fixable)
    fixes = []

    for name, pkg, label in PLUGINS:
        apk = os.path.join(args.dir, name + ".apk")
        if not os.path.isfile(apk):
            print("%-8s %s" % (label, "缺少 %s" % apk))
            problems.append((label, "缺 APK", False))
            continue

        size = os.path.getsize(apk)
        md5 = md5_file(apk)
        vname, vcode = read_manifest_version(args.repo, name)
        row = rows.get(pkg)

        if row is None:
            print("%-8s %-8s %-8s %-8s %-34s %-22s %s" %
                  (label, "-", "-", size, md5, "无记录", "缺 SCREEN_INFO"))
            problems.append((label, "缺 SCREEN_INFO 记录", False))
            continue

        row_size = str(row["FILE_SIZE"] or "")
        row_md5 = str(row["MD5"] or "").upper()
        row_vname = str(row["VERSION_NAME"] or "")
        row_vcode = str(row["VERSION_CODE"] or "")
        exp_path = "/data/data/com.stv.launcher/files/plugins/%s.apk" % name
        exp_file = name + ".apk"

        fix_errs = []
        hard_errs = []
        if row_size != str(size):
            fix_errs.append("大小 %s→%s" % (row_size or "空", size))
        if row_md5 != md5:
            fix_errs.append("MD5 %s→%s" % (row_md5[:8] or "空", md5[:8]))
        if vname and row_vname != vname:
            fix_errs.append("版本名 %s→%s" % (row_vname or "空", vname))
        if vcode and row_vcode != vcode:
            fix_errs.append("vcode %s→%s" % (row_vcode or "空", vcode))
        if str(row["PLUGIN_PATH"] or "") != exp_path:
            fix_errs.append("路径 %s→%s" % (row["PLUGIN_PATH"] or "空", exp_path))
        if str(row["FILE_NAME"] or "") != exp_file:
            fix_errs.append("文件名 %s→%s" % (row["FILE_NAME"] or "空", exp_file))
        if str(row["SHOW_ON_TAB"]) != "1":
            hard_errs.append("SHOW_ON_TAB=%s（需手工改）" % row["SHOW_ON_TAB"])

        errs = fix_errs + hard_errs
        print("%-8s %-8s %-8s %-8s %-34s %-22s %s" %
              (label, row_vname or "-", row_vcode or "-", size, md5,
               "%s 字节 / %s" % (row_size or "-", row_md5[:8] or "-"),
               "OK" if not errs else "；".join(errs)))

        if fix_errs:
            problems.append((label, "；".join(fix_errs), True))
            if args.fix:
                fixes.append((pkg, size, md5, vname or row_vname,
                              vcode or row_vcode, exp_path, exp_file))
        if hard_errs:
            problems.append((label, "；".join(hard_errs), False))

    if args.fix and fixes:
        for pkg, size, md5, vname, vcode, ppath, fname in fixes:
            con.execute(
                "UPDATE SCREEN_INFO SET FILE_SIZE=?, MD5=?, VERSION_NAME=?, "
                "VERSION_CODE=?, PLUGIN_PATH=?, FILE_NAME=? WHERE PACKAGE_NAME=?",
                (str(size), md5, vname, vcode, ppath, fname, pkg),
            )
        con.commit()
        print("")
        print("已写回 %d 条记录。" % len(fixes))
        problems = [p for p in problems if not p[2]]

    con.close()
    print("")
    if problems:
        left = [p for p in problems if not p[2]]
        if left:
            print("仍有 %d 处无法自动修复：" % len(left))
            for label, msg, _ in left:
                print("  - %s: %s" % (label, msg))
        else:
            print("发现 %d 处不一致。修法：python %s --fix"
                  % (len(problems), os.path.relpath(__file__)))
        return 1
    print("SCREEN_INFO 与 APK 一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
