'use strict';

/**
 * The single interface contract shared by main / preload / renderer.
 *
 * Why a separate file:
 *   the UI layer (animation, layout) and the injection layer (process scan, CreateRemoteThread,
 *   JVMTI) may talk **only** through the channels defined here. Swapping the injection
 *   implementation (say, replacing the native injector with a pure JVMTI attach) requires the new
 *   implementation to emit the same events and the UI doesn't change a single pixel; conversely,
 *   tuning UI animation never touches injection code.
 *
 *   Anyone adding a field: add it here first, then adapt both sides. Defining channel names privately
 *   inside one layer is forbidden.
 *
 * 主进程 / 预加载 / 渲染进程之间唯一的一份接口契约。
 *
 * 为什么要单独一个文件：
 *   UI 层（动效、布局）与注入层（进程扫描、CreateRemoteThread、JVMTI）之间**只能**通过这里
 *   定义的通道通信。换注入实现（比如换掉 native 注入器、改成纯 JVMTI attach）时，只要新实现
 *   仍然发同样的事件，UI 一个像素都不用动；反之调 UI 动效也不会碰到注入代码。
 *
 *   任何一端想加字段：先在这里加，再让两端各自适配。禁止在某一层里私自定义通道名。
 */

/**
 * Renderer → main process (invoke, returns a Promise).
 * 渲染进程调用主进程（invoke，返回 Promise）。
 */
const CALL = {
  /**
   * () => Target[]                         List candidate Minecraft / Java processes.
   * () => Target[]                         列出候选 Minecraft / Java 进程
   */
  LIST_TARGETS: 'inject:list-targets',
  /**
   * (pid:number) => {accepted:boolean}    Start an injection.
   * (pid:number) => {accepted:boolean}    开始注入
   */
  START_INJECT: 'inject:start',
  /**
   * () => void                            Cancel (in the stage that hasn't started running).
   * () => void                            取消（还没开始跑的那一阶段）
   */
  CANCEL_INJECT: 'inject:cancel',
  /**
   * () => LogLine[]                       Read the last few lines of the agent log.
   * () => LogLine[]                       读最近若干行 agent 日志
   */
  READ_LOG: 'log:read',
  /**
   * (dir:'bridge-tap'|'injector') => void Open the directory holding the logs / injector.
   * (dir:'bridge-tap'|'injector') => void 打开日志/注入器所在目录
   */
  OPEN_FOLDER: 'app:open-folder',
  /**
   * () => AppInfo                         Version, injector path, whether injection is available.
   * () => AppInfo                         版本、注入器路径、是否可注入
   */
  APP_INFO: 'app:info',
  /**
   * (mode:'auto'|'light'|'dark') => {mode,resolved}  Switch theme and persist it.
   * (mode:'auto'|'light'|'dark') => {mode,resolved}  切换主题并落盘
   */
  SET_THEME: 'theme:set',
  /**
   * () => SettingsSnapshot                     Read injector settings + mod settings.
   * () => SettingsSnapshot                     读注入器设置 + 模组设置
   */
  SETTINGS_READ: 'settings:read',
  /**
   * (patch:object) => {ok,values?,error?}      Change mod settings (writes the properties the agent
   *                                            is reading).
   * (patch:object) => {ok,values?,error?}      改模组设置（直接写 agent 正在读的 properties）
   */
  SETTINGS_WRITE_MOD: 'settings:write-mod',
  /**
   * (patch:object) => appSettings              Change the injector's own settings.
   * (patch:object) => appSettings              改注入器自己的设置
   */
  SETTINGS_WRITE_APP: 'settings:write-app',
  /**
   * () => {path,exists}                        Relearn the config file path from agent.log.
   * () => {path,exists}                        重新从 agent.log 学配置文件路径
   */
  SETTINGS_RELOCATE: 'settings:relocate',
  /**
   * (op:'minimize'|'maximize'|'close') => void  Frameless title-bar buttons.
   * (op:'minimize'|'maximize'|'close') => void  无边框标题栏按钮
   */
  WINDOW: 'window:control',
  /**
   * (maximized:boolean) => void            Maximised / restored, so the renderer can drop the
   *                                        rounded corner while the window fills the screen.
   * (maximized:boolean) => void            最大化/还原，渲染层据此在铺满屏幕时去掉圆角。
   */
  WINDOW_STATE: 'window:state',
  /**
   * () => {default, locales:[{code,name}]}          Available languages (from i18n/index.json).
   * () => {default, locales:[{code,name}]}          可用语言列表（来自 i18n/index.json）
   */
  I18N_INDEX: 'i18n:index',
  /**
   * (code:string) => {code, defaultLocale, locales, dict}   Fetch the whole merged dictionary.
   * (code:string) => {code, defaultLocale, locales, dict}   取合并后的整份字典
   */
  I18N_LOAD: 'i18n:load',
  /**
   * (options?:{via?:'direct'|'mirror'}) => {ok, update|null, via?, code?, error?}
   *   Check GitHub for the latest Release; code distinguishes "no response (timeout)" from other
   *   failures · 查 GitHub 最新 Release；code 用于区分"没响应(timeout)"与其它失败
   */
  UPDATE_CHECK: 'update:check',
  /**
   * (assetUrl:string) => {ok, results:[{id,name,host,ok,code,latency,speed,received}], error?}
   *   Probe latency and download speed of every mirror prefix in parallel; reads only 1MB, changes no
   *   state. Each finished route first pushes an UPDATE_EVENT.MIRROR event; the invoke result is the
   *   full set.
   * (assetUrl:string) => {ok, results:[{id,name,host,ok,code,latency,speed,received}], error?}
   *   并行测每条镜像前缀的延迟与下载速度；只读 1MB，不改任何状态。
   *   每条测完会先推一条 UPDATE_EVENT.MIRROR 事件，invoke 的返回值是全量结果。
   */
  UPDATE_PROBE_MIRRORS: 'update:probe-mirrors',
  /**
   * () => {id,name,host}[]                     Built-in mirror route list (excluding "direct").
   * () => {id,name,host}[]                     内置镜像线路清单（不含"直连"）
   */
  UPDATE_ROUTES: 'update:routes',
  /**
   * (update:object, mirrorId?:string) => {ok, file?, sha?, code?} Download + SHA256 verify.
   * (update:object, mirrorId?:string) => {ok, file?, sha?, code?} 下载 + SHA256 校验
   */
  UPDATE_DOWNLOAD: 'update:download',
  /**
   * (file:string) => {ok, error?}              Build the replace script and exit; the script
   *                                            overwrites and restarts.
   * (file:string) => {ok, error?}              生成替换脚本并退出，由脚本覆盖后重启
   */
  UPDATE_APPLY: 'update:apply',
  /**
   * (dir:string) => {ok, file?, count?, canceled?, error?}  Bundle logs into the user-chosen
   *                                                         directory.
   * (dir:string) => {ok, file?, count?, canceled?, error?}  打包日志到用户选的目录
   */
  LOG_EXPORT: 'log:export',
  /**
   * () => {ok, canceled?, error?}              Wipe all local data under the user directory, then
   *                                            exit.
   * () => {ok, canceled?, error?}              清掉用户目录下的全部本地数据后退出
   */
  APP_UNINSTALL: 'app:uninstall'
};

