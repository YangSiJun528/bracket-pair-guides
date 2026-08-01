# Configure Bracket Pair Guides

Use this guide to install the plugin, configure its single Settings page, and
resolve duplicate highlighting.

## Install a local build

1. Build the plugin:

   ```shell
   ./gradlew buildPlugin
   ```

2. Open **Settings | Plugins** in the target JetBrains IDE.
3. Open the gear menu and select **Install Plugin from Disk**.
4. Select the ZIP from `build/distributions/`.
5. Restart if requested.

Open a supported source file and place the primary caret inside nested
brackets. Only the innermost containing pair receives a guide, pair border, and
pair background. Moving outside every pair removes that active presentation.

## Open the single Settings page

Open **Settings | Editor | Bracket Pair Guides**. Behavior and colors
are configured on this page; there is no second page under **Color Scheme**.

## Choose visible components

| Setting | Default | Effect |
|---|---:|---|
| Enable Bracket Pair Guides | On | Master switch; disabled passes skip recognition and clear plugin markup |
| Color matching bracket tokens by nesting level | On | Colors bracket tokens from the six-level base palette |
| Show active pair guide | On | Draws the one caret-activated C-shaped line |
| Vertical segment | On | Draws the vertical part of a multiline guide |
| Opening and closing segments | On | Draws horizontal arms and single-line guides |
| Line width | 1 px | Sets line width from 1 to 4 pixels |
| Opacity | 100% | Multiplies line opacity from 10% to 100% |
| Show pair border | On | Adds a border only to the opening and closing symbols |
| Border style | Box | Selects Box or Rounded box |
| Show pair background | On | Adds a background only to the opening and closing symbols |
| Background opacity | 22% | Blends the pair background source color with the editor background |

The pair settings never shade the complete range between the two symbols.

## Set the base palette

Use the compact table under **Level colors** to choose levels 1–6. Level 7
repeats level 1, level 8 repeats level 2, and so on. Click an editable swatch to
open the IDE color chooser. The tooltip shows its hexadecimal value.

By default, each level's Base color supplies all four components:

| Component | Default source |
|---|---|
| Bracket token | Base color |
| Guide color | Base color |
| Pair border | Base color |
| Pair background | Base color blended by background opacity |

The six supplied Base colors differ from one another and have light/dark theme
defaults. **Reset to current theme defaults** clears explicit Base
colors and resumes following the current editor scheme.

## Override component colors

Enable **Customize component colors separately** only when a component must
differ from its level's Base color.

The table contains one row per level and these color columns:

- **Base**
- **Guide**
- **Border**
- **Background**

Base is editable while the plugin is enabled. Guide, Border, and Background
remain inherited and read-only until separate colors are enabled. A component
column is also read-only when its visibility switch is off. Turning separate
colors off makes the Base colors effective but retains the advanced values for
the next time the switch is enabled. Only **Reset to current theme defaults**
clears those overrides.

## Check changes before applying

The **Preview** is the editable editor pane to the right of the controls. It
shows the current draft appearance directly without adding diagnostic text
below the editor.

To inspect a supported format:

1. Select an example from **Example**. Java, Kotlin, JSON, XML, and Markdown
   appear when their language file types are installed in the IDE.
2. Edit the boilerplate directly or move the caret into another nested pair.
   Document edits are analyzed after a 150ms pause; caret movement and Settings
   changes reuse the latest recognition result.
3. Switch examples as needed. The Preview retains temporary text and the caret
   separately for each format.
4. Select **Reset** to restore only the selected format's boilerplate and
   initial caret.

For unusually dense edited examples, the Preview colors the first 500 pairs.
Pair recognition and the caret-active pair still cover the complete example.
Recognition pauses when an example exceeds 100,000 characters.

Preview text is session-only. **Apply** and **OK** persist the feature and
appearance controls, but never save preview text or copy it into a source
file. Open source editors do not receive the draft appearance until you select
**Apply** or **OK**.

A Pair background opacity of 0% adds no background attribute. It therefore
does not cover diff, inspection, or third-party background highlighting.

## Map VS Code settings

| VS Code | Bracket Pair Guides |
|---|---|
| `editor.bracketPairColorization.enabled` | Color matching bracket tokens by nesting level |
| `editor.guides.bracketPairs: "active"` | Show active pair guide |
| `editor.guides.bracketPairsHorizontal: "active"` | Opening and closing segments |
| `editor.guides.highlightActiveBracketPair` | Show pair border / Show pair background |
| `editorBracketHighlight.foreground1..6` | Base column under Level colors |
| `editorBracketPairGuide.activeBackground1..6` | Guide column with separate colors enabled |

VS Code does not expose this plugin's pair border style, pair background color,
or component-linking model.

## Coexist with IntelliJ features

| IDE feature | Difference or possible overlap |
|---|---|
| Matched brace | Activates near a brace boundary and can add a native scope line |
| Current scope | Uses IDE scope rules and can duplicate the active guide |
| Show indent guides | Draws whitespace indentation columns, not bracket pairs |
| Semantic rainbow highlighting | Colors identifiers, not bracket nesting |

A low-conflict setup is:

1. Leave **Show indent guides** on.
2. Leave **Matched brace** on for native boundary feedback.
3. Turn **Current scope** off.
4. Leave this plugin's guide, pair border, and pair background on.

For a plugin-only appearance, turn **Current scope** and **Matched brace** off.
JetBrains tracks the inability to disable only the Matched brace gutter line in
[IJPL-31232](https://youtrack.jetbrains.com/issue/IJPL-31232).

## Resolve another plugin overlap

| Symptom | Resolution |
|---|---|
| Bracket token colors overwrite each other | Disable token coloring in one plugin |
| Two active lines are visible | Disable the active guide line in one plugin |
| Pair borders are stacked | Disable **Show pair border** in one plugin |
| Pair backgrounds are stacked | Disable **Show pair background** in one plugin |
| A complete block is dark | Disable IDE Current scope or the other plugin's scope shading; this plugin does not shade ranges |
| Native and plugin lines overlap only at a brace boundary | Keep both or disable Matched brace |

In source editors, the plugin removes only highlighters carrying its ownership
marker. It does not delete by layer number, call `removeAllHighlighters`, change
Matched brace keys, or edit another plugin's settings.

## Rider

Rider C# uses a ReSharper backend. Do not assume Java/Kotlin matcher behavior
applies to C# without a dedicated provider and regression test.

For related native controls, see JetBrains'
[editor appearance guide](https://www.jetbrains.com/help/idea/customize-editor.html),
[indent guide documentation](https://www.jetbrains.com/help/idea/indentation.html),
and [Rider matching-delimiter documentation](https://www.jetbrains.com/help/rider/Coding_Assistance__Matching_Delimiters.html).
