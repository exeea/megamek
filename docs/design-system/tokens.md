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
- The primary button is the FlatLaf style class `primary`
  (`putClientProperty(FlatClientProperties.STYLE_CLASS, "primary")`), and the window's default button looks the same.
  The other button types will be style classes too.
- The default font is Roboto at the `body` size, and FlatLaf's semibold (the `h1` to `h3` headings) is Roboto Medium.
- Keyboard focus is drawn inside the control edge (a 1-wide inner line plus the focus-colored border, 2 wide in total)
  instead of 2 wide with a 2 offset outside it. A focus ring outside the edge would make every Swing control 4 pixels
  larger and move every layout. On the primary button the inner line uses `text.onAccent`, so it shows against the
  accent fill. The final focus design is the next control to settle.

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
| Focus ring | 2 wide, 2 offset, `focus.ring` |
| Corner ticks | 8 on controls, 13 on panels |
| Motion | fast 120 ms, medium 200 ms, slow 320 ms; easing cubic-bezier(0.2, 0, 0, 1) |

Regular frames use corner ticks. The framed button (ticks that grow into a full frame on hover) is a special
variant for emphasis moments, not the default button.

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
| [`VisionLayerUI`](../../megamek/src/megamek/client/ui/design/swing/VisionLayerUI.java) | Simulates a deficiency over any Swing component |
| [`GpuUiLibraryWindow`](../../megamek/src/megamek/client/ui/clientGUI/boardview/gpu/GpuUiLibraryWindow.java) | The libGDX gallery |

Outside the galleries, only the two Swing themes read the tokens. They are selectable but not the default. Next come
the button types (as FlatLaf style classes and libGDX styles), and a `UiTheme` rename pass so hud-v3 reads the same
roles.
