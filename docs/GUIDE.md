# VRCamera user guide

[Back to README](../README.md) · [Русская версия](GUIDE_RU.md)

Detailed controls, camera behaviour and configuration.

All camera distances scale with the player's `scale` attribute.
The mod is translated into English, Russian, Ukrainian, German, French, Spanish,
Brazilian Portuguese and Simplified Chinese.

## Controls

| Action | Key | In the headset |
|---|---|---|
| Switch mode | `F8` | button in the pause menu |
| Next shot | `F9` | "VR Camera..." screen |
| Hold the current shot | `F10` | "VR Camera..." screen |
| Next own angle | `F7` | "VR Camera..." screen |
| Take a photo | `F6` | bind it to a controller button, or `/vrcam photo` |
| Bring camera to me | unbound | "VR Camera..." screen |
| New own angle | unbound | "VR Camera..." screen |
| Delete own angle | — | "VR Camera..." screen |
| Debug overlay | unbound | "VR Camera..." screen |
| Settings | — | "VR Camera..." screen, needs Cloth Config |

Keys are regular Minecraft key bindings. Rebind them in the controls settings, or bind them to controller buttons
in SteamVR. They work while the game window has focus and no menu is open, so someone at the computer can run the
camera for the player in the headset.

### Commands

You can also use `/vrcam`, which supports tab completion. The command runs on the client and needs no permissions.

| Command | What it does |
|---|---|
| `/vrcam`, `/vrcam status` | prints what the debug overlay shows |
| `/vrcam off`, `director`, `follow`, `physics` | switches to that mode |
| `/vrcam mode` | next mode |
| `/vrcam next` | next shot |
| `/vrcam hold` | holds or releases the current shot |
| `/vrcam shot <name>` | shows a shot: `shoulder`, `front`, `orbit`, `flyby`, `crane`, `low`, `hands`, `duel`, `death`, `fall`, `pov`, `menu`, `custom`. Turns the director on if needed |
| `/vrcam summon` | brings the camera to you |
| `/vrcam photo` | takes a photo |
| `/vrcam preset next`, `new`, `delete` | own angles |
| `/vrcam preset <number>` | picks an own angle, counted from 1 |
| `/vrcam debug` | debug overlay |
| `/vrcam settings` | settings screen, needs Cloth Config |
| `/vrcam reload` | reads the settings file again |

`/vrcam shot` shows a shot even if it is disabled or does not fit the situation. It lasts as long as usual, then
the director carries on. To keep that shot, follow it with `/vrcam hold`.

In the headset, put the commands into Vivecraft's Quick Commands. They then show up under **Commands** in the pause
menu, and each can be bound to a controller button in SteamVR.

## Camera in your hands

The following controls work in every mode.

**Grab and place.** Reach for the camera and hold the interact button, like with the plain Vivecraft camera. When
you let go:

- in Follow the camera stays at that spot relative to you;
- in Director it keeps that angle for `manualHoldSeconds`, then the director carries on;
- in Physics it falls.

Only the position is remembered. The camera still looks at the player.

**Throw.** Swing and let go. The camera flies in that direction, stops at walls and stays where it lands. A faster
swing goes further. A swing slower than 2.5 blocks per second does not count as a throw. `throwPower` sets the range, 0 turns throwing
off. This also works on the plain Vivecraft camera while the mod is off.

**Pull from afar.** Look at the camera and point a hand at it: the controller gives a short buzz. Hold the interact
button. The buzz grows, and after `pullSeconds` (1.25 by default) the camera flies into your hand. It stays there
until you let go. The hand takes it by the side, so it does not cover the lens. Works from 1.5 to 64 blocks.

In Director and Follow the camera often hangs in front of you, and a hand may catch it by accident. If that
happens, turn `pullAllModes` off: pulling then only works in Physics.

**Bring camera to me.** A following camera backs away when you walk up to it. This button puts it at arm's length
in front of your face for 20 seconds.

**Stabilization.** Your hand can tremble or twitch when the other hand breaks a block. The mod smooths those movements out while
letting deliberate movements through. Only the picture is steadied: in the headset the camera stays right in your
hand. Adjust the strength with `handStabilize`; 0 turns it off.

**Hidden arm.** The arm that holds the camera is right next to the lens, so it is not drawn in the picture. It
shows again when the camera looks at you, like in a selfie. `hideHoldingArm` turns this off.

### Own angles

You can keep several hand-placed angles. Placing the camera by hand always writes into the active one.

