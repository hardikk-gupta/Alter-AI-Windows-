# Alter for Windows

First desktop increment of Alter, referenced to [Android main at 0bda3d8](https://github.com/hardikk-gupta/ALTER-AI-code-base/tree/0bda3d8583d1f53b6f305e1eeb22628c17a08e66).

This build provides the five product destinations, local memories, notes, checkbox tasks, search, editing/deletion, themes and a shared river. Local chat connects to a user-started llama.cpp server. See [parity and limits](docs/ANDROID_PARITY.md); this is not full Android parity.

## Run on Windows

Install a **JDK 21** (not just a JRE) and Python 3. From PowerShell in this checkout:

```powershell
python scripts/setup_fonts.py
.\gradlew.bat test
.\gradlew.bat run
```

The wrapper pins Gradle and verifies its distribution SHA-256. Font setup fetches official Satoshi assets and checks every pinned hash; retain [the licence](docs/SATOSHI_LICENSE.txt). Font binaries and model weights are not committed.

Data is stored under `%LOCALAPPDATA%\Alter\alter-desktop.db`. It is private local data, without backup/sync yet. Do not uninstall/delete this directory to update the application. Start a second copy only after closing the first; concurrent editors are not supported yet. Linux development uses `~/.local/share/Alter`; `-Dalter.dataDir=...` overrides the data directory for disposable tests.

Use Capture to save a Memory, Note or Task. Tasks accept one explicit commitment per line; no reminder time is invented. Ctrl+N opens capture, Ctrl+Enter sends chat, and the standard Tab/Space/Enter keys operate controls. Completed checkboxes stay saved. Deletion is confirmed and soft-deletes records; there is no Trash restoration UI yet.

For local AI, follow [Windows model setup](docs/WINDOWS_MODELS.md). Model binaries, microphone/GPU performance, Windows DPI/input and OS integrations need Windows acceptance. No credentials are required for the shell or local chat.

## Cloud development

```bash
bash scripts/setup_cloud.sh
source /workspace/setup/activate.sh
./gradlew test
./gradlew run  # requires a display; see saved environment startup instructions
```

The Linux cloud prepares compilation/tests and an internal display for UI smoke checks. It cannot validate Windows native inference, installer signing or physical microphone behavior. See [PROJECT.md](PROJECT.md) for the framework decision and [PLANS.md](PLANS.md) for the remaining increments.
