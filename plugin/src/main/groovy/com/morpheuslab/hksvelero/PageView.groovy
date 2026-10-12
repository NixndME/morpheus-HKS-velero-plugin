package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

/** Everything the integration page shows. */
@Slf4j
class PageView {

    String access = 'none'
    Map flash
    Map csrf
    Long integrationId
    Settings settings
    List<Map> clusters = []
    List<Map> recent = Collections.synchronizedList(new ArrayList<Map>())
    List<Map> schedules = Collections.synchronizedList(new ArrayList<Map>())
    String error
    String sub = 'clusters'
    String bundled

    boolean getCanRead() { access in ['read', 'full'] }
    boolean getCanManage() { access == 'full' }
    boolean getHasFlash() { flash != null }
    String getFlashLevel() { flash?.level }
    String getFlashText() { flash?.text }
    String getCsrfParam() { csrf?.param }
    String getCsrfToken() { csrf?.value }

    boolean getSubClusters() { sub == 'clusters' }
    boolean getSubS3() { sub == 's3' }
    boolean getSubGit() { sub == 'git' }
    boolean getSubActivity() { sub == 'activity' }
    boolean getHasClusters() { !clusters.isEmpty() }
    int getClusterCount() { clusters.size() }
    int getScheduleCount() { schedules.size() }
    int getProtectedCount() { clusters.count { it.protected } as int }
    boolean getNothingInstalled() { hasClusters && !clusters.any { it.hasVelero } }
    List<Map> getFoundVelero() { clusters.findAll { it.state == 'existing' } }
    boolean getHasFoundVelero() { !foundVelero.isEmpty() }

    List<Map> s3Rows = []
    List<Map> gitRows = []
    List<Map> s3Options = []
    List<Map> gitOptions = []
    boolean getHasS3Rows() { !s3Rows.isEmpty() }
    boolean getHasGitRows() { !gitRows.isEmpty() }
    boolean getHasS3Options() { !s3Options.isEmpty() }
    boolean getHasGitOptions() { !gitOptions.isEmpty() }
    int getS3Count() { s3Rows.size() }
    int getGitCount() { gitRows.size() }
    int getS3TestedCount() { s3Rows.count { it.tested } as int }
    int getGitTestedCount() { gitRows.count { it.tested } as int }
    String getStorageChip() { !s3Rows ? 'Not set' : "${s3TestedCount} of ${s3Count} tested" }
    String getStorageChipClass() { !s3Rows ? 'unknown' : (s3TestedCount == s3Count ? 'ok' : 'warn') }
    String getGitChip() { !gitRows ? 'Not set' : "${gitTestedCount} of ${gitCount} tested" }
    String getGitChipClass() { !gitRows ? 'unknown' : (gitTestedCount == gitCount ? 'ok' : 'warn') }

    /** One table row per S3 or Git target, with its form values. */
    static Map targetRow(Settings s, def t, String kind, List<Map> clusters, List<Map> sources) {
        String key = "${kind}:${t.id}"
        boolean tested = s.isTested(t)
        Map cfg = t.cfg
        Map r = [id: t.id, name: t.name, kind: kind, tested: tested, cfg: cfg, hasSecret: t.secret as boolean,
                 where: kind == 's3' ? t.where : (cfg.url ?: ''),
                 at: s.tested["${key}At".toString()] ? Format.ago(s.tested["${key}At".toString()] as Long) : '',
                 failed: s.tested["${key}Ok".toString()] == false, message: s.tested["${key}Message".toString()] ?: (kind == 's3' ? t.sourceError : null),
                 skipTls: cfg.skipTls == 'on',
                 used: clusters.findAll { (kind == 's3' ? it.s3Id : it.gitId) == t.id }*.name.join(', ')]
        r.chip = tested ? 'ok' : (r.failed ? 'bad' : 'warn')
        r.status = tested ? 'Tested' : (r.failed ? 'Test failed' : 'Not tested')
        r.options = sources.collect { it + [selected: (kind == 's3' ? cfg.source : cfg.integrationId as String) == (it.value as String)] }
        r
    }

    boolean getHasRecent() { !recent.isEmpty() }
    boolean getHasSchedules() { !schedules.isEmpty() }
    boolean getRunning() { Activity.anyRunning || recent.any { it.running } }
    String getLogText() { Activity.lines(null, 60).collect { "${Format.clock(it.at as Long)}  ${it.cluster}  ${it.text}" }.join('\n') }

