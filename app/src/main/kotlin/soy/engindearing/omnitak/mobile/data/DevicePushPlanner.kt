package soy.engindearing.omnitak.mobile.data

/**
 * Pure planning step for "Push to device" (GAP-109a): turns the operator's
 * [MeshDeviceConfig] draft into the framed admin messages to send.
 *
 * Kept free of transports so the one security-relevant decision is unit
 * testable: the primary-channel rename (`set_channel` index 0) is emitted
 * ONLY when the radio's current PSK is known and non-empty, and that PSK is
 * echoed back verbatim. Meshtastic's `set_channel` replaces the whole
 * Channel struct, and an empty PSK on the primary channel means encryption
 * off, so a name-only write would silently strip encryption
 * (audit 2026-09-14, H4).
 */
object DevicePushPlanner {

    data class PushPlan(
        /** Fully framed ToRadio buffers, in send order. */
        val frames: List<ByteArray>,
        /** True when the channel-name write was omitted because the current PSK is unknown. */
        val channelSkipped: Boolean,
    )

    fun plan(dest: UInt, config: MeshDeviceConfig, knownPrimary: AdminResponse.Channel?): PushPlan {
        val frames = mutableListOf(
            AdminMessageSerializer.buildSetOwner(dest, config.longName, config.shortName),
            AdminMessageSerializer.buildSetDeviceRole(dest, config.role),
            AdminMessageSerializer.buildSetPositionBroadcastSecs(dest, config.positionBroadcastSecs),
        )
        val channelFrame = knownPrimary?.let {
            AdminMessageSerializer.buildSetPrimaryChannel(dest, config.channelName, it)
        }
        if (channelFrame != null) frames += channelFrame
        frames += AdminMessageSerializer.buildSetLoraPreset(dest, config.channelPreset)
        return PushPlan(frames = frames, channelSkipped = channelFrame == null)
    }
}
