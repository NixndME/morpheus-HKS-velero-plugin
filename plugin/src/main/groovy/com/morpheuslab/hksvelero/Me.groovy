package com.morpheuslab.hksvelero

import groovy.json.JsonSlurper

import javax.net.ssl.HttpsURLConnection

/** Asks this Morpheus about the signed-in user, with the user's own session, so Morpheus' own access rules apply. */
class Me {
    /** True when Morpheus lets this user open the cluster (tenant, groups, permissions). */
    static boolean seesCluster(Long id) {
        HttpURLConnection c = open("/api/clusters/${id}")
        if (!c) return false
        try {
            if (c.responseCode != 200) return false
            Map r = (Map) new JsonSlurper().parseText(c.inputStream.getText('UTF-8'))
            (r.cluster?.id as Long) == id
        } catch (Exception e) {
            false
        } finally {
            c.disconnect()
        }
    }

    private static HttpURLConnection open(String path) {
        Object req = Web.request()
        if (!req) return null
        String host = req.getHeader('Host') ?: req.serverName
        HttpURLConnection c = (HttpURLConnection) new URL("https://${host}${path}").openConnection()
        if (c instanceof HttpsURLConnection) {
            c.SSLSocketFactory = Kube.trustAll()
            c.hostnameVerifier = { h, s -> true }
        }
        c.connectTimeout = 5000
        c.readTimeout = 10000
        c.instanceFollowRedirects = false
        c.setRequestProperty('Accept', 'application/json')
        String cookie = req.getHeader('Cookie')
        if (cookie) c.setRequestProperty('Cookie', cookie)
        c
    }

    static String access() {
        Object req = Web.request()
        if (!req) return 'none'
        try {
            String host = req.getHeader('Host') ?: req.serverName
            HttpURLConnection c = (HttpURLConnection) new URL("https://${host}/api/whoami").openConnection()
            if (c instanceof HttpsURLConnection) {
                c.SSLSocketFactory = Kube.trustAll()
                c.hostnameVerifier = { h, s -> true }
            }
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.setRequestProperty('Accept', 'application/json')
            String cookie = req.getHeader('Cookie')
            if (cookie) c.setRequestProperty('Cookie', cookie)
            if (c.responseCode != 200) return 'none'
            Map w = (Map) new JsonSlurper().parseText(c.inputStream.getText('UTF-8'))
            Object perms = w.permissions
            Map p = perms instanceof List ? ((List<Map>) perms).find { it.code == HksVeleroPlugin.PERMISSION } : null
            (p?.access ?: 'none') as String
        } catch (Exception e) {
            'none'
        }
    }
}