- **New angle** copies the active one and makes the copy active. Then place the camera.
- **Next own angle** cycles through them, the camera flies over.
- **Delete angle** removes the active one. The last one stays.

The usual sequence is: New angle → Bring camera to me → grab, place, let go.

## Physics mode

For found footage and horror, this mode makes the camera an object you hold.

- Hold it, and it films where your hand points. In the headset it looks like the Vivecraft camera with a screen.
- Let go, and it falls, bounces and tumbles. It keeps the speed of your hand, so you can throw it.
- Let go slowly right above a block, and it is put down: it stays as you held it and films where you aimed it.
- A camera on the ground needs a fast swing of the hand to be hit. Reaching for it does not knock it away.
- While it comes to rest it turns its lens towards you and stays tilted. Without that it would film the ground or
  the sky most of the time. `physicsAim` sets how much: 0 leaves it as it fell, 1 looks straight at you.
- Once it lies still it stops following you. Walk away and step into the picture.
- It bounces off mobs, boats and minecarts like off blocks. Landing on a boat or minecart, it rides along. You can
  drop it in the boat you sit in.
- Whoever walks into a lying camera kicks it away: you, a mob, a boat. A slow step does not.
- Hands and feet hit a dropped camera, in the air or on the ground: kick it, or keep it up like a volleyball. A
  camera falling onto a still hand bounces off it. Feet need full body tracking. `kickPower` sets how hard, 0 turns
  it off.
- A held camera sways with your breath, more with your steps, and jolts when you get hurt. `physicsShake` sets how
  much.
- When you die you drop it. It turns to get the body and the killer into the picture.
- On impact the zoom jolts, you hear the block and its dust flies. The dust only spawns behind the lens: you see
  it, the camera does not.
- It sinks slowly in water and lava. It keeps falling if the block below it is broken.
- Under water it films with a wider angle, also while you hold it. A sinking camera rolls slowly from side to side
  instead of tumbling, and leaves bubbles. `underwaterLook` turns this off.
- Further than 40 blocks away it comes back to you, Vivecraft hides a camera that far off.
- Switching the mode on puts the camera in front of your face for 20 seconds. If you do not pick it up, it falls.

The director, own angles and events do not run in this mode.

## Shots

| Key | What it does | Picked more often |
|---|---|---|
| `shoulder` | from behind, over the shoulder | moving, flying, tight places |
| `front` | from the front, backing away; moves in while you stand | idle, walking |
| `orbit` | slowly circles the player | idle, combat |
| `flyby` | stands ahead on your path and pans as you pass, zoomed | running, flying, riding |
| `crane` | rises high behind the player | open places, flying |
| `low` | from the ground, looking up | combat, running |
| `hands` | close-up of the hands | mining, idle |
| `duel` | over your shoulder at the opponent, both in frame | combat with a target |
| `death` | circles the dead player and pulls back | on death only |
| `fall` | almost straight from above | on a long fall only |
| `pov` | first person: right in front of the face | where there is no room for anything else |
| `menu` | close over the shoulder at an open menu | while a menu is open |
| own | an angle placed by hand | Follow mode |

## How the director works

**Situation.** The director knows what you are doing: standing, walking, running, flying with an elytra, riding,
swimming, fighting, mining. It also knows if the place is tight. These affect the odds of each shot and the
distances: closer in tight places, further when flying.

**Changing shots.** A shot changes when:

- its time is up;
- walls pushed the camera too close;
- something new started: a fight, flight, ride, swim, or more than a second of mining;
- you pressed Next shot.

Shot changes use either a cut, which puts the camera at the new spot immediately, or a fly-over that swings around the
player. Two similar shots in a row are avoided. The camera also tries to stay on one side of the line you move
along, or you would run right in one shot and left in the next.

On a teleport the camera jumps with you and keeps the angle. Only `flyby` is replaced, because it stands still.

Use Hold (`F10`) to stop shot changes and events until you release it.

**Combat.** A fight starts when you hit a living thing, including with a controller swing, or when one hurts you. That entity
becomes the target. Fall and fire damage do not count. The target is dropped after 5 seconds without fighting, when
it dies or when it is far away.

**Events.**

- **Death:** `death` until you respawn. If someone killed you, the killer stays in frame.
- **Long fall:** `fall` cuts in once you drop with more than 6 blocks of air below, and stays a second after
  landing.
