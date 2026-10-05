# VRCamera architecture

For whoever changes the code. How to use the mod: [README.md](README.md).

## The mod in one paragraph

Vivecraft can render the world once more from a separate handheld camera and show that picture in the game window.
The mod draws nothing itself. Once per frame it works out where that camera should be, where it should look and
with which field of view, and writes that into Vivecraft. Picking shots, smoothing and keeping out of walls all
happen before that write.

## The camera in Vivecraft

What the mod hooks into. Class names are from Vivecraft 1.3.15 for Minecraft 26.2.

| What | Where in Vivecraft | How the mod uses it |
|---|---|---|
| Pose of the handheld camera, world space | `CameraTracker`: `position`, `rotation` | written with `setPosition`, `setRotation` |
| Camera visibility | `CameraTracker.isVisible`, `toggleVisibility` | turned on if it is off |
| Camera held by the player | `CameraTracker.isMoving`, `startMoving`, `stopMoving`, `ScreenshotCameraModule` | while `isMoving` the mod only steadies it |
| Camera field of view | `VRSettings.handCameraFov`, read in `CameraVRMixin` every pass | written every frame |
| Camera picture in the game window | `VRSettings.displayMirrorUseScreenshotCamera`, read in `ShaderHelper` | turned on while the mod works |
| Camera buffer | `VRRenderer.cameraFramebuffer`, 1920×1080 × Camera Resolution | untouched |
| Camera render pass | `RenderPass.CAMERA`, loop in `VRPassHelper.renderAndSubmit` | untouched |
| Code that runs once per frame | `Tracker` with `ProcessType.PER_FRAME`, called from `VRPlayer.preRender` | entry point of the mod |
| Interact button | `InteractTracker`, `HeldInteractModule` | the pull gesture |
| Headset and hand poses | `VRPlayer.vrdata_world_render` (`VRData`) | where player data comes from |
| Camera model in the headset | `VRWidgetHelper.extractVRHandheldCameraWidget` | hidden by a mixin |

Order within a frame:

1. `VRPlayer.preRender` builds `vrdata_world_render`. The camera pose is copied into `VRData.cam` here.
2. All `PER_FRAME` trackers run, Vivecraft's own first, then `CameraController`.
3. `VRPassHelper.renderAndSubmit` renders the passes: both eyes, then `CAMERA`.
4. `ShaderHelper` copies the camera buffer into the game window.

Steps 1 and 2 mean the pose is **one frame late**: what the mod writes reaches `VRData.cam` on the next frame. With
smoothing this does not show.

The public Vivecraft API is only used to register the tracker and the interact module
(`VRClientAPI.addClientRegistrationHandler`) and for haptics. Everything else is internal classes:
`ClientDataHolderVR`, `CameraTracker`, `VRSettings`, `VRData`, `VRState`, `GuiHandler`. This is what breaks first
on a Vivecraft update.

## Data flow of a frame

```
VRPlayer.preRender (Vivecraft)
  └─ CameraController.activeProcess          entry point, once per frame
       ├─ Subject.update                     the player: feet, head, center, velocity, scale, target
       ├─ camera flying to a hand? → move it and return
       ├─ camera in a hand? → steady it and return
       ├─ PHYSICS: DroppedCamera.update      fall, bounce, rest
       ├─ Director.update  (DIRECTOR)
       │    ├─ updateContext                 what the player does, tight place, combat target
       │    ├─ detectEvent                   death, fall, menu
       │    ├─ change needed? → choose       candidates and their scores
       │    └─ Shot.update                   where the shot wants the camera now
       ├─ Shot.update      (FOLLOW, one own angle)
       ├─ Rig.update                         smoothing → walls → aim → FOV
       └─ write to CameraTracker and VRSettings
```

Who answers what:

- **Shot**: where the camera wants to be. Knows nothing about walls or smoothness.
- **Rig**: where the camera ends up.
- **Director**: which shot runs now and when it changes.
- **Subject**: the only place the others take player data from.
- **CameraController**: ties it to Vivecraft and keeps the mode.

## Packages and classes

