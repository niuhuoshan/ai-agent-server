import * as CryptoJSModule from 'crypto-js';
import { JSEncrypt } from 'jsencrypt';

const CryptoJS = ('default' in CryptoJSModule ? CryptoJSModule.default : CryptoJSModule) as typeof CryptoJSModule;

declare global {
  interface Window {
    __NHS_SERVER_CONFIG__?: {
      rsaPublicKey?: string;
    };
  }
}

function resolveRsaPublicKey() {
  const runtimeKey = window.__NHS_SERVER_CONFIG__?.rsaPublicKey?.trim();

  return runtimeKey || import.meta.env.VITE_APP_RSA_PUBLIC_KEY;
}

function generateRandomString() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);

  return Array.from(bytes, value => value.toString(16).padStart(2, '0'))
    .join('')
    .slice(0, 32);
}

export function encryptRequestBody(payload: unknown) {
  const aesKey = CryptoJS.enc.Utf8.parse(generateRandomString());
  const encryptedBody = CryptoJS.AES.encrypt(JSON.stringify(payload), aesKey, {
    mode: CryptoJS.mode.ECB,
    padding: CryptoJS.pad.Pkcs7
  }).toString();

  const encryptor = new JSEncrypt();
  encryptor.setPublicKey(resolveRsaPublicKey());
  const encryptedKey = encryptor.encrypt(CryptoJS.enc.Base64.stringify(aesKey));
  if (!encryptedKey) {
    throw new Error('Unable to encrypt the login request key');
  }

  return {
    encryptedBody,
    encryptedKey
  };
}
