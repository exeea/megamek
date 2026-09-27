\# Mouse and Camera Input Specification



\*\*Scope:\*\* Desktop mouse and keyboard controls for the tactical-map viewport. Touch and controller navigation are outside this specification.



\*\*Core rule:\*\* Middle-drag orbits and right-drag pans. Shift swaps their drag behavior. Only the right button has a click action.



\## 1. Default bindings



| Input | Action |

|---|---|

| \*\*Middle-button drag\*\* | Orbit |

| \*\*Shift + middle-button drag\*\* | Pan |

| \*\*Right-button drag\*\* | Pan |

| \*\*Shift + right-button drag\*\* | Orbit |

| \*\*Right click without dragging\*\*, with or without Shift | Cancel the current operation; otherwise perform the applicable context action |

| \*\*Middle click without dragging\*\*, with or without Shift | No action |

| \*\*Mouse wheel forward / backward\*\* | Zoom in / out |

| \*\*WASD / arrow keys\*\* | Pan in screen-relative directions |

| \*\*Shift + WASD / arrow keys\*\* | Faster panning |

| \*\*Q / E\*\* | Rotate left / right |

| \*\*Home\*\* | Center on the selected unit; no action when nothing is selected |

| \*\*Escape during a mouse gesture\*\* | Abort that gesture without executing a click action |



Left-click gameplay controls remain unchanged. Neither left-click nor left-drag is assigned to camera navigation.



\---



\## 2. Required invariants



\*\*A gesture must never perform both camera navigation and a gameplay action.\*\* A right-button drag must not cancel targeting, issue an order, deselect a unit, or open a context menu when released.



\*\*Pressing a camera button must not immediately affect gameplay.\*\* Selection, targeting, movement planning, and other active operations remain intact while the player navigates.



\*\*Once a gesture becomes a drag, it remains a drag until completion or interruption.\*\* Returning the pointer to its original position does not restore click eligibility.



\---



\## 3. Gesture recognition



\### 3.1 Starting a gesture



Accept a gesture only when the press starts over the interactive map, no blocking interface element owns the input, and no other mouse button is already held.



On an accepted middle- or right-button press, record:



| Field | Purpose |

|---|---|

| `pointerId` | Identifies the pointer that owns the gesture |

| `button` | Middle or right |

| `mode` | Right: `PAN`; middle: `ORBIT`. Shift held at press swaps the mode. |

| `startPosition` | Initial pointer position in logical/CSS pixels |

| `lastPosition` | Position used for incremental camera updates |

| `state` | Initially `PENDING` |

| `clickIntent` | Right-click operation to validate on release; none for middle button |



Do not move the camera or execute the click intent yet.



\*\*Capture Shift at the initial press and lock the mode for the entire gesture.\*\* Pressing or releasing Shift afterward must not switch between pan and orbit.



\### 3.2 Click-versus-drag threshold



Use a configurable threshold with an initial default of \*\*5 logical/CSS pixels\*\*. This is a project tuning value, not a claimed industry standard.



Measure displacement from the original press position:



```text

dx = currentX - startX

dy = currentY - startY



startDrag = (dx² + dy²) >= dragThreshold²

```



Do not use cumulative travel distance or distance from only the previous movement event.



Below the threshold, remain `PENDING` and leave the camera unchanged. At or above the threshold, enter `DRAGGING` using the locked mode and permanently discard the click intent.



On the transition, discard only the initial threshold distance and apply the remaining movement. Subsequent updates use incremental pointer deltas. Do not discard an entire large first movement event.



\### 3.3 Holding without moving



There is \*\*no long-press timer\*\*.



Holding either button still does nothing until release. A right-button hold that stays below the movement threshold remains eligible for cancel/context, regardless of its duration.



\### 3.4 Releasing



Process the final pointer position before deciding whether the gesture qualifies as a click.



| State at release | Required behavior |

|---|---|

| `PENDING`, right button, valid click | Execute the stored click intent once |

| `PENDING`, middle button | No action |

| `PENDING`, invalid click | No action |

| `DRAGGING` | Finish camera navigation; no gameplay action |

| Interrupted or suppressed gesture | No action |



A click is valid only when release occurs over the interactive map, no blocking interface has taken ownership, and the stored intent remains valid.



\---



\## 4. Right-click action resolution



Determine the candidate intent on press, \*\*without executing it\*\*:



