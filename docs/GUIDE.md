# VRCamera user guide

[Back to README](../README.md) · [Русская версия](GUIDE_RU.md)

Detailed controls, camera behaviour and configuration.

**Required:** turn on **Show Playermodel** in Vivecraft (VR Settings → Playermodel Settings). Without it your
character is missing from the camera's picture.

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
| VR Camera menu | unbound | radial menu or a controller button |
| Settings | unbound | "VR Camera..." screen, needs Cloth Config |

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
| `/vrcam load <address>` | puts a picture from the internet on a sheet |
| `/vrcam preset next`, `new`, `delete` | own angles |
| `/vrcam preset <number>` | picks an own angle, counted from 1 |
| `/vrcam debug` | debug overlay |
| `/vrcam settings` | settings screen, needs Cloth Config |
| `/vrcam pace alpha`, `default`, `faster` | sets the pace of the director, see Settings |
| `/vrcam screen director`, `follow`, `off` | the camera without VR, in the game window |
| `/vrcam screen steer` | take the camera over with the keys you walk with, or give it back |
| `/vrcam chroma` | green screen on or off |
| `/vrcam reload` | reads the settings file again |

`/vrcam shot` shows a shot even if it is disabled or does not fit the situation. It lasts as long as usual, then
the director carries on. To keep that shot, follow it with `/vrcam hold`.

In the headset there is no keyboard. Two ways to reach all of this from VR:

- **Radial menu.** Every key above can be put into Vivecraft's radial menu: VR Settings → Radial Menu..., click a
  slot and pick the key from the "VR Camera" group. Keys without a keyboard key work there as well. Good ones to
  have at hand: Hold current shot, Next shot, Switch camera mode, Bring camera to me, Take a photo. Or just one:
  **Open VR Camera menu**, the screen with all of them.
- **Controller buttons.** The same keys show up in the SteamVR controller bindings of Vivecraft and can be bound
  to a button directly.

Vivecraft's Quick Commands take the `/vrcam` commands too. They then show up under **Commands** in the pause
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
button and the camera comes to your hand like by telekinesis: it stirs, leaves its place and gets faster, following
the hand wherever you move it. The buzz grows as it comes closer. The whole way takes `pullSeconds` (1.25 by
default) from any distance. Held to the end, it lands in your hand and stays there until you let go. The hand
takes it by the side, so it does not cover the lens. Works from 1.5 to 64 blocks.

- Let go on the way in Physics and the camera flies on with the speed it had, falls and rolls.
- Swing the hand as you let go and the camera is flung the way the hand went, in any mode. `throwPower` sets how
  far.
- Let go on the way in Director or Follow and it stays where it got to, like a camera you put there by hand.
- Let go right away, before it left its place, and nothing happens.
- A camera lying on the ground comes up in an arc.

`pullStyle: "instant"` is the way it was before: hold the button for the whole time, then the camera comes at once.

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

**Putting the camera up.** In the physics mode the camera can not only be put down but put up as well. Hold it
right against something and let go slowly:

- **A wall or ceiling:** it stays there, turned the way you held it. It falls when the block breaks or it is hit.
- **A mob or player:** it rides along and sways while they walk, like in a hand (`physicsShake`). Near the head it
  turns and nods with the head, on the body with the body.
- **Your own forehead:** hold it to your head. It does not stick to the rest of your own body, to not catch by
  accident.

Take it off by grabbing it or by knocking it off.

A camera that sinks onto a fish, a squid, a dolphin or an axolotl is taken along by it, on its head. Whatever
the camera is put on makes its sound when that happens.

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

### Pictures from the internet

`/vrcam load <address>` puts a picture from the web on a sheet and drops it in front of you. From there it is a
sheet like any other: pick it up, pin it.