| Class | Responsibility |
|---|---|
| `client.VrcameraClient` | Fabric entry point: tracker, interact module, keys (a key → action table), command, pause menu buttons, overlay |
| `client.VrcamCommand` | client command `/vrcam`: what keys and buttons do, plus picking a shot |
| `client.CameraController` | Vivecraft tracker; modes; taking and restoring Vivecraft settings; placing by hand; own angles; marker and icon |
| `client.CameraPull` | Vivecraft interact module: pulling the camera into a hand |
| `client.CameraEffects` | particles and sounds: impact dust, pulling |
| `client.rig.Subject` | snapshot of the player for a frame |
| `client.rig.Rig` | springs, arm length, aim, final pose |
| `client.rig.WorldProbe` | every look at the blocks of the world: rays, free space, obstacle thickness, openness |
| `client.rig.HandThrow` | watches a held camera and tells a throw from letting go |
| `client.rig.HandStabilizer` | steadies a held camera |
| `client.rig.HandheldShake` | Physics: sway of a held camera from breath, steps and damage |
| `client.rig.DroppedCamera` | Physics: fall, bounces, kicks, impacts and rest pose of a camera that was let go of |
| `client.shot.ShotType` | list of shots: defaults and how well each fits a situation |
| `client.shot.Shot` | a running shot: wanted azimuth, elevation, distance, FOV, look target |
| `client.director.Director` | situation, target, events, picking and changing shots |
| `client.director.Context` | the situations |
| `client.config.CameraConfig`, `ShotConfig` | reading and writing `vrcamera.json` |
| `client.config.Marker`, `Transition` | enums for settings with fixed values, lower case in the file |
| `client.gui.ConfigScreen` | settings screen on Cloth Config |
| `client.gui.CameraMenuScreen` | screen with all control buttons, opened from the pause menu |
| `client.gui.DebugOverlay` | text of the debug overlay and drawing it; `/vrcam status` prints the same lines |
| `client.gui.ModMenuIntegration` | settings button in Mod Menu |
| `client.math.*` | springs (`Smooth`, `SmoothAngle`, `SmoothVec`) and geometry (`CamMath`) |
| `mixin.client.VRWidgetHelperMixin` | hides the Vivecraft camera model |
| `mixin.client.LevelExtractorMixin` | draws marker and icon for the eyes |

## Coordinates and conventions

- **Yaw** as in Minecraft: 0 faces south (+Z), direction vector `(-sin, 0, cos)`. Vivecraft's
  `VRDevicePose.getYawRad` does the same.
- **Orbit.** The camera position relative to the player is three numbers: azimuth (world yaw of the direction from
  player to camera), elevation above the horizon, distance. `CamMath.orbit` turns them into a vector.
- **Azimuth in the config** is relative: 0 is in front of the player, 180 behind. `Shot` adds the body direction
  to get the world value. For `duel` it is measured from the direction to the opponent.
- **Side.** Director shots mirror the azimuth with `side` (−1 or 1). Own angles always have `side` 1 and a signed
  azimuth.
- **Camera rotation.** Vivecraft looks along local −Z. `CamMath.lookRotation` builds a quaternion from the basis
  right, up, back, without roll.
- **Unit of length.** Every distance from the config is multiplied by `Subject.unit`, which is
  `LivingEntity.getScale()`. So the `scale` attribute applies everywhere, including collision radius and minimum
  distances.

## Subject: what is known about the player

| Field | From | For |
|---|---|---|
| `feet` | `player.getPosition(partialTick)` | base, interpolated between ticks |
| `head` | headset position from `VRData.hmd`; the entity's eyes if that is too far from the entity | top of the body |
| `center` | between `feet` and `head` at `aimHeight` | look target and orbit center |
| `hands` | middle between the controllers | `hands` shot |
| `unit` | `player.getScale()` | scale of all distances |
| `facing` | `VRData.getBodyYawRad()`, smoothed over 0.3 s | where the body faces |
| `velocity`, `speed` | difference of `feet` between frames, smoothed over 0.25 s | situation, lead room, FOV |
| `teleported` | moved more in a frame than `max(1 block, 70 blocks/s × frame time)` | jump detection |
| `guiCenter` | set by the controller | `menu` shot |
| `target`, `targetCenter` | set by `Director` | combat |

`facing` is the body, not the headset: players turn their heads all the time, and a camera tied to the headset
swings with it. Vivecraft estimates the body direction from headset and hands.

The teleport threshold: Vivecraft moves the player with `snapTo`, one frame without interpolation. Nothing a player
does, elytra included, reaches 70 blocks/s.

