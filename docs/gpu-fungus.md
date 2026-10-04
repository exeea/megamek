# Fungal terrain and spores

The `fungus` theme keeps its authored cup, rounded, cliff and scatter GLBs. Woods
density and the reduced scatter/cliff placement rates remain in `BoardFeatures`,
`BoardScatter` and `BoardFungus`. Visual effects do not change terrain rules,
cover, visibility or picking.

## Living surfaces

The surface follows concept A's material roles, with C's stronger cyan/violet:

- Open plain: mauve mineral skin and irregular blue mycelial marbling, 22 m span.
- Ridge crowns and lips: lobed cyan crust, 8 m span.
- Slopes: a separate purple fibrous mantle, 10 m span.
- Exposed cliffs: dark violet rock with sparse cyan fissures, 12 m span.

`terrain-materials.glsl` evaluates these through the existing four material roles
and shared physical projection. Color, normal, height and cavity maps use the same
role weights and translated sampling windows; neither scale nor UV origin resets
at a hex edge. The baker retains broad fungal pigment boundaries, since translating
the samples already breaks up repeating landmarks. Normal-map relief is an artistic
estimate from source luminance, not measured displacement or new terrain geometry.

Rebuild and verify with:

```
python tools/prepare_terrain_contact.py --only fungus-ground fungus-mat fungus-cliff fungus-fibres
python tools/prepare_terrain_contact.py --check --only fungus-ground fungus-mat fungus-cliff fungus-fibres
```

The existing sculpt texture array owns the four color/height and normal/AO pairs.

The shared `levelGrade` helper starts fungus with a darker, richer pigment, then
progressively lightens and desaturates it uphill; sublevels darken toward plum.
Grading happens after the four roles combine, so the cyan crust receives the same
elevation treatment as the underlying skin, slopes and cliffs. The curve follows
continuous world height and eases at extreme elevations rather than bleaching to
white. Captured contact weights blend the treatment at theme boundaries. Negative
levels retain separate tones down to -6; other materials retain their existing grade.

## Bioluminescence and mist

`terrain-foliage.frag` uses the authored atlas quadrants to make lips, gills and
pores emissive, retaining their texture and opaque stems. Cyan mycelium emits its
own color. This includes scatter and all mesh LODs. A restrained pulse follows the
terrain's shared animation clock; the surfaces remain visible at night.

All installed fungal models also contribute colored spill through the existing
`GpuLavaLighting` pass. Its shared 32-source budget uses the captured field of view
and ranks projected proximity against source size/power, so tiny scatter does not
crowd out mature colonies. Emission color and reach follow the actual placed model:
warm lips/gills and cyan hanging mycelium light the nearby terrain and ordinary
objects. The existing diffuse-area approximation does not cast obstacle shadows;
it retains lava's response. Lights install and retire with their terrain chunks,
and native fungal prop lights are omitted from Tactical View and clay inspection.

`GpuFungus` derives emitter anchors once from the installed large cover prop bounds
when a chunk is assembled. Each occupied hex receives one broad, low pink cloud,
regardless of woods density, plus a small wisp starting inside each large cup or
spore body. Cliff and scatter fungi do not emit mist, but retain their glow.
Replacing the chunk replaces its emitters, so board edits and scene changes retire
the old clouds. Both native camera projections use those same emitters and clock.

The existing `GpuEffectBatch` draws seeded, drifting pink billboards with the
`fungus-spores.frag` noise shader. Wisps fade in and out, are depth-tested against
the scene, and do not write depth or cast shadows. Visibility and projected size
filter emitters; a bounded 4096-quad batch retains the nearest colonies if full.
Tactical View keeps its original tileset artwork and does not draw these effects.
The shader is editable under **Terrain** in the existing shader editor.

## Cliff attachment

`BoardFungus` reads the mounting vertices from the same finest GLB meshes as the
renderer, using the authored local Y >= 0 attachment side. A cached `BoardKit`
snapshot owns these CPU points and refreshes with asset reload. For each candidate,
horizontal rays test all rear contacts against the finished wall triangles. The
whole upright cluster moves inward just enough to bury its roots in rock. A site
is retried when it spans a gap or would swallow more than 45% of the model's outward
reach. This supports all shelves of a tiered cluster rather than only its origin;
it does not add new cliff geometry or change the existing placement probabilities.

The hanging mycelium GLB has been rebuilt in Blender with its strand roots embedded
in the shelf's curved underside. Its three mesh levels share matching root
positions; the exporter checked 17/9/5 roots against the actual shell triangles.

## Verification

`BoardFungusTest` covers asset loading, theme selection, density and support of the
actual GLB's rear vertices on the finished cliffs.
`GpuFungusSmokeTest` compiles and renders the actual GL shaders on Fungal Crevasse
by day and night, verifies visible night emission, checks the mist changes over
time, checks one cloud per occupied hex, and compares the same night frame with
and without spill lighting. Replacement cliff-only and scatter-only boards must
have no mist or leftover clouds. A controlled overhead comparison raises the same
fungal plateau through levels 1–5, tracking the same cyan pixels through the complete
rendering pipeline and requiring each step to lighten and lose saturation. Captures
and `fungus-elevation.csv` are saved under the configured GPU review directory's
`fungus` folder. `GpuLavaLightingSmokeTest` guards existing lava lighting,
fungal illumination of ordinary objects, field-of-view gating and source removal.
`BoardUltraSublevelTest` guards the shared pit geometry and retained tactical tiles.
