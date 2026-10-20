package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.hud.CarPlayCallCard
import com.shilapi.xcertplay.hud.CarPlayCallControl
import java.util.concurrent.Executor

enum class CarPlayPhoneCommand(val hidIndex: Int) {
    ANSWER(1), END(3);

    fun accepts(call: CarPlayCallControl): Boolean =
        this == END || call.phase == CarPlayCallCard.Phase.RINGING
}

/** A successful return means queued, not that the iPhone has completed the operation. */
internal fun enqueuePhoneCommand(executor: Executor, command: CarPlayPhoneCommand,
    current: () -> CarPlayCallControl?, eligible: () -> Boolean, send: (Int) -> Unit,
    report: (String) -> Unit = {}): Boolean = try {
    val call = current()
    if (call == null || !command.accepts(call) || !eligible()) false
    else {
        executor.execute {
            val written = runCatching {
                if (current() != call || !eligible()) false
                else { send(command.hidIndex); true }
            }.getOrDefault(false)
            report("Phone key transport command=$command reportWritten=$written handsetResult=unconfirmed")
        }
        true
    }
} catch (_: Exception) { false }
