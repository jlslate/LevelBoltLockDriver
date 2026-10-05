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

@Field static final Integer DOOR_LOCK_CLUSTER    = 0x0101
@Field static final Integer POWER_SOURCE_CLUSTER = 0x002F
@Field static final Integer ATTR_LOCK_STATE      = 0x0000
@Field static final Integer ATTR_BAT_PERCENT     = 0x000C
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

        attribute "lockStateDetail", "string"   // raw Matter state name (e.g. notFullyLocked, unlatched)

        // Generic Matter door lock; if auto-match fails, pick this driver manually.
        fingerprint endpointId: "01", inClusters: "0003,0004,0005,0101", outClusters: "", controllerType: "MAT"
    }

    preferences {
        input name: "lockEndpoint", type: "number", title: "Door Lock endpoint", defaultValue: 1, required: true
        input name: "batteryEndpoint", type: "number", title: "Power Source endpoint (battery)", defaultValue: 1, required: true
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
    if (logEnable) runIn(1800, "logsOff")
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
    if (logEnable) runIn(1800, "logsOff")
    sendHubCommand(new hubitat.device.HubAction(subscribeCmd(), hubitat.device.Protocol.MATTER))
    refresh()
}

void logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ---------------------------------------------------------------- commands

void lock() {
    if (logEnable) log.debug "lock()"
    sendMatter(matter.invoke(lockEp(), DOOR_LOCK_CLUSTER, CMD_LOCK))
}

void unlock() {
    if (logEnable) log.debug "unlock()"
    sendMatter(matter.invoke(lockEp(), DOOR_LOCK_CLUSTER, CMD_UNLOCK))
}

void refresh() {
    if (logEnable) log.debug "refresh()"
    sendMatter(matter.readAttributes(attributePaths()))
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
    if (logEnable) log.debug "parse: ${descMap}"

    Integer cluster = descMap.clusterInt
    Integer attr    = descMap.attrInt
    def value       = descMap.value
    if (cluster == null || attr == null || value == null) return

    if (cluster == DOOR_LOCK_CLUSTER && attr == ATTR_LOCK_STATE) {
        handleLockState(toInt(value))
    } else if (cluster == POWER_SOURCE_CLUSTER && attr == ATTR_BAT_PERCENT) {
        handleBattery(toInt(value))
    }
}

private void handleLockState(Integer state) {
    String name = LOCK_STATES.get(state, "unknown")
    String detail = [0: "notFullyLocked", 1: "locked", 2: "unlocked", 3: "unlatched"].get(state, "unknown")
    String text = "${device.displayName} is ${name}"
    if (txtEnable) log.info text
    sendEvent(name: "lock", value: name, descriptionText: text)
    sendEvent(name: "lockStateDetail", value: detail)
}

private void handleBattery(Integer halfPercent) {
    Integer pct = Math.max(0, Math.min(100, (int) Math.round(halfPercent / 2.0)))
    String text = "${device.displayName} battery is ${pct}%"
    if (txtEnable) log.info text
    sendEvent(name: "battery", value: pct, unit: "%", descriptionText: text)
}

// ---------------------------------------------------------------- helpers

private Integer lockEp()    { (settings.lockEndpoint    ?: 1) as Integer }
private Integer batteryEp() { (settings.batteryEndpoint ?: 1) as Integer }

private List<Map<String, String>> attributePaths() {
    List<Map<String, String>> paths = []
    paths.add(matter.attributePath(lockEp(), DOOR_LOCK_CLUSTER, ATTR_LOCK_STATE))
    paths.add(matter.attributePath(batteryEp(), POWER_SOURCE_CLUSTER, ATTR_BAT_PERCENT))
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
