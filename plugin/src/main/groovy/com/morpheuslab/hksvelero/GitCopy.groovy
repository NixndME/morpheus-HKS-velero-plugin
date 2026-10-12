package com.morpheuslab.hksvelero

import groovy.util.logging.Slf4j
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish

import java.nio.file.Files

/** The readable YAML copy in a Git repository: one file per object, under <folder>/<cluster>/. */
@Slf4j
class GitCopy {

    String url
    String branch
    String folder
    String user
    String secret
    boolean skipTls

    CredentialsProvider creds() { new GitLogin(user ?: 'git', secret ?: '', skipTls) }

    String clusterDir(String clusterName) { Storage.folder(folder, clusterName) }

    /** Clone, push a test branch and delete it again. Returns null when it worked, or what failed. */
    String test() {
        File work = Files.createTempDirectory('hks-velero-git').toFile()
        try {
            Git g = open(work)
            String ref = "refs/heads/hks-velero-test-${System.currentTimeMillis()}"
            if (g.repository.resolve('HEAD') == null) return 'The repository has no commits yet. Add a README in it first.'
            String err = pushed(g.push().setCredentialsProvider(creds()).setTimeout(60).setRefSpecs(new RefSpec("HEAD:${ref}")).call())
            if (err) return "Can not write to the repository: ${err}"
            g.push().setCredentialsProvider(creds()).setTimeout(60).setRefSpecs(new RefSpec(":${ref}")).call()
            g.close()
            null
        } catch (Exception e) {
            why(e)
        } finally {
            work.deleteDir()
        }
    }

    /** Write the files of one cluster, commit and push. */
    Map export(String clusterName, Map<String, String> files, String message) {
        File work = Files.createTempDirectory('hks-velero-git').toFile()
        try {
            Git g = open(work)
            if (g.repository.resolve('HEAD') == null) return [error: 'The repository has no commits yet. Add a README in it first.']
            String dir = clusterDir(clusterName)
            File root = new File(work, dir)
            root.deleteDir()
            files.each { String rel, String text ->
                File f = new File(root, rel)
                f.parentFile.mkdirs()
                f.setText(text, 'UTF-8')
            }
            g.add().addFilepattern(dir).call()
            g.add().addFilepattern(dir).setUpdate(true).call()
            def st = g.status().call()
            int changed = st.changed.size()
            int added = st.added.size()
            int removed = st.removed.size() + st.missing.size()
            if (st.clean) return [files: files.size(), added: 0, changed: 0, removed: 0, commit: null]
            String msg = "${message}: ${added} new, ${changed} changed, ${removed} removed"
            def commit = g.commit().setMessage(msg).setAuthor('HKS Velero', 'hks-velero@morpheus.local')
                .setCommitter('HKS Velero', 'hks-velero@morpheus.local').call()
            String err = pushed(g.push().setCredentialsProvider(creds()).setTimeout(120).call())
            g.close()
            if (err) return [error: "Push failed: ${err}"]
            [files: files.size(), added: added, changed: changed, removed: removed, commit: commit.name.take(10)]
        } catch (Exception e) {
            [error: why(e)]
        } finally {
            work.deleteDir()
        }
    }

    /** Read the copy of one cluster back. */
    Map read(String clusterName) {
        File work = Files.createTempDirectory('hks-velero-git').toFile()
        try {
            Git g = open(work)
            File root = new File(work, clusterDir(clusterName))
            if (!root.exists()) return [error: 'There is no Git copy of this cluster yet.']
            Map<String, String> objects = [:]
            root.eachFileRecurse { File f -> if (f.isFile() && f.name.endsWith('.yaml')) objects[root.toPath().relativize(f.toPath()).toString()] = f.getText('UTF-8') }
            def head = g.log().setMaxCount(1).call().find()
            g.close()
            List<String> nss = objects.keySet().collect { it.split('/')[0] }.findAll { it != '_cluster' }.unique().sort()
            [namespaces: nss, objects: objects, commit: head?.name?.take(10), when: head ? head.commitTime * 1000L : null, message: head?.shortMessage]
        } catch (Exception e) {
            [error: why(e)]
        } finally {
            work.deleteDir()
        }
    }

    private Git open(File work) {
        def c = Git.cloneRepository().setURI(url).setDirectory(work).setDepth(1).setCloneAllBranches(false).setTimeout(60)
            .setCredentialsProvider(creds())
        if (branch) c.setBranch(branch).setBranchesToClone(["refs/heads/${branch}".toString()])
        c.call()
    }

    static String pushed(Iterable<PushResult> results) {
        for (PushResult r : results) {
            for (RemoteRefUpdate u : r.remoteUpdates) {
                if (!(u.status in [RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE])) return "${u.status}${u.message ? ' ' + u.message : ''}"
            }
        }
        null
    }

    static String why(Exception e) {
        String m = (e.message ?: e.class.simpleName)
        if (m.contains('not authorized') || m.contains('Authentication is required') || m.contains('401')) return 'the user or token is wrong'
        if (m.contains('not found') || m.contains('404')) return 'the repository URL is wrong or the token can not see it'
        if (m.contains('Remote branch') || m.contains('not found in upstream')) return 'that branch does not exist'
        if (m.contains('SSL') || m.contains('certificate') || m.contains('PKIX')) return 'the TLS certificate is not trusted (tick Do not check the certificate for a lab server)'
        if (e instanceof TransportException && (m.contains('connect') || m.contains('timed out'))) return "can not reach the Git server (${m})"
        m
    }
}

/** Gives JGit the login, and says yes to skipping the certificate check when asked to. */
class GitLogin extends CredentialsProvider {
    String user, secret
    boolean skipTls

    GitLogin(String user, String secret, boolean skipTls) { this.user = user; this.secret = secret; this.skipTls = skipTls }

    boolean isInteractive() { false }
    boolean supports(CredentialItem... items) { true }

    boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        items.each { CredentialItem i ->
            if (i instanceof CredentialItem.Username) i.value = user
            else if (i instanceof CredentialItem.Password) i.value = secret.toCharArray()
            else if (i instanceof CredentialItem.YesNoType) i.value = skipTls
        }
        true
    }
}
