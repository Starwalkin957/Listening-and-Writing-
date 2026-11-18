#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
下载端侧模型（识别 / 声纹 / 标点）。

用法（在仓库根目录运行）：
    python3 scripts/download_models.py --target oh        # 鸿蒙：识别+声纹模型放入 rawfile
    python3 scripts/download_models.py --target android   # 安卓：下载到 models-cache/（应用也会自动下载）
    python3 scripts/download_models.py --target both --punct   # 附带标点模型（仅安卓端使用）
    python3 scripts/download_models.py --target oh --mirror hf # 改用 huggingface 官方源

仅依赖 Python 标准库。识别模型默认走 hf-mirror.com 国内镜像；
声纹/标点模型来自 GitHub release，受限时自动尝试 ghproxy.net 代理。
"""
import argparse
import os
import sys
import tarfile
import urllib.request

MODEL_DIR = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20"
ASR_FILES = [
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt",
    "bpe.model",  # 安卓热词增强需要；鸿蒙一并放入无副作用
]

BASES = {
    "hf-mirror": "https://hf-mirror.com/csukuangfj/%s/resolve/main/" % MODEL_DIR,
    "hf": "https://huggingface.co/csukuangfj/%s/resolve/main/" % MODEL_DIR,
}

SPEAKER_FILE = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
SPEAKER_URL = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/"
               "speaker-recongition-models/" + SPEAKER_FILE)

PUNCT_TAR = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"
PUNCT_URL = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/"
             "punctuation-models/" + PUNCT_TAR)

GH_PROXIES = ["", "https://ghproxy.net/"]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def download(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("  已存在，跳过: %s" % dest)
        return
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".tmp"
    print("  下载: %s" % url)
    req = urllib.request.Request(url, headers={"User-Agent": "BilingNotes/0.1"})
    with urllib.request.urlopen(req, timeout=120) as resp, open(tmp, "wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = resp.read(256 * 1024)
            if not chunk:
                break
            out.write(chunk)
            done += len(chunk)
            if total:
                sys.stdout.write("\r    %d%% (%.1f MB)" % (done * 100 // total, done / 1e6))
                sys.stdout.flush()
    print()
    os.replace(tmp, dest)


def download_github(url: str, dest: str) -> None:
    last = None
    for proxy in GH_PROXIES:
        try:
            download(proxy + url, dest)
            return
        except Exception as e:  # noqa: BLE001
            last = e
            if os.path.exists(dest + ".tmp"):
                os.remove(dest + ".tmp")
    raise SystemExit("下载失败 %s: %s" % (url, last))


def fetch_asr(dest_dir: str, mirror: str) -> None:
    order = [mirror, "hf" if mirror == "hf-mirror" else "hf-mirror"]
    for name in ASR_FILES:
        dest = os.path.join(dest_dir, name)
        last = None
        for key in order:
            try:
                download(BASES[key] + name, dest)
                last = None
                break
            except Exception as e:  # noqa: BLE001
                last = e
                if os.path.exists(dest + ".tmp"):
                    os.remove(dest + ".tmp")
        if last is not None:
            raise SystemExit("下载失败 %s: %s" % (name, last))


def fetch_punct(dest_dir: str) -> None:
    os.makedirs(dest_dir, exist_ok=True)
    target = os.path.join(dest_dir, "model.int8.onnx")
    if os.path.exists(target) and os.path.getsize(target) > 0:
        print("  已存在，跳过: %s" % target)
        return
    tar_path = os.path.join(dest_dir, PUNCT_TAR)
    download_github(PUNCT_URL, tar_path)
    print("  解压 model.int8.onnx …")
    with tarfile.open(tar_path, "r:bz2") as tf:
        for m in tf.getmembers():
            if m.name.endswith("model.int8.onnx"):
                f = tf.extractfile(m)
                with open(target, "wb") as out:
                    out.write(f.read())
                break
    os.remove(tar_path)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", choices=["android", "oh", "both"], default="both")
    ap.add_argument("--mirror", choices=["hf-mirror", "hf"], default="hf-mirror")
    ap.add_argument("--punct", action="store_true", help="附带标点模型（仅安卓端使用）")
    args = ap.parse_args()

    if args.target in ("oh", "both"):
        raw = os.path.join(ROOT, "openharmony", "entry", "src", "main", "resources", "rawfile")
        print("[鸿蒙] 识别模型写入 rawfile（构建时打包进 HAP）:")
        fetch_asr(os.path.join(raw, MODEL_DIR), args.mirror)
        print("[鸿蒙] 声纹模型（说话人区分）:")
        download_github(SPEAKER_URL, os.path.join(raw, SPEAKER_FILE))

    if args.target in ("android", "both"):
        cache = os.path.join(ROOT, "models-cache")
        print("[安卓] 识别模型缓存（应用首次启动也会自动下载，此步可跳过）:")
        fetch_asr(os.path.join(cache, MODEL_DIR), args.mirror)
        print("[安卓] 声纹模型缓存（应用会自动下载，此步可跳过）:")
        download_github(SPEAKER_URL, os.path.join(cache, "speaker", SPEAKER_FILE))
        if args.punct:
            print("[安卓] 标点模型（应用会自动下载，此步可跳过）:")
            fetch_punct(os.path.join(cache, "punct"))

    print("完成。")


if __name__ == "__main__":
    main()