```text

If a cancellable gameplay operation is active:

&#x20;   intent = Cancel(that operation)



Otherwise, if the clicked target has a context action:

&#x20;   intent = ContextAction(that target)



Otherwise:

&#x20;   intent = None

```



A cancellable operation includes an active targeting mode, movement-placement operation, or another explicit interaction awaiting confirmation. Merely having a selected unit does not automatically count as such an operation.



On a valid click release, revalidate the stored operation or target and execute that intent once.



\*\*Do not fall through to a different action.\*\* For example, if the operation selected for cancellation ended while the button was held, discard the intent rather than opening a context menu instead.



Store an operation identifier or interaction-version token so a delayed release cannot accidentally act on a newly started operation. Context actions must also validate that their original target still exists and remains eligible.



\*\*Cancel takes priority over context.\*\* One right click must never both cancel an operation and open a menu.



\---



\## 5. State machine



Use these logical states:



```text

IDLE

PENDING

DRAGGING\_ORBIT

DRAGGING\_PAN

SUPPRESSED\_UNTIL\_RELEASE

```



| Current state | Input or condition | Next state | Effect |

|---|---|---|---|

| `IDLE` | Accepted middle/right press | `PENDING` | Record gesture and locked mode |

| `PENDING` | Movement below threshold | `PENDING` | None |

| `PENDING` | Threshold reached; mode is orbit | `DRAGGING\_ORBIT` | Discard click intent; begin orbit |

| `PENDING` | Threshold reached; mode is pan | `DRAGGING\_PAN` | Discard click intent; begin pan |

| `PENDING` | Owning button released | `IDLE` | Execute valid right-click intent, otherwise nothing |

| Either dragging state | Pointer movement | Same state | Update camera |

| Either dragging state | Owning button released | `IDLE` | End navigation without clicking |

| Pending or dragging | Interruption | `SUPPRESSED\_UNTIL\_RELEASE` | Discard intent and stop navigation |

| `SUPPRESSED\_UNTIL\_RELEASE` | All mouse buttons released | `IDLE` | Re-arm input |



Cleanup must be \*\*idempotent\*\*: multiple termination notifications must not cause duplicate actions or errors. Clear the active gesture before invoking gameplay callbacks.



\---



\## 6. Camera behavior



\### Orbit



Rotate around the camera’s current battlefield focus point, not the unit or tile under the pointer.



Horizontal dragging changes yaw. Vertical dragging changes tilt within the configured limits. Maintain camera-to-focus distance, prohibit roll, and keep the pivot fixed throughout the orbit.



Do not automatically recenter on the selected unit when an orbit begins or ends. Provide configurable sensitivity and axis inversion.



\### Pan



Translate the camera and its focus point together across a horizontal navigation plane.



Use a \*\*grab-the-map\*\* interaction: dragging right moves the displayed terrain right; dragging upward moves it upward.



Preserve yaw, tilt, and camera-to-focus distance. Keep the navigation plane stable throughout the gesture; do not change its height whenever the pointer crosses a raised hex, building, or cliff.



Clamp movement to the configured map-navigation bounds.



\### Zoom



Wheel forward zooms in; wheel backward zooms out.



Zoom toward the current camera focus, preserving yaw and tilt. Clamp to configured minimum and maximum zoom. The wheel does not select terrain elevation or floors.



\### Keyboard navigation



WASD and arrow keys pan relative to the current view, not fixed compass directions. Normalize diagonal input so diagonal movement is not faster.



Q/E changes yaw without changing tilt. Home centers the selected unit while preserving the current viewing angle and zoom.



Disable gameplay keyboard shortcuts while typing in text fields or when another interface owns keyboard input.



\### Navigation conflicts



For the initial implementation, an accepted mouse gesture has exclusive camera ownership until release.



While it is pending or dragging, suspend edge scrolling, automatic recentering, and keyboard/wheel camera commands. Do not queue those commands for later execution.



If the player attempts keyboard or wheel navigation while a right click is pending, \*\*discard that pending click intent\*\*, even though the competing camera command is suppressed. This prevents an attempted navigation combination from becoming an accidental cancel.



Camera movement must stop on release. Disable post-release orbit/pan inertia by default.



\---



\## 7. Input ownership and interruption



\### Interface boundaries



A press beginning on a menu, toolbar, dialog, or other interactive overlay must not start map navigation.



A drag beginning on the map may continue across an overlay or outside the viewport. Its release must not activate the interface underneath the pointer.



The camera controller and gameplay controller must not independently interpret the same raw middle/right input. Use one gesture recognizer to dispatch either camera updates or a validated right-click action.



