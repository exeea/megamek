# Exterior Battle Armor and swarming

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `GpuBoardSource / BoardScene.Attachment` | Capture visible exterior passengers, hostile swarmers and carrier occupancy; internal passengers stay hidden. |
| `UnitAttachments` | Choose stable grip slots and place suits on the carrier's final posed parts. |
| `UnitAttachmentMotion / UnitPlayback` | Animate boarding and release at the correct point in the carrier's movement/combat sequence. |
| `MovePathHandler / TWGameManager` | Publish authoritative occupancy and resolved movement updates; packet ordering must agree with playback. |

The GPU board presents exterior passengers and hostile swarmers as separate units whose individual
figures ride on the carrier's posed model. Both cameras, picking, shadows and effects use these same
instances. Selection and commands still use the existing game entities.

## Capture and placement

`GpuBoardSource` captures exterior occupancy from `Entity.getExternalUnits()` and the carrier's
swarm-attacker ID. Internal bay passengers stay hidden. Identified exterior Battle Armor can be captured
even when transport has cleared its own board position. Both the passenger and carrier must pass the
existing visibility checks. The immutable `BoardScene.Attachment` belongs to the Swing snapshot; no
renderer reads live entities.

`UnitAttachments` places suits against the existing arm, leg, torso and hull joints. Suit identities keep
stable slots when another suit is lost. Each suit retains its own scale and follows carrier movement,
torso twist, jumping, falling and hover. Hostile suits hold with one arm and repeatedly strike with the
other. Boarding and normal dismounts blend into alternating climbing motions; forced releases flail and
tumble before settling.

Friendly riders and a hostile swarming squad can share a carrier. Their attachment surfaces are assigned
by role, independently of which other squads are visible or attached. Friendly grips stay on the sides
and rear. Swarmers use the fronts of Mek limbs and torso, or the front and rear of a vehicle hull.
Quadrupeds use their front legs in place of arm grips. Spacing accounts for the suits' actual width,
including on narrow central torsos. A squad joining, leaving or becoming concealed cannot move the other
squad's grips. A brush-off only releases the hostile squad when the game confirms that result.

## Playback and authoritative updates

Attachment changes run in `UnitPlayback`, with the same pause, speed, instant and concealment handling as
movement and attacks. Separately delivered passenger packets cannot release a swarmer before the
carrier's path arrives. Jump arrival flows directly into release. A water release partway along a
received route splits playback at the recorded drop hex, then resumes the remaining route.

An explicit unload step publishes the carrier's changed occupancy with its resolved movement route. The
passenger update may arrive earlier, while the carrier still owns the displayed attachment. Sending a
separate carrier update from inside that step would detach the suits at the starting hex, before the
route is available. Unloads outside movement retain their immediate carrier update. Mounted passengers
with a null board position can still capture their annotations, so they do not interrupt movement
capture.

Brush-off and deliberate shake-off attempts publish confirmed `ResolvedAttack` events, including
failures. Failed attempts animate the carrier while retaining its attachment. A successful brush releases
at contact. The renderer never rolls for success or changes transport/swarm relationships. Stop-swarm
events identify voluntary dismounts; stale movement flags are not used to infer a later jump
dislodgement.

## Release geometry

Release paths start from the carrier's current posed attachment and finish at the captured destination's
normal formation. If movement and release advance in the same render tick, the origin is the arrived
carrier's pose rather than a cached position partway along its route. The path bows away from the hull,
samples the rendered ground for clearance, and eases into its exact endpoint. Figures removed by the
result remain visible through their departure. Water entry produces a separate splash and foam ring for
each figure, using the existing water effect batch and playback clock. Destroyed figures sink before
disappearing.

Water does not automatically detach every Battle Armor unit. In the current rules, `drownSwarmer`
specifically handles conventional infantry. These visuals animate actual relationship changes and
preserve Battle Armor attachment whenever the game preserves it.

## Rig limits

The attachment locations are derived from the shipped rigid rigs and their mesh bounds. They are general
placements, not individually authored hand contacts for every chassis. Unusual custom hulls or arm
layouts may need art tuning. The arc clearance is a presentation aid, not a rigid-body collision
simulation against every prop or building.
