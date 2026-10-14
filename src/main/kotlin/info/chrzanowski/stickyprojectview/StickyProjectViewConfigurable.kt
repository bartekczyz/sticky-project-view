package info.chrzanowski.stickyprojectview

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class StickyProjectViewConfigurable : BoundConfigurable(MyBundle.message("settings.displayName")) {
    private var enabled = true
    private var maxStickyHeightPercent = 50

    override fun getHelpTopic() = StickyProjectViewHelpProvider.HELP_TOPIC

    override fun createPanel() = panel {
        row {
            label(MyBundle.message("settings.support"))
        }
        row {
            browserLink(MyBundle.message("support.github"), StickyProjectViewSupport.GITHUB_URL)
            browserLink(MyBundle.message("support.kofi"), StickyProjectViewSupport.KO_FI_URL)
            browserLink(MyBundle.message("support.paypal"), StickyProjectViewSupport.PAYPAL_URL)
        }
        separator()
        row {
            checkBox(MyBundle.message("settings.enabled")).bindSelected(::enabled)
        }
        row(MyBundle.message("settings.maxHeight")) {
            spinner(10..90).bindIntValue(::maxStickyHeightPercent)
        }
        row {
            comment(MyBundle.message("settings.scope"))
        }
    }

    override fun reset() {
        val preferences = StickyProjectViewSettings.getInstance().preferences
        enabled = preferences.enabled
        maxStickyHeightPercent = preferences.maxStickyHeightPercent
        super.reset()
    }

    override fun apply() {
        super.apply()
        StickyProjectViewSettings.getInstance().updatePreferences(
            StickyProjectViewPreferences(enabled, maxStickyHeightPercent),
        )
    }
}