\### Interruptions



Abort the gesture on `pointercancel`, unexpected pointer-capture loss, window focus loss, document hiding, viewport removal, or a blocking interface taking ownership.



Escape during a pending or active gesture also aborts it. Consume that Escape press; it must not additionally cancel the underlying targeting or movement operation.



Aborting discards the click intent, stops camera movement, restores the cursor, and releases capture where applicable. \*\*Keep the camera’s current pose; do not rewind it.\*\*



Returning focus must not resume an interrupted drag. Require a fresh press.



\### Multiple mouse buttons



Do not support mouse-button chords in this version.



If another mouse button is pressed during a pending or active camera gesture, abort it and suppress pointer actions until all buttons are released. Do not switch the owning button or interpret either release as a click.



\---



\## 8. Browser implementation requirements



\### Pointer capture and button tracking



Use Pointer Events and capture the accepted pointer with `setPointerCapture(pointerId)`. Capture routes subsequent pointer events to the viewport even when the pointer moves outside its boundaries. Release capture during cleanup. :chatgpt-content-reference{index="0"}



For mouse input, middle is `button === 1`, with held-button mask `4`; right is `button === 2`, with held-button mask `2`. Track `buttons` during the gesture: additional button presses and releases do not necessarily produce separate `pointerdown` and `pointerup` events. :chatgpt-content-reference{index="1"}



Do not assume the captured event’s target proves that release happened over the map. Validate the actual release coordinates against the viewport and blocking overlays.



\### Native events and duplicate actions



Execute the game’s right-click action \*\*only through the gesture recognizer\*\*. Do not also execute it from `contextmenu`, `auxclick`, or another mouse handler.



Preventing a pointer event does not by itself prevent all subsequent `click`, `auxclick`, or `contextmenu` events. Suppress relevant native defaults separately, and keep those events out of gameplay dispatch. :chatgpt-content-reference{index="2"}



Prevent middle-button defaults on accepted viewport presses and suppress viewport `auxclick` defaults. Browser/operating-system defaults can include autoscroll, clipboard paste, or opening links. Do not suppress these behaviors globally outside the game viewport. :chatgpt-content-reference{index="3"}



\### Firefox limitation



Suppress ordinary viewport context menus with `contextmenu.preventDefault()`.



\*\*Firefox’s Shift + right-click can show the native menu without dispatching `contextmenu`.\*\* Therefore, Shift + right-drag is a best-effort browser binding, not a universally guaranteed one. Retain Shift + middle-drag, keyboard panning, and remapping as alternatives. Do not require browser settings changes. :chatgpt-content-reference{index="4"}



\### Wheel handling



Normalize wheel deltas according to `deltaMode`; values may represent pixels, lines, or pages. Use a non-passive listener where the viewport needs to prevent native scrolling, and consume wheel events only when the viewport owns them. :chatgpt-content-reference{index="5"}



\---



\## 9. Acceptance tests



| Test | Expected result |

|---|---|

| Middle-drag without Shift | Orbit |

| Shift held before middle-drag | Pan |

| Right-drag without Shift | Orbit; no action on release |

| Shift held before right-drag | Pan where the browser permits; no gameplay action on release |

| Right press/release with movement below 5 px | Exactly one valid cancel/context action |

| Right press/release at exactly 5 px displacement | Classified as drag; no click action |

| Hold right button still for several seconds, then release | Valid cancel/context action; no long-press behavior |

| Right-drag away, return to the start, then release | No click action |

| Begin orbit, then press Shift | Continues orbiting |

| Begin pan, then release Shift | Continues panning |

| Right-drag while targeting or planning movement | Operation remains active after release |

| Right-click while targeting and over a contextual target | Cancels targeting only |

| Stored operation or target becomes invalid before release | No action and no fallback |

| Middle click without dragging | No gameplay action |

| Begin drag on map, release over an interface control | Ends drag; does not activate the control |

| Begin press on an interface control | No map camera gesture |

| Lose focus or receive `pointercancel` during a gesture | Stops safely; no click; does not resume automatically |

| Press Escape during a gesture | Aborts gesture only; preserves underlying gameplay operation |

| Press a second mouse button during a gesture | Aborts and suppresses actions until all buttons are released |

| Use different display scaling or canvas rendering resolutions | Same logical 5 px threshold |

| Complete one right click while native auxiliary events also fire | Exactly one gameplay action, never duplicates |

