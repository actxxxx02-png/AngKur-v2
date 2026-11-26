const { get, put } = require('@vercel/blob');
const crypto = require('node:crypto');

const VAULT_PATH = 'promptcut/private-api-keys.enc';
const PROVIDERS = new Set(['GEMINI', 'GROQ', 'CLAUDE']);

function send(res, status, body) {
  res.setHeader('Cache-Control', 'no-store, private, max-age=0');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.status(status).json(body);
}

function authorized(req) {
  const expected = process.env.VAULT_PASSWORD || '';
  const supplied = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '');
  if (!expected || !supplied) return false;
  const a = Buffer.from(expected);
  const b = Buffer.from(supplied);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function encryptionKey() {
  const key = Buffer.from(process.env.VAULT_ENCRYPTION_KEY || '', 'base64');
  if (key.length !== 32) throw new Error('VAULT_ENCRYPTION_KEY must be a base64-encoded 32-byte key.');
  return key;
}

function encrypt(plainText) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', encryptionKey(), iv);
  const ciphertext = Buffer.concat([cipher.update(plainText, 'utf8'), cipher.final()]);
  return JSON.stringify({
    version: 1,
    iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
    ciphertext: ciphertext.toString('base64'),
  });
}

function decrypt(payload) {
  const data = JSON.parse(payload);
  if (data.version !== 1) throw new Error('Unsupported vault data version.');
  const decipher = crypto.createDecipheriv(
    'aes-256-gcm',
    encryptionKey(),
    Buffer.from(data.iv, 'base64'),
  );
  decipher.setAuthTag(Buffer.from(data.tag, 'base64'));
  return Buffer.concat([
    decipher.update(Buffer.from(data.ciphertext, 'base64')),
    decipher.final(),
  ]).toString('utf8');
}

async function readKeys() {
  const stored = await get(VAULT_PATH, { access: 'private', useCache: false });
  if (!stored || stored.statusCode !== 200) return [];
  const payload = await new Response(stored.stream).text();
  return JSON.parse(decrypt(payload));
}

async function writeKeys(keys) {
  await put(VAULT_PATH, encrypt(JSON.stringify(keys)), {
    access: 'private',
    addRandomSuffix: false,
    allowOverwrite: true,
    contentType: 'application/json',
  });
}

function mask(value) {
  const key = String(value || '');
  return key.length < 9 ? '••••' : key.slice(0, 4) + '••••••••' + key.slice(-4);
}

module.exports = async function handler(req, res) {
  if (req.method === 'OPTIONS') {
    res.setHeader('Allow', 'GET, PUT, DELETE, OPTIONS');
    return res.status(204).end();
  }
  if (!['GET', 'PUT', 'DELETE'].includes(req.method)) {
    res.setHeader('Allow', 'GET, PUT, DELETE, OPTIONS');
    return send(res, 405, { error: 'Method not allowed.' });
  }
  if (!authorized(req)) return send(res, 401, { error: 'Invalid vault password.' });

  try {
    const keys = await readKeys();

    if (req.method === 'GET') {
      if (req.query.sync === '1') {
        return send(res, 200, { keys });
      }
      return send(res, 200, {
        keys: keys.map(({ key, ...item }) => ({ ...item, maskedKey: mask(key) })),
      });
    }

    if (req.method === 'PUT') {
      const body = typeof req.body === 'string' ? JSON.parse(req.body) : (req.body || {});
      if (body.action === 'toggle') {
        const item = keys.find((entry) => entry.id === body.id);
        if (!item) return send(res, 404, { error: 'Key not found.' });
        item.enabled = Boolean(body.enabled);
      } else if (body.action === 'add') {
        const providerId = String(body.providerId || '').toUpperCase();
        const name = String(body.name || '').trim().slice(0, 48);
        const key = String(body.key || '').trim();
        if (!PROVIDERS.has(providerId)) return send(res, 400, { error: 'Unsupported provider.' });
        if (key.length < 8 || key.length > 4096) return send(res, 400, { error: 'API key length is invalid.' });
        if (keys.some((entry) => entry.providerId === providerId && entry.key === key)) {
          return send(res, 409, { error: 'That key is already saved for this provider.' });
        }
        keys.push({
          id: crypto.randomUUID(),
          providerId,
          name: name || providerId + ' key',
          key,
          enabled: body.enabled !== false,
          isPreferred: !keys.some((entry) => entry.providerId === providerId && entry.isPreferred),
          createdAt: new Date().toISOString(),
        });
      } else if (body.action === 'update') {
        const item = keys.find((entry) => entry.id === body.id);
        if (!item) return send(res, 404, { error: 'Key not found.' });
        const key = String(body.key || '').trim();
        if (key.length < 8 || key.length > 4096) return send(res, 400, { error: 'Replacement API key length is invalid.' });
        if (keys.some((entry) => entry.id !== item.id && entry.providerId === item.providerId && entry.key === key)) {
          return send(res, 409, { error: 'That key is already saved for this provider.' });
        }
        item.key = key;
        item.name = String(body.name || '').trim().slice(0, 48) || item.name;
        item.updatedAt = new Date().toISOString();
      } else {
        return send(res, 400, { error: 'Unknown action.' });
      }

      await writeKeys(keys);
      return send(res, 200, {
        keys: keys.map(({ key, ...item }) => ({ ...item, maskedKey: mask(key) })),
      });
    }

    const body = typeof req.body === 'string' ? JSON.parse(req.body) : (req.body || {});
    const next = keys.filter((entry) => entry.id !== body.id);
    if (next.length === keys.length) return send(res, 404, { error: 'Key not found.' });
    for (const providerId of PROVIDERS) {
      if (!next.some((entry) => entry.providerId === providerId && entry.isPreferred)) {
        const first = next.find((entry) => entry.providerId === providerId);
        if (first) first.isPreferred = true;
      }
    }
    await writeKeys(next);
    return send(res, 200, {
      keys: next.map(({ key, ...item }) => ({ ...item, maskedKey: mask(key) })),
    });
  } catch (error) {
    // Never log or return request bodies, stored values, or cryptographic material.
    console.error('Key vault operation failed:', error && error.message ? error.message : 'unknown error');
    return send(res, 500, { error: 'Vault operation failed. Check Vercel environment configuration.' });
  }
};