- PNG, JPEG or the first frame of a GIF, up to 8 MB, `http` and `https` only.
- The picture is cut from its middle to the nearest shape a sheet can have: 16:9, square or 9:16.
- The file as it was downloaded is kept in `screenshots/vrcamera/custom`.
- Only your own game opens the address, and the site sees your IP address, like in a browser. Other players never
  get the address, only the small picture the server made of it.

On a server such a sheet is marked as custom. Other players see a black sheet labelled "Picture hidden by your settings", with the setting to turn on below it, in
its place, and their game does not download the picture, unless they turned `showCustomPhotos` on. It is off by
default: custom pictures are not photos of the game and can show anything.

The mark is set by the client that loads the picture. It keeps honest players apart from the rest; it is not a
guard against someone who changes their game to lie about it. The server can't tell a screenshot from any other
picture.

The server decides who may: the permission `vrcamera.custom`, which everyone has by default, and
`custom-pictures` in the plugin's config, which turns it off for all. `/vrcamsync purgecustom` removes every
pinned custom picture.

### Sharing photos on a server

On a server that runs the **VRCameraSync** plugin (Paper), pinned sheets are shared: everyone with the mod sees
them, and they stay when you log off. The plugin jar is built together with the mod, `vrcamera-paper-plugin-<version>.jar`
in `build/libs/`. Put it into the server's `plugins` folder. Players without the mod see nothing and are not
affected.

- Pinned sheets are kept by the server and stay.
- Loose sheets, in a hand, falling or lying around, are shown to the others too, but only held in the server's
  memory: at most 8 per player, gone when their owner leaves, changes the world or after 10 minutes untouched.
- Anyone in VR can pick up a loose sheet of someone else. It is theirs from then on, and they can pin it.
- A player who is not in VR takes a photo of what they see with the photo key (`F6`). The sheet drops in front of
  them. They can't pick it up again, a player in VR can. They keep at most 3 lying around, older ones go.
- You take your own sheets off. Others' sheets only if the server lets you.
- When the block a sheet is pinned to is broken or blown up, it falls for everyone.
- The server may refuse a pin: too many of your own, too many in that spot, or too fast. The sheet then comes off
  again and a message says why.
- `showOthersPhotos: false` shows only your own. Yours are still shared.
- Players within 32 blocks see your camera where it is, in your hand or flying, with a camera icon and your name over it.
  `shareCamera: false` hides it from them.
- A player with the mod who is not in VR sees the pinned sheets and the cameras too, but can't pick anything up.

What keeps it light:

- A client is told about a sheet in a few dozen bytes when it comes within 32 blocks. The picture itself is only
  fetched within 16 blocks, and dropped from memory beyond 24.
- Pictures travel as JPEG, 256 pixels across, about 10 KB. Each is fetched once and then kept on disk in
  `vrcamera/remote`, at most 1000 of them.
- At most 48 shared pictures are in memory at once, the nearest ones.
- The server sends a player 4 pictures per second at most.

Server settings, `plugins/VRCameraSync/config.yml`:

| Setting | Default | Meaning |
|---|---|---|
| `limits.per-player` | `64` | sheets one player may have pinned |
| `limits.per-chunk` | `16` | sheets in one chunk, of all players together |
| `limits.loose-per-player` | `8` | loose sheets of one player the others see; `0` keeps them to their owner |
| `limits.loose-minutes` | `10` | minutes after which an untouched loose sheet is gone |
| `limits.total` | `20000` | sheets on the whole server |
| `limits.image-bytes` | `20000` | largest picture a client may send |
| `limits.pin-cooldown-ms` | `1500` | wait between two pins of a player |
| `anyone-takes-off` | `false` | `true` lets everyone take off anyone's sheets |
| `custom-pictures` | `true` | `false`: nobody may put up pictures from the internet |
| `worlds.mode`, `worlds.list` | `deny`, empty | the worlds photos can be pinned and shared in. `deny`: everywhere but in the listed worlds. `allow`: only in the listed ones. Photos already hanging there stay |
| `photos-protect-blocks` | `false` | `true`: a block with a photo on it is not blown up, burned, pushed by a piston or decayed. Players still break it. Lets anyone make a block blast-proof with a photo |
| `range.send`, `range.forget` | `32`, `48` | blocks in which clients are told about sheets, and after which they forget them |
| `network.images-per-second` | `4` | pictures sent to one player per second |
| `cameras.share`, `cameras.range` | `true`, `32` | show players' cameras to the others, and within how many blocks |