## Shot: where a shot wants the camera

`Shot` keeps its goal in orbit coordinates and updates it every frame.

| Type | Behavior |
|---|---|
| `SHOULDER`, `LOW`, `HANDS`, `CRANE`, `FALL`, `CUSTOM` | azimuth follows `facing + own angle` with the dead zone `turnDeadzone` and the lag `turnLag` |
| `FRONT` | same, and the distance shrinks to 72% while the player stands |
| `ORBIT`, `DEATH` | azimuth grows at `orbitSpeed`, whatever the player looks at; `DEATH` also pulls back |
| `CRANE` | elevation and distance grow over the shot |
| `DUEL` | azimuth is measured from the direction to the target; distance grows with the distance to it; aims between player and target |
| `HANDS` | aim is shifted to the hands |
| `LOW` | the camera is never below the player's feet |
| `FLYBY` | the only world shot: a spot ahead on the path is picked once and stays; the FOV keeps the player the same size in frame |
| `POV` | first person. The only shot not placed on the orbit: `Shot.position` puts the camera `distance` in front of the head, horizontally. It looks where the head looks, 0.25 s late. Only cuts lead to it and away from it (`ShotType.cutsOnly`) |
| `MENU` | like `DUEL` with the open menu as opponent: azimuth is measured from the direction to it, the aim is shifted 80% to the menu |

The dead zone keeps small body turns from moving the camera at all. The lag turns large ones into a smooth swing.

`Shot.finished` says the shot has nothing left to show: for `FLYBY` the player is far away, for `DUEL` the target
is gone.

## Rig: from wanted to actual

Steps of `Rig.update`, in order:

1. **Anchor.** The player center is smoothed (0.18 s), so the camera does not copy every head bob. The lag this
   causes while moving is made up for by adding `velocity × 0.18`.
2. **Orbit.** Azimuth, elevation and distance each have their own spring. The orbit is smoothed, not the point in
   space. That is why the camera swings around the player on a shot change and does not fly through them.
3. **Arm.** `WorldProbe.armFraction` casts 8 rays from the player center to the wanted spot, one per corner of a
   cube the size of the wall gap. The result is the part of the way that is free of blocks. The camera goes there.
4. **Soft obstacle.** If the way is blocked by something no thicker than a block and the camera itself stands in
   free space, the arm is not shortened for up to `softOcclusionTime` seconds.
5. **Aim.** The look target of the shot, plus lead room (`leadRoom`), is smoothed (`lookLag`) and turned into a
   rotation without roll.
6. **FOV** is smoothed over 0.5 s.

What must not break:

- **No lava or powder snow** unless the player's head is in it: `armFraction` moves the camera back along the ray
  in half-block steps.
- **Never inside a block.** The camera is either on the free part of the ray or, in step 4, at a spot for which
  `spotFree` confirmed that the camera cube touches nothing.
- **The body center is visible**, except during the soft obstacle window: the camera is on the line from the
  center, before the first block.
- **The arm shortens at once and grows slowly** (0.6 s). Otherwise the camera would jerk outwards after every
  post.
- **No soft obstacle right after a cut** (`softArmed`) until the view was clear once. Otherwise a new shot could
  start with the player hidden.

Transitions:

- `snap`: a cut. All springs are set to their goals.
- `blend`: a fly-over. The springs keep their state but run 3.5 times slower for 1.6 s, so the move reads as one.
- `rebase`: a teleport. Anchor and aim move to the player, the orbit stays. Same angle.
- `adopt`: placed by hand. A cut, but the orbit comes from where the camera is, so it does not jump.

The spring (`Smooth`) is critically damped, the SmoothDamp formula: it does not overshoot and does not depend on
the frame rate. `SmoothAngle` always takes the short way around.

## Director: which shot and when

### Situation

`updateContext` picks one of eight situations every frame, first match from the top:

| Situation | Condition |
|---|---|
| `FLY` | flying with an elytra |
| `RIDE` | the player is a passenger |
| `COMBAT` | in the last 5 s the player hit a living entity or was hurt by one |
| `MINE` | breaking blocks for more than a second; the counter rises while working and falls half as fast without |
| `SWIM` | in water |
| `RUN` | faster than 4.8 blocks/s |
| `IDLE` | standing for more than 1.2 s |
| `WALK` | everything else |

