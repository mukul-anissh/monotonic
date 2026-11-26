package com.monotonic.metronome.network

import com.monotonic.metronome.model.DiscoveredServer

object ServerSelectionPolicy {

    data class SelectionOutcome(
        val serverUrl: String,
        val activeServerName: String?,
        val autoSelected: Boolean
    )

    fun evaluate(
        discovered: List<DiscoveredServer>,
        currentServerUrl: String,
        hasExplicitSavedServer: Boolean,
        isEditorExpanded: Boolean,
        userManuallyPickedInSession: Boolean,
        isInRoom: Boolean
    ): SelectionOutcome {
        val trimmedCurrent = currentServerUrl.trim()
        val matched = discovered.firstOrNull { it.wsUrl.equals(trimmedCurrent, ignoreCase = true) }

        if (isInRoom) {
            return SelectionOutcome(
                serverUrl = currentServerUrl,
                activeServerName = matched?.name,
                autoSelected = false
            )
        }

        if (matched != null) {
            return SelectionOutcome(
                serverUrl = currentServerUrl,
                activeServerName = matched.name,
                autoSelected = false
            )
        }

        if (hasExplicitSavedServer && ServerUrlNormalizer.isRemoteSecureUrl(trimmedCurrent)) {
            return SelectionOutcome(
                serverUrl = currentServerUrl,
                activeServerName = null,
                autoSelected = false
            )
        }

        if (discovered.size == 1 && !isEditorExpanded && !userManuallyPickedInSession) {
            val isStaleOrDefault = !hasExplicitSavedServer ||
                trimmedCurrent.isEmpty() ||
                ServerUrlNormalizer.isLanOrLocalUrl(trimmedCurrent)
            if (isStaleOrDefault) {
                val only = discovered.first()
                return SelectionOutcome(
                    serverUrl = only.wsUrl,
                    activeServerName = only.name,
                    autoSelected = true
                )
            }
        }

        return SelectionOutcome(
            serverUrl = currentServerUrl,
            activeServerName = null,
            autoSelected = false
        )
    }
}
