# Alter desktop product goal

A Windows user can speak or type a request, choose where inference happens, use tools connected to a project, and inspect the outcome later. The local workspace works as a guest. Google and GitHub are both launch requirements for the first account release.

## Inventory and delivery map

| Experience | Starting point and next deliverable | Acceptance |
|---|---|---|
| Home, Notes, Memories, Upcoming | Existing local SQLite records and editing. Home adds recent projects, approvals and finished runs. Add provenance and an explicit forget action to personal memory. | Restart preserves records and a deleted memory drops out of retrieval. |
| Projects | Add project identity, membership for threads and documents, project instructions and per-project connector grants. Existing threads open in the personal workspace after migration. | Switching projects isolates retrieval, chat history and granted tools. |
| Identity | Reuse the Android Firebase identity where available. Implement both Google and GitHub browser sign-in with verified desktop return handling, session expiry, sign-out and deliberate account linking. Keep account sign-in separate from connector permissions and opt-in sync. | Both providers and local guest work on Windows. Linking verifies ownership and preserves local notes. |
| Models | Bundled verified Qwen and Whisper are the baseline. This branch adds manual discovery of Ollama and LM Studio at their default loopback ports, explicit model selection, and a session-only OpenAI API key. Next add user-selected GGUF import, capability checks and opt-in OS credential storage. | Show the active provider before sending. Local failures stay local. Measure first reply and peak memory on an entry-level PC and the Ryzen/4050. |
| Skills | This branch ports Android’s three bundled guides and opens a reviewable memory, note or task from the chat draft. Next add project-specific instruction-only skill import, required connections and enable/disable. | Dictate a draft, choose a skill, review or cancel, save and reopen the entry. Instructions alone grant no tools. |
| Tool calling and Activity | Add a typed tool registry, argument validation, cancellation, action previews and a durable run record. Associate each call and actual result with the requesting thread. Start with selected-repository checks and issue summaries. | A failed call reports its error. Editing a repository produces a reviewable PR. |
| Plugins | Port Android MCP concepts to desktop transport and browser OAuth. List tools and scopes, test the connection, enable per project, revoke and reconnect. Ship GitHub repository reads and Figma file reads first, then Calendar and Drive. | Figma works independently of the user's Alter sign-in provider. Revoking a connection prevents its next tool call. |
| Voice and computer actions | Whisper currently fills a draft. Add a global push-to-talk shortcut, tray controls, indicator, stop, then allowlisted app launch. For Spotify playback, connect Spotify separately and target the selected device. Prefer app APIs; use foreground Windows accessibility actions when a task needs a visible window. | “Open Spotify and play…” either confirms actual playback or reports account/device availability. A voice request to review a repo produces linked evidence. |
| Scheduling | Upcoming saves commitments. Add explicit scheduled runs with a timezone, recurrence, pause, next run, missed-run policy, and idempotent execution. Background mode should release unused models. | A weekday project brief runs once across restart. Show what happens on wake when the PC sleeps through a run. |
| Discovery and reuse | Add a command palette based on enabled capabilities, connection tests, and Run again / Save as skill from Activity. | A user can tell what is available before making a request and reuse a successful run. |

## Integration decisions before shipping

Use the system browser for sign-in. Confirm the identity project and provision Google and GitHub client registrations and callbacks. Keep provider secrets on the owning service. Store refresh tokens or optional saved API keys with Windows credential protection. Exercise account linking, revocation and cancellation using test accounts.

Keep desktop APIs and plugin authorization independent. GitHub repository access can use a scoped GitHub App installation. Figma remote MCP needs Figma authorization. Spotify playback requires the corresponding scopes, a Premium account and an available device. Locally scheduled work needs a running PC and background process. Cloud execution while the machine is off would be a separate service.

## First integrated demo

Sign in, select a project and repository, hold push-to-talk, and ask which checks need attention. Show which model received the request, the repository queried, the source links, and a cancellable Activity record. Repeat using a guest workspace and local models for actions that need no account.

## References

Android main reviewed at `6cc6c25c6e19a5f82bc569695b9b12ad57bc3258`, including AgentSkills.kt, agent_skills.json, FIREBASE_CLOUD_SYNC.md and MCP_CONNECTOR_GATEWAY_SETUP.md. Its newer chat checklist behavior is tracked separately from desktop model selection.

[Google native OAuth](https://developers.google.com/identity/protocols/oauth2/native-app) · [GitHub authorization](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps) · [Figma MCP](https://github.com/figma/mcp-server-guide) · [MCP authorization](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization) · [Spotify playback](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback) · [Ollama discovery](https://docs.ollama.com/api/tags) · [LM Studio](https://lmstudio.ai/docs/developer/rest) · [Agent Skills](https://agentskills.io/specification)
