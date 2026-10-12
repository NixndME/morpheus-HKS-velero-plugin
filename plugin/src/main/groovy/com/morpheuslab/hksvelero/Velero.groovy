package com.morpheuslab.hksvelero

import java.util.zip.GZIPInputStream

/** Velero objects in one cluster: backups, restores, schedules. */
class Velero {

    static final String V1 = '/apis/velero.io/v1'

    Kube kube
    String ns

    List<Map> list(String plural) {
        Map r = kube.get("${V1}/namespaces/${ns}/${plural}")
        ((r.data?.items ?: []) as List<Map>)
    }

    List<Map> backups() { list('backups').sort { a, b -> (b.metadata.creationTimestamp ?: '') <=> (a.metadata.creationTimestamp ?: '') } }

    List<Map> restores() { list('restores').sort { a, b -> (b.metadata.creationTimestamp ?: '') <=> (a.metadata.creationTimestamp ?: '') } }

    List<Map> schedules() { list('schedules').sort { it.metadata.name } }

    Map backup(String name) { kube.get("${V1}/namespaces/${ns}/backups/${name}").data as Map }

    /** Start a backup. namespaces empty means all. */
    Map backupNow(String name, List<String> namespaces, int keepDays, boolean volumes, String user) {
        Map spec = [storageLocation: Storage.LOCATION, ttl: "${keepDays * 24}h0m0s".toString(),
                    includedNamespaces: namespaces ?: ['*'], defaultVolumesToFsBackup: volumes,
                    excludedNamespaces: namespaces ? [] : [ns]]
        kube.post("${V1}/namespaces/${ns}/backups", [apiVersion: 'velero.io/v1', kind: 'Backup',
            metadata: [name: name, namespace: ns, labels: ['hks-velero/by': safeLabel(user)], annotations: ['hks-velero/started-by': user ?: '']], spec: spec])
    }

    /** Restore a backup. No namespaces means all of them. A target restores one namespace as a copy. */
    Map restore(String name, String backupName, List<String> namespaces, String target, boolean overwrite, String user) {
        Map spec = [backupName: backupName, includedNamespaces: namespaces ?: ['*'],
                    existingResourcePolicy: overwrite ? 'update' : 'none', restorePVs: true]
        if (target && namespaces?.size() == 1) spec.namespaceMapping = [(namespaces[0]): target]
        kube.post("${V1}/namespaces/${ns}/restores", [apiVersion: 'velero.io/v1', kind: 'Restore',
            metadata: [name: name, namespace: ns, labels: ['hks-velero/by': safeLabel(user)], annotations: ['hks-velero/started-by': user ?: '']], spec: spec])
    }

    Map deleteBackup(String backupName) {
        kube.post("${V1}/namespaces/${ns}/deletebackuprequests", [apiVersion: 'velero.io/v1', kind: 'DeleteBackupRequest',
            metadata: [generateName: "${backupName}-".toString(), namespace: ns, labels: ['velero.io/backup-name': backupName.take(63)]],
            spec: [backupName: backupName]])
    }

    Map schedule(String name, String cron, List<String> namespaces, int keepDays, boolean volumes) {
        Map template = [storageLocation: Storage.LOCATION, ttl: "${keepDays * 24}h0m0s".toString(),
                        includedNamespaces: namespaces ?: ['*'], excludedNamespaces: namespaces ? [] : [ns],
                        defaultVolumesToFsBackup: volumes]
        kube.post("${V1}/namespaces/${ns}/schedules", [apiVersion: 'velero.io/v1', kind: 'Schedule',
            metadata: [name: name, namespace: ns, labels: ['hks-velero/by': 'plugin']],
            spec: [schedule: cron, template: template, useOwnerReferencesInBackup: false]])
    }

