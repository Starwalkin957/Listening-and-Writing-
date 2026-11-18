# 双语语音纪要（Bilingual Voice Notes）

安卓 / 开源鸿蒙（OpenHarmony）双端原生应用：**端侧离线**的中英双语语音识别与纪要工具，面向个人 / 小团队自用。全部功能模块（M1～M5）已实现。

## 功能总览（对照原始需求）

| # | 需求 | 实现方式 | 安卓 | 鸿蒙 |
|---|------|---------|------|------|
| 1 | 中英双语准确识别、穿插直接记录 | sherpa-onnx 流式双语 Zipformer，单模型原生支持中英混说，端点自动断句 | ✅ | ✅ |
| 2 | 随说随写 + 同声传译 | 100ms 级实时上屏；每句定稿后自动中↔英互译（逐句近实时同传，需配置 LLM） | ✅ | ✅ |
| 3 | 历史备份 + 内容总结 | 停止即自动入库；整库备份导出/导入；LLM 一键生成结构化纪要 | ✅ | ✅ |
| 4 | 系列纪要库 + 其他文件整合 | 系列创建/筛选/归档；附件整合（安卓 txt/md/docx/pdf，鸿蒙 txt/md 等纯文本） | ✅ | ✅ |
| 5 | 分辨不同说话人 | 3D-Speaker 声纹嵌入 + 在线注册/检索，自动「说话人N」，点击可重命名 | ✅ | ✅ |
| 6 | 识别准确度 99% | 热词增强 + 自动标点（安卓）+ LLM 术语纠错；见「关于准确率」 | 工程目标 | 工程目标 |
| 7 | 计算机专业知识库 | 识别端约 200 词热词表 + 理解端 120 条术语知识库，总结/纠错自动注入相关术语 | ✅ | ✅（热词待接口验证） |

## 架构

```
┌─────────────────────────┐      ┌─────────────────────────┐
│ Android (Kotlin/Compose) │      │ OpenHarmony (ArkTS/ArkUI) │
│ 记录 / 纪要库 / 设置 三页  │      │ 记录 / 纪要库 / 设置 三页   │
│ AudioRecord 16kHz        │      │ AudioCapturer 48kHz      │
│ 前台服务保活              │      │ Worker 线程识别           │
└────────────┬────────────┘      └────────────┬────────────┘
             │     同一套端侧引擎与模型（共享核心层）      │
             ▼                                ▼
   sherpa-onnx（Apache-2.0）：流式双语 ASR + 声纹嵌入 + 标点(安卓)
             │
   本地 JSON 纪要库（系列/说话人/附件/总结/纠错稿）
             │
   可选 LLM 层（OpenAI 兼容协议）：同传 / 总结 / 术语纠错
   —— 可指向云端 API，或局域网 ollama / llama.cpp server / vLLM
```

**隐私边界**：语音识别、声纹区分、纪要存储全部在本机离线完成。只有两类数据会出设备：
① 首次下载模型；② 你主动配置并启用 LLM 后，转写文本会发往你填写的服务地址（可用局域网服务做到不出内网）。

## 目录结构

```
bilingual-voice-notes/
├── android/                     # Android Studio 打开此目录
│   └── app/src/main/java/com/qwen/bilingnotes/
│       ├── MainActivity.kt / ui/            # 三页 UI（记录/纪要库/设置）
│       ├── MainViewModel.kt                 # 录音管线：ASR→标点→声纹→入库→同传
│       ├── LibraryViewModel.kt              # 纪要库/总结/纠错/翻译/备份/附件
│       ├── SettingsViewModel.kt             # LLM 配置与连接测试
│       ├── asr/                             # ASR 引擎、标点、模型下载管理
│       ├── speaker/SpeakerEngine.kt         # 声纹嵌入+在线聚类
│       ├── llm/                             # LLM 客户端 + 计算机知识库
│       ├── data/                            # 纪要库持久化/备份导入导出
│       ├── service/RecordingService.kt      # 录音前台保活
│       └── util/FileTextExtractor.kt        # txt/md/docx/pdf 文本提取
├── openharmony/                 # DevEco Studio 打开此目录
│   └── entry/src/main/ets/
│       ├── pages/Index.ets                  # 三页 UI + 录音采集
│       ├── workers/AsrWorker.ets            # ASR + 声纹（Worker 线程）
│       └── common/                          # 纪要库 / LLM / 知识库 / 权限
├── shared/                      # 两端共用资产：热词表 + 术语知识库
└── scripts/                     # 模型与依赖下载脚本
```

