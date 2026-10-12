package com.morpheuslab.hksvelero

import groovy.util.logging.Slf4j
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Finds, installs and removes Velero in one cluster. */
@Slf4j
class Installer {

    static final String NS = 'velero'
    static final String MARKER = 'hks-velero'
    static final String CRD_PATH = '/apis/apiextensions.k8s.io/v1/customresourcedefinitions/'

    /** What the cluster has: [state: none|ours|existing|error, namespace, version, error]. */
    static Map status(Kube kube) {
        Map r = kube.get('/apis/apps/v1/deployments?labelSelector=' + enc('app.kubernetes.io/name=velero'))
        if (r.error) return [state: 'error', error: r.error]
        List items = (r.data?.items ?: []) as List
        if (!items) {
            // older installs made by the velero CLI use component=velero
            Map r2 = kube.get('/apis/apps/v1/deployments?labelSelector=' + enc('component=velero'))
            items = ((r2.data?.items ?: []) as List).findAll { it.metadata?.name == 'velero' }
        }
        if (!items) return [state: 'none']
        Map dep = items[0] as Map
        String ns = dep.metadata.namespace
        Map marker = kube.get("/api/v1/namespaces/${ns}/configmaps/${MARKER}")
        String mode = marker.status == 200 ? marker.data?.data?.mode : null
        String image = dep.spec?.template?.spec?.containers?.find { it.name == 'velero' }?.image ?: ''
        int ready = (dep.status?.readyReplicas ?: 0) as int
        [state: mode == 'installed' ? 'ours' : (mode == 'existing' ? 'used' : 'existing'),
         namespace: ns, version: image.contains(':') ? image.substring(image.lastIndexOf(':') + 1) : '', ready: ready > 0]
    }

    private static List<Map> parsed
    private static String version

    /** Velero version in the bundled YAML, for example v1.18.4. */
    static synchronized String bundledVersion() {
        if (version == null) {
            Map dep = manifest().find { it.kind == 'Deployment' }
            String image = dep?.spec?.template?.spec?.containers?.find { it.name == 'velero' }?.image ?: ''
            version = image.contains(':') ? image.substring(image.lastIndexOf(':') + 1) : ''
        }
        version
    }

    /** The bundled Velero objects. Parsed once (168 KB of YAML); callers get their own copy. */
    static synchronized List<Map> manifest() {
        if (parsed == null) {
            String text = Installer.getResourceAsStream('/velero/velero.yaml').getText('UTF-8')
            Yaml y = new Yaml(new SafeConstructor(new LoaderOptions()))
            parsed = (y.loadAll(text).findAll { it instanceof Map } as List<Map>).asImmutable()
        }
        parsed.collect { (Map) new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(it)) }
    }

    /** Install our Velero. Returns null when it worked, or an error. */
    static String install(Kube kube, Closure<Void> say) {
        List<Map> all = manifest()
        List<Map> crds = all.findAll { it.kind == 'CustomResourceDefinition' }
        List<Map> rest = all - crds
        say("Creating namespace ${NS}")
        Map r = kube.apply([apiVersion: 'v1', kind: 'Namespace', metadata: [name: NS, labels: ['app.kubernetes.io/managed-by': MARKER]]])
        if (r.error) return "Could not create the namespace: ${r.error}"
        say("Adding ${crds.size()} Velero resource types")
        for (Map c : crds) {
            r = kube.apply(c)
            if (r.error) return "Could not add ${c.metadata.name}: ${r.error}"
        }
        for (int i = 0; i < 30; i++) {
            if (crds.every { established(kube, it.metadata.name as String) }) break
            sleep(2000)
        }
        for (Map o : rest) {
            say("Creating ${o.kind} ${o.metadata.name}")
            r = kube.apply(o)
            if (r.error) return "Could not create ${o.kind} ${o.metadata.name}: ${r.error}"
        }
        r = setMode(kube, NS, 'installed')
        if (r.error) return "Could not save the plugin marker: ${r.error}"
        say('Waiting for Velero to start')
        for (int i = 0; i < 60 && !status(kube).ready; i++) Thread.sleep(3000)
        say(status(kube).ready ? 'Velero is installed' : 'Velero is installed, still starting')
        null
    }

    /** Use a Velero that was already there. We only add our marker and later our own location. */
    static String useExisting(Kube kube, String ns) {
        Map r = setMode(kube, ns, 'existing')
        r.error ? "Could not save the plugin marker: ${r.error}" : null
    }

    /** Remove what we made. A Velero we did not install stays, only our parts go. */
    static String remove(Kube kube, Closure<Void> say) {
        Map st = status(kube)
        if (st.state == 'used') {
            String ns = st.namespace
            Storage.removeLocation(kube, ns)
            kube.delete("/api/v1/namespaces/${ns}/configmaps/${MARKER}")
            say('Stopped using this Velero. It is still installed.')
            return null
        }
        if (st.state != 'ours') return 'Velero on this cluster was not installed by this plugin.'
        List<Map> all = manifest()
        all.findAll { it.kind != 'CustomResourceDefinition' }.reverse().each { Map o ->
            say("Deleting ${o.kind} ${o.metadata.name}")
            kube.remove(o)
        }
        say("Deleting namespace ${NS}")
        kube.delete("/api/v1/namespaces/${NS}")
        all.findAll { it.kind == 'CustomResourceDefinition' }.each { Map c -> kube.delete(CRD_PATH + c.metadata.name) }
        say('Velero is removed. Backups in storage are kept.')
        null
    }

    /** Keep what else is in the marker (Git copy state), only set the mode. */
    static Map setMode(Kube kube, String ns, String mode) {
        Map cur = kube.get("/api/v1/namespaces/${ns}/configmaps/${MARKER}")
        cur.status == 200 ? Marker.update(kube, ns, [mode: mode]) : kube.apply(marker(ns, mode))
    }

    static Map marker(String ns, String mode) {
        [apiVersion: 'v1', kind: 'ConfigMap', metadata: [name: MARKER, namespace: ns], data: [mode: mode]]
    }

    static boolean established(Kube kube, String name) {
        Map r = kube.get(CRD_PATH + name)
        (r.data?.status?.conditions ?: []).any { it.type == 'Established' && it.status == 'True' }
    }

    static String enc(String s) { URLEncoder.encode(s, 'UTF-8') }
}
