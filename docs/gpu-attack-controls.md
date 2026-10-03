# Attack controls in the GPU board

[Docs index](README.md) · [Complete class responsibilities](gpu-code-map.md)

## What does what

| Owner | Responsibility |
| --- | --- |
| `FiringDisplay / PhysicalDisplay / TargetingPhaseDisplay` | Own selected weapons, ammo, target calculations, queued orders and completion actions. |
| `GpuBoardActions` | Capture attack-panel data and dispatch commands after checking the live phase/selection. |
| `GpuAttackPanel` | Lay out and display the native weapon/physical controls. |
| `BoardFiringGeometry / GpuFireControl` | Build and draw queued-attack geometry and range labels from client-supplied tactical data. |

The GPU board shows an attack console on the right during an acting unit's attack
phase: the battle HUD's weapons panel, with the target cards over the board, the solution card and the
command dock ([gpu-hud.md](gpu-hud.md)). Physical attacks use the dock's physical options; TARGETING and
OFFBOARD keep MegaMek's own board tool. The HUD's commands change `FiringDisplay`'s own queue through the
display's methods on the Swing event thread, which owns all game access and attack calculations.

The displayed weapon's range brackets are the field-of-fire handler's borders as upright walls of the shared
`BoardRangeBorder`, a level tall in each bracket's colour, as the visual range and the deployment zones stand
([gpu-hud.md](gpu-hud.md), region markings); the Tactical View lays them flat. Sensor ranges and objective zones
retain their ground presentation. During the local weapon declaration the acting unit's lines
are the HUD's traces; the lines below are every other attack's.

Queued attack arrows connect the attacker and target at their occupied heights.
Direct shots, including direct artillery, are straight, depth-tested 3D lines.
Indirect orders use a curved visual trajectory above intervening terrain and structures. This
curve illustrates the order; it does not simulate projectile physics or change
LOS or attack legality. Multiple weapons share a line, with separate straight
and curved lines when both modes attack the same target. These graphics are
drawn separately from per-hex ground textures so their height remains continuous.
