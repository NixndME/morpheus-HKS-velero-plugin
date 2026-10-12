package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/** Every action of the integration page and the Backup tab. Each one sends the user back with a message. */
@Slf4j
class VeleroController implements PluginController {

    // the sub tab to show after an action
    static final Map<String, String> SUBS = ['schedule': 'schedules', 'schedule-remove': 'schedules', 'schedule-pause': 'schedules',
        'restore': 'restores', 'restore-items': 'restores', 'git-export': 'git', 'git-schedule': 'git', 'git-restore': 'git',
        'other': 'other', 'schedule-run': 'backups', 'schedule-edit': 'schedules', 'backup': 'backups', 'delete-backup': 'backups',
        'install': 'clusters', 'use': 'clusters', 'remove': 'clusters', 'storage': 's3', 'test-s3': 's3',
        'git-settings': 'git', 'test-git': 'git', 'assign': 'clusters']

    static final String NAME_RE = /^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$/

    Plugin plugin
    MorpheusContext morpheus

    VeleroController(Plugin plugin, MorpheusContext morpheus) { this.plugin = plugin; this.morpheus = morpheus }

    String getCode() { 'hks-velero-controller' }
    String getName() { 'HKS Velero Controller' }
    MorpheusContext getMorpheus() { morpheus }
    Plugin getPlugin() { plugin }

    List<Route> getRoutes() {
        Permission read = Permission.build(HksVeleroPlugin.PERMISSION, 'read')
        Permission full = Permission.build(HksVeleroPlugin.PERMISSION, 'full')
        [Route.build('/hks-velero/status', 'status', read),
         Route.build('/hks-velero/activity', 'activity', read),
         Route.build('/hks-velero/journal', 'log', read),
         Route.build('/hks-velero/install', 'install', full),
         Route.build('/hks-velero/use', 'use', full),
         Route.build('/hks-velero/remove', 'remove', full),
         Route.build('/hks-velero/storage', 'storage', full),
         Route.build('/hks-velero/test-s3', 'testS3', full),
         Route.build('/hks-velero/backup', 'backup', full),
         Route.build('/hks-velero/restore', 'restore', full),
         Route.build('/hks-velero/delete-backup', 'deleteBackup', full),
         Route.build('/hks-velero/schedule', 'schedule', full),
         Route.build('/hks-velero/schedule-remove', 'scheduleRemove', full),
         Route.build('/hks-velero/schedule-pause', 'schedulePause', full),
         Route.build('/hks-velero/restore-items', 'restoreItems', full),
         Route.build('/hks-velero/git-settings', 'gitSettings', full),
         Route.build('/hks-velero/test-git', 'testGit', full),
         Route.build('/hks-velero/git-export', 'gitExport', full),
         Route.build('/hks-velero/git-schedule', 'gitSchedule', full),
         Route.build('/hks-velero/git-restore', 'gitRestore', full),
         Route.build('/hks-velero/other', 'other', full),
         Route.build('/hks-velero/contents', 'contents', read),
         Route.build('/hks-velero/changes', 'changes', read),
         Route.build('/hks-velero/schedule-run', 'scheduleRun', full),
         Route.build('/hks-velero/schedule-edit', 'scheduleEdit', full),
         Route.build('/hks-velero/target-delete', 'deleteTarget', full),
         Route.build('/hks-velero/page-html', 'pageHtml', read),
         Route.build('/hks-velero/tab-html', 'tabHtml', read),
         Route.build('/hks-velero/others', 'others', read),
         Route.build('/hks-velero/assign', 'assign', full)]
    }

    // ---------- read ----------

    /** Live state of running backups and restores of one cluster, for the tab script. */
    def status(ViewModel<Map> model) {
        noCache(model)
        if (!Access.canRead(model.user)) return JsonResponse.of([error: 'no access'])
        ComputeServerGroup c = cluster(model)
        if (!c) return JsonResponse.of([error: 'cluster not found'])
        Map ctx = velero(c)
        if (!ctx.vel) return JsonResponse.of([error: ctx.error])
        Velero vel = ctx.vel
        List<Map> running = []
        vel.backups().take(10).collect { BackupRow.of(it) }.findAll { it.running }.each { Map b ->
            running << [kind: 'backup', name: b.name, phase: b.phase, items: b.items, percent: b.percent]
        }
        vel.restores().take(10).collect { RestoreRow.of(it) }.findAll { it.running }.each { Map r ->
            running << [kind: 'restore', name: r.name, phase: r.phase, items: r.items, percent: r.percent]
        }
        List<String> lines = []
        running.each { Map r -> lines.addAll(vel.liveLines(r.name as String, 40)) }
        String job = Activity.running(c.id)
        if (job) {
            running << [kind: 'job', name: job, phase: 'Running', items: '', percent: 50]
            lines.addAll(Activity.lines(c.id, 40).collect { "${Format.clock(it.at as Long)} ${it.text}".toString() })
        }
        JsonResponse.of([running: running, lines: lines.takeRight(60)])
    }