**Tightness** is measured twice a second: 9 rays of 5 blocks from the player center. If less than 55% of their
length is free on average, the place is tight: distances are multiplied by 0.7 and far shots lose odds.

### Combat target

- The player hit a living entity: it becomes the target. The hit comes from Fabric's `AttackEntityCallback`, so
  controller swings that miss the crosshair count too.
- The player was hurt: the target is who did it, `player.getLastDamageSource().getEntity()`. The client gets the
  damage source from the server. Damage without a living attacker is no fight.
- While the player is dead the target is kept, so `DEATH` can show the killer.
- The target is dropped after 5 s without fighting, when it dies or when it is far away.

### Events

`detectEvent` returns `DEATH`, `FALL`, `MENU` or nothing, in that order. While an event runs its shot is shown and
the regular choice is off. A held shot turns events off.

**Fall** is detected two ways: `fallDistance > 5`, or the player is in the air, falls faster than 0.3 blocks per
tick and has more than 6 blocks of air below (`WorldProbe.groundDistance`). The second one fires at the start of a
fall. The event stays for a second after landing. The camera always cuts to `FALL`: a fly-over takes longer than
the fall.

**Menu** means a screen is open and Vivecraft knows where it is in the world (`GuiHandler.GUI_POS_ROOM`, turned
into world space by `VRPlayer.roomToWorldPos`). The controller puts that point into `Subject.guiCenter` every
frame. Any open screen counts; chat only with `menuShotChat` on. `Director.eventShot` tries both shoulders and
takes the one with more room. If the camera would still be closer than `minDistance`, `POV` is used instead.

While the game is paused, time keeps running for director and rig, or the camera would never reach the pause menu.
Only what belongs to the world stands still: a falling camera in `PHYSICS` and the wait after Bring camera to me.

Pressing Next shot or asking for a shot during an event marks it `dismissed`. It is not shown again until it ends.

### When a shot changes

Checked in order, the first match wins. Its name shows in the debug overlay as the reason.

| Reason | Condition | Transition |
|---|---|---|
| `start` | no shot yet, or the rig was reset | cut |
| `teleport` | teleport during a world shot | cut |
| `key` | Next shot was pressed | by setting |
| `asked for …` | `/vrcam shot` asked for a shot | by setting |
| `event over` | the event ended | by setting |
| `held shot ended` | a held world shot is blocked or finished | cut |
| `blocked` | the arm was below the threshold for longer than `occlusionCutTime` | cut |
| `finished` | the shot said it is done | by setting |
| `time` | its time is up | by setting |
| `unfit for …` | the shot does not fit the new situation, `minShotTime` has passed | by setting |
| `now …` | something new started, `minShotTime` has passed | by setting |

"Something new" compares activities, where `IDLE`, `WALK` and `RUN` are the same one. The rule only fires at the
**start** of a fight, flight, ride, swim or mining. The end of an activity changes nothing, or a single hit would
cause two changes in a row.

A shot asked for by command (`Shot.forced`) is exempt from `unfit for …` and `now …`.

The main idea: **obstacles are solved by changing the shot, not by finding a path.** The camera does not try to
fly around a wall. The director switches to an angle from which the player can be seen.

### Picking a shot

`choose` goes through every enabled shot with a weight above zero for the situation, each from both sides. For
each candidate `consider`:

1. Starts the shot and takes the wanted spot.
2. Measures the free part of the ray. A world shot below 0.9 is dropped: it only works at its intended spot.
3. Drops the candidate if the camera would be closer than the minimum distance.
4. Scores it: `weight × (0.35 + 0.65 × free part) × random 0.8–1.2`.
5. Applies penalties: head not visible (×0.6), camera and head on different sides of a water surface (×0.25), same
   type as the last shot (×0.25), view direction less than 30° from the current one (×0.5), camera on the other
   side of the line of movement (×0.35).

The highest score wins. All of this lives in the inner class `Director.Selection`. One is made per choice and
holds what the candidates share: the line of movement, the current side, the view direction.

Weight = `config weight × fit of the type for the situation × tightness factor × one-time bonus`. Fit and
tightness factor are tables in `ShotType`. There is one bonus (×4): `FLYBY` on elytra takeoff.

