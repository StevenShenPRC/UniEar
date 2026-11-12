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
        options(title, listOf(false, true), value, { if (it) "开启" else "关闭" }, request = request)

    val features = runtimeState().features
    features.get<HuaweiAncLevelFeatureState>()?.let { state ->
        val labels = listOf("普通", "轻度", "深度", "动态", "人声增强", "普通")
        val levels = state.supported.sortedBy { it.ordinal }
        for ((mode, title) in listOf(NoiseMode.ANC to "降噪强度", NoiseMode.TRANSPARENCY to "通透强度")) {
            options(title, levels.filter { it.domainNoiseMode() == mode }, state.current,
                { labels[it.ordinal] }, request = { HuaweiControlRequest.SetAncLevel(it) })
        }
    }
    features.get<HonorAncDepthFeatureState>()?.let { state ->
        val labels = listOf("智能", "轻度", "中度", "深度")
        options("降噪强度", state.supported.sortedBy { it.ordinal }, state.current,
            { labels[it.ordinal] }, request = { HonorControlRequest.SetAncDepth(it) })
    }
    features.get<EdifierGameModeFeatureState>()?.let {
        toggle("低延迟游戏模式", it.enabled, { EdifierControlRequest.SetGameMode(it) })
    }
    features.get<FloS2SoundEffectFeatureState>()?.let {
        val labels = listOf("标准", "游泳", "人声", "增强")
        options("音效", FloS2SoundEffect.entries, it.effect, { effect -> labels[effect.ordinal] },
            request = { FloS2ControlRequest.SetSoundEffect(it) })
    }
    features.get<SamsungBudsSettingsFeatureState>()?.let { state ->
        options("通透音量", 0..3, state.ambientVolume, { "${it + 1} 档" },
            request = { SamsungControlRequest.SetAmbientVolume(it) })
        val presets = listOf("关闭", "低音增强", "柔和", "动态", "清晰", "高音增强")
        options("均衡器", -1..4, if (state.equalizerEnabled) state.equalizerPreset else -1, { presets[it + 1] }) {
            SamsungControlRequest.SetEqualizer(it >= 0, if (it >= 0) it else state.equalizerPreset.coerceIn(0, 4))
        }
        toggle("语音检测", state.voiceDetectEnabled, { SamsungControlRequest.SetVoiceDetect(it) })
        toggle("单耳噪声控制", state.noiseControlsWithOneEarbud, { SamsungControlRequest.SetNoiseControlsWithOneEarbud(it) })
        toggle("无缝连接", state.seamlessConnectionEnabled, { SamsungControlRequest.SetSeamlessConnection(it) })
        toggle("通话侧音", state.sidetoneEnabled, { SamsungControlRequest.SetSidetone(it) })
        toggle("超高通透音量", state.extraHighAmbientEnabled, { SamsungControlRequest.SetExtraHighAmbient(it) })
        toggle("触控锁", state.touchpadLocked, { SamsungControlRequest.SetTouchpadLock(it) })
        val gestures = listOf(
            Triple(SamsungTouchGesture.SINGLE_TAP, "单击", state.singleTapEnabled),
            Triple(SamsungTouchGesture.DOUBLE_TAP, "双击", state.doubleTapEnabled),
            Triple(SamsungTouchGesture.TRIPLE_TAP, "三击", state.tripleTapEnabled),
            Triple(SamsungTouchGesture.TOUCH_AND_HOLD, "长按", state.touchHoldEnabled),
            Triple(SamsungTouchGesture.DOUBLE_TAP_CALL, "通话双击", state.doubleTapCallEnabled),
            Triple(SamsungTouchGesture.TOUCH_AND_HOLD_CALL, "通话长按", state.touchHoldCallEnabled),
        )
        for ((gesture, title, enabled) in gestures) {
            toggle(title, enabled) { SamsungControlRequest.SetTouchGesture(gesture, it) }
        }
        toggle("双击耳机边缘", state.outsideDoubleTapEnabled, { SamsungControlRequest.SetOutsideDoubleTap(it) })
        val actions = listOf("语音助手", "切换降噪", "音量", "Spotify")
        val cycles = listOf("降噪 / 通透", "降噪 / 关闭", "通透 / 关闭")
        for (side in SamsungTouchSide.entries) {
            val left = side == SamsungTouchSide.LEFT
            val sideLabel = if (left) "左耳" else "右耳"
            val action = if (left) state.displayedLeftAction else state.displayedRightAction
            val cycle = if (left) state.displayedLeftCycle else state.displayedRightCycle
            options("${sideLabel}长按动作", SamsungTouchAction.entries, action,
                { actions[it.ordinal] }, confirmation(if (left) state.leftActionPending else state.rightActionPending,
                    state.touchHoldActionsTimedOut)) { SamsungControlRequest.SetTouchHoldAction(side, it) }
            if (action == SamsungTouchAction.NOISE_CONTROL) {
                options("${sideLabel}长按切换范围", SamsungNoiseCycle.entries, cycle,
                    { cycles[it.ordinal] }, confirmation(state.touchHoldCyclesPending, state.touchHoldCyclesTimedOut)) {
                    SamsungControlRequest.SetTouchHoldNoiseCycle(side, it)
                }
            }
        }
        options("双耳长按动作（左耳 · 右耳）", orderedPairs(SamsungTouchAction.entries),
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
            options("双耳长按切换范围（左耳 · 右耳）", orderedPairs(SamsungNoiseCycle.entries),
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
    pending -> "等待耳机确认"
    timedOut -> "耳机未确认，已显示最后收到的状态"
    else -> null
}
