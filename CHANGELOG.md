# Changelog

## 1.0.0 — Monochrome AI Studio Upgrade
### UI & Studio Timeline
- Complete **strictly monochrome** CapCut-style mobile-first UI (`Dark (Studio)`, `Light`, and `System` themes) powered by `Inter` and `JetBrains Mono`.
- Interactive **Preview Stage** with aspect frame guides, live synchronized caption overlay, and ExoPlayer playback controls.
- Zoomable **Multi-Track Timeline** (`V1` video strips, `A1` audio waveform track, `T1` caption track), precision time ruler, playhead scrubbing, split, trim, duplicate, delete, reorder, keyframe editing, and **60-step Undo/Redo**.
- Expandable dock panels for **AI Agent**, **Style**, **Edit / Keyframes**, **Captions**, **Audio / Music**, **Effects**, and **Export / QA**.

### Security & Privacy
- Replaced legacy credential handling with **`KeyVault`** using Android Keystore **AES-256-GCM** (`enc2:` format), safe legacy credential migration, key masking/redaction, and **zero plaintext fallback**.
- Added **`PrivacyManager`** with explicit disclosures, frame-sampling toggles, Groq STT toggles, and a hard block against uploading full videos without explicit approval.

### Three AI Providers & Agentic Editing
- Added unified **`AiProvider`** architecture with adapters for **Google Gemini**, **Groq**, and **Anthropic Claude**, including streaming, tool calling, capability detection, connection tests, usage tracking, and **`ProviderRouter`**.
- **Free-first routing**: Gemini for vision, Groq for fast planning/STT, and **strict manual + explicit paid-consent gating for Claude** (never silently routed).
- Added **`ConversationManager`** and **`TokenBudgetManager`** for persistent project memory, selection tracking, editing decisions, and rolling context compaction.
- Added autonomous **`EditingAgent`** (`AgentPlanner`, `AgentExecutor`, `AgentValidator`, `AgentLoop`, `AgentTools`) with strict schema validation, manual-edit lock protection, cancellation/timeouts, and atomic undoable batch operations.

### Video Intelligence, Export, QA & Persistence
- Added on-device **ML Kit OCR** and **`GamingSignals`** (kill feeds, headshots, victory/defeat screens, score deltas) plus **`StoryAnalyzer`** (5-act structure, silence detection, synchronized timeline caption generation).
- Added **720p / 1080p**, **24–60 fps**, **4–24 Mbps** export settings, **Safe Render** checkpoint persistence, and extended **`QualityCheck`** (`ABRUPT_ENDING`, `CAPTION_OVERLAP`, `REPEATED_SCENES`, `AUDIO_CLIPPING`, `AUDIO_SYNC`, and `SuggestedRepair` items).
- Added **Room SQLite persistence** (`AppDatabase`, `ProjectRepository`) with autosave, project manager (create, rename, duplicate, soft delete, restore, purge), v2 JSON import/export, non-destructive legacy `EditPlan` v1 migration, and interrupted session/render recovery.

## 0.8.4 and earlier
- Initial PromptCut release with Gemini/Groq planning, Media3 1.11 export, on-device ML Kit understanding, Openverse & synthesized mood music, FilmRules, and self-checking QA.
