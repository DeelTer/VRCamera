# VRCamera

[Русская версия](README_RU.md) · [User guide](docs/GUIDE.md)

A [Vivecraft](https://modrinth.com/mod/vivecraft) addon that moves its handheld camera for you and films
the player in third person. Made for Minecraft VR streams and recordings with OBS.

Choose how you want to film:

- **Director** picks camera angles and switches between them, like the cinematic camera in GTA.
- **Follow** keeps a hand-placed angle relative to the player.
- **Physics** lets you carry, drop, throw and kick the camera. Good for found footage and horror.
- **Off** leaves the Vivecraft camera alone.

In Director and Follow, the camera keeps you in view and stays out of blocks.
In Physics, a held camera films where your hand points; a dropped one falls, bounces and comes to rest.

## Install

Supported Minecraft versions: **26.2 and 26.3**, with a separate jar for each. Requires **Java 25**.
The mod runs on the client, so there is nothing to install on the server.

Use Fabric Loader and put these files in your `mods` folder:

- VRCamera for your Minecraft version.
- Vivecraft for the same Minecraft version (Fabric).
- Fabric API.

Optional: **Cloth Config** adds the settings screen; **Mod Menu** adds a settings button in the mod list.
Exact dependency versions for each build are listed in [`versions/`](versions/).

You need **standing VR**. Vivecraft disables the handheld camera in seated mode.

## Quick start

1. Start Minecraft, turn VR on and enter a world.
2. Press `F8` or the **VR Camera** button in the pause menu. Modes cycle: Off → Director → Follow → Physics.
3. Capture the Minecraft window in OBS.

While the camera is on, the game window shows its picture. In your headset you see the Vivecraft camera model
where the camera is; it does not appear in the recording.

## Vivecraft and OBS setup

| Vivecraft setting | Recommended value |
|---|---|
| Play mode | Standing |
| Desktop Mirror | anything but OFF, otherwise the game window is black |
| Camera Resolution | 1.0 for 1920×1080; higher values cost more FPS |
| GUI On Mirror | OFF to keep the HUD out of streams |
| Hotswitching | OFF to keep filming when you take the headset off |
| Camera as Desktop Mirror | leave it; the mod enables it while filming and restores it afterwards |

Set the Minecraft window to 16:9, for example 1920×1080, and add a Game Capture or Window Capture source in OBS.
Other aspect ratios produce black bars. Keep the game window open and do not minimize it.

The camera adds another full render of the world on top of the two eye views.
Expect the same FPS drop as with Vivecraft's ordinary camera.

## Controls

| Action | Key |
|---|---|
| Switch mode | `F8` |
| Next shot | `F9` |
| Hold or release the current shot | `F10` |
| Next own angle | `F7` |
| Take a photo | `F6` |

You can also use the "VR Camera..." screen in the pause menu to bring the camera to you, manage your own angles
and open settings. Settings require Cloth Config.

Rebind keys in Minecraft's controls or bind them to controller buttons in SteamVR.
Keyboard controls work while the game window has focus and no menu is open.
Client commands are available through `/vrcam`, with tab completion and no permissions required.
See the [full command list](docs/GUIDE.md#commands).

## Camera in your hands

**Grab and place.** Reach for the camera and hold the interact button. Let go to keep that angle in Follow,
hold it temporarily in Director, or drop the camera in Physics.

**Throw.** Swing your hand and let go. A faster swing throws the camera further.
You can also throw the ordinary Vivecraft camera while the mod is off.

**Pull from afar.** Look at the camera and point a hand at it. When the controller buzzes, hold the interact
button until the camera flies into your hand. It stays there until you let go.

**Bring camera to me.** Use this button to put the camera within reach, in front of your face.
To save a new angle: New angle → Bring camera to me → grab, place, let go.

## Settings and help

With Cloth Config installed, open settings from the "VR Camera..." screen. The settings use sliders,
so you do not have to type numbers in VR. Save to apply changes.

You can also edit `config/vrcamera.json`, created on first start, and apply it with `/vrcam reload`.

| Problem | What to check |
|---|---|
| VR is not running or seated mode is reported | enable VR and use standing mode in Vivecraft |
| The game window is black | Desktop Mirror must not be OFF |
| The window shows first person | check that VR is running and `forceMirror` is enabled |
| You cannot reach the camera | use Bring camera to me or pull it |
| There is no Settings button | install Cloth Config |
| Keys do nothing | focus the game window and close menus |
| The camera turns off with an internal error | check `logs/latest.log` |

The [user guide](docs/GUIDE.md) covers all shots, Physics mode, commands, markers,
configuration fields and tuning suggestions.

## Known limits

- Vivecraft updates can break compatibility because the mod uses its internal classes.
- Without VR, there is no handheld camera. The mod restores Vivecraft's settings and waits;
  when VR returns, filming resumes with a new shot.
- Pulling uses that hand's interact button, and the pulled camera flies through walls.
- The mod temporarily changes Vivecraft's camera FOV and mirror setting. If Vivecraft saves its settings
  while filming and the game crashes, those temporary values can remain in its file.

See the [guide](docs/GUIDE.md#known-limits) for the remaining limitations.

## Build

```bash
./gradlew build
./gradlew build -Pmc=26.3
./gradlew buildAll
```

These build the default Minecraft version, 26.3, and all supported versions respectively.
Jars are written to `build/libs/`. The default version is set by `mc` in [`gradle.properties`](gradle.properties);
Minecraft and dependency versions are defined in [`versions/`](versions/).
