import worker, { ErrorEvent, MessageEvents, ThreadWorkerGlobalScope } from '@ohos.worker';
import {
  OnlineModelConfig,
  OnlineRecognizer,
  OnlineRecognizerConfig,
  OnlineStream,
  SpeakerEmbeddingExtractor,
  SpeakerEmbeddingExtractorConfig,
  SpeakerEmbeddingManager
} from 'sherpa_onnx';

const workerPort: ThreadWorkerGlobalScope = worker.workerPort;

let recognizer: OnlineRecognizer | undefined = undefined;
let micStream: OnlineStream | undefined = undefined;

let extractor: SpeakerEmbeddingExtractor | undefined = undefined;
let manager: SpeakerEmbeddingManager | undefined = undefined;
let speakerCounter: number = 0;

// 模型由 scripts/download_models.py --target oh 放入 rawfile
const MODEL_DIR = 'sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20';
const SPEAKER_MODEL = '3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx';
const SAMPLE_RATE = 48000;
const SPEAKER_THRESHOLD = 0.6;

// 当前句音频缓冲（供声纹计算），上限约 30 秒
let audioBuffer: Float32Array[] = [];
let bufferedSamples: number = 0;

function initStreamingAsr(context: Context): OnlineRecognizer {
  const modelConfig = new OnlineModelConfig();
  modelConfig.transducer.encoder = `${MODEL_DIR}/encoder-epoch-99-avg-1.int8.onnx`;
  modelConfig.transducer.decoder = `${MODEL_DIR}/decoder-epoch-99-avg-1.onnx`;
  modelConfig.transducer.joiner = `${MODEL_DIR}/joiner-epoch-99-avg-1.int8.onnx`;
  modelConfig.tokens = `${MODEL_DIR}/tokens.txt`;
  modelConfig.modelType = 'zipformer';
  modelConfig.numThreads = 2;
  modelConfig.debug = false;

  const config = new OnlineRecognizerConfig();
  config.modelConfig = modelConfig;
  config.enableEndpoint = true;

  return new OnlineRecognizer(config, context.resourceManager);
}

function initSpeaker(context: Context): boolean {
  try {
    const config = new SpeakerEmbeddingExtractorConfig();
    config.model = SPEAKER_MODEL;
    config.numThreads = 1;
    config.debug = false;
    extractor = new SpeakerEmbeddingExtractor(config, context.resourceManager);
    manager = new SpeakerEmbeddingManager(extractor.dim);
    return true;
  } catch (e) {
    console.error(`speaker init failed (可继续，无说话人区分): ${e}`);
    extractor = undefined;
    manager = undefined;
    return false;
  }
}

function bufferAudio(samples: Float32Array): void {
  audioBuffer.push(samples);
  bufferedSamples += samples.length;
  const cap = SAMPLE_RATE * 30;
  while (bufferedSamples > cap && audioBuffer.length > 0) {
    const first = audioBuffer.shift();
    if (first) {
      bufferedSamples -= first.length;
    }
  }
}

/** 取最近 ≤15 秒音频计算声纹并匹配/注册说话人 */
function identifySpeaker(): string {
  if (!extractor || !manager) {
    return '';
  }
  const recent: Float32Array[] = [];
  let n = 0;
  for (let i = audioBuffer.length - 1; i >= 0; i--) {
    recent.unshift(audioBuffer[i]);
    n += audioBuffer[i].length;
    if (n >= SAMPLE_RATE * 15) {
      break;
    }
  }
  audioBuffer = [];
  bufferedSamples = 0;
  if (n < SAMPLE_RATE * 0.6) {
    return '';
  }
  const flat = new Float32Array(n);
  let off = 0;
  for (const c of recent) {
    flat.set(c, off);
    off += c.length;
  }
  try {
    const stream = extractor.createStream();
    stream.acceptWaveform({ samples: flat, sampleRate: SAMPLE_RATE });
    const v = extractor.compute(stream);
    let name = manager.search({ threshold: SPEAKER_THRESHOLD, v: v });
    if (name === '' || name === undefined) {
      speakerCounter += 1;
      name = `说话人${speakerCounter}`;
      manager.add({ name: name, v: v });
    }
    return name;
  } catch (e) {
    console.error(`speaker identify failed: ${e}`);
    return '';
  }
}

workerPort.onmessage = (e: MessageEvents) => {
  const msgType = e.data['msgType'] as string;

  if (msgType === 'init-asr' && !recognizer) {
    try {
      const context = e.data['context'] as Context;
      recognizer = initStreamingAsr(context);
      const speakerOk = initSpeaker(context);
      workerPort.postMessage({ 'msgType': 'init-asr-done', 'speakerEnabled': speakerOk });
    } catch (err) {
      workerPort.postMessage({
        'msgType': 'init-asr-failed',
        'error': `初始化失败：请确认模型已放入 rawfile（见 scripts/download_models.py）。${err}`
      });
    }
  }

  if (msgType === 'mic-start' && recognizer) {
    micStream = recognizer.createStream();
    audioBuffer = [];
    bufferedSamples = 0;
  }

  if (msgType === 'mic-samples' && recognizer && micStream) {
    const samples = e.data['samples'] as Float32Array;
    const sampleRate = e.data['sampleRate'] as number;

    bufferAudio(samples);
    micStream.acceptWaveform({ samples, sampleRate });
    while (recognizer.isReady(micStream)) {
      recognizer.decode(micStream);

      let isEndpoint = false;
      const text = recognizer.getResult(micStream).text;

      if (recognizer.isEndpoint(micStream)) {
        isEndpoint = true;
        recognizer.reset(micStream);
      }

      if (text.trim() !== '') {
        let speaker = '';
        if (isEndpoint) {
          speaker = identifySpeaker();
        }
        workerPort.postMessage({
          'msgType': 'mic-result',
          'text': text,
          'isEndpoint': isEndpoint,
          'speaker': speaker
        });
      } else if (isEndpoint) {
        // 空句也清空缓冲
        audioBuffer = [];
        bufferedSamples = 0;
      }
    }
  }

  if (msgType === 'mic-stop') {
    micStream = undefined;
    audioBuffer = [];
    bufferedSamples = 0;
  }
};

workerPort.onmessageerror = (e: MessageEvents) => {
  console.error('AsrWorker message error');
};

workerPort.onerror = (e: ErrorEvent) => {
  console.error(`AsrWorker error: ${e.message}`);
};
