<p align="center">
  <img src="store/icon-512.png" width="96" alt="PromptCut icon">
</p>

<h1 align="center">PromptCut</h1>

<p align="center"><b>AI-powered mobile video editor on Android with a strictly monochrome studio interface.</b><br>
On-device analysis, multi-track timeline editing, autonomous AI editing agent, and local hardware-accelerated Media3 export.</p>

<p align="center">
  <img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-111111?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-222222?logo=kotlin&logoColor=white">
  <img alt="Media3" src="https://img.shields.io/badge/Media3-Transformer%201.11-333333">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.0-000000">
</p>

## What PromptCut v1.0 Does

PromptCut upgrades natural-language video editing into a full multi-track studio:

1. **Strictly Monochrome Studio UI (Dark & Light + System)**
   - Live **Preview Stage** with synchronized caption overlays, aspect-ratio frame guide (`9:16`, `16:9`, `1:1`), and ExoPlayer playback controls.
   - Interactive **Multi-Track Timeline** (`V1` video clip strips, `A1` audio waveform track, `T1` timed captions track) with pinch/button zoom, time ruler, playhead scrub, split, trim, duplicate, delete, reorder, and **60-step Undo/Redo**.
   - Expandable **Studio Dock** for **AI Agent**, **Style**, **Edit / Keyframes**, **Captions**, **Audio / Music**, **Effects**, and **Export / QA**.
2. **Three AI Providers & Free-First Routing**
   - **Google Gemini** (free tier, vision + sampled frames)
   - **Groq** (free tier, fast LLM + Whisper speech-to-text)
   - **Anthropic Claude** (optional paid API using your own key; requires explicit paid-API consent and manual selection — never auto-selected or silently switched to).
   - **Conversation Memory & Token Budgeting**: Remembers previous turns, project instructions, editing decisions, selected clips/segments, and recent tool operations.
3. **Autonomous AI Editing Agent**
   - Strictly typed, schema-validated editing tools (`inspect_clips`, `detect_highlights`, `trim_segment`, `split_segment`, `delete_segment`, `duplicate_segment`, `reorder_segments`, `set_crop_resize`, `generate_captions`, `edit_caption`, `set_transition`, `set_look`, `set_speed`, `configure_audio_music`, `inspect_timeline_qa`).
   - **Manual-Edit Protection**: Items modified by hand (`editedByUser`) trigger an approval prompt before the AI agent can overwrite them.
   - Every agent edit runs as a single atomic, undoable timeline transaction.
4. **On-Device Intelligence**
   - ML Kit image labeling, face/smile detection, and **Latin OCR** (`GamingSignals` detects kill feeds, headshots, victory/defeat screens, and score increases; `StoryAnalyzer` builds 5-act arcs, detects silence spans, and maps speech transcripts into synchronized timeline captions).
5. **Security & Privacy**
   - API keys are encrypted on-device with **Android Keystore AES-256-GCM** (`KeyVault`) with automatic migration of legacy entries and **zero plaintext fallback**.
   - Full source videos are **never uploaded**; only compact text descriptions, optional sampled low-res still frames, or user-approved Groq STT audio are sent.
6. **Offline-First Project Persistence & Crash Recovery**
   - Room SQLite database (`projects`, `media_assets`, `conversations`, `render_jobs`) with automatic session autosave, project create/rename/duplicate/soft-delete/restore, JSON v2 import/export, non-destructive migration of legacy `EditPlan` v1 files, and interrupted render/session recovery.

## Quick Build & Run

See [`BUILD.md`](BUILD.md) for SDK, Gradle, test, and signed release build commands, and [`CHANGELOG.md`](CHANGELOG.md) for full release notes.