The 30° penalty is an editing rule: two similar shots in a row look like a glitch.

The line of movement is the direction of the player's velocity, or of the body while standing. The side of the
camera is the sign of its sideways offset from that line. A camera nearly on the line, in front or behind, has no
side and gets no penalty. Circling shots are exempt. This is the 180-degree rule: while the camera stays on one
side, the player moves the same way across the screen.

If nobody fits (`Director.fallback`), `POV` is used, which needs no room. If it is disabled, a close `SHOULDER` is
used and the rig keeps it out of walls. If the fallback is already showing it stays, without a new cut.

`POV` has no fit per situation: `Director.fit` gives it weight only in tight places. When there is room again, it
is replaced by `unfit for …`.

The `POV` camera stands in front of the face and not in the eyes because Vivecraft draws the whole player model,
head included, in the `CAMERA` pass. A camera inside the head would see it from inside.

A shot asked for (`Director.force`) goes the same way, but the candidates are only the two sides of that type,
whatever the situation and the `enabled` flag. With no room on either side the shot is still set, and the rig gets
it out of the walls.

Cut or fly-over with `transition: "auto"`: a fly-over with the chance `blendChance`, if neither shot is a world
shot and the camera has to go less than 130° around the player.

## CameraController: the link to Vivecraft

### Modes

- `OFF`: the mod leaves the camera alone.
- `DIRECTOR`: `Director` picks the shots.
- `FOLLOW`: one active own angle, no changes.
- `PHYSICS`: the camera is in a hand or lies where it fell. `Director`, `Rig` and `Shot` take no part.

### Taking and restoring settings

While the camera works (`engaged`) the mod keeps three things changed in Vivecraft: camera visibility, the camera
picture in the game window and the camera FOV. `engage` remembers the old values, `release` puts them back.

`release` is called:

- on switching to `OFF`;
- from `tick`, when VR stopped or the player left the world;
- when the game closes (`CLIENT_STOPPING`).

### Hot switch and losing VR

Vivecraft can turn VR off at any time: headset off (Hotswitching), VR disabled by hand, a render error.
`VRState.VR_RUNNING` goes false, `VRPlayer.preRender` is no longer called, and the tracker just stops getting
frames, without notice. When VR is turned off fully, `dh.vr`, `dh.vrPlayer` and `dh.vrRenderer` become null.

| Situation | What the mod does |
|---|---|
| VR is gone while the camera was on | `tick` (every client tick, also without VR) restores Vivecraft's settings and resets the rig. The mode is kept |
| VR is back | the tracker gets frames again, takes the settings again, the first shot is a cut |
| More than 0.5 s between frames | VR was paused: player data and rig are reset |
| Mode switch pressed without VR | the camera turns off instead of going to the next mode |
| Pause menu without VR while the camera is on | the mod's buttons are shown, so the camera can be turned off |
| Any access to `vrPlayer` | only through `isVRRunning()`, which checks it for null |

### Crash guard

`activeProcess` runs right before the frame is rendered for the headset. An exception there would throw the player
out of VR. So the body is in a `try`: the error is logged, the camera turns off, the player gets a message.

### Commands

`VrcamCommand` is registered with Fabric's client command API and calls the same `CameraController` methods as keys
and buttons. It never reaches the server. Vivecraft sends its quick commands through
`ClientPacketListener.sendCommand`, which Fabric intercepts, so `/vrcam` works from there as well.

The settings screen is opened on the next tick, not at once: the chat is still closing when the command runs.

## Camera in a hand

Applies to every mode.

- **Held.** While `CameraTracker.isMoving()` the mod does not place the camera and remembers that it was held.
- **Stabilization** (`HandStabilizer`). The pose `CameraTracker` computed, the raw pose of the hand, goes through a
  filter and is written back. The filter lag is `0.3 s × handStabilize`, divided by `1 + how far behind / soft
  threshold` (5 cm and 4°): small twitches are swallowed, a large move is caught up with fast. This does not add
  up over frames, because `CameraTracker` computes the pose from the hand again every frame and the mod's tracker
  runs after it. `HandThrow` gets the raw position, or a throw would lose speed.
- **Let go.** On the first frame after, `placedByHand` turns the camera position into an orbit around the player,
  writes it into the active own angle and saves the config. In `PHYSICS` the camera is dropped instead.
