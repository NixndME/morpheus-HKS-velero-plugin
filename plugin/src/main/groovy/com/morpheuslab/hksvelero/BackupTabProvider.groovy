package com.morpheuslab.hksvelero

import com.morpheusdata.core.AbstractClusterTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/** The "Backup" tab, on HKS clusters that use Velero through this plugin. */
@Slf4j
class BackupTabProvider extends AbstractClusterTabProvider {

    // renderTemplate gets no user, show runs first on the same thread: keep the user and the Velero state
    private static final ThreadLocal<User> USER = new ThreadLocal<>()
    private static final ThreadLocal<Map> STATUS = new ThreadLocal<>()

    Plugin plugin
    MorpheusContext morpheus

    BackupTabProvider(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-velero-tab' }
    String getName() { 'Backup' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    Boolean show(ComputeServerGroup cluster, User user, Account account) {
        USER.set(user)
        if (!Access.isHks(cluster) || !Access.canRead(user)) return false
        try {
            Map st = Installer.status(Kube.of(morpheus, cluster))
            STATUS.set(st)
            st.state in ['ours', 'used']
        } catch (Throwable t) {
            log.warn("HKS Velero: show failed for cluster ${cluster.id}: ${t}")
            false
        }
    }

    HTMLResponse renderTemplate(ComputeServerGroup cluster) {
        User user = USER.get()
        Map st = STATUS.get()
        USER.remove()
        STATUS.remove()
        ClusterView v
        try {
            v = ClusterView.build(morpheus, cluster, Access.level(user), st)
        } catch (Throwable t) {
            log.error("HKS Velero: tab failed for cluster ${cluster.id}", t)
            v = new ClusterView(clusterId: cluster.id, clusterName: cluster.name, error: "The tab could not load: ${t.message}")
        }
        ViewModel<ClusterView> m = new ViewModel<>()
        m.object = v
        getRenderer().renderTemplate('hbs/backup-tab', m)
    }

    ContentSecurityPolicy getContentSecurityPolicy() { new ContentSecurityPolicy(connectSrc: "'self'") }
}
