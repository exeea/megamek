# Design tokens v0

Status: v0.1, for review. The decisions behind it are in [gap-analysis.md](gap-analysis.md).

The tokens are the one source of truth for the Swing and libGDX look of the MegaMek suite. They live in
[design-tokens.json](../../megamek/resources/megamek/client/ui/design/design-tokens.json) and are read by
[`DesignTokens`](../../megamek/src/megamek/client/ui/design/DesignTokens.java), which has no AWT or libGDX dependency so
both platforms (and the launcher, later) can share it.

## Seeing them

In a dev build, open the lobby and use **Help > UI library (developer)**. The submenu only shows in the lobby.

- **Swing controls...** opens the Swing gallery: every color role in both modes, the accessibility checks, the text
  styles, spacing and shape, and a **Controls** tab of real Swing controls drawn by the MegaMek look and feel. A
  toolbar toggles light mode and simulates deuteranopia, protanopia and tritanopia over the whole window. While the
  gallery is open the whole app uses MegaMek Dark or Light, and closing it restores the previous theme. This is the
  complete library; Swing controls are added here as they are built.
- **libGDX controls...** opens the native gallery: the Token v0 dark colors beside the hud-v3 colors in use today,
  the hud fonts, all 19 current button styles in rest, pressed and disabled states, and the other toolkit controls. It
  is a subset of the Swing library and grows only when the game needs a control. Only one native window can run at a
  time, so it closes before a board or the board editor opens, and it will not open next to one.

The same checks run as a unit test, along with a test that the Swing themes pick the tokens up:

```
./gradlew :megamek:test --tests megamek.client.ui.design.DesignTokensTest --tests megamek.client.ui.design.swing.TokenLafDefaultsTest
```

## Swing themes

**MegaMek Dark** and **MegaMek Light** are FlatLaf look and feels
([`MegaMekDarkLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekDarkLaf.java),
[`MegaMekLightLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekLightLaf.java)). They are registered next
to the four stock themes, so any suite app can pick them in its settings; the stock themes and the current default are
unchanged for now. They have no properties file: [`TokenLafDefaults`](../../megamek/src/megamek/client/ui/design/swing/TokenLafDefaults.java)
builds the FlatLaf values from the token file when the theme loads, so a token change reaches Swing with no second
copy.

- FlatLaf derives most keys from a few variables (`@background`, `@foreground`, `@accentColor`, `@selectionBackground`
  and so on). These are set from the tokens, and then the keys whose derived value would not match a token.
- Translucent tokens (hover, selection) are composited over their surface, because opaque Swing components must paint
  opaque colors.
