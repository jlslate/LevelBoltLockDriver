# Level Bolt (Matter) Lock – Hubitat Driver

A Hubitat driver for the [Level Bolt](https://level.co) smart lock paired over Matter.
Tested on a Level Bolt with Matter firmware.

## Features
- Lock and unlock (`Lock` capability)
- Lock state, kept in sync by a Matter subscription plus a re-read after each command
- Battery: `batteryStatus` (good / warning / critical) and an approximate `battery` percentage
- Refresh, Configure and Initialize commands
- Quiet logging: state changes only, with a debug option that turns itself off after 30 minutes

## Not supported
- User codes / PINs (the Level Bolt exposes none over Matter)
- Auto-relock, LED and sound settings
- Other Matter locks (untested; uses endpoint 1 and the Level Bolt's fingerprint)

## Install

### Hubitat Package Manager (recommended)
1. Pair the lock with your hub: Devices → Add Device → Matter.
2. In HPM choose **Install** → **From a URL** and paste:
   `https://raw.githubusercontent.com/jlslate/LevelBoltLockDriver/main/packageManifest.json`
3. On the lock's device page set **Type** to **Level Bolt Matter Lock**, click **Save Device**, reload the page, click **Save Preferences**, then **Configure** and **Refresh**.

### Manual
1. Pair the lock with your hub: Devices → Add Device → Matter.
2. Drivers Code → **New Driver** → **Import** and paste:
   `https://raw.githubusercontent.com/jlslate/LevelBoltLockDriver/main/LevelBoltMatterLock.groovy`
   (or paste the contents of `LevelBoltMatterLock.groovy`) → **Save**.
3. On the lock's device page set **Type** to **Level Bolt Matter Lock** and click **Save Device**.
4. Reload the page, click **Save Preferences**, then **Configure** and **Refresh**.

## Preferences
| Preference | Default | Notes |
|---|---|---|
| Timed invoke window | 5000 ms | The Level Bolt needs lock/unlock sent as a timed invoke. Leave as is. |
| Unlock command | UnlockDoor | `UnlockWithTimeout` is an alternative that relocks after the timeout below. |
| Enable descriptionText logging | on | Logs state changes. |
| Enable debug logging | on | Raw Matter traffic; turns off after 30 minutes. |

## Troubleshooting
- **No Lock/Unlock buttons, or old preferences:** the device is on a different driver. Check the Type dropdown.
- **Lock doesn't move:** make sure the timed invoke window is above 0.
- **Battery empty:** press Refresh and check the debug log for cluster `002F`.

## License
[The Unlicense](LICENSE): public domain, no warranty.
