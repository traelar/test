import { httpError } from './http.js';

const encoder = new TextEncoder();
export const PASSWORD_KDF_ITERATIONS = 100000;

function bytesToBase64(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return bytes;
}

function bytesToHex(bytes) {
  return Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
}

function base64Url(bytes) {
  return bytesToBase64(bytes)
    .replaceAll('+', '-')
    .replaceAll('/', '_')
    .replace(/=+$/g, '');
}

async function derivePassword(password, salt, iterations) {
  const material = await crypto.subtle.importKey(
    'raw',
    encoder.encode(String(password)),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt, iterations },
    material,
    256
  );
  return new Uint8Array(bits);
}

export async function hashPassword(password, saltBase64 = null, iterations = PASSWORD_KDF_ITERATIONS) {
  if (typeof password !== 'string' || password.length < 10 || password.length > 200) {
    throw httpError(400, 'Password must be 10 to 200 characters');
  }
  const salt = saltBase64
    ? base64ToBytes(saltBase64)
    : crypto.getRandomValues(new Uint8Array(16));
  const hash = await derivePassword(password, salt, iterations);
  return {
    salt: bytesToBase64(salt),
    hash: bytesToBase64(hash),
    iterations
  };
}

export async function verifyPassword(password, stored) {
  if (!stored?.salt || !stored?.hash || !stored?.iterations) return false;
  const expected = base64ToBytes(stored.hash);
  const actual = await derivePassword(password, base64ToBytes(stored.salt), Number(stored.iterations));
  if (actual.length !== expected.length) return false;
  let difference = 0;
  for (let index = 0; index < actual.length; index += 1) {
    difference |= actual[index] ^ expected[index];
  }
  return difference === 0;
}

export function randomToken(byteLength = 32) {
  const bytes = crypto.getRandomValues(new Uint8Array(byteLength));
  return base64Url(bytes);
}

export async function sha256Hex(value) {
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(String(value))));
  return bytesToHex(digest);
}

export { bytesToBase64, base64ToBytes };
