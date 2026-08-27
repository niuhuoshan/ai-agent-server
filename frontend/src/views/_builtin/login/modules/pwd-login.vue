<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { fetchCaptcha } from '@/service/api';
import { useAuthStore } from '@/store/modules/auth';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
  code: string;
  uuid: string;
}

const model: FormModel = reactive({
  userName: 'admin',
  password: '',
  code: '',
  uuid: ''
});

const captchaEnabled = ref(true);
const captchaImage = ref('');

const rules = computed<Record<keyof FormModel, App.Global.FormRule[]>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    userName: formRules.userName,
    password: formRules.pwd,
    code: captchaEnabled.value
      ? [
          {
            required: true,
            message: '请输入验证码',
            trigger: ['input', 'blur']
          }
        ]
      : [],
    uuid: []
  };
});

async function handleSubmit() {
  await validate();
  await authStore.login(model.userName, model.password, model.code, model.uuid);
  if (!authStore.isLogin && captchaEnabled.value) {
    await loadCaptcha();
  }
}

async function loadCaptcha() {
  const { data, error } = await fetchCaptcha();
  if (error) return;

  captchaEnabled.value = data.captchaEnabled ?? true;
  model.code = '';
  model.uuid = data.uuid ?? '';
  captchaImage.value = data.img ? `data:image/gif;base64,${data.img}` : '';
}

onMounted(loadCaptcha);
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :placeholder="$t('page.login.common.userNamePlaceholder')" />
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      />
    </NFormItem>
    <NFormItem v-if="captchaEnabled" path="code">
      <div class="captcha-row">
        <NInput v-model:value="model.code" placeholder="请输入验证码" />
        <button class="captcha-image" type="button" title="刷新验证码" @click="loadCaptcha">
          <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
        </button>
      </div>
    </NFormItem>
    <NSpace vertical :size="24">
      <NButton type="primary" size="large" round block :loading="authStore.loginLoading" @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
    </NSpace>
  </NForm>
</template>

<style scoped>
.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 12px;
  width: 100%;
}

.captcha-image {
  height: 40px;
  overflow: hidden;
  cursor: pointer;
  background: var(--n-color);
  border: 1px solid var(--n-border-color);
  border-radius: 4px;
}

.captcha-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
</style>
