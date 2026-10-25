# Design reference

Authority: upstream UI/theme and latest AGENTS corrections at the locked Android SHA. The designer now explicitly requests a left desktop navigation panel and web-inspired desktop layout while keeping Alter's identity.

Dark tokens: yellow #FFBD00, black #000000, card #18181B, raised/border #262626, text #ECECEC, muted #A1A1AA. Light and device themes are retained. Satoshi is the UI face; ASCII uses monospace. Interface hierarchy follows 26/34 headings, 20/28 secondary, 16/24 body and 14/20 supporting text. Memory cards retain their 24dp metadata strip, 18dp outer envelope, 4dp inset and 14dp inner surface, with 15dp note titles.

The desktop workspace base is explicitly pitch black (#000000). Gray (#18181B) is reserved for the sidebar, cards and form surfaces; the root Surface must use `colorScheme.background`, because Material `Surface` otherwise defaults to the gray surface token. The river renders over the black base rather than changing it.

Home, Alter AI, Memories, Upcoming and Notes stay in original order in a persistent left desktop panel, using upstream rounded vector glyphs and Alter mark. The panel uses the familiar desktop pattern of brand header, selected route and bottom Settings. No glass/gradients across the app. Chat alone has a neutral translucent matte prompt and quiet rim with 12dp corners. Settings uses categories/subpages. Capture is one unlabeled plus at the lower corner; its Memory/Note/Task menu scales and fades from that anchor and closes back to it. Directional page transitions are still pending.

One root-owned river simulation/renderer is preserved across destinations and dialogs. Home/Memories/Upcoming/Notes show it only when the destination has no content, independent of search results; chat always shows it, dimmed with saved messages. Source species/geometry/cruise and fixed square ASCII grid are retained. Rendering runs off the UI thread. It pauses when hidden, minimized or motion is reduced. Desktop visual acceptance, DPI/resize and original capture/nav motion are pending.
