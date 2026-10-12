package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.AbstractGenericIntegrationProvider
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

/** Administration > Integrations > HKS Velero: clusters, storage, schedules and activity. */
class VeleroIntegrationProvider extends AbstractGenericIntegrationProvider {

    Plugin plugin
    MorpheusContext morpheus

    VeleroIntegrationProvider(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { Settings.TYPE }
    String getName() { 'HKS Velero' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    String getCategory() { 'other' }
    List<OptionType> getOptionTypes() { [] }
    void refresh(AccountIntegration integration) { }
    Icon getIcon() { new Icon(path: 'hks-velero.svg', darkPath: 'hks-velero-dark.svg') }

    HTMLResponse renderTemplate(AccountIntegration integration) {
        ViewModel<PageView> m = new ViewModel<>()
        m.object = PageView.build(morpheus, integration)
        plugin.renderer.renderTemplate('hbs/integration', m)
    }
}
