package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Git copy jobs: export now, restore from Git, and the schedule check that runs every minute. */
@Slf4j
class GitJob {

    static ScheduledExecutorService timer

    /** Run an export in the background. Returns false when something already runs on that cluster. */
    static boolean exportNow(MorpheusContext morpheus, ComputeServerGroup c, String why) {
        if (!Activity.start(c.id, 'Pushing to Git')) return false
        Thread.start("hks-velero-git-${c.id}") {
            try {
                run(morpheus, c, why)
            } catch (Throwable t) {
                log.error("HKS Velero: Git export of ${c.name} failed", t)
                Activity.say(c.id, c.name, "Git export failed: ${t.message}")
            } finally {
                Activity.done(c.id)
            }
        }
        true
    }

    static void run(MorpheusContext morpheus, ComputeServerGroup c, String why) {
        Settings s = Settings.load(morpheus)
        Kube kube = Kube.of(morpheus, c)
        Map st = Installer.status(kube)
        GitTarget g = s.gitFor(c.id)
        if (!s.isTested(g)) { Activity.say(c.id, c.name, 'Git push skipped: Git not tested'); return }
        Activity.say(c.id, c.name, "Git push started (${why})")
        Map<String, String> files = new Exporter(kube: kube, say: { String line -> Activity.say(c.id, c.name, line) }).files()
        Activity.say(c.id, c.name, "Writing ${files.size()} files and pushing to ${g.name}")
        String stamp = java.time.Instant.now().toString().substring(0, 19).replace('T', ' ')
        Map r = g.client().export(c.name, files, "Copy of ${c.name} at ${stamp} UTC")
        String result = r.error ? "Failed: ${r.error}" :
            (r.commit ? "${r.files} objects, ${r.added} new, ${r.changed} changed, ${r.removed} removed, commit ${r.commit}" : "${r.files} objects, no changes")
        Activity.say(c.id, c.name, "Git push ${r.error ? 'failed' : 'done'}: ${result}")
        String nss = files.keySet().collect { it.split('/')[0] }.findAll { it != '_cluster' }.unique().sort().join(',')
        if (st.namespace) {
            Marker.update(kube, st.namespace as String, [gitLast: System.currentTimeMillis() as String, gitResult: result,
                gitOk: r.error ? 'false' : 'true', gitNamespaces: r.error ? null : nss])
            Marker.addHistory(kube, st.namespace as String, 'gitHistory', [at: System.currentTimeMillis(), why: why, ok: !r.error,
                result: result, commit: r.commit ?: '', files: r.files ?: 0, added: r.added ?: 0, changed: r.changed ?: 0, removed: r.removed ?: 0], 15)
        }
    }

    /** Put one namespace back from the Git copy. */
    static Map restore(MorpheusContext morpheus, ComputeServerGroup c, String namespace, String target, boolean overwrite) {
        Settings s = Settings.load(morpheus)
        GitTarget g = s.gitFor(c.id)
        if (!s.isTested(g)) return [error: 'Git not tested.']
        Map copy = g.client().read(c.name)
        if (copy.error) return [error: copy.error]
        Map<String, String> files = (Map<String, String>) copy.objects
        List<String> order = ['Namespace', 'ServiceAccount', 'ConfigMap', 'PersistentVolumeClaim', 'Role', 'RoleBinding', 'Service']
        Map<String, Map> objects = new LinkedHashMap<>()
        String nsFile = "_cluster/Namespace/${namespace}.yaml"
        if (!target && files[nsFile]) objects[nsFile] = Exporter.load(files[nsFile])
        files.findAll { k, v -> k.startsWith(namespace + '/') }
            .sort { a, b -> rank(order, a.key) <=> rank(order, b.key) ?: a.key <=> b.key }
            .each { k, v -> Map o = Exporter.load(v); if (o) objects[k] = o }
        if (objects.size() <= (objects.containsKey(nsFile) ? 1 : 0)) return [error: "The Git copy has nothing for namespace ${namespace}."]
        Map r = Items.restore(Kube.of(morpheus, c), objects, target, overwrite)
        r + [commit: copy.commit]
    }

    static int rank(List<String> order, String path) {
        String kind = path.split('/')[1]
        int i = order.indexOf(kind)
        i < 0 ? order.size() : i
    }

    /** Every minute: export the clusters whose Git schedule is due. */
    static void startTimer(MorpheusContext morpheus) {
        stopTimer()
        timer = Executors.newSingleThreadScheduledExecutor { Runnable r -> Thread t = new Thread(r, 'hks-velero-git-timer'); t.daemon = true; t }
        timer.scheduleWithFixedDelay({
            try { tick(morpheus) } catch (Throwable t) { log.warn("HKS Velero: Git timer: ${t}") }
        } as Runnable, 60, 60, TimeUnit.SECONDS)
    }

    static void stopTimer() {
        try { timer?.shutdownNow() } catch (Throwable ignored) { }
        timer = null
    }

    static void tick(MorpheusContext morpheus) {
        Settings s = Settings.load(morpheus)
        if (!s.gitTargets) return
        morpheus.services.cluster.list(new DataQuery()).findAll { Access.isHks(it) }.each { ComputeServerGroup c ->
            Kube kube = Kube.of(morpheus, c)
            Map st = Installer.status(kube)
            if (!(st.state in ['ours', 'used']) || !s.isTested(s.gitFor(c.id))) return
            Map m = Marker.get(kube, st.namespace as String)
            String cron = m.gitCron
            if (!cron || !Cron.valid(cron)) return
            long last = (m.gitLast ?: '0') as long
            Long due = Cron.next(cron, last ?: System.currentTimeMillis() - 60_000L)
            if (due && due <= System.currentTimeMillis()) exportNow(morpheus, c, 'schedule')
        }
    }
}

/** Our ConfigMap in the Velero namespace: install mode and Git copy state. */
class Marker {
    static Map get(Kube kube, String ns) {
        Map r = kube.get("/api/v1/namespaces/${ns}/configmaps/${Installer.MARKER}")
        (r.data?.data ?: [:]) as Map
    }

    /** Keep the last runs of something (restores, Git pushes) as JSON in the marker. */
    static void addHistory(Kube kube, String ns, String key, Map entry, int max = 30) {
        List list = history(kube, ns, key)
        list.add(0, entry)
        update(kube, ns, [(key): groovy.json.JsonOutput.toJson(list.take(max))])
    }

    static List<Map> history(Kube kube, String ns, String key) { history(get(kube, ns), key) }

    static List<Map> history(Map marker, String key) {
        String text = marker[key]
        if (!text) return []
        try { (List<Map>) new groovy.json.JsonSlurper().parseText(text) } catch (Exception e) { [] }
    }

    static Map update(Kube kube, String ns, Map changes) {
        String path = "/api/v1/namespaces/${ns}/configmaps/${Installer.MARKER}"
        Map cur = kube.get(path)
        if (cur.error) return cur
        Map obj = cur.data as Map
        Map data = new LinkedHashMap((obj.data ?: [:]) as Map)
        changes.each { k, v -> if (v == null) data.remove(k) else data[k] = v as String }
        obj.data = data
        kube.put(path, obj)
    }
}
