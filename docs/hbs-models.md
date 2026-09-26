# Play with local HBS / CAB models

From this checkout, double-click **play-hbs.cmd**. It reads the sibling
`BTA_ASSETS` folder, converts the installed chassis, then starts MegaMek with
the 3D board enabled. Host, join, or load a game as usual. The first import
takes longer; later launches reuse unchanged conversions.

The model order is **matching HBS chassis → normal Gaea model → Gaea generic
model → existing sprite fallback**. A missing or unreadable HBS model does not
prevent playing. Ordinary `gradlew.bat :megamek:run` keeps the Gaea models.
This is a client display option; other players need no assets or server changes.

## Launch options

```powershell
# Import and play (same as double-clicking play-hbs.cmd)
.\play-hbs.cmd

# Already imported: go directly to the game
.\play-hbs.cmd -SkipImport

# Use a different local CAB installation or Python executable
.\play-hbs.cmd -Assets 'D:\BattleTech\Mods' -Python 'C:\Python312\python.exe'

# Prepare the cache without opening the game
.\play-hbs.cmd -ImportOnly

# Direct Gradle launch after import
.\gradlew.bat :megamek:runHbs
```

The importer needs Python 3.10+ and installs its dependencies into
`.work/hbs-python`, without changing the system Python packages. `-Python` or
`HBS_PYTHON` selects an interpreter. The launcher also recognizes the bundled
Codex Python on this machine. The game itself needs only the existing Java/GL
runtime and the converted cache. For a different cache location, use
`gradlew.bat :megamek:runHbs -PhbsCache='D:\my-cache'`.

## Conversion and selection

`tools/hbs/import_assets.py` reads each base chassis prefab through
[UnityPy](https://github.com/K0lb3/UnityPy). It bakes the prefab's transforms and
skin bind poses, selects intact geometry, excludes damage alternatives, sensor
markers and lower LODs, and exports textured
[libGDX models](https://libgdx.com/wiki/graphics/3d/models). Texture copies are
limited to 1024 pixels on their longest side. Large meshes are split without
dropping triangles. Named joints are mapped to Gaea's existing rigid Mek rig;
the same animation, scene, picking, damage presentation and client commands are
used in both camera views. Conversion has no access to gameplay state.

The generated `.work/hbs-cache/catalog.json` contains only completed imports.
Each model retains its original bundle path. The cache and Python dependencies
are ignored by Git and are outside the data staging and release packaging trees.
Source bundles are read in place and never modified. The CAB's own README,
licenses and attributions remain in the supplied repositories; this integration
does not grant redistribution rights to their artwork.

Matching uses the visible entity's exact chassis name, ignoring punctuation and
case. Either half of a combined name such as `Mad Cat (Timber Wolf)` can match.
`hbs-chassis.properties` supplies explicit aliases for abbreviated or
author-prefixed bundle names. It does not guess from prefixes or fuzzy matches:
`Mad Cat Mk II` must not select `Mad Cat`. Era-specific repositories take priority
over the older all-era CAB repositories when both contain the same bundle.
Unidentified sensor contacts never carry a chassis identity into the renderer.

## Current scope

- The supplied collection contains Mek bundles. Biped chassis use HBS artwork;
  other unit types and alternate vehicle/fighter forms retain Gaea's models.
- The import represents the base chassis. Separate HBS weapon prefabs and
  variant hardpoint loadouts are not yet assembled. MegaMek's actual weapons,
  attacks and rules are unchanged, but external artwork does not depict every
  variant's loadout.
- Diffuse textures and Gaea's lighting are used. HBS shaders, paint masks,
  terrain, effects and original animation clips are not imported. Imported
  models retain their source colors rather than Gaea camouflage. Recognized
  joints use Gaea animation; unsupported rigs remain static while moving along
  the normal timeline.
- Import failures are listed in `.work/hbs-cache/import-report.json`. Runtime
  loads/failures are logged with `[HBS]`. After updating the assets, rerun the
  importer and restart the game. Old cache generations are retained, so a
  running board can finish using them.

## Verification

```powershell
# Synthetic transform, skinning, filtering and mesh-splitting checks
$env:PYTHONPATH = "$PWD\.work\hbs-python"
python -m unittest discover -s tools/hbs -p test_import_assets.py

# Lookup and visibility checks, without proprietary fixtures
.\gradlew.bat :megamek:test --tests '*HbsUnitCatalogTest' --tests '*UnitModelSelectionTest'

# Actual native rendering; requires local Bushwacker, Uziel, Mad Cat and Dire Wolf imports
.\gradlew.bat :megamek:gpuBoardSmoke --tests '*GpuHbs*SmokeTest'
```

The native check renders imported models beside a Gaea fallback and saves
`hbs-isometric.png` and `hbs-top.png` under `megamek/build/gpu-board-review`.
It also loads the actual Bushwacker BSW-X1 beside an Atlas on the shared board,
exercises the native command bridge and walking timeline, and saves
`hbs-board-isometric.png`, `hbs-board-moving.png` and `hbs-board-top.png`.

Verified on this Windows checkout: 295 of 297 distinct installed bundle names
converted; `phawklam` and `shawklam` contained no supported intact geometry and
use Gaea fallbacks. The three Python checks, 20 focused Java tests, both native
rendering tests, Checkstyle and formatting checks on the changed Java files
passed. The repository-wide formatting check also found pre-existing violations
in unrelated files; those files were left unchanged. Native review covered
Bushwacker, Uziel, Mad Cat and Dire Wolf, not a visual audit of every conversion.
