package com.morpheuslab.hksvelero

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Times as short text. The browser turns data-ms into local time. */
class Format {
    static Long ms(String iso) {
        if (!iso) return null
        try { Instant.parse(iso).toEpochMilli() } catch (Exception e) { null }
    }

    static String ago(Long ms) {
        if (!ms) return ''
        long s = (long) ((System.currentTimeMillis() - ms) / 1000)
        if (s < 0) return 'in ' + span(-s)
        s < 45 ? 'just now' : span(s) + ' ago'
    }

    static String span(long s) {
        if (s < 90) return "${s} s"
        long m = (long) (s / 60)
        if (m < 90) return "${m} min"
        long h = (long) (m / 60)
        if (h < 36) return "${h} h"
        "${(long) (h / 24)} days"
    }

    static String clock(Long ms) {
        ms ? ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC).toLocalTime().withNano(0).toString() : ''
    }

    static String duration(String from, String to) {
        Long a = ms(from), b = ms(to) ?: System.currentTimeMillis()
        a ? span((long) ((b - a) / 1000)) : ''
    }
}

class BackupRow {
    static Map of(Map b) {
        Map st = (b.status ?: [:]) as Map
        String phase = st.phase ?: 'New'
        Map prog = (st.progress ?: [:]) as Map
        int done = (prog.itemsBackedUp ?: 0) as int
        int total = (prog.totalItems ?: 0) as int
        List inc = (b.spec?.includedNamespaces ?: ['*']) as List
        [name: b.metadata.name, phase: phase, chip: chip(phase), running: phase in ['New', 'InProgress', 'WaitingForPluginOperations', 'Finalizing'],
         created: b.metadata.creationTimestamp, createdMs: Format.ms(b.metadata.creationTimestamp as String),
         when: Format.ago(Format.ms(b.metadata.creationTimestamp as String)),
         took: st.completionTimestamp ? Format.duration(st.startTimestamp as String, st.completionTimestamp as String) : '',
         items: total ? "${done} of ${total}" : '', itemsDone: done, itemsTotal: total,
         percent: total ? (int) (100 * done / total) : 0,
         warnings: (st.warnings ?: 0) as int, errors: (st.errors ?: 0) as int,
         expires: st.expiration ? Format.ago(Format.ms(st.expiration as String)) : '',
         namespaces: inc == ['*'] ? 'All namespaces' : inc.join(', '),
         namespaceList: inc == ['*'] ? [] : inc,
         schedule: b.metadata.labels?.get('velero.io/schedule-name') ?: '',
         by: b.metadata.annotations?.get('hks-velero/started-by') ?: b.metadata.labels?.get('hks-velero/by') ?: '',
         location: b.spec?.storageLocation ?: '',
         fromOther: b.spec?.storageLocation?.startsWith('from-') ? (b.spec.storageLocation as String).substring(5) : '',
         volumes: b.spec?.defaultVolumesToFsBackup ? 'Yes' : 'No',
         failure: st.failureReason ?: '',
         deleting: phase == 'Deleting']
    }

    static String chip(String phase) {
        switch (phase) {
            case 'Completed': return 'ok'
            case 'PartiallyFailed': return 'warn'
            case 'Failed': case 'FailedValidation': return 'bad'
            case 'Deleting': return 'bad'
            default: return 'info'
        }
    }
}