    /** Lines for the live log box on the integration page. */
    def activity(ViewModel<Map> model) {
        noCache(model)
        if (!Access.canRead(model.user)) return JsonResponse.of([error: 'no access'])
        List<String> lines = Activity.lines(null, 60).collect { "${Format.clock(it.at as Long)}  ${it.cluster}  ${it.text}".toString() }
        JsonResponse.of([running: Activity.anyRunning, lines: lines])
    }

    /** Log of a finished backup or restore as a plain page. */
    def log(ViewModel<Map> model) {
        noCache(model)
        if (!Access.canRead(model.user)) return HTMLResponse.success('No access.')
        ComputeServerGroup c = cluster(model)
        String name = param(model, 'name')
        String kind = param(model, 'kind') == 'restore' ? 'RestoreLog' : 'BackupLog'
        if (!c || !(name ==~ NAME_RE)) return param(model, 'format') == 'json' ? JsonResponse.of([error: 'Not found.']) : HTMLResponse.success('Not found.')
        Map ctx = velero(c)
        String text = ctx.vel ? ((Velero) ctx.vel).log(kind, name) : ctx.error
        List<String> all = (text ?: '').readLines().collect { LogLine.plain(it) ?: it }
        List<String> bad = all.findAll { it =~ /^\S+ (WARNING|ERROR) / }
        if (param(model, 'format') == 'json') return JsonResponse.of([lines: all.takeRight(5000), bad: bad.size(), error: ctx.vel ? null : ctx.error])
        String lines = all.join('\n')
        HTMLResponse.success("<!doctype html><html><head><meta charset=\"utf-8\"><title>${esc(name)} log</title>" +
            "<style>body{margin:0;background:#1f2428;color:#d6dde2;font:12px/1.5 monospace}h1{font:600 14px sans-serif;padding:12px 16px;margin:0;border-bottom:1px solid #3a4148}" +
            "pre{padding:12px 16px;margin:0;white-space:pre-wrap}</style></head><body><h1>${kind == 'BackupLog' ? 'Backup' : 'Restore'} ${esc(name)} on ${esc(c.name)}</h1>" +
            (bad ? "<h1 style=\"border:0;color:#e0b341\">Warnings and errors (${bad.size()})</h1><pre style=\"color:#e0b341\">${esc(bad.join('\n'))}</pre><h1>Full log</h1>" : '') +
            "<pre>${esc(lines ?: 'The log is empty.')}</pre></body></html>")
    }

    // ---------- integration page ----------

    def install(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        ComputeServerGroup c = cluster(model)
        if (!c || !Access.isHks(c)) return toPage(model, 'danger', 'Cluster not found.')
        boolean update = Installer.status(Kube.of(morpheus, c)).state == 'ours'
        if (!Activity.start(c.id, update ? 'Updating' : 'Installing')) return toPage(model, 'warning', "${c.name} is busy. Try again soon.")
        Kube kube = Kube.of(morpheus, c)
        Thread.start("hks-velero-install-${c.id}") {
            try {
                Activity.say(c.id, c.name, update ? "Updating Velero to ${Installer.bundledVersion()}" : 'Install started')
                String err = Installer.install(kube) { String line -> Activity.say(c.id, c.name, line) }
                // read the settings again: the bucket of this cluster may have changed while it installed
                Settings now = Settings.load(morpheus)
                S3Target t = now.s3For(c.id)
                if (!err && now.isTested(t)) {
                    Activity.say(c.id, c.name, "Adding the backup location (${t.name})")
                    err = Storage.applyLocation(kube, Installer.NS, t, c.name)
                }
                Activity.say(c.id, c.name, err ? "Install failed: ${err}" : 'Done')
            } catch (Throwable t) {
                log.error("HKS Velero: install on ${c.name} failed", t)
                Activity.say(c.id, c.name, "Install failed: ${t.message}")
            } finally {
                Activity.done(c.id)
            }
        }
        toPage(model, 'info', update ? "Updating Velero on ${c.name}." : "Installing Velero on ${c.name}.")
    }

    def use(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        ComputeServerGroup c = cluster(model)
        if (!c || !Access.isHks(c)) return toPage(model, 'danger', 'Cluster not found.')
        Kube kube = Kube.of(morpheus, c)
        Map st = Installer.status(kube)
        if (st.state != 'existing') return toPage(model, 'warning', "No Velero found on ${c.name}.")
        String err = Installer.useExisting(kube, st.namespace as String)
        Settings s = Settings.load(morpheus)
        if (!err && s.isTested(s.s3For(c.id))) err = Storage.applyLocation(kube, st.namespace as String, s.s3For(c.id), c.name)
        Activity.say(c.id, c.name, err ?: "Using the Velero in namespace ${st.namespace}")
        err ? toPage(model, 'danger', err) : toPage(model, 'success', "${c.name} uses its Velero now.")
    }

