import com.morpheuslab.hksvelero.*
import spock.lang.Specification

class ItemsSpec extends Specification {

    def "tar reader finds the files"() {
        given:
        List<String> names = []
        when:
        Tar.each(getClass().getResourceAsStream('/contents.tar').bytes) { String n, byte[] b -> names << n }
        then:
        names == ['metadata/version', 'resources/deployments.apps/namespaces/shop/web.json',
                  'resources/deployments.apps/v1-preferredversion/namespaces/shop/web.json', 'resources/namespaces/cluster/shop.json']
    }

    def "tree groups by namespace, cluster wide last, noisy kinds left out"() {
        when:
        List<Map> t = Items.tree(['v1/Event': ['shop/e1'], 'v1/ConfigMap': ['shop/b', 'shop/a'], 'apps/v1/Deployment': ['shop/web'], 'v1/Namespace': ['shop']])
        then:
        t*.label == ['shop', 'Cluster wide']
        t[0].count == 3
        t[0].kinds*.kind == ['ConfigMap', 'Deployment']
        t[0].kinds[0].items*.key == ['v1/ConfigMap/shop/a', 'v1/ConfigMap/shop/b']
        t[1].kinds[0].items[0].name == 'shop'
    }

    def "clean drops what the cluster sets"() {
        when:
        Map o = Items.clean([apiVersion: 'v1', kind: 'Service', metadata: [name: 's', namespace: 'n', uid: 'u', resourceVersion: '1',
            annotations: ['kubectl.kubernetes.io/last-applied-configuration': '{}']], spec: [clusterIP: '10.0.0.1', ports: [[port: 80]]], status: [:]])
        then:
        o.metadata == [name: 's', namespace: 'n']
        o.spec == [ports: [[port: 80]]]
        !o.containsKey('status')
    }

    def "a saved claim can be created again"() {
        when:
        Map o = Items.clean([apiVersion: 'v1', kind: 'PersistentVolumeClaim', metadata: [name: 'd', namespace: 'n',
            annotations: ['pv.kubernetes.io/bind-completed': 'yes', 'volume.kubernetes.io/storage-provisioner': 'x', 'keep': 'me']],
            spec: [volumeName: 'pvc-1', storageClassName: 'ceph']])
        then:
        o.metadata.annotations == [keep: 'me']
        o.spec == [storageClassName: 'ceph']
    }

    def "item keys are checked"() {
        expect:
        Items.validKey('apps/v1/Deployment/shop/web')
        Items.validKey('v1/Namespace/shop')
        !Items.validKey('apps/v1/Deployment/shop/we b')
        !Items.validKey('x')
    }

    def "compare finds new and gone objects"() {
        given:
        Kube k = new Kube(apiUrl: 'https://k', token: 't')
        k.plurals['v1/ConfigMap'] = 'configmaps'
        k.transport = { String m, String url, String body, String ct, String tok ->
            [status: 200, body: '{"items":[{"metadata":{"name":"a"}},{"metadata":{"name":"c"}}]}']
        }
        when:
        Map d = Items.compare(k, ['v1/ConfigMap': ['shop/a', 'shop/b']], [])
        then:
        d.added == ['ConfigMap shop/c']
        d.removed == ['ConfigMap shop/b']
    }
}
