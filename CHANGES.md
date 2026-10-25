# Changes

## Windows installer

- Added an unsigned per-user MSI artifact alongside the portable app image. The installer adds Alter to the Start menu and retains a stable upgrade identity for later versioned releases.
- Set the Windows executable, installer and shortcut icon from Alter's existing Android launcher mark (`ic_launcher.webp` at the pinned reference SHA), preserving the yellow face on its dark background.
- Windows CI builds the installer after adding the pinned local text and voice engines and inspects the MSI file table for both executables. README now distinguishes installation from extracting the portable ZIP.
- Moved the native binaries into Compose's packaged app resources, which both the portable image and MSI copy. The first MSI attempt omitted them because it built a fresh app image after they had been added to the earlier portable image; CI caught that before publication.
- Version bumped to 0.1.1. Windows installation, upgrade behavior and SmartScreen presentation still need a physical Windows acceptance check.

## Desktop redesign and local models

- Moved navigation into the designer-requested left panel while retaining Alter's icons, Satoshi and palette. Fixed startup animation state by pausing only when hidden/minimized and moved river rendering off the UI thread; retained upstream fish behavior and ASCII sampling. Linux desktop screenshots at two times differ, confirming animation advances.
- Added official Qwen3 0.6B Q8_0 / 4B Q4_K_M and multilingual Whisper Tiny/Base model entries with immutable source revisions, exact sizes and SHA-256 values. In-app downloads verify before activation; Windows CI builds pinned CPU llama.cpp and whisper.cpp runtimes for the app artifact. No weights are bundled.
- Added local text model startup and explicit microphone → Whisper transcription → reviewable prompt. GPU inference, physical Windows speech quality and complete Android agent/voice parity still need acceptance.
- Added RAM/CPU-based model suggestion and river frame budget, capped river render resolution, and checked free disk space before model downloads. Existing data and model files remain outside the replaceable app folder.
- Hardware-budget regression added: 8 tests passed on Linux, 0 failures/skips. The app's About page links to newer builds; automatic installer updates are not implemented.
- Replaced the labeled Capture capsule with the designer-requested plus-only control. Its supported actions expand and return at that button's lower-corner anchor.
- Corrected the root Compose Surface to use pitch-black background explicitly; the default Material surface had made the entire desktop workspace gray (#18181B). Sidebar and card surfaces retain the gray token.
- Linux checks after the capture and color correction: 7 tests passed, 0 skipped/failed; internal UI run succeeded. Sampled screenshot pixels confirm main workspace `#000000` and sidebar `#18181B`.
- Linux checks: `./gradlew test` passed 7 tests, 0 failures/skips; model catalog and digest checks are included. Internal desktop UI run completed and two screenshots taken 3.5 seconds apart had different SHA-256 values, confirming the fish animation advances. Windows native runtimes and physical microphone remain untested here.

## Download handoff

- Windows CI now uploads its `createDistributable` app image as the `Alter-Windows` Actions artifact only after Windows tests and packaging pass. README explains extraction and launch. This is an unsigned development app image; a signed installer and Windows hardware acceptance are still outstanding.

## 25 October 2026 — first desktop increment

- Recorded Android merged-main reference `0bda3d8583d1f53b6f305e1eeb22628c17a08e66` and source hashes. Brought over pure task parsing, shoal simulation, icon vectors, theme colors and exact runtime personality; added a Java2D renderer adapter.
- Added Compose Desktop shell with five destinations, local SQLite text memories/plain notes/tasks, search/edit/checkbox completion/soft delete, settings, Satoshi and root-owned river. Added manual local llama.cpp HTTP chat and Windows model guidance for low-memory PCs and Ryzen/RTX 4050 systems.
- Pinned Gradle 8.14.3 and its wrapper checksum; verified JDK 21.0.13 and official Satoshi hashes in the cloud setup. Added Linux/Windows source-check CI and Windows application-image task. No installer has been published.
- Validation in Linux cloud: `scripts/setup_cloud.sh` succeeded; `./gradlew test` passed **6 tests, 0 failures/skips**. Compose window started under an internal Xorg display and produced `build/screenshots/desktop.png`; visually checked Home, navigation and shared river. The run used Skiko's fallback rendering API because this virtual display has no GL context. Native Windows build/run, model throughput, device DPI/keyboard/mouse, microphone and installer tests are unrun.
- Android notification-only GitHub linkage and complete Android↔Windows backup remain pending. No Android source changes or personal data were added.
