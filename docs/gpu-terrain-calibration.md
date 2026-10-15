# Terrain Material Calibration

Open `Calibration/32x34 Terrain Material Calibration.board` in the board editor.
The source is in `mm-data/data/boards/Calibration/`; the runtime copy under
`megamek/data/boards/Calibration/` has identical bytes. This is a diagnostic map,
not a balanced scenario. It adds no placed models, buildings or units.

The 32x34 board preserves the original 24 reference regions in its northern
32x17 half and adds a dedicated cliff section in rows 19-34. Each original
region is four columns by five rows. Its level-zero interior provides a
reference patch; shared edges exercise material transitions. Dirt strips at
rows 06, 12 and 18 separate the bands. Descriptions and hex notes embed the
legend in the board itself. There are 40 labelled regions in total.

## Region legend

Each band runs west to east through columns 01-04, 05-08, 09-12, 13-16, 17-20,
21-24, 25-28 and 29-32. Coordinates below use the board's visible four-digit
hex numbers. The A-X reference hexes are all at elevation zero.

| ID | Region | Rows | Reference | Authored input |
| --- | --- | --- | --- | --- |
| A | Grass | 01-05 | 0204 | Default theme |
| B | Dirt | 01-05 | 0604 | `dirt` theme |
| C | Rock | 01-05 | 1004 | `rock` theme |
| D | Sand on rock | 01-05 | 1404 | `sand:1`, `rock` theme |
| E | Pavement | 01-05 | 1804 | `pavement:1` |
| F | Snow | 01-05 | 2204 | `snow:1`, `snow` theme |
| G | Lunar | 01-05 | 2604 | `lunar` theme |
| H | Fungus | 01-05 | 3004 | `fungus` theme |
| I | Desert | 07-11 | 0210 | `desert` theme |
| J | Mars | 07-11 | 0610 | `mars` theme |
| K | Volcanic ground | 07-11 | 1010 | `volcano` theme |
| L | Tropical ground | 07-11 | 1410 | `tropical` theme |
| M | Planted field | 07-11 | 1810 | `planted_fields:1` |
| N | Reed marsh | 07-11 | 2210 | `swamp:1` |
| O | Mud | 07-11 | 2610 | `mud:1` |
| P | Tundra | 07-11 | 3010 | `tundra:1`, default theme |
| Q | Quicksand | 13-17 | 0216 | `swamp:2` |
| R | Water | 13-17 | 0616 | `water:1`; two depth-two hexes |
| S | Ice over water | 13-17 | 1016 | `water:1;ice:1`, `snow` theme |
| T | Magma crust | 13-17 | 1416 | `magma:1`, `volcano` theme |
| U | Flowing magma | 13-17 | 1816 | `magma:2`, `volcano` theme |
| V | Hazardous liquid | 13-17 | 2216 | `water:1;hazardous_liquid:1` |
| W | Woods on grass | 13-17 | 2616 | Two light-woods hexes; remaining ground clear |
| X | Jungle on tropical ground | 13-17 | 3016 | Two light-jungle hexes; remaining ground clear |

Sand is an independent cover over rock, preserving the production material
contract. It is distinct from desert geology. Tundra, fields and wetlands are
biome treatments, and magma crust/flow and frozen water use their liquid/ice
paths. The map therefore covers all 12 ground-cover families (11 base surface
classifications plus sand cover), five nonempty biomes, four liquid/crust kinds
and ice without pretending
that those categories are interchangeable.

## Slopes and selected contacts

In the north and middle bands, the first row is level 2, the next is level 1,
and the final three are level 0. This puts the same material on elevated tops,
sloping/cut faces and a flat reference area. The crust and flowing-magma
regions use the same profile at rows 13-17, exercising a descending lava field
beside crust. Other regions in rows 13-17 remain level 0; water depths vary below
the level surface. No slope theme is overridden separately from its hex.

Useful direct boundaries are grass/dirt, rock/sand, pavement/snow,
lunar/fungus, desert/Mars, field/marsh, marsh/mud, water/ice and crust/flowing
magma. Three specifically labelled, pairwise-adjacent contacts are:

| Contact | Three touching hexes | Materials |
| --- | --- | --- |
| J1 | 0405 / 0505 / 0506 | Grass / dirt / rock |
| J2 | 1611 / 1711 / 1712 | Tropical ground / planted field / dirt |
| J3 | 0812 / 0813 / 0913 | Snow / open water / ice |

These contacts are level zero. They test useful transitions, not every possible
combination. Generic incidental contacts at region edges are not additional
calibration cases. Inspect the named reference hexes for pigment, and the
ramps/boundaries for normals, relief and blending. Sparse woods and jungle
provide foliage context without covering the whole reference region.
Shared lighting, nearby emission and material transitions still affect the
image: this is a visual calibration board, not an isolated albedo measurement
or an exhaustive collection of every terrain variant.

## Cliff section: rows 19-34

These are direct drops of four, five or six levels, not stair-step banks.
`BoardRelief` selects its cliff profile above 2.5 levels and its full cliff
shading from three levels. No manual `cliff_top` token is needed or authored;
the game derives its high-incline edges from the neighboring elevations.

Each C01-C14 bay is four columns wide and seven rows deep. In the first band,
rows 19-21 form the high plateau and rows 22-25 the level-zero toe. In the
second band, rows 27-29 are high and rows 30-33 are low. Row 26 separates the
bands and row 34 is a final level-zero dirt toe. References below identify a
high hex and its directly adjoining low hex on the main south-facing edge.