class RestoreRow {
    static Map of(Map r) {
        Map st = (r.status ?: [:]) as Map
        String phase = st.phase ?: 'New'
        Map prog = (st.progress ?: [:]) as Map
        List inc = (r.spec?.includedNamespaces ?: ['*']) as List
        Map map = (r.spec?.namespaceMapping ?: [:]) as Map
        [name: r.metadata.name, backup: r.spec?.backupName, phase: phase, chip: BackupRow.chip(phase), kind: 'Velero', velero: true,
         createdMs: Format.ms(r.metadata.creationTimestamp as String),
         running: phase in ['New', 'InProgress', 'WaitingForPluginOperations', 'Finalizing'],
         when: Format.ago(Format.ms(r.metadata.creationTimestamp as String)),
         items: prog.totalItems ? "${prog.itemsRestored ?: 0} of ${prog.totalItems}" : '',
         percent: prog.totalItems ? (int) (100 * ((prog.itemsRestored ?: 0) as int) / (prog.totalItems as int)) : 0,
         warnings: (st.warnings ?: 0) as int, errors: (st.errors ?: 0) as int,
         what: (inc == ['*'] ? 'Everything' : inc.join(', ')) + (map ? ' as ' + map.values().join(', ') : ''),
         failure: st.failureReason ?: '']
    }
}

class ScheduleRow {
    static Map of(Map s) {
        String cron = s.spec?.schedule ?: ''
        Map t = (s.spec?.template ?: [:]) as Map
        List inc = (t.includedNamespaces ?: ['*']) as List
        boolean paused = s.spec?.paused == true
        Long next = paused ? null : Cron.next(cron, System.currentTimeMillis())
        [name: s.metadata.name, cron: cron, when: Cron.describe(cron), paused: paused,
         keep: Keep.days(t.ttl as String), namespaces: inc == ['*'] ? 'All namespaces' : inc.join(', '),
         keepDays: Keep.number(t.ttl as String), volumes: t.defaultVolumesToFsBackup == true, nsList: inc == ['*'] ? [] : inc,
         picked: inc != ['*'], custom: !(cron in ['0 * * * *', '0 1 * * *', '0 1 * * 0']),
         last: s.status?.lastBackup ? Format.ago(Format.ms(s.status.lastBackup as String)) : 'not yet',
         next: next ? Format.ago(next) : (paused ? 'paused' : ''), nextMs: next,
         phase: s.status?.phase ?: '', ours: s.metadata?.labels?.get('hks-velero/by') == 'plugin']
    }
}

/** A picked items or Git restore, kept in the marker because Velero does not know about it. */
class HistoryRow {
    static Map of(Map h) {
        List done = (h.done ?: []) as List, skipped = (h.skipped ?: []) as List, failed = (h.failed ?: []) as List
        String phase = failed ? (done ? 'PartiallyFailed' : 'Failed') : 'Completed'
        [name: h.kind == 'git' ? 'From Git' : 'Picked items', kind: h.kind == 'git' ? 'Git' : 'Items', velero: false,
         backup: h.from, phase: phase, chip: BackupRow.chip(phase), createdMs: h.at as Long, when: Format.ago(h.at as Long),
         what: (h.what ?: VeleroController.count(done.size() + skipped.size() + failed.size(), "object")) + (h.target ? " into ${h.target}" : ''),
         items: "${done.size()} done", warnings: skipped.size(), errors: failed.size(), by: h.by ?: '',
         done: done, skipped: skipped, failed: failed, hasSkipped: !skipped.isEmpty(), hasFailed: !failed.isEmpty(), doneCount: done.size(), skippedCount: skipped.size()]
    }
}

class GitRow {
    static Map of(Map h, String web) {
        [when: Format.ago(h.at as Long), why: h.why ?: '', ok: h.ok != false, result: h.result ?: '', commit: h.commit ?: '',
         link: web && h.commit ? "${web}/commit/${h.commit}".toString() : '']
    }

    /** Web page of a repository from its clone URL (GitHub, GitLab, Gitea). */
    static String web(String url) {
        if (!url?.startsWith('http')) return ''
        url.replaceAll(/\.git$/, '').replaceAll(/\/\/[^@\/]+@/, '//')
    }
}

class Keep {
    static int number(String ttl) {
        def m = (ttl ?: '') =~ /^(\d+)h/
        m ? Math.max(1, ((m[0][1] as int) / 24) as int) : 30
    }

