import { util } from '@kit.ArkTS';

/**
 * 计算机专业知识库：rawfile/cs_glossary.json。
 * 总结/纠错时注入相关术语释义；与识别热词表构成双层术语保障。
 */
export class KnowledgeBase {
  private glossary: Record<string, string> = {};
  private loaded: boolean = false;

  init(context: Context): void {
    if (this.loaded) {
      return;
    }
    try {
      const bytes = context.resourceManager.getRawFileContentSync('cs_glossary.json');
      const decoder = util.TextDecoder.create('utf-8');
      const text = decoder.decodeWithStream(bytes);
      this.glossary = JSON.parse(text) as Record<string, string>;
      this.loaded = true;
    } catch (e) {
      console.error(`glossary load failed: ${e}`);
    }
  }

  size(): number {
    return Object.keys(this.glossary).length;
  }

  contextFor(text: string): string {
    const lower = text.toLowerCase();
    const lines: string[] = [];
    for (const term of Object.keys(this.glossary)) {
      const hit = this.isAscii(term) ? lower.includes(term.toLowerCase()) : text.includes(term);
      if (hit) {
        lines.push(`- ${term}：${this.glossary[term]}`);
        if (lines.length >= 25) {
          break;
        }
      }
    }
    if (lines.length === 0) {
      return '';
    }
    return '相关计算机术语表：\n' + lines.join('\n');
  }

  private isAscii(s: string): boolean {
    for (let i = 0; i < s.length; i++) {
      if (s.charCodeAt(i) > 127) {
        return false;
      }
    }
    return true;
  }
}