- **Elytra takeoff:** `flyby` gets better odds for the next change.
- **Open menu:** `menu` while an inventory, chest, pause menu, settings screen or chat is open. Vivecraft shows
  menus in the world, so the menu and the player are both in frame. The camera takes the shoulder with more room.
  With no room behind either, it switches to first person. `menuShotChat` turns this off for chat.

If the shoulder covers the menu, lower the angle around (`azimuth`) of the `menu` shot: 180 is straight behind,
lower is more to the side. The default is 138.

**Lead room.** While you move the camera aims a bit ahead of you, to leave room in the direction of travel.
`leadRoom: 0` aims at the middle of the body again.

## How the camera avoids blocks

- Eight rays go from the middle of the body to where the camera wants to be. If a block is in the way, the camera
  stops in front of it.
- It moves in at once and back out slowly.
- A thin obstacle, like a trunk or a post, is ignored for up to `softOcclusionTime` seconds if the camera itself
  stands in free space. It never enters a block.
- It stays out of lava and powder snow unless you are in them.
- Pushed too close for longer than `occlusionCutTime`, the director switches the angle.

The camera does not search for a route around obstacles. Instead, the director picks an angle from which you can be seen.

With no room for any angle, like in a one-block tunnel, `pov` takes over. The camera stands a third of a block in
front of the face, not in the eyes: there it would see the head of the player model from inside. `distance` of
that shot sets how far in front.

## Photos

Hold the camera, then hold the other button of the same hand: the trigger while you grip the camera, or the grip
while you hold it with the trigger. The controller buzzes harder and harder, and after `photoHoldSeconds`
(1 by default) the shutter clicks. The next photo has to wait until the sheet of this one has left the camera.

`photoGesture` picks the gesture:

| Value | Gesture |
|---|---|
| `"same_hand"` | the other button of the hand that holds the camera. Needs SteamVR; found by what else that button is bound to, so it has to be bound to something |
| `"other_hand"` | bring the other hand to the camera and hold its interact button. To pass the camera over, hold on with the second hand and let go with the first |
| `"off"` | key and command only |

Without a free hand, or when the camera is not held: `F6`, a controller button you bound to it, or
`/vrcam photo`. Photos work in every mode while the camera is on.

- The photo is what the camera films at that moment, in full size, saved to `screenshots/vrcamera`. The mod never
  deletes these.
- The shutter clicks and the view jolts for a moment. The jolt is not in the photo.
- A sheet with the photo slides out below the camera, hangs there for a moment, then flutters to the ground. You
  see it in the headset and in the recording. Other players do not: the mod runs on your client only.
- `photoSheet: false` only saves the photo.

### Sheets

- **Developing.** A fresh sheet is blank, the picture comes through over a few seconds.
- **Pick up.** Reach for a sheet and hold the interact button, like with the camera. The other hand can take it
  out of the first the same way.
- **Throw.** Let go while moving your hand.
- **Pin.** Hold the back of the sheet against a block and let go slowly: it sticks there, turned the way you held
  it. Upright, on its side for a portrait, or at an angle; within 7° of straight it is made straight. Signs and
  banners work too, and on a head, standing sign or banner the sheet follows the way that is turned. Pick it up
  again to move it.
- **Icon.** A sheet that falls or lies around has an icon with the distance, like the camera. Pinned ones do not.
- **Fire and lava** burn a sheet.
- **Coming off.** When the block a sheet is pinned to is broken, it falls. An explosion blows sheets off and away,
  each a bit differently. A sheet that came off is loose again: pin it again or it is forgotten.
- **Kept or not.** Only pinned sheets are kept: they are there again the next time you play this world. A sheet
  you left lying is forgotten when its chunk unloads or you leave, and at most 12 lie around at once. The photo
  itself stays in `screenshots/vrcamera` either way.

Sheets work while the camera is off as well.

Pinned sheets and their small pictures are kept per world in `vrcamera/sheets`. When a singleplayer world is
deleted, its folder there is removed at the next game start. Folders of servers stay: a server that is gone cannot
be told from one you are just not on. If a picture is missing from the folder, its sheet is dropped.

## Marker, icon and debug overlay

**Marker.** The Vivecraft camera model with its screen shows where the camera is in the headset. It is not in the
recording. `marker` picks `"model"`, `"dot"` (a red dot with `REC` and the name of the shot) or `"none"`.

