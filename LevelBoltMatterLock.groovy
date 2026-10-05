/*
 * Level Bolt (Matter) Lock - Hubitat driver
 *
 * Supports: lock / unlock, lock state, battery, refresh, Matter subscription.
 * Matter clusters used:
 *   0x0101 Door Lock    - attr 0x0000 LockState, cmd 0x00 LockDoor, cmd 0x01 UnlockDoor
 *   0x002F Power Source - attr 0x000C BatPercentRemaining (units of 0.5%)
 *
 * NOTE: written against Hubitat's Matter API without access to the hardware; check the
 * debug logs on first use and adjust the battery endpoint preference if battery stays empty.
 */
import groovy.transform.Field
import hubitat.matter.DataType

@Field static final Integer DOOR_LOCK_CLUSTER    = 0x0101
@Field static final Integer POWER_SOURCE_CLUSTER = 0x002F
@Field static final Integer ATTR_LOCK_STATE      = 0x0000
@Field static final Integer ATTR_BAT_PERCENT     = 0x000C
@Field static final Integer ATTR_BAT_CHARGE_LEVEL = 0x000E
@Field static final Map CHARGE_LEVELS = [0: "good", 1: "warning", 2: "critical"]
@Field static final Map CHARGE_LEVEL_PERCENT = [0: 100, 1: 20, 2: 5]  // coarse stand-ins; the lock only reports a level, not a percentage
@Field static final Integer CMD_LOCK             = 0x00
@Field static final Integer CMD_UNLOCK           = 0x01
@Field static final Map LOCK_STATES = [0: "unknown", 1: "locked", 2: "unlocked", 3: "unlocked"]  // NotFullyLocked, Locked, Unlocked, Unlatched

metadata {
    definition(name: "Level Bolt Matter Lock", namespace: "jamesslate", author: "James Slate",
               importUrl: "https://raw.githubusercontent.com/jlslate/LevelBoltLockDriver/main/LevelBoltMatterLock.groovy") {
        capability "Initialize"
        capability "Configuration"
        capability "Refresh"
        capability "Lock"
        capability "Battery"
        capability "Sensor"

        attribute "batteryStatus", "string"     // good / warning / critical, as reported by the lock

        attribute "lockStateDetail", "string"   // raw Matter state name (e.g. notFullyLocked, unlatched)

        // Reported by the Level Bolt when paired with Hubitat.
        fingerprint endpointId: "01", inClusters: "0003,001D,002F,0101,129FFC00", outClusters: "", model: "Level Bolt (Matter)", manufacturer: "Level Home", controllerType: "MAT"
    }

    preferences {
        input name: "lockEndpoint", type: "number", title: "Door Lock endpoint", defaultValue: 1, required: true
        input name: "batteryEndpoint", type: "number", title: "Power Source endpoint (battery)", defaultValue: 1, required: true
        input name: "timedMs", type: "number", title: "Timed invoke window in ms for lock/unlock (0 = off)", defaultValue: 5000, required: true
        input name: "unlockMethod", type: "enum", title: "Unlock command", options: ["UnlockDoor", "UnlockWithTimeout"], defaultValue: "UnlockDoor"
        input name: "unlockTimeout", type: "number", title: "UnlockWithTimeout: seconds before the lock relocks", defaultValue: 30
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: true
    }
}

// ---------------------------------------------------------------- lifecycle

void installed() {
    log.info "${device.displayName} installed"
    initialize()
}

void updated() {
    log.info "${device.displayName} preferences saved"
    if (settings.logEnable != false) runIn(1800, "logsOff")
    initialize()
}

void configure() {
    cleanupOldDriverData()
    initialize()
}

// Remove state and attributes left behind by whatever driver the device used before.
private void cleanupOldDriverData() {
    List keep = ["lock", "battery", "lockStateDetail"]
    state.clear()
    device.getCurrentStates()?.each { if (!(it.name in keep)) device.deleteCurrentState(it.name) }
}

void initialize() {
    if (settings.logEnable != false) log.debug "initializing: subscribing and refreshing"
    if (settings.logEnable != false) runIn(1800, "logsOff")
    sendHubCommand(new hubitat.device.HubAction(subscribeCmd(), hubitat.device.Protocol.MATTER))
    refresh()
}

void logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ---------------------------------------------------------------- commands

private String doorCmd(Integer cmd) {
    Integer t = (settings.timedMs != null ? settings.timedMs : 5000) as Integer
    return matter.invoke(lockEp(), DOOR_LOCK_CLUSTER, cmd, t)  // 4th arg is the timed-invoke window in ms (0 = plain invoke)
}

void lock() {
    String cmd = doorCmd(CMD_LOCK)
    if (settings.txtEnable != false) log.info "${device.displayName} lock requested"
    if (settings.logEnable != false) log.debug "lock command sent: ${cmd}"
    sendMatter(cmd)
    verifyState()
}

