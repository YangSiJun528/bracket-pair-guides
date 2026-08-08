# Configure Bracket Pair Guides

All behavior and appearance settings are under **Settings | Editor | Bracket
Pair Guides**. There is no separate Color Scheme page.

## Choose what is shown

| Setting | Default | Effect |
|---|---:|---|
| Enabled | On | Enables or disables all plugin highlighting |
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

## Choose languages

The **Languages** group lists every installed language family that provides the
`com.intellij.lang.braceMatcher` capability, including embedded-only
languages without a standalone file type. Every family is enabled by default.

- Clear a family to exclude it from token colors, active guides, and pair
  emphasis.
- Derived languages that inherit the same matcher are grouped together. For
  example, TypeScript and JSX can appear in the JavaScript family tooltip.
- A newly installed supported family starts enabled. A disabled selection is
  retained if its language plugin is temporarily removed.
- **Custom file types** means syntax-table bracket tokens registered through
  the platform `TEXT` matcher; ordinary raw plain text is not scanned.

Applying a language change clears stale decorations, runs the bounded immediate
resolver for the focused or selected editor's current pair, and schedules
complete background analysis. Other open editors and split views remain
undecorated until their background pass completes. Reopen the Settings page
after installing a language plugin so the family list can be rediscovered.

Only matcher-defined pairs are affected. Enabling YAML does not turn indentation
blocks into bracket pairs, and unsupported legacy-only languages are not added
to this list. See the [IDE and language support reference](reference_language_support.md).

## Set level colors

The **Colors** grid contains six levels. Deeper levels repeat the same sequence:
level 7 uses level 1, level 8 uses level 2, and so on. The selectors are the
IntelliJ Platform's standard color controls.

An empty Base selector uses the active editor theme. By default, Base supplies
the bracket-token foreground, guide line, pair border, and pair background for
that level. Select **Reset colors** to clear explicit values and return every
level to this automatic behavior.

Enable **Component overrides** only when Guide, Border, or Background should
differ from Base. An empty component selector inherits Base. Turning the switch
off retains explicit component values for later use; **Reset colors** clears
them.

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
name. An IDE can load the plugin while its primary language remains unsupported;
for example, load compatibility with CLion does not imply C/C++ recognition.
Languages with only the legacy file-type matcher are left unchanged.

Related JetBrains documentation:

- [Customize editor appearance](https://www.jetbrains.com/help/idea/customize-editor.html)
- [Indent guides](https://www.jetbrains.com/help/idea/indentation.html)
- [Brace matching extension](https://plugins.jetbrains.com/docs/intellij/additional-minor-features.html)
- [Plugin compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)
