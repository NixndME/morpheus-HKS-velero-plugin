package com.morpheuslab.hksvelero

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Tiny S3 client (signature v4, path style) used to test the settings. */
class S3 {

    String endpoint     // empty means AWS
    String region
    String bucket
    String accessKey
    String secretKey
    boolean verifyTls = false

    String getBaseUrl() {
        String e = endpoint?.trim() ? endpoint.trim().replaceAll('/+$', '') : "https://s3.${region}.amazonaws.com"
        e.startsWith('http') ? e : 'https://' + e
    }

    /** Write, read and delete a small file. Returns null when all worked, or what failed. */
    String test(String prefix) {
        String key = (prefix ? prefix.replaceAll('^/+|/+$', '') + '/' : '') + '.hks-velero-test'
        byte[] body = "test ${System.currentTimeMillis()}".getBytes('UTF-8')
        Map r = request('PUT', key, body)
        if (r.status != 200) return "Can not write to the bucket: ${why(r)}"
        r = request('GET', key, null)
        if (r.status != 200) return "Can not read from the bucket: ${why(r)}"
        r = request('DELETE', key, null)
        if (!(r.status in [200, 204])) return "Can not delete from the bucket: ${why(r)}"
        null
    }

    /** Folder names right under prefix/ (S3 ListObjectsV2 with a delimiter). */
    Map folders(String prefix) {
        String p = prefix ? prefix.replaceAll('^/+|/+$', '') + '/' : ''
        Map r = request('GET', '', null, ['list-type': '2', delimiter: '/', prefix: p])
        if (r.status != 200) return [error: why(r)]
        List<String> out = []
        ((r.body as String) =~ /<CommonPrefixes>\s*<Prefix>([^<]+)<\/Prefix>/).each { m -> out << (m[1] as String).substring(p.length()).replaceAll('/+$', '') }
        [folders: out.findAll()]
    }

    static String why(Map r) {
        if (r.error) return r.error
        def m = (r.body as String) =~ /<Code>([^<]+)<\/Code>/
        String code = m ? m[0][1] : null
        switch (code) {
            case 'NoSuchBucket': return 'the bucket does not exist'
            case 'InvalidAccessKeyId': return 'the access key is wrong'
            case 'SignatureDoesNotMatch': return 'the secret key is wrong'
            case 'AccessDenied': return 'the keys are not allowed to do this'
            case 'AuthorizationHeaderMalformed': return 'the region is wrong'
            case 'PermanentRedirect': return 'the bucket is in another region'
        }
        "HTTP ${r.status}${code ? ' ' + code : ''}"
    }

    Map request(String method, String key, byte[] body, Map<String, String> query = [:]) {
        URL base = new URL(baseUrl)
        String path = '/' + bucket + (key ? '/' + key.split('/').collect { enc(it) }.join('/') : '')
        String qs = query.sort().collect { k, v -> "${enc(k)}=${enc(v)}" }.join('&')
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC)
        String amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        String day = now.format(DateTimeFormatter.ofPattern('yyyyMMdd'))
        String payloadHash = hex(sha256(body ?: new byte[0]))
        String host = base.port > 0 ? "${base.host}:${base.port}" : base.host
        String basePath = base.path ?: ''
        String fullPath = basePath + path
        String canonical = [method, fullPath, qs, "host:${host}\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${amzDate}\n",
                            'host;x-amz-content-sha256;x-amz-date', payloadHash].join('\n')
        String scope = "${day}/${region ?: 'us-east-1'}/s3/aws4_request"
        String toSign = ['AWS4-HMAC-SHA256', amzDate, scope, hex(sha256(canonical.getBytes('UTF-8')))].join('\n')
        byte[] k = hmac(hmac(hmac(hmac(('AWS4' + secretKey).getBytes('UTF-8'), day), region ?: 'us-east-1'), 's3'), 'aws4_request')
        String sig = hex(hmac(k, toSign))
        String auth = "AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=${sig}"
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base.protocol, base.host, base.port, fullPath + (qs ? '?' + qs : '')).openConnection()
            if (c instanceof HttpsURLConnection && !verifyTls) {
                ((HttpsURLConnection) c).SSLSocketFactory = Kube.trustAll()
                ((HttpsURLConnection) c).hostnameVerifier = { String h, SSLSession s -> true }
            }
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.setRequestProperty('Authorization', auth)
            c.setRequestProperty('x-amz-date', amzDate)
            c.setRequestProperty('x-amz-content-sha256', payloadHash)
            if (body != null) {
                c.doOutput = true
                c.outputStream.withStream { it.write(body) }
            }
            int status = c.responseCode
            InputStream s = status >= 400 ? c.errorStream : c.inputStream
            return [status: status, body: s?.getText('UTF-8') ?: '']
        } catch (Exception e) {
            return [status: 0, error: "can not reach ${baseUrl} (${e.class.simpleName}: ${e.message})"]
        }
    }

    static String enc(String s) { URLEncoder.encode(s, 'UTF-8').replace('+', '%20').replace('%7E', '~') }

    static byte[] sha256(byte[] b) { MessageDigest.getInstance('SHA-256').digest(b) }

    static byte[] hmac(byte[] key, String data) {
        Mac mac = Mac.getInstance('HmacSHA256')
        mac.init(new SecretKeySpec(key, 'HmacSHA256'))
        mac.doFinal(data.getBytes('UTF-8'))
    }

    static String hex(byte[] b) { b.collect { String.format('%02x', it & 0xff) }.join('') }
}
