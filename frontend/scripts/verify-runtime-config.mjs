import { createPublicKey } from 'node:crypto';
import { readFileSync } from 'node:fs';

const configPath = process.argv[2] ?? new URL('../public/runtime-config.js', import.meta.url);
const runtimeConfig = readFileSync(configPath, 'utf8');
const match = runtimeConfig.match(/rsaPublicKey\s*:\s*['"]([^'"]+)['"]/);

if (!match) {
  throw new Error('public/runtime-config.js must define rsaPublicKey');
}

const encodedKey = match[1];
const decodedKey = Buffer.from(encodedKey, 'base64');

if (decodedKey.toString('base64') !== encodedKey) {
  throw new Error('runtime rsaPublicKey must use canonical Base64 encoding');
}

const publicKey = createPublicKey({ key: decodedKey, format: 'der', type: 'spki' });
const modulusLength = publicKey.asymmetricKeyDetails?.modulusLength;

if (publicKey.asymmetricKeyType !== 'rsa' || typeof modulusLength !== 'number' || modulusLength < 2048) {
  throw new Error('runtime rsaPublicKey must be an RSA key with at least 2048 bits');
}
