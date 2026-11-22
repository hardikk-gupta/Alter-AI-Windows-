# Alter desktop product goal

A Windows user can speak or type a request, choose where inference happens, use tools connected to a project, and inspect the outcome later. The local workspace works as a guest. Google and GitHub are both launch requirements for the first account release.

## Inventory and delivery map

| Experience | Implemented on the preview branch | Remaining acceptance |
|---|---|---|
| Home and projects | Recent projects, editable project instructions, isolated local databases, pending approvals, finished runs, notes and memory provenance | Exercise the project workflow with real user data. |
| Identity | Local guest, Firebase exchange for Google desktop PKCE and GitHub Device Flow, deliberate account linking, session expiry and sign-out | Register provider clients and verify both sign-ins and linking with test accounts on Windows. |
| Models | Bundled Qwen and Whisper, existing GGUF files, local Ollama and LM Studio capability checks, explicit OpenAI BYOK, optional Windows Credential Manager storage | Run the performance test on an entry-level laptop and the Ryzen HS / RTX 4050. |
| Skills | Bundled capture skills, imported Markdown instructions, per-project enable/disable and required connections, saving successful commands as skills | Check with a real connected project. |
| Tools and Activity | Model function-call validation, chosen targets, immediate or deferred consent, cancellation, durable outcomes, originating chat and replay. Manual repository edits create a draft PR | Verify publication using a user-selected repository and scoped GitHub credential. |
| Plugins | Project-specific GitHub, Figma Desktop and Cloud, Calendar, Drive and Spotify connections | Verify live remote consent, reads, expiry and provider-side revocation. |
| Voice and Windows actions | Local transcription, global hold-to-dictate, explicit app launch, Spotify device playback, user-selected window maximize/restore | Check physical microphones and real Spotify Premium playback. |
| Scheduling | Weekday GitHub briefs, timezone and next run, pause/resume, missed-run coalescing, tray and idle engine release | Exercise laptop sleep and wake on the target hardware. |
| Discovery | Ctrl+K lists available commands. Chat exposes Plugins and project skills. Home links to Activity and approvals | Review keyboard navigation and Windows DPI on the target device. |

## Preview branch status

Settings → Workspace opens separate local databases for each project. Personal retains the existing database. Workspace instructions accompany model requests. Settings → AI models can load a user-selected GGUF with the bundled engine. Other providers & runtimes → Test local performance measures model verification and load time, the first complete response to a fixed prompt and peak engine working set. Measurement uses a fresh engine and the selected CPU or CUDA runtime. Peak working set excludes GPU VRAM and the desktop UI. CI publishes the same Qwen 0.6B CPU measurement for its Windows runner. Settings → Actions & schedules supports reviewed “open Spotify”, “open my repo” and “review my repo” commands, per-project Activity and one weekday public-repository workflow brief per project. A missed brief runs once on resume. Tray mode is opt-in.

Google desktop PKCE and GitHub device authorization exchange their credentials with Firebase. Unconfigured providers show “setup required”. To test, enable both providers in the same Firebase project, register a Google desktop client and enable Device Flow for the GitHub OAuth app. Supply ALTER_FIREBASE_WEB_API_KEY, ALTER_GOOGLE_DESKTOP_CLIENT_ID, ALTER_GOOGLE_DESKTOP_CLIENT_SECRET and ALTER_GITHUB_CLIENT_ID in the app process environment. GitHub's server-side client secret belongs in Firebase. Live sign-in, linking and revocation require registered clients and test accounts.

### Plugins, tools and voice in the preview

Each project can enable the Figma Desktop MCP server on loopback. Alter initializes the session, discovers get_metadata and reads the selected layer after confirmation. Google Calendar reads upcoming events and Drive lists recent file metadata with separate browser grants. Enable the respective Google APIs on the configured Google Cloud project. Spotify playback needs a Spotify developer app, ALTER_SPOTIFY_CLIENT_ID, a registered redirect of http://127.0.0.1:43897/callback, a Premium listener and an active device. Connector access tokens expire in memory. Disconnect clears the local grant. Provider-side revocation remains available in the provider account.

Plugins can opt a compatible chat model into proposing commands. Alter validates each function against enabled commands and asks for confirmation. Action results appear in Activity and the originating thread. Ctrl+K opens the command list. Project Markdown skills import as reviewable plain instructions. Windows packaging adds a Ctrl+Alt+Space hold-to-dictate shortcut and optional per-project Windows Credential Manager storage for OpenAI. An idle hidden window releases the bundled model after one minute.

Project settings support editing project names and instructions. Import text or Markdown from Voice & files, review it as a note and choose it in the composer’s Add project context dialog. Saved records show their source. Forgetting a memory removes it from the picker. Activity records include timestamps, links to the originating chat and a Save as skill action. Manual GitHub reads can use a fine-grained token bound to the selected repository. Scheduled public briefs use anonymous access.

### Draft pull requests, approvals and connected skills

From Chat → Plugins → GitHub → Manage, choose **Propose a file change**. A separate repository token with Contents and Pull requests write permissions loads the file at a specific Git commit. Review the original and replacement before creating a draft PR. Alter creates a new branch and preserves the original file mode. The dialog retains a recovery link after branch creation and checks for an existing pull request on retry.

Action previews can be deferred to Home and Activity, then approved once or dismissed. Home shows recently opened projects. Imported skills can require individual plugins and take the user to Connect when a required service is missing. “Maximize a window” and “restore a window” open a native window picker for confirmation. The Windows helper checks the selected caption and process before invoking the Window pattern and verifying the result.

Figma Desktop uses the local selection. Figma Cloud uses the remote MCP server and a browser grant. If Figma accepts dynamic public-client registration, Alter stores the public client ID for reuse. Otherwise, configure ALTER_FIGMA_MCP_CLIENT_ID with the registered desktop redirect http://127.0.0.1:43898/callback. Connect tests for get_metadata. In chat, enter /figma followed by the link to a design layer. Live Figma Cloud consent and file reads require an eligible Figma account.

## Integration decisions before shipping

Use the system browser for sign-in. Confirm the identity project and provision Google and GitHub client registrations and callbacks. Keep provider secrets on the owning service. Store refresh tokens or optional saved API keys with Windows credential protection. Exercise account linking, revocation and cancellation using test accounts.

Keep desktop APIs and plugin authorization independent. GitHub repository access can use a scoped GitHub App installation. Figma remote MCP needs Figma authorization. Spotify playback requires the corresponding scopes, a Premium account and an available device. Locally scheduled work needs a running PC and background process. Cloud execution while the machine is off would be a separate service.

## First integrated demo

Sign in, select a project and repository, hold push-to-talk, and ask which checks need attention. Show which model received the request, the repository queried, the source links, and a cancellable Activity record. Repeat using a guest workspace and local models for actions that need no account.

## References

Android main reviewed at `6cc6c25c6e19a5f82bc569695b9b12ad57bc3258`, including AgentSkills.kt, agent_skills.json, FIREBASE_CLOUD_SYNC.md and MCP_CONNECTOR_GATEWAY_SETUP.md. Its newer chat checklist behavior is tracked separately from desktop model selection.

[Google native OAuth](https://developers.google.com/identity/protocols/oauth2/native-app) · [GitHub authorization](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps) · [Figma MCP](https://github.com/figma/mcp-server-guide) · [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) · [Spotify playback](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback) · [Ollama discovery](https://docs.ollama.com/api/tags) · [LM Studio](https://lmstudio.ai/docs/developer/rest) · [Agent Skills](https://agentskills.io/specification)