    def remove(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        ComputeServerGroup c = cluster(model)
        if (!c) return toPage(model, 'danger', 'Cluster not found.')
        if (param(model, 'confirm') != c.name) return toPage(model, 'warning', 'Name does not match.')
        Kube kube = Kube.of(morpheus, c)
        boolean used = Installer.status(kube).state == 'used'
        if (!Activity.start(c.id, 'Removing')) return toPage(model, 'warning', "${c.name} is busy. Try again soon.")
        Thread.start("hks-velero-remove-${c.id}") {
            try {
                String err = Installer.remove(kube) { String line -> Activity.say(c.id, c.name, line) }
                if (err) Activity.say(c.id, c.name, err)
            } catch (Throwable t) {
                Activity.say(c.id, c.name, "Remove failed: ${t.message}")
            } finally {
                Activity.done(c.id)
            }
        }
        toPage(model, 'info', used ? "Removing from ${c.name}. Its Velero stays." : "Removing Velero from ${c.name}.")
    }

    /** Add or change one S3 target, then test it. */
    def storage(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        Settings s = Settings.load(morpheus)
        if (!s.integration) return toPage(model, 'danger', 'Add the HKS Velero integration first.')
        String name = trim(param(model, 'name')) ?: 'Default'
        String source = param(model, 's3Source') ?: ''
        if (source && !(source ==~ /^(bucket|cloud):\d+$/)) source = ''
        String endpoint = trim(param(model, 'endpoint'))
        if (endpoint && !(endpoint ==~ /^https?:\/\/[^\s\/]+(\/.*)?$/)) return toPage(model, 'danger', 'Endpoint must start with http:// or https://')
        String bucket = trim(param(model, 'bucket'))
        if (!source.startsWith('bucket:') && !(bucket ==~ /^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/)) return toPage(model, 'danger', 'Bucket name is not valid.')
        S3Target t = s.s3(param(model, 'targetId'))
        if (!t) { t = new S3Target(id: Settings.newId(name, s.s3Targets*.id)); s.s3Targets << t }
        t.cfg = [name: name, source: source, endpoint: endpoint, region: trim(param(model, 'region')) ?: 'us-east-1', bucket: bucket,
                 prefix: trim(param(model, 'prefix'))?.replaceAll(/^\/+|\/+$/, ''), accessKey: trim(param(model, 'accessKey')),
                 skipTls: param(model, 'skipTls') == 'on' ? 'on' : '']
        String secret = param(model, 'secretKey')
        if (secret && !source) {
            Settings.writeSecret(morpheus, s.integration, "s3/${t.id}", secret.trim())
            t.secret = secret.trim()
        }
        if (source) t.resolve(morpheus)
        s.tested.remove("s3:${t.id}".toString())
        s.save(morpheus)
        if (t.sourceError) return toPage(model, 'danger', t.sourceError)
        if (!t.complete) return toPage(model, 'warning', 'Saved. Add the keys to test.')
        test(model, s, t)
    }

    def testS3(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        Settings s = Settings.load(morpheus)
        S3Target t = s.s3(param(model, 'targetId'))
        if (!t?.complete) return toPage(model, 'warning', 'Fill in the bucket first.')
        test(model, s, t)
    }

    /** Test a bucket, remember the result, then point the clusters that use it at it. */
    private def test(ViewModel<Map> model, Settings s, S3Target t) {
        String err = t.client().test(t.cfg.prefix as String)
        s.mark("s3:${t.id}", t.fingerprint(), err)
        s.save(morpheus)
        if (err) return toPage(model, 'danger', "${t.name}: test failed: ${err}")
        List<String> failed = []
        hksClusters().findAll { s.s3For(it.id)?.id == t.id }.each { ComputeServerGroup c ->
            Kube kube = Kube.of(morpheus, c)
            Map st = Installer.status(kube)
            if (!(st.state in ['ours', 'used'])) return
            String e = Storage.applyLocation(kube, st.namespace as String, t, c.name)
            Activity.say(c.id, c.name, e ?: "Backup location set (${t.name})")
            if (e) failed << "${c.name}: ${e}".toString()
        }
        if (failed) return toPage(model, 'warning', "${t.name}: test passed. Could not update ${failed.join('; ')}")
        toPage(model, 'success', "${t.name}: test passed.")
    }

    def deleteTarget(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        Settings s = Settings.load(morpheus)
        String kind = param(model, 'kind') == 'git' ? 'git' : 's3'
        String id = param(model, 'targetId')
        def t = kind == 's3' ? s.s3(id) : s.git(id)
        if (!t) return toPage(model, 'danger', 'Not found.')
        List<String> users = hksClusters().findAll { c -> (kind == 's3' ? s.s3For(c.id) : s.gitFor(c.id))?.id == id && Installer.status(Kube.of(morpheus, c)).state in ['ours', 'used'] }*.name
        if (users) return toPage(model, 'warning', "${t.name} is used by ${users.join(', ')}. Pick another one for them first.")
        if (kind == 's3') s.s3Targets.remove(t) else s.gitTargets.remove(t)
        s.assign.each { k, v -> if (v instanceof Map && v[kind] == id) v.remove(kind) }
        Settings.deleteSecret(morpheus, s.integration, "${kind}/${id}")
        s.save(morpheus)
        toPage(model, 'success', "${t.name} removed.")
    }

