# Project and framework decision

## Source

Canonical product reference: hardikk-gupta/ALTER-AI-code-base, merged main at `0bda3d8583d1f53b6f305e1eeb22628c17a08e66`, matching the 25 October handoff baseline. Inspected native boundaries, card entity/parser, UI card composition/theme/nav, river simulation/renderer, model manifest, runtime personality, voice and backup documentation. Selected source hashes and destinations are in upstream/android.lock.json. Android documentation has superseded sections: current AGENTS corrections/source take precedence over old mandatory-APK instructions.

## Feature/design/platform map

| Area | Reference | Desktop boundary |
| --- | --- | --- |
| Notes/tasks | AlterData.kt, TaskText.kt, MemoryCards.kt | Independent SQLite schema; same primitive field names and checkbox rules; rich blocks/board objects still pending |
| UI | Color.kt, Type.kt, Theme.kt, AdaptiveBottomNavigation.kt | Compose Desktop, Satoshi, original bottom destinations and envelope cards; desktop acceptance pending |
| River | ShoalSimulation.kt, KoiAsciiRenderer.kt | Pure simulation copied; Android graphics mapped to Java2D, fixed ASCII sampling retained |
| Agent | ALTER_INSTRUCTIONS.md, AlterAgent.kt | Exact personality resource, local conversation only; validated tools/retrieval not yet ported |
| Models | ModelCatalog.kt, local_models.json | Windows llama.cpp loopback provider; manual Windows GGUF installation instead of Android LiteRT binaries |
| Voice/files | VoicePack.kt, whisper/Moonshine integrations | Pending Windows microphone, ASR, file picker/extraction, TTS and cancellation acceptance |
| Backup/account | AlterBackup.kt, DriveBackup.kt | Pending logical ZIP v2 compatibility fixtures and desktop OAuth; no Android database copying |
| OS integration | Android services/receivers | Pending Windows notifications, focus-safe insertion, explicit hotkeys/tray permissions |

## Framework

Evaluated Compose Multiplatform Desktop/JVM first as requested. Chosen for the first increment: Kotlin/JVM + Compose Desktop, JDK 21, Gradle 8.14.3, Kotlin 2.2.20, Compose 1.9.1. Desktop supports Windows x64 JVM/native distribution tasks and reuses pure Kotlin behavior and Compose vector assets. Android AGP/LiteRT dependencies are excluded. A JRE is insufficient for compiling and native packaging.

This is a desktop JVM target rather than a speculative shared-core rewrite. Java2D replaces Android Canvas only at the river drawing boundary. Compose/Skiko owns desktop UI. SQLite JDBC supplies Windows/Linux native drivers; JSON is used only for the local model protocol. Windows native application images must be built/tested on Windows; Linux success does not establish Windows packaging/runtime compatibility.

## Persistence and generation

Schema version 1 stores local text/note/upcoming cards, soft deletion, theme/server-port preferences and one active persistent conversation. IDs are numeric, timestamps are epoch milliseconds, display uses the OS zone and Unicode remains intact. Unsupported database versions fail before schema writes. SQLite WAL/busy timeout are enabled. This is not an Android Room file or full archive implementation. Existing task parsing/completion is copied with provenance.

Local chat sends only the last 20 conversation messages and the runtime instructions to `127.0.0.1`, with a 512-token output cap. Curated model files are fetched from immutable official revisions and verified by exact size and SHA-256 before a bundled CPU llama.cpp server starts. Voice uses explicit Java Sound recording and a bundled whisper.cpp CLI with a separately selected verified multilingual model. Transcription fills a prompt for review; it does not send automatically. Stop cancels the HTTP future; a failed/cancelled reply is not saved. There is no cloud fallback, semantic retrieval or multi-session picker yet. Windows CI builds the native runtimes from pinned upstream commits. No model weights are bundled.

Data at rest uses the user's OS account storage, without application encryption. Backups, synchronization, cloud credentials and installer signing are separate increments. Do not expose the local inference server to the LAN.
