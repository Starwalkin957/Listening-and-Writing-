此目录用于存放端侧识别模型（构建前必须就位，否则应用初始化会失败）。

在仓库根目录运行：

    python3 scripts/download_models.py --target oh

脚本会把以下文件下载到本目录下的
sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/ 子目录：

    encoder-epoch-99-avg-1.int8.onnx
    decoder-epoch-99-avg-1.onnx
    joiner-epoch-99-avg-1.int8.onnx
    tokens.txt

模型来源：k2-fsa/sherpa-onnx 官方发布的中英双语流式 Zipformer 模型（Apache-2.0 生态，
模型本身 license 见原始仓库 icefall）。下载默认走 hf-mirror.com 国内镜像。