    /** Which bucket and repository one cluster uses. */
    def assign(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        ComputeServerGroup c = cluster(model)
        if (!c || !Access.isHks(c)) return toPage(model, 'danger', 'Cluster not found.')
        Settings s = Settings.load(morpheus)
        S3Target before = s.s3For(c.id)
        Map a = [:]
        if (s.s3(param(model, 's3'))) a.s3 = param(model, 's3')
        String g = param(model, 'git')
        if (g == 'none' || s.git(g)) a.git = g
        s.assign[c.id as String] = a
        s.save(morpheus)
        S3Target now = s.s3For(c.id)
        String msg = "${c.name} saved."
        if (now && now.id != before?.id) {
            Kube kube = Kube.of(morpheus, c)
            Map st = Installer.status(kube)
            if (st.state in ['ours', 'used'] && s.isTested(now)) {
                String e = Storage.applyLocation(kube, st.namespace as String, now, c.name)
                Activity.say(c.id, c.name, e ?: "Backups now go to ${now.name}")
                msg = e ? "Saved, but the location failed: ${e}" : "${c.name}: new backups go to ${now.name}. Old backups stay in ${before?.name}."
            } else if (!s.isTested(now)) {
                msg = "${c.name} saved. Test ${now.name} to start using it."
            }
        }
        toPage(model, 'success', msg)
    }

    private List<ComputeServerGroup> hksClusters() {
        morpheus.services.cluster.list(new com.morpheusdata.core.data.DataQuery()).findAll { Access.isHks(it) }
    }

    // ---------- backup tab ----------