    static PageView build(MorpheusContext morpheus, AccountIntegration integration) {
        PageView v = new PageView(integrationId: integration?.id, csrf: Web.csrf(), flash: Flash.take('page'))
        v.access = Me.access()
        Map tab = TabState.get(0L)
        if (tab.sub) { v.sub = tab.sub; tab.remove('sub') }
        v.bundled = Installer.bundledVersion()
        if (!v.canRead) return v
        try {
            v.settings = Settings.load(morpheus)
            v.s3Options = Settings.s3Sources(morpheus)
            v.gitOptions = Settings.gitIntegrations(morpheus).collect { gi -> [value: gi.id as String, label: "${gi.name} (${gi.type ?: gi.integrationType?.code})".toString()] }
            List<ComputeServerGroup> all = morpheus.services.cluster.list(new DataQuery()).findAll { Access.isHks(it) }
            // ask every cluster at the same time, so the page waits for the slowest one, not for all of them in a row
            def pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(8, all.size())))
            try {
                List futures = all.sort { it.name?.toLowerCase() }.collect { ComputeServerGroup c -> pool.submit({ row(morpheus, c, v) } as java.util.concurrent.Callable) }
                futures.each { v.clusters << (Map) it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            v.s3Rows = v.settings.s3Targets.collect { targetRow(v.settings, it, 's3', v.clusters, v.s3Options) }
            v.gitRows = v.settings.gitTargets.collect { targetRow(v.settings, it, 'git', v.clusters, v.gitOptions) }
            v.recent = new ArrayList<Map>(v.recent).sort { a, b -> (b.created ?: '') <=> (a.created ?: '') }.take(12)
            v.schedules = new ArrayList<Map>(v.schedules)
        } catch (Throwable t) {
            log.warn("HKS Velero: page failed: ${t}", t)
            v.error = "The page could not load everything: ${t.message}"
        }
        v
    }

    static Map row(MorpheusContext morpheus, ComputeServerGroup c, PageView v) {
        Map row = [id: c.id, name: c.name, url: "/infrastructure/clusters/${c.id}#!hks-velero-tab".toString()]
        S3Target t = v.settings.s3For(c.id)
        GitTarget g = v.settings.gitFor(c.id)
        row.s3Id = t?.id
        row.s3Name = t?.name ?: 'None'
        row.gitId = g?.id
        row.gitName = g?.name ?: 'None'
        row.s3Choices = v.settings.s3Targets.collect { [id: it.id, name: it.name, selected: it.id == t?.id] }
        row.gitChoices = [[id: 'none', name: 'None', selected: g == null]] + v.settings.gitTargets.collect { [id: it.id, name: it.name, selected: it.id == g?.id] }
        String job = Activity.running(c.id)
        Kube kube = Kube.of(morpheus, c)
        Map st = Installer.status(kube)
        row.state = st.state
        row.namespace = st.namespace
        row.version = st.version
        row.hasVelero = st.state in ['ours', 'used', 'existing']
        row.protected = st.state in ['ours', 'used']
        row.canInstall = st.state == 'none' && !job
        row.canUpdate = st.state == 'ours' && !job && st.version && st.version != Installer.bundledVersion()
        row.canUse = st.state == 'existing' && !job
        row.canRemove = row.protected && !job
        row.job = job
        switch (st.state) {
            case 'none': row.status = 'Not installed'; row.chip = 'unknown'; break
            case 'ours': row.status = st.ready ? 'Installed' : 'Starting'; row.chip = st.ready ? 'ok' : 'info'; break
            case 'used': row.status = 'Existing Velero'; row.chip = st.ready ? 'ok' : 'info'; break
            case 'existing': row.status = 'Velero found'; row.chip = 'info'; break
            default:
                String cs = c.status?.toString()
                boolean building = cs in ['initializing', 'syncing'] || (!kube.usable && !(cs in ['error', 'offline']))
                row.status = building ? 'Being built' : 'Offline'
                row.chip = building ? 'info' : 'bad'
                if (!building && !(st.error =~ /(?i)route|refused|timed out|no api access/)) row.detail = st.error
        }
        if (job) { row.status = job; row.chip = 'info' }
        if (row.protected) {
            Map loc = Storage.location(kube, st.namespace as String)
            row.location = loc.phase == 'missing' ? 'Not set' : loc.phase
            row.locationOk = loc.phase == 'Available'
            Velero vel = new Velero(kube: kube, ns: st.namespace as String)
            List<Map> backups = vel.backups()
            Map last = backups.find { it.status?.phase == 'Completed' }
            row.lastGood = last ? Format.ago(Format.ms(last.status.completionTimestamp as String)) : 'never'
            Map m = Marker.get(kube, st.namespace as String)
            row.gitLast = m.gitLast ? Format.ago(m.gitLast as Long) : ''
            row.gitWhen = m.gitCron ? Cron.describe(m.gitCron as String) : ''
            backups.take(5).each { Map b -> v.recent << BackupRow.of(b) + [cluster: c.name, clusterUrl: row.url] }
            vel.schedules().each { Map s -> v.schedules << ScheduleRow.of(s) + [cluster: c.name, clusterUrl: row.url] }
        }
        row
    }
}
