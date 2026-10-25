# Changes

## 25 October 2026 — first desktop increment

- Recorded Android merged-main reference `0bda3d8583d1f53b6f305e1eeb22628c17a08e66` and source hashes. Brought over pure task parsing, shoal simulation, icon vectors, theme colors and exact runtime personality; added a Java2D renderer adapter.
- Added Compose Desktop shell with five destinations, local SQLite text memories/plain notes/tasks, search/edit/checkbox completion/soft delete, settings, Satoshi and root-owned river. Added manual local llama.cpp HTTP chat and Windows model guidance for low-memory PCs and Ryzen/RTX 4050 systems.
- Pinned Gradle 8.14.3 and its wrapper checksum; verified JDK 21.0.13 and official Satoshi hashes in the cloud setup. Added Linux/Windows source-check CI and Windows application-image task. No installer has been published.
- Validation in Linux cloud: `scripts/setup_cloud.sh` succeeded; `./gradlew test` passed **6 tests, 0 failures/skips**. Compose window started under an internal Xorg display and produced `build/screenshots/desktop.png`; visually checked Home, navigation and shared river. The run used Skiko's fallback rendering API because this virtual display has no GL context. Native Windows build/run, model throughput, device DPI/keyboard/mouse, microphone and installer tests are unrun.
- Android notification-only GitHub linkage and complete Android↔Windows backup remain pending. No Android source changes or personal data were added.
