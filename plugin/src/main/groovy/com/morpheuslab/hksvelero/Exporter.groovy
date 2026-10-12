package com.morpheuslab.hksvelero

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

/** Reads the cluster and turns every object into a clean YAML file. Secrets are never written. */
class Exporter {

    static final Set<String> NEVER = ['Secret', 'Event', 'Pod', 'ReplicaSet', 'Endpoints', 'EndpointSlice', 'ControllerRevision', 'Lease',
                                      'PodVolumeBackup', 'PodVolumeRestore', 'DataUpload', 'DataDownload', 'DownloadRequest',
                                      'BackupRepository', 'DeleteBackupRequest', 'ServerStatusRequest', 'PodMetrics', 'NodeMetrics',
                                      'TokenReview', 'SubjectAccessReview', 'LocalSubjectAccessReview', 'SelfSubjectAccessReview', 'Binding'] as Set
    static final Set<String> CLUSTER_KINDS = ['Namespace', 'StorageClass', 'ClusterRole', 'ClusterRoleBinding', 'CustomResourceDefinition',
                                              'IngressClass', 'PriorityClass'] as Set
    static final Set<String> SKIP_NS = ['kube-node-lease', 'kube-public'] as Set

    Kube kube
    Closure<Void> say = { String s -> }

    /** Every listable kind: [apiVersion, kind, plural, namespaced]. Preferred version of each group only. */
    List<Map> kinds() {
        List<Map> out = []
        Map core = kube.get('/api/v1')
        (core.data?.resources ?: []).each { Map r -> add(out, 'v1', r) }
        Map groups = kube.get('/apis')
        (groups.data?.groups ?: []).each { Map g ->
            String gv = g.preferredVersion?.groupVersion
            if (!gv) return
            Map res = kube.get("/apis/${gv}")
            (res.data?.resources ?: []).each { Map r -> add(out, gv, r) }
        }
        out.unique { "${it.apiVersion}/${it.kind}" }
    }

    private static void add(List<Map> out, String apiVersion, Map r) {
        if ((r.name as String).contains('/') || !('list' in (r.verbs ?: [])) || r.kind in NEVER) return
        if (!r.namespaced && !(r.kind in CLUSTER_KINDS)) return
        out << [apiVersion: apiVersion, kind: r.kind, plural: r.name, namespaced: r.namespaced]
    }

    /** path in the repo -> YAML text. Paths: <namespace>/<kind>/<name>.yaml or _cluster/<kind>/<name>.yaml */
    Map<String, String> files() {
        Map<String, String> out = new TreeMap<>()
        List<Map> all = kinds()
        say("Reading ${all.size()} kinds of objects")
        all.each { Map k ->
            String base = k.apiVersion == 'v1' ? '/api/v1' : "/apis/${k.apiVersion}"
            Map r = kube.get("${base}/${k.plural}")
            List items = (r.data?.items ?: []) as List
            int n = 0
            items.each { Map o ->
                Map md = (Map) o.metadata
                if (md.ownerReferences) return
                String ns = md.namespace
                if (ns in SKIP_NS) return
                if (k.kind == 'ConfigMap' && md.name == 'kube-root-ca.crt') return
                if (k.kind == 'ServiceAccount' && md.name == 'default') return
                if (k.kind in ['ClusterRole', 'ClusterRoleBinding'] && (md.name as String).startsWith('system:')) return
                o.apiVersion = k.apiVersion
                o.kind = k.kind
                Map clean = Items.clean(o)
                clean.remove('apiVersion')
                clean.remove('kind')
                String path = k.namespaced ? "${ns}/${k.kind}/${md.name}.yaml" : "_cluster/${k.kind}/${md.name}.yaml"
                out[path.toString()] = dump([apiVersion: k.apiVersion, kind: k.kind] + clean)
                n++
            }
            if (n) say("${k.kind}: ${n}")
        }
        out
    }

    static String dump(Map o) {
        DumperOptions d = new DumperOptions()
        d.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        d.width = 200
        new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(d), d).dump(o)
    }

    static Map load(String text) {
        Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(text)
        o instanceof Map ? (Map) o : null
    }
}