- **Throw.** While held, `HandThrow.sample` records the position over the last 0.12 s. On release it takes the
  hand speed minus the player speed. Below 2.5 blocks/s it is no throw. Otherwise the range is
  `0.3 × speed² × throwPower`, at most 24 blocks. The landing spot is clipped by blocks and written into the own
  angle; the rig starts from the hand (`adopt`) and flies there (`blend`).
- **Throwing the plain Vivecraft camera.** While the mod is off, `idleProcess`, which runs every frame for an
  inactive tracker too, watches the camera the same way and moves it to the landing spot with a spring over 0.3 s.
- **Bring camera to me** (`summon`). A following camera backs away when approached. `summon` puts it in front of
  the face and parks it for 20 s: the mod does not move it until it is taken or the time is up.

### Pulling

`CameraPull` is a `HeldInteractModule` with priority 760, right after Vivecraft's camera grab at 750.

- `isActive`: the camera can be pulled (`canPull`), the hand is 1.5 to 64 blocks from it, the hand points at it
  within 14° and the head within 35°. Once found, both angles get 40% slack, or a hand at the edge would buzz over
  and over. Vivecraft gives the short buzz itself when a module becomes active.
- `canPull`: in `PHYSICS` the camera has to be dropped. In `FOLLOW` and `DIRECTOR` it needs `pullAllModes`, a ready
  rig and a camera that is not held.
- `onHoldTick` sends a pulse every tick, rising in frequency and amplitude. After `pullSeconds` it calls
  `startPull`.
- `CameraController.flyToHand` moves the camera with a `SmoothVec` (0.12 s), past the `Rig`. The goal is not the
  hand but a point 0.16 blocks to its side along the player's view: left of a right hand, right of a left one,
  respecting `reverseHands`. So the hand grips the side and stays out of the lens.
- Within 0.15 blocks it calls `CameraTracker.startMoving`, which keeps that offset. From here it is a held camera.
- `onRelease` → `endPull` → `stopMoving`. Released before arrival: in `PHYSICS` the camera falls from where it is;
  in the other modes `rig.adopt` takes its position and `rig.blend` swings it back to the shot.

While a module is active, Vivecraft gives that hand's interact button to it. So the hand cannot attack or use
items while it points at the camera and the player looks at it. This is why `pullAllModes` exists: in `FOLLOW` and
`DIRECTOR` the camera is often in front of the player.

## PHYSICS mode

| State | Sign | Who moves the camera |
|---|---|---|
| waiting | `parkedTime > 0`, after switching the mode on or Bring camera to me | nobody |
| held | `CameraTracker.isMoving()` | Vivecraft, steadied and shaken by the mod |
| flying to a hand | `pullHand != null` | `CameraController.flyToHand` |
| falling | `DroppedCamera.isDropped()` and not `isResting()` | `DroppedCamera` |
| lying | `isResting()` | nobody; it turns for the first 1.5 s |
| carried | `isCarried()` | the entity it lies on |

- **Letting go.** `DroppedCamera.drop` gets position, rotation and the hand velocity from `HandThrow.velocity`.
  There is no speed threshold as for throws in the other modes: the camera always falls.
- **Falling** (`fall`). Gravity 16 blocks/s², little drag in air, a lot in fluids. Collision is one ray along the
  step, extended by the camera size. On a hit the camera is put on the surface; speed along the normal drops to
  35% and flips, along the surface to 55%.
- **Entities** (`trace`). The same ray is tested against the hitboxes of all entities that can be hit
  (`isPickable`: mobs, boats, minecarts; not items or arrows), except the player. The nearest hit of block and
  entities wins. The normal of a hitbox is the face the hit point is closest to. A camera let go of inside a
  hitbox, like a hand in the boat the player sits in, is put on top of it.
- **Coming to rest.** The camera stops when it hit a surface facing up and is slower than 0.9 blocks/s. On a block
  the support below is checked every frame; without it the camera falls on.
- **Riding** (`ride`). At rest on an entity the camera remembers it, its offset from it and its yaw. From then on
  the position follows the entity's interpolated position, and offset and rotation turn with its yaw. If the
  entity is gone, the camera falls on.
