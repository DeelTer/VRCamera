# VRCamera

[Русская версия](README_RU.md) · [User guide](docs/GUIDE.md)

A cinematic camera mod for Minecraft VR and PC streams, recordings and screenshots.
Use the [Vivecraft](https://modrinth.com/mod/vivecraft) handheld camera in VR, or film through a separate camera window on PC. Vivecraft is optional for PC use.

- **Director** chooses angles and switches shots automatically.
- **Follow** keeps your chosen angle relative to the player.
- **Free (PC)** gives you up to 26 saved cameras, smooth flight and gaze-based switching.
- **Physics (VR)** lets you carry, drop, throw, kick and attach the camera.

Director and Follow keep the player in frame and avoid blocks. On PC you can also film another player or include a friend in your shots.

## Install

Supports **Minecraft 26.2 and 26.3**, with a separate jar for each. Requires **Java 25**, **Fabric Loader** and **Fabric API**. Put the matching VRCamera jar in `mods`.

For VR, install Vivecraft for the same Minecraft version and use **standing mode**. Its handheld camera is disabled in seated mode.
Optional: **Cloth Config** adds settings; **Mod Menu** adds a settings button in the mod list. Build dependency versions are in [`versions/`](versions/).

The mod runs on the client. A server plugin is only needed to share photos and camera positions between players.

## Quick start

**PC:** enter a world, press `F8` to choose a mode, then run `/vrcam screen window` for a separate VRCamera window. Capture it in OBS with Window Capture. `/vrcam screen here` uses the Minecraft window instead.

**VR:** enable VR, enter a world and press `F8` or the **VR Camera** button in the pause menu. Capture the Minecraft window in OBS. The camera model is visible in the headset and hidden from the recording.

Use `/vrcam chroma` for a green screen. On PC, a tall camera window gives a portrait view; `F11` in that window toggles fullscreen.

## Vivecraft and OBS setup

For VR, check these Vivecraft settings:

| Setting | Recommended value |
| --- | --- |
| Show Playermodel | **ON** in VR Settings → Playermodel Settings, or your character will be missing |
| Desktop Mirror | Anything except OFF |
| Camera Resolution | 1.0 for 1920×1080; higher values cost more FPS |
| GUI On Mirror | OFF for a clean stream |
| Hotswitching | OFF to keep filming when you take the headset off |
| Camera as Desktop Mirror | Managed by the mod and restored when it stops |

For a 16:9 recording, use a matching window size, such as 1920×1080. The camera renders another view of the world, so expect extra GPU load. On PC, lower `outputFps` if needed; render resolution follows the Minecraft window size.

## Controls

| Action | Key |
| --- | --- |
| Switch mode | `F8` |
| Next shot | `F9` |
| Hold/release the shot | `F10` |
| Next saved angle or nearby free camera | `F7` |
| Take a photo | `F6` |
| New saved angle / free camera | `N` |
| Steer the PC camera | `G` |

Rebind keys in Minecraft's controls; in VR you can also use Vivecraft's radial menu or SteamVR bindings. Client commands support Tab completion and need no server permissions. See the [full command list](docs/GUIDE.md#commands).

Free-camera shortcuts: `/cam add`, `/cam B`, `/cam next`, `/cam fly`, `/cam clear`.
Film another player with `/cam follow Name`, or add a partner with `/cam with Name`.

## Camera in your hands

**VR:** hold interact near the camera to grab it; release to place or drop it. Swing and release to throw. To pull it from afar, look at it, point a hand and hold interact after the controller buzzes.

**PC, with a separate window:** aim at a camera and hold right-click to grab it. Move it with your look, adjust its distance with the wheel and release to place it. Click its window or press `G` to steer with movement keys.

Use **Bring camera to me** to put it within reach. In VR, save an angle with New angle → Bring camera to me → grab, place, release.

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

With Figura installed ([NoFigura](https://modrinth.com/mod/nofigura) for these versions of the game), the script of
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
Other plugins can listen to `PhotoPinEvent` (cancellable) and `PhotoTakeEvent` in `ru.deelter.vrcamera.sync.plugin.event`. Add `depend: [VRCamera]` or `softdepend: [VRCamera]` to `plugin.yml`.
`/vrcamsync list <player>` lists a player's pinned photos. See [server setup](docs/GUIDE.md#sharing-photos-on-a-server).

## Build

```bash
./gradlew build
./gradlew build -Pmc=26.3
./gradlew buildAll
```

Builds the default version, 26.3, or all supported versions. Mod and optional `vrcamera-paper-plugin-<version>.jar` files go to `build/libs/`.
The default Minecraft version is set in [`gradle.properties`](gradle.properties); dependencies are in [`versions/`](versions/).