Permissions: `vrcamera.pin` (everyone), `vrcamera.remove.others` (operators), `vrcamera.admin` (operators).
Commands: `/vrcamsync stats`, `/vrcamsync purge <player>`, `/vrcamsync purgenear <blocks>`, `/vrcamsync reload`.

What protects the server and the other players:

- Every picture is unpacked and packed again by the server. No client ever receives bytes another client sent,
  only a plain JPEG the server made. Its size in pixels is checked before it is unpacked.
- A sheet has to be within reach of the player who pins it, on a block that is there. Its size comes from the
  picture, not from what the client claims.
- A client may send 10 messages per second. One that keeps flooding is ignored for a minute.
- Pictures are only handed out for sheets the client was told about.
- The client checks what a server sends the same way, and only takes pictures it asked for.

What it does not do: judge what is in a picture. A player can pin any screenshot of the game. For that there are
the commands above, the limits and `showOthersPhotos`.

## Without VR

The director and the follow camera also work for a player at a screen, with Vivecraft installed and VR off. This
is a first step: the camera films into the game window in place of your own view. A second picture next to your
own view, for OBS, is not there yet.

- `/vrcam screen director`, `follow` or `off`, or the mode key (`F8`): it cycles through the three while VR is off.
- Next shot (`F9`) and Hold (`F10`) work as in VR. Pace, shots and all the director settings are the same ones.
- You keep playing as before: you move and look around as yourself, only the picture comes from the camera.
- The shots for hands and for an open menu are left out, they need tracked hands and a menu in the world.
- Physics, grabbing, pulling and putting the camera up need hands and stay in VR.

**Steering it yourself.** Click into the window of the camera and it is yours for as long as that window has the
keyboard; click back into the game to give it back. The game does not pause for that. Without a window of its
own, or to stay in the game, press `G` (or `/vrcam screen steer`) to take the camera over. The keys you walk with move
it then, and you stand still: forward and back bring it closer and take it away, left and right fly it around
you, jump and sneak raise and lower it. Press `G` again to give it back. The director keeps the angle you found for
`manualHoldSeconds` and then goes on; the follow camera keeps it for good.

**A window of its own, for OBS.** `/vrcam screen window` gives the camera a second window, "VRCamera". You keep
your own view in the game window, with your hand, hotbar and menus; the window shows what the camera films and
nothing else. Capture it in OBS with a Window Capture source. `/vrcam screen here` goes back to filming into the
game window. Also in the settings ("Without VR: picture goes to").

- The world is drawn twice for this, expect a third to a half fewer FPS. `outputFps` (60 by default) limits how
  often the camera's picture is drawn: at 30 it costs about half as much.
- The picture is as large as the game window, the camera window only shows it scaled. Keep the game window at
  the size you want to record.
- Closing the camera window turns the camera off.
- In your own view the camera is shown where it is: the model of the Vivecraft camera with the camera icon over
  it. `indicator` turns the icon off.
- With a menu open (inventory, chest, pause menu, settings), a screen with that menu stands in front of your
  character, and the director films it over the shoulder like in VR. Only the camera sees that screen. Chat counts
  with `menuShotChat`.
- With no room for a camera around you, the picture is your own first person view, with your hand, hotbar and
  menus. In VR the camera films from your face instead.
- Needs the OpenGL renderer of the game. Shaders are not supported.
- Minecraft 26.2 only for now. 26.3 makes its windows another way, there the camera can only film into the game
  window.
