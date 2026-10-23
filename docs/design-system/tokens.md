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
| Border | 1 |
| Selection | 2 (the selected tab's underline) |
| Focus ring | 2 wide inside the edge; 1 gap around small indicators; `focus.ring` |
| Corner ticks | 8 on controls, 13 on panels; 2 wide |
| Opacity | tint.hover 0.16, tint.pressed 0.28 (a status color washed over a face) |
| Motion | fast 120 ms, medium 200 ms, slow 320 ms; easing cubic-bezier(0.2, 0, 0, 1) |

Regular frames use corner ticks. The framed button (ticks that grow into a full frame on hover) is a special
variant for emphasis moments, not the default button.

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
| Popup list | `surface.popover` frame with a `border.control` edge, `xs` (4) below the face (above it when there is no room), at least as wide as the face; scrolls after 10 rows |
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

## Code

| Class | Role |
| --- | --- |
| [`DesignTokens`](../../megamek/src/megamek/client/ui/design/DesignTokens.java) | Loads and exposes the tokens |
| [`ColorMath`](../../megamek/src/megamek/client/ui/design/ColorMath.java) | Contrast, CIEDE2000, compositing, deficiency simulation |
| [`Vision`](../../megamek/src/megamek/client/ui/design/Vision.java) | Typical vision and the three simulated deficiencies |
| [`TokenChecks`](../../megamek/src/megamek/client/ui/design/TokenChecks.java) | Runs the JSON `checks` |
| [`SwingUiLibrary`](../../megamek/src/megamek/client/ui/design/swing/SwingUiLibrary.java) | The Swing gallery |
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
| [`VisionLayerUI`](../../megamek/src/megamek/client/ui/design/swing/VisionLayerUI.java) | Simulates a deficiency over any Swing component |
| [`GpuUiLibraryWindow`](../../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuUiLibraryWindow.java) | The libGDX gallery |

Outside the galleries, only the two Swing themes and the libGDX button, input, choice, tab, segmented and select
styles read the tokens. The Swing themes are selectable but not the default, and no view uses the new controls yet.
Next come the rest of the controls and dialogs, then a trial of MegaMek's Swing startup path on them; hud-v3 moves
only once the developers accept the system.
