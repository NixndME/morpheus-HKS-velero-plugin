package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.cypher.CypherAccess
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountIntegration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

/**
 * Where backups go. Several S3 buckets and Git repositories, and which one each cluster uses.
 * Plain values in the integration config, keys and tokens in Cypher.
 */
@Slf4j
class Settings {

    static final String TYPE = 'hks-velero-integration'

    AccountIntegration integration
    List<S3Target> s3Targets = []
    List<GitTarget> gitTargets = []
    Map tested = [:]      // "s3:<id>" / "git:<id>": fingerprint that passed, plus At, Ok, Message
    Map assign = [:]      // cluster id: [s3: target id, git: target id]

    S3Target s3(String id) { s3Targets.find { it.id == id } }
    GitTarget git(String id) { gitTargets.find { it.id == id } }

    /** The S3 target of a cluster: its own pick, else the first one. */
    S3Target s3For(Long clusterId) { s3(assign[clusterId as String]?.s3 as String) ?: s3Targets[0] }

    GitTarget gitFor(Long clusterId) {
        String id = assign[clusterId as String]?.git as String
        if (id == 'none') return null
        git(id) ?: gitTargets[0]
    }

    boolean isTested(S3Target t) { t?.complete && tested["s3:${t.id}".toString()] == t.fingerprint() }
    boolean isTested(GitTarget t) { t?.complete && tested["git:${t.id}".toString()] == t.fingerprint() }

    void mark(String key, String fingerprint, String error) {
        tested[key] = error ? null : fingerprint
        tested["${key}At".toString()] = System.currentTimeMillis()
        tested["${key}Ok".toString()] = error == null
        tested["${key}Message".toString()] = error ?: ''
    }

    List<Long> clustersUsing(S3Target t, List<Long> clusterIds) { clusterIds.findAll { s3For(it)?.id == t.id } }

    static AccountIntegration find(MorpheusContext morpheus) {
        try {
            morpheus.services.accountIntegration.list(new DataQuery()).find { AccountIntegration i ->
                i.type == TYPE || i.integrationType?.code == TYPE
            }
        } catch (Throwable t) {
            log.warn("HKS Velero: can not list integrations: ${t}")
            null
        }
    }

    static Settings load(MorpheusContext morpheus) {
        Settings s = new Settings(integration: find(morpheus))
        if (!s.integration) return s
        Map cfg = config(s.integration)
        s.tested = (cfg.tested ?: [:]) as Map
        s.assign = (cfg.clusters ?: [:]) as Map
        if (!cfg.s3Targets && (cfg.s3?.bucket || cfg.s3?.source)) {
            // settings from before there could be several targets
            cfg.s3Targets = [[id: 'default'] + (cfg.s3 as Map) + [name: 'Default']]
            if (s.tested.s3) s.tested['s3:default'] = s.tested.s3
            copySecret(morpheus, s.integration, 's3-secret-key', 's3/default')
        }
        if (!cfg.gitTargets && cfg.git?.url) {
            cfg.gitTargets = [[id: 'default'] + (cfg.git as Map) + [name: 'Default']]
            if (s.tested.git) s.tested['git:default'] = s.tested.git
            copySecret(morpheus, s.integration, 'git-token', 'git/default')
        }
        (cfg.s3Targets ?: []).each { Map m ->
            S3Target t = new S3Target(id: m.id, cfg: new LinkedHashMap(m), secret: readSecret(morpheus, s.integration, "s3/${m.id}"))
            t.resolve(morpheus)
            s.s3Targets << t
        }
        (cfg.gitTargets ?: []).each { Map m ->
            GitTarget t = new GitTarget(id: m.id, cfg: new LinkedHashMap(m), secret: readSecret(morpheus, s.integration, "git/${m.id}"))
            t.resolve(morpheus)
            s.gitTargets << t
        }
        s
    }

    void save(MorpheusContext morpheus) {
        Map cfg = config(integration)
        cfg.remove('s3')
        cfg.remove('git')
        cfg.s3Targets = s3Targets.collect { [id: it.id] + it.saved() }
        cfg.gitTargets = gitTargets.collect { [id: it.id] + it.saved() }
        cfg.tested = tested.findAll { k, v -> !(k in ['s3', 'git', 's3At', 'gitAt', 's3Ok', 'gitOk', 's3Message', 'gitMessage']) }
        cfg.clusters = assign
        integration.serviceConfig = JsonOutput.toJson(cfg)
        morpheus.services.accountIntegration.save(integration)
    }

    static Map config(AccountIntegration i) {
        try { (Map) new JsonSlurper().parseText(i.serviceConfig ?: '{}') } catch (Exception ignored) { [:] }
    }

    static String newId(String name, List ids) {
        String base = (name ?: 'target').toLowerCase().replaceAll(/[^a-z0-9]+/, '-').replaceAll(/^-+|-+$/, '').take(20) ?: 'target'
        String id = base
        int n = 2
        while (id in ids) id = "${base}-${n++}"
        id
    }

    /** S3 buckets and AWS clouds in Morpheus, for the drop-down. */
    static List<Map> s3Sources(MorpheusContext morpheus) {
        List<Map> out = []
        try {
            morpheus.services.storageBucket.list(new DataQuery()).findAll { it.providerType in ['s3', 'amazon'] }.each {
                out << [value: "bucket:${it.id}".toString(), label: "Bucket: ${it.name} (${it.bucketName})".toString()]
            }
        } catch (Throwable t) { log.warn("HKS Velero: can not list buckets: ${t}") }
        try {
            morpheus.services.cloud.list(new DataQuery()).findAll { it.cloudType?.code == 'amazon' }.each {
                out << [value: "cloud:${it.id}".toString(), label: "AWS cloud: ${it.name}".toString()]
            }
        } catch (Throwable t) { log.warn("HKS Velero: can not list clouds: ${t}") }
        out
    }

    static final Set<String> GIT_TYPES = ['git', 'github', 'gitlab', 'bitbucket'] as Set

    static List<AccountIntegration> gitIntegrations(MorpheusContext morpheus) {
        try {
            morpheus.services.accountIntegration.list(new DataQuery()).findAll { AccountIntegration i ->
                (i.type ?: i.integrationType?.code) in GIT_TYPES
            }
        } catch (Throwable t) {
            log.warn("HKS Velero: can not list Git integrations: ${t}")
            []
        }
    }

    static String cypherKey(AccountIntegration i, String name) { "secret/hks-velero/${i.id}/${name}" }

    static String readSecret(MorpheusContext morpheus, AccountIntegration i, String name) {
        try {
            morpheus.services.cypher.read(new CypherAccess(i.account), cypherKey(i, name)) ?: null
        } catch (Throwable t) {
            log.debug("HKS Velero: no secret ${name}: ${t}")
            null
        }
    }

    static void writeSecret(MorpheusContext morpheus, AccountIntegration i, String name, String value) {
        morpheus.services.cypher.write(new CypherAccess(i.account), cypherKey(i, name), value, 0L)
    }

    static void deleteSecret(MorpheusContext morpheus, AccountIntegration i, String name) {
        try { morpheus.services.cypher.delete(new CypherAccess(i.account), cypherKey(i, name)) } catch (Throwable ignored) { }
    }

    private static void copySecret(MorpheusContext morpheus, AccountIntegration i, String from, String to) {
        String v = readSecret(morpheus, i, from)
        if (v && !readSecret(morpheus, i, to)) writeSecret(morpheus, i, to, v)
    }
}
