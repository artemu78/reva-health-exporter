package dev.reva.healthexporter

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class EmaResponseStatus(val wireValue: String) {
    PENDING("pending"),
    ANSWERED("answered"),
    DISMISSED("dismissed"),
    EXPIRED("expired"),
    ;

    companion object {
        fun fromWireValue(value: String): EmaResponseStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

data class EmaAnswers(
    val mood: Int,
    val energy: Int,
    val focus: Int,
    val stress: Int,
    val additional: Map<String, Int> = emptyMap(),
) {
    init {
        listOf(mood, energy, focus, stress).forEach {
            require(it in 1..5) { "EMA scale values must be between 1 and 5" }
        }
        require(additional.keys.none { it in CORE_KEYS }) { "Additional answers must use distinct keys" }
    }

    companion object {
        val CORE_KEYS = setOf("mood", "energy", "focus", "stress")
    }
}

data class EmaEvent(
    val schemaVersion: Int,
    val id: String,
    val scheduleDate: LocalDate,
    val scheduledAt: Instant,
    val answeredAt: Instant?,
    val answers: EmaAnswers?,
    val activity: String?,
    val activityLabel: String?,
    val note: String?,
    val status: EmaResponseStatus,
    val timezone: String,
) {
    companion object {
        fun pending(
            id: String,
            scheduledAt: Instant,
            zoneId: ZoneId,
            scheduleDate: LocalDate = scheduledAt.atZone(zoneId).toLocalDate(),
        ) = EmaEvent(
            schemaVersion = 1,
            id = id,
            scheduleDate = scheduleDate,
            scheduledAt = scheduledAt,
            answeredAt = null,
            answers = null,
            activity = null,
            activityLabel = null,
            note = null,
            status = EmaResponseStatus.PENDING,
            timezone = zoneId.id,
        )
    }
}

interface EmaEventStore {
    fun save(event: EmaEvent)
    fun get(id: String): EmaEvent?
    fun all(): List<EmaEvent>
    fun updatePending(id: String, transform: (EmaEvent) -> EmaEvent): Boolean
}

class InMemoryEmaEventStore : EmaEventStore {
    private val events = linkedMapOf<String, EmaEvent>()

    @Synchronized
    override fun save(event: EmaEvent) {
        events[event.id] = event
    }

    @Synchronized
    override fun get(id: String): EmaEvent? = events[id]

    @Synchronized
    override fun all(): List<EmaEvent> = events.values.sortedBy { it.scheduledAt }

    @Synchronized
    override fun updatePending(id: String, transform: (EmaEvent) -> EmaEvent): Boolean {
        val event = events[id] ?: return false
        if (event.status != EmaResponseStatus.PENDING) return false
        events[id] = transform(event)
        return true
    }
}

class FileEmaEventStore(
    private val directory: File,
) : EmaEventStore {
    init {
        require(directory.isDirectory || (!directory.exists() && directory.mkdirs())) {
            "EMA event directory could not be created"
        }
    }

    override fun save(event: EmaEvent) {
        synchronized(STORE_LOCK) {
        require(SAFE_ID.matches(event.id)) { "EMA event ID contains unsafe characters" }
        val destination = File(directory, "${event.id}.json")
        val temporary = File(directory, ".${event.id}.tmp")
        temporary.writeText(serializeEmaEvent(event), Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        }
    }

    override fun get(id: String): EmaEvent? {
        synchronized(STORE_LOCK) {
        if (!SAFE_ID.matches(id)) return null
        val file = File(directory, "$id.json")
        return if (file.isFile) deserializeEmaEvent(file.readText(Charsets.UTF_8)) else null
        }
    }

    override fun all(): List<EmaEvent> = synchronized(STORE_LOCK) {
        directory.listFiles { file ->
            file.isFile && file.extension == "json" && !file.name.startsWith(".")
        }.orEmpty().mapNotNull { deserializeEmaEvent(it.readText(Charsets.UTF_8)) }.sortedBy { it.scheduledAt }
    }

    override fun updatePending(id: String, transform: (EmaEvent) -> EmaEvent): Boolean =
        synchronized(STORE_LOCK) {
            val event = get(id) ?: return@synchronized false
            if (event.status != EmaResponseStatus.PENDING) return@synchronized false
            save(transform(event))
            true
        }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,100}")
        private val STORE_LOCK = Any()
    }
}

class EmaCheckInService(
    private val store: EmaEventStore,
) {
    @Synchronized
    fun answer(
        eventId: String,
        answeredAt: Instant,
        answers: EmaAnswers,
        activity: String,
        note: String?,
        activityLabel: String? = null,
    ): Boolean = updatePending(eventId) { event ->
        require(activity.isNotBlank()) { "Activity is required" }
        event.copy(
            answeredAt = answeredAt,
            answers = answers,
            activity = activity,
            activityLabel = activityLabel?.trim()?.ifBlank { null },
            note = note?.trim()?.take(280)?.ifBlank { null },
            status = EmaResponseStatus.ANSWERED,
        )
    }

    @Synchronized
    fun dismiss(eventId: String): Boolean = updatePending(eventId) {
        it.copy(status = EmaResponseStatus.DISMISSED)
    }

    @Synchronized
    fun expire(eventId: String): Boolean = updatePending(eventId) {
        it.copy(status = EmaResponseStatus.EXPIRED)
    }

    private fun updatePending(eventId: String, transform: (EmaEvent) -> EmaEvent): Boolean {
        return store.updatePending(eventId, transform)
    }
}

fun emaEventToJson(event: EmaEvent): JsonObject {
    return JsonObject().apply {
        addProperty("schemaVersion", event.schemaVersion)
        addProperty("id", event.id)
        addProperty("scheduleDate", event.scheduleDate.toString())
        addProperty("scheduledAt", event.scheduledAt.toString())
        event.answeredAt?.let { addProperty("answeredAt", it.toString()) }
        event.answers?.let { answers ->
            addProperty("mood", answers.mood)
            addProperty("energy", answers.energy)
            addProperty("focus", answers.focus)
            addProperty("stress", answers.stress)
            if (answers.additional.isNotEmpty()) {
                add("additionalAnswers", JsonObject().apply {
                    answers.additional.toSortedMap().forEach { (key, value) -> addProperty(key, value) }
                })
            }
        }
        event.activity?.let { addProperty("activity", it) }
        event.activityLabel?.let { addProperty("activityLabel", it) }
        event.note?.let { addProperty("note", it) }
        addProperty("status", event.status.wireValue)
        addProperty("timezone", event.timezone)
    }
}

fun serializeEmaEvent(event: EmaEvent): String {
    return Gson().toJson(emaEventToJson(event))
}

fun deserializeEmaEvent(serialized: String): EmaEvent? {
    return try {
        val json = JsonParser.parseString(serialized).asJsonObject
        val schemaVersion = json.get("schemaVersion")?.asInt ?: 1
        if (schemaVersion != 1) return null

        val status = EmaResponseStatus.fromWireValue(json.get("status")?.asString ?: return null) ?: return null
        val mood = json.get("mood")?.asInt
        val energy = json.get("energy")?.asInt
        val focus = json.get("focus")?.asInt
        val stress = json.get("stress")?.asInt
        val core = listOf(mood, energy, focus, stress)
        val answers = if (core.all { it != null }) {
            val additional = json.getAsJsonObject("additionalAnswers")?.entrySet()
                ?.associate { (key, value) -> key to value.asInt }.orEmpty()
            EmaAnswers(checkNotNull(mood), checkNotNull(energy), checkNotNull(focus), checkNotNull(stress), additional)
        } else {
            null
        }
        val activity = json.get("activity")?.asString
        val answeredAt = json.get("answeredAt")?.asString?.let(Instant::parse)

        if (status == EmaResponseStatus.ANSWERED) {
            if (answers == null || answeredAt == null || activity == null) return null
        } else {
            if (answers != null || answeredAt != null || activity != null || core.any { it != null }) return null
        }

        EmaEvent(
            schemaVersion = schemaVersion,
            id = json.get("id")?.asString ?: return null,
            scheduleDate = json.get("scheduleDate")?.asString?.let(LocalDate::parse)
                ?: Instant.parse(json.get("scheduledAt")?.asString ?: return null)
                    .atZone(ZoneId.of(json.get("timezone")?.asString ?: return null)).toLocalDate(),
            scheduledAt = Instant.parse(json.get("scheduledAt")?.asString ?: return null),
            answeredAt = answeredAt,
            answers = answers,
            activity = activity,
            activityLabel = json.get("activityLabel")?.asString,
            note = json.get("note")?.asString,
            status = status,
            timezone = json.get("timezone")?.asString ?: return null,
        )
    } catch (_: Exception) {
        null
    }
}
