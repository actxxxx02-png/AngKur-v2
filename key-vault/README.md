# PromptCut API Key Vault

A small, private dashboard for storing Gemini, Groq, and Claude API keys. It is intentionally separate from the Android editing engine.

## Security model

- The dashboard is protected by a vault password stored only in the Vercel project's encrypted environment variables.
- Keys are encrypted with AES-256-GCM before they are written to a **private** Vercel Blob store.
- The encryption key and Blob token never enter the browser bundle or Git repository.
- Dashboard responses show masked keys. The authenticated sync endpoint returns raw keys only to an authorized client.
- The Android app stores the vault URL and password with its existing Android Keystore AES-GCM key vault. Sync is manual.
- This is a key-storage service, not an AI proxy. Provider API calls still happen from the Android app, so synced keys are present on that device.

## Required Vercel environment variables

Create these for Production, Preview, and Development:

- `VAULT_PASSWORD`: a long, unique password for the dashboard and app sync.
- `VAULT_ENCRYPTION_KEY`: base64 encoding of exactly 32 random bytes.
- `BLOB_READ_WRITE_TOKEN`: created automatically when the private Blob store is connected to the Vercel project.

Never put these values in GitHub, HTML, Android source code, or screenshots. Redeploy after changing environment variables.

## Deployment

Deploy this directory as the Vercel project root (`key-vault`). Attach a **private** Vercel Blob store to that project. The static dashboard is `index.html`; the authenticated serverless endpoint is `/api/keys`.

GitHub Pages can host static HTML but cannot run this private storage API. Vercel is used for the full dashboard + API, while all source code remains in GitHub.

## API

- `GET /api/keys`: authenticated masked key list.
- `GET /api/keys?sync=1`: authenticated raw keys for manual Android sync.
- `PUT /api/keys` with `{ "action":"add", "providerId":"GEMINI|GROQ|CLAUDE", "name":"...", "key":"..." }`: add a key.
- `PUT /api/keys` with `{ "action":"update", "id":"...", "name":"...", "key":"..." }`: replace a saved key and optionally rename it.
- `PUT /api/keys` with `{ "action":"toggle", "id":"...", "enabled":true }`: enable or disable a key.
- `DELETE /api/keys` with `{ "id":"..." }`: delete a key.

All requests require `Authorization: Bearer <VAULT_PASSWORD>`. Responses are marked `no-store`.