    /** Start a backup now with the settings of a schedule. */
    Map runSchedule(String name, String user) {
        Map sch = kube.get("${V1}/namespaces/${ns}/schedules/${name}").data as Map
        if (!sch?.spec?.template) return [error: 'Schedule not found.']
        String stamp = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern('yyyyMMddHHmmss'))
        kube.post("${V1}/namespaces/${ns}/backups", [apiVersion: 'velero.io/v1', kind: 'Backup',
            metadata: [name: "${name.take(48)}-${stamp}".toString(), namespace: ns,
                       labels: ['velero.io/schedule-name': name, 'hks-velero/by': safeLabel(user)], annotations: ['hks-velero/started-by': user ?: '']],
            spec: sch.spec.template])
    }

    Map editSchedule(String name, String cron, List<String> namespaces, int keepDays, boolean volumes) {
        Map cur = kube.get("${V1}/namespaces/${ns}/schedules/${name}")
        if (cur.error) return cur
        Map obj = cur.data as Map
        obj.spec.schedule = cron
        obj.spec.template.ttl = "${keepDays * 24}h0m0s".toString()
        obj.spec.template.includedNamespaces = namespaces ?: ['*']
        obj.spec.template.excludedNamespaces = namespaces ? [] : [ns]
        obj.spec.template.defaultVolumesToFsBackup = volumes
        kube.put("${V1}/namespaces/${ns}/schedules/${name}", obj)
    }

    Map deleteSchedule(String name) { kube.delete("${V1}/namespaces/${ns}/schedules/${name}") }

    Map pauseSchedule(String name, boolean paused) {
        Map cur = kube.get("${V1}/namespaces/${ns}/schedules/${name}")
        if (cur.error) return cur
        Map obj = cur.data as Map
        obj.spec.paused = paused
        kube.put("${V1}/namespaces/${ns}/schedules/${name}", obj)
    }

    /** Log of a finished backup or restore. kind: BackupLog or RestoreLog. */
    String log(String kind, String name) {
        Map d = download(kind, name)
        d.error ? d.error : new String(gunzip(d.bytes as byte[]), 'UTF-8')
    }

    /** Object list of a backup: ["apps/v1/Deployment": ["shop/web", ...], "v1/Namespace": ["shop"]]. */
    Map resourceList(String backup) {
        Map d = download('BackupResourceList', backup)
        if (d.error) return [error: d.error]
        [items: new groovy.json.JsonSlurper().parseText(new String(gunzip(d.bytes as byte[]), 'UTF-8'))]
    }

    /** The saved JSON of picked objects. keys like "apps/v1/Deployment/shop/web". */
    Map contents(String backup, Set<String> keys) {
        Map d = download('BackupContents', backup)
        if (d.error) return [error: d.error]
        Map<String, Map> found = [:]
        Tar.each(gunzip(d.bytes as byte[])) { String path, byte[] body ->
            // resources/deployments.apps/namespaces/shop/web.json or resources/namespaces/cluster/shop.json
            def m = path =~ /^resources\/([^\/]+)\/(?:namespaces\/([^\/]+)|cluster)\/([^\/]+)\.json$/
            if (!m) return
            Map obj = (Map) new groovy.json.JsonSlurper().parseText(new String(body, 'UTF-8'))
            String key = "${obj.apiVersion}/${obj.kind}/${m[0][2] ? m[0][2] + '/' : ''}${obj.metadata?.name}".toString()
            if (keys.contains(key)) found[key] = obj
        }
        [objects: found]
    }

    /** Ask Velero for a file of a backup or restore and fetch it through the signed link. */
    Map download(String kind, String name) {
        String drName = "hks-velero-${System.currentTimeMillis()}"
        Map r = kube.post("${V1}/namespaces/${ns}/downloadrequests", [apiVersion: 'velero.io/v1', kind: 'DownloadRequest',
            metadata: [name: drName, namespace: ns], spec: [target: [kind: kind, name: name]]])
        if (r.error) return [error: "Could not ask Velero for the file: ${r.error}"]
        String url = null
        for (int i = 0; i < 30 && !url; i++) {
            sleep(500)
            Map d = kube.get("${V1}/namespaces/${ns}/downloadrequests/${drName}").data as Map
            if (d?.status?.phase == 'Processed') url = d.status.downloadURL
        }
        kube.delete("${V1}/namespaces/${ns}/downloadrequests/${drName}")
        if (!url) return [error: 'Velero did not give a download link in time.']
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection()
            if (c instanceof javax.net.ssl.HttpsURLConnection) {
                ((javax.net.ssl.HttpsURLConnection) c).SSLSocketFactory = Kube.trustAll()
                ((javax.net.ssl.HttpsURLConnection) c).hostnameVerifier = { h, s -> true }
            }
            c.connectTimeout = 8000
            c.readTimeout = 60000
            if (c.responseCode == 404) return [error: 'This backup has no such file in the storage.']
            if (c.responseCode != 200) return [error: "Could not download from the storage: HTTP ${c.responseCode}"]
            [bytes: c.inputStream.bytes]
        } catch (Exception e) {
            [error: "Could not download from ${new URL(url).host}: ${e.message}"]
        }
    }

    static byte[] gunzip(byte[] b) { new GZIPInputStream(new ByteArrayInputStream(b)).bytes }

    /** Recent Velero server lines about one backup or restore. Used for the live view. */
    List<String> liveLines(String name, int max) {
        // the server pod is the one with a container named velero, node-agent pods have the same labels
        Map pods = kube.get("/api/v1/namespaces/${ns}/pods")
        String pod = ((pods.data?.items ?: []) as List).find { Map p ->
            p.status?.phase == 'Running' && p.spec?.containers?.any { it.name == 'velero' }
        }?.metadata?.name
        if (!pod) return []
        String text = kube.text("/api/v1/namespaces/${ns}/pods/${pod}/log?container=velero&sinceSeconds=900&tailLines=3000") ?: ''
        List<String> out = text.readLines().findAll { it.contains(name) }.collect { LogLine.plain(it) }.findAll()
        out.size() > max ? out.subList(out.size() - max, out.size()) : out
    }

    static String safeLabel(String s) {
        String v = (s ?: 'unknown').replaceAll(/[^A-Za-z0-9._-]/, '_')
        v = v.take(63).replaceAll(/^[^A-Za-z0-9]+|[^A-Za-z0-9]+$/, '')
        v ?: 'unknown'
    }
}

