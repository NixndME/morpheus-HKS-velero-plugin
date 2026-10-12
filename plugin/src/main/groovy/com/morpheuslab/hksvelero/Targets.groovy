package com.morpheuslab.hksvelero

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.AccountIntegration
import groovy.util.logging.Slf4j

/** One S3 bucket backups can go to. cfg: name, source, endpoint, region, bucket, prefix, accessKey, skipTls. */
@Slf4j
class S3Target {
    String id
    Map cfg = [:]
    String secret
    String sourceError

    String getName() { cfg.name ?: id }
    boolean getComplete() { cfg.bucket && cfg.accessKey && secret }
    String getWhere() { complete || cfg.bucket ? "${cfg.endpoint ?: 'AWS S3'} / ${cfg.bucket}${cfg.prefix ? '/' + cfg.prefix : ''}" : '' }

    String fingerprint() {
        S3.hex(S3.sha256("${cfg.endpoint}|${cfg.region}|${cfg.bucket}|${cfg.prefix}|${cfg.accessKey}|${secret}|${cfg.skipTls}".getBytes('UTF-8'))).take(16)
    }

    S3 client() {
        new S3(endpoint: cfg.endpoint, region: cfg.region ?: 'us-east-1', bucket: cfg.bucket, accessKey: cfg.accessKey, secretKey: secret,
            verifyTls: cfg.skipTls != 'on')
    }

    /** Fill the details from a Morpheus storage bucket or the keys of a Morpheus AWS cloud. */
    void resolve(MorpheusContext morpheus) {
        String src = cfg.source as String
        if (!src) return
        try {
            if (src.startsWith('bucket:')) {
                def b = morpheus.services.storageBucket.get(src.substring(7) as Long)
                if (!b) { sourceError = 'The Morpheus bucket was not found.'; return }
                Map c = b.configMap ?: [:]
                cfg.endpoint = c.endpoint ?: ''
                cfg.region = c.region ?: b.regionCode ?: 'us-east-1'
                cfg.bucket = b.bucketName
                cfg.accessKey = c.accessKey ?: b.storageServer?.serviceUsername
                secret = c.secretKey ?: b.storageServer?.servicePassword
            } else if (src.startsWith('cloud:')) {
                def cl = morpheus.services.cloud.get(src.substring(6) as Long)
                if (!cl) { sourceError = 'The Morpheus cloud was not found.'; return }
                Map c = cl.configMap ?: [:]
                String ak = c.accessKey, sk = c.secretKey
                if (!ak || !sk) {
                    def cred = morpheus.services.accountCredential.loadCredentials(cl)
                    ak = ak ?: cred?.data?.username ?: cred?.data?.accessKey
                    sk = sk ?: cred?.data?.password ?: cred?.data?.secretKey
                }
                if (!ak || !sk) { sourceError = 'That cloud has no keys. Type them instead.'; return }
                cfg.accessKey = ak
                secret = sk
                cfg.endpoint = ''
                if (!cfg.region) cfg.region = cl.regionCode ?: (c.endpoint =~ /ec2\.([a-z0-9-]+)\.amazonaws/).with { it ? it[0][1] : 'us-east-1' }
            }
        } catch (Throwable t) {
            log.warn("HKS Velero: can not read ${src}: ${t}")
            sourceError = "Could not read the Morpheus ${src.startsWith('cloud:') ? 'cloud' : 'bucket'}: ${t.message}"
        }
    }

    /** What is saved: typed values only, never the details a Morpheus source gives. */
    Map saved() {
        String src = cfg.source
        if (src?.startsWith('bucket:')) return [name: cfg.name, source: src, prefix: cfg.prefix, skipTls: cfg.skipTls]
        if (src?.startsWith('cloud:')) return [name: cfg.name, source: src, region: cfg.region, bucket: cfg.bucket, prefix: cfg.prefix, skipTls: cfg.skipTls]
        cfg.findAll { k, v -> k in ['name', 'endpoint', 'region', 'bucket', 'prefix', 'accessKey', 'skipTls'] }
    }
}

/** One Git repository the YAML copy can go to. cfg: name, integrationId, url, branch, folder, user, skipTls. */
@Slf4j
class GitTarget {
    String id
    Map cfg = [:]
    String secret

    String getName() { cfg.name ?: id }
    boolean getComplete() { cfg.url && (secret || cfg.user) }

    String fingerprint() {
        S3.hex(S3.sha256("${cfg.url}|${cfg.branch}|${cfg.folder}|${cfg.user}|${secret}|${cfg.skipTls}".getBytes('UTF-8'))).take(16)
    }

    GitCopy client() {
        new GitCopy(url: cfg.url, branch: cfg.branch ?: '', folder: cfg.folder ?: '', user: cfg.user ?: '', secret: secret ?: '',
            skipTls: cfg.skipTls == 'on')
    }

    /** Login (and for plain Git, the URL) of a Morpheus Git or GitHub integration. */
    void resolve(MorpheusContext morpheus) {
        if (!cfg.integrationId) return
        AccountIntegration gi = Settings.gitIntegrations(morpheus).find { it.id == (cfg.integrationId as Long) }
        if (!gi) return
        String token = gi.serviceToken ?: gi.servicePassword
        if (!token) {
            try {
                def cred = morpheus.services.accountCredential.loadCredentials(gi)
                token = cred?.data?.password ?: cred?.data?.apiKey ?: cred?.data?.token
                if (!cfg.user && cred?.data?.username) cfg.user = cred.data.username
            } catch (Throwable t) { log.debug("HKS Velero: no credential on integration ${gi.id}: ${t}") }
        }
        if (token) secret = token
        if (gi.serviceUsername) cfg.user = gi.serviceUsername
        if (!cfg.user) cfg.user = 'git'
        // a plain Git integration points at one repository, so its URL wins over a typed one
        if (gi.type == 'git' && gi.serviceUrl) cfg.url = gi.serviceUrl
    }

    Map saved() { cfg.findAll { k, v -> k in ['name', 'integrationId', 'url', 'branch', 'folder', 'user', 'skipTls'] } }
}
