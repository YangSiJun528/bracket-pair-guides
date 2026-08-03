# Configure Bracket Pair Guides

All behavior and appearance settings are under **Settings | Editor | Bracket
Pair Guides**. There is no separate Color Scheme page.

## Choose what is shown

| Setting | Default | Effect |
|---|---:|---|
| Enable bracket pair guides | On | Enables or disables all plugin highlighting |
| Color matching bracket tokens by nesting level | On | Colors both symbols of every matched pair |
| Show active pair guide | On | Shows a guide for the innermost pair containing the primary caret |
| Vertical segment | On | Shows the vertical part of a multiline guide |
| Opening and closing segments | On | Shows horizontal arms and single-line guides |
| Line width | 1 px | Sets the guide width from 1 to 4 pixels |
| Opacity | 100% | Sets guide opacity from 10% to 100% |
| Show pair border | Off | Adds a border to the two active symbols |
| Border style | Box when enabled | Selects Box or Rounded box |
| Show pair background | Off | Adds a background to the two active symbols |
| Background opacity | 22% when enabled | Blends the pair color with the editor background |

The plugin does not shade the complete range between the active symbols.
Moving the caret outside every pair removes the active guide and symbol
emphasis; nesting-level token colors remain visible when enabled.
The default active presentation uses only the vertical and horizontal guide
segments. Enable either pair option when the two active symbols need additional
emphasis.

## Set level colors

The **Level Colors** table contains six levels. Deeper levels repeat the same
palette: level 7 uses level 1, level 8 uses level 2, and so on.

By default, one Base color per level supplies the bracket-token foreground,
guide line, pair border, and pair background. The built-in Base colors differ
by level and follow light and dark editor schemes. Select **Reset to current
theme defaults** to discard explicit colors and resume using theme defaults.

Enable **Customize component colors separately** only when Guide, Border, or
Background should differ from Base. A component cell is editable only when its
feature and the advanced-color switch are enabled. Turning the switch off
retains the advanced values for later use; resetting the palette clears them.

## Use the Preview

The editable **Preview** is beside the controls and reflects draft settings
before Apply.

The README screenshots enable the optional pair border and background to show
their appearance; a new installation leaves both options off.

1. Select any example offered by the language matcher capability filter.
2. Edit the example or move the caret into another pair.
3. Switch examples without losing the temporary text and caret position.
4. Select **Reset** to restore the current example.

Preview text is session-only. **Apply** and **OK** persist settings, not preview
content. Open source editors receive draft appearance changes only after Apply
or OK.

## Map familiar settings

| VS Code setting | Bracket Pair Guides setting |
|---|---|
| `editor.bracketPairColorization.enabled` | Color matching bracket tokens by nesting level |
| `editor.guides.bracketPairs: "active"` | Show active pair guide |
| `editor.guides.bracketPairsHorizontal: "active"` | Opening and closing segments |
| `editor.guides.highlightActiveBracketPair` | Show pair border / Show pair background |
| `editorBracketHighlight.foreground1..6` | Base colors |
| `editorBracketPairGuide.activeBackground1..6` | Guide colors in advanced mode |

## Avoid duplicate highlighting

IntelliJ's **Current scope** line can overlap the active guide. **Matched brace**
can overlap near a brace boundary, while indent guides use whitespace columns
and normally coexist with this plugin.

Recommended setup:

1. Leave **Show indent guides** enabled.
2. Leave **Matched brace** enabled if native boundary feedback is useful.
3. Disable **Current scope** when two active lines appear.
4. If another bracket plugin is installed, enable token colors, guides,
   borders, and backgrounds in only one plugin for each overlapping component.

Bracket Pair Guides removes only highlighters it created. It does not clear an
editor's markup model or change another feature's settings.

Language support follows `com.intellij.lang.braceMatcher`, not the IDE product
name. The same plugin build can therefore support a language in Rider,
RustRover, CLion, WebStorm, GoLand, or another JetBrains IDE when that language
plugin registers the extension. Languages with only the legacy file-type
matcher are left unchanged.

Related JetBrains documentation:

- [Customize editor appearance](https://www.jetbrains.com/help/idea/customize-editor.html)
- [Indent guides](https://www.jetbrains.com/help/idea/indentation.html)
- [Brace matching extension](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
