# Building PromptCut

## Prerequisites
- **JDK**: Java 17
- **Android SDK**: `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29` (Android 10+)
- **Gradle**: Use system `gradle` (or Android Studio Gradle sync). Do **not** hardcode API keys or credentials in Gradle or source files.

## Common Commands

```bash
# Run all unit tests and Robolectric tests (Room, KeyVault, ProviderRouter, Agent, Timeline, FilmRules, QA)
gradle :app:testDebugUnitTest

# Build debug APK (app/build/outputs/apk/debug/app-debug.apk)
gradle assembleDebug

# Run Android Lint
gradle :app:lintDebug

# Build shrunk & R8-optimized release APK (app/build/outputs/apk/release/app-release.apk)
gradle assembleRelease

# Build Play Store App Bundle (AAB)
gradle bundleRelease
```

## Release Signing
Create a git-ignored `keystore.properties` file in the repository root to sign release builds with your upload key:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

If `keystore.properties` is absent, release builds fall back to `debug.keystore` for local verification only. Never commit `keystore.properties`, `.env`, or production `.jks`/`.keystore` files.

## API Keys (Runtime Only)
PromptCut ships with **no embedded API keys**. Users paste their own keys inside the app's **Settings** screen, where `KeyVault` encrypts them on-device using Android Keystore AES-256-GCM:
- Google Gemini (Free tier): https://aistudio.google.com/apikey
- Groq (Free tier + Whisper STT): https://console.groq.com/keys
- Anthropic Claude (Paid API, explicit opt-in consent required): https://console.anthropic.com/settings/keys
