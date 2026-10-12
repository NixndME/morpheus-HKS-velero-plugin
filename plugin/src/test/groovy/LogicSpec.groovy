import com.morpheuslab.hksvelero.*
import spock.lang.Specification

import java.time.Instant

class LogicSpec extends Specification {

    def "cron next run"() {
        given:
        long from = Instant.parse('2026-10-12T10:20:00Z').toEpochMilli()
        expect:
        Instant.ofEpochMilli(Cron.next(cron, from)).toString() == next
        where:
        cron          | next
        '@hourly'     | '2026-10-12T11:00:00Z'
        '0 1 * * *'   | '2026-10-13T01:00:00Z'
        '0 1 * * 0'   | '2026-10-18T01:00:00Z'
        '*/15 * * * *'| '2026-10-12T10:30:00Z'
        '30 2 * * 1-5'| '2026-10-13T02:30:00Z'
    }

    def "bad cron lines"() {
        expect:
        !Cron.valid(cron)
        where:
        cron << ['', '* * *', '61 * * * *', 'a b c d e', '5-1 * * * *']
    }

    def "cron in words"() {
        expect:
        Cron.describe('0 1 * * *') == 'Every day at 01:00 UTC'
        Cron.describe('0 1 * * 0') == 'Every Sunday at 01:00 UTC'
        Cron.describe('@hourly') == 'Every hour at :00'
        Cron.describe('*/15 * * * *') == 'Every 15 minutes'
        Cron.describe('30 2 * * 1-5') == 'Every weekday at 02:30 UTC'
    }

    def "velero log line to short text"() {
        expect:
        LogLine.plain('time="2026-10-12T16:40:01Z" level=info msg="Backed up 12 items out of an estimated total of 40" backup=velero/b1 logSource="pkg/backup/backup.go:500" progress=') ==
            '16:40:01 Backed up 12 items out of an estimated total of 40'
        LogLine.plain('time="2026-10-12T16:40:02Z" level=info msg="Processing item" backup=velero/b1 name=web namespace=shop resource=deployments.apps') ==
            '16:40:02 Processing item  deployments.apps shop/web'
        LogLine.plain('time="2026-10-12T16:40:03Z" level=warning msg="could not restore, already exists" restore=velero/r1') ==
            '16:40:03 WARNING could not restore, already exists'
        LogLine.plain('no message here') == null
    }

    def "cluster folder in the bucket"() {
        expect:
        Storage.folder(prefix, name) == folder
        where:
        prefix  | name          | folder
        ''      | 'HKS'         | 'hks'
        'hks/'  | 'Prod East 1' | 'hks/prod-east-1'
        null    | 'a_b'         | 'a-b'
    }

    def "bundled velero manifest"() {
        when:
        List<Map> all = Installer.manifest()
        then:
        all.size() == 21
        all.count { it.kind == 'CustomResourceDefinition' } == 13
        all.findAll { it.kind != 'CustomResourceDefinition' && it.kind != 'ClusterRoleBinding' }.every { it.metadata.namespace == 'velero' }
    }

    def "kube apply creates, then replaces with the current version"() {
        given:
        List calls = []
        Kube k = new Kube(apiUrl: 'https://k', token: 't')
        k.plurals['v1/ConfigMap'] = 'configmaps'
        k.transport = { String m, String url, String body, String ct, String tok ->
            calls << "${m} ${url}".toString()
            if (m == 'POST') return [status: 409, body: '{"message":"exists"}']
            if (m == 'GET') return [status: 200, body: '{"metadata":{"resourceVersion":"7"}}']
            [status: 200, body: body]
        }
        when:
        Map r = k.apply([apiVersion: 'v1', kind: 'ConfigMap', metadata: [name: 'x', namespace: 'velero'], data: [a: 'b']])
        then:
        r.status == 200
        r.data.metadata.resourceVersion == '7'
        calls == ['POST https://k/api/v1/namespaces/velero/configmaps', 'GET https://k/api/v1/namespaces/velero/configmaps/x',
                  'PUT https://k/api/v1/namespaces/velero/configmaps/x']
    }

    def "targets: each cluster uses its own pick, else the first one"() {
        given:
        Settings s = new Settings(s3Targets: [new S3Target(id: 'prod'), new S3Target(id: 'dev')],
            gitTargets: [new GitTarget(id: 'main')], assign: ['2': [s3: 'dev', git: 'none'], '3': [s3: 'gone']])
        expect:
        s.s3For(2L).id == 'dev'
        s.s3For(3L).id == 'prod'
        s.s3For(4L).id == 'prod'
        s.gitFor(2L) == null
        s.gitFor(3L).id == 'main'
        Settings.newId('Prod East', ['prod-east']) == 'prod-east-2'
    }

    def "a Morpheus bucket source saves no keys"() {
        expect:
        new S3Target(cfg: [name: 'A', source: 'bucket:1', bucket: 'b', accessKey: 'k', prefix: 'p']).saved() == [name: 'A', source: 'bucket:1', prefix: 'p', skipTls: null]
    }

    def "an unchanged backup location is not replaced"() {
        expect:
        Storage.same([provider: 'aws', objectStorage: [bucket: 'b', prefix: 'p'], config: [region: 'r'], credential: [name: 'c', key: 'cloud'], accessMode: 'ReadWrite', default: false],
                     [provider: 'aws', objectStorage: [bucket: 'b', prefix: 'p'], config: [region: 'r'], credential: [name: 'c', key: 'cloud'], accessMode: 'ReadWrite'])
        !Storage.same([provider: 'aws', objectStorage: [bucket: 'b', prefix: 'p']], [provider: 'aws', objectStorage: [bucket: 'x', prefix: 'p']])
    }

    def "safe label"() {
        expect:
        Velero.safeLabel('jane@example.com') == 'jane_example.com'
        Velero.safeLabel(null) == 'unknown'
    }
}
