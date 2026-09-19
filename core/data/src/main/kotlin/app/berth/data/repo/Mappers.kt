package app.berth.data.repo

import app.berth.data.db.HostEntity
import app.berth.data.db.IdentityEntity
import app.berth.data.db.KnownHostEntity
import app.berth.data.db.SessionEntity
import app.berth.data.db.SnippetEntity
import app.berth.data.db.TunnelEntity
import app.berth.data.db.WorkspaceEntity
import app.berth.domain.model.AddressFamily
import app.berth.domain.model.AppearanceOverride
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Snippet
import app.berth.domain.model.SnippetAction
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One lenient JSON instance for every column that stores a document. */
internal val dataJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val stringList = ListSerializer(String.serializer())
private val stringMap = MapSerializer(String.serializer(), String.serializer())

private inline fun <reified E : Enum<E>> String.toEnumOr(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default

internal fun Host.toEntity() = HostEntity(
    id = id,
    name = name,
    color = color.name,
    monogram = monogram,
    address = address,
    port = port,
    user = user,
    authJson = dataJson.encodeToString(AuthMethod.serializer(), auth),
    jumpHostIdsJson = dataJson.encodeToString(stringList, jumpHostIds),
    persistenceJson = dataJson.encodeToString(PersistencePolicy.serializer(), persistence),
    startupCommand = startupCommand,
    environmentJson = dataJson.encodeToString(stringMap, environment),
    terminalType = terminalType,
    agentForwarding = agentForwarding,
    compression = compression,
    addressFamily = addressFamily.name,
    appearanceJson = dataJson.encodeToString(AppearanceOverride.serializer(), appearance),
    tagsJson = dataJson.encodeToString(stringList, tags),
    muteBell = muteBell,
    lastConnectedAt = lastConnectedAt,
    createdAt = createdAt,
)

internal fun HostEntity.toDomain() = Host(
    id = id,
    name = name,
    color = color.toEnumOr(SwatchColor.forName(name)),
    monogram = monogram,
    address = address,
    port = port,
    user = user,
    auth = runCatching { dataJson.decodeFromString(AuthMethod.serializer(), authJson) }.getOrDefault(AuthMethod.AskEachTime),
    jumpHostIds = runCatching { dataJson.decodeFromString(stringList, jumpHostIdsJson) }.getOrDefault(emptyList()),
    persistence = runCatching { dataJson.decodeFromString(PersistencePolicy.serializer(), persistenceJson) }.getOrDefault(PersistencePolicy()),
    startupCommand = startupCommand,
    environment = runCatching { dataJson.decodeFromString(stringMap, environmentJson) }.getOrDefault(emptyMap()),
    terminalType = terminalType,
    agentForwarding = agentForwarding,
    compression = compression,
    addressFamily = addressFamily.toEnumOr(AddressFamily.AUTO),
    appearance = runCatching { dataJson.decodeFromString(AppearanceOverride.serializer(), appearanceJson) }.getOrDefault(AppearanceOverride()),
    tags = runCatching { dataJson.decodeFromString(stringList, tagsJson) }.getOrDefault(emptyList()),
    muteBell = muteBell,
    lastConnectedAt = lastConnectedAt,
    createdAt = createdAt,
)

internal fun Identity.toEntity() = IdentityEntity(
    id = id,
    name = name,
    algorithm = algorithm.name,
    storage = storage.name,
    protection = protection.name,
    publicKeyOpenSsh = publicKeyOpenSsh,
    fingerprintSha256 = fingerprintSha256,
    comment = comment,
    keystoreAlias = keystoreAlias,
    createdAt = createdAt,
)

internal fun IdentityEntity.toDomain() = Identity(
    id = id,
    name = name,
    algorithm = algorithm.toEnumOr(KeyAlgorithm.ED25519),
    storage = storage.toEnumOr(KeyStorage.SOFTWARE_ENCRYPTED),
    protection = protection.toEnumOr(KeyProtection.NONE),
    publicKeyOpenSsh = publicKeyOpenSsh,
    fingerprintSha256 = fingerprintSha256,
    comment = comment,
    keystoreAlias = keystoreAlias,
    createdAt = createdAt,
)

internal fun KnownHostKey.toEntity() = KnownHostEntity(id, host, port, keyType, publicKeyBase64, fingerprintSha256, firstSeenAt, lastSeenAt, pinned)

internal fun KnownHostEntity.toDomain() = KnownHostKey(id, host, port, keyType, publicKeyBase64, fingerprintSha256, firstSeenAt, lastSeenAt, pinned)

internal fun Workspace.toEntity() = WorkspaceEntity(id, name, color.name, monogram, accentRgb, sortOrder, reconnectAtLaunch, createdAt)

internal fun WorkspaceEntity.toDomain() = Workspace(
    id = id,
    name = name,
    color = color.toEnumOr(SwatchColor.forName(name)),
    monogram = monogram,
    accentRgb = accentRgb,
    sortOrder = sortOrder,
    reconnectAtLaunch = reconnectAtLaunch,
    createdAt = createdAt,
)

internal fun SessionRecord.toEntity() = SessionEntity(
    id = id,
    workspaceId = workspaceId,
    hostId = hostId,
    hostSnapshotJson = dataJson.encodeToString(Host.serializer(), hostSnapshot),
    state = state.name,
    layer = layer.name,
    title = title,
    cwd = cwd,
    lastCommand = lastCommand,
    needsAttention = needsAttention,
    attentionReason = attentionReason,
    sortOrder = sortOrder,
    createdAt = createdAt,
    lastLiveAt = lastLiveAt,
    frameKey = frameKey,
)

internal fun SessionEntity.toDomain(): SessionRecord? {
    val snapshot = runCatching { dataJson.decodeFromString(Host.serializer(), hostSnapshotJson) }.getOrNull() ?: return null
    return SessionRecord(
        id = id,
        workspaceId = workspaceId,
        hostId = hostId,
        hostSnapshot = snapshot,
        state = state.toEnumOr(SessionState.DETACHED),
        layer = layer.toEnumOr(PersistenceLayer.LOCAL_FRAME),
        title = title,
        cwd = cwd,
        lastCommand = lastCommand,
        needsAttention = needsAttention,
        attentionReason = attentionReason,
        sortOrder = sortOrder,
        createdAt = createdAt,
        lastLiveAt = lastLiveAt,
        frameKey = frameKey,
    )
}

internal fun Tunnel.toEntity() = TunnelEntity(id, hostId, type.name, bindAddress, bindPort, destinationHost, destinationPort, enabled)

internal fun TunnelEntity.toDomain() = Tunnel(
    id = id,
    hostId = hostId,
    type = type.toEnumOr(TunnelType.LOCAL),
    bindAddress = bindAddress,
    bindPort = bindPort,
    destinationHost = destinationHost,
    destinationPort = destinationPort,
    enabled = enabled,
)

internal fun Snippet.toEntity() = SnippetEntity(
    id = id,
    name = name,
    body = body,
    hostId = hostId,
    tagsJson = dataJson.encodeToString(stringList, tags),
    defaultAction = defaultAction.name,
    runOnConnect = runOnConnect,
    pinnedToDeck = pinnedToDeck,
    workspaceId = workspaceId,
)

internal fun SnippetEntity.toDomain() = Snippet(
    id = id,
    name = name,
    body = body,
    hostId = hostId,
    tags = runCatching { dataJson.decodeFromString(stringList, tagsJson) }.getOrDefault(emptyList()),
    defaultAction = defaultAction.toEnumOr(SnippetAction.RUN),
    runOnConnect = runOnConnect,
    pinnedToDeck = pinnedToDeck,
    workspaceId = workspaceId,
)
