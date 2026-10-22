# MegaMek Suite design system: the Immersive / interstellar-map style as the base

Status: draft v0 for discussion. Companion to [gap-analysis.md](gap-analysis.md), which takes the libGDX hud-v3 style
as the base. This document asks the same questions with the MekHQ Immersive / interstellar-map style as the base
instead, then compares the two.

## The "blue family" today

It is not one codebase but five that share one hard-coded palette (identical RGB values, copied):

| Member | Where | Classes | Modes | Notes |
| --- | --- | --- | --- | --- |
| Interstellar map chrome | MekHQ `InterstellarMapPanel`, `InterstellarMapLegend`, `MapTab` | 3 (map panel is 10k lines, ~200 color constants) | dark only | Origin of the palette. Layer controls reuse the Immersive controls. |
| Immersive controls | MekHQ `baseComponents/Immersive*` | 6: `ControlStyle`, `CheckBox`, `RadioButton`, `ComboBox`, `Spinner`, `ScrollBarStyle` | dark only | |
| MekHQ hud library | MekHQ `baseComponents/hud` | 17: `HudStyle`, `HudButton`, `HudConfirmButton`, `HudCard`, `HudChip`, `HudCheckBox`, `HudSegmentedControl`, `HudTabStrip`, `HudModeSelector`, `HudStatTile`, `HudTrack`, `HudRing`, `HudVerdictBanner`, `HudGuideDrawer`, `HudDateField`, `HudRichTextEditor`, `Hud` | dark only ("deliberately fixed rather than theme-derived") | Newest member (Oct 2026, contract GUI, salvage, StratCon wizard); 49 files use it. The most complete Swing component set in the suite. |
| Immersive dialogs + framed buttons | MekHQ `baseComponents/immersiveDialogs`, `FramedCommandButton(Style)` | ~14 | **dark and light**, derived from the active FlatLaf theme | Uses a *different* teal (#56D0C5 dark, #177070 light) and amber information color. |
| Launcher | `mm-launcher/.../gui/LauncherTheme`, `FirstLaunchButton`, `StyledComboBox`, `MapCheckBox`, split buttons | ~8 | dark + **Windows high-contrast** | Standalone: no FlatLaf, no megamek dependency. |

All five are Swing. None of it exists in libGDX.

## Tokens

### Color (dark)

| Proposed role | Blue family value | hud-v3 equivalent | Notes |
| --- | --- | --- | --- |
| `surface.ground` | `GROUND`/`PANEL` #050D17 | — (board shows through) | |
| `surface.panel` | `SURFACE` #0F1E2B, `CONTROL_BACKGROUND` #0C1D2A | `PANEL` | Two near-identical values; merge. |
| `surface.popover` | `SURFACE_DEEP`/`POPUP_BACKGROUND` #07101B | `POP` | |
| `surface.input` | `INPUT_BACKGROUND` #11404E | white @ .05 | Inputs are noticeably bluer than panels: a strong, recognizable cue. |
| `surface.hover` | `SURFACE_HIGHLIGHT`/`ACTIVE_BACKGROUND` #122D38 | white @ .04 | |
| `surface.pressed` | `PRESSED_BACKGROUND` #214952 | = hover | |
| `surface.disabled` | `DISABLED_BACKGROUND` #131D22 | — | |
| `text.primary` | `TEXT` #DAE7EB | `TEXT` | |
| `text.muted` | `TEXT_MUTED` #9EB3BB | `MUTED` | |
| `text.faint` / disabled | `TEXT_FAINT` #8499A1 | `DISABLED` | Blue-family "faint" is also used for live content, not only disabled. |
| `border.default` | `BORDER` #234252 | `QUIET` | |
| `border.accent` | `BORDER_CYAN` cyan @ 107 | — | Primary button and tooltip outline. |
| `border.divider` | `DIVIDER` cyan @ 40–45 | `LINE` | |
| `accent.primary` | `ACCENT` #41D2E0 | `MINT` | Focus, hover outline, planned route, popup selection text. |
| `accent.bright` | `ACCENT_BRIGHT`/`BUTTON_ICON` #7DE6EE | — | Primary button label, icons. |
| `accent.selected` | `SELECTED`/`AMBER` #EBA642 | — | Checked checkbox, selected radio, selected system, active route. |
| `status.success` | `READY` #52C7A0, `PLAYER_BASE` #57D6BE | `MINT` | |
| `status.warning` | `CAUTION` #EBA642, `NAVIGATION_CAUTION` #F2B848 | `AMBER` | **`CAUTION` is the same color as `SELECTED`.** |
| `status.danger` | `DANGER` #D7574F, `NAVIGATION_BLOCKED` #EA5656 | `CORAL` | Two near-duplicates (ΔE 8); merge. |
| `status.attention` | `CURRENT_LOCATION` #FFBE52, `URGENT_OPERATION` #FFDC7A | `BLIP` | `CURRENT_LOCATION` vs `SELECTED`: ΔE 9, effectively the same color. |
| `scrollbar.thumb` | #366571 | #4A5553 | |

Light values: only the Immersive dialogs and `FramedCommandButton` have them (signal #177070, information #965C10), and
they are derived at runtime by mixing with the FlatLaf panel color. The map, Immersive controls, hud library and
launcher would all need light-mode design, the same work as with hud-v3. `HudStyle` documents the fixed dark palette as
deliberate ("the map chrome it echoes is always dark"), so light mode is a reversal of an explicit decision there.

### Typography

The blue family has no typeface of its own: it derives from FlatLaf's `Label.font` (Segoe UI on Windows, other system
fonts elsewhere), always bold for chrome, with size factors (0.70, 0.74, 0.78, 0.82, 0.85 of the label size) and
letter tracking (0.12–0.14) via `TextAttribute.TRACKING`. Immersive dialog labels use `Font.MONOSPACED` bold.

Since fonts must be 100% shared, this base still has to adopt hud-v3's Roboto and type scale. The blue family's
contribution is the *rule* (bold, tracked, upper-case captions), which matches hud-v3's condensed caps styles closely.
`HudStyle.hudFont` already proves that tracked fonts work in Swing.

### Icons

Only one Material Symbol is used (the map's edit glyph); otherwise hand-drawn shapes (combo arrows, check marks) and
PNGs. The shared icon set would come from hud-v3 either way.

### Shape, spacing, size

| | Blue family | hud-v3 |
| --- | --- | --- |
| Corner radius | 0 everywhere (square) | 3 |
| Border | 1 px; 1.5 on checkbox/radio; 2 px on framed-button corners | 1 px |
| Signature frame | `FramedCommandButton`: 12-unit corner ticks that grow into a full frame on hover; dialogs: cut corners (11) | `HudFrame`: corner ticks on panels; utility "bracket" buttons: ticks at rest, outline on hover |
| Control height | 30 (`CONTROL_HEIGHT`, all inputs) | 36 buttons, varies |
| Button padding (v × h) | `HudButton` 9 × 22 (compact 5 × 11); `FramedCommandButton` 8 × 16; launcher 14 × 18 / 8 × 14 / 5 × 10 | 8 × 12 (mini 3 × 9) |
| Checkbox / radio size | 18 | icon glyph |

The two families already share the corner-tick idea; only hud-v3 animates it instantly and the blue family over
320 ms.

### Motion

The blue family animates more: the framed-button corner grow (320 ms), map layer, selection and route animations
(16 ms steps), zoom settle (180 ms), transmission reveal. hud-v3 state changes are instant. If the blue family is the
base, a motion token set is required (durations and an easing), and libGDX would implement hover transitions it does
not have today. libGDX does this cheaply (scene2d `Actions`); in Swing each animated control needs its own timer, as
`FramedCommandButton` does.

### Scaling

All members use `UIUtil.scaleForGUI` (the launcher its own `GuiScale` copy). Same as the Swing side of the hud-v3
option; nothing new.

## Components

### Buttons

The blue family has **about 7 button implementations**, none of them a FlatLaf-styled `JButton`:

| Implementation | Look | States | Focus visible? | Notes |
| --- | --- | --- | --- | --- |
| `HudButton` primary | translucent cyan gradient, `BORDER_CYAN` hairline, `ACCENT_BRIGHT` label | idle, hover, disarmed (dashed outline) | **no** | A `JPanel`, not a `JButton`: no accessible "button" role, no mnemonic, no default-button support. |
| `HudButton` secondary (+ compact) | flat `SURFACE`, `BORDER`, muted label | idle, hover, disarmed | **no** | Same. |
| `HudConfirmButton` | `HudButton` variant with a confirm step | | | |
| `FramedCommandButton` | corner ticks → full frame, theme-derived teal | idle, active, pressed, disabled | yes (frame) | A real `JButton`. Dark and light. |
| `TransmissionResponseButton` | `FramedCommandButton` + "transmitting" overlay | + locked | yes | Immersive dialogs. |
| Launcher `FirstLaunchButton` | flat fill, 1 px outline, bright outline on hover/focus | idle, hover, pressed, focus, disabled | yes | Three sizes; high-contrast aware. |
| Map layer-control buttons | flat, cyan outline, hover/pressed fills | idle, hover, pressed | | Inside `InterstellarMapPanel`. |

A consolidated set would look like:

| Variant | Source |
| --- | --- |
| **primary** | `HudButton` primary (cyan gradient + bright label) |
| **secondary** (default) | `HudButton` secondary / launcher button |
| **framed** (command / response) | `FramedCommandButton` |
| **danger** | none today; needs design (`DANGER` #D7574F) |
| **ghost / icon** | map layer-control buttons |
| sizes | regular / compact (from `HudButton`, launcher has three) |

### Other controls

| Component | Blue family | hud-v3 |
| --- | --- | --- |
| Checkbox | `ImmersiveCheckBox`, `HudCheckBox`, launcher `MapCheckBox` (three copies) | glyph checkbox |
| Radio | `ImmersiveRadioButton` | none |
| Combo / select | `ImmersiveComboBox`, launcher `StyledComboBox` | `hud-select` + popover |
| Spinner | `ImmersiveSpinner` | `UiNumber`, `UiSliderInput` |
| Segmented / tabs | `HudSegmentedControl`, `HudTabStrip`, `HudModeSelector` | `hud-seg`, `hud-pill`, `hud-tab` |
| Chip | `HudChip` | `chip`, `chip-warn`, `chip-bad` |
| Card / stat tile / ring / track | `HudCard`, `HudStatTile`, `HudRing`, `HudTrack`, `HudVerdictBanner` | `UiCardGrid`, meter |
| Date field, rich text editor | `HudDateField`, `HudRichTextEditor` | none |
| Guide / drawer | `HudGuideDrawer` | none |
| List, tree, popover/menu | Swing defaults recolored (map popup, launcher split-button popup) | `UiList`, `UiTree`, `UiPopover`, `UiMenuList` |
| Scrollbar | `ImmersiveScrollBarStyle`, launcher `MapScrollBarStyle` (two copies) | thin knob |
| Dialog frame | Immersive dialogs (cut corners, monospace technical labels) | `UiModal`, `UiKit.dialog` (corner ticks) |
| Tooltip | launcher `toolTip` (popup bg + cyan border) | panel frame |

Swing coverage is broader than hud-v3's in forms (radio, date, rich text, cards); hud-v3 is broader in data views
(list with drag-reorder, tree with keyboard navigation, popover menus).

## Accessibility findings

Measured on the blue family's ground (#050D17). Same thresholds: 4.5:1 body text, 3:1 control borders.

| Pair | Ratio | Verdict |
| --- | ---: | --- |
| `TEXT` on ground | 15.4 | pass |
| `TEXT` on input (#11404E) | 8.9 | pass |
| `TEXT_MUTED` on ground / on surface | 8.9 / 7.8 | pass |
| `TEXT_FAINT` on ground | 6.6 | pass |
| `TEXT_FAINT` on input | 3.8 | **fail** for body text |
| `ACCENT` on ground | 10.7 | pass |
| `ACCENT_BRIGHT` on primary button fill | 10.1 | pass |
| `AMBER` / `READY` / `DANGER` on ground | 9.4 / 9.3 / 5.0 | pass (danger tightest) |
| Disarmed `HudButton` label | 2.8 | ok (disabled exempt) |
| **`BORDER` on ground** | **1.8** | **fail** |
| **`BORDER` on surface** (secondary button) | **1.6** | **fail** |
| **`BORDER_CYAN` on ground** (primary button) | **2.7** | **fail** (close) |
| **Combo/spinner `BORDER` vs its own input fill** | **1.06** | **fail**: the border is invisible; the input reads by its fill instead |
| **Unchecked checkbox border** | **1.6** | **fail** |
| `ACCENT` focus/hover border | 10.7 | pass |
| Divider | 1.3 | ok if decorative |
| Scrollbar thumb | 3.0 | pass (borderline) |

Border contrast fails the same way as hud-v3 (1.6–2.7 vs 1.7–2.4). Neither base solves it; it is a design decision
either way. The blue family partly compensates because inputs have a distinctly colored fill.

Focus: better than hud-v3 overall (combo, spinner, checkbox, framed button and launcher all show focus with `ACCENT`),
but the newest library, `HudButton`, shows none.

Colorblind simulation (Machado 2009, full severity), ΔE76; below ~20 is hard to tell apart at a glance:

| Pair | Normal | Deutan | Protan | Tritan |
| --- | ---: | ---: | ---: | ---: |
| `AMBER`–`DANGER` (warning vs danger) | 48 | 30 | 45 | 34 |
| `READY`–`DANGER` (good vs bad) | 96 | 32 | 29 | 117 |
| `ACCENT`–`AMBER` (accent vs selected) | 92 | 82 | 74 | 89 |
| `ACCENT`–`READY` (accent vs success) | 30 | 28 | 29 | **8** |
| `AMBER`–`CURRENT_LOCATION` | **9** | **8** | **9** | **12** |
| `DANGER`–`NAVIGATION_BLOCKED` | **8** | **4** | **3** | **9** |

The blue family's status colors separate **better** than hud-v3's: warning vs danger stays at ΔE 30 for deuteranopes,
against 15 for hud-v3's coral/amber. The low pairs are duplicates (same meaning, two values) rather than conflicts,
except accent vs success under tritanopia (rare, about 0.01%).

The real problem is semantic: **`SELECTED` and `CAUTION` are the same orange**, so a selected checkbox, the selected
system and a navigation warning all look alike. A shared system needs separate tokens.

High contrast: the launcher is the only suite UI that honors Windows high-contrast mode. Worth keeping in any base.

## Cost to adopt the blue family as the base

| Area | Work |
| --- | --- |
| Swing | Move and consolidate, not build: about 30 MekHQ classes plus the launcher copies into `megamek`, merge the 3 checkbox, 2 scrollbar and ~7 button implementations, make `HudButton` a real `JButton` with focus, add a FlatLaf theme for everything not hand-painted. Breaking change for MekHQ's 49 `hud` users and ~128 Immersive/map users (mostly import moves). |
| libGDX | Restyle all of hud-v3: retune the ~20 tokens in `UiTheme`, plus **54** hard-coded colors in the toolkit and **125** in the battle HUD (`boardview/gpu`); swap radius 3 → 0; add hover/frame animations; rework the corner-tick frames to the framed-button style. Every HUD screenshot and the GPU HUD doc change. |
| MekBay | Diverges: hud-v3 came from MekBay's mock, so the native HUD would stop matching it. |
| Fonts, icons | Same as the hud-v3 option (Roboto + Material Symbols from mm-data). The blue family uses system fonts today, so this is new work in Swing either way. |
| Light mode | Same work in both options; the blue family has one runtime-derived precedent (`FramedCommandButtonStyle`). |
| Launcher | Same either way: standalone (no FlatLaf, no megamek), so it needs generated constants or a tiny shared jar. |

## Side by side

| Question | hud-v3 base | Blue family base |
| --- | --- | --- |
| Where it lives today | libGDX only (megamek GDX branch) | Swing only (MekHQ + launcher) |
| Swing work | Build a component library + FlatLaf theme | Consolidate an existing library + FlatLaf theme |
| libGDX work | Rename tokens, consolidate 19 button styles | Restyle the whole native HUD (~180 literal colors + tokens + shapes + motion) |
| Typography | Has a full Roboto type scale | None; adopts hud-v3's anyway |
| Icons | Material Symbols, ~60 named | Adopts hud-v3's anyway |
| Status colors / colorblind | Coral–amber collapses (ΔE 15 deutan) | Separates well (ΔE 30) |
| Semantic clarity | `ACCENT` is grey, `MINT` is both accent and success | `SELECTED` = `CAUTION` (same orange) |
| Border contrast | Fails (1.7–2.4) | Fails (1.6–2.7) |
| Focus visibility | None | Mostly yes (not `HudButton`) |
| Motion | Instant states | Animated frames, map transitions |
| Light mode | None | One derived precedent |
| High-contrast mode | No | Launcher only |
| Matches MekBay | Yes (ported from its mock) | No |
| Current Swing reach | 0 files | ~180 MekHQ files + launcher |
| Feel over the 3D board | Neutral grey-green, recedes behind terrain | Saturated cyan, more "sci-fi console", competes more with terrain color |

## A hybrid is plausible

The two families overlap more than their colors suggest: both are dark, both use tracked bold caps for chrome, both
use corner-tick frames, both use amber as the "look here" color. Each also has something the other lacks:

- From hud-v3: Roboto type scale, Material Symbols, MekBay alignment, data-view widgets, the libGDX implementation.
- From the blue family: the colorblind-safer status palette, distinct input fills, focus states, light-mode precedent,
  high-contrast support, the wider Swing form controls, and the framed-button motion.

One option is hud-v3's structure (type, icons, shapes, components) with a palette decision made on its own merits,
possibly with the blue family's accent and status colors. That is a palette question for Token v0, not a choice of
codebase.