- Selection uses `selected.background` and keeps full strength when the control is not focused.
- The button kinds are FlatLaf style classes, see [Buttons](#buttons); the window's default button looks like the
  primary one.
- The default font is Roboto at the `body` size, and FlatLaf's semibold (the `h1` to `h3` headings) is Roboto Medium.
- Keyboard focus follows [Focus](#focus): a 2-wide ring inside the edge, shown only after keyboard use.

## Color roles

Names describe a role, not a hue, so each name has one meaning in both modes. Surfaces also have an `overlay` value
(translucent) for panels drawn over the board; opaque Swing surfaces use the plain value.

| Role | Dark | Light | Use |
| --- | --- | --- | --- |
| `surface.base` | `#0E1314` | `#ECEFEE` | Window background |
| `surface.panel` | `#141A1C` (overlay `#12181AEB`) | `#FFFFFF` | Panels, dialogs, cards |
| `surface.raised` | `#1C2426` (overlay `#1C2426F7`) | `#F4F6F5` | Headers, footers |
| `surface.popover` | `#172022` | `#FFFFFF` | Menus, tooltips |
| `surface.input` | `#1D2426` | `#FFFFFF` | Fields, selects |
| `surface.hover` | `#FFFFFF0F` | `#16201E0D` | Hover wash |
| `surface.inverse` | `#F0F2EE` | `#1C2426` | Primary button, checked toggle |
| `surface.scrim` | `#0D1112A3` | `#16201E66` | Dims the window behind a modal card |
| `text.primary` | `#F1F4F0` | `#16201E` | Body text |
| `text.secondary` | `#CBD3D0` | `#3A4542` | Button labels, secondary values |
| `text.muted` | `#9AA6A3` | `#596562` | Captions, hints |
| `text.disabled` | `#5F6B69` | `#98A29F` | Disabled text (exempt from contrast) |
| `text.onInverse` | `#16201E` | `#F1F4F0` | Text on `surface.inverse` |
| `text.onAccent` | `#0F1A18` | `#FFFFFF` | Text on an accent fill |
| `border.control` | `#6B7774` | `#7A8683` | Edges that identify a control |
| `border.subtle` | `#2A3133` | `#D5DBD9` | Decorative dividers only |
| `border.strong` | `#E8F1EB` | `#2A3533` | Hovered edge, full frame |
| `accent.primary` | `#82E2CE` | `#0A5C4C` | Active state, links, progress |
| `focus.ring` | `#82E2CE` | `#0A5C4C` | Keyboard focus |
| `selected.primary` | `#9982D3` | `#5A49C6` | Selected item marker and edge |
| `selected.background` | `#9982D33D` | `#5A49C624` | Selected row wash |
| `status.danger` | `#FA878C` | `#8C1C12` | Errors, destruction, enemy |
| `status.warning` | `#F2D42D` | `#A24F00` | Caution, heat |
| `status.success` | `#669C00` | `#357A48` | Done, healthy |
| `status.info` | `#82E2CE` | `#0A5C4C` | Neutral information |

Why these values:

- **Accent is mint, and only the accent.** hud-v3 used mint for both "active" and "success". Success is now a
  distinct olive green, and info deliberately shares the accent so there is one fewer hue to tell apart.
- **Selected is violet, not orange.** Orange sat too close to danger and warning: its worst simulated
  distance (CIEDE2000) was 12.1, against 16.5 for violet. Violet is also unused by every status.
- **Statuses separate by lightness, not only hue.** Danger, warning and success differ in lightness as well as hue,
  so they stay apart under every simulated color vision deficiency. In light mode they must be dark enough to read as
  text (4.5:1), which leaves less room, so the worst light-mode distance is about 11 against about 16.5 in dark.
- **Borders that identify a control reach 3:1** (WCAG 1.4.11). hud-v3 field and chip borders measured 1.8 and 2.4.
  `border.subtle` is for decoration only and must never be the only cue.
- **Color is never the only cue.** Status chips pair the color with an icon (error, warning, check, info, radio);
  the galleries show this.

The `checks` block in the JSON lists every rule: text contrast 4.5:1, control contrast 3:1, and minimum distances
between statuses, selection and accent for typical vision and the three simulated deficiencies. All 148 checks pass.

## Typography

One family, Roboto, on both platforms; icons are Material Symbols Rounded. Both ship in `mm-data`.

| Style | Size | Weight | Use |
| --- | ---: | ---: | --- |
| `display` | 23 | 500 | Phase banner, splash titles |
| `headline` | 20 | 500 | Page and dialog headings |
| `title` | 16 | 500 | Panel and section titles |
| `body` | 13 | 400 | Default text |
| `body.strong` | 13 | 500 | Emphasis, names, values |
| `label` | 12.5 | 500 | Buttons, tabs, field labels |
| `small` | 11.5 | 400 | Dense tables, secondary lines |
| `caption` | 10.5 | 400 | Hints, units, timestamps |
| `overline` | 10.5 | 500 | Short eyebrows and tags; the only upper-case style |

Rules:

- **Sentence case everywhere.** Only `overline` is upper case, and only for labels of one or two words. Buttons and
  tabs are no longer all caps.
- **Two weights**, regular (400) and medium (500). Hierarchy comes from size and weight, not case or condensed faces.
  hud-v3's condensed bold styles are retired.
- Sizes are logical pixels at scale 1; both platforms multiply by the user's UI scale.

### Fonts

Java2D can't select instances of the Roboto variable font: asking for medium draws regular. libGDX FreeType reaches
every instance. So `mm-data` also ships `Roboto/Roboto-Medium.ttf`, a static instance (weight 500, width 100) that
[make_static_roboto.py](make_static_roboto.py) generates from the variable font. Its family name is "Roboto Medium",
which is how Swing asks for medium; regular comes from the variable font's default instance. libGDX keeps reading the
variable font. Both platforms therefore draw the same outlines from one source.

## Spacing, shape and motion

| Group | Values |
| --- | --- |
| Space | xxs 2, xs 4, sm 6, md 8, lg 12, xl 16, xxl 24, xxxl 32 |
| Radius | sm 2, md 3 |
| Control height | sm 28, md 36, lg 46 |
| Icon | sm 16, md 20, lg 24 |
| Dialog width | sm 420, md 620, lg 800 |
| Border | 1 |
| Selection | 2 (the selected tab's underline) |
| Focus ring | 2 wide inside the edge; 1 gap around small indicators; `focus.ring` |
| Corner ticks | 8 on controls, 13 on panels; 2 wide |
| Opacity | tint.hover 0.16, tint.pressed 0.28 (a status color washed over a face) |
| Motion | fast 120 ms, medium 200 ms, slow 320 ms; easing cubic-bezier(0.2, 0, 0, 1) |

Regular frames use corner ticks. The framed button (ticks that grow into a full frame on hover) is a special
variant for emphasis moments, not the default button.

## Motion

A game UI moves: controls ease between their states instead of switching, so the interface responds to the pointer.
Every transition uses the `motion` tokens and one easing curve; nothing bounces or overshoots.

| What | How |
| --- | --- |
| Hover | Comes in `fast` (120) and lingers on the way out, `medium` (200), so a sweep across controls leaves a short trail |
| Press | Shows at once; the release fades back over `medium` |
| Buttons, inputs, selects, tabs, segmented controls | Face, text and edge colors ease between states |
| Check box, radio button, switch | Box fill and edge ease `fast`; the check draws along its stroke, the radio dot grows from the center, the switch thumb slides |
| Special button | The corner ticks grow along the edges into the full frame, `slow` (320) in and `medium` out |
| Popups, menus, tooltips | Fade in `fast`; they close at once |
| Collapsible sections | The chevron turns a quarter and the content slides open or closed over `medium` |
| List, tree and table rows | The hover wash fades in and out per row |
| Scrollbars | The rail eases in and the thumb brightens with hover |
| Sliders | The thumb's colors ease and it grows a pixel each side under the pointer |
| Appearing | Nothing animates when it first shows; only changes move |

- **Reduce motion** turns every transition into an instant change, including FlatLaf's own animations and smooth
  scrolling. It is a MegaMek client setting (Client Settings, next to the theme), off by default.
- **Popups only fade where they have their own window**, which is every popup on Windows 11 and any popup that
  reaches past its window elsewhere. One that fits inside its window appears at once.
- **The gallery shows it live** on the Spacing and shape tab: one sample of each kind, and Play runs them all once.

**Swing.** [`TokenMotion`](../../megamek/src/megamek/client/ui/design/swing/TokenMotion.java) is the one animator:
a control asks for the value it would paint for its current state and gets the value on its way there, and one shared
timer repaints moving controls until they arrive. Custom controls use the same calls, `TokenMotion.color(...)` and
`TokenMotion.value(...)`, with `TokenMotion.hover(...)` and `TokenMotion.buttonState(...)` for the durations above.
`TokenMotion.setReduced(...)` applies the setting.

**libGDX.** Nothing new yet; hud-v3 keeps its instant states until the developers accept the system.

## Focus

One focus look for every control, so keyboard users always find the same thing.

- **Keyboard only.** The ring shows after a key press and hides again on a mouse press, like the web's
  `:focus-visible`. Editable text always shows it, because the caret alone is easy to lose.
- **Inside the edge.** The ring is `focus.width` (2) wide and drawn inside the control edge, so focus never changes a
  control's size or moves a layout.
- **Focus wins.** The ring replaces the edge, whatever the control's state. Meaning stays in the text, fill and icons:
  a checked box stays checked, and error and warning fields carry a status icon as well as their colored edge.
- **Primary buttons** use a `text.primary` outer line and a `text.onAccent` inner line, so the ring shows on the
  accent face.
- **Small indicators** (check box, radio, slider thumb) get the ring around them, `focus.gap` (1) away, so the
  indicator keeps its look. Their label sits far enough away for the ring.
- **Tabs** get the ring inside the selected tab. **Lists and tables** show it on their scroll pane edge.

**Swing.** [`FocusVisibility`](../../megamek/src/megamek/client/ui/design/swing/FocusVisibility.java) tracks keyboard
use for the MegaMek themes; `FocusVisibility.shows(component)` tells custom painters whether to draw the ring.
[`TokenCheckBoxIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenCheckBoxIcon.java),
[`TokenRadioButtonIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenRadioButtonIcon.java) and
[`TokenTabbedPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTabbedPaneUI.java) draw the rings FlatLaf
does not. The gallery's "Keyboard focus" section shows each control frozen in focus.

**libGDX.** Buttons draw the ring now (see [Buttons](#buttons)); scene2d gives buttons keyboard focus only from the
keyboard, so it is keyboard-only already. The other token controls get the ring as they are built. Keyboard
navigation between libGDX controls comes later.

## Buttons

Seven kinds in two sizes replace the 19 hud-v3 styles and the Swing button families. Both galleries show every kind in
every state.

| Kind | Use | Rest | Hover / pressed | Selected |
| --- | --- | --- | --- | --- |
| Primary | The one main action of a view | `accent.primary` face, `text.onAccent` label | Face leans toward the label color (hover) or `text.primary` (pressed), strong edge | — |
| Secondary | Every other action (the default) | `surface.raised` face, `border.control` edge, `text.secondary` label | `surface.hover` wash (twice for pressed), `border.strong` edge, `text.primary` label | — |
| Quiet | Low-emphasis actions in toolbars and rows | No face, no edge | As secondary, without the edge | — |
| Danger | Destructive actions | As secondary, `status.danger` label and edge | Danger wash at `tint.hover` / `tint.pressed` | — |
| Toggle | An on/off choice | As secondary | As secondary | `selected.background` wash, `selected.primary` edge |
| Icon | A Material Symbols glyph, square; needs a tooltip | As quiet | As quiet | Violet wash and violet glyph |
| Special | Emphasis moments (the HUD utility look) | Raised face, `border.control` corner ticks | Full `border.strong` frame | Violet wash and frame |

- **Sizes.** Regular is `control.md` (36) high with `lg` side padding, for dialogs and forms; compact is `control.sm`
  (28) with `md` padding, for dense tables and toolbars. Icon glyphs are `icon.md` / `icon.sm`.
- **Labels** are sentence case in Roboto Medium. Rest text is `text.secondary` and brightens to `text.primary` under
  the pointer, so the hover reads on every kind.
- **Disabled** is the same for every kind: raised face, `border.subtle` edge, `text.disabled` label.
- **Focus** follows [Focus](#focus): a 1-wide `focus.ring` border plus a 1-wide inner line, inside the edge. On
  primary the border is `text.primary` and the inner line `text.onAccent`, so it shows on the accent face.
- **Selection** is always violet with an edge or glyph change, never a lighter face alone, so it does not depend on
  telling two greys apart.
- The primary label keeps at least 4.5:1 against its hover and pressed faces in both modes (tested).

### Using them

**Swing.** [`TokenButtons`](../../megamek/src/megamek/client/ui/design/swing/TokenButtons.java) builds them:
`TokenButtons.button(Kind.DANGER, text, Size.COMPACT)`, `primary(text)`, `toggle(text, size)`,
`icon(codePoint, tooltip, toggle, size)`, `special(text, size)`. The look comes from FlatLaf style classes in the
MegaMek themes (`primary`, `danger`, `quiet`, `icon`, plus `regular` or `compact`), so under another theme they are
plain Swing buttons. `TokenButtons.style(button, kind, size)` restyles an existing button while migrating a view; it
sets the look and padding but keeps the button's natural height, because FlatLaf only applies a minimum height to
buttons with the default margin. Buttons from the factories grow to the size's height themselves.
[`SpecialButton`](../../megamek/src/megamek/client/ui/design/swing/SpecialButton.java) paints itself from the tokens.

**libGDX.** [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers the `TextButtonStyle`s
`button-<kind>` and `button-<kind>-compact` (`button-primary`, `button-secondary`, `button-quiet`, `button-danger`,
`button-toggle`, `button-icon`, `button-special`), built from the tokens. `UiKit.button(style, icon, text, sub)` makes one.
libGDX gives hover priority over focus, so [`UiButton`](../../megamek/src/megamek/client/ui/gdx/UiButton.java) draws
the focus ring as an overlay when it has keyboard focus; `focused(true)` and `held(true)` freeze those states for the
gallery. The libGDX label is 12.5 px (`hud-medium`) and the Swing one 13 px; aligning them is part of the type pass.

### Migrating hud-v3 styles

| hud-v3 style | New kind |
| --- | --- |
| `hud`, `hud-plain` | secondary |
| `hud-main` | primary |
| `hud-danger` | danger |
| `hud-delete-icon`, `hud-icon` | icon |
| `hud-mini`, `hud-utility-small` | secondary, compact |
| `hud-utility`, `hud-utility-narrow`, `hud-utility-row` | special (the icon-above-label layout stays a HUD composition) |
| `hud-tab`, `hud-tab-caps` | Not buttons: [tabs](#tabs) |
| `hud-seg`, `hud-pill` | Not buttons: the [segmented control](#segmented-control) |
| `hud-row`, `hud-menu-row` | Not buttons: list row and menu item come later |
| `hud-select`, `hud-select-inline` | Not buttons: the [select](#select) |

The hud-v3 styles stay until their views move; the libGDX gallery lists them under "hud-v3 button styles (to
migrate)".

## Inputs

Text, password, search and number inputs, the text area and the form field. Both galleries show every kind in every
state.

| State | Look |
| --- | --- |
| Rest | `surface.input` fill, `border.control` edge, `text.primary` text, `text.muted` placeholder |
| Hover | `border.strong` edge |
| Focus | The focus ring in place of the edge (see [Focus](#focus)); editable text always shows it |
| Error / warning | `status.danger` / `status.warning` edge and a trailing status icon; the form field adds the words |
| Read-only | No fill, `border.subtle` edge, `text.primary` text |
| Disabled | `text.disabled` text and a muted edge |

- **Sizes** match buttons, so a field and a button side by side line up: regular `control.md` (36), compact
  `control.sm` (28). Text sits `md` (8) inside the edge.
- **Precedence**: focus wins over a status, and a status over hover. Under the focus ring the status icon still shows.
- **Form field**: the label above (`text.secondary`), then the input, then a helper (`text.muted`) or a status
  message in the status color with its icon. The message line is only there while it has text. Dense option grids
  may keep labels on the left.

**Swing.** The MegaMek themes give every `JTextField`, `JPasswordField`, `JFormattedTextField`, `JSpinner` and
`JComboBox` the compact height and the hover and read-only edges, so existing views get them without changes; text
areas get them through their scroll pane. [`TokenFields`](../../megamek/src/megamek/client/ui/design/swing/TokenFields.java)
builds regular or compact inputs: `text(size)`, `password(size)`, `search(placeholder, size)` (search glyph, clear
button, accessible name), `number(model, size)` and `area(textArea)`; `status(input, Status.ERROR)` sets a status.
[`TokenFormField`](../../megamek/src/megamek/client/ui/design/swing/TokenFormField.java) is the form field
(`setHelper`, `setError`, `setWarning`); its label is the input's `labelFor` and its message the input's accessible
description. Hover comes from one AWT listener (`FieldEdge`) and custom UI delegates (`FieldUIs`, `FieldBorders`),
because FlatLaf has no hover edge or minimum height for inputs. A read-only spinner is not offered, because its arrows
still step.

**libGDX.** [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers the `TokenFieldStyle`s
`input<variant>` and `input<variant>-compact` (`UiTheme.INPUT_VARIANTS`: plain or search, each with error, warning or
read-only). [`UiTextField`](../../megamek/src/megamek/client/ui/gdx/UiTextField.java) adds the hover scene2d lacks,
the status icon, `status(Status)` and `readOnly(true)`; `UiTextField.Area` is the text area. `UiKit` builds them:
`input(text, compact)`, `password(compact)`, `tokenSearch(placeholder, compact)`, `area(text, rows)` and
`formField(label, input)` ([`UiFormField`](../../megamek/src/megamek/client/ui/gdx/UiFormField.java)). It is the
in-game subset: there is no number spinner yet (`UiNumber`'s scrub stays the HUD's number control), and read-only text
can not be selected, because scene2d has no read-only field. The `hud` and `hud-search` field styles stay until their
views move.

## Choices

Check box, radio button and switch, with the label on the right. A click on the indicator or its label flips it. Both
galleries show every kind in every state, and a live row to click.

| State | Look |
| --- | --- |
| Off | `surface.input` fill, `border.control` edge |
| On | `accent.primary` fill and edge with a `text.onAccent` mark: a check, a dot, or the switch's thumb on the right |
| Mixed (check box) | As on, with a dash in place of the check |
| Hover / pressed | `border.strong` edge; off adds the `surface.hover` wash, on leans the accent as a primary button does |
| Focus | The ring around the indicator, `focus.gap` (1) away (see [Focus](#focus)) |
| Disabled | `surface.base` fill, `border.subtle` edge, `text.disabled` mark and label |

- **Size.** The box and circle are `icon.sm` (16) in every row density; the switch's track is 16 high and 28 long,
  with a 12 thumb. The label sits `xs` (4) beyond the ring's room.
- **Which one.** A check box is for a choice confirmed with the rest of a form; a switch applies at once, like a
  setting; radio buttons pick one of a few, and a group always keeps one selected.
- **Not by color alone.** On always adds a mark or moves the thumb, so the state reads without telling colors apart.
- **Mixed** means some of the items a check box stands for are checked. It reads as unchecked, so a click checks
  everything; nothing clicks back into mixed.

**Swing.** The MegaMek themes give every `JCheckBox` and `JRadioButton` the token look through
[`TokenCheckBoxIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenCheckBoxIcon.java) and
[`TokenRadioButtonIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenRadioButtonIcon.java), so existing views
get it without changes. [`TokenChoices`](../../megamek/src/megamek/client/ui/design/swing/TokenChoices.java) builds
them: `check(text, checked)`, `radio(text, selected)`, `group(radios...)` and `toggleSwitch(text, on)` (a `JCheckBox`
with [`TokenSwitchIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenSwitchIcon.java), announced as a toggle
button); `mixed(box, true)` shows the dash through FlatLaf's indeterminate state. Swing's accessibility API has no
mixed state, so a screen reader hears a mixed box as unchecked.

**libGDX.** [`UiChoice`](../../megamek/src/megamek/client/ui/gdx/UiChoice.java) draws the `choice-<shape><state>`
drawables [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers (`box`, `radio` and `track`, in
the `UiTheme.CHOICE_STATES`), its mark and the focus ring. `UiKit` builds them: `check(text, checked)`,
`radio(text, checked)`, `toggleSwitch(text, on)` and `radioGroup(radios...)`. A click, or Space or Enter while it has
keyboard focus, fires a `ChangeEvent`; `setChecked` and `mixed(true)` from code fire none. `hovered`, `held` and
`focused` freeze those states for the gallery. hud-v3's `UiKit.checkbox` stays until its views move.

## Tabs

Labels on the window surface that switch between the pages of one view.

| State | Look |
| --- | --- |
| Rest | `text.secondary` label; a 1-wide `border.subtle` hairline runs under the whole strip |
| Hover | `text.primary` label (libGDX adds the `surface.hover` wash) |
| Selected | `text.primary` label and a `selection.width` (2) `selected.primary` underline, with or without focus |
| Focus | The ring inside the selected tab (see [Focus](#focus)) |
| Disabled | `text.disabled` labels and a `border.subtle` underline |

- **Sizes.** Compact (`control.sm`, 28 high, `md` side padding) is the default for every tab pane, since most sit in
  panels; regular (`control.md`, 36, `lg` padding) is for a window's top-level pages.
- **Labels** are sentence case in Roboto Medium, like buttons; hud-v3's all-caps tabs go.
- **Not by color alone.** The underline marks the selected tab as well as the brighter label.

## Segmented control

Two to five short, mutually exclusive options in one raised frame, for switching a view or a mode in place (map, list
or cards). Use tabs to switch between pages, and radio buttons for a choice in a form.

| State | Look |
| --- | --- |
| Rest | `surface.raised` frame with a `border.control` edge; `border.subtle` dividers; `text.secondary` labels |
| Hover / pressed | The `surface.hover` wash (twice for pressed) on the section, `text.primary` label |
| Selected | `selected.background` wash and `selected.primary` edge on the section, as a selected toggle; the dividers beside it give way |
| Focus | The ring on the selected section, over its edge |
| Disabled | `border.subtle` edges, `text.disabled` labels, the selected wash at half strength |

- **Sizes** match buttons: regular `control.md` (36) with `lg` padding, compact `control.sm` (28) with `md`.
- **Width.** The sections share the width equally, the widest label setting it.
- **Keys.** Tab reaches the control as one stop; Left and Right move the selection, which applies at once.

**Swing.** The MegaMek themes give every `JTabbedPane` the token tabs, compact, so existing views get them without
changes; [`TokenTabs`](../../megamek/src/megamek/client/ui/design/swing/TokenTabs.java) makes a pane of either size
(`tabs(size)`, `size(tabs, size)`). FlatLaf has no pressed tab color, so a pressed tab looks hovered.
[`TokenSegmented`](../../megamek/src/megamek/client/ui/design/swing/TokenSegmented.java) is the segmented control:
`new TokenSegmented(size, labels...)`, `getSelectedIndex()`, `setSelectedIndex(i)` (fires nothing) and
`addActionListener` (user changes only, with the label as the command). Its sections are toggle buttons in a button
group, announced as radio buttons.

**libGDX.** [`UiTabs`](../../megamek/src/megamek/client/ui/gdx/UiTabs.java) and
[`UiSegmented`](../../megamek/src/megamek/client/ui/gdx/UiSegmented.java) share
[`UiOptionBar`](../../megamek/src/megamek/client/ui/gdx/UiOptionBar.java), which keeps the selection and draws the
faces from the `tab-over` and `seg-*` drawables and the `OptionStyle` colors
[`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java) registers. `UiKit` builds them:
`tokenTabs(compact, labels...)` and `tokenSegmented(compact, labels...)`. A click, or Left and Right while the bar has
keyboard focus, fires a `ChangeEvent` from the bar; `select(i)` from code fires none. `option(i).hovered`, `held` and
`focused` freeze those states for the gallery. A view gives the bar keyboard focus itself, since libGDX has no Tab
traversal yet. hud-v3's `UiKit.segmented` and the `hud-tab`, `hud-seg` and `hud-pill` styles stay until their views
move.

## Select

Picks one value from a list that opens below the face. Use a segmented control or radio buttons when two to five
options should stay visible.

| Part / state | Look |
| --- | --- |
| Face | The input box (see [Inputs](#inputs)): the value in `text.primary`, a `text.secondary` chevron, a `text.muted` placeholder while nothing is chosen |
| Inline face | No box: `text.secondary` value that turns `text.primary` under the pointer, open or focused, over a dotted (2 on, 2 off) `border.control` underline, `border.strong` under the pointer |
| Hover / open | `border.strong` edge |
| Focus | The ring in place of the edge; the inline face gets the ring around it instead of the underline |
| Disabled | As a disabled input; the inline underline turns `border.subtle` |
| Popup list | `surface.popover` frame with a `border.control` edge, padded `xs` (4) top and bottom so the rows' highlight sits `xs` from the edge on every side, `xs` (4) below the face (above it when there is no room), at least as wide as the face; scrolls after 10 rows |
| Rows | `control.sm` (28) high; a `surface.hover` wash, inset `xs`, on the row under the pointer or the keyboard; a `selected.primary` check before the current value, in a leading `icon.sm` slot |

- **Sizes** match inputs: regular `control.md` (36), compact `control.sm` (28). The face is as wide as its widest item
  or placeholder, so it does not jump when the value changes. The inline face is `control.sm` high.
- **Inline** is for a value inside a header or running text, where a box would be too heavy.
- **Violet means selected.** The check, not the wash, marks the current value, so the wash stays neutral.
- **Keys.** Enter, Space or Down opens the list on the current value; Up and Down move, Home and End jump, Enter
  chooses and Escape closes without a choice. The focus comes back to the face.

**Swing.** The MegaMek themes give every `JComboBox` the face, the popup list (check, wash, edge, gap and 10 rows)
through the `ComboBox` delegate in `FieldUIs`, so existing views get them without changes. The popup edge is the
themes' `PopupMenu.borderColor`, so menus get the `border.control` edge too.
[`TokenSelects`](../../megamek/src/megamek/client/ui/design/swing/TokenSelects.java) builds them: `select(size,
items...)`, `inline(items...)` (or `inline(combo)`) and `placeholder(combo, text)`, shown while the selected index is
-1. Swing's list has no disabled rows or separators.

**libGDX.** [`UiSelect`](../../megamek/src/megamek/client/ui/gdx/UiSelect.java) is the face and
[`UiListPopup`](../../megamek/src/megamek/client/ui/gdx/UiListPopup.java) the list, drawn from the input boxes, the
`sel-*` drawables and the `SelectStyle` colors [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java)
registers. `UiKit` builds them: `tokenSelect(compact, items...)` and `tokenSelectInline(items...)`. The list sits on
the stage's root while open and closes on a press outside it. Choosing another item fires a `ChangeEvent` from the
select; `select(i)` from code fires none, and `placeholder(text)` shows the placeholder until a choice. `hovered`,
`focused` and `opened` freeze those states for the gallery. hud-v3's `UiKit.select`, its `hud-select` styles and the
`UiPopover` / `UiMenuList` drop-downs stay until their views move.

## Lists and trees

Show many items to pick from or browse. A select is better when only the chosen value needs to stay visible; a table
when items have several comparable columns.

| Part / state | Look |
| --- | --- |
| Frame | The scroll pane: `surface.input` inside a `border.control` edge; it carries the keyboard focus ring |
| Rows | At least `control.sm` (28) high, the shape inset `xs` (4) with a `radius.sm` corner, like the popup list's rows |
| Hover | A `surface.hover` wash on the row under the pointer |
| Selected | The `selected.background` wash, at full strength whether or not the list has focus, and a `selected.primary` marker, `selection.width` (2) by `icon.sm` (16), at the row shape's leading edge |
| Text | `text.primary`, starting `sm` (6) past the marker; `text.disabled` when the list is disabled, which drops the hover and the marker |
| Two-line row | At least `control.lg` (46) high: the title over a `small` detail line in `text.secondary` |
| Empty | A `text.muted` message centered in the first row's place |
| Tree | The list's rows with `text.secondary` chevrons and no connecting lines; each level indents 16, and the leading edge leaves room for the marker |

- **Violet means selected**, and the marker keeps a selection visible without relying on its color, in multiple
  selections too. Hover stays neutral.
- **Keys** are Swing's: Up and Down move, Home and End jump, Shift and Ctrl extend a multiple selection; Left and Right
  collapse and expand tree nodes.

**Swing.** The MegaMek themes draw every `JList` and `JTree` this way through
[`TokenListUI`](../../megamek/src/megamek/client/ui/design/swing/TokenListUI.java) and
[`TokenTreeUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTreeUI.java), so existing views get them without
changes; a combo box's popup list keeps the select's rows. Lists with their own cell renderers keep their rows'
content and get the hover and the marker, but only the stock renderer gets the rounded selection.
[`TokenLists`](../../megamek/src/megamek/client/ui/design/swing/TokenLists.java) adds `emptyText(list, text)`,
`twoLine(list, title, detail)` and `scroll(view)`.

**libGDX.** Nothing new yet. hud-v3's `UiList` and `UiTree` stay until the developers accept the system.

## Menus

Menu bars, their menus and context menus. They share the select's popup, so every popup in the suite looks the same.

| Part / state | Look |
| --- | --- |
| Popup | The select's popup: `surface.popover` with a `border.control` edge and a `radius.md` corner, padded `xs` (4) top and bottom |
| Rows | `control.sm` (28) high, the highlight inset `xs` (4) with a `radius.sm` corner; text starts `xs` + `md` (12) in, after a check column when any row has one |
| Hover / keyboard | A `surface.hover` wash on the row, `text.primary` label; the open menu in a menu bar gets the same wash |
| Check and radio | A `selected.primary` check or dot in the check column |
| Shortcut | `text.muted`, `text.secondary` on the hovered row, right-aligned |
| Submenu | A `text.secondary` chevron at the end of the row |
| Separator | A `border.subtle` line with `xs` (4) above and below |
| Disabled | `text.disabled` label, shortcut, check and chevron |

- **Hover stays neutral**, as in the select's popup and lists. Violet only marks a state that is on.
- **Keys** are Swing's: arrows move and open submenus, Enter chooses, Escape closes, and Alt or F10 reaches the menu
  bar.

**Swing.** The MegaMek themes style every `JMenuBar`, `JMenu`, `JMenuItem`, `JCheckBoxMenuItem`,
`JRadioButtonMenuItem` and `JPopupMenu`, so existing menus get the look without changes.

**libGDX.** Nothing new yet; the in-game UI has no menus that need it.

## Tooltips

Tooltips are small info cards, not bare OS labels: they share the popup's surface, edge and corner, and they can
carry a shortcut and a title.

| Part | Look |
| --- | --- |
| Card | `surface.popover` with a `border.control` edge and a `radius.md` corner, padded `sm` (6) top and bottom and `md` (8) at the sides |
| Plain text | `small`, `text.primary`; wraps at 280 (about 45 characters), at spaces and line breaks |
| Info card | A `body.strong` title in `text.primary` over `small` detail in `text.secondary`, `xxs` (2) apart |
| Shortcut | A key chip at the end of the first line: `caption` in `text.secondary` inside a `border.control` edge with a `radius.sm` corner |
| HTML | Keeps its own layout inside the same card |

- **One line of plain text is about `control.sm` (28) tall**, the height of a compact control.
- **Delays stay user settings**: MegaMek's tooltip delay and dismiss preferences still apply. Tooltips fade in `fast`
  (see [Motion](#motion)).

**Swing.** The MegaMek themes draw every tooltip this way. `TokenTooltips.withKey(component, text, key)` adds a
shortcut chip; `TokenTooltips.card(component, title, detail, key)` makes an info card. Both set the plain tooltip text
too, so screen readers and other look and feels still get it.

**libGDX.** Nothing new yet; hud-v3's tooltip stays until the developers accept the system.

## Scrollbars

Scroll bars stay quiet until they're needed: no track or arrow buttons of their own, so a bar sits on the content it
scrolls instead of drawing a stripe beside it.

| Part / state | Look |
| --- | --- |
| Bar | 10 wide (`sm` + 2 × `xxs`), the color of the scrolled content; the corner between two bars matches it |
| Thumb | An `sm` (6) wide pill, `xxs` (2) from the bar's sides, at least `control.sm` (28) long; `border.control` |
| Hover | The pointer anywhere on the bar shows the rail, a `surface.hover` pill over the content, and turns the thumb `text.muted` |
| Dragging | The thumb turns `text.secondary` |
| Disabled | No thumb and no rail |

- **The thumb stays visible on every surface**: `border.control` keeps at least 3:1 against the base, panel, input and
  popover surfaces in both modes.
- **Wheel and keys** are Swing's. Smooth scrolling is on unless reduce motion is set (see [Motion](#motion)).

**Swing.** The MegaMek themes draw every `JScrollBar` and `JScrollPane` this way through
[`TokenScrollBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollBarUI.java) and
[`TokenScrollPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollPaneUI.java), so existing views get
them without changes. A bar takes the color of an opaque view, otherwise the viewport's; a transparent scroll pane
shows what's behind it.

**libGDX.** Nothing new yet; hud-v3's 4-wide list knob stays until the developers accept the system.

## Sliders

A number picked from a range by dragging the thumb, clicking the track (the thumb jumps there) or the arrow keys.

| Part / state | Look |
| --- | --- |
| Track | An `xs` (4) high pill: `accent.primary` up to the thumb, `border.control` past it |
| Thumb | An `icon.sm` (16) `accent.primary` disc, with or without ticks and labels |
| Ticks | `tick.width` (2) pills under the track in `border.control`: major ones `sm` (6) long, minor ones half that |
| Labels | `small` type in `text.muted` under the ticks |
| Hover / dragging | The pointer anywhere on the slider gives the thumb a `border.strong` edge and leans its face as on a checked box: toward the on-accent color on hover, toward the text color while dragging |
| Focus | The ring around the thumb, `focus.gap` (1) away (see [Focus](#focus)) |
| Disabled | A `border.subtle` track and ticks with no fill, and a `text.disabled` thumb |

- **One look.** Ticks and labels don't change the thumb or drop the fill, so every slider reads the same way.
- **Readout.** When the exact value matters (opacity, scale, a count), show it beside the track in `body` type. It
  keeps the width of the longest value so the track doesn't shift as it changes.
- **Not by color alone.** The thumb's position carries the value; the fill only repeats it.

**Swing.** The MegaMek themes draw every `JSlider` this way through
[`TokenSliderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSliderUI.java), so existing views get it
without changes, including their ticks and standard labels; a custom label table keeps the labels it was given.
[`TokenSliders`](../../megamek/src/megamek/client/ui/design/swing/TokenSliders.java)`.withReadout(slider, format)` adds
the readout and lines it up with the track on the slider's baseline.

**libGDX.** Nothing new yet; the tuning panel's slider style stays until the developers accept the system.

## Tables

Items with several comparable columns, such as units with their tonnage, movement and armor. A list is better when
each item is one line of text.

| Part / state | Look |
| --- | --- |
| Frame | The scroll pane, as for lists |
| Rows | The list's rows stretched across every column: `control.sm` (28) high, inset `xs` (4) with a `radius.sm` corner. No grid lines |
| Zebra | Every other row gets `surface.hover` at half strength, so the eye can follow a wide row |
| Hover | A `surface.hover` wash over the row under the pointer, painted on top so it also shows on stripes and on custom cells |
| Selected | The list's selection: the `selected.background` wash and the `selected.primary` marker at the row's leading edge |
| Cells | `sm` (6) apart; the first column's text starts past the marker and the last one ends `sm` (6) inside the row shape. Numbers align right |
| Header | `overline` labels (upper case) in `text.secondary` on `surface.input`, over a `border.control` line. Each label aligns like its column |
| Header hover / sorted | `text.primary`; the sorted column adds a `text.primary` chevron `sm` (6) after its label. Dragging a column gives it a `surface.hover` fill |
| Empty | A `text.muted` message centered in the first row's place |
| Disabled | `text.disabled` text and headers, with no hover or marker |

- **Violet means selected**, as in lists. A selected cell has no rectangle of its own: the row is the cursor.
- **Sorting.** Click a header to sort by it and again to reverse; the chevron shows the direction.
- **Keys** are Swing's: Up and Down move, Page Up and Page Down scroll, Shift and Ctrl extend a multiple selection.

**Swing.** The MegaMek themes draw every `JTable` and its header this way through
[`TokenTableUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableUI.java),
[`TokenTableHeaderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableHeaderUI.java) and
[`TokenTableCellBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTableCellBorder.java), so existing views
get them without changes. Tables with their own renderers keep their cells' content and get the hover and the marker;
headers take their alignment from the column's renderer.
[`TokenTables`](../../megamek/src/megamek/client/ui/design/swing/TokenTables.java)`.emptyText(table, text)` adds the
empty message.

**libGDX.** Nothing new yet; hud-v3 has no table.

## Progress bars

How far along a known amount of work is, or that unknown work is still going on.

| Part / state | Look |
| --- | --- |
| Track | An `xs` (4) high `border.control` pill, as on sliders |
| Fill | `accent.primary` from the leading edge, in the same pill shape |
| Text | Above the track, `xs` (4) away, in `small` type and `text.secondary`, leading-aligned; never inside the bar |
| Indeterminate | An `accent.primary` segment, 30% of the track, enters at the start, crosses and leaves at the end, once every 1.6 s |
| Disabled | A `border.subtle` track with a `text.disabled` fill and text |

- **Text outside the bar.** Status messages ("Round 3: waiting for the other players") are often longer than the bar
  is wide, and text over a thin fill can't keep its contrast.
- **Determinate when possible.** Use the indeterminate bar only when the amount of work is truly unknown.

**Swing.** The MegaMek themes draw every horizontal `JProgressBar` this way through
[`TokenProgressBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenProgressBarUI.java), so existing views get
it without changes; the bar grows by the text's height when it shows text. Vertical bars keep FlatLaf's drawing in the
same colors.

**libGDX.** Nothing new yet.

## Sections

A heading that names the group of fields or content below it, inside a page or a dialog.

| Part / state | Look |
| --- | --- |
| Header | A `control.sm` (28) high row: an `overline` title (upper case) in `text.secondary`, then a 1px `border.subtle` line from `sm` (6) past it to the far edge, level with the middle of the capitals |
| Titled panel | The header across the top of the panel; the content starts below it, flush with the title. No box |
| Collapsible | The header with the tree's chevron `sm` (6) in from its leading edge; the content below opens and closes |
| Collapsible hover | A `surface.hover` wash over the header row with a `radius.sm` corner; the title turns `text.primary` |
| Collapsible focus | The focus ring inside the header row (see [Focus](#focus)) |
| Disabled | A `text.disabled` title; a collapsible section doesn't open or close |
| Separator | The line alone, `border.subtle`, horizontal or vertical |

- **Short titles.** Overline is for labels of one or two words; longer explanations go in the content.
- **Keys.** A collapsible section's header takes the keyboard focus; Enter or Space opens or closes it.
- **No boxes.** Sections are told apart by their headers and space, not by frames around them.

**Swing.** [`TokenSections`](../../megamek/src/megamek/client/ui/design/swing/TokenSections.java) makes the three
forms: `header(title)` is a label to place above a group, `titled(title)` is a
[`TokenTitledBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTitledBorder.java) for a panel, and
`collapsible(title, content, expanded)` is a
[`TokenCollapsibleSection`](../../megamek/src/megamek/client/ui/design/swing/TokenCollapsibleSection.java) that fires
`expanded` when it opens or closes. Screen readers get the title as written, not in capitals, and the collapsible
header as a toggle that is expanded or collapsed. `JSeparator` already has the separator's look.

Existing borders from `BorderFactory.createTitledBorder` change with the themes but keep the JDK's layout: their title
gets `label` type in `text.secondary` and a `border.subtle` line after it instead of a box, but it stays in mixed case
and sits about 3px further in than the content. Moving a view to `TokenSections.titled` gives it the true look. The
settings dialog's `CollapsibleSectionPanel` is the developers' to move to `TokenSections.collapsible`.

**libGDX.** Nothing new yet; hud-v3 has no collapsible section.

## Split panes

Two areas side by side or stacked, with a divider the user drags to share the space between them.

| Part / state | Look |
| --- | --- |
| Divider | A 1px `border.subtle` line in the middle of an `md` (8) wide grab area |
| Hover / dragging | A `selection.width` (2) `accent.primary` line |
| One-touch arrows | `text.muted`, `text.primary` on hover and `accent.primary` pressed |

**Swing.** The MegaMek themes draw every `JSplitPane` divider this way through
[`TokenSplitPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSplitPaneUI.java), so existing views get it
without changes.

**libGDX.** Nothing new yet.

## Dialogs

Two tiers. Messages, confirmations and short forms open as a **modal card** over the window; tools and longer forms
that the user moves around or keeps open beside the game open as a **window** with the theme's title bar.

| Part / state | Look |
| --- | --- |
| Scrim | `surface.scrim` over the whole window; it takes every click, and the window's keys stand down |
| Card | `surface.panel` with a 1px `border.subtle` edge and `border.strong` corner ticks, `tick.panel` (13) long and `tick.width` (2) wide; centred in the window |
| Width | `dialog.sm` (420) for messages and confirmations, `dialog.md` (620) for forms, `dialog.lg` (800) for the largest cards |
| Padding | `xl` (16) above the header, `xxl` (24) elsewhere; a window has `xxl` all round |
| Header | An optional status icon at `icon.lg` (24), the title in `headline`, then help and close icon buttons in the compact size |
| Status | Info `status.info`, success `status.success`, warning `status.warning`, error `status.danger`, question `accent.primary`; always with the icon, never the color alone |
| Body | `body` text in `text.primary` wrapping at the card's width, or any content such as form fields; an optional extra row (for example "Don't ask again") `xl` (16) below it |
| Footer | `xxl` (24) below the content: the buttons centred, `md` (8) apart, all as wide as the widest and at least 88; the main action first, as in Windows (last in right-to-left) |
| Motion | The card and its scrim fade in over `fast` (120 ms); they close at once. Reduce motion skips the fade |

- **One main action.** A dialog has at most one primary or danger button, and it goes first; Cancel and other choices
  follow it. This is the Windows order ([Deploy] [Cancel]), which most of the suite's users already know.
- **Keys.** Enter presses the focused button or else the main one; Escape and the close button cancel. Tab stays in the
  card.
- **Destructive actions.** With a danger button the focus starts on Cancel, and Enter only presses the danger button
  when the user has moved the focus to it.
- **First focus.** Otherwise the first input of the body takes the focus, then the main action.
- **No stacking by habit.** A card can open over another card, but a flow that needs that is a sign it wants a window.

**Swing.** Build the content as a
[`TokenDialogPane`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogPane.java): `status`, `width`,
`message` or `body`, `extra` and `help`, then the buttons with `cancel`, `secondary` and one of `primary` or `danger`.
[`TokenDialogs`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogs.java)`.show(owner, pane)` opens it as a
card (a [`TokenModalCard`](../../megamek/src/megamek/client/ui/design/swing/TokenModalCard.java) in the window's layered
pane) and returns the pressed button, or null when cancelled; it waits like a modal dialog. When the window has no
room for the card it opens a window instead, which `TokenDialogs.window(owner, pane)` also does on request. The
shortcuts `message`, `confirm` and `confirmDanger` cover the common cases. While a card is open
`UIUtil.isModalDialogDisplayed()` is true, so MegaMek's hotkeys stand down as they do for a modal dialog.

Existing `JOptionPane` dialogs keep their windows but take the footer's layout through the MegaMek themes: the token
status icons, `xxl` padding, buttons centred, as wide as each other, at least 88 and with the main one first. Their
titles stay in the title bar. Moving them to `TokenDialogs` gives them the card.

**libGDX.** Spec only for now: hud-v3's `UiModal` already draws a scrim and `GpuModalDialog` a 620-wide panel with 88
by 36 buttons. Moving them to the card's frame, centred footer and keys is the developers' work.

## Code

| Class | Role |
| --- | --- |
| [`DesignTokens`](../../megamek/src/megamek/client/ui/design/DesignTokens.java) | Loads and exposes the tokens |
| [`ColorMath`](../../megamek/src/megamek/client/ui/design/ColorMath.java) | Contrast, CIEDE2000, compositing, deficiency simulation |
| [`Vision`](../../megamek/src/megamek/client/ui/design/Vision.java) | Typical vision and the three simulated deficiencies |
| [`TokenChecks`](../../megamek/src/megamek/client/ui/design/TokenChecks.java) | Runs the JSON `checks` |
| [`SwingUiLibrary`](../../megamek/src/megamek/client/ui/design/swing/SwingUiLibrary.java) | The Swing gallery |
| [`TokenMotion`](../../megamek/src/megamek/client/ui/design/swing/TokenMotion.java), [`TokenButtonUI`](../../megamek/src/megamek/client/ui/design/swing/TokenButtonUI.java), [`TokenPopupFactory`](../../megamek/src/megamek/client/ui/design/swing/TokenPopupFactory.java), [`RowWash`](../../megamek/src/megamek/client/ui/design/swing/RowWash.java) | Swing motion: the animator, eased buttons, popup fade-in and row hover |
| [`TokenLafDefaults`](../../megamek/src/megamek/client/ui/design/swing/TokenLafDefaults.java) | Turns the tokens into FlatLaf values |
| [`MegaMekDarkLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekDarkLaf.java), [`MegaMekLightLaf`](../../megamek/src/megamek/client/ui/design/swing/MegaMekLightLaf.java) | The Swing themes |
| [`TokenButtons`](../../megamek/src/megamek/client/ui/design/swing/TokenButtons.java), [`SpecialButton`](../../megamek/src/megamek/client/ui/design/swing/SpecialButton.java) | The Swing button set |
| [`FocusVisibility`](../../megamek/src/megamek/client/ui/design/swing/FocusVisibility.java), [`TokenCheckBoxIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenCheckBoxIcon.java), [`TokenRadioButtonIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenRadioButtonIcon.java), [`TokenTabbedPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTabbedPaneUI.java) | Swing keyboard focus |
| [`UiTheme`](../../megamek/src/megamek/client/ui/gdx/UiTheme.java), [`UiButton`](../../megamek/src/megamek/client/ui/gdx/UiButton.java) | The libGDX button styles and focus ring |
| [`TokenFields`](../../megamek/src/megamek/client/ui/design/swing/TokenFields.java), [`TokenFormField`](../../megamek/src/megamek/client/ui/design/swing/TokenFormField.java) | The Swing inputs and form field |
| [`UiTextField`](../../megamek/src/megamek/client/ui/gdx/UiTextField.java), [`UiFormField`](../../megamek/src/megamek/client/ui/gdx/UiFormField.java) | The libGDX inputs and form field |
| [`TokenChoices`](../../megamek/src/megamek/client/ui/design/swing/TokenChoices.java), [`TokenSwitchIcon`](../../megamek/src/megamek/client/ui/design/swing/TokenSwitchIcon.java) | The Swing check box, radio button and switch |
| [`UiChoice`](../../megamek/src/megamek/client/ui/gdx/UiChoice.java) | The libGDX check box, radio button and switch |
| [`TokenTabs`](../../megamek/src/megamek/client/ui/design/swing/TokenTabs.java), [`TokenSegmented`](../../megamek/src/megamek/client/ui/design/swing/TokenSegmented.java) | The Swing tabs and segmented control |
| [`UiOptionBar`](../../megamek/src/megamek/client/ui/gdx/UiOptionBar.java), [`UiTabs`](../../megamek/src/megamek/client/ui/gdx/UiTabs.java), [`UiSegmented`](../../megamek/src/megamek/client/ui/gdx/UiSegmented.java) | The libGDX tabs and segmented control |
| [`TokenSelects`](../../megamek/src/megamek/client/ui/design/swing/TokenSelects.java), [`FieldUIs`](../../megamek/src/megamek/client/ui/design/swing/FieldUIs.java) | The Swing select and its popup list |
| [`UiSelect`](../../megamek/src/megamek/client/ui/gdx/UiSelect.java), [`UiListPopup`](../../megamek/src/megamek/client/ui/gdx/UiListPopup.java) | The libGDX select and its popup list |
| [`TokenLists`](../../megamek/src/megamek/client/ui/design/swing/TokenLists.java), [`TokenListUI`](../../megamek/src/megamek/client/ui/design/swing/TokenListUI.java), [`TokenTreeUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTreeUI.java) | The Swing lists and trees |
| [`TokenTooltips`](../../megamek/src/megamek/client/ui/design/swing/TokenTooltips.java), [`TokenToolTipUI`](../../megamek/src/megamek/client/ui/design/swing/TokenToolTipUI.java) | The Swing tooltips |
| [`TokenScrollBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollBarUI.java), [`TokenScrollPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenScrollPaneUI.java) | The Swing scrollbars |
| [`TokenSliderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSliderUI.java), [`TokenSliders`](../../megamek/src/megamek/client/ui/design/swing/TokenSliders.java) | The Swing sliders and their readout |
| [`TokenTables`](../../megamek/src/megamek/client/ui/design/swing/TokenTables.java), [`TokenTableUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableUI.java), [`TokenTableHeaderUI`](../../megamek/src/megamek/client/ui/design/swing/TokenTableHeaderUI.java), [`TokenTableCellBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTableCellBorder.java) | The Swing tables |
| [`TokenProgressBarUI`](../../megamek/src/megamek/client/ui/design/swing/TokenProgressBarUI.java) | The Swing progress bars |
| [`TokenSections`](../../megamek/src/megamek/client/ui/design/swing/TokenSections.java), [`TokenTitledBorder`](../../megamek/src/megamek/client/ui/design/swing/TokenTitledBorder.java), [`TokenCollapsibleSection`](../../megamek/src/megamek/client/ui/design/swing/TokenCollapsibleSection.java) | The Swing section headers, titled panels and collapsible sections |
| [`TokenSplitPaneUI`](../../megamek/src/megamek/client/ui/design/swing/TokenSplitPaneUI.java) | The Swing split panes |
| [`TokenDialogPane`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogPane.java), [`TokenDialogs`](../../megamek/src/megamek/client/ui/design/swing/TokenDialogs.java), [`TokenModalCard`](../../megamek/src/megamek/client/ui/design/swing/TokenModalCard.java) | The Swing dialogs: content, opening and the modal card |
| [`VisionLayerUI`](../../megamek/src/megamek/client/ui/design/swing/VisionLayerUI.java) | Simulates a deficiency over any Swing component |
| [`GpuUiLibraryWindow`](../../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuUiLibraryWindow.java) | The libGDX gallery |

Outside the galleries, only the two Swing themes and the libGDX button, input, choice, tab, segmented and select
styles read the tokens; lists, trees, menus, tooltips, scrollbars, sliders, tables, progress bars, sections, split
panes and dialogs are Swing-only for now, and so is motion. The Swing themes are selectable but not the default, and no
view uses the new controls yet. Next comes a trial of MegaMek's Swing startup path on them; hud-v3 moves only once the
developers accept the system.
