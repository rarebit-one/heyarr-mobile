package one.rarebit.heyarr.mobile.playback

/** One report from the player: the source position and what just happened. */
data class PlaybackProgress(val seconds: Double, val completed: Boolean, val event: Event) {
    enum class Event { TICK, PAUSED, RESUMED, ENDED, LEFT }
}