**Camera icon.** A camera icon with the distance in blocks, like a waypoint: seen through walls, same size at any
distance, in both eyes. When the camera is out of view the icon sticks to the edge of the view on that side. It is
hidden while the camera is closer than 1.2 blocks and in first person. Not in the recording. `indicator` toggles
it, `indicatorSize` sets the size.

**Debug overlay.** Shows what the camera is doing, right on the HUD.

| Line | Meaning |
|---|---|
| `VRCamera DIRECTOR` | mode; `(waiting for VR)` VR is not running; `(parked 12s)` waiting to be picked up |
| `shot: ORBIT left 3.2/9.5s` | shot, side, how long it runs and will run; `HOLD` when held |
| `why: time, blend` | why this shot was picked and how the camera got there |
| `context: RUN tight` | situation; `tight` place; `event FALL` |
| `blocked: 0.4s` | how long walls have been pushing the camera in |
| `arm: 100%` | how much of its distance the camera has; `(looking past)` a thin obstacle |
| `fov`, `speed`, `scale` | field of view, your speed, your size |
| `target: Zombie` | combat target |

Reasons in `why`:

| Reason | When |
|---|---|
| `start` | first shot after turning on or VR coming back |
| `time` | time was up |
| `blocked` | walls pushed the camera in |
| `finished` | the shot ended on its own: `flyby` you left, `duel` the target is gone |
| `now COMBAT` | something new started |
| `unfit for SWIM` | the shot does not fit the new situation |
| `key` | Next shot was pressed |
| `teleport` | teleport during `flyby` |
| `event FALL`, `event over` | an event started or ended |
| `manual` | placed by hand or an own angle was picked |

## Settings

**In the game.** Needs Cloth Config. Open it from the "VR Camera..." screen, or from the mod list with Mod Menu.
Every number has a slider, so you can adjust it without typing in VR. Save to apply your changes.

**File.** `config/vrcamera.json`, created on first start. It is read again when the camera is switched on from Off,
and by `/vrcam reload`.

### General

| Field | Default | Meaning |
|---|---|---|
| `forceMirror` | `true` | show the camera picture in the game window while the camera is on |
| `marker` | `"model"` | `"model"`, `"dot"` or `"none"` |
| `markerLabel` | `true` | name of the shot next to the dot |
| `markerSize` | `14` | size of the dot |
| `indicator` | `true` | camera icon with the distance |
| `indicatorSize` | `1.0` | size of the icon |
| `throwPower` | `1.0` | throw range multiplier, 0 = no throwing |
| `handStabilize` | `0.5` | steadying of a held camera, 0 = off, 1 = most |
| `hideHoldingArm` | `true` | keep the arm that holds the camera out of the picture, except in a selfie |
| `pullSeconds` | `1.25` | seconds to hold the button to pull the camera, 0 = no pulling |
| `pullAllModes` | `true` | `false` = pulling only in Physics |
| `physicsAim` | `0.75` | Physics: how much a dropped camera turns to the player, 0 to 1 |
| `physicsShake` | `1.0` | Physics: sway of a held camera, 0 = off |
| `kickPower` | `1.0` | Physics: how hard hands and feet hit a dropped camera, 0 = they pass through |
| `underwaterLook` | `true` | Physics: wider angle, slow roll and bubbles under water |
| `menuShotChat` | `true` | the `menu` shot for chat as well |
| `photoSheet` | `true` | a taken photo comes out of the camera as a sheet; `false` only saves it |
| `photoGesture` | `"same_hand"` | `"same_hand"`, `"other_hand"` or `"off"` |
| `photoHoldSeconds` | `1.0` | seconds the button of the photo gesture is held, 0 = at once |
| `debugOverlay` | `false` | debug overlay on the HUD |

### Motion

| Field | Default | Meaning |
|---|---|---|
| `aimHeight` | `0.6` | where to aim: 0 = feet, 1 = head |
| `faceDistance` | `1.25` | blocks; a camera closer than this aims at the face, twice as far at the body, 0 = always the body |
| `positionLag` | `0.35` | seconds to catch up with the wanted position |
| `lookLag` | `0.12` | seconds for the aim to catch up with the player |
| `turnLag` | `0.9` | seconds to swing around when the player turns |
| `turnDeadzone` | `12` | degrees you can turn without moving the camera |
| `speedFov` | `true` | wider field of view at high speed |
| `leadRoom` | `0.25` | seconds of movement the camera aims ahead, 0 = off |
| `leadRoomMax` | `0.8` | limit of that offset, in player sizes |

### Collision

