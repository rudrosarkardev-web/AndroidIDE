# Codex for Android

A standalone 64-bit Android coding-agent workspace built inside the AndroidIDE repository. It has a dark Codex-inspired UI, an embedded PTY terminal, workspace tools, local-model support, and a provider router that can hold and rotate 100+ API routes.

## What is included

- **64-bit APKs only:** `arm64-v8a` and `x86_64`; minimum Android 8.0 (API 26).
- **Agent loop:** inspect files, search, edit, create directories, and run bounded shell commands. Tool results are returned to the model so it can continue and verify work.
- **Provider adapters:** OpenAI, Anthropic, Gemini, OpenAI-compatible endpoints, Ollama, LM Studio, and llama.cpp.
- **Encrypted route vault:** API routes are stored as AES-GCM data encrypted with Android Keystore. Keys are masked in the UI and never included in exported redacted route data.
- **Automatic rotation:** routes are selected least-recently-used; failed or rate-limited routes enter exponential cooldown and the next healthy route is attempted.
- **Bulk import:** add one route in the UI or import 100+ routes as newline records:

  ```text
  OPENAI|primary|sk-...|https://api.openai.com/v1|gpt-4o-mini
  ANTHROPIC|backup|sk-ant-...|https://api.anthropic.com|claude-3-5-haiku-latest
  OLLAMA|phone-local||http://127.0.0.1:11434|llama3.2
  OPENAI_COMPATIBLE|lan-model||http://192.168.1.20:1234/v1|local-model
  ```

  JSON arrays and `{ "keys": [...] }` objects are accepted too.
- **Terminal:** a native PTY shell is vendored from the AndroidIDE/Termux terminal implementation. If Termux is installed, the Terminal tab can ask Termux to install `git`, Python, Node.js, ripgrep, clang, and make using its `RUN_COMMAND` service.
- **Workspace:** the agent is sandboxed to the app workspace at `files/workspace`. The terminal starts in the same directory.

## Build

The root repository's regular AndroidIDE build is unchanged. Build this app separately from `codex-android`:

```bash
cd codex-android
./gradlew assembleDebug
# or
./gradlew assembleRelease
```

The release variant is debug-signed by default so it is installable without a private signing key. Configure a real release signing config before publishing outside development.

The build needs Android SDK platform 34, build-tools 34.0.0, and NDK 26.1.10909125. The native module is only the PTY implementation; the app does not bundle a Linux distribution or a model. Local model providers run on the same device when available or on a reachable LAN/localhost endpoint.

## Security notes

- API keys are encrypted at rest, but a rooted device or a debugging process can still inspect an unlocked app.
- Shell and file tools are constrained to the app workspace. Review commands and edits before using the result in production.
- Provider prompts and file contents leave the device only when sent to the route selected by the user.
- No telemetry is included.

## Licensing

The app-specific code is GPL-3.0-or-later. The embedded terminal emulator and PTY source are vendored from AndroidIDE/Termux and retain their original GPL-3.0 notices; see `NOTICE.md`.
