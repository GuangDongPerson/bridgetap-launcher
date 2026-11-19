/**
 * Toast (light notification). Display only, no business logic.
 *
 * Toast（轻提示）。只做展示，不含业务判断。
 */

import { el } from './render.js';

const ICON = {
  info: '',
  success: '✓',
  error: '!'
};

/**
 * @param {HTMLElement} host .toasts container · .toasts 容器
 * @param {{level?:'info'|'success'|'error', text:string, ttl?:number}} options
 */
export function toast(host, options) {
  const level = options.level || 'info';
  const node = el('div', 'toast toast--' + level);

  node.append(el('span', 'toast__bar'));

  const text = el('span', 'toast__text');
  const icon = ICON[level];
  text.textContent = icon ? icon + '  ' + options.text : options.text;
  node.append(text);

  host.append(node);

  const ttl = options.ttl ?? (level === 'error' ? 7000 : 4200);
  const timer = setTimeout(() => dismiss(), ttl);

  node.addEventListener('click', () => {
    clearTimeout(timer);
    dismiss();
  });

  function dismiss() {
    if (node.classList.contains('is-leaving')) {
      return;
    }
    node.classList.add('is-leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
    setTimeout(() => node.remove(), 400);
  }
}
