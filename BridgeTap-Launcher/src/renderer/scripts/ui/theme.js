/**
 * Theme: auto / light / dark.
 *
 * Three layers of state are tracked separately:
 *   <html data-mode="auto|light|dark">  — what the user picked (drives the toggle icon highlight)
 *   <html data-theme="light|dark">      — the palette actually in effect (CSS reads only this)
 *   auto = follow the system prefers-color-scheme (Electron's nativeTheme mirrors this media query)
 *
 * Why the main process also needs to know: it must set window backgroundColor and nativeTheme.themeSource
 * from the same choice, otherwise a white frame flashes on every launch in dark mode.
 *
 * 主题：自动 / 浅色 / 暗色。
 *
 * 三层状态分开记：
 *   <html data-mode="auto|light|dark">  —— 用户选的是什么（切换按钮的图标高亮用）
 *   <html data-theme="light|dark">      —— 实际生效的是哪套配色（CSS 只认这个）
 *   自动 = 跟随系统 prefers-color-scheme（Electron 的 nativeTheme 会同步这个媒体查询）
 *
 * 为什么还要让主进程知道：主进程要按同一份选择设置窗口 backgroundColor 和 nativeTheme.themeSource，
 * 否则暗色下每次启动都会先闪一帧白底。
 */

export const MODES = ['auto', 'light', 'dark'];

const LABEL = {
  auto: '自动（跟随系统）',
  light: '浅色',
  dark: '暗色'
};

/**
 * Next mode in the cycle (auto → light → dark → auto).
 *
 * 当前模式对应的下一个模式（循环 auto → light → dark → auto）。
 */
export function nextMode(mode) {
  const index = MODES.indexOf(mode);
  return MODES[(index + 1) % MODES.length];
}

export function modeLabel(mode) {
  return LABEL[mode] || LABEL.auto;
}

/**
 * @param {{initial?:'auto'|'light'|'dark', onChange?:(mode:string, resolved:string)=>void}} [options]
 */
export function createTheme(options = {}) {
  const root = document.documentElement;
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  let mode = MODES.includes(options.initial) ? options.initial : 'auto';

  const resolved = () => (mode === 'auto' ? (media.matches ? 'dark' : 'light') : mode);

  function paint() {
    root.dataset.mode = mode;
    root.dataset.theme = resolved();
    if (options.onChange) {
      options.onChange(mode, root.dataset.theme);
    }
  }

  // Auto mode follows the system: repaint immediately when the system flips light/dark
  // 自动模式跟着系统走：系统切换深浅色时立刻重绘
  const onSystemChange = () => {
    if (mode === 'auto') {
      paint();
    }
  };
  if (media.addEventListener) {
    media.addEventListener('change', onSystemChange);
  }

  return {
    get mode() {
      return mode;
    },
    get resolved() {
      return resolved();
    },
    /**
     * Set the mode and apply it immediately.
     *
     * 设置模式并立即生效。
     */
    set(next) {
      if (!MODES.includes(next)) {
        return mode;
      }
      mode = next;
      paint();
      return mode;
    },
    /**
     * Resolved result reported back by the main process (nativeTheme already switched per the choice).
     *
     * 主进程回报的落地结果（nativeTheme 已按选择切换）。
     */
    sync(resolvedTheme) {
      if (resolvedTheme === 'light' || resolvedTheme === 'dark') {
        root.dataset.theme = resolvedTheme;
      }
    },
    /**
     * Align the first frame: write the resolved theme into the DOM (without firing onChange).
     *
     * 首帧对齐：把已解析的主题写进 DOM（不触发 onChange）。
     */
    apply() {
      root.dataset.mode = mode;
      root.dataset.theme = resolved();
    },
    dispose() {
      if (media.removeEventListener) {
        media.removeEventListener('change', onSystemChange);
      }
    }
  };
}