- With the green screen on, only the camera window is keyed, your own view stays as it is.

**Taking it with the mouse.** With a window of its own the camera is something in your world. Point at its model
(the icon over it grows and beats when you have it), hold the use key (right mouse button) and it hangs in front of you: it goes where you look, and the wheel takes it
further away and brings it back. It comes after both softly, for a shot that flows. Let go and it stays there, like a camera put down by hand in VR. Let go while
you swing the view and it is thrown that way; `throwPower` sets how far. While you point at the camera or hold it,
the use key does nothing else.

**Free cameras.** The third mode without VR, `/vrcam screen free` or the mode key. A camera is put at your eyes,
films what you look at, and stays there whatever you do: it does not follow you and does not look for you. Put up
to 26 of them, with the key for a new shot of your own (`N`) or `/vrcam screen free` once more. They are called A,
B, C and so on — a letter stays with its camera, and a new one gets the first letter that is free — and you see them in the world with their letter over them, the one that films with the camera
icon as well. The ones further away than `cameraLabelDistance` are left out, 0 leaves them all out. One films at a time: the
attack key on a camera picks it, so does the key for the next shot of your own. Take one with the use key and it
films and goes where you look; let go and it stays as you held it, let go in a swing and it is thrown away and
gone. The last one can't be. The key that calls the camera puts the one that films at your eyes again. They are
kept with the world, for each dimension; `/vrcam screen clear` takes them all away. The keys of the mod work in
the window of the camera as well as in the game.

Take the camera over (click into its window, or `G`) and you fly it: the keys to walk move it, jump and sneak take
it up and down, sprint makes it fast, and the mouse turns it — its window takes the mouse for that, `Esc` goes back to
the game; from the game window it turns it as it turns you. It comes after all of that softly. The wheel zooms, also for a shot of the director that
you steer. While you have the camera, lines that split the picture into thirds are on its window; they go when
you give it back, and are never in what you record after that.

**Upright pictures.** The picture has the shape of the window of the camera. Make that window tall and narrow and
the camera films upright, with no bars at the sides.

**Green screen.** `/vrcam chroma` films only entities, on one plain colour, to cut them out later: green `#00B140`, or what `chromaColor` says. They are all lit the same, as in full daylight, wherever they stand. The
world, the sky, clouds, weather, particles, chests and signs are left out, and the round shadows under entities
are off. In VR only the picture of the camera turns green, your eyes see the world as it is. The same command
turns it off again. It does not work with shaders or with the "Fabulous!" graphics setting.

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
| `throwPower` | `1.3` | throw range multiplier in every mode, 0 = no throwing |
| `handStabilize` | `0.6` | steadying of a held camera, 0 = off, 1 = most |
| `attachToSelf` | `true` | the camera can be put on your own head by holding it there |
| `screenOutput` | `"screen"` | without VR: `"screen"` films into the game window, `"window"` into a window of its own |
| `menuSize` | `1.0` | size of the screen with an open menu that stands in front of your character for the camera |
| `chromaDistance` | `32` | blocks around you in which entities are filmed with the green screen on, 0 = all of them |
| `outputFps` | `60` | pictures per second in the window of the camera, 0 = as many as the game draws |
| `chromaColor` | `"#00B140"` | colour behind the entities with the green screen on |
| `selfieScreen` | `true` | a second screen on top of the camera while it is near you with its lens to you, in your hand or wherever you put it. Only in the headset, costs no FPS |
| `selfieDistance` | `3.0` | blocks from your head to the camera up to which the selfie screen is shown |
| `hideHoldingArm` | `true` | keep the arm that holds the camera out of the picture, except in a selfie |
| `pullStyle` | `"telekinesis"` | `"telekinesis"`: the camera comes to the hand for as long as the button is held, and stays where it got to if you let go. `"instant"`: hold the button, then it comes at once |
| `pullSeconds` | `1.25` | seconds the camera takes to come to the hand, 0 = no pulling |
| `pullAllModes` | `true` | `false` = pulling only in Physics |
| `physicsAim` | `0.75` | Physics: how much a dropped camera turns to the player, 0 to 1 |
| `physicsShake` | `1.0` | Physics: sway of a held camera, 0 = off |
| `kickPower` | `1.0` | Physics: how hard hands and feet hit a dropped camera, 0 = they pass through |
| `underwaterLook` | `true` | Physics: wider angle, slow roll and bubbles under water |
| `menuShotChat` | `true` | the `menu` shot for chat as well |
| `photoSheet` | `true` | a taken photo comes out of the camera as a sheet; `false` only saves it |
| `showOthersPhotos` | `true` | show the photos other players pinned, on servers that share them |
| `showCustomPhotos` | `false` | show the pictures other players loaded from the internet |
| `shareCamera` | `true` | let players around see your camera, on servers that share that |
| `photoGesture` | `"same_hand"` | `"same_hand"`, `"other_hand"` or `"off"` |
| `photoHoldSeconds` | `1.0` | seconds the button of the photo gesture is held, 0 = at once |
| `photoSounds` | `true` | the click of a photo and the whirr of printing it, yours and of others. `false` mutes them for you; the others still hear yours |
| `photoPixels` | `0.3` | pixel art on the sheet, in the colours of a map: 0 = off, 1 = fewest pixels (128 down to 32 along the longer side). New photos only; the saved file is not changed |
| `photoBrightness` | `0.3` | how much the picture on a sheet is brightened, 0 = as taken, 1 = most. New photos only; the saved file is not changed |
| `debugOverlay` | `false` | debug overlay on the HUD |