    static String days(String ttl) {
        def m = (ttl ?: '') =~ /^(\d+)h/
        if (!m) return ttl ?: ''
        int h = m[0][1] as int
        h % 24 == 0 ? VeleroController.count((int) (h / 24), "day") : VeleroController.count(h, "hour")
    }
}

/** Reads 5 field cron lines: minute hour day month weekday, in UTC. */
class Cron {
    static final Map<String, String> PRESETS = ['@hourly': '0 * * * *', '@daily': '0 0 * * *', '@weekly': '0 0 * * 0', '@monthly': '0 0 1 * *']

    static Long next(String cron, long from) {
        List<Set<Integer>> f = parse(cron)
        if (!f) return null
        ZonedDateTime t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(from), ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        for (int i = 0; i < 60 * 24 * 366; i++) {
            if (f[3].contains(t.monthValue) && f[2].contains(t.dayOfMonth) && f[4].contains(t.dayOfWeek.value % 7) &&
                f[1].contains(t.hour) && f[0].contains(t.minute)) return t.toInstant().toEpochMilli()
            t = t.plusMinutes(1)
        }
        null
    }

    static boolean valid(String cron) { parse(cron) != null }

    static List<Set<Integer>> parse(String cron) {
        String c = PRESETS[cron?.trim()] ?: cron?.trim()
        String[] p = (c ?: '').split(/\s+/)
        if (p.length != 5) return null
        int[][] ranges = [[0, 59], [0, 23], [1, 31], [1, 12], [0, 7]] as int[][]
        List<Set<Integer>> out = []
        for (int i = 0; i < 5; i++) {
            Set<Integer> s = field(p[i], ranges[i][0], ranges[i][1])
            if (s == null || s.isEmpty()) return null
            if (i == 4 && s.contains(7)) { s.remove(7); s.add(0) }
            out << s
        }
        out
    }

    static Set<Integer> field(String text, int lo, int hi) {
        Set<Integer> s = new TreeSet<>()
        for (String part : text.split(',')) {
            def m = part =~ /^(\*|\d+)(?:-(\d+))?(?:\/(\d+))?$/
            if (!m) return null
            int a = m[0][1] == '*' ? lo : (m[0][1] as int)
            int b = m[0][2] ? (m[0][2] as int) : (m[0][1] == '*' || m[0][3] ? hi : a)
            int step = m[0][3] ? (m[0][3] as int) : 1
            if (a < lo || b > hi || a > b || step < 1) return null
            for (int x = a; x <= b; x += step) s.add(x)
        }
        s
    }

    static String describe(String cron) {
        String c = PRESETS[cron?.trim()] ?: cron?.trim()
        String[] p = (c ?: '').split(/\s+/)
        if (p.length != 5) return cron
        String hm = (p[0] ==~ /\d+/ && p[1] ==~ /\d+/) ? String.format('%02d:%02d UTC', p[1] as int, p[0] as int) : null
        if (p[1] == '*' && p[2] == '*' && p[3] == '*' && p[4] == '*' && p[0] ==~ /\d+/) return "Every hour at :${String.format('%02d', p[0] as int)}"
        if (p[0] ==~ /\*\/\d+/ && p[1] == '*' && p[2] == '*' && p[3] == '*' && p[4] == '*') return "Every ${p[0].substring(2)} minutes"
        if (p[0] ==~ /\d+/ && p[1] ==~ /\*\/\d+/ && p[2] == '*' && p[3] == '*' && p[4] == '*') return "Every ${p[1].substring(2)} hours"
        if (hm && p[2] == '*' && p[3] == '*' && p[4] == '*') return "Every day at ${hm}"
        String[] days = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
        if (hm && p[2] == '*' && p[3] == '*' && p[4] ==~ /[0-7]/) return "Every ${days[p[4] as int]} at ${hm}"
        if (hm && p[2] == '*' && p[3] == '*' && p[4] == '1-5') return "Every weekday at ${hm}"
        cron
    }
}
