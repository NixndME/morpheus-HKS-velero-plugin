package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerGroup

/** Everything the Backup tab shows. */
class ClusterView {

    Long clusterId
    String clusterName
    String access = 'none'
    Map csrf
    Map flash
    String error
    String namespace
    String version
    boolean ready
    Map location = [:]
    boolean storageTested
    List<Map> backups = []
    List<Map> restores = []
    List<Map> gitRuns = []
    String gitWeb
    List<Map> schedules = []
    List<String> namespaces = []
    String loadedAt
    boolean gitTested
    String s3Name
    String s3Where
    String gitName
    String gitUrl
    Map marker = [:]
    String job
    String sub = 'backups'
    String bundled
    String state

    boolean getCanManage() { access == 'full' }
    boolean getHasError() { error != null }
    boolean getHasFlash() { flash != null }
    String getFlashLevel() { flash?.level }
    String getFlashText() { flash?.text }
    String getCsrfParam() { csrf?.param }
    String getCsrfToken() { csrf?.value }

    boolean getLocationOk() { location.phase == 'Available' }
    String getLocationText() {
        switch (location.phase) {
            case 'Available': return 'Storage reachable'
            case 'missing': return 'No storage set'
            case 'Unavailable': return 'Storage not reachable'
            default: return 'Checking storage'
        }
    }
    String getLocationChip() { locationOk ? 'ok' : (location.phase == 'Unavailable' || location.phase == 'missing' ? 'bad' : 'info') }
    String getLocationWhere() { location.bucket ? "${location.bucket}/${location.prefix ?: ''}" : '' }

    boolean getCanBackup() { canManage && locationOk && storageTested }
    String getBlockedReason() {
        if (!canManage) return 'Read only access.'
        if (!storageTested) return 'Storage not tested. See Administration > Integrations > HKS Velero.'
        if (!locationOk) return "Storage not reachable yet${location.message ? ': ' + location.message : ''}."
        null
    }

    boolean getHasBackups() { !backups.isEmpty() }
    boolean getHasRestores() { !restores.isEmpty() }
    boolean getHasSchedules() { !schedules.isEmpty() }
    int getTotal() { backups.size() }
    int getSucceeded() { backups.count { it.phase == 'Completed' } as int }
    int getFailed() { backups.count { it.phase in ['Failed', 'PartiallyFailed', 'FailedValidation'] } as int }
    String getLastGood() { backups.find { it.phase == 'Completed' }?.when ?: 'never' }
    String getNextRun() {
        Map n = schedules.findAll { it.nextMs }.min { it.nextMs }
        n ? n.next : 'none'
    }
    boolean getRunning() { job || backups.any { it.running } || restores.any { it.running } }

    boolean isSub(String n) { sub == n }
    boolean getSubBackups() { sub == 'backups' }
    boolean getSubSchedules() { sub == 'schedules' }
    boolean getSubRestores() { sub == 'restores' }
    boolean getSubGit() { sub == 'git' }
    boolean getSubOther() { sub == 'other' }
    int getScheduleCount() { schedules.size() }
    int getRestoreCount() { restores.size() }
    boolean getNeedsUpdate() { canManage && state == 'ours' && bundled && version && version != bundled }
    boolean getCanGit() { canManage && gitTested }
    String getGitLast() { marker.gitLast ? Format.ago(marker.gitLast as Long) : 'never' }
    String getGitResult() { marker.gitResult ?: '' }
    boolean getGitFailed() { marker.gitOk == 'false' }
    String getGitCron() { marker.gitCron ?: '' }
    String getGitWhen() { marker.gitCron ? Cron.describe(marker.gitCron as String) : 'Off' }
    String getGitNext() {
        if (!marker.gitCron) return ''
        Long n = Cron.next(marker.gitCron as String, System.currentTimeMillis())
        n ? Format.ago(n) : ''
    }
    List<String> getGitNamespaces() { marker.gitNamespaces ? (marker.gitNamespaces as String).split(',') as List : [] }
    boolean getHasGitCopy() { !gitNamespaces.isEmpty() }
    String getGitBlocked() { !canManage ? 'Read only access.' : (!gitTested ? 'Git not set. See Administration > Integrations > HKS Velero.' : null) }
    String getRunningJson() {
        groovy.json.JsonOutput.toJson((backups.findAll { it.running }.collect { [kind: 'backup', name: it.name] }) +
            (restores.findAll { it.running }.collect { [kind: 'restore', name: it.name] }))
    }

