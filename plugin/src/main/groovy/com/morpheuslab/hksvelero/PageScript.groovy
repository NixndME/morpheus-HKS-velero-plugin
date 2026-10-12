package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.GlobalUIComponentProvider
import com.morpheusdata.model.Account
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.Renderer
import com.morpheusdata.views.ViewModel

/** The integration page drops scripts inside plugin HTML, so its live log script is added here on every page. */
class PageScript implements GlobalUIComponentProvider {
    Plugin plugin
    MorpheusContext morpheus

    PageScript(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-velero-page-script' }
    String getName() { 'HKS Velero page script' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }
    Renderer<?> getRenderer() { plugin.renderer }

    Boolean show(User user, Account account) { Access.canRead(user) }

    HTMLResponse renderTemplate(User user, Account account) {
        ViewModel<Map> m = new ViewModel<>()
        m.object = [:]
        plugin.renderer.renderTemplate('hbs/page-script', m)
    }
}