- **Kicks** (`getKicked`). A camera lying on a block looks every frame for an entity, the player included, whose
  hitbox covers it and that moves faster than 1.5 blocks/s horizontally (speed from `getX() - xo`). It takes that
  speed plus a push upwards and falls again. For 0.6 s it does not collide with what kicked it, or it would land
  on its head.
- **Rotation is not physics.** In the air the camera spins around a random axis, faster the faster it flies; every
  bounce picks a new axis. At the same time the rotation is pulled towards the rest pose.
- **Rest pose** (`restRotation`). Each drop picks a random pose: any heading and a clear tilt. The rest pose is a
  slerp between that and "lens at the focus" by `physicsAim`. At 0.75 the camera looks roughly at the player, but
  tilted. That is what makes the shot look dropped.
- **After stopping** the rotation goes to the rest pose for 1.5 s more and then freezes. The camera does not
  follow the player after that.
- **Death** (`CameraController.watchDeath`). At the moment of death the killer is taken from
  `getLastDamageSource()`, a held camera is let go of (`stopMoving`), a lying one gets 1.5 s more of turning
  (`settleAgain`). While the killer lives, the focus of the rest pose is the middle between body and killer
  (`restFocus`).
- **Shake** (`HandheldShake`). While held, the rotation is multiplied by a small one: sines for breath, steps (by
  `Subject.speed`) and damage (`hurtTime`). It does not add up, for the same reason as the stabilization.
- **Impacts** (`DroppedCamera.Impact`, `pollImpact`). A hit faster than 1 block/s along the normal, and every
  kick, is recorded and picked up by the controller once per frame. `CameraEffects.impact` plays the hit sound of
  the block and spawns its particles, `POOF` for an entity. Particles cannot be hidden from one render pass, so
  their directions are mirrored into the half-space behind the lens.
- **FOV jolt** (`fovOffset`). An impact starts a damped swing: up to 12°, 0.2 s, zooming in first. In `PHYSICS`
  the controller writes `handCameraFov = previousFov + fovOffset()` every frame.
- **Leash.** Further than 40 blocks from the head the camera comes back through `summon`. A teleport alone does
  not bring it back: teleporting away from a lying camera is a normal way to step into the picture.
- **Marker.** The Vivecraft camera model stays visible and the red dot is not drawn. The icon works.

## Marker and camera icon

Two mixins:

- `VRWidgetHelperMixin` in `VRWidgetHelper.extractVRHandheldCameraWidget` hides the Vivecraft camera model.
- `LevelExtractorMixin` at the head of vanilla `LevelExtractor.extractGizmos` calls `drawHeadsetAids`, only in the
  eye passes (`LEFT`, `RIGHT`). That draws the marker (dot and label) and the camera icon with the distance through
  `Gizmos`. They are not drawn in the `CAMERA` pass, so they are not in the recording.

**Why `extractGizmos`.** The game takes the collected gizmos once per pass, in `extractGizmos`. Whatever is added
later in the same pass is drawn in the next one. Vivecraft collects its own state, and calls
`extractVRHandheldCameraWidget`, at the very end of `GameRenderer.extract`, after that point. The marker used to be
added from there. What was added in the left eye pass was drawn in the right eye, and what was added in the right
eye pass was drawn in the camera pass after it, so in the recording. In the headset the marker showed in one eye.
Vivecraft's own debug rendering is added at the same place and only looks right with two passes because the next
pass after the right eye is the left eye of the next frame.

**The icon is a font glyph.** `Gizmos` can draw lines, points and text in the default font, no textures. So the
icon is added to the default font as one glyph from the private use area (`U+E7C0`):
`assets/minecraft/font/default.json` with one bitmap provider, the picture in
`assets/vrcamera/textures/font/camera.png`. Minecraft merges font definitions of all resource packs, so the file
adds a glyph and replaces nothing.

**Where it is drawn** (`drawIndicator`). The vector from headset to camera is split into right, up and forward of
the head.

- Camera within 35° of the view direction: the icon goes above the camera, in world space.
- Otherwise: 0.6 blocks in front of the face, 30° off the view center towards the camera. 30° is less than 35°, so
  when the camera leaves the view the icon does not jump. It slides to the edge and stays there.

**Size.** Text of scale 1 in `Gizmos` is half a block tall. The scale of icon and label is multiplied by the
distance from the head to where they stand, so they look the same size to the eye.

