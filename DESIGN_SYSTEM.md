# Design reference

Authority: upstream UI/theme and latest AGENTS corrections at the locked Android SHA. The designer now explicitly requests a left desktop navigation panel and web-inspired desktop layout while keeping Alter's identity.

Dark tokens: yellow #FFBD00, black #000000, card #191919, raised #191919, border #262626, text #ECECEC, muted #A1A1AA. Light and device themes are retained. Windows uses the system UI font. ASCII uses monospace. Interface hierarchy follows 26/34 headings, 20/28 secondary, 16/24 body and 14/20 supporting text. Memory cards retain their 24dp metadata strip, 18dp outer envelope, 4dp inset and 14dp inner surface, with 15dp note titles.

The desktop workspace base is explicitly pitch black (#000000). Gray (#191919) is reserved for the sidebar, cards and form surfaces; the root Surface must use `colorScheme.background`, because Material `Surface` otherwise defaults to the gray surface token. The river renders over the black base rather than changing it.

The Windows executable and installer use `packaging/alter.ico`, generated from the pinned Android `mipmap-xxxhdpi/ic_launcher.webp` (source SHA-256 `eff1ca55f980d2f0eb83cb1afaa8029ae995e24c6d2e72fb745af79e2ffdb5e5`). The app window also sets a PNG from the same source. This is the existing yellow Alter face on its dark circle, with 16–256px icon sizes for the Start menu, taskbar and desktop shortcut.

The chat dashboard follows the two designer-supplied desktop references. A 43dp toolbar shows the current thread or destination. The left panel combines a 42dp centered icon rail and a 174dp navigation pane on one full-height surface flush with the left edge. Collapsing the panel leaves the icon rail visible. Saved-chat search opens from Settings → Workspace. Favorites, Chats, Library and Archive collapse independently. The footer opens installed models. Settings contains project switching and commands. Chat includes Plugins beside New chat. Connection steps and permissions appear in the Connect dialog. Below 850dp the toolbar opens that panel on demand. The workspace-details card sits at the upper right from 1160dp and opens on demand at smaller widths.

The transcript and bottom composer share an 810dp maximum width. User turns align right in gray capsules, assistant prose aligns left, and completed replies include Copy. The composer holds multiline text, a local model menu, dictation and send/stop. Alter yellow identifies primary actions. The anchored plus menu on the record screens expands to Memory, Note and Task. Actual Compose captures are in design/previews.

One root-owned river simulation/renderer is preserved across destinations and dialogs. Home/Memories/Upcoming/Notes show it only when the destination has no content, independent of search results; chat always shows it, dimmed with saved messages. Source species/geometry/cruise and fixed square ASCII grid are retained. Rendering runs off the UI thread. It pauses when hidden, minimized or motion is reduced. Desktop visual acceptance, DPI/resize and original capture/nav motion are pending.

The desktop Material color roles follow Android Color.kt and Theme.kt at `5b05604861a3e7d236b820e603c620deea61fe93`.

Desktop controls use Hugeicons Free Stroke Rounded 4.4.0 from Android main at 5b05604. The 18dp rail mark sits above the icon buttons. Collapse navigation hides the desktop panel and the toolbar restores it.