/**
 * Event channels related to updates (they don't join the big app:event stream, so they never mix
 * with logs).
 * 更新相关的事件通道（不进 app:event 大流，单独走，避免和日志混在一起）。
 */
const UPDATE_EVENT = {
  AVAILABLE: 'update:available',
  PROGRESS: 'update:progress',
  /**
   * One route finished: {id,name,host,ok,code,latency,speed,received}
   * 单条线路测完：{id,name,host,ok,code,latency,speed,received}
   */
  MIRROR: 'update:mirror'
};

/**
 * One-way pushes from the main process to the renderer (event stream).
 * 主进程单向推给渲染进程（事件流）。
 */
const EVENT = 'app:event';

/**
 * State machine. The UI only knows this enum, not implementation details like "currently inside
 * CreateRemoteThread".
 *   idle ──listTargets──> scanning ──> ready ──startInject──> injecting ──> attached | failed
 *     ^                                  |                        |
 *     └──────────── cancel / reset ──────┴────────────────────────┘
 *
 * 状态机。UI 只认这个枚举，不认识 "正在 CreateRemoteThread" 之类的实现细节。
 *   idle ──listTargets──> scanning ──> ready ──startInject──> injecting ──> attached | failed
 *     ^                                  |                        |
 *     └──────────── cancel / reset ──────┴────────────────────────┘
 */
const PHASE = {
  IDLE: 'idle',
  SCANNING: 'scanning',
  READY: 'ready',
  INJECTING: 'injecting',
  ATTACHED: 'attached',
  FAILED: 'failed'
};

/**
 * Event object shapes, for both sides to cross-check (not validated at runtime, keeps zero
 * dependencies).
 * 事件对象形状，供两端对照（不参与运行时校验，保持零依赖）。
 */
const EVENT_KIND = {
  /** {phase, detail?} */
  PHASE: 'phase',
  /** {targets: Target[]} */
  TARGETS: 'targets',
  /** {stream:'stdout'|'stderr'|'agent', line:string} */
  OUTPUT: 'output',
  /** {level:'info'|'success'|'error', text:string} */
  NOTICE: 'notice'
};

/**
 * @typedef {Object} Target
 * @property {number} pid
 * @property {string} title      Window title (may be empty) · 窗口标题（可能为空）
 * @property {string} client     'Lunar' | 'Badlion' | 'Minecraft' | 'Java'
 * @property {boolean} injectable Whether this client is on the verified list · 该客户端是否在已验证列表内
 * @property {string} note       One line shown to the user (e.g. "verified" / "unverified, may fail") ·
 *   给用户看的一句话（例如"已验证" / "未验证，可能失败"）
 */

module.exports = { CALL, EVENT, UPDATE_EVENT, PHASE, EVENT_KIND };