Both texts are drawn on top of everything (`setAlwaysOnTop`), or a wall between player and camera would hide the
icon. They are drawn again for each pass from the current head pose, so an icon stuck to the edge does not lag.

The mixin has `require = 0`: if the method is renamed, only marker and icon are lost and the game still starts.

## Building for several Minecraft versions

- One source tree for all versions. The Gradle property `mc` picks the version: the default is in
  `gradle.properties`, on the command line it is `-Pmc=26.3`.
- `build.gradle` reads `versions/<mc>.properties`: versions of Minecraft, Fabric API, Vivecraft, Cloth Config and
  Mod Menu.
- The Minecraft version goes into the jar name and into the `minecraft` dependency in `fabric.mod.json`.
- `buildAll` runs one nested build (`GradleBuild`) per file in `versions/`, one after the other.

Differences between 26.2 and 26.3 so far:

| Difference | Solution |
|---|---|
| Key codes differ, `org.lwjgl.glfw` is not available in 26.3 | codes come from the game's `InputConstants` |
| The field `LivingEntity.swinging` became a method in 26.3 | hits come from Fabric's `AttackEntityCallback` |
| `Quaternionf.dot` takes a `Quaternionf` in 26.2 and a `Quaternionfc` in 26.3 | the dot product is written out |

The rule: use no API that is missing in one of the supported versions. The check is a build for each version.

## Config

- One `CameraConfig` object, written by Gson to `config/vrcamera.json`.
- Loaded at game start and again when the camera is switched on from `OFF`. That picks up edits to the file.
- `fillDefaults` adds missing shots and fields, so old files survive updates.
- The settings screen changes fields of **the same object** that `Director` and `Rig` read. Changes apply at once,
  without restarting the camera.
- Own angles are the list `presets` and the index `activePreset`. The old field `custom` is moved into the list on
  load.
- `version` and `CameraConfig.migrate`. When a default turns out bad and is changed, players already have the old
  one in their file. `migrate` moves only values that equal the old default exactly, so the player never touched
  them. Files without `version` count as version 1.

## Optional dependencies

- **Cloth Config.** `ConfigScreen` refers to its classes, so `ConfigScreen` is only touched after
  `ConfigScreen.isAvailable()`.
- **Mod Menu.** `ModMenuIntegration` is only loaded by Mod Menu itself, through the `modmenu` entry point. In the
  dev environment Mod Menu is `compileOnly`.

## Adding a shot

1. Add a value to `ShotType`: defaults, tightness factor, minimum distance and eight weights, one per situation in
   the order of `Context`. All zeros means the shot is never picked on its own, only by an event.
2. For special behavior add a branch in `Shot.update`, and in `wantedAzimuth`, `finished` or `position` if needed.
3. Add `vrcamera.shot.<name>` and `vrcamera.shot.<name>.tooltip` to all language files.

Config and settings screen pick the shot up on their own: `fillDefaults` creates the entry in `shots`, the screen
is built by going through `ShotType`.

## Adding an event

1. A value in `Director.Event` with its `ShotType`.
2. A condition in `Director.detectEvent`.

## Adding a setting

1. A field with a default in `CameraConfig`.
2. An entry in `ConfigScreen`.
3. `vrcamera.option.<field>` and `vrcamera.option.<field>.tooltip` in all eight language files.
4. A row in both READMEs.

## Testing

There are no automated tests. A change is checked by building for every supported version and then by hand in a
headset.

## Known weak spots

- **Internal Vivecraft classes.** Listed at the top. A Vivecraft update can break the build or the behavior.
- **Pose one frame late.** A mixin at the head of `VRPlayer.preRender` could fix it.
- **Vivecraft settings on disk.** If Vivecraft saves its config while the camera works and the game crashes, the
  FOV and mirror setting of the mod stay in its file.
- **Anchor lag compensation.** On a sudden stop the camera can overshoot a little.
- **Physics collisions.** One ray per step. A fast camera can clip a corner.
- **Killer on the client.** Taken from the last damage source the server sent. If the server sends none, the
  camera only looks at the body.
- **Body after death.** The model of a dead player disappears after about a second, the death shot runs until
  respawn. With a killer in frame that works; without one the camera circles an empty spot.
- **Marker size.** The unit of the point size in `Gizmos.point` is unknown, the setting is tuned by eye.