void unlock() {
    String cmd
    if (settings.unlockMethod == "UnlockWithTimeout") {
        // Door Lock command 0x03, field 0 = Timeout (uint16, seconds)
        Integer t = (settings.timedMs != null ? settings.timedMs : 5000) as Integer
        List<Map<String, String>> fields = []
        fields.add(matter.cmdField(DataType.UINT16, 0, integerTo16bitUnsignedHex((settings.unlockTimeout ?: 30) as Integer)))
        cmd = matter.invoke(lockEp(), DOOR_LOCK_CLUSTER, 0x03, t, fields)
    } else {
        cmd = doorCmd(CMD_UNLOCK)
    }
    if (settings.txtEnable != false) log.info "${device.displayName} unlock requested"
    if (settings.logEnable != false) log.debug "unlock command sent: ${cmd}"
    sendMatter(cmd)
    verifyState()
}

// Subscription reports can be missed, so re-read the lock state shortly after each command.
private void verifyState() {
    runIn(4, "refresh")
    runIn(15, "refresh")
}

void refresh() {
    if (settings.logEnable != false) log.debug "refresh"
    sendMatter(matter.readAttributes(attributePaths()))
}

// Called by the hub's Matter device page; reads Basic Information (cluster 0x0028) and logs it.
void getInfo() {
    List<Map<String, String>> paths = []
    [0x0001, 0x0003, 0x000A, 0x000C].each { paths.add(matter.attributePath(0x00, 0x0028, it)) }  // vendor, product, sw version, hw version
    sendMatter(matter.readAttributes(paths))
}

// ---------------------------------------------------------------- parsing

void parse(String description) {
    Map descMap
    try {
        descMap = matter.parseDescriptionAsMap(description)
    } catch (e) {
        log.warn "Unable to parse: ${description} (${e.message})"
        return
    }
    if (settings.logEnable != false) log.debug "parse: ${descMap}"
    if (!descMap) {
        log.debug "parse: unrecognized raw description: ${description}"
        return
    }

    Integer cluster = descMap.clusterInt
    Integer attr    = descMap.attrInt
    def value       = descMap.value
    if (cluster == null || attr == null || value == null) return

    if (cluster == 0x0028) {
        log.info "${device.displayName} basic info attr 0x${Integer.toHexString(attr)}: ${value}"
    } else if (cluster == DOOR_LOCK_CLUSTER && attr == ATTR_LOCK_STATE) {
        handleLockState(toInt(value))
    } else if (cluster == POWER_SOURCE_CLUSTER && attr == ATTR_BAT_PERCENT) {
        handleBattery(toInt(value))
    } else if (cluster == POWER_SOURCE_CLUSTER && attr == ATTR_BAT_CHARGE_LEVEL) {
        handleChargeLevel(toInt(value))
    }
}

private void handleLockState(Integer state) {
    String name = LOCK_STATES.get(state, "unknown")
    String detail = [0: "notFullyLocked", 1: "locked", 2: "unlocked", 3: "unlatched"].get(state, "unknown")
    String text = "${device.displayName} is ${name}"
    if (device.currentValue("lock") != name && settings.txtEnable != false) log.info text
    sendEvent(name: "lock", value: name, descriptionText: text)
    sendEvent(name: "lockStateDetail", value: detail)
}

private void handleChargeLevel(Integer level) {
    String status = CHARGE_LEVELS.get(level, "unknown")
    String text = "${device.displayName} battery is ${status}"
    if (device.currentValue("batteryStatus") != status && settings.txtEnable != false) log.info text
    sendEvent(name: "batteryStatus", value: status, descriptionText: text)
    // Only fall back to a stand-in percentage when the lock doesn't report a real one.
    if (!state.hasBatteryPercent && CHARGE_LEVEL_PERCENT.containsKey(level)) {
        sendEvent(name: "battery", value: CHARGE_LEVEL_PERCENT[level], unit: "%", descriptionText: "${text} (approximate)")
    }
}

private void handleBattery(Integer halfPercent) {
    state.hasBatteryPercent = true
    Integer pct = Math.max(0, Math.min(100, (int) Math.round(halfPercent / 2.0)))
    String text = "${device.displayName} battery is ${pct}%"
    if (device.currentValue("battery") != pct && settings.txtEnable != false) log.info text
    sendEvent(name: "battery", value: pct, unit: "%", descriptionText: text)
}

// ---------------------------------------------------------------- helpers

private Integer lockEp()    { (settings.lockEndpoint    ?: 1) as Integer }
private Integer batteryEp() { (settings.batteryEndpoint ?: 1) as Integer }

private List<Map<String, String>> attributePaths() {
    List<Map<String, String>> paths = []
    paths.add(matter.attributePath(lockEp(), DOOR_LOCK_CLUSTER, ATTR_LOCK_STATE))
    paths.add(matter.attributePath(batteryEp(), POWER_SOURCE_CLUSTER, ATTR_BAT_PERCENT))
    paths.add(matter.attributePath(batteryEp(), POWER_SOURCE_CLUSTER, ATTR_BAT_CHARGE_LEVEL))
    return paths
}

private String subscribeCmd() {
    // min 1s, max 1h heartbeat
    return matter.subscribe(1, 3600, attributePaths())
}

private void sendMatter(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.MATTER))
}

private Integer toInt(def v) {
    if (v instanceof Number) return ((Number) v).intValue()
    String s = v.toString()
    return s ==~ /(?i)[0-9a-f]+/ && !(s ==~ /\d+/) ? Integer.parseInt(s, 16) : Integer.parseInt(s)
}
