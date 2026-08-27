import { request } from '../request';
import { encryptRequestBody } from '@/utils/request-crypto';

const clientId = import.meta.env.VITE_APP_CLIENT_ID;

/**
 * Login
 *
 * @param userName User name
 * @param password Password
 */
export function fetchLogin(userName: string, password: string, code?: string, uuid?: string) {
  const { encryptedBody, encryptedKey } = encryptRequestBody({
    username: userName,
    password,
    code,
    uuid,
    clientId,
    grantType: 'password'
  });

  return request<Api.Auth.LoginToken>({
    url: '/auth/login',
    method: 'post',
    data: encryptedBody,
    headers: {
      'encrypt-key': encryptedKey
    }
  });
}

/** Register a local user when the backend registration switch is enabled. */
export function fetchRegister(userName: string, password: string, userType = '00') {
  const { encryptedBody, encryptedKey } = encryptRequestBody({
    username: userName,
    password,
    userType,
    clientId,
    grantType: 'password'
  });
  return request<void>({
    url: '/auth/register',
    method: 'post',
    data: encryptedBody,
    headers: { 'encrypt-key': encryptedKey }
  });
}

/** Send a real SMS login code through the configured provider. */
export function fetchSmsCode(phoneNumber: string) {
  return request<void>({ url: '/resource/sms/code', method: 'get', params: { phoneNumber } });
}

/** Reset a local account password after a verified SMS challenge. */
export function fetchResetPassword(phoneNumber: string, smsCode: string, newPassword: string) {
  const { encryptedBody, encryptedKey } = encryptRequestBody({
    phoneNumber,
    smsCode,
    newPassword
  });
  return request<void>({
    url: '/auth/resetPassword',
    method: 'post',
    data: encryptedBody,
    headers: { 'encrypt-key': encryptedKey }
  });
}

/** Authenticate with a configured SMS login client. */
export function fetchSmsLogin(phoneNumber: string, smsCode: string) {
  const { encryptedBody, encryptedKey } = encryptRequestBody({
    phoneNumber,
    smsCode,
    clientId,
    grantType: 'sms'
  });
  return request<Api.Auth.LoginToken>({
    url: '/auth/login',
    method: 'post',
    data: encryptedBody,
    headers: { 'encrypt-key': encryptedKey }
  });
}

/** Get image captcha metadata. */
export function fetchCaptcha() {
  return request<Api.Auth.Captcha>({ url: '/auth/code' });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/auth/getUserInfo' });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/refreshToken',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/**
 * return custom backend error
 *
 * @param code error code
 * @param msg error message
 */
export function fetchCustomBackendError(code: string, msg: string) {
  return request({ url: '/auth/error', params: { code, msg } });
}
