此目录存放 sherpa-onnx 预编译 AAR（构建必需）。

在仓库根目录运行：

    python3 scripts/fetch_android_aar.py

脚本自动从 k2-fsa/sherpa-onnx 的 GitHub release 下载 sherpa-onnx-<版本>.aar 到此处。
GitHub 访问受限时可加代理前缀：

    python3 scripts/fetch_android_aar.py --proxy https://ghproxy.net/

也可手动下载后放入本目录（文件名形如 sherpa-onnx-1.12.14.aar）。