### Motion

| Field | Default | Meaning |
|---|---|---|
| `aimHeight` | `0.6` | where to aim: 0 = feet, 1 = head |
| `faceDistance` | `1.25` | blocks; a camera closer than this aims at the face, twice as far at the body, 0 = always the body |
| `positionLag` | `0.35` | seconds to catch up with the wanted position |
| `lookLag` | `0.12` | seconds for the aim to catch up with the player |
| `turnLag` | `1.1` | seconds to swing around when the player turns |
| `turnDeadzone` | `16` | degrees you can turn without moving the camera |
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
| `pace` | `"default"` | the pace picked last: `"alpha"`, `"default"` or `"faster"`. See below |
| `transition` | `"auto"` | `"auto"`, `"cut"` or `"blend"` (fly-overs only) |
| `blendChance` | `0.6` | chance of a fly-over with `"auto"` |
| `minShotTime` | `4.0` | least seconds before a change because the situation changed |
| `manualHoldSeconds` | `30` | how long the director keeps a hand-placed angle |
| `orbitSpeed` | `10` | circling speed, degrees per second |
| `events` | `true` | special shots for death and falling |
| `activePreset` | `0` | number of the active own angle, counted from 0 |
| `customInRotation` | `false` | own angles take part in the director's rotation |

**Pace.** Three ready-made sets of values for how long shots last and how readily the camera moves. Pick one in
the settings ("Pace") or with `/vrcam pace <name>`:

| Pace | For | Shots |
|---|---|---|
| `default` | YouTube, long videos: easy to follow, long pieces to cut from | 5–18 s, at least 4 s before a change |
| `faster` | TikTok, shorts: more changes, less to cut from | 4–16 s, at least 3 s |
| `alpha` | the way the mod first came: short shots, many changes | 4–14 s, at least 2.5 s |

Picking a pace writes `minShotTime`, `orbitSpeed`, `manualHoldSeconds`, `turnLag`, `turnDeadzone`,
`handStabilize` and the `minDuration`/`maxDuration` of the shots, and overwrites what you set there by hand. After
that each of them can be changed on its own again.

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
| Your character is not in the camera's picture | turn on Show Playermodel in Vivecraft: VR Settings → Playermodel Settings |
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