/** Reads the files of a tar archive. */
class Tar {
    static void each(byte[] tar, Closure c) {
        int pos = 0
        while (pos + 512 <= tar.length) {
            String name = new String(tar, pos, 100, 'UTF-8').replaceAll(/\u0000.*$/, '')
            if (!name) break
            String prefix = new String(tar, pos + 345, 155, 'UTF-8').replaceAll(/\u0000.*$/, '')
            long size = Long.parseLong(new String(tar, pos + 124, 12, 'UTF-8').replaceAll(/[\u0000 ]/, '') ?: '0', 8)
            byte type = tar[pos + 156]
            pos += 512
            if (type == ('0' as char) || type == 0) c.call(prefix ? "${prefix}/${name}".toString() : name, Arrays.copyOfRange(tar, pos, pos + (int) size))
            pos += (int) (((size + 511) / 512) as long) * 512
        }
    }
}

/** Turns a Velero log line into short text: time and message, plus resource and namespace when present. */
class LogLine {
    static String plain(String line) {
        String time = field(line, 'time')
        String msg = field(line, 'msg')
        if (!msg) return null
        String res = field(line, 'resource')
        String nsName = field(line, 'namespace')
        String name = field(line, 'name')
        String t = time && time.length() >= 19 ? time.substring(11, 19) : ''
        String what = [res, nsName && name ? "${nsName}/${name}" : name].findAll().join(' ')
        String level = field(line, 'level')
        String mark = level == 'warning' ? 'WARNING ' : (level in ['error', 'fatal'] ? 'ERROR ' : '')
        "${t} ${mark}${msg}${what ? '  ' + what : ''}".trim()
    }

    static String field(String line, String key) {
        def m = line =~ /(?:^|\s)${key}="((?:[^"\\]|\\.)*)"/
        if (m) return m[0][1].replace('\\"', '"')
        m = line =~ /(?:^|\s)${key}=(\S+)/
        m ? m[0][1] : null
    }
}
