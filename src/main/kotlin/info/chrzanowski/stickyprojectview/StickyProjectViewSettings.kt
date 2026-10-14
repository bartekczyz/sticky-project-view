package info.chrzanowski.stickyprojectview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.OptionTag

data class StickyProjectViewPreferences(
    @field:OptionTag @JvmField val enabled: Boolean = true,
    @field:OptionTag @JvmField val maxStickyHeightPercent: Int = 50,
)

interface StickyProjectViewSettingsListener {
    fun settingsChanged(preferences: StickyProjectViewPreferences)

    companion object {
        val TOPIC = Topic.create("Sticky Project View preferences", StickyProjectViewSettingsListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(
    name = "StickyProjectViewSettings",
    storages = [Storage("sticky-project-view.xml")],
    category = SettingsCategory.UI,
)
class StickyProjectViewSettings :
    SerializablePersistentStateComponent<StickyProjectViewPreferences>(StickyProjectViewPreferences()) {
    val preferences: StickyProjectViewPreferences
        get() = state.let { it.copy(maxStickyHeightPercent = it.maxStickyHeightPercent.coerceIn(10, 90)) }

    override fun loadState(state: StickyProjectViewPreferences) {
        val previous = preferences
        super.loadState(state.copy(maxStickyHeightPercent = state.maxStickyHeightPercent.coerceIn(10, 90)))
        val loaded = preferences
        if (previous != loaded) publishUpdate(loaded)
    }

    fun updatePreferences(preferences: StickyProjectViewPreferences) {
        val normalized = preferences.copy(maxStickyHeightPercent = preferences.maxStickyHeightPercent.coerceIn(10, 90))
        if (this.preferences == normalized) return
        updateState { normalized }
        publishUpdate(normalized)
    }

    private fun publishUpdate(preferences: StickyProjectViewPreferences) {
        ApplicationManager.getApplication().messageBus.syncPublisher(StickyProjectViewSettingsListener.TOPIC)
            .settingsChanged(preferences)
    }

    companion object {
        fun getInstance(): StickyProjectViewSettings = service()
    }
}