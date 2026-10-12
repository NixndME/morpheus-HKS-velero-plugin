package com.morpheuslab.hksvelero

import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HandlebarsRenderer

/** HKS Velero: install Velero on HKS clusters and back them up to S3. */
class HksVeleroPlugin extends Plugin {

    static final String PERMISSION = 'hks-velero'

    @Override
    String getCode() { 'hks-velero' }

    @Override
    void initialize() {
        setName('HKS Velero')
        setDescription('Back up and restore HKS clusters with Velero')
        // a plugin with controllers must own its renderer on Morpheus 9.0.2
        HandlebarsRenderer r = new HandlebarsRenderer('renderer', getClassLoader())
        r.registerAssetHelper(getName())
        r.registerNonceHelper(morpheus.getWebRequest())
        r.registerI18nHelper(this, morpheus)
        setRenderer(r)
        Permission p = Permission.build('HKS Velero', PERMISSION, [Permission.AccessType.none, Permission.AccessType.read, Permission.AccessType.full])
        p.subCategory = 'HKS Velero'
        setPermissions([p])
        registerProvider(new BackupTabProvider(this, morpheus))
        registerProvider(new VeleroIntegrationProvider(this, morpheus))
        registerProvider(new PageScript(this, morpheus))
        controllers.add(new VeleroController(this, morpheus))
        GitJob.startTimer(morpheus)
    }

    // Velero stays on the clusters when the plugin is removed. Use Remove on the integration page first.
    @Override
    void onDestroy() { GitJob.stopTimer() }
}
