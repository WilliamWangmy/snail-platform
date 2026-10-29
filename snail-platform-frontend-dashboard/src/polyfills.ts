/**
 * 兼容性补丁：在 Cesium 库代码执行前加载（main.ts 首行 import）。
 * CesiumJS 1.146 使用了 ES2023 的 Array.prototype.toSorted，
 * 老内核浏览器（Chromium < 110）会报 "modifiers.toSorted is not a function"
 * 导致 Viewer 构造失败、地图空白。
 */
interface ArrayToSorted {
  toSorted?: (compareFn?: (a: unknown, b: unknown) => number) => unknown[];
}

if (!(Array.prototype as unknown as ArrayToSorted).toSorted) {
  Object.defineProperty(Array.prototype, 'toSorted', {
    value: function toSorted(this: unknown[], compareFn?: (a: unknown, b: unknown) => number) {
      return Array.from(this).sort(compareFn);
    },
    writable: true,
    configurable: true,
  });
}