    def backup(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String name = trim(param(model, 'name')) ?: "${slug(ctx.cluster.name)}-${stamp()}".toString()
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Use small letters, digits and dashes.')
        List<String> nss = values(model, 'namespace')
        if (param(model, 'scope') != 'picked') nss = []
        else if (!nss) return toTab(model, ctx.cluster, 'warning', 'Pick a namespace.')
        int keep = days(param(model, 'keepDays'))
        Map r = ((Velero) ctx.vel).backupNow(name, nss, keep, param(model, 'volumes') == 'on', model.user?.username)
        Activity.say(ctx.cluster.id, ctx.cluster.name, r.error ? "Backup ${name} not started: ${r.error}" : "Backup ${name} started")
        if (!r.error && param(model, 'git') == 'on' && Settings.load(morpheus).with { it.isTested(it.gitFor(ctx.cluster.id)) }) {
            GitJob.exportNow(morpheus, ctx.cluster, "with backup ${name}")
        }
        r.error ? toTab(model, ctx.cluster, 'danger', "Backup failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', "Backup started.")
    }

    def restore(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String backupName = param(model, 'backup')
        if (!(backupName ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Backup not found.')
        List<String> nss = param(model, 'scope') == 'picked' ? values(model, 'namespace') : []
        if (param(model, 'scope') == 'picked' && !nss) return toTab(model, ctx.cluster, 'warning', 'Pick a namespace.')
        String target = trim(param(model, 'target'))
        if (target && !(target ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Namespace name is not valid.')
        if (target && nss.size() != 1) return toTab(model, ctx.cluster, 'warning', 'Copy works for one namespace only.')
        String name = "${backupName.take(40)}-r-${stamp()}".toString()
        Map r = ((Velero) ctx.vel).restore(name, backupName, nss, target, param(model, 'overwrite') == 'on', model.user?.username)
        Activity.say(ctx.cluster.id, ctx.cluster.name, r.error ? "Restore of ${backupName} not started: ${r.error}" : "Restore ${name} started")
        r.error ? toTab(model, ctx.cluster, 'danger', "Restore failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', "Restore started.")
    }

    def deleteBackup(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String name = param(model, 'backup')
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Backup not found.')
        if (param(model, 'confirm') != name) return toTab(model, ctx.cluster, 'warning', 'Name does not match.')
        Map r = ((Velero) ctx.vel).deleteBackup(name)
        Activity.say(ctx.cluster.id, ctx.cluster.name, r.error ? "Delete of ${name} failed: ${r.error}" : "Deleting backup ${name}")
        r.error ? toTab(model, ctx.cluster, 'danger', "Delete failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', "Deleting ${name}.")
    }

    def schedule(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String cron = param(model, 'preset') == 'custom' ? trim(param(model, 'cron')) : param(model, 'preset')
        if (!Cron.valid(cron)) return toTab(model, ctx.cluster, 'danger', 'Not a valid cron line.')
        String name = trim(param(model, 'name')) ?: slug("${ctx.cluster.name} ${Cron.describe(cron).replace(' UTC', '')}").take(50).replaceAll(/-+$/, '')
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Use small letters, digits and dashes.')
        List<String> nss = param(model, 'scope') == 'picked' ? values(model, 'namespace') : []
        if (param(model, 'scope') == 'picked' && !nss) return toTab(model, ctx.cluster, 'warning', 'Pick a namespace.')
        Map r = ((Velero) ctx.vel).schedule(name, Cron.PRESETS[cron] ?: cron, nss, days(param(model, 'keepDays')), param(model, 'volumes') == 'on')
        Activity.say(ctx.cluster.id, ctx.cluster.name, r.error ? "Schedule ${name} not added: ${r.error}" : "Schedule ${name} added")
        r.error ? toTab(model, ctx.cluster, 'danger', "Schedule failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', "Schedule added.")
    }

    def scheduleRemove(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String name = param(model, 'schedule')
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Schedule not found.')
        Map r = ((Velero) ctx.vel).deleteSchedule(name)
        Activity.say(ctx.cluster.id, ctx.cluster.name, r.error ? "Remove of schedule ${name} failed: ${r.error}" : "Schedule ${name} removed")
        r.error ? toTab(model, ctx.cluster, 'danger', "Remove failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', "Schedule removed.")
    }

    def schedulePause(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String name = param(model, 'schedule')
        boolean pause = param(model, 'pause') == 'true'
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Schedule not found.')
        Map r = ((Velero) ctx.vel).pauseSchedule(name, pause)
        r.error ? toTab(model, ctx.cluster, 'danger', "Change failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', pause ? 'Schedule paused.' : 'Schedule resumed.')
    }

    /** The integration page HTML alone, so the page script can swap it in without reloading Morpheus. */
    def pageHtml(ViewModel<Map> model) {
        noCache(model)
        if (!Access.canRead(model.user)) return HTMLResponse.success('')
        ViewModel<PageView> m = new ViewModel<>()
        m.object = PageView.build(morpheus, Settings.find(morpheus))
        plugin.renderer.renderTemplate('hbs/integration', m)
    }

    /** The Backup tab HTML alone, for the same reason. */
    def tabHtml(ViewModel<Map> model) {
        noCache(model)
        if (!Access.canRead(model.user)) return HTMLResponse.success('')
        ComputeServerGroup c = cluster(model)
        if (!c) return HTMLResponse.success('')
        ViewModel<ClusterView> m = new ViewModel<>()
        m.object = ClusterView.build(morpheus, c, Access.level(model.user))
        plugin.renderer.renderTemplate('hbs/backup-tab', m)
    }

    /** Other cluster folders in this cluster's bucket. Loaded only when the Other clusters tab opens. */
    def others(ViewModel<Map> model) {
        noCache(model)
        Map ctx = forRead(model)
        if (ctx.back) return HTMLResponse.success('<div class="hv-bad">Cluster not found.</div>')
        Settings s = Settings.load(morpheus)
        S3Target t = s.s3For(ctx.cluster.id)
        Map view = [canBackup: Access.canManage(model.user) && s.isTested(t), clusterId: ctx.cluster.id, csrfParam: Web.csrf().param, csrfToken: Web.csrf().value]
        if (!s.isTested(t)) view.error = 'The bucket of this cluster is not tested.'
        else {
            String own = Storage.folder(t.cfg.prefix as String, ctx.cluster.name).tokenize('/').last()
            Map f = t.client().folders(t.cfg.prefix as String)
            if (f.error) view.error = f.error
            else {
                Velero vel = ctx.vel
                List<String> shown = Storage.others(vel.kube, vel.ns)
                view.folders = (f.folders as List<String>).findAll { it != own }.collect { [name: it, shown: it in shown] }
            }
        }
        ViewModel<Map> m = new ViewModel<>()
        m.object = view
        plugin.renderer.renderTemplate('hbs/others', m)
    }

    /** What is in a backup, by namespace, for the Contents panel. */
    def contents(ViewModel<Map> model) {
        noCache(model)
        Map ctx = forJson(model)
        if (ctx.error) return JsonResponse.of(ctx)
        Map list = ((Velero) ctx.vel).resourceList(param(model, 'backup'))
        if (list.error) return JsonResponse.of([error: list.error])
        List<Map> tree = Items.tree(list.items as Map)
        JsonResponse.of([tree: tree, total: tree.sum { it.count } ?: 0])
    }

    /** New and gone objects since a backup, for the Changes panel. */
    def changes(ViewModel<Map> model) {
        noCache(model)
        Map ctx = forJson(model)
        if (ctx.error) return JsonResponse.of(ctx)
        Velero vel = ctx.vel
        String b = param(model, 'backup')
        Map list = vel.resourceList(b)
        if (list.error) return JsonResponse.of([error: list.error])
        List<String> nss = (vel.backup(b)?.spec?.includedNamespaces ?: []) as List<String>
        Map d = Items.compare(vel.kube, list.items as Map, nss == ['*'] ? [] : nss)
        JsonResponse.of([added: (d.added as List).take(300), removed: (d.removed as List).take(300), addedCount: d.added.size(), removedCount: d.removed.size()])
    }

    def scheduleRun(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String name = param(model, 'schedule')
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Schedule not found.')
        Map r = ((Velero) ctx.vel).runSchedule(name, model.user?.username)
        Activity.say(ctx.cluster.id, ctx.cluster.name, r.error ? "Run of ${name} failed: ${r.error}" : "Backup from schedule ${name} started")
        r.error ? toTab(model, ctx.cluster, 'danger', "Run failed: ${r.error}") : toTab(model, ctx.cluster, 'success', 'Backup started.')
    }

    def scheduleEdit(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String name = param(model, 'schedule')
        if (!(name ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Schedule not found.')
        String cron = param(model, 'preset') == 'custom' ? trim(param(model, 'cron')) : param(model, 'preset')
        if (!Cron.valid(cron)) return toTab(model, ctx.cluster, 'danger', 'Not a valid cron line.')
        List<String> nss = param(model, 'scope') == 'picked' ? values(model, 'namespace') : []
        if (param(model, 'scope') == 'picked' && !nss) return toTab(model, ctx.cluster, 'warning', 'Pick a namespace.')
        Map r = ((Velero) ctx.vel).editSchedule(name, Cron.PRESETS[cron] ?: cron, nss, days(param(model, 'keepDays')), param(model, 'volumes') == 'on')
        r.error ? toTab(model, ctx.cluster, 'danger', "Save failed: ${r.error}") : toTab(model, ctx.cluster, 'success', 'Schedule saved.')
    }

    private Map forJson(ViewModel<Map> model) {
        if (!Access.canRead(model.user)) return [error: 'No access.']
        ComputeServerGroup c = cluster(model)
        if (!c) return [error: 'Cluster not found.']
        if (!(param(model, 'backup') ==~ NAME_RE)) return [error: 'Backup not found.']
        Map ctx = velero(c)
        ctx.vel ? ctx : [error: ctx.error]
    }

    def restoreItems(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String b = param(model, 'backup')
        if (!(b ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Backup not found.')
        List<String> keys = ((model.request?.getParameterValues('item') ?: []) as List<String>).findAll { Items.validKey(it) }
        if (!keys) return toTab(model, ctx.cluster, 'warning', 'Pick an item.')
        if (keys.size() > 500) return toTab(model, ctx.cluster, 'warning', 'Pick 500 items or fewer.')
        String target = trim(param(model, 'target'))
        if (target && !(target ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Namespace name is not valid.')
        Velero vel = ctx.vel
        Map c = vel.contents(b, keys as Set)
        if (c.error) return toTab(model, ctx.cluster, 'danger', "Could not read the backup: ${c.error}")
        Map r = Items.restore(vel.kube, c.objects as Map, target, param(model, 'overwrite') == 'on')
        int missing = keys.size() - (c.objects as Map).size()
        String msg = "Restored ${count(r.done.size(), 'item')}."
        if (r.skipped) msg += " Skipped ${r.skipped.size()} that exist."
        if (r.failed) msg += " Failed: ${r.failed.take(5).join('; ')}."
        if (missing > 0) msg += " ${missing} not found."
        Activity.say(ctx.cluster.id, ctx.cluster.name, "Picked restore from ${b}: ${r.done.size()} done, ${r.skipped.size()} skipped, ${r.failed.size()} failed")
        Marker.addHistory(vel.kube, vel.ns, 'restoreHistory', [at: System.currentTimeMillis(), kind: 'items', from: b, target: target ?: '',
            by: model.user?.username ?: '', done: r.done.take(100), skipped: r.skipped.take(100), failed: r.failed.take(50), missing: missing])
        toTab(model, ctx.cluster, r.failed ? 'warning' : 'success', msg)
    }

    /** Add or change one Git target, then test it. */
    def gitSettings(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        Settings s = Settings.load(morpheus)
        if (!s.integration) return toPage(model, 'danger', 'Add the HKS Velero integration first.')
        String name = trim(param(model, 'name')) ?: 'Default'
        String iid = param(model, 'gitIntegration')
        String url = trim(param(model, 'gitUrl'))
        if (url && !(url ==~ /^https?:\/\/\S+$/)) return toPage(model, 'danger', 'Use an https:// URL.')
        String branch = trim(param(model, 'gitBranch'))
        if (branch && !(branch ==~ /^[A-Za-z0-9._\/-]{1,100}$/)) return toPage(model, 'danger', 'Branch name is not valid.')
        GitTarget g = s.git(param(model, 'targetId'))
        if (!g) { g = new GitTarget(id: Settings.newId(name, s.gitTargets*.id)); s.gitTargets << g }
        g.cfg = [name: name, integrationId: iid?.isLong() ? iid : '', url: url, branch: branch,
                 folder: trim(param(model, 'gitFolder'))?.replaceAll(/^\/+|\/+$/, ''), user: trim(param(model, 'gitUser')),
                 skipTls: param(model, 'gitSkipTls') == 'on' ? 'on' : '']
        String token = param(model, 'gitToken')
        if (token) {
            Settings.writeSecret(morpheus, s.integration, "git/${g.id}", token.trim())
            g.secret = token.trim()
        }
        g.resolve(morpheus)
        s.tested.remove("git:${g.id}".toString())
        s.save(morpheus)
        if (!g.cfg.url) return toPage(model, 'warning', 'Saved. Add the URL to test.')
        doTestGit(model, s, g)
    }

    def testGit(ViewModel<Map> model) {
        if (!Access.canManage(model.user)) return toPage(model, 'danger', 'Not allowed for your role.')
        Settings s = Settings.load(morpheus)
        GitTarget g = s.git(param(model, 'targetId'))
        if (!g?.complete) return toPage(model, 'warning', 'Fill in the repository first.')
        doTestGit(model, s, g)
    }

    private def doTestGit(ViewModel<Map> model, Settings s, GitTarget g) {
        String err = g.client().test()
        s.mark("git:${g.id}", g.fingerprint(), err)
        s.save(morpheus)
        err ? toPage(model, 'danger', "${g.name}: test failed: ${err}") : toPage(model, 'success', "${g.name}: test passed.")
    }

    def gitExport(ViewModel<Map> model) {
        Map ctx = forGit(model)
        if (ctx.back) return ctx.back
        GitJob.exportNow(morpheus, ctx.cluster, "started by ${model.user?.username}") ?
            toTab(model, ctx.cluster, 'info', 'Push started.') :
            toTab(model, ctx.cluster, 'warning', 'Busy. Try again soon.')
    }

    def gitSchedule(ViewModel<Map> model) {
        Map ctx = forGit(model)
        if (ctx.back) return ctx.back
        String preset = param(model, 'preset')
        String cron = preset == 'custom' ? trim(param(model, 'cron')) : preset
        if (preset == 'off') cron = null
        else if (!Cron.valid(cron)) return toTab(model, ctx.cluster, 'danger', 'Not a valid cron line.')
        Map st = Installer.status(Kube.of(morpheus, ctx.cluster))
        Map r = Marker.update(Kube.of(morpheus, ctx.cluster), st.namespace as String, [gitCron: cron ? (Cron.PRESETS[cron] ?: cron) : null])
        r.error ? toTab(model, ctx.cluster, 'danger', "Save failed: ${r.error}") :
            toTab(model, ctx.cluster, 'success', cron ? "Schedule: ${Cron.describe(cron)}." : 'Schedule off.')
    }

    def gitRestore(ViewModel<Map> model) {
        Map ctx = forGit(model)
        if (ctx.back) return ctx.back
        String ns = param(model, 'namespace')
        if (!(ns ==~ NAME_RE)) return toTab(model, ctx.cluster, 'warning', 'Pick a namespace.')
        String target = trim(param(model, 'target'))
        if (target && !(target ==~ NAME_RE)) return toTab(model, ctx.cluster, 'danger', 'Namespace name is not valid.')
        Map r = GitJob.restore(morpheus, ctx.cluster, ns, target, param(model, 'overwrite') == 'on')
        if (r.error) return toTab(model, ctx.cluster, 'danger', "Restore failed: ${r.error}")
        String msg = "Restored ${count(r.done.size(), 'object')}."
        if (r.skipped) msg += " Skipped ${r.skipped.size()} that exist."
        if (r.failed) msg += " Failed: ${r.failed.take(5).join('; ')}."
        Activity.say(ctx.cluster.id, ctx.cluster.name, "Restore of ${ns} from Git: ${r.done.size()} done, ${r.skipped.size()} skipped, ${r.failed.size()} failed")
        Map vctx = velero(ctx.cluster)
        if (vctx.vel) Marker.addHistory(((Velero) vctx.vel).kube, ((Velero) vctx.vel).ns, 'restoreHistory', [at: System.currentTimeMillis(), kind: 'git',
            from: "Git ${r.commit}".toString(), what: ns, target: target ?: '', by: model.user?.username ?: '',
            done: r.done.take(100), skipped: r.skipped.take(100), failed: r.failed.take(50)])
        toTab(model, ctx.cluster, r.failed ? 'warning' : 'success', msg)
    }

    /** Show or hide the backups of another cluster's folder (read only location). */
    def other(ViewModel<Map> model) {
        Map ctx = forTab(model)
        if (ctx.back) return ctx.back
        String folder = param(model, 'folder')
        if (!(folder ==~ /^[a-z0-9][a-z0-9.-]{0,60}$/)) return toTab(model, ctx.cluster, 'danger', 'Folder name is not valid.')
        Velero vel = ctx.vel
        if (param(model, 'hide') == 'true') {
            Storage.removeOther(vel.kube, vel.ns, folder)
            Activity.say(ctx.cluster.id, ctx.cluster.name, "Stopped showing backups of ${folder}")
            return toTab(model, ctx.cluster, 'success', "Hidden.")
        }
        String err = Storage.addOther(vel.kube, vel.ns, Settings.load(morpheus).s3For(ctx.cluster.id), folder)
        Activity.say(ctx.cluster.id, ctx.cluster.name, err ?: "Showing backups of ${folder} (read only)")
        err ? toTab(model, ctx.cluster, 'danger', err) :
            toTab(model, ctx.cluster, 'success', "Backups of ${folder} show up in a minute.")
    }

    // ---------- helpers ----------

    private Map forGit(ViewModel<Map> model) {
        ComputeServerGroup c = cluster(model)
        if (!c) return [back: toPage(model, 'danger', 'Cluster not found.')]
        if (!Access.canManage(model.user)) return [back: toTab(model, c, 'danger', 'Not allowed for your role.')]
        Settings s = Settings.load(morpheus)
        if (!s.isTested(s.gitFor(c.id))) return [back: toTab(model, c, 'warning', 'Test Git on the integration page first.')]
        [cluster: c]
    }

    private Map forRead(ViewModel<Map> model) {
        ComputeServerGroup c = cluster(model)
        if (!c) return [back: toPage(model, 'danger', 'Cluster not found.')]
        if (!Access.canRead(model.user)) return [back: toTab(model, c, 'danger', 'Not allowed for your role.')]
        Map ctx = velero(c)
        if (!ctx.vel) return [back: toTab(model, c, 'danger', ctx.error as String)]
        [cluster: c, vel: ctx.vel]
    }

    /** Checks shared by every tab action. */
    private Map forTab(ViewModel<Map> model) {
        ComputeServerGroup c = cluster(model)
        if (!c) return [back: toPage(model, 'danger', 'Cluster not found.')]
        if (!Access.canManage(model.user)) return [back: toTab(model, c, 'danger', 'Not allowed for your role.')]
        Map ctx = velero(c)
        if (!ctx.vel) return [back: toTab(model, c, 'danger', ctx.error as String)]
        Settings s = Settings.load(morpheus)
        if (!s.isTested(s.s3For(c.id))) return [back: toTab(model, c, 'warning', 'Test the storage on the integration page first.')]
        [cluster: c, vel: ctx.vel]
    }

    private Map velero(ComputeServerGroup c) {
        Kube kube = Kube.of(morpheus, c)
        Map st = Installer.status(kube)
        st.state in ['ours', 'used'] ? [vel: new Velero(kube: kube, ns: st.namespace as String)] :
            [error: 'Velero is not set up for this cluster.']
    }

    /** The cluster of the request, only when Morpheus lets this user open it (tenant, groups, permissions). */
    private ComputeServerGroup cluster(ViewModel<Map> model) {
        String id = param(model, 'clusterId')
        if (!id?.isLong() || !model.user) return null
        if (!Me.seesCluster(id as Long)) {
            log.info("HKS Velero: ${model.user.username} may not open cluster ${id}")
            return null
        }
        try { morpheus.services.cluster.get(id as Long) } catch (Throwable t) { null }
    }

    private static String param(ViewModel<Map> model, String name) { model.request?.getParameter(name) as String }

    private static List<String> values(ViewModel<Map> model, String name) {
        ((model.request?.getParameterValues(name) ?: []) as List<String>).findAll { it ==~ NAME_RE }
    }

    private static String trim(String s) { s?.trim() ?: '' }

    private static int days(String s) { s?.isInteger() ? Math.max(1, Math.min(3650, s as int)) : 30 }

    private static String stamp() { java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss')) }

    private static String slug(String s) { (s ?: 'cluster').toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-+|-+$/, '').take(30) ?: 'cluster' }

    static String count(int n, String word) { "${n} ${word}${n == 1 ? '' : 's'}" }

    static String esc(String s) { (s ?: '').replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;') }

    private def toPage(ViewModel<Map> model, String level, String text) {
        Flash.set('page', level, text)
        String sub = SUBS[(model.request?.requestURI as String)?.replaceAll('.*/', '')]
        if (sub) TabState.get(0L).sub = sub
        if (ajax(model)) return JsonResponse.of([ok: true])
        String id = param(model, 'integrationId')
        redirect(model, id?.isLong() ? "/admin/integrations/${id}" : '/admin/integrations')
    }

    private def toTab(ViewModel<Map> model, ComputeServerGroup c, String level, String text) {
        if (text) Flash.set("cluster-${c.id}", level, text)
        String sub = SUBS[(model.request?.requestURI as String)?.replaceAll('.*/', '')]
        if (sub) TabState.get(c.id).sub = sub
        if (ajax(model)) return JsonResponse.of([ok: true])
        redirect(model, "/infrastructure/clusters/${c.id}#!hks-velero-tab")
    }

    /** Browsers must not keep old plugin answers: a normal reload has to show the real state. */
    static void noCache(ViewModel<Map> model) {
        try { model.response?.setHeader('Cache-Control', 'no-store') } catch (Throwable ignored) { }
    }

    /** The page script sends forms in the background and then swaps in fresh HTML. */
    private static boolean ajax(ViewModel<Map> model) { model.request?.getHeader('X-HV-Ajax') == '1' }

    private static def redirect(ViewModel<Map> model, String url) {
        try {
            def resp = model.response
            if (resp != null && !resp.committed) {
                resp.sendRedirect(url)
                return HTMLResponse.success('')
            }
        } catch (Throwable ignored) { }
        HTMLResponse.success("<!doctype html><meta http-equiv=\"refresh\" content=\"0;url=${url}\"><a href=\"${url}\">Back</a>")
    }
}
