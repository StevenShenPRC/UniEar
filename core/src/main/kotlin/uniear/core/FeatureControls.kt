package uniear.core

import dev.hyperears.integration.*

data class ControlChoice(val label: String, val request: ControlRequest, val selected: Boolean)
data class ControlGroup(val title: String, val choices: List<ControlChoice>, val hint: String? = null)

/** Build controls from reported features and the adapter's request contract. */
internal fun EarbudAdapter.controlGroups(): List<ControlGroup> = buildList {
    fun <T> options(
        title: String, values: Iterable<T>, selected: T?, label: (T) -> String,
        hint: String? = null, request: (T) -> ControlRequest,
    ) {
        val choices = values.mapNotNull { value ->
            val command = request(value)
            if (supportsControl(command)) ControlChoice(label(value), command, value == selected) else null
        }
        if (choices.isNotEmpty()) add(ControlGroup(title, choices, hint))
    }

    fun toggle(title: String, value: Boolean, request: (Boolean) -> ControlRequest) =
        options(title, listOf(false, true), value, { if (it) tr("on") else tr("off") }, request = request)

    val features = runtimeState().features
    features.get<HuaweiAncLevelFeatureState>()?.let { state ->
        val labels = listOf(tr("normal"), tr("light"), tr("deep"), tr("dynamic"), tr("voice_boost"), tr("normal"))
        val levels = state.supported.sortedBy { it.ordinal }
        for ((mode, title) in listOf(NoiseMode.ANC to tr("anc_strength"), NoiseMode.TRANSPARENCY to tr("transparency_strength"))) {
            options(title, levels.filter { it.domainNoiseMode() == mode }, state.current,
                { labels[it.ordinal] }, request = { HuaweiControlRequest.SetAncLevel(it) })
        }
    }
    features.get<HonorAncDepthFeatureState>()?.let { state ->
        val labels = listOf(tr("smart"), tr("light"), tr("medium"), tr("deep"))
        options(tr("anc_strength"), state.supported.sortedBy { it.ordinal }, state.current,
            { labels[it.ordinal] }, request = { HonorControlRequest.SetAncDepth(it) })
    }
    features.get<EdifierGameModeFeatureState>()?.let {
        toggle(tr("low_latency_gaming_mode"), it.enabled, { EdifierControlRequest.SetGameMode(it) })
    }
    features.get<FloS2SoundEffectFeatureState>()?.let {
        val labels = listOf(tr("standard"), tr("swimming"), tr("voice"), tr("enhanced"))
        options(tr("sound_effects"), FloS2SoundEffect.entries, it.effect, { effect -> labels[effect.ordinal] },
            request = { FloS2ControlRequest.SetSoundEffect(it) })
    }
    features.get<SamsungBudsSettingsFeatureState>()?.let { state ->
        options(tr("transparency_volume"), 0..3, state.ambientVolume, { tr("level_0", it + 1) },
            request = { SamsungControlRequest.SetAmbientVolume(it) })
        val presets = listOf(tr("off"), tr("bass_boost"), tr("soft"), tr("dynamic"), tr("clear"), tr("treble_boost"))
        options(tr("equalizer"), -1..4, if (state.equalizerEnabled) state.equalizerPreset else -1, { presets[it + 1] }) {
            SamsungControlRequest.SetEqualizer(it >= 0, if (it >= 0) it else state.equalizerPreset.coerceIn(0, 4))
        }
        toggle(tr("voice_detection"), state.voiceDetectEnabled, { SamsungControlRequest.SetVoiceDetect(it) })
        toggle(tr("single_earbud_noise_control"), state.noiseControlsWithOneEarbud, { SamsungControlRequest.SetNoiseControlsWithOneEarbud(it) })
        toggle(tr("seamless_connection"), state.seamlessConnectionEnabled, { SamsungControlRequest.SetSeamlessConnection(it) })
        toggle(tr("call_sidetone"), state.sidetoneEnabled, { SamsungControlRequest.SetSidetone(it) })
        toggle(tr("extra_high_transparency_volume"), state.extraHighAmbientEnabled, { SamsungControlRequest.SetExtraHighAmbient(it) })
        toggle(tr("touch_lock"), state.touchpadLocked, { SamsungControlRequest.SetTouchpadLock(it) })
        val gestures = listOf(
            Triple(SamsungTouchGesture.SINGLE_TAP, tr("tap"), state.singleTapEnabled),
            Triple(SamsungTouchGesture.DOUBLE_TAP, tr("double_tap"), state.doubleTapEnabled),
            Triple(SamsungTouchGesture.TRIPLE_TAP, tr("triple_tap"), state.tripleTapEnabled),
            Triple(SamsungTouchGesture.TOUCH_AND_HOLD, tr("touch_and_hold"), state.touchHoldEnabled),
            Triple(SamsungTouchGesture.DOUBLE_TAP_CALL, tr("double_tap_during_calls"), state.doubleTapCallEnabled),
            Triple(SamsungTouchGesture.TOUCH_AND_HOLD_CALL, tr("touch_and_hold_during_calls"), state.touchHoldCallEnabled),
        )
        for ((gesture, title, enabled) in gestures) {
            toggle(title, enabled) { SamsungControlRequest.SetTouchGesture(gesture, it) }
        }
        toggle(tr("double_tap_earbud_edge"), state.outsideDoubleTapEnabled, { SamsungControlRequest.SetOutsideDoubleTap(it) })
        val actions = listOf(tr("voice_assistant"), tr("switch_noise_control"), tr("volume"), "Spotify")
        val cycles = listOf(tr("noise_canceling_transparency"), tr("noise_canceling_off"), tr("transparency_off"))
        for (side in SamsungTouchSide.entries) {
            val left = side == SamsungTouchSide.LEFT
            val sideLabel = if (left) tr("left") else tr("right")
            val action = if (left) state.displayedLeftAction else state.displayedRightAction
            val cycle = if (left) state.displayedLeftCycle else state.displayedRightCycle
            options(tr("0_touch_and_hold_action", sideLabel), SamsungTouchAction.entries, action,
                { actions[it.ordinal] }, confirmation(if (left) state.leftActionPending else state.rightActionPending,
                    state.touchHoldActionsTimedOut)) { SamsungControlRequest.SetTouchHoldAction(side, it) }
            if (action == SamsungTouchAction.NOISE_CONTROL) {
                options(tr("0_touch_and_hold_modes", sideLabel), SamsungNoiseCycle.entries, cycle,
                    { cycles[it.ordinal] }, confirmation(state.touchHoldCyclesPending, state.touchHoldCyclesTimedOut)) {
                    SamsungControlRequest.SetTouchHoldNoiseCycle(side, it)
                }
            }
        }
        options(tr("touch_and_hold_actions_left_right"), orderedPairs(SamsungTouchAction.entries),
            state.displayedLeftAction to state.displayedRightAction,
            { (left, right) -> "${actions[left.ordinal]} · ${actions[right.ordinal]}" },
            confirmation(state.touchHoldActionsPending, state.touchHoldActionsTimedOut)) { (left, right) ->
            SamsungControlRequest.SetTouchHoldActions(left, right)
        }
        val leftCycle = state.displayedLeftCycle
        val rightCycle = state.displayedRightCycle
        if (leftCycle != null && rightCycle != null &&
            state.displayedLeftAction == SamsungTouchAction.NOISE_CONTROL &&
            state.displayedRightAction == SamsungTouchAction.NOISE_CONTROL
        ) {
            options(tr("touch_and_hold_modes_left_right"), orderedPairs(SamsungNoiseCycle.entries),
                leftCycle to rightCycle, { (left, right) -> "${cycles[left.ordinal]} · ${cycles[right.ordinal]}" },
                confirmation(state.touchHoldCyclesPending, state.touchHoldCyclesTimedOut)) { (left, right) ->
                SamsungControlRequest.SetTouchHoldNoiseCycles(left, right)
            }
        }
    }
}

private fun <T> orderedPairs(values: List<T>): List<Pair<T, T>> =
    values.flatMap { left -> values.map { right -> left to right } }

private fun confirmation(pending: Boolean, timedOut: Boolean): String? = when {
    pending -> tr("waiting_for_headphone_confirmation")
    timedOut -> tr("headphones_did_not_confirm_showing_the_last")
    else -> null
}
