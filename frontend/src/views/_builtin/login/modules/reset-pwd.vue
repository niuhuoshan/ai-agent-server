<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { useRouterPush } from '@/hooks/common/router';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { $t } from '@/locales';
import { fetchResetPassword, fetchSmsCode } from '@/service/api/auth';

defineOptions({
  name: 'ResetPwd'
});

const { toggleLoginModule } = useRouterPush();
const { formRef, validate } = useNaiveForm();
const sendingCode = ref(false);
const resetting = ref(false);
const countdown = ref(0);
let countdownTimer: number | null = null;

interface FormModel {
  phone: string;
  code: string;
  password: string;
  confirmPassword: string;
}

const model: FormModel = reactive({
  phone: '',
  code: '',
  password: '',
  confirmPassword: ''
});

type RuleRecord = Partial<Record<keyof FormModel, App.Global.FormRule[]>>;

const rules = computed<RuleRecord>(() => {
  const { formRules, createConfirmPwdRule } = useFormRules();

  return {
    phone: formRules.phone,
    code: [{ required: true, message: '请输入短信验证码', trigger: ['input', 'blur'] }],
    password: formRules.pwd,
    confirmPassword: createConfirmPwdRule(model.password)
  };
});

async function handleSubmit() {
  await validate();
  resetting.value = true;
  try {
    const { error } = await fetchResetPassword(model.phone.trim(), model.code.trim(), model.password);
    if (error) return;
    window.$message?.success('密码已重置，请使用新密码登录');
    toggleLoginModule('pwd-login');
  } finally {
    resetting.value = false;
  }
}

async function sendCode() {
  const phone = model.phone.trim();
  if (!/^1[3-9]\\d{9}$/.test(phone)) {
    window.$message?.warning('请输入正确的手机号');
    return;
  }
  if (countdown.value > 0 || sendingCode.value) return;
  sendingCode.value = true;
  try {
    const { error } = await fetchSmsCode(phone);
    if (error) return;
    countdown.value = 60;
    countdownTimer = window.setInterval(() => {
      countdown.value -= 1;
      if (countdown.value <= 0 && countdownTimer !== null) {
        window.clearInterval(countdownTimer);
        countdownTimer = null;
      }
    }, 1000);
    window.$message?.success('验证码已发送');
  } finally {
    sendingCode.value = false;
  }
}

onBeforeUnmount(() => {
  if (countdownTimer !== null) window.clearInterval(countdownTimer);
});
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleSubmit">
    <NFormItem path="phone">
      <NInput v-model:value="model.phone" :placeholder="$t('page.login.common.phonePlaceholder')" />
    </NFormItem>
    <NFormItem path="code">
      <NInputGroup>
        <NInput v-model:value="model.code" :placeholder="$t('page.login.common.codePlaceholder')" />
        <NButton :loading="sendingCode" :disabled="countdown > 0" @click="sendCode">
          {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
        </NButton>
      </NInputGroup>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      />
    </NFormItem>
    <NFormItem path="confirmPassword">
      <NInput
        v-model:value="model.confirmPassword"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.confirmPasswordPlaceholder')"
      />
    </NFormItem>
    <NSpace vertical :size="18" class="w-full">
      <NButton type="primary" size="large" round block :loading="resetting" @click="handleSubmit">
        {{ $t('common.confirm') }}
      </NButton>
      <NButton size="large" round block @click="toggleLoginModule('pwd-login')">
        {{ $t('page.login.common.back') }}
      </NButton>
    </NSpace>
  </NForm>
</template>

<style scoped></style>
