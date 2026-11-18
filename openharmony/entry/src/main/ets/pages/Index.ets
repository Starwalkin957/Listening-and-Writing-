import worker, { MessageEvents } from '@ohos.worker';
import { Permissions } from '@kit.AbilityKit';
import { audio } from '@kit.AudioKit';
import { picker } from '@kit.CoreFileKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { allAllowed, requestPermissions } from '../common/Permission';
import { NotesStore, Session, Series, Utterance, Attachment, fmtTime } from '../common/NotesStore';
import { LlmClient, LlmPrefs, LlmSettings, llmConfigured } from '../common/LlmClient';
import { KnowledgeBase } from '../common/KnowledgeBase';

function readUriText(uri: string): string {
  const fp = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  const size = fileIo.statSync(fp.fd).size;
  const buf = new ArrayBuffer(size);
  fileIo.readSync(fp.fd, buf);
  fileIo.closeSync(fp.fd);
  const decoder = util.TextDecoder.create('utf-8');
  return decoder.decodeWithStream(new Uint8Array(buf));
}

function writeUriText(uri: string, text: string): void {
  const fp = fileIo.openSync(uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
  fileIo.writeSync(fp.fd, text);
  fileIo.closeSync(fp.fd);
}

@Entry
@Component
struct Index {
  // ---- 全局 ----
  @State tabIndex: number = 0;
  @State message: string = '';
  @State busyText: string = '';

  // ---- 记录页 ----
  @State statusText: string = '正在初始化识别引擎…';
  @State ready: boolean = false;
  @State recording: boolean = false;
  @State speakerEnabled: boolean = false;
  @State recUtterances: Utterance[] = [];
  @State partial: string = '';
  @State currentSeriesId: number = 0;
  @State autoTranslate: boolean = false;
  @State llmReady: boolean = false;
  @State renameSpeakerId: string = '';
  @State renameSpeakerText: string = '';

  // ---- 纪要库 ----
  @State libSessions: Session[] = [];
  @State filterSeriesId: number = 0;
  @State selected: Session | undefined = undefined;
  @State showNewSeries: boolean = false;
  @State newSeriesName: string = '';
  @State showAssign: boolean = false;
  @State renameSessionId: number = 0;
  @State renameSessionText: string = '';

  // ---- 设置 ----
  @State baseUrl: string = '';
  @State apiKey: string = '';
  @State modelName: string = 'qwen-plus';
  @State testing: boolean = false;
  @State glossarySize: number = 0;

  private store?: NotesStore;
  private kb: KnowledgeBase = new KnowledgeBase();
  private settings: LlmSettings = { baseUrl: '', apiKey: '', model: 'qwen-plus', autoTranslate: false };
  private currentSessionId: number = 0;
  private workerInstance?: worker.ThreadWorker;
  private readonly scriptURL: string = 'entry/ets/workers/AsrWorker.ets';
  private mic?: audio.AudioCapturer;
  private readonly sampleRate: number = 48000;
  private controller: TabsController = new TabsController();
  private readonly emptySession: Session = {
    id: 0, title: '', seriesId: 0, createdAt: 0,
    utterances: [], attachments: [], summary: '', corrected: ''
  };

  get sel(): Session {
    return this.selected ? this.selected : this.emptySession;
  }

  aboutToAppear(): void {
    const context = getContext();
    this.store = new NotesStore(context);
    this.kb.init(context);
    this.glossarySize = this.kb.size();
    this.settings = LlmPrefs.load(context);
    this.baseUrl = this.settings.baseUrl;
    this.apiKey = this.settings.apiKey;
    this.modelName = this.settings.model;
    this.autoTranslate = this.settings.autoTranslate;
    this.llmReady = llmConfigured(this.settings);
    if (this.store.db.series.length > 0) {
      this.currentSeriesId = this.store.db.series[0].id;
    }

    this.workerInstance = new worker.ThreadWorker(this.scriptURL, { name: 'AsrWorker' });
    this.workerInstance.onmessage = (e: MessageEvents) => {
      this.onWorkerMessage(e);
    };
    this.workerInstance.postMessage({ 'msgType': 'init-asr', 'context': context });
    this.refreshLibrary();
  }

  aboutToDisappear(): void {
    this.stopMic();
    this.workerInstance?.terminate();
  }

  // ================= 记录 =================

  onWorkerMessage(e: MessageEvents): void {
    const msgType = e.data['msgType'] as string;

    if (msgType === 'init-asr-done') {
      this.ready = true;
      this.speakerEnabled = e.data['speakerEnabled'] as boolean;
      this.statusText = '就绪。点击「开始记录」，支持中英混说，识别完全离线。';
    }

    if (msgType === 'init-asr-failed') {
      this.statusText = '初始化失败';
      this.message = e.data['error'] as string;
    }

    if (msgType === 'mic-result') {
      const text = (e.data['text'] as string).trim();
      const isEndpoint = e.data['isEndpoint'] as boolean;
      const speaker = e.data['speaker'] as string;
      if (text === '') {
        return;
      }
      if (isEndpoint) {
        this.partial = '';
        const u = this.store?.addUtterance(this.currentSessionId, speaker, text);
        const s = this.store?.session(this.currentSessionId);
        if (s) {
          this.recUtterances = s.utterances.slice();
        }
        if (u && this.autoTranslate && llmConfigured(this.settings)) {
          this.translateUtterance(u.id, text);
        }
      } else {
        this.partial = text;
      }
    }
  }

  async translateUtterance(uid: number, text: string): Promise<void> {
    try {
      const tr = await new LlmClient(this.settings).translate(text);
      const s = this.store?.session(this.currentSessionId);
      if (!s) {
        return;
      }
      const u = s.utterances.find((x: Utterance) => x.id === uid);
      if (u) {
        u.translation = tr;
        this.store?.save();
        this.recUtterances = s.utterances.slice();
      }
    } catch (e) {
      console.error(`translate failed: ${e}`);
    }
  }

  async ensureMicPermission(): Promise<boolean> {
    const perms: Permissions[] = ['ohos.permission.MICROPHONE'];
    if (await allAllowed(perms)) {
      return true;
    }
    return await requestPermissions(perms);
  }

  async startRecording(): Promise<void> {
    if (!await this.ensureMicPermission()) {
      this.message = '未获得麦克风权限';
      return;
    }
    const session = this.store?.createSession(this.currentSeriesId);
    if (!session) {
      return;
    }
    this.currentSessionId = session.id;
    this.recUtterances = [];
    this.partial = '';

    const options: audio.AudioCapturerOptions = {
      streamInfo: {
        samplingRate: this.sampleRate,
        channels: audio.AudioChannel.CHANNEL_1,
        sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
        encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
      },
      capturerInfo: { source: audio.SourceType.SOURCE_TYPE_MIC, capturerFlags: 0 }
    };

    try {
      this.mic = await audio.createAudioCapturer(options);
      this.mic.on('readData', (buffer: ArrayBuffer) => {
        const int16 = new Int16Array(buffer);
        const samples = new Float32Array(int16.length);
        for (let i = 0; i < int16.length; i++) {
          samples[i] = int16[i] / 32768.0;
        }
        this.workerInstance?.postMessage({
          'msgType': 'mic-samples', 'samples': samples, 'sampleRate': this.sampleRate
        });
      });
      this.workerInstance?.postMessage({ 'msgType': 'mic-start' });
      await this.mic.start();
      this.recording = true;
      this.statusText = this.speakerEnabled ? '正在记录（中英混说 · 说话人区分 · 离线）' : '正在记录（中英混说 · 离线）';
      this.message = '';
    } catch (err) {
      this.message = `麦克风启动失败：${err}`;
    }
  }

  stopMic(): void {
    if (this.mic) {
      try {
        this.mic.stop();
        this.mic.release();
      } catch (err) {
        console.error(`stop mic error: ${err}`);
      }
      this.mic = undefined;
    }
    this.workerInstance?.postMessage({ 'msgType': 'mic-stop' });
    if (this.partial.trim() !== '' && this.currentSessionId !== 0) {
      this.store?.addUtterance(this.currentSessionId, '', this.partial.trim());
      const s = this.store?.session(this.currentSessionId);
      if (s) {
        this.recUtterances = s.utterances.slice();
      }
      this.partial = '';
    }
    if (this.recording) {
      this.recording = false;
      this.statusText = '已停止，纪要已自动保存到纪要库。';
      this.refreshLibrary();
    }
  }

  // ================= 纪要库 =================

  refreshLibrary(): void {
    if (!this.store) {
      return;
    }
    const all = this.store.db.sessions.slice().sort((a: Session, b: Session) => b.createdAt - a.createdAt);
    this.libSessions = this.filterSeriesId === 0 ? all :
      all.filter((s: Session) => s.seriesId === this.filterSeriesId);
    if (this.selected) {
      const s = this.store.session(this.sel.id);
      // 深拷贝快照，确保 @State 刷新
      this.selected = s ? JSON.parse(JSON.stringify(s)) as Session : undefined;
    }
  }

  openSession(id: number): void {
    const s = this.store?.session(id);
    this.selected = s ? JSON.parse(JSON.stringify(s)) as Session : undefined;
    this.showAssign = false;
  }

  transcriptOf(s: Session): string {
    let text = '';
    for (const u of s.utterances) {
      const sp = this.store ? this.store.speakerDisplayName(u.speaker) : u.speaker;
      text += sp !== '' ? `${sp}：${u.text}\n` : `${u.text}\n`;
    }
    for (const a of s.attachments) {
      text += `【附件 ${a.name}】\n${a.text.substring(0, 8000)}\n`;
    }
    return text;
  }

  async llmAction(label: string, action: (client: LlmClient, s: Session, ctx: string) => Promise<void>): Promise<void> {
    if (!llmConfigured(this.settings)) {
      this.message = '请先在「设置」中配置 LLM 服务';
      return;
    }
    if (!this.selected || !this.store) {
      return;
    }
    const live = this.store.session(this.sel.id);
    if (!live || (live.utterances.length === 0 && live.attachments.length === 0)) {
      this.message = '该纪要还没有内容';
      return;
    }
    this.busyText = label;
    this.message = '';
    try {
      const text = this.transcriptOf(live);
      await action(new LlmClient(this.settings), live, this.kb.contextFor(text));
      this.store.save();
      this.refreshLibrary();
      this.message = `${label}完成`;
    } catch (e) {
      this.message = `${label}失败：${e}`;
    } finally {
      this.busyText = '';
    }
  }

  async importAttachment(): Promise<void> {
    if (!this.selected || !this.store) {
      return;
    }
    try {
      const opts = new picker.DocumentSelectOptions();
      opts.maxSelectNumber = 1;
      opts.fileSuffixFilters = ['.txt', '.md', '.markdown', '.csv', '.json', '.log'];
      const uris = await new picker.DocumentViewPicker().select(opts);
      if (uris.length === 0) {
        return;
      }
      const uri = uris[0];
      const text = readUriText(uri);
      const name = uri.substring(uri.lastIndexOf('/') + 1);
      const kind = name.includes('.') ? name.substring(name.lastIndexOf('.') + 1) : 'txt';
      this.store.addAttachment(this.sel.id, name, kind, text);
      this.refreshLibrary();
      this.message = `已整合附件：${name}（鸿蒙端支持纯文本类；docx/pdf 请用安卓端整合后同步备份）`;
    } catch (e) {
      this.message = `文件整合失败：${e}`;
    }
  }

  async exportMarkdown(): Promise<void> {
    if (!this.selected || !this.store) {
      return;
    }
    try {
      const opts = new picker.DocumentSaveOptions();
      opts.newFileNames = [`${this.sel.title}.md`];
      const uris = await new picker.DocumentViewPicker().save(opts);
      if (uris.length === 0) {
        return;
      }
      writeUriText(uris[0], this.store.sessionMarkdown(this.sel.id));
      this.message = '纪要已导出为 Markdown';
    } catch (e) {
      this.message = `导出失败：${e}`;
    }
  }

  async exportBackup(): Promise<void> {
    if (!this.store) {
      return;
    }
    try {
      const opts = new picker.DocumentSaveOptions();
      opts.newFileNames = ['双语语音纪要备份.json'];
      const uris = await new picker.DocumentViewPicker().save(opts);
      if (uris.length === 0) {
        return;
      }
      writeUriText(uris[0], this.store.exportJson());
      this.message = '备份已导出';
    } catch (e) {
      this.message = `导出失败：${e}`;
    }
  }

  async importBackup(): Promise<void> {
    if (!this.store) {
      return;
    }
    try {
      const opts = new picker.DocumentSelectOptions();
      opts.maxSelectNumber = 1;
      opts.fileSuffixFilters = ['.json'];
      const uris = await new picker.DocumentViewPicker().select(opts);
      if (uris.length === 0) {
        return;
      }
      const n = this.store.importJson(readUriText(uris[0]));
      this.refreshLibrary();
      this.message = `已导入 ${n} 条纪要`;
    } catch (e) {
      this.message = `导入失败：${e}`;
    }
  }

  confirmDeleteSession(id: number): void {
    AlertDialog.show({
      title: '删除纪要',
      message: '删除后不可恢复（可先导出备份）。确定删除？',
      primaryButton: {
        value: '删除',
        fontColor: '#CC3333',
        action: () => {
          this.store?.deleteSession(id);
          this.selected = undefined;
          this.refreshLibrary();
        }
      },
      secondaryButton: { value: '取消', action: () => {} }
    });
  }

  // ================= 设置 =================

  saveSettings(): void {
    this.settings = {
      baseUrl: this.baseUrl, apiKey: this.apiKey, model: this.modelName, autoTranslate: this.autoTranslate
    };
    LlmPrefs.save(getContext(), this.settings);
    this.llmReady = llmConfigured(this.settings);
    this.message = '已保存';
  }

  async testConnection(): Promise<void> {
    this.saveSettings();
    if (!llmConfigured(this.settings)) {
      this.message = '请先填写服务地址与模型名';
      return;
    }
    this.testing = true;
    try {
      const reply = await new LlmClient(this.settings).ping();
      this.message = `连接成功：${reply}`;
    } catch (e) {
      this.message = `连接失败：${e}`;
    } finally {
      this.testing = false;
    }
  }

  // ================= UI =================

  @Builder
  RecordTab() {
    Column({ space: 10 }) {
      Text(this.statusText).fontSize(14).fontColor('#555555')
      if (this.message !== '') {
        Text(this.message).fontSize(12).fontColor('#CC3333')
      }

      Row({ space: 12 }) {
        Button(this.recording ? '停止并保存' : '开始记录')
          .enabled(this.ready)
          .onClick(() => {
            if (this.recording) {
              this.stopMic();
            } else {
              this.startRecording();
            }
          })
      }

      // 系列选择
      Scroll() {
        Row({ space: 8 }) {
          ForEach(this.store ? this.store.db.series : [], (s: Series) => {
            Button(s.name)
              .fontSize(12)
              .height(32)
              .backgroundColor(this.currentSeriesId === s.id ? '#2869E8' : '#BBBBBB')
              .enabled(!this.recording)
              .onClick(() => {
                this.currentSeriesId = s.id;
              })
          }, (s: Series) => `${s.id}`)
        }
      }.scrollable(ScrollDirection.Horizontal).width('100%')

      Row() {
        Text('逐句同传（中↔英）').fontSize(14)
        Blank()
        Toggle({ type: ToggleType.Switch, isOn: this.autoTranslate })
          .enabled(this.llmReady)
          .onChange((on: boolean) => {
            this.autoTranslate = on;
            this.saveSettings();
          })
      }.width('100%')

      if (!this.llmReady) {
        Text('提示：在「设置」中配置 LLM 服务后可启用同传与总结。')
          .fontSize(12).fontColor('#999999')
      }

      // 说话人标签（点击重命名）
      if (this.speakerEnabled) {
        Scroll() {
          Row({ space: 8 }) {
            ForEach(this.speakerIds(), (sp: string) => {
              Button(this.store ? this.store.speakerDisplayName(sp) : sp)
                .fontSize(12)
                .height(32)
                .backgroundColor('#6B8FDB')
                .onClick(() => {
                  this.renameSpeakerId = sp;
                  this.renameSpeakerText = this.store ? this.store.speakerDisplayName(sp) : sp;
                })
            }, (sp: string) => sp)
          }
        }.scrollable(ScrollDirection.Horizontal).width('100%')
      }
      if (this.renameSpeakerId !== '') {
        Row({ space: 8 }) {
          TextInput({ text: this.renameSpeakerText })
            .layoutWeight(1)
            .onChange((v: string) => {
              this.renameSpeakerText = v;
            })
          Button('命名').onClick(() => {
            if (this.renameSpeakerText.trim() !== '') {
              this.store?.setSpeakerName(this.renameSpeakerId, this.renameSpeakerText.trim());
              this.recUtterances = this.recUtterances.slice();
            }
            this.renameSpeakerId = '';
          })
        }.width('100%')
      }

      List({ space: 8 }) {
        ForEach(this.recUtterances, (u: Utterance) => {
          ListItem() {
            Column({ space: 2 }) {
              if (u.speaker !== '') {
                Text(this.store ? this.store.speakerDisplayName(u.speaker) : u.speaker)
                  .fontSize(12).fontColor('#2869E8').fontWeight(FontWeight.Bold)
              }
              Text(u.text).fontSize(16)
              if (u.translation !== '') {
                Text(u.translation).fontSize(14).fontColor('#7A5FB8')
              }
            }.alignItems(HorizontalAlign.Start).width('100%')
          }
        }, (u: Utterance) => `${u.id}-${u.translation.length}`)
        if (this.partial !== '') {
          ListItem() {
            Text(`${this.partial} ▌`).fontSize(16).fontColor('#2869E8')
          }
        }
      }.width('100%').layoutWeight(1).alignListItem(ListItemAlign.Start)
    }
    .width('100%').height('100%').padding(16)
  }

  speakerIds(): string[] {
    const ids: string[] = [];
    for (const u of this.recUtterances) {
      if (u.speaker !== '' && !ids.includes(u.speaker)) {
        ids.push(u.speaker);
      }
    }
    return ids;
  }

  @Builder
  LibraryTab() {
    Column({ space: 10 }) {
      if (this.busyText !== '') {
        Row({ space: 8 }) {
          LoadingProgress().width(20).height(20)
          Text(this.busyText).fontSize(14)
        }
      }
      if (this.message !== '') {
        Text(this.message).fontSize(12).fontColor('#2869E8')
      }

      if (!this.selected) {
        // 列表
        Scroll() {
          Row({ space: 8 }) {
            Button('全部')
              .fontSize(12).height(32)
              .backgroundColor(this.filterSeriesId === 0 ? '#2869E8' : '#BBBBBB')
              .onClick(() => {
                this.filterSeriesId = 0;
                this.refreshLibrary();
              })
            ForEach(this.store ? this.store.db.series : [], (s: Series) => {
              Button(s.name)
                .fontSize(12).height(32)
                .backgroundColor(this.filterSeriesId === s.id ? '#2869E8' : '#BBBBBB')
                .onClick(() => {
                  this.filterSeriesId = s.id;
                  this.refreshLibrary();
                })
            }, (s: Series) => `${s.id}`)
            Button('+ 新系列')
              .fontSize(12).height(32).backgroundColor('#888888')
              .onClick(() => {
                this.showNewSeries = !this.showNewSeries;
              })
          }
        }.scrollable(ScrollDirection.Horizontal).width('100%')

        if (this.showNewSeries) {
          Row({ space: 8 }) {
            TextInput({ placeholder: '系列名（如「操作系统课程」）', text: this.newSeriesName })
              .layoutWeight(1)
              .onChange((v: string) => {
                this.newSeriesName = v;
              })
            Button('创建').onClick(() => {
              if (this.newSeriesName.trim() !== '') {
                this.store?.addSeries(this.newSeriesName.trim());
                this.newSeriesName = '';
                this.showNewSeries = false;
                this.refreshLibrary();
              }
            })
          }.width('100%')
        }

        if (this.libSessions.length === 0) {
          Text('还没有纪要。到「记录」页开始录音，停止后自动入库。')
            .fontSize(14).fontColor('#999999')
        }

        List({ space: 8 }) {
          ForEach(this.libSessions, (s: Session) => {
            ListItem() {
              Column({ space: 4 }) {
                Text(s.title).fontSize(16).fontWeight(FontWeight.Bold)
                Text(`${fmtTime(s.createdAt, false)} · ${this.store ? this.store.seriesName(s.seriesId) : ''} · ${s.utterances.length} 句` +
                  (s.attachments.length > 0 ? ` · ${s.attachments.length} 附件` : ''))
                  .fontSize(12).fontColor('#999999')
                if (s.summary !== '') {
                  Text(s.summary.substring(0, 80) + '…').fontSize(12).maxLines(2)
                }
              }
              .alignItems(HorizontalAlign.Start)
              .width('100%')
              .padding(12)
              .backgroundColor('#F5F6F8')
              .borderRadius(8)
              .onClick(() => {
                this.openSession(s.id);
              })
            }
          }, (s: Session) => `${s.id}-${s.utterances.length}-${s.summary.length}`)
        }.width('100%').layoutWeight(1)
      } else {
        // 详情
        Row({ space: 8 }) {
          Button('← 返回').fontSize(12).height(32).backgroundColor('#888888')
            .onClick(() => {
              this.selected = undefined;
              this.refreshLibrary();
            })
          Text(this.sel.title).fontSize(16).fontWeight(FontWeight.Bold).layoutWeight(1)
            .onClick(() => {
              this.renameSessionId = this.sel.id;
              this.renameSessionText = this.sel.title;
            })
        }.width('100%')

        if (this.renameSessionId !== 0) {
          Row({ space: 8 }) {
            TextInput({ text: this.renameSessionText }).layoutWeight(1)
              .onChange((v: string) => {
                this.renameSessionText = v;
              })
            Button('重命名').onClick(() => {
              const s = this.store?.session(this.renameSessionId);
              if (s && this.renameSessionText.trim() !== '') {
                s.title = this.renameSessionText.trim();
                this.store?.save();
              }
              this.renameSessionId = 0;
              this.refreshLibrary();
            })
          }.width('100%')
        }

        Text(`系列：${this.store ? this.store.seriesName(this.sel.seriesId) : ''}（点击更换）`)
          .fontSize(12).fontColor('#2869E8')
          .onClick(() => {
            this.showAssign = !this.showAssign;
          })
        if (this.showAssign) {
          Scroll() {
            Row({ space: 8 }) {
              ForEach(this.store ? this.store.db.series : [], (s: Series) => {
                Button(s.name).fontSize(12).height(32).backgroundColor('#6B8FDB')
                  .onClick(() => {
                    const live = this.store?.session(this.sel.id);
                    if (live) {
                      live.seriesId = s.id;
                      this.store?.save();
                    }
                    this.showAssign = false;
                    this.refreshLibrary();
                  })
              }, (s: Series) => `${s.id}`)
            }
          }.scrollable(ScrollDirection.Horizontal).width('100%')
        }

        Scroll() {
          Row({ space: 8 }) {
            Button('生成总结').fontSize(12).height(32).backgroundColor('#2869E8')
              .onClick(() => {
                this.llmAction('生成总结', async (client: LlmClient, s: Session, ctx: string) => {
                  s.summary = await client.summarize(this.transcriptOf(s), ctx);
                });
              })
            Button('术语纠错').fontSize(12).height(32).backgroundColor('#2869E8')
              .onClick(() => {
                this.llmAction('术语纠错', async (client: LlmClient, s: Session, ctx: string) => {
                  s.corrected = await client.correct(this.transcriptOf(s), ctx);
                });
              })
            Button('全文翻译').fontSize(12).height(32).backgroundColor('#2869E8')
              .onClick(() => {
                this.llmAction('全文翻译', async (client: LlmClient, s: Session, _ctx: string) => {
                  for (const u of s.utterances) {
                    if (u.translation === '') {
                      try {
                        u.translation = await client.translate(u.text);
                      } catch (e) {
                        console.error(`translate item failed: ${e}`);
                      }
                    }
                  }
                });
              })
            Button('整合文件').fontSize(12).height(32).backgroundColor('#5A9B6B')
              .onClick(() => {
                this.importAttachment();
              })
            Button('导出MD').fontSize(12).height(32).backgroundColor('#5A9B6B')
              .onClick(() => {
                this.exportMarkdown();
              })
            Button('删除').fontSize(12).height(32).backgroundColor('#CC3333')
              .onClick(() => {
                if (this.selected) {
                  this.confirmDeleteSession(this.sel.id);
                }
              })
          }
        }.scrollable(ScrollDirection.Horizontal).width('100%')

        List({ space: 8 }) {
          if (this.sel.summary !== '') {
            ListItem() {
              Column({ space: 4 }) {
                Text('内容总结').fontSize(14).fontWeight(FontWeight.Bold)
                Text(this.sel.summary).fontSize(14)
              }.alignItems(HorizontalAlign.Start).width('100%').padding(12)
              .backgroundColor('#EEF3FF').borderRadius(8)
            }
          }
          ForEach(this.sel.utterances, (u: Utterance) => {
            ListItem() {
              Column({ space: 2 }) {
                if (u.speaker !== '') {
                  Text(this.store ? this.store.speakerDisplayName(u.speaker) : u.speaker)
                    .fontSize(12).fontColor('#2869E8').fontWeight(FontWeight.Bold)
                }
                Text(u.text).fontSize(16)
                if (u.translation !== '') {
                  Text(u.translation).fontSize(14).fontColor('#7A5FB8')
                }
              }.alignItems(HorizontalAlign.Start).width('100%')
            }
          }, (u: Utterance) => `${u.id}-${u.translation.length}`)
          if (this.sel.corrected !== '') {
            ListItem() {
              Column({ space: 4 }) {
                Text('术语纠错稿').fontSize(14).fontWeight(FontWeight.Bold)
                Text(this.sel.corrected).fontSize(14)
              }.alignItems(HorizontalAlign.Start).width('100%').padding(12)
              .backgroundColor('#F0F7EE').borderRadius(8)
            }
          }
          ForEach(this.sel.attachments, (a: Attachment) => {
            ListItem() {
              Column({ space: 4 }) {
                Text(`附件：${a.name}（${a.kind}）`).fontSize(14).fontWeight(FontWeight.Bold)
                Text(a.text.substring(0, 2000) + (a.text.length > 2000 ? '…' : ''))
                  .fontSize(12)
              }.alignItems(HorizontalAlign.Start).width('100%').padding(12)
              .backgroundColor('#F5F6F8').borderRadius(8)
            }
          }, (a: Attachment) => `${a.id}`)
        }.width('100%').layoutWeight(1).alignListItem(ListItemAlign.Start)
      }
    }
    .width('100%').height('100%').padding(16)
  }

  @Builder
  SettingsTab() {
    Scroll() {
      Column({ space: 12 }) {
        Text('LLM 服务（翻译 / 总结 / 纠错）').fontSize(16).fontWeight(FontWeight.Bold)
        Text('识别与声纹始终在本机离线运行。此处 LLM 仅用于翻译、总结和术语纠错，' +
          '可填云端兼容接口（如阿里百炼 compatible-mode），也可填局域网自建服务' +
          '（ollama / llama.cpp server / vLLM）。不配置则这些功能禁用。')
          .fontSize(12).fontColor('#999999')

        TextInput({ placeholder: '服务地址（…/v1）', text: this.baseUrl })
          .onChange((v: string) => {
            this.baseUrl = v;
          })
        TextInput({ placeholder: 'API Key（局域网服务可留空）', text: this.apiKey })
          .type(InputType.Password)
          .onChange((v: string) => {
            this.apiKey = v;
          })
        TextInput({ placeholder: '模型名（qwen-plus / qwen2.5:7b …）', text: this.modelName })
          .onChange((v: string) => {
            this.modelName = v;
          })
        Row() {
          Text('记录时逐句自动同传').fontSize(14)
          Blank()
          Toggle({ type: ToggleType.Switch, isOn: this.autoTranslate })
            .onChange((on: boolean) => {
              this.autoTranslate = on;
            })
        }.width('100%')
        Row({ space: 12 }) {
          Button('保存').onClick(() => {
            this.saveSettings();
          })
          Button(this.testing ? '测试中…' : '测试连接')
            .backgroundColor('#5A9B6B')
            .enabled(!this.testing)
            .onClick(() => {
              this.testConnection();
            })
        }

        Text('历史备份').fontSize(16).fontWeight(FontWeight.Bold)
        Text('导出整个纪要库为 JSON 备份文件；换机或重装后导入恢复。与安卓端备份格式互通（安卓为 zip 包内 db.json，鸿蒙导出裸 JSON，安卓可直接导入）。')
          .fontSize(12).fontColor('#999999')
        Row({ space: 12 }) {
          Button('导出备份').backgroundColor('#5A9B6B').onClick(() => {
            this.exportBackup();
          })
          Button('导入备份').backgroundColor('#5A9B6B').onClick(() => {
            this.importBackup();
          })
        }

        Text('计算机专业知识库').fontSize(16).fontWeight(FontWeight.Bold)
        Text(`术语词条：${this.glossarySize} 条。双层术语保障：识别端热词增强 + 理解端术语知识库，总结与纠错时自动注入相关术语释义。`)
          .fontSize(12)

        Text('关于准确率').fontSize(16).fontWeight(FontWeight.Bold)
        Text('安静环境、标准口音下单人识别典型准确率约 90%～95%；中英混说、噪声、多人抢话会下降。' +
          '热词、术语纠错可进一步提升可用性。建议用自建测试集验收，不要相信任何「全场景 99%」的承诺。')
          .fontSize(12).fontColor('#999999')

        if (this.message !== '') {
          Text(this.message).fontSize(12).fontColor('#2869E8')
        }
      }
      .width('100%').padding(16).alignItems(HorizontalAlign.Start)
    }.width('100%').height('100%')
  }

  build() {
    Tabs({ barPosition: BarPosition.End, controller: this.controller, index: this.tabIndex }) {
      TabContent() {
        this.RecordTab()
      }.tabBar('记录')

      TabContent() {
        this.LibraryTab()
      }.tabBar('纪要库')

      TabContent() {
        this.SettingsTab()
      }.tabBar('设置')
    }
    .onChange((index: number) => {
      this.tabIndex = index;
      if (index === 1) {
        this.refreshLibrary();
      }
    })
    .width('100%').height('100%')
  }
}