    String getPanelCss() {
        css('b', backups) + css('r', restores) + css('c', schedules)
    }

    // click a row to open its details: one radio group per table
    private static String css(String g, List<Map> rows) {
        rows.collect { Map b ->
            String c = "#hv-${g}-${b.index}:checked~.hv-subs"
            "${c} .hv-${g}row-${b.index} td{background:var(--hv-sel)}${c} .hv-${g}open-${b.index}{display:none}" +
                "${c} .hv-${g}shut-${b.index}{display:block}${c} tr.hv-${g}d-${b.index}{display:table-row}"
        }.join('')
    }

    boolean getHasGitRuns() { !gitRuns.isEmpty() }

    static ClusterView build(MorpheusContext morpheus, ComputeServerGroup cluster, String access, Map known = null) {
        ClusterView v = new ClusterView(clusterId: cluster.id, clusterName: cluster.name, access: access,
            csrf: Web.csrf(), flash: Flash.take("cluster-${cluster.id}"), loadedAt: Format.clock(System.currentTimeMillis()))
        Kube kube = Kube.of(morpheus, cluster)
        Map st = known ?: Installer.status(kube)
        if (!(st.state in ['ours', 'used'])) {
            v.error = 'Velero is not set up here. See Administration > Integrations > HKS Velero.'
            return v
        }
        v.namespace = st.namespace
        v.version = st.version
        v.state = st.state
        v.bundled = Installer.bundledVersion()
        v.ready = st.ready
        Settings s = Settings.load(morpheus)
        S3Target t = s.s3For(cluster.id)
        GitTarget g = s.gitFor(cluster.id)
        v.storageTested = s.isTested(t)
        // the location in the cluster must match the bucket picked for it; fix it when not (unchanged is left alone)
        if (v.storageTested && !Activity.running(cluster.id)) Storage.applyLocation(kube, v.namespace, t, cluster.name)
        v.location = Storage.location(kube, v.namespace)
        v.s3Where = t ? "${t.name}: ${t.cfg.bucket ?: ''} / ${Storage.folder(t.cfg.prefix as String, cluster.name)}" : ''
        v.s3Name = t?.name
        v.gitTested = s.isTested(g)
        v.gitName = g?.name
        v.gitUrl = g?.cfg?.url
        v.marker = Marker.get(kube, v.namespace)
        v.gitWeb = GitRow.web(v.gitUrl)
        v.gitRuns = Marker.history(v.marker, 'gitHistory').take(10).collect { GitRow.of(it, v.gitWeb) }
        v.job = Activity.running(cluster.id)
        Velero vel = new Velero(kube: kube, ns: v.namespace)
        int i = 0
        vel.backups().each { Map b -> v.backups << (BackupRow.of(b) + [index: i++]) }
        List<Map> rs = vel.restores().take(20).collect { RestoreRow.of(it) }
        rs.addAll(Marker.history(v.marker, 'restoreHistory').collect { HistoryRow.of(it) })
        int ri = 0
        v.restores = rs.sort { a, b -> (b.createdMs ?: 0L) <=> (a.createdMs ?: 0L) }.take(30).collect { it + [index: ri++] }
        int si = 0
        v.schedules = vel.schedules().collect { Map sch ->
            Map row = ScheduleRow.of(sch)
            List<Map> runs = v.backups.findAll { it.schedule == row.name }
            // Velero counts only timed runs as last backup; Run now backups count too
            if (runs && (row.last == 'not yet' || (runs[0].createdMs ?: 0L) > (Format.ms(sch.status?.lastBackup as String) ?: 0L))) row.last = runs[0].when
            row + [index: si++, runs: runs.take(8)]
        }
        Map tab = TabState.get(cluster.id)
        if (tab.sub) { v.sub = tab.sub; tab.remove('sub') }
        Map nsList = kube.get('/api/v1/namespaces')
        v.namespaces = ((nsList.data?.items ?: []) as List).collect { it.metadata.name as String }
            .findAll { !(it in [v.namespace, 'kube-node-lease', 'kube-public']) }.sort()
        v
    }
}
