#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下载 sherpa-onnx 安卓预编译 AAR 到 android/app/libs/。

用法：
    python3 scripts/fetch_android_aar.py                 # 自动取 GitHub 最新 release
    python3 scripts/fetch_android_aar.py --version 1.12.14
    python3 scripts/fetch_android_aar.py --proxy https://ghproxy.net/   # GitHub 访问受限时加代理前缀

仅依赖 Python 标准库。
"""
import argparse
import json
import os
import re
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBS = os.path.join(ROOT, "android", "app", "libs")
API = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases"


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "BilingNotes/0.1"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", help="指定版本号，如 1.12.14；缺省取最新 release")
    ap.add_argument("--proxy", default="", help="下载代理前缀，如 https://ghproxy.net/")
    args = ap.parse_args()

    api_url = API + ("/tags/v" + args.version if args.version else "/latest")
    print("查询 release: %s" % api_url)
    rel = json.loads(get(api_url).decode("utf-8"))
    tag = rel.get("tag_name", "?")

    asset = None
    for a in rel.get("assets", []):
        if re.fullmatch(r"sherpa-onnx-[\d.]+\.aar", a["name"]):
            asset = a
            break
    if asset is None:
        raise SystemExit("该 release 未找到 sherpa-onnx-*.aar 资产，请换 --version 重试")

    url = args.proxy + asset["browser_download_url"]
    dest = os.path.join(LIBS, asset["name"])
    os.makedirs(LIBS, exist_ok=True)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("已存在: %s" % dest)
        return

    print("下载 %s (%s, %.1f MB)" % (url, tag, asset["size"] / 1e6))
    data = get(url)
    tmp = dest + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dest)
    print("完成: %s" % dest)


if __name__ == "__main__":
    main()
