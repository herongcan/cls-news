<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'
import { useTheme } from '@/stores/theme'

onLaunch(() => {
  useTheme().init()
})
</script>

<style>
/* 设计令牌收在这一层：组件里只写 var(--x)，切主题不动任何一条规则。
   变量挂在 page 上而不是 :root —— 小程序没有 :root，page 是两端都认的根选择器。 */
page {
  --bg: #0a0b0d;
  --panel: #101216;
  --line: #1e2128;
  --line-2: #2a2e37;
  --text: #e7e9ee;
  --muted: #868d9b;
  --dim: #5d646f;
  --up: #e5484d;
  --down: #2fa66a;
  --amber: #d9a441;
  --amber-line: rgba(217, 164, 65, 0.45);
  --up-line: rgba(229, 72, 77, 0.45);
  --down-line: rgba(47, 166, 106, 0.45);
  --up-line-weak: rgba(229, 72, 77, 0.35);
  --down-line-weak: rgba(47, 166, 106, 0.35);
  --sec-fg: #8fb8d8;
  --sec-bg: rgba(120, 170, 220, 0.1);
  --sec-line: rgba(120, 170, 220, 0.25);
  --mark-bg: rgba(217, 164, 65, 0.22);
  --infer: #7f8790;
  --on-amber: #0a0b0d;
  --on-accent: #0a0b0d;

  background: var(--bg);
  color: var(--text);
  font-size: 14px;
  line-height: 1.65;
}

/* 浅色：微暖米白。红绿都往下压一档，否则直接放白底上会发虚 */
.theme-light {
  --bg: #faf9f6;
  --panel: #ffffff;
  --line: #e7e3da;
  --line-2: #d8d2c6;
  --text: #1b1a17;
  --muted: #5e5b54;
  --dim: #8d887e;
  --up: #c7363c;
  --down: #1a7f55;
  --amber: #9c6d18;
  --amber-line: rgba(156, 109, 24, 0.35);
  --up-line: rgba(199, 54, 60, 0.42);
  --down-line: rgba(26, 127, 85, 0.42);
  --up-line-weak: rgba(199, 54, 60, 0.32);
  --down-line-weak: rgba(26, 127, 85, 0.32);
  --sec-fg: #2f6b8f;
  --sec-bg: rgba(47, 107, 143, 0.07);
  --sec-line: rgba(47, 107, 143, 0.22);
  --mark-bg: rgba(156, 109, 24, 0.18);
  --infer: #7a7568;
  --on-amber: #ffffff;
  --on-accent: #ffffff;

  background: var(--bg);
}

/* #ifdef H5 */
body {
  background: var(--bg);
  margin: 0;
}
/* #endif */
</style>
