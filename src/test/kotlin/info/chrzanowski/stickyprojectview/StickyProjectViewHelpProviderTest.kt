package info.chrzanowski.stickyprojectview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class StickyProjectViewHelpProviderTest : BasePlatformTestCase() {
    fun testSettingsHelpOpensPluginDocumentation() {
        val provider = StickyProjectViewHelpProvider()
        val configurable = StickyProjectViewConfigurable()
        assertEquals("StickyProjectView", provider.getHelpTopicPrefix())
        assertEquals(StickyProjectViewHelpProvider.HELP_TOPIC, configurable.getHelpTopic())
        assertEquals(StickyProjectViewHelpProvider.DOCUMENTATION_URL, provider.getHelpPageUrl(configurable.getHelpTopic()))
    }

    fun testUnrelatedHelpTopicsAreNotIntercepted() {
        val provider = StickyProjectViewHelpProvider()
        assertNull(provider.getHelpPageUrl("preferences.editor"))
        assertNull(provider.getHelpPageUrl("StickyProjectView.Unknown"))
    }
}