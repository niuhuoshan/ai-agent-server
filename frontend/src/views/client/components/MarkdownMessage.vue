<script setup lang="ts">
import { computed } from 'vue';
import DOMPurify from 'dompurify';
import { marked } from 'marked';

const props = withDefaults(defineProps<{ content?: string | null }>(), {
  content: ''
});

marked.setOptions({
  gfm: true,
  breaks: true
});

const html = computed(() => {
  const source = props.content?.trim() || '';
  if (!source) return '<p class="message-placeholder">[无文本内容]</p>';

  const rendered = marked.parse(source, { async: false });
  return DOMPurify.sanitize(rendered, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel']
  });
});
</script>

<template>
  <div class="markdown-message" v-html="html" />
</template>

<style scoped lang="scss">
.markdown-message {
  color: var(--n-text-color);
  line-height: 1.72;
  overflow-wrap: anywhere;

  :deep(p) {
    margin: 0 0 10px;
  }

  :deep(p:last-child) {
    margin-bottom: 0;
  }

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 16px 0 8px;
    color: var(--n-text-color);
    font-weight: 650;
    line-height: 1.3;
  }

  :deep(h1:first-child),
  :deep(h2:first-child),
  :deep(h3:first-child) {
    margin-top: 0;
  }

  :deep(ul),
  :deep(ol) {
    margin: 8px 0;
    padding-left: 22px;
  }

  :deep(li + li) {
    margin-top: 4px;
  }

  :deep(blockquote) {
    margin: 10px 0;
    padding: 2px 0 2px 12px;
    border-left: 2px solid var(--n-primary-color);
    color: var(--n-text-color-2);
  }

  :deep(code) {
    padding: 2px 5px;
    border-radius: 4px;
    background: color-mix(in srgb, var(--n-text-color) 8%, transparent);
    font-family: var(--n-font-family-mono);
    font-size: 0.92em;
  }

  :deep(pre) {
    margin: 12px 0;
    padding: 12px 14px;
    overflow-x: auto;
    border: 1px solid var(--n-border-color);
    border-radius: 8px;
    background: color-mix(in srgb, var(--n-color) 90%, var(--n-text-color) 10%);
  }

  :deep(pre code) {
    padding: 0;
    background: transparent;
  }

  :deep(table) {
    width: 100%;
    margin: 12px 0;
    border-collapse: collapse;
    font-size: 13px;
  }

  :deep(th),
  :deep(td) {
    padding: 7px 9px;
    border-bottom: 1px solid var(--n-border-color);
    text-align: left;
  }

  :deep(a) {
    color: var(--n-primary-color);
  }

  :deep(img) {
    max-width: 100%;
    border-radius: 6px;
  }

  :deep(.message-placeholder) {
    color: var(--n-text-color-3);
  }
}
</style>
