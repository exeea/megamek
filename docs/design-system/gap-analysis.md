# MegaMek Suite design system: gap analysis

Status: draft v0 for discussion. Step 1 of the unified look-and-feel effort.

## Goal and decisions so far

One look and feel for the controls of MegaMek, MegaMekLab, MekHQ and the launcher, implemented twice from one source
of truth:

- **libGDX**: the native board and HUD (`megamek.client.ui.gdx`).
- **Swing**: a FlatLaf theme plus a small custom component library in `megamek`, used by every Swing app.

Agreed so far:

| Topic | Decision |
| --- | --- |
| Swing toolkit | Stay on Swing + FlatLaf; no JavaFX. |
| Source of truth | One token file; platform themes are generated from it. MekBay may follow it but is not bound by it. |
| Custom Swing components | Only where FlatLaf theming cannot reach; they live in `megamek` so MML and MHQ share them. |
| Visual reference | The libGDX hud-v3 style. The Immersive/interstellar blue palette is retired in favor of it. |
| Modes | Dark **and** light. |
| Scaling | Required on both platforms. |
| Accessibility | Contrast and colorblind safety are criteria when choosing tokens. |
| Fonts and icons | Shared 100% across platforms. |
| Pilot | Buttons first. |
| Base | Start from hud-v3 structure, but fix its contrast and colorblind problems rather than copying its palette. |
| Swing themes | The four stock FlatLaf themes are replaced by MegaMek Dark and MegaMek Light. |
| Button family | Collapse the 19 hud-v3 styles into a small variant × size set. |
| Focus | Designed for Swing first, then ported to libGDX. |
| On, selected and focus | **Final.** Three looks. **On** (checked toggle, selected segment, on chip, check box, radio, switch) is the solid cream `surface.inverse` face with `text.onInverse` marks, as hud-v3's `button-checked`; a checked toggle looks like the primary button, as in hud-v3. **Selected** (list, table and tree rows, tabs, selectable panels, unit rows, menu and select checks) is a neutral wash plus a `selected.primary` (inverse color) marker, underline, edge or check. **Mint** is only focus, progress, slider fill, links, info and badges. Focus on a cream face is the mint ring `focus.gap` outside it, falling back to two inside lines where it would be clipped. On while disabled is the faint `surface.inverseDisabled` face with a `text.onInverse` label. This replaces the earlier "mint selection everywhere". Violet stays only for the Game Master (`status.gamemaster`). See [tokens.md](tokens.md#color-roles). |
| Palettes | **Final.** Four themes: dark and light, each in a standard and a color-blind safe palette, picked by a user setting ("Color-blind safe colors"). Standard statuses use the expected red, amber and green and are checked for typical vision; the color-blind palette changes only the statuses and is checked for all simulated vision types. Contrast is checked in all four. Player and team colors are out of scope; whether MekHQ and MegaMekLab share the setting is decided when they adopt the tokens. Both galleries have a switch. See [tokens.md](tokens.md#palettes). |
| Frames | Corner ticks for regular frames; Immersive dialogs may keep their own style. |
| Startup screens | `MegaMekButton` bitmap skins will probably be replaced by the shared button (not final). |
| libGDX light mode | Deferred; the HUD is dark only for now. The Java2D board has no light/dark theming either. |
| Accent | hud-v3 mint, retuned as needed; it stops doubling as the success color. |
| Migration | The stock themes stay behind a hidden "classic" option until migration is done. |
| Presentation mode | **Final.** Two modes, chosen per screen: **game** and **app**. The mode sets the text case, the [type width](tokens.md#type-width) and the slider thumb (game: hud-v3's thin bar; app: a disc), and later differences join it as whole-screen choices. Game mode puts the short framing labels (one to three words) in upper case; app mode is sentence case. Content is sentence case in both. Game screens to start with: the in-game libGDX HUD, startup and main menus, MekHQ Immersive dialogs and the interstellar map. Everything else starts as app and can move to game when it is reviewed. Weight and size rules are shared; upper-case text uses each style's wider **caps tracking** token (values signed off: display .04, headline .05, title .06, label .08, overline .1 em). Both galleries have a switch between the modes. See [tokens.md](tokens.md#presentation-mode). |
| Type width | **Final.** Roboto Condensed (`wdth` 75) for game mode's framing roles (display, headline, title, button, tab, overline) and a new `name` role for unit and pilot names (`body.strong`, sentence case), at the same weight (500) and tracking rules; body, small, caption, field labels, tables, values and running text stay regular width, and app mode is all regular width. Condensed packs some 15-20% more into narrow HUD columns and reads as technical and military; it stays off running text, tables and app mode because condensed letters crowd at 10-12 px and tire in long text. hud-v3's wide tracking on mixed case is not adopted: it uses up the space condensed saves. All condensed roles are medium, so Swing needs one more static face, Roboto Condensed Medium. See [tokens.md](tokens.md#type-width). |
| Panels and rails | **Final.** Ticked frames (panels, dialog cards, status cards) draw their edge only at the top and bottom, a paper roll, since the ticks close the sides. The edge stays the quiet `border.subtle` so busy screens don't fill with lines (revisited and kept). Only the outermost frame has ticks: cards in a card grid stay plain, so a grid doesn't repeat a tick set per card. hud-v3's `panel-rails` (the Tactical View chip) keeps its rails as `token-rails`; `token-frame` is the plain frame. See [tokens.md](tokens.md#panels). |
| Dialog buttons | **Final.** Centred, equal width, main action first, in both presentation modes. hud-v3 puts them in the bottom-right corner, but that is a desktop OS habit, and one rule for both modes means shared dialogs don't move their buttons. See [tokens.md](tokens.md#dialogs). |
| Segmented control | **Final.** Separate segments, `xs` apart, each with its own edge, instead of a joined frame with hairlines, in both sizes: hud-v3's joined `hud-pill` becomes the compact size, not a second look. See [tokens.md](tokens.md#segmented-control). |
| Primary button | **Final.** The `surface.inverse` face, hud-v3's cream `hud-main` (dark in light mode), not mint, so mint stays for focus; checked toggles share its look. See [tokens.md](tokens.md#buttons). |
| Menus and popups | **Final.** A plain popup frame without corner ticks in both modes; ticks frame lasting containers (panels, dialogs, status cards), and submenus would stack them. See [tokens.md](tokens.md#menus). |
| Dial | **Final.** The angle dial keeps hud-v3's mint hub ring, and the needle stops at it. See [tokens.md](tokens.md#inputs). |
| Framed button | Corner ticks that become a full frame on hover (hud-v3 bracket, Immersive `FramedCommandButton`) are one shared special-purpose variant. |
| MekHQ `hud` library | Folded into the new library. |
| Launcher | Retune its colors to the tokens for now; shared controls wait for the planned monorepo. |
| Accessibility modes | Light and dark only; no dedicated high-contrast mode. |
| Character | A game UI, not an OS one. Where game conventions and desktop conventions differ, prefer what fits a game; desktop conventions are a fallback, not the goal. |
| Token location | `megamek` repository, next to both theme implementations. Fonts and icons stay in `mm-data`. |
| Ownership | Vicente owns UI decisions and presents them to the team. |
| Review | Runnable galleries opened from MegaMek: one Swing, one libGDX. The Swing library is the complete set; libGDX is a subset that grows on demand. |
| Naming, translucency | Role-based token names with a `UiTheme` rename pass; surfaces get opaque and overlay values. |
| In-game UI | Once a game starts, all UI is libGDX and dark only; the remaining in-game Swing dialogs migrate to libGDX. The lobby stays Swing. |
| Animation | Broad UI animation (for example panel reveals) comes after the unification pass. |

## Sources inventoried

| Source | Where | Tech | Notes |
| --- | --- | --- | --- |
| hud-v3 toolkit | [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java), [`UiKit`](../../megamek/src/megamek/client/ui/gdx/UiKit.java), [`UiButton`](../../megamek/src/megamek/client/ui/gdx/UiButton.java) and 15 more widgets; documented in [gpu-hud.md §8](../gpu-hud.md) | libGDX scene2d | The reference. Ported from the MekBay "hud-v3" CSS mock; comments cite its CSS classes (`.b`, `.b.util`, `.row`, `.fsel`...). Dark only. |
| Immersive controls | MekHQ `mekhq/gui/baseComponents/ImmersiveControlStyle` + `ImmersiveComboBox`, `ImmersiveCheckBox`, `ImmersiveRadioButton`, `ImmersiveSpinner`, `ImmersiveScrollBarStyle` | Swing, hand-painted | Navy/cyan palette, dark only. |
| Immersive dialogs | MekHQ `mekhq/gui/baseComponents/immersiveDialogs/ImmersiveDialogStyle` and `ImmersiveDialog*` | Swing on top of the active FlatLaf theme | Teal signal + amber information, **dark and light** (picks by `Panel.background` luminance). Cut corners, monospace "technical" labels, grid/stripe backdrop. |
| Interstellar map | MekHQ `mekhq/gui/InterstellarMapPanel` (10k lines, ~200 color constants), `InterstellarMapLegend` | Swing/Java2D | Same navy/cyan palette as Immersive controls, plus map-domain colors (HPG classes, routes, territories). |
| MekHQ hud library | `mekhq/gui/baseComponents/hud` (17 classes: `HudStyle`, `HudButton`, `HudCard`, `HudChip`, `HudSegmentedControl`, `HudTabStrip`...) | Swing, hand-painted | Same navy/cyan palette, fixed dark. Merged Oct 2026 with the new contract GUI; 49 files use it. Its name collides with libGDX "hud-v3". |
| Framed command button | `mekhq/gui/baseComponents/FramedCommandButton(Style)` | Swing | Corner ticks that grow into a full frame on hover (320 ms). Theme-derived teal, dark and light. Base of the Immersive dialogs' response buttons. |
| Standalone launcher | `mm-launcher/.../gui/LauncherTheme` | Swing, **no FlatLaf and no megamek dependency** | A copy of the Immersive palette (identical RGB values). Honors Windows high-contrast mode. |
| App startup screens | MegaMek `client/ui/widget/MegaMekButton` + `SkinSpecification`/`SkinXMLHandler` (`defaultSkin.xml`); used by MekHQ `StartupScreenPanel` and MegaMek's main menu | Swing, image-skinned | Bitmap backgrounds, text colors from skin XML (white / yellow active / grey disabled). |
| Everything else in Swing | MM, MML, MHQ | FlatLaf | User picks one of four stock themes: Flat Light, Flat IntelliJ, Flat Dark, Flat Darcula (`MegaMek.java`); MML and MHQ apply the stored choice. No custom FlatLaf properties anywhere. (Since the Swing trial, MegaMek defaults to MegaMek Dark and lists the stock themes as classic ones.) |

About 128 MekHQ source files reference an Immersive component or the interstellar map, so the blue palette has real
reach.

## Tokens

### Color

The libGDX names are mostly *appearance* names (`MINT`, `CORAL`, `FILL`). Light mode needs *role* names, so the
proposed column below is a first draft of semantic names for Token v0.

| Proposed role | hud-v3 (libGDX) | Immersive / map / launcher | Immersive dialogs (dark / light) | FlatLaf stock | Gap |
| --- | --- | --- | --- | --- | --- |
| `surface.panel` | `PANEL` rgba(18,24,26,.92) | `PANEL` #050D17, `CONTROL_BACKGROUND` #0C1D2A | derived from `Panel.background` | theme | hud-v3 panels are translucent (sit over the board); Swing needs an opaque equivalent. |
| `surface.raised` | `PANEL2` rgba(28,36,38,.97) | `ACTIVE_BACKGROUND` #122D38 | mix(panel, label, 6%) | theme | |
| `surface.popover` | `POP` #172022 | `POPUP_BACKGROUND` #07101B | — | theme | |
| `surface.input` | white @ .05 over panel | `INPUT_BACKGROUND` #11404E | — | theme | |
| `text.primary` | `TEXT` #F1F4F0 | `TEXT` #DAE7EB | `Label.foreground` | theme | |
| `text.secondary` | `ACCENT` #CBD3D0 (button labels) | — | — | — | hud-v3 `ACCENT` is a light grey, not an accent; rename. |
| `text.muted` | `MUTED` #9AA6A3 | `MUTED` #8499A1 | — | theme | |
| `text.disabled` | `DISABLED` #5F6B69 | `MUTED` (reused) | — | theme | |
| `border.default` | `QUIET` rgba(174,187,180,.24) | `BORDER` #234252 | — | theme | Both fail 3:1 (see Accessibility). |
| `border.hover` | `HOVER` #E8F1EB | — | — | theme | |
| `border.divider` | `LINE` white @ .08 | `DIVIDER` cyan @ 45/255 | — | theme | |
| `accent.primary` (focus, selection, active) | `MINT` #82E2CE | `ACCENT` #41D2E0 | signal #56D0C5 / #177070 | theme | Three different teals today. Converge on one per mode. |
| `accent.selected` (current/selected item) | — (selection uses `MINT` or `FILL`) | `SELECTED` #EBA642 (orange) | — | — | hud-v3 has no orange "selected"; the map's selected-system/active-route orange must map to a role. |
| `fill.strong` (checked / primary button face) | `FILL` #F0F2EE, `MAIN` #F1F3F2 | — | — | — | Inverted (light-on-dark) faces are a hud-v3 signature; need a light-mode rule. |
| `fill.strong.text` | `FILL_INK` #16201E, `FILL_MUTED` #39433F | — | — | — | |
| `status.danger` | `CORAL` #EC9189 (hover #FF554C) | `NAVIGATION_BLOCKED` #EA5656 | — | — | |
| `status.warning` | `AMBER` #E6C991 | `NAVIGATION_CAUTION` #F2B848 | information #EBB14C / #965C10 | — | |
| `status.success` / friendly | `MINT` (shared with accent) | `PLAYER_BASE` #57D6BE | — | — | Accent and success share a color in hud-v3. |
| `status.info` / special | `VIOLET` #B9A8FF | — | — | — | |
| `status.attention` | `BLIP` #FFAE66 | `CURRENT_LOCATION` #FFBE52 | — | — | |
| `badge` | `AMBER` with #1A1A1A text | — | — | — | |
| `scrollbar.thumb` | #4A5553 (v), #738581 (h) | #366571 | — | theme | |

Out of scope for the shared palette, but should *use* status tokens where the meaning matches: map-domain colors
(HPG classes, faction territory, reachability, routes), hex terrain, unit tints.

### Typography

| | hud-v3 | Swing today |
| --- | --- | --- |
| Family | Roboto variable (`mm-data/data/fonts/Roboto`), Regular/Medium/Condensed instances | OS default via FlatLaf (Segoe UI on Windows); Immersive labels use `Font.MONOSPACED` bold |
| Scale | 13 named styles, 10–23 units: `hud-body` 13, `hud-small` 11.5, `hud-medium` 12.5, `hud-utility` 10.5, `hud-sub` 10, `hud-caption` 10.5, `hud-mini` 11, `hud-button` 12.5, `hud-name` 13.5, `hud-title` 16, `hud-heading` 20, `hud-phase` 23, `hud-main` 17 | FlatLaf default size (scaled by the user's GUI scale) |
| Tracking | Per style, 0–.14em; uppercase at display time for titles, captions, buttons | None |
| Fallbacks | Noto Sans Symbols 2, then Bitter Bold for arrows | JDK fallback |

Gaps:

- Swing needs to load Roboto (variable font) at runtime. Java2D does not select variable-font named instances;
  static Roboto/Roboto Condensed weights may need to ship alongside, or FlatLaf's bundled Roboto font extension used.
  To investigate in step 4.
- Swing has no letter-spacing beyond `TextAttribute.TRACKING`. **Resolved:** the tokens give each style a tracking
  and, where it can be upper case, a wider caps tracking; Swing applies them through `TextAttribute.TRACKING`
  (`TokenFonts`), libGDX bakes them into the font.
- 13 sizes is a HUD scale; desktop forms need a body size that reads well at 100% scaling. Decide whether Swing uses
  the same scale or a subset.
- Uppercase-at-display is a style rule both platforms must apply the same way (and it must be locale-aware for i18n).
  **Resolved:** the [presentation](tokens.md#presentation-mode) modes; both platforms read the same role rules from the tokens
  and upper-case with the player's locale when the text is drawn.

### Shape, spacing and size

| | hud-v3 | Immersive / launcher | Immersive dialogs |
| --- | --- | --- | --- |
| Corner radius | 3 on buttons, fields, rows, chips; 2 on pill sections | 0 (square) | 0 + **cut corners** of 11 |
| Panel frame | `HudFrame`: rail border plus corner ticks (13) | 1px border | Angular frame, 2px signal underline on headers, 3px left bar on information panels |
| Button heights | 36 default, 46 main, 26 mini, 30 icon, 34 plain, 56 utility | 30 (`CONTROL_HEIGHT`) | FlatLaf default |
| Button padding (v × h) | 8 × 12 default, 3 × 9 mini | — | — |
| Field padding | 7 × 10 | — | — |
| Panel padding | 12 × 14 | — | 10 frame, 6 section gap |
| Border width | 1 (1.5 on dial hub, 2 on tab underline) | 1 | 1, 2, 3 |

Gap: hud-v3 has an implicit spacing scale (2, 3, 4, 6, 7, 8, 9, 10, 12, 13, 14) rather than a stepped one. Token v0
should propose a step scale (for example 2/4/6/8/12/16/24) and map existing values onto it.

### Icons

- hud-v3: Material Symbols Rounded variable font (`mm-data/data/fonts/Icons`), ~60 named glyphs baked into a texture
  sheet; four are drawn filled.
- Swing: the interstellar map already draws one Material Symbol glyph (`MATERIAL_EDIT_SYMBOL`); elsewhere PNG icons and
  FlatLaf's built-in icons.
- Good news: Roboto, Material Symbols and Noto Symbols 2 are already in mm-data `main`, not only on the GDX branch.
- Gap: the icon *name* map (`UiTheme.ICONS`) belongs in the token source so both platforms use the same names and
  codepoints. Swing can render the same font glyphs, or use the SVG versions through FlatLaf's `FlatSVGIcon`.

### Motion

- hud-v3 state changes (hover, pressed, checked) are **instant**; only `UiList` reordering animates (critically damped
  springs, ~150 ms settle).
- Immersive dialogs have text reveal and distortion effects (bespoke; stay custom).
- Interstellar map animates at 16 ms steps; zoom settles after 180 ms.
- FlatLaf has no state transitions of its own, so the Swing themes add them: one shared animator eases every control
  between states on the `motion` tokens, with a reduce-motion setting; see [tokens.md](tokens.md#motion).

### Scaling

| | Rule |
| --- | --- |
| hud-v3 | One logical-pixel scale per window ([`DisplayScale`](../../megamek/src/megamek/client/ui/gdx/DisplayScale.java)): max(monitor DPI, window resolution / 1600×1000), capped so 960×640 units fit, times the user preference. Tokens are in those units (one unit = one CSS px of the mock). |
| Swing | `UIUtil.scaleForGUI(n)` on every fixed measurement, plus FlatLaf's own `flatlaf.uiScale`. |

Both platforms already think in logical pixels, so tokens can be logical pixels with no conversion. The open point is
that hud-v3 also grows with window size, which Swing does not, so the same token can render at different physical sizes
in the HUD and in a Swing dialog side by side.

## Components

### Buttons (pilot)

hud-v3 already has **19 button styles**, so consolidation is needed inside libGDX too, not only across platforms:

| hud-v3 style | Look | Proposed variant |
| --- | --- | --- |
| `hud` | quiet: faint fill, `QUIET` border, hover border `HOVER`, checked = filled `FILL` | **secondary** (default) |
| `hud-main` | filled `MAIN` with `FILL_INK` text, 46 high | **primary** |
| `hud-danger`, `hud-delete-icon` | coral text, coral-tinted hover | **danger** (+ icon-only size) |
| `hud-plain` | quiet, normal-case Roboto Medium, 34 high | secondary, *normal-case* option |
| `hud-mini`, `hud-utility-small` | 26 / 28 high | secondary, **small** size |
| `hud-icon` | no frame at rest, framed on hover, 30×30 | **ghost**, icon-only |
| `hud-utility`, `-narrow`, `-row` | bracket corner ticks at rest, outline on hover, filled when checked; icon above label, 56 high | **toolbar / utility** (HUD-specific? decide) |
| `hud-seg`, `hud-pill` | segments of one pill; pressed section filled | **segmented control** (separate component) |
| `hud-tab`, `hud-tab-caps` | text only; checked = 2px mint underline | **tabs** (separate component) |
| `hud-row`, `hud-menu-row` | list row / popover item | **list row**, **menu item** (separate components) |
| `hud-select`, `hud-select-inline` | drop-down face; inline has a dotted underline | **select** (separate component) |

States used: up, over, down (= over), checked, checkedOver (= checked), disabled. There is **no focus state**, so
keyboard users cannot see where focus is. A shared spec must add `focused` (a focus ring) on both platforms.

Swing today: FlatLaf `JButton` (theme-dependent, with focus ring), `MegaMekButton` (bitmap skin), the Immersive
`TransmissionResponseButton` (frame style), the launcher's `FirstLaunchButton` and split buttons, and the map's
layer-control buttons. Roughly six visual families.

### Other controls

| Component | hud-v3 | Immersive (Swing) | FlatLaf | Notes |
| --- | --- | --- | --- | --- |
| Text field | `field` box, mint border + mint tint on focus | `INPUT_BACKGROUND` | yes | Search field variant in both. |
| Select / combo | `hud-select` face + `UiPopover` list | `ImmersiveComboBox` | yes | |
| Checkbox | Material Symbols `checkbox-on`/`-off` glyphs | `ImmersiveCheckBox` | yes | Radio: not in hud-v3. |
| Number / spinner | `UiNumber` (caption scrub), `UiSliderInput` | `ImmersiveSpinner` | yes | Scrub-to-change is HUD-only behavior. |
| Tabs | underline tabs | — | `JTabbedPane` | FlatLaf supports underline tabs natively. |
| List row / tree | `UiList`, `UiTree` | — | `JList`, `JTree` | |
| Popover / menu | `UiPopover`, `UiMenuList` | map popup colors | `JPopupMenu` | |
| Tooltip | `panel` frame, `hud-small`, wraps at 340 | — | yes | |
| Dialog / modal | `UiModal`, `UiKit.dialog` (620 wide), `UiConfirmation` | `ImmersiveDialog*` | `JDialog` | Decided: corner ticks; a modal card over a scrim, or a window. See [Dialogs](tokens.md#dialogs). |
| Status card | `UiKit.Progress` (print card) | — | — | Decided: the dialog's frame without a scrim, Cancel in the header. See [Status cards](tokens.md#status-cards). |
| Panel / frame | `HudFrame` with corner ticks | angular surface | `JPanel` | Decided: the dialog's frame without rails, status ticks, selectable and plain variants. See [Panels](tokens.md#panels). |
| Scrollbar | 4-wide thin knob | `ImmersiveScrollBarStyle` | yes | FlatLaf thin-scrollbar properties cover it. |
| Chip / badge | `chip`, `chip-warn`, `chip-bad`, amber badge | — | — | Decided: status, filter and tag chips in sentence case; count and dot badges in the accent. See [Chips and badges](tokens.md#chips-and-badges). |
| Toast | `GpuToastStack` (3px bar, above the dock) | `BoardToastOverlay` (tinted fill) | — | Decided: top centre, bar and icon, the newest on top, hover pause and ✕. See [Toasts](tokens.md#toasts). |
| Section header / rule | `rule`, `UiKit.header` | `createSectionHeader` (mono label + rule) | — | |

## Accessibility findings

Measured against hud-v3's panel composited over a dark board (#111719). WCAG 2.2 asks for 4.5:1 for body text, 3:1 for
large text, and 3:1 for the borders that identify a control (non-text contrast).

| Pair | Ratio | Verdict |
| --- | ---: | --- |
| `TEXT` on panel | 16.3 | pass |
| `ACCENT` (button label) on panel | 11.9 | pass |
| `MUTED` on panel | 7.2 | pass |
| `DISABLED` on panel | 3.3 | ok (disabled is exempt) |
| `MINT` / `CORAL` / `AMBER` / `VIOLET` on panel | 11.8 / 7.7 / 11.3 / 8.7 | pass |
| `FILL_INK` on `FILL` | 14.8 | pass |
| **Button border** `QUIET` on panel | **1.7** | **fail** (3.0) |
| **Field border** white@.18 on panel | **1.8** | **fail** |
| **Chip border** #4B5654 on panel | **2.4** | **fail** |
| Divider `LINE` on panel | 1.2 | ok if purely decorative |
| Immersive `BORDER` on control background | 1.6 | fail |

The quiet-button and field outlines are the hud-v3 look's defining feature, and they fail non-text contrast. Options:
raise them to ~3:1, or keep them subtle and make sure every control is also identifiable by its label or fill. This is
a design call for the group.

Colorblind simulation (Machado 2009, full severity), CIE ΔE76 between status colors, where below ~20 is hard to tell
apart at a glance:

| Pair | Normal | Deutan | Protan | Tritan |
| --- | ---: | ---: | ---: | ---: |
| `CORAL`–`AMBER` (danger vs warning) | 36 | **15** | 25 | 32 |
| `AMBER`–`BLIP` | 27 | **18** | **16** | 21 |
| `CORAL`–`BLIP` | 33 | 29 | 35 | **12** |
| `MINT`–`CORAL` (friend vs foe) | 70 | 27 | 22 | 90 |

Danger vs warning collapses for deuteranopes (~5% of men). Status colors need a second cue (icon or shape, as the chips
partly do with `chip-warn`/`chip-bad`), and danger should probably move to a redder, darker hue to separate it from
amber. Friend vs foe stays distinguishable but with a thin margin under protanopia.

Light mode: there are no hud-v3 light values to measure yet. Immersive dialogs' light colors (signal #177070 on light
panels) are the only light-mode precedent.

## Gaps and decisions needed

Items 1–10 and 12–14 are settled (see the table at the top); item 1 is deferred for libGDX but still needs design
work for Swing, and item 11 is under investigation.

1. **Light mode does not exist in hud-v3.** Every token needs a light value, and the inverted `FILL` faces need a
   light-mode rule. Needs design work, not just mapping.
2. **User-selectable Swing themes.** Today users pick among four stock FlatLaf themes. Proposal: replace them with
   "MegaMek Dark" and "MegaMek Light" (generated), optionally keeping stock themes as "classic" during migration.
3. **Accent.** Three teals today (#82E2CE, #41D2E0, #56D0C5). Converge on hud-v3 `MINT` for dark; derive a light
   counterpart.
4. **Orange "selected".** The map and Immersive controls use orange for the selected/current item; hud-v3 uses mint
   or a white fill. Decide whether `accent.selected` exists or maps to `accent.primary`.
5. **Frame language.** hud-v3 corner ticks vs Immersive cut corners. Pick one for panels and dialogs; the other can
   stay as a decorative variant of Immersive dialogs. *Decided: corner ticks for panels and dialogs; cut corners stay
   in the Immersive dialogs.*
6. **Focus state.** hud-v3 has none; add a focus ring to the spec.
7. **Border contrast.** See Accessibility.
8. **Danger hue / colorblind cues.** See Accessibility.
9. **Button family size.** 19 hud-v3 styles → about 5 variants × 3 sizes, plus separate tabs, segmented, row, menu
   item and select components.
10. **Utility (bracket) buttons.** HUD-only, or part of the shared set (for example a Swing toolbar)?
11. **Fonts in Swing.** Java2D only draws the default instance of the Roboto variable font, so medium (500) rendered
    as regular. Fixed with a static Roboto Medium generated into `mm-data`; see [tokens.md](tokens.md#fonts).
12. **Startup screens.** `MegaMekButton` bitmap skins: migrate to the shared button, or keep as themed art?
13. **Translucency.** hud-v3 panels are translucent over the board; Swing surfaces are opaque. Tokens need both an
    opaque and an overlay value for surfaces.
14. **Token naming.** hud-v3 names (`ACCENT` is grey, `MINT` doubles as success) do not survive light mode; the
    generator will emit role names, and `UiTheme` will need a rename pass.

## Next steps

- ~~Token v0~~: done, see [tokens.md](tokens.md).
- ~~Shared assets~~: static Roboto Medium and Roboto Condensed Medium for Swing are in `mm-data`.
- ~~FlatLaf theme~~: MegaMek Dark and MegaMek Light are built from the tokens and selectable; see
  [tokens.md](tokens.md#swing-themes). Making them the default and hiding the stock themes behind a classic option
  comes after the controls are settled.
- ~~Button spec~~: Primary, Secondary, Quiet, Danger, Toggle, Icon and Special in regular and compact sizes, with
  every state including focus, in Swing and libGDX; see [tokens.md](tokens.md#buttons).
- ~~Focus~~: one keyboard-only ring inside the edge for every Swing control, and on libGDX buttons; see
  [tokens.md](tokens.md#focus).
- ~~Inputs~~: text, password, search, number and text area at button heights, with hover, focus, status, read-only
  and disabled looks, plus the form field (label above, helper or status below), in Swing; libGDX has the in-game
  subset (no number spinner yet); see [tokens.md](tokens.md#inputs).
- ~~Choices~~: check box (with a mixed state), radio button and switch, with hover, focus and disabled looks, in
  Swing and libGDX; see [tokens.md](tokens.md#choices).
- ~~Tabs and segmented control~~: underlined tabs and a segmented control of separate segments with a cream
  selected one, in two sizes, in Swing and libGDX; see [tokens.md](tokens.md#tabs).
- ~~Select~~: an input-faced select in two sizes and an inline one with a dotted underline, sharing one popup list
  (neutral wash, a check on the current value), in Swing and libGDX; see [tokens.md](tokens.md#select).
- ~~Lists and trees~~: inset rounded rows at least 28 high, a neutral hover wash, and a stronger neutral wash plus a
  leading marker for the selection, with two-line rows and an empty state, in Swing; libGDX keeps hud-v3's `UiList` and
  `UiTree` until the developers accept the system; see [tokens.md](tokens.md#lists-and-trees).
- ~~Menus~~: menu bars, menus and context menus on the select's popup, with neutral hover rows, checks and
  radios, and muted shortcuts, in Swing; see [tokens.md](tokens.md#menus).
- ~~Tooltips~~: small cards on the popup surface that wrap at 280, with a key chip for shortcuts and a title over
  secondary detail for info cards, in Swing; see [tokens.md](tokens.md#tooltips).
- ~~Scrollbars~~: a thin pill thumb with no track or arrows of its own, sitting on the content it scrolls, with a
  rail and a brighter thumb under the pointer, in Swing; see [tokens.md](tokens.md#scrollbars).
- ~~Sliders~~: an accent fill up to a cream `surface.inverse` thumb (a round disc in app mode, hud-v3's flat thin bar in game mode) on a pill track, the same with ticks and labels, with an optional
  value readout, in Swing; see [tokens.md](tokens.md#sliders).
- ~~Tables~~: the list's rows across every column with faint zebra stripes, a hover wash over the cells, the
  selection wash and marker, and upper-case overline headers aligned like their column, in Swing; see
  [tokens.md](tokens.md#tables).
- ~~The rest of the gallery~~: progress bars (a thin accent pill with its text above it), section headers (titled
  panels, collapsible sections and separators share one upper-case header with a line) and split panes (a subtle line
  that turns accent under the pointer), in Swing; see [tokens.md](tokens.md#progress-bars),
  [Sections](tokens.md#sections) and [Split panes](tokens.md#split-panes). No view uses a toolbar, so there is none.
- ~~Motion~~: hover that comes in fast and lingers out, instant presses with fading releases, drawn checks, sliding
  switches, the Special button's closing ticks, fading popups and tooltips, opening sections and per-row hover washes,
  from one shared animator with a reduce-motion client setting, in Swing; see [tokens.md](tokens.md#motion).
- ~~Dialogs~~: a corner-ticked modal card over a scrim for messages, confirmations and short forms, and a themed window
  for tools, with centred equal-width buttons (main action first), Enter and Escape, Cancel-first destructive
  confirmations and restyled `JOptionPane` dialogs, in Swing; see [tokens.md](tokens.md#dialogs).
- ~~Status cards~~: the progress of a long task in the dialog's frame, beside the view or over a scrim, with Cancel in
  the header, a percentage on the bar and a failed state with Close and Retry, in Swing; see
  [tokens.md](tokens.md#status-cards).
- ~~Panels and frames~~: the dialog card's corner-ticked frame for anything that isn't a dialog, with an optional
  header, status ticks with their icon, selectable unit cards (hover, selection, focus ring, one at a time),
  plain frames for groups inside a panel and a border for existing panels, with paper-roll edges, in Swing; see
  [tokens.md](tokens.md#panels).
- ~~Chips and badges~~: status chips with their icon and an edge in the status color, filter chips (a check when on,
  one at a time in a group), tag chips removed by their ✕ or Delete, and count and dot badges in the accent or a status
  color on a button's corner, in Swing; see [tokens.md](tokens.md#chips-and-badges).
- ~~Toasts~~: short notices at the top centre of a view, a bar and an icon in the level's color (the Game Master in
  violet with a shield), the newest on top and at most five, paused by the pointer, closed by their ✕, with clicks
  passing through, in Swing; see [tokens.md](tokens.md#toasts).
- ~~All-caps~~ (discussion closed): a game and an app presentation mode, chosen per screen. Game mode upper-cases only
  the short framing labels, with signed-off caps tracking, and sets them and unit names in condensed type (see Type
  width); content stays sentence case in both modes. Both galleries switch between the modes and compare each hud-v3
  font with its token type as a game screen draws them, on a realistic sample for that kind of text. In Swing and
  libGDX; see [tokens.md](tokens.md#presentation-mode).
- ~~Drake's feedback~~: presentation mode (text case plus the slider thumb), violet kept only for the Game Master, standard and color-blind safe palettes as a user setting, paper-roll panel edges, rails
  kept for `panel-rails`, separate segments and the dial's mint hub, in Swing and libGDX.
- ~~Type width~~: game mode sets the framing labels and unit names in Roboto Condensed Medium (a second static face for
  Swing in `mm-data`), the rest regular width, in Swing and libGDX; see [tokens.md](tokens.md#type-width).
- ~~On state~~: cream for every "on" control (toggles, segments, chips, check boxes, radios, switches), neutral
  selection for items in collections, mint only for focus and accents, an outside focus ring on cream faces and a
  faint cream disabled-on look, in Swing and libGDX; see [tokens.md](tokens.md#color-roles).
- A Swing trial: move MegaMek's startup path (everything before the libGDX board opens) onto the themes and the new
  controls, so the developers can judge the system in real use. The startup path is also reorganized where the old
  layout gets in the way. Steps, one screen at a time:
  1. ~~Theme~~: MegaMek Dark is MegaMek's default; settings list the MegaMek themes, the stock ones behind **Show
     classic themes**. Players who picked a theme keep it.
  2. ~~Shared dialog footers~~: `AbstractButtonDialog`, `AbstractValidationButtonDialog` and the Host/Connect
     dialogs put their buttons in `TokenDialogs.footer`, Ok as the primary button first. Dialogs with their own
     footers (settings, game options, scenario chooser, unit and bot dialogs) move with their own steps.
  3. Main menu, in libGDX (a Swing version dropped frames on ordinary machines, and the 2D board that kept the
     startup path in Swing is going away): a full-window game screen over the battle art
     (`GpuStartWindow`, `GpuStartScreen`), opened by a skippable "Welcome back" greeting and reveal on app launch (an
     instant cut with reduce motion), with Settings, Help and Tools on screen and Up, Down, Tab and Enter moving
     through the menu. The developers chose the hex reveal: hexes pop open across the art from left to right. The menu
     sits in a column on the right, because the art's main mech stands on the left, with the tip card at the bottom
     left. The window opens maximized the first time and remembers its bounds;
     the lobby opens at the same bounds. If OpenGL cannot start, MegaMek shows an error and exits. The licensing
     notice waits for the reveal to end. The old splash medal is dropped. Its pages replace the menu in the menu's own
     column, with Back (Esc) to return, so the art stays in view. ~~G1, the window and menu~~, ~~G2, the Host
     page~~ and ~~G3, the Connect page~~ are done. **New game** opens the Host page: the player name, then a closed
     **Online options** section
     (password, port, the server browser and the local and public addresses), **Host game** and Back. The section
     opens by itself when the password or port was changed before or the port is wrong; errors show under the
     inputs, and Enter in a field hosts while Tab moves to the next control. The password has an eye button that shows
     it as typed, in both presentations, because a game password is a word shared with friends and there is no
     confirm field to catch a typo. The local address shows at once; the public one is only asked of
     checkip.amazonaws.com when the player clicks **Show**, so it stays off streams and screenshots and MegaMek makes no
     outside request unasked. **Connect to a game** opens the Connect page: a **Player | Bot** segmented control at
     the top, the name, the server address beside its port, a **Strategic BattleForce game** check box for the player
     only (there is no SBF bot), **Connect** (**Connect bot**, which opens the bot setup) and Back; errors show under
     the inputs as on Host. It has no password: joining a game never asks for one, since MegaMek's server password
     only guards game options and server commands, which ask for it when they need it. Next come Load game (G4), the
     scenario browser (G5) and the briefing (G6). Until then, the Swing dialogs open over the start window, and
     Ctrl+click reaches quick load and the last scenario. To review a page without clicking through it, set
     `MM_START_PAGE` (such as `host+online+reveal` or `connect+bot`) before `gradlew :megamek:run`;
     `MM_START_SHOT` saves a PNG from the screen's own frame buffer, `MM_START_SHOT_AT` sets when, and
     `MM_START_QUIT=true` quits afterwards (`StartScreenProbe`).
  4. ~~Host and Connect~~ as modal cards over the main menu in app mode: form fields with their labels above, the
     main action named for what it does (**Host game**, **Connect**) and errors under the inputs instead of popups.
     Host keeps the password, port and server browser in a closed **Online options** section, which opens when the
     password or port was changed before or one of them is wrong. Bot setup moves with the lobby's dialogs (step 8). The new main menu shows these forms as
     its own pages; the card stays for MekHQ.
  5. The scenario flow.
  6. The lobby as a briefing room in game mode: a top bar with Ready and summary chips, players on the left, forces in
     the centre, the battlefield (map, conditions, victory) on the right and a chat dock.
  7. The unit selector. 8. The lobby's dialogs. 9. A settings check.
- If the developers accept it, move hud-v3 onto the tokens and the new libGDX controls (including the `UiTheme`
  rename pass). Until then, hud-v3 code does not change.
