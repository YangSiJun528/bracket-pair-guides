# Configure Bracket Pair Guides

All behavior and appearance settings are under **Settings | Editor | Bracket
Pair Guides**. There is no separate Color Scheme page.

## Choose what is shown

| Setting | Default | Effect |
|---|---:|---|
| Enabled | On | Enables or disables all plugin highlighting |
| Disable IntelliJ matched-brace highlighting | On | Prevents IntelliJ's native endpoint foreground/background from replacing the plugin's pair colors |
| Bracket colorization | On | Colors both symbols of every matched pair |
| Active guide | On | Shows a guide for the innermost pair containing the primary caret |
| Vertical | On | Shows the vertical part of a multiline guide |
| Horizontal | On | Shows opening/closing arms and single-line guides |
| Width (px) | 1 | Sets the guide width from 1 to 4 pixels |
| Opacity | 100% | Sets guide opacity from 10% to 100% |
| Pair border | Off | Adds a border to the two active symbols |
| Pair background | Off | Adds a background to the two active symbols |
| Background opacity | 22% when enabled | Blends the pair color with the editor background |

The plugin does not shade the complete range between the active symbols.
Moving the caret outside every pair removes the active guide and symbol
emphasis; nesting-level token colors remain visible when enabled.
The default active presentation uses only the vertical and horizontal guide
segments. Enable either pair option when the two active symbols need additional
emphasis.

**Disable IntelliJ matched-brace highlighting** is enabled by default because
IntelliJ normally replaces the endpoint foreground and background when the
caret touches a brace boundary. Clear the option to restore the native setting.
That combination is not tested and may not reproduce the intended appearance.

This is an IDE-wide IntelliJ setting. The plugin remembers its previous value
before taking ownership and restores that value when the option or plugin is
disabled, or when the plugin is dynamically unloaded. If the native setting is
explicitly enabled elsewhere while the plugin owns it, the newer native choice
wins and this option is cleared.

## Choose languages

The **Languages** group is at the bottom of the settings page. It lists every
installed language family that provides either a language brace matcher or a
language-backed legacy file-type brace matcher. This includes embedded-only
languages without a standalone file type. Every family is enabled by default.

- Clear a family to exclude it from token colors, active guides, and pair
  emphasis.
- Use **Select All** or **Deselect All** to change every currently installed
  family at once, then adjust individual families as needed.
- Derived languages that inherit the same matcher are grouped together. For
  example, TypeScript and JSX can appear in the JavaScript family tooltip.
- A newly installed supported family starts enabled. A disabled selection is
  retained if its language plugin is temporarily removed.
- **Custom file types** means syntax-table bracket tokens registered through
  the platform `TEXT` matcher. Plain text also uses matcher-defined standard
  bracket tokens; arbitrary raw characters are not scanned.

Applying a language change clears stale decorations in every live editor session
and schedules complete background analysis. Editors and split views do not run
matcher callbacks synchronously; each waits for its background pass to publish a
current snapshot. Reopen the Settings page after installing a language plugin so
the family list can be rediscovered.

Only matcher-defined pairs are affected. Enabling YAML does not turn indentation
blocks into bracket pairs. Languages backed only by the legacy file-type
extension are included when their file type is language-backed. See the
[IDE and language support reference](reference_language_support.md).

## Set level colors

The **Colors** grid contains six levels. Deeper levels repeat the same sequence:
level 7 uses level 1, level 8 uses level 2, and so on. The selectors are the
IntelliJ Platform's standard color controls.

Every level starts with an explicit built-in Base color. These values are the
applied palette, not placeholders that resolve through the active editor theme.
By default, Base supplies the bracket-token foreground, guide line, pair border,
and pair background for that level.

Enable **Component overrides** only when Guide, Border, or Background should
differ from Base. While the switch is off, those three columns remain visible
but read-only and Base is applied to all components. Turning the switch on makes
the saved component colors editable. Turning it off retains them for later use.
**Reset colors** restores the built-in palette in all four columns and turns
component overrides off.

## Map familiar settings

| VS Code setting | Bracket Pair Guides setting |
|---|---|
| `editor.bracketPairColorization.enabled` | Bracket colorization |
| `editor.guides.bracketPairs: "active"` | Active guide |
| `editor.guides.bracketPairsHorizontal: "active"` | Horizontal |
| `editor.guides.highlightActiveBracketPair` | Pair border / Pair background |
| `editorBracketHighlight.foreground1..6` | Base colors |
| `editorBracketPairGuide.activeBackground1..6` | Guide colors with Component overrides enabled |

## Avoid duplicate highlighting

IntelliJ's **Current scope** line can overlap the active guide. The plugin
suppresses **Matched brace** by default because its boundary attributes replace
the configured pair colors. Indent guides use whitespace columns and normally
coexist with this plugin.

Recommended setup:

1. Leave **Show indent guides** enabled.
2. Leave **Disable IntelliJ matched-brace highlighting** enabled for the tested
   appearance. Clear it only when native boundary feedback is preferred.
3. Disable **Current scope** when two active lines appear.
4. If another plugin styles the same editor elements, either accept the
   overlap or disable the overlapping feature in one of the plugins.

### Use with other highlighting plugins

Bracket Pair Guides does not reserve a higher rendering priority than other
plugins. When multiple plugins draw colors, backgrounds, borders, or guides on
the same editor elements, one plugin can partially cover another. The result
depends on the layers and components used by each plugin, so there is no single
ordering that works for every combination.

Use the plugins together when the combined appearance is acceptable. Otherwise,
disable overlapping features or disable one of the plugins.

Bracket Pair Guides removes only highlighters it created and never clears an
editor's markup model. The native matched-brace flag described above is the one
IDE setting it intentionally changes and restores.

Language support follows the matcher selected by IntelliJ's brace-matching
resolver, not the IDE product name. The resolver can select either a token
language matcher or a legacy file-type matcher. An IDE can still load the plugin
while its primary language remains unsupported; for example, load compatibility
with CLion does not imply C/C++ recognition.

Related JetBrains documentation:

- [Customize editor appearance](https://www.jetbrains.com/help/idea/customize-editor.html)
- [Indent guides](https://www.jetbrains.com/help/idea/indentation.html)
- [Brace matching extension](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [Plugin compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)
