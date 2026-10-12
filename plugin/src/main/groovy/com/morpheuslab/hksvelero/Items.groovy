package com.morpheuslab.hksvelero

/** Single objects from a backup: list them, compare them with the cluster, put picked ones back. */
class Items {

    // these change all the time or come back by themselves, so they are not shown or compared
    static final Set<String> SKIP = ['v1/Event', 'events.k8s.io/v1/Event', 'v1/Pod', 'apps/v1/ReplicaSet', 'v1/Endpoints',
                                     'discovery.k8s.io/v1/EndpointSlice', 'apps/v1/ControllerRevision', 'coordination.k8s.io/v1/Lease',
                                     'velero.io/v1/PodVolumeBackup', 'velero.io/v1/DataUpload', 'v1/PersistentVolume'] as Set

    static final String PART = /[A-Za-z0-9.:_-]{1,253}/

    /** Backup contents by namespace for the tree: [[ns, count, kinds: [[kind, items: [[key, name]]]]]]. Cluster wide ones come last. */
    static List<Map> tree(Map list) {
        Map<String, Map<String, List<Map>>> byNs = new TreeMap<>()
        list.findAll { k, v -> !(k in SKIP) }.each { String k, List v ->
            String kind = k.substring(k.lastIndexOf('/') + 1)
            v.each { String id ->
                int i = id.indexOf('/')
                String ns = i > 0 ? id.substring(0, i) : ''
                String name = i > 0 ? id.substring(i + 1) : id
                byNs.computeIfAbsent(ns) { new TreeMap<>() }.computeIfAbsent(kind) { [] } << [key: "${k}/${id}".toString(), name: name]
            }
        }
        List<Map> out = byNs.findAll { it.key }.collect { ns, kinds -> node(ns, kinds) }
        if (byNs['']) out << node('', byNs[''])
        out
    }

    private static Map node(String ns, Map<String, List<Map>> kinds) {
        [ns: ns, label: ns ?: 'Cluster wide', count: kinds.values().sum { it.size() },
         kinds: kinds.collect { kind, items -> [kind: kind, items: items.sort { it.name }] }]
    }

    /** What is new or gone in the cluster since the backup. */
    static Map compare(Kube kube, Map list, List<String> namespaces) {
        List<String> added = []
        List<String> removed = []
        int kinds = 0
        list.findAll { k, v -> !(k in SKIP) }.each { String k, List v ->
            String apiVersion = k.substring(0, k.lastIndexOf('/'))
            String kind = k.substring(k.lastIndexOf('/') + 1)
            String plural = kube.plural(apiVersion, kind)
            if (!plural) return
            String base = apiVersion == 'v1' ? '/api/v1' : "/apis/${apiVersion}"
            boolean namespaced = v.any { (it as String).contains('/') }
            Set<String> live = [] as Set
            if (namespaced) {
                List<String> nss = namespaces ?: (v.collect { (it as String).split('/')[0] }.unique() as List<String>)
                nss.each { String ns ->
                    Map r = kube.get("${base}/namespaces/${ns}/${plural}")
                    (r.data?.items ?: []).each { live << "${ns}/${it.metadata.name}".toString() }
                }
            } else {
                Map r = kube.get("${base}/${plural}")
                Set<String> saved = v as Set<String>
                (r.data?.items ?: []).each { if (saved.contains(it.metadata.name)) live << (it.metadata.name as String) }
            }
            kinds++
            Set<String> saved = (v as List<String>) as Set
            (live - saved).sort().each { added << "${kind} ${it}".toString() }
            (saved - live).sort().each { removed << "${kind} ${it}".toString() }
        }
        [added: added, removed: removed, kinds: kinds]
    }

    /** Put picked objects back. */
    static Map restore(Kube kube, Map<String, Map> objects, String target, boolean overwrite) {
        List<String> done = [], skipped = [], failed = []
        if (target) kube.apply([apiVersion: 'v1', kind: 'Namespace', metadata: [name: target]])
        objects.each { String key, Map obj ->
            Map o = clean(obj)
            if (target && o.metadata.namespace) o.metadata.namespace = target
            String label = "${o.kind} ${o.metadata.namespace ? o.metadata.namespace + '/' : ''}${o.metadata.name}".toString()
            String path = kube.objectPath(o)
            if (!path) { failed << "${label}: unknown kind".toString(); return }
            boolean exists = kube.get(path).status == 200
            if (exists && !overwrite) { skipped << label; return }
            Map r = kube.apply(o)
            r.error ? failed << "${label}: ${r.error}".toString() : done << label
        }
        [done: done, skipped: skipped, failed: failed]
    }

    /** Drop what the cluster sets by itself, so the saved object can be created again. */
    static Map clean(Map obj) {
        Map o = (Map) new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(obj))
        o.remove('status')
        Map md = (Map) o.metadata
        ['uid', 'resourceVersion', 'creationTimestamp', 'managedFields', 'generation', 'selfLink', 'ownerReferences', 'deletionTimestamp'].each { md.remove(it) }
        if (md.annotations instanceof Map) {
            ((Map) md.annotations).remove('kubectl.kubernetes.io/last-applied-configuration')
            ((Map) md.annotations).remove('deployment.kubernetes.io/revision')
            // binding notes of a claim would make a new claim look bound to the old volume
            ((Map) md.annotations).keySet().removeAll { String k -> k.startsWith('pv.kubernetes.io/') || k.endsWith('kubernetes.io/storage-provisioner') || k == 'volume.kubernetes.io/selected-node' }
            if (((Map) md.annotations).isEmpty()) md.remove('annotations')
        }
        if (md.labels instanceof Map) {
            ((Map) md.labels).remove('velero.io/backup-name')
            ((Map) md.labels).remove('velero.io/restore-name')
            if (((Map) md.labels).isEmpty()) md.remove('labels')
        }
        if (o.kind == 'Service' && o.spec instanceof Map) { o.spec.remove('clusterIP'); o.spec.remove('clusterIPs') }
        if (o.kind == 'PersistentVolumeClaim' && o.spec instanceof Map) o.spec.remove('volumeName')
        o
    }

    static boolean validKey(String key) {
        String[] p = key.split('/')
        p.length >= 3 && p.length <= 6 && p.every { it ==~ PART }
    }
}
