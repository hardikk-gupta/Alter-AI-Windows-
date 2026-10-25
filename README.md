# Alter for Windows

First desktop increment of Alter, referenced to [Android main at 0bda3d8](https://github.com/hardikk-gupta/ALTER-AI-code-base/tree/0bda3d8583d1f53b6f305e1eeb22628c17a08e66).

This build provides five destinations in a left desktop panel, local memories, notes, tasks, themes and a shared river. Text chat and microphone transcription use local Windows runtimes and separately downloaded models. See [parity and limits](docs/ANDROID_PARITY.md); this is not full Android parity.

## Download the runnable app

Open [Desktop source checks in GitHub Actions](https://github.com/hardikk-gupta/Alter-AI-Windows-/actions/workflows/windows.yml), select the latest successful run on `main`, and download **Alter-Windows-Installer** under Artifacts. Extract the downloaded artifact ZIP, then double-click the `.msi` inside it to install Alter for your Windows user account. Launch **Alter** from the Start menu. The installer includes Java and the local inference engines; model weights download separately in Settings. GitHub may require you to sign in to see repository artifacts. This development installer is unsigned, so Windows may show a publisher warning.

The separate **Alter-Windows** artifact is a portable folder ZIP. Extract it completely and run `Alter.exe` inside the folder if you prefer not to install. Downloading the repository's source ZIP will not install or run the app.

If the Windows job has not passed or no artifact appears, use the source steps below while the packaging failure is fixed. The [source ZIP](https://github.com/hardikk-gupta/Alter-AI-Windows-/archive/refs/heads/main.zip) contains code, not a runnable app.

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

For local AI, open Settings → AI models, download Qwen and tap **Use model**. The app verifies the pinned size and SHA-256 before running its bundled CPU llama.cpp server. For speech, download and select Whisper Tiny or Base, then tap **Mic** and **Stop mic** in Alter AI. Review the resulting draft before sending. See [model details](docs/WINDOWS_MODELS.md). Microphone/GPU performance, Windows DPI/input and OS integrations need Windows acceptance.

On an RTX 4050, you can select a compatible official Windows CUDA `llama-server.exe` through **Use NVIDIA GPU runtime…** in AI models for faster local text inference. Keep that runtime's DLLs in the same folder as its executable. Alter's included CPU runtime remains available for lower-end PCs.

Alter suggests a smaller model on lower-memory PCs, checks free disk space before a download, and keeps inference unloaded until you choose **Use model**. The river renders at a bounded resolution and frame rate on slower systems. Models and personal data live under `%LOCALAPPDATA%\Alter`, separate from the installed app, so installing a later version keeps them. Each installer release must increase the version; install the newer MSI to upgrade. Automatic update checks/downloads are not available yet.

## Cloud development

```bash
bash scripts/setup_cloud.sh
source /workspace/setup/activate.sh
./gradlew test
./gradlew run  # requires a display; see saved environment startup instructions
```

The Linux cloud prepares compilation/tests and an internal display for UI smoke checks. It cannot validate Windows native inference, installer signing or physical microphone behavior. See [PROJECT.md](PROJECT.md) for the framework decision and [PLANS.md](PLANS.md) for the remaining increments.
