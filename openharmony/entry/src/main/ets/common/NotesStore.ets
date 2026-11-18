import { fileIo } from '@kit.CoreFileKit';

export interface Utterance {
  id: number;
  speaker: string;
  text: string;
  ts: number;
  translation: string;
}

export interface Attachment {
  id: number;
  name: string;
  kind: string;
  text: string;
}

export interface Session {
  id: number;
  title: string;
  seriesId: number;
  createdAt: number;
  utterances: Utterance[];
  attachments: Attachment[];
  summary: string;
  corrected: string;
}

export interface Series {
  id: number;
  name: string;
}

export interface SpeakerInfo {
  id: string;
  name: string;
}

export interface Db {
  nextId: number;
  series: Series[];
  sessions: Session[];
  speakers: SpeakerInfo[];
}

function pad(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

export function fmtTime(ts: number, full: boolean): string {
  const d = new Date(ts);
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return full ? `${date} ${time}` : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}`;
}

/**
 * 纪要库：JSON 文件持久化，原子写入。备份导出/导入为 JSON 文件。
 */
export class NotesStore {
  private path: string;
  db: Db;

  constructor(context: Context) {
    const dir = context.filesDir + '/notes';
    if (!fileIo.accessSync(dir)) {
      fileIo.mkdirSync(dir);
    }
    this.path = dir + '/db.json';
    this.db = { nextId: 1, series: [], sessions: [], speakers: [] };
    if (fileIo.accessSync(this.path)) {
      try {
        const text = fileIo.readTextSync(this.path);
        this.db = JSON.parse(text) as Db;
      } catch (e) {
        console.error(`db parse failed: ${e}`);
      }
    }
    if (this.db.series.length === 0) {
      this.db.series.push({ id: this.newId(), name: '默认系列' });
      this.save();
    }
  }

  private newId(): number {
    const id = this.db.nextId;
    this.db.nextId += 1;
    return id;
  }

  save(): void {
    const tmp = this.path + '.tmp';
    const fp = fileIo.openSync(tmp, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    fileIo.writeSync(fp.fd, JSON.stringify(this.db));
    fileIo.closeSync(fp.fd);
    fileIo.moveFileSync(tmp, this.path);
  }

  createSession(seriesId: number): Session {
    const s: Session = {
      id: this.newId(),
      title: `纪要 ${fmtTime(Date.now(), true)}`,
      seriesId: seriesId,
      createdAt: Date.now(),
      utterances: [],
      attachments: [],
      summary: '',
      corrected: ''
    };
    this.db.sessions.push(s);
    this.save();
    return s;
  }

  session(id: number): Session | undefined {
    return this.db.sessions.find((s: Session) => s.id === id);
  }

  deleteSession(id: number): void {
    this.db.sessions = this.db.sessions.filter((s: Session) => s.id !== id);
    this.save();
  }

  addSeries(name: string): Series {
    const s: Series = { id: this.newId(), name: name };
    this.db.series.push(s);
    this.save();
    return s;
  }

  seriesName(id: number): string {
    const s = this.db.series.find((x: Series) => x.id === id);
    return s ? s.name : '未分组';
  }

  addUtterance(sessionId: number, speaker: string, text: string): Utterance | undefined {
    const s = this.session(sessionId);
    if (!s) {
      return undefined;
    }
    const u: Utterance = { id: this.newId(), speaker: speaker, text: text, ts: Date.now(), translation: '' };
    s.utterances.push(u);
    this.save();
    return u;
  }

  addAttachment(sessionId: number, name: string, kind: string, text: string): void {
    const s = this.session(sessionId);
    if (!s) {
      return;
    }
    s.attachments.push({ id: this.newId(), name: name, kind: kind, text: text });
    this.save();
  }

  speakerDisplayName(id: string): string {
    if (id === '') {
      return '';
    }
    const sp = this.db.speakers.find((x: SpeakerInfo) => x.id === id);
    return sp ? sp.name : id;
  }

  setSpeakerName(id: string, name: string): void {
    const sp = this.db.speakers.find((x: SpeakerInfo) => x.id === id);
    if (sp) {
      sp.name = name;
    } else {
      this.db.speakers.push({ id: id, name: name });
    }
    this.save();
  }

  exportJson(): string {
    return JSON.stringify(this.db);
  }

  /** 合并导入，返回导入会话数 */
  importJson(text: string): number {
    const imported = JSON.parse(text) as Db;
    const seriesIdMap = new Map<number, number>();
    for (const old of imported.series) {
      const existing = this.db.series.find((x: Series) => x.name === old.name);
      if (existing) {
        seriesIdMap.set(old.id, existing.id);
      } else {
        const ns = this.addSeries(old.name);
        seriesIdMap.set(old.id, ns.id);
      }
    }
    for (const sp of imported.speakers) {
      if (!this.db.speakers.find((x: SpeakerInfo) => x.id === sp.id)) {
        this.db.speakers.push(sp);
      }
    }
    let count = 0;
    for (const old of imported.sessions) {
      const mapped = seriesIdMap.get(old.seriesId);
      const s: Session = {
        id: this.newId(),
        title: old.title,
        seriesId: mapped !== undefined ? mapped : this.db.series[0].id,
        createdAt: old.createdAt,
        utterances: [],
        attachments: [],
        summary: old.summary,
        corrected: old.corrected
      };
      for (const u of old.utterances) {
        s.utterances.push({
          id: this.newId(), speaker: u.speaker, text: u.text, ts: u.ts, translation: u.translation
        });
      }
      for (const a of old.attachments) {
        s.attachments.push({ id: this.newId(), name: a.name, kind: a.kind, text: a.text });
      }
      this.db.sessions.push(s);
      count += 1;
    }
    this.save();
    return count;
  }

  sessionMarkdown(id: number): string {
    const s = this.session(id);
    if (!s) {
      return '';
    }
    let md = `# ${s.title}\n\n- 系列：${this.seriesName(s.seriesId)}\n- 时间：${fmtTime(s.createdAt, true)}\n\n`;
    if (s.summary !== '') {
      md += `## 内容总结\n\n${s.summary}\n\n`;
    }
    md += '## 转写记录\n\n';
    for (const u of s.utterances) {
      const sp = this.speakerDisplayName(u.speaker);
      md += sp !== '' ? `**${sp}**：${u.text}\n` : `${u.text}\n`;
      if (u.translation !== '') {
        md += `> ${u.translation}\n`;
      }
      md += '\n';
    }
    if (s.corrected !== '') {
      md += `## 术语纠错稿\n\n${s.corrected}\n\n`;
    }
    for (const a of s.attachments) {
      md += `## 附件：${a.name}\n\n${a.text}\n\n`;
    }
    return md;
  }
}
