# VRCamera

[Русская версия](README_RU.md) · [User guide](docs/GUIDE.md)

A cinematic camera mod for Minecraft VR and PC streams, recordings and screenshots.
Use the [Vivecraft](https://modrinth.com/mod/vivecraft) handheld camera in VR, or film through a separate camera window
on PC. Vivecraft is optional for PC use.

- **Director** chooses angles and switches shots automatically.
- **Follow** keeps your chosen angle relative to the player.
- **Free (PC)** gives you up to 26 saved cameras, smooth flight and gaze-based switching.
- **Physics (VR)** lets you carry, drop, throw, kick and attach the camera.

Director and Follow keep the player in frame and avoid blocks. On PC you can also film another player or include a
friend in your shots.

## Install

Supports **Minecraft 1.21.4, 1.21.8 and 1.21.11**, with a separate jar for each. Requires **Java 21**, **Fabric Loader** and **Fabric
API**. Put the matching VRCamera jar in `mods`.

For VR, install Vivecraft for the same Minecraft version and use **standing mode**. Its handheld camera is disabled in
seated mode.
Optional: **Cloth Config** adds settings; **Mod Menu** adds a settings button in the mod list. Build dependency versions
are in [`versions/`](versions/).

The mod runs on the client. A server plugin is only needed to share photos and camera positions between players.

## Quick start

**PC:** enter a world, press `F8` to choose a mode, then run `/vrcam screen window` for a separate VRCamera window.
Capture it in OBS with Window Capture. `/vrcam screen here` uses the Minecraft window instead.

**VR:** enable VR, enter a world and press `F8` or the **VR Camera** button in the pause menu. Capture the Minecraft
window in OBS. The camera model is visible in the headset and hidden from the recording.

Use `/vrcam chroma` for a green screen. On PC, a tall camera window gives a portrait view; `F11` in that window toggles
fullscreen.

## Vivecraft and OBS setup

For VR, check these Vivecraft settings:

| Setting                  | Recommended value                                                               |
|--------------------------|---------------------------------------------------------------------------------|
| Show Playermodel         | **ON** in VR Settings → Playermodel Settings, or your character will be missing |
| Desktop Mirror           | Anything except OFF                                                             |
| Camera Resolution        | 1.0 for 1920×1080; higher values cost more FPS                                  |
| GUI On Mirror            | OFF for a clean stream                                                          |
| Hotswitching             | OFF to keep filming when you take the headset off                               |
| Camera as Desktop Mirror | Managed by the mod and restored when it stops                                   |

For a 16:9 recording, use a matching window size, such as 1920×1080. The camera renders another view of the world, so
expect extra GPU load. On PC, lower `outputFps` if needed; render resolution follows the Minecraft window size.

## Controls

| Action                                 | Key   |
|----------------------------------------|-------|
| Switch mode                            | `F8`  |
| Next shot                              | `F9`  |
| Hold/release the shot                  | `F10` |
| Next saved angle or nearby free camera | `F7`  |
| Take a photo                           | `F6`  |
| New saved angle / free camera          | `N`   |
| Steer the PC camera                    | `G`   |
| Turn the held camera or photo          | `R`   |

Rebind keys in Minecraft's controls; in VR you can also use Vivecraft's radial menu or SteamVR bindings. Client commands
support Tab completion and need no server permissions. See the [full command list](docs/GUIDE.md#commands).

Free-camera shortcuts: `/cam add`, `/cam B`, `/cam next`, `/cam fly`, `/cam clear`.
Film another player with `/cam follow Name`, or add a partner with `/cam with Name`.

## Camera in your hands

**VR:** hold interact near the camera to grab it; release to place or drop it. Swing and release to throw. To pull it
from afar, look at it, point a hand and hold interact after the controller buzzes.

**PC, with a separate window:** aim at a camera and hold right-click to grab it. Move it with your look, adjust its
distance with the wheel and release to place it. `R` turns it around to film what you look at; let go and it turns
back to you. Click its window or press `G` to steer with movement keys.

**Photos on PC:** aim at a photo and hold right-click to take it. The wheel moves it nearer or farther, `R` rotates it.
Hold it against a block and release to pin it; release in the air and it falls.

Use **Bring camera to me** to put it within reach. In VR, save an angle with New angle → Bring camera to me → grab,
place, release.

## Settings and help

Open settings from the pause menu with Cloth Config, or edit `config/vrcamera.json` and run `/vrcam reload`.
Photos are saved in `screenshots/vrcamera` and copied to the clipboard on Windows.

- Missing character in VR: enable **Show Playermodel**.
- Black VR mirror: check **Desktop Mirror**.
- Missing settings: install **Cloth Config**.
- Unresponsive keys: focus the appropriate window and close menus.
- Camera error: check `logs/latest.log`.

The [user guide](docs/GUIDE.md) covers camera modes, photos, commands, configuration and troubleshooting.

## Known limits

- Vivecraft updates can break compatibility with its internal classes.
- The separate PC window needs OpenGL; shaders are unsupported. Green screen also does not support Fabulous graphics.
- Pulling the VR camera uses that hand's interact button and ignores walls.
- A crash during filming can leave temporary camera values in Vivecraft's saved settings.

See the [guide](docs/GUIDE.md#known-limits) for details.

## Figura avatars

With [Figura](https://modrinth.com/mod/figura) installed ([NoFigura](https://modrinth.com/mod/nofigura) on 1.21.8 and 1.21.11), the script of
your avatar gets a global `vrcamera` and can make its eyes look into the camera of the screen mode: for a moment
after a cut to another free camera, and now and then while the camera is closer than 8 blocks. Eyes are part of the
avatar, so its script has to move them:

```lua
function events.render()
    local look = vrcamera and vrcamera:getLookOffset()
    local x = look and math.clamp(look.x / 60, -1, 1) or 0
    local y = look and math.clamp(look.y / 60, -1, 1) or 0
    models.model.Head.Eyes:setPos(-x, y, 0)
end
```

`getLookOffset()` gives degrees to the right and up from where the head faces, or `nil` when there is nothing to
look at. There are also `isFilming()`, `getCameraPos()` and `getLookTarget()`: a position, which an eye library like
Gaze takes as it is, `tracking:setTargetOverride(vrcamera:getLookTarget())`. Only your own avatar gets answers, and
only on your own game: for others to see the eyes move, send them with a ping.

## Server plugin API

The optional **VRCamera** plugin runs on **Paper 1.21.4+ / Java 21** and shares photos and camera positions.
Other plugins can listen to `PhotoPinEvent` (cancellable) and `PhotoTakeEvent` in
`ru.deelter.vrcamera.sync.plugin.event`. Add `depend: [VRCamera]` or `softdepend: [VRCamera]` to `plugin.yml`.
`/vrcamsync list <player>` lists a player's pinned photos. See [server setup](docs/GUIDE.md#sharing-photos-on-a-server).

### Cameras from a plugin

A plugin can give players free cameras at places worth filming from: an arena, a stage, a finish line. The camera
becomes the player's own. They can move it or throw it away, and one they threw away does not come back.

```java
CameraApi cameras = CameraApi.get();
cameras.placeCamera(player, CameraView.builder("arena:north")
        .location(new Location(world, 120.5, 72, -40.5))
        .lookAt(arenaCenter)
        .fov(50)
        .build());
cameras.removeCameras(player, "arena:");   // every camera whose id starts with that
```

- The id is what the camera is known by. Sending the same id again changes nothing, so it is safe on every join;
  `replace(true)` puts it there anyway, for a map that changed.
- `yaw(..)` and `pitch(..)` instead of `lookAt(..)`, or neither: the camera then looks the way the location does.
- `placeCamera(player, view, true)` and `showCamera(player, id)` also have it film, for a player in the free mode.
- `showCamera(player, id, 5, TimeUnit.SECONDS)` lends the picture to that camera for five seconds, whatever mode
  the camera of the player is in, and then gives back what they had. A player who changes something in the
  meantime keeps that. Never for a camera that is off.
- `CameraSwitchEvent` is called when a player goes over to another of their free cameras, with its letter, its id
  if a plugin gave it, and where it is.
- Players at a screen only, a few cameras per world, and a player can turn them off in the settings: every call
  is a wish. All of them return `false` for a player without the mod.
- By hand: `/vrcamsync camera place <player> <id>` puts one where you stand and look, `remove`, `clear` and `show`
  do the rest.

Classes are in `ru.deelter.vrcamera.sync.plugin.api` and `...plugin.event`.

## Build

```bash
./gradlew build
./gradlew build -Pmc=1.21.11
./gradlew buildAll
```

Builds the default version, 1.21.11, or all supported versions. Mod and optional `vrcamera-paper-plugin-<version>.jar`
files go to `build/libs/`.
The default Minecraft version is set in [`gradle.properties`](gradle.properties); dependencies are in [
`versions/`](versions/).