| Field | Default | Meaning |
|---|---|---|
| `collisionRadius` | `0.15` | gap between camera and walls, in blocks |
| `collisionMargin` | `0.12` | extra gap to a block in the way |
| `softOcclusionTime` | `0.35` | seconds a thin obstacle is ignored, 0 = off |
| `occlusionRatio` | `0.45` | part of the distance below which the camera counts as pushed in |
| `occlusionCutTime` | `0.6` | seconds pushed in before the angle changes |

### Director

| Field | Default | Meaning |
|---|---|---|
| `transition` | `"auto"` | `"auto"`, `"cut"` or `"blend"` (fly-overs only) |
| `blendChance` | `0.6` | chance of a fly-over with `"auto"` |
| `minShotTime` | `2.5` | least seconds before a change because the situation changed |
| `manualHoldSeconds` | `20` | how long the director keeps a hand-placed angle |
| `orbitSpeed` | `14` | circling speed, degrees per second |
| `events` | `true` | special shots for death and falling |
| `activePreset` | `0` | number of the active own angle, counted from 0 |
| `customInRotation` | `false` | own angles take part in the director's rotation |

### Per shot

`shots` has one entry per shot, `presets` lists the hand-placed angles.

| Field | Meaning |
|---|---|
| `enabled` | use the shot or not |
| `weight` | relative odds |
| `azimuth` | degrees around the player: 0 in front, 180 behind. Left or right is random |
| `elevation` | degrees above the horizon |
| `distance` | blocks, for a player of regular size |
| `fov` | field of view |
| `minDuration`, `maxDuration` | length of the shot in seconds |

Exceptions:

- `flyby`: `distance` is how far ahead the camera stands, the angles are not used.
- `duel`: `azimuth` is measured from the direction to the opponent.
- own angles: `azimuth` is signed, −180 to 180, and not mirrored.

### What to change

| You see | Change |
|---|---|
| The camera swings when you turn your head | raise `turnDeadzone` and `turnLag` |
| The camera is sluggish | lower `positionLag` |
| Too many changes in tight places | raise `occlusionCutTime`, lower `occlusionRatio` |
| The camera twitches near trees and posts | raise `softOcclusionTime` |
| You disappear behind trees | lower `softOcclusionTime` or set 0 |
| Shots are too short or too long | `minDuration`, `maxDuration` of the shot |
| You want only cuts or only fly-overs | `transition` |
| You are off-center when running | `leadRoom: 0` |
| The held camera shakes | raise `handStabilize`, lower `physicsShake` |
| The hand catches the camera by accident | `pullAllModes: false` |
| You don't like a shot | `enabled: false` on it |

## Headset off, VR gone

With Vivecraft's **Hotswitching** on, taking the headset off switches the game to non-VR. VR can also stop when you
turn it off or on a render error.

- The game window then shows plain first person. That is Vivecraft: without VR there is no handheld camera.
- The mod puts Vivecraft's settings back and waits. The mode is kept.
- When VR is back, the camera turns on again and starts with a new shot.
- Pressing `F8` while VR is off turns the camera off.

For streams, turn Hotswitching off so the game stays in VR and the camera keeps filming.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "VR is not running" | VR is off in Vivecraft or the headset was not picked up |
| "not available in seated mode" | switch Vivecraft to standing |
| The game window is black | Desktop Mirror is OFF |
| The window shows first person | `forceMirror` is off, or VR is not running right now |
| No camera model in the headset | check `marker`: `"model"` shows the Vivecraft camera, `"dot"` a red dot |
| The camera is behind you in `front` | a bug, please report it |
| You can't reach the camera | Bring camera to me, or pull it |
| "internal error, camera turned off" | the mod caught its own error and stopped, to not throw you out of VR. See `logs/latest.log` |
| No Settings button | Cloth Config is missing |
| Keys do nothing | the game window has no focus, or a menu is open |

## Known limits

- The mod uses Vivecraft's internal classes, not only its public API. A Vivecraft update can break it.
- The camera pose is one frame late: Vivecraft reads it before it runs the trackers.
- While the camera is on, the mod changes Vivecraft's camera field of view and mirror setting, and puts them back
  when it turns off. If Vivecraft saves its config in that time and the game crashes, the changed values stay in
  Vivecraft's file.
- While you look at the camera and point a hand at it, that hand's interact button belongs to the pull gesture.
- A pulled camera flies through walls.
- The body of a dead player disappears after about a second. The death shot keeps running until respawn.
- There are no automated tests.
