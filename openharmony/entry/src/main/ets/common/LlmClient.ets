import { http } from '@kit.NetworkKit';
import { preferences } from '@kit.ArkData';

export interface LlmSettings {
  baseUrl: string;
  apiKey: string;
  model: string;
  autoTranslate: boolean;
}

export function llmConfigured(s: LlmSettings): boolean {
  return s.baseUrl.trim() !== '' && s.model.trim() !== '';
}

export class LlmPrefs {
  static load(context: Context): LlmSettings {
    const p = preferences.getPreferencesSync(context, { name: 'llm_settings' });
    return {
      baseUrl: p.getSync('baseUrl', '') as string,
      apiKey: p.getSync('apiKey', '') as string,
      model: p.getSync('model', 'qwen-plus') as string,
      autoTranslate: p.getSync('autoTranslate', false) as boolean
    };
  }

  static save(context: Context, s: LlmSettings): void {
    const p = preferences.getPreferencesSync(context, { name: 'llm_settings' });
    p.putSync('baseUrl', s.baseUrl.trim());
    p.putSync('apiKey', s.apiKey.trim());
    p.putSync('model', s.model.trim());
    p.putSync('autoTranslate', s.autoTranslate);
    p.flush();
  }
}

interface ChatMessage {
  role: string;
  content: string;
}

interface ChatBody {
  model: string;
  max_tokens: number;
  temperature: number;
  messages: ChatMessage[];
}

interface ChatChoice {
  message: ChatMessage;
}

interface ChatResponse {
  choices: ChatChoice[];
}

/**
 * OpenAI 兼容 Chat Completions 客户端（云端或局域网自建服务均可）。
 * 识别本身始终离线；此客户端仅用于翻译/总结/纠错。
 */
export class LlmClient {
  private settings: LlmSettings;

  constructor(settings: LlmSettings) {
    this.settings = settings;
  }

  async chat(system: string, user: string, maxTokens: number): Promise<string> {
    const body: ChatBody = {
      model: this.settings.model,
      max_tokens: maxTokens,
      temperature: 0.2,
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: user }
      ]
    };
    let base = this.settings.baseUrl.trim();
    while (base.endsWith('/')) {
      base = base.substring(0, base.length - 1);
    }
    const header: Record<string, string> = { 'Content-Type': 'application/json' };
    if (this.settings.apiKey.trim() !== '') {
      header['Authorization'] = `Bearer ${this.settings.apiKey.trim()}`;
    }
    const req = http.createHttp();
    try {
      const resp = await req.request(base + '/chat/completions', {
        method: http.RequestMethod.POST,
        header: header,
        extraData: JSON.stringify(body),
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 15000,
        readTimeout: 120000
      });
      const text = resp.result as string;
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`LLM HTTP ${resp.responseCode}: ${text.substring(0, 300)}`);
      }
      const parsed = JSON.parse(text) as ChatResponse;
      return parsed.choices[0].message.content.trim();
    } finally {
      req.destroy();
    }
  }

  async translate(text: string): Promise<string> {
    return this.chat(
      '你是专业的同声传译引擎，精通中英互译与计算机专业术语。' +
        '判断输入语言：以中文为主则译为英文，以英文为主则译为中文。' +
        '只输出译文，不要解释，不要添加任何前后缀。',
      text, 1024);
  }

  async summarize(transcript: string, glossaryContext: string): Promise<string> {
    return this.chat(
      '你是会议纪要助手。根据转写文本输出结构化纪要，包含：会议要点、关键结论、待办事项（含负责人若可推断）。' +
        '忠实于原文，不编造。转写可能含语音识别错误，请结合术语表理解。\n' + glossaryContext,
      transcript, 2048);
  }

  async correct(transcript: string, glossaryContext: string): Promise<string> {
    return this.chat(
      '你是语音转写校对助手，精通计算机专业术语。请修正转写文本中的同音错字与术语错误，' +
        '保持原意与说话人标记不变，输出修正后的完整文本。不要总结、不要解释。\n' + glossaryContext,
      transcript, 4096);
  }

  async ping(): Promise<string> {
    return this.chat('你是连接测试助手。', '请只回复：OK', 8);
  }
}
