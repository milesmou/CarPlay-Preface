package com.shilapi.xcertplay.airplay

/** Fixed protocol fields only: never log arbitrary dictionaries, phone identifiers or text. */
internal object AirPlayModeDiagnostics {
    private val resourceFields = listOf("resourceID", "entity", "owner", "transferType", "transferPriority",
        "takeConstraint", "borrowConstraint", "unborrowConstraint")
    private val stateFields = listOf("appStateID", "entity", "state", "speechMode")

    fun summarize(params: Map<String, Any?>): String {
        val modes = params["modes"] as? Map<*, *> ?: params
        fun scalar(value: Any?): String? = when (value) {
            is Boolean -> value.toString()
            is Byte, is Short, is Int, is Long -> value.toString()
            else -> null
        }
        fun fields(value: Any?, names: List<String>): String? {
            scalar(value)?.let { return it }
            val map = value as? Map<*, *> ?: return null
            return names.mapNotNull { name -> scalar(map[name])?.let { "$name=$it" } }
                .joinToString(",").takeIf { it.isNotEmpty() }
        }
        val values = mutableListOf<String>()
        for (name in listOf("screen", "mainAudio", "speech", "phoneCall", "turnByTurn")) {
            fields(modes[name], resourceFields + stateFields)?.let { values.add("$name=[$it]") }
        }
        for ((name, names) in listOf("resources" to resourceFields, "appStates" to stateFields)) {
            (modes[name] as? List<*>)?.take(6)?.mapNotNull { fields(it, names) }
                ?.takeIf { it.isNotEmpty() }?.let { values.add("$name=[${it.joinToString(";")}]") }
        }
        return values.joinToString(" ").ifEmpty { "state=unavailable" }.take(1024)
    }
}
