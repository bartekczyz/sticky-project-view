package info.chrzanowski.stickyprojectview

import com.intellij.openapi.help.WebHelpProvider

class StickyProjectViewHelpProvider : WebHelpProvider() {
    override fun getHelpTopicPrefix() = "StickyProjectView"

    override fun getHelpPageUrl(helpTopicId: String): String? =
        DOCUMENTATION_URL.takeIf { helpTopicId == HELP_TOPIC }

    companion object {
        const val HELP_TOPIC = "StickyProjectView.Settings"
        const val DOCUMENTATION_URL = "https://github.com/hsz/sticky-project-view#controls"
    }
}