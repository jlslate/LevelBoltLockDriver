# Level Bolt (Matter) Lock – Hubitat Driver

Hubitat driver for the Level Bolt smart lock paired over Matter.

**Features:** lock / unlock, lock state, battery, refresh, Matter subscription.
Not yet supported: user codes/PINs, auto-relock, LED/sound settings.

> Status: lock and unlock confirmed working on a Level Bolt. Lock/unlock must be sent as a Matter timed invoke (default 5000 ms preference). Battery is not yet confirmed.

## Install
1. Pair the lock with the hub: Add Device → Matter.
2. Drivers Code → New Driver → paste `LevelBoltMatterLock.groovy` (or Import using the raw URL) → Save.
3. On the lock's device page set Type to **Level Bolt Matter Lock** and Save.
4. Save Preferences, then press Refresh.
