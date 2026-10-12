package com.morpheuslab.hksvelero

/** Our backup location in a cluster: one Secret with the keys and one BackupStorageLocation. */
class Storage {

    static final String LOCATION = 'hks-velero'
    static final String SECRET = 'hks-velero-s3'
    static final String V1 = '/apis/velero.io/v1'

    /** Folder of this cluster inside the bucket. */
    static String folder(String prefix, String clusterName) {
        String c = (clusterName ?: 'cluster').toLowerCase().replaceAll(/[^a-z0-9.-]+/, '-').replaceAll(/^-+|-+$/, '')
        String p = (prefix ?: '').replaceAll(/^\/+|\/+$/, '')
        p ? "${p}/${c}" : c
    }

    static String applyLocation(Kube kube, String ns, S3Target t, String clusterName) {
        Map s3 = t.cfg
        Map secret = [apiVersion: 'v1', kind: 'Secret', metadata: [name: SECRET, namespace: ns], type: 'Opaque',
                      stringData: [cloud: "[default]\naws_access_key_id=${s3.accessKey}\naws_secret_access_key=${t.secret}\n".toString()]]
        String cloud = secret.stringData.cloud
        Map cur = kube.get("/api/v1/namespaces/${ns}/secrets/${SECRET}")
        String now = cur.status == 200 && cur.data?.data?.cloud ? new String(Base64.decoder.decode(cur.data.data.cloud as String), 'UTF-8') : null
        Map r = now == cloud ? [status: 200] : kube.apply(secret)
        if (r.error) return "Could not save the keys in the cluster: ${r.error}"
        Map config = [region: s3.region ?: 'us-east-1']
        if (s3.endpoint) {
            config.s3Url = s3.endpoint
            config.s3ForcePathStyle = 'true'
        }
        if (s3.skipTls == 'on') config.insecureSkipTLSVerify = 'true'
        Map bsl = [apiVersion: 'velero.io/v1', kind: 'BackupStorageLocation', metadata: [name: LOCATION, namespace: ns],
                   spec: [provider: 'aws', objectStorage: [bucket: s3.bucket, prefix: folder(s3.prefix as String, clusterName)],
                          config: config, credential: [name: SECRET, key: 'cloud'], accessMode: 'ReadWrite']]
        // an unchanged location is left alone: replacing it clears its status until Velero checks it again
        Map have = kube.get("${V1}/namespaces/${ns}/backupstoragelocations/${LOCATION}")
        if (have.status == 200 && same(have.data?.spec as Map, bsl.spec as Map)) return null
        r = kube.apply(bsl)
        r.error ? "Could not add the backup location: ${r.error}" : null
    }

    /** Read only location for the folder of another cluster, so its backups show up here and can be restored. */
    static String addOther(Kube kube, String ns, S3Target t, String folderName) {
        String p = (t.cfg.prefix ?: '').replaceAll(/^\/+|\/+$/, '')
        Map own = location(kube, ns)
        if (own.phase == 'missing') return 'Set the storage for this cluster first.'
        Map cur = kube.get("${V1}/namespaces/${ns}/backupstoragelocations/${LOCATION}")
        Map spec = new LinkedHashMap((Map) cur.data.spec)
        spec.objectStorage = [bucket: t.cfg.bucket, prefix: p ? "${p}/${folderName}".toString() : folderName]
        spec.accessMode = 'ReadOnly'
        spec.remove('default')
        Map bsl = [apiVersion: 'velero.io/v1', kind: 'BackupStorageLocation',
                   metadata: [name: otherName(folderName), namespace: ns, labels: ['hks-velero/from': folderName.take(63)]], spec: spec]
        Map r = kube.apply(bsl)
        r.error ? "Could not add the read only location: ${r.error}" : null
    }

    static void removeOther(Kube kube, String ns, String folderName) {
        kube.delete("${V1}/namespaces/${ns}/backupstoragelocations/${otherName(folderName)}")
    }

    static String otherName(String folderName) { "from-${folderName}".take(63).replaceAll(/-+$/, '') }

    /** Folders of other clusters that are shown here now. */
    static List<String> others(Kube kube, String ns) {
        Map r = kube.get("${V1}/namespaces/${ns}/backupstoragelocations?labelSelector=hks-velero%2Ffrom")
        ((r.data?.items ?: []) as List).collect { it.metadata.labels['hks-velero/from'] as String }
    }

    static boolean same(Map have, Map want) {
        if (!have) return false
        ['provider', 'objectStorage', 'config', 'credential', 'accessMode'].every { k -> (have[k] ?: null) == (want[k] ?: null) }
    }

    static void removeLocation(Kube kube, String ns) {
        kube.delete("${V1}/namespaces/${ns}/backupstoragelocations/${LOCATION}")
        kube.delete("/api/v1/namespaces/${ns}/secrets/${SECRET}")
    }

    /** [phase: Available|Unavailable|missing, message, lastChecked] */
    static Map location(Kube kube, String ns) {
        Map r = kube.get("${V1}/namespaces/${ns}/backupstoragelocations/${LOCATION}")
        if (r.status == 404) return [phase: 'missing']
        if (r.error) return [phase: 'error', message: r.error]
        [phase: r.data?.status?.phase ?: 'Checking', message: r.data?.status?.message, lastChecked: r.data?.status?.lastValidationTime,
         bucket: r.data?.spec?.objectStorage?.bucket, prefix: r.data?.spec?.objectStorage?.prefix]
    }
}
