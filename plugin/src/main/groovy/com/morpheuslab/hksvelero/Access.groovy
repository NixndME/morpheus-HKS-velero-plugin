package com.morpheuslab.hksvelero

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.User
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/** Role permission "HKS Velero": none, read (see backups) or full (back up, restore, delete, settings). */
class Access {
    static String level(User user) { user?.permissions?.get(HksVeleroPlugin.PERMISSION) ?: 'none' }
    static boolean canRead(User user) { level(user) in ['read', 'full'] }
    static boolean canManage(User user) { level(user) == 'full' }

    /** HKS clusters are the Kubernetes clusters Morpheus built itself (type kubernetes-cluster). */
    static boolean isHks(ComputeServerGroup cluster) { cluster?.type?.code == 'kubernetes-cluster' }
}

/** Spring's request, CSRF token and session id, read without a compile dependency on Spring. */
@Slf4j
class Web {
    static Object request() {
        try {
            ClassLoader cl = Thread.currentThread().contextClassLoader
            Class rch = Class.forName('org.springframework.web.context.request.RequestContextHolder', true, cl)
            return rch.getMethod('getRequestAttributes').invoke(null)?.getRequest()
        } catch (Throwable t) {
            return null
        }
    }

    static Map csrf() {
        Object tok = request()?.getAttribute('_csrf')
        tok ? [param: tok.parameterName as String, value: tok.token as String] : [param: '_csrf', value: '']
    }

    static String session() {
        try { request()?.getSession(false)?.getId() ?: '' } catch (Throwable t) { '' }
    }
}

/** A message shown once after an action, per login session. */
class Flash {
    private static final Map<String, Map> MESSAGES = new ConcurrentHashMap<>()

    static void set(String where, String level, String text) { MESSAGES.put("${Web.session()}:${where}".toString(), [level: level, text: text]) }

    static Map take(String where) { MESSAGES.remove("${Web.session()}:${where}".toString()) }
}

/** Lines written by running jobs (install, remove), kept in memory for the live log box. */
class Activity {
    private static final List<Map> LINES = Collections.synchronizedList(new ArrayList<Map>())
    private static final Map<Long, String> RUNNING = new ConcurrentHashMap<>()

    static void say(Long clusterId, String cluster, String text) {
        LINES.add([at: System.currentTimeMillis(), clusterId: clusterId, cluster: cluster, text: text])
        synchronized (LINES) { while (LINES.size() > 300) LINES.remove(0) }
    }

    static List<Map> lines(Long clusterId, int max) {
        List<Map> all
        synchronized (LINES) { all = new ArrayList<Map>(clusterId ? LINES.findAll { it.clusterId == clusterId } : LINES) }
        all.size() > max ? all.subList(all.size() - max, all.size()) : all
    }

    static boolean start(Long clusterId, String what) { RUNNING.putIfAbsent(clusterId, what) == null }

    static void done(Long clusterId) { RUNNING.remove(clusterId) }

    static String running(Long clusterId) { RUNNING.get(clusterId) }

    static boolean getAnyRunning() { !RUNNING.isEmpty() }
}

/** What the tab shows open for this login session: the item picker of one backup, the last compare result. */
class TabState {
    private static final Map<String, Map> STATES = new ConcurrentHashMap<>()

    static Map get(Long clusterId) {
        String k = "${Web.session()}:${clusterId}".toString()
        Map s = STATES.get(k)
        if (s && System.currentTimeMillis() - (s.at as long) > 3600_000L) { STATES.remove(k); s = null }
        if (!s) { s = new ConcurrentHashMap([at: System.currentTimeMillis()]); STATES.put(k, s) }
        s
    }
}