## 构建步骤

### 安卓

1. Android Studio（Ladybug+）+ JDK 17。
2. 下载引擎 AAR（必需）：
   ```bash
   python3 scripts/fetch_android_aar.py
   # GitHub 受限：python3 scripts/fetch_android_aar.py --proxy https://ghproxy.net/
   ```
3. 打开 `android/` 同步 Gradle（首次自动下载 Gradle 8.9；命令行构建先执行 `gradle wrapper`）。
4. 运行到 Android 8.0+ 手机（建议 arm64 中高端机型）。
5. 首次点「开始记录」自动下载：识别模型（约100MB，hf-mirror）→ 声纹模型（约28MB）→ 标点模型（约65MB）。
   后两者失败会自动跳过（对应功能禁用），不影响核心识别。之后完全离线。

### 开源鸿蒙

1. DevEco Studio 5.0+（OpenHarmony SDK API 12+），OpenHarmony 5.0 开发者手机。
2. 下载模型到 rawfile（必需）：
   ```bash
   python3 scripts/download_models.py --target oh
   ```
3. 打开 `openharmony/`，IDE 自动 ohpm 安装 `sherpa_onnx`（或手动 `ohpm install sherpa_onnx`）。
4. 配置调试签名后运行。

## 使用指南

- **记录页**：选系列 → 开始记录 → 中英随便说，实时上屏、自动断句、自动标说话人；
  点说话人标签可重命名（如「张工」）；开「逐句同传」后每句下方出现译文；停止即自动入库。
- **纪要库页**：按系列筛选；进入详情可「生成总结 / 术语纠错 / 全文翻译 / 整合文件 / 导出MD / 删除」；
  点标题重命名，点系列名归档到其他系列。
- **设置页**：填 LLM 服务地址（OpenAI 兼容 `/v1`）、Key、模型名 → 测试连接；整库备份导出/导入。
  - 云端示例：阿里百炼 `https://dashscope.aliyuncs.com/compatible-mode/v1` + API Key + `qwen-plus`
  - 局域网示例：`http://192.168.x.x:11434/v1`（ollama）+ 留空 Key + `qwen2.5:7b`
- **备份互通**：安卓导出 zip（内含 db.json），鸿蒙导出裸 JSON；安卓两种都能导入，鸿蒙导入 JSON。

## 关于准确率（务必阅读）

没有任何语音识别系统能在所有真实场景承诺 99%。本工程口径：

- 安静环境、标准口音、单人：开源流式双语模型典型字准确率约 90%～95%；中英混说、噪声、多人抢话会下降。
- 提升手段已全部内置：术语热词（识别端）、自动标点（安卓）、声纹分句、LLM 术语纠错（理解端）。
- 说话人区分准确率受麦克风距离/重叠讲话影响，阈值 0.6 可在 SpeakerEngine 中调整。
- 验收建议：自建 30～60 分钟中英混说测试集（含计算机术语、2～3 人），按 CER/WER 与说话人准确率统计，
  把「99%」定义为该测试集上的逼近目标。

## 已知边界

- 同传为**逐句近实时**（一句结束后 1～3 秒出译文，取决于 LLM 速度），不是逐词耳语式同传；
  端侧小模型跑不动高质量翻译，故翻译/总结走可配置 LLM 层（可局域网部署保持数据不出内网）。
- 鸿蒙端暂无标点恢复（ArkTS 接口未开放）与 docx/pdf 解析（建议安卓端整合后经备份同步）；
  鸿蒙端热词增强待 sherpa_onnx ArkTS 接口验证后补齐。
- 安卓息屏录音由前台服务保活，但各厂商省电策略仍可能杀后台，建议加入电池优化白名单。
- 本工程在云端编写，未经真机编译验证；双端接口均按 sherpa-onnx 官方最新 API 与官方示例核对，
  首次构建如遇小版本差异，以实际依赖版本接口为准微调。
- 纪要库为本地 JSON 存储，适合个人/小团队（数千句规模）；超大规模建议后续迁移 SQLite。

## 许可与致谢

- 应用代码：Apache-2.0。
- sherpa-onnx：Apache-2.0（k2-fsa / Next-gen Kaldi）；模型许可见各自原始仓库（icefall / 3D-Speaker）。
- PDF 解析：PDFBox（Apache-2.0，pdfbox-android 移植）。

---

内容由 AI 生成
