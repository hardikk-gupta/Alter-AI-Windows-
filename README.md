# Alter for Windows

Alter opens to a local chat dashboard with a labeled activity rail, a collapsible conversation panel, and a text composer with a local model selector. Use a chat’s menu to favorite or archive it. Memories, notes, upcoming tasks, capture and Settings share the same shell. The workspace details card shows the selected speech model and local engine. See the [native screen gallery](design/desktop-review.html) and [Android parity ledger](docs/ANDROID_PARITY.md).

## Download the runnable app

For this preview, open [the Windows pull request](https://github.com/hardikk-gupta/Alter-AI-Windows-/pull/2/checks), select the successful **Desktop source checks** run for the branch, and download **Alter-Windows-Installer** under Artifacts. For an existing workspace, use version **0.2.3 or later** and close Alter before installation. Extract the downloaded artifact ZIP, then double-click the `.msi` inside it to install Alter for your Windows user account. Launch **Alter** from the Start menu. The installer includes Java and the local inference engines; model weights download separately in Settings. GitHub may require you to sign in to see repository artifacts. This development installer is unsigned, so Windows may show a publisher warning.

The separate **Alter-Windows** artifact is a portable folder ZIP. Extract it completely and run `Alter.exe` inside the folder if you prefer not to install. Downloading the repository's source ZIP will not install or run the app.

If a downloaded Qwen model fails to start, install the newest MSI, open Settings → AI models, and tap **Use model** again. The model does not need to be downloaded twice. The current build opens its runtime log from the error banner and shows common startup causes directly. The earlier 0.1.1 runtime was compiled for its build machine's CPU and may fail on other processors.

If the Windows job has not passed or no artifact appears, use the source steps below while the packaging failure is fixed. The [source ZIP](https://github.com/hardikk-gupta/Alter-AI-Windows-/archive/refs/heads/main.zip) contains code, not a runnable app.

## Try the desktop preview

Choose **Settings → Workspace → Personal → New project**. Each project has its own chats, notes, settings and instructions. In **Settings → Actions & schedules**, save a public GitHub repository as `owner/name`. In chat, type or dictate **review my repo**, then confirm to fetch the last five workflow runs. This command also works as a guest without a text model. Schedule a weekday brief on the same settings page and enable the tray option to keep it running with the window closed.

**Chat → Plugins** lists connections. Select **Connect** to see setup and permissions. GitHub’s **Manage → Propose a file change** opens a file editor and a draft PR review. Choose **Review later** on a desktop action to revisit it from Home.

**Settings → AI models** supports downloaded Qwen models, local GGUF files, Ollama, LM Studio and a session-only OpenAI key. Google and GitHub sign-in require [provider registration](docs/DESKTOP_PRODUCT_GOAL.md). Preview installers are attached to the successful PR workflow run.

## Run on Windows

Install a **JDK 21**. From PowerShell in this checkout:

```powershell
.\gradlew.bat test
.\gradlew.bat run
```

The wrapper pins Gradle and verifies its distribution SHA-256. The interface uses the system font.

Data is stored under `%LOCALAPPDATA%\Alter\alter-desktop.db`. It is private local data, without backup/sync yet. Do not uninstall/delete this directory to update the application. Start a second copy only after closing the first; concurrent editors are not supported yet. Linux development uses `~/.local/share/Alter`; `-Dalter.dataDir=...` overrides the data directory for disposable tests.

Use Capture to save a Memory, Note or Task. Tasks accept one explicit commitment per line; no reminder time is invented. Ctrl+N opens a new chat in Alter AI and capture in the other workspace views. Enter or Ctrl+Enter sends chat, Shift+Enter inserts a line break, and the standard Tab/Space/Enter keys operate controls. Completed checkboxes stay saved. Deletion is confirmed and soft-deletes records; there is no Trash restoration UI yet.

For local AI, open Settings → AI models, download Qwen and tap **Use model**. The app verifies the pinned size and SHA-256 before running its bundled CPU llama.cpp server. For speech, download and select Whisper Tiny or Base, then tap **Mic** and **Stop mic** in Alter AI. Review the resulting draft before sending. See [model details](docs/WINDOWS_MODELS.md). Microphone/GPU performance, Windows DPI/input and OS integrations need Windows acceptance.

On an RTX 4050, you can select a compatible official Windows CUDA `llama-server.exe` through **Use NVIDIA GPU runtime…** in AI models for faster local text inference. Keep that runtime's DLLs in the same folder as its executable. Alter's included CPU runtime remains available for lower-end PCs.

Alter suggests a smaller model on lower-memory PCs, checks free disk space before a download, and keeps inference unloaded until you choose **Use model**. The river renders at a bounded resolution and frame rate on slower systems. Models and personal data live under `%LOCALAPPDATA%\Alter`, and version 0.2.3 installs the application under `%LOCALAPPDATA%\Programs\Alter`. Install 0.2.3 directly over older versions to preserve the workspace and downloads. Each installer release must increase the version; install the newer MSI to upgrade. Automatic update checks/downloads are not available yet.

## Cloud development

```bash
bash scripts/setup_cloud.sh
source /workspace/setup/activate.sh
./gradlew test
./gradlew run  # requires a display; see saved environment startup instructions
```

The Linux cloud prepares compilation/tests and an internal display for UI smoke checks. It cannot validate Windows native inference, installer signing or physical microphone behavior. See [PROJECT.md](PROJECT.md) for the framework decision and [PLANS.md](PLANS.md) for the remaining increments.

In Settings → AI models → Other providers & runtimes, **Find installed models** checks running Ollama and LM Studio servers on this PC. Select a listed chat-capable model to send messages to that runtime. For explicit cloud chat, enter an OpenAI key and Chat Completions model ID and choose **Use OpenAI for chat**. The key lasts for this session unless you save it in Windows Credential Manager. OpenAI receives the instructions and recent conversation only when you send with that provider selected. The chat composer’s plus menu also opens a local capture editor for a typed or dictated draft.