| ID | Material | Columns | High / low hex | Drop |
| --- | --- | --- | --- | --- |
| C01 | Grass cliff | 01-04 | 0221 / 0222 | 4 levels |
| C02 | Dirt cliff | 05-08 | 0621 / 0622 | 5 levels |
| C03 | Rock cliff | 09-12 | 1021 / 1022 | 6 levels |
| C04 | Sand on rock cliff | 13-16 | 1421 / 1422 | 4 levels |
| C05 | Pavement cliff | 17-20 | 1821 / 1822 | 5 levels |
| C06 | Snow cliff | 21-24 | 2221 / 2222 | 6 levels |
| C07 | Lunar cliff | 25-28 | 2621 / 2622 | 4 levels |
| C08 | Fungus cliff | 01-04 | 0229 / 0230 | 5 levels |
| C09 | Desert cliff | 05-08 | 0629 / 0630 | 6 levels |
| C10 | Mars cliff | 09-12 | 1029 / 1030 | 4 levels |
| C11 | Volcanic ground cliff | 13-16 | 1429 / 1430 | 5 levels |
| C12 | Tropical ground cliff | 17-20 | 1829 / 1830 | 6 levels |
| C13 | Magma crust cliff | 21-24 | 2229 / 2230 | 6 levels |
| C14 | Flowing magma fall | 25-28 | 2629 / 2630 | 6 levels |

C04 preserves sand as cover over rock: erosion and steep-face exposure may
reveal the rock substrate. C13 uses `magma:1` and remains a solid crust cliff.
C14 uses connected `magma:2` tiles on both sides of its six-level drop, causing
the actual molten-fall geometry path to run. It is not a sloping liquid surface.

Columns 29-32 add two mixed vertical contacts:

- **V1**, rows 19-25: grass and dirt plateaus at level 6 over rock at level 0.
  Hexes **3021 / 3121 / 3122** are pairwise adjacent grass/dirt/rock at levels
  6/6/0, providing a three-way contact across a cliff edge. The main reference
  edge is **3021 / 3022**.
- **V2**, rows 27-33: snow at level 6 over lunar ground at level 0. The main
  reference edge is **3029 / 3030**.

These bays cover the 12 ground-cover families through their production wall
semantics, plus crust, falling magma and selected mixed contacts. Cliff walls
need not retain the top's visible cover: steepness, exposed geology, sediment
and the lower material still participate in normal material blending. An
angled view is necessary to inspect the faces; a top view primarily shows the
plateaus and their edges.

## Validation and use

CPU validation uses the current compiled `Board.load` and `Board.isValid`, plus
actual `BoardScene.captureTile`, `BoardSurfaceBlend` and `BoardLiquid` rules.
It passed for all 1,088 hexes, all intended families, 24 original flat references,
43 annotations and the original three adjacent junctions. All 544 hex records
in the northern half exactly match the archived compact board.

The probe also constructed the production `BoardSurface` geometry at every
new main cliff edge. All 15 solid cliff/contact edges have triangles steeper
than 60 degrees and the expected complete four-to-six-level height; their
vertices carry full cliff shading descriptors. The molten edge generated a
six-level fall segment. The actual game high-incline metadata and the V1
three-way adjacency also passed. No new terrain codes or renderer overrides
are used. These checks establish inputs and geometry independently of the native captures below.

The reproducible authoring script, region manifest, Java probe, exact commands
and CPU report are in `build/shader-audit/terrain-calibration/`. The script
refuses to overwrite either board copy. Its `compact32x17/` directory preserves
the original board and its input/CPU evidence; the original six native images
remain under `native/`. Expanded-board captures use `native32x34/` so evidence
is never silently replaced. Only the two newly created, untracked compact
board copies were removed, after verifying their exact archived hash.

Compare fixed-camera captures under the same atmosphere, exposure and
animation time. Use both a top view and an angled view; a focused angled
camera framed on rows 19-34 helps compare cliff shading and rendering cost
without removing the rest of the board from the actual scene.

## Native captures

The expanded board passed the native atlas capture on 13 October 2026: one
test, no skips or failures, six 1920x1200 images on the NVIDIA GeForce RTX 4070
Laptop GPU. Top and angled cameras each captured clear dawn (06:30), day
(13:00) and dusk (17:30), with exposure zero and the animation clock frozen at
0.5 seconds. Each full-frame repeat was byte-exact. This capture is visual
validation, not a performance measurement; it does not establish Iris Xe cost.

| View | Dawn | Day | Dusk |
| --- | --- | --- | --- |
| Top | [Image](../build/shader-audit/terrain-calibration/native32x34/captures/current/terrain-calibration/top-dawn.png) | [Image](../build/shader-audit/terrain-calibration/native32x34/captures/current/terrain-calibration/top-day.png) | [Image](../build/shader-audit/terrain-calibration/native32x34/captures/current/terrain-calibration/top-dusk.png) |
| Angled | [Image](../build/shader-audit/terrain-calibration/native32x34/captures/current/terrain-calibration/angled-dawn.png) | [Image](../build/shader-audit/terrain-calibration/native32x34/captures/current/terrain-calibration/angled-day.png) | [Image](../build/shader-audit/terrain-calibration/native32x34/captures/current/terrain-calibration/angled-dusk.png) |

The angled day image shows distinct steep cliff faces, solid crust and the
connected molten fall. Automated follow-up verified all six PNG byte hashes
and dimensions, the exact source/runtime/manifest hashes, camera equality
across times, and native surface/biome counts against the CPU report. Evidence
is in [capture-validation.json](../build/shader-audit/terrain-calibration/native32x34/evidence/capture-validation.json);
the adjacent archived XML and logs contain the native repeat assertions.
These local build artifacts are intentionally separate from the user-loadable
board and its tracked documentation.
