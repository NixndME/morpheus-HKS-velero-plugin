# HKS Velero for Morpheus

Back up and restore HKS clusters from Morpheus, with Velero.

- Finds your HKS clusters by itself. Install Velero on the ones you pick, or use a Velero that is already there.
- Keeps backups in S3 buckets (AWS S3, MinIO or any S3 compatible storage) and a readable YAML copy in Git.
- Several buckets and repositories, for example one for production and one for the rest. Each cluster picks its own.
- Back up now or on a schedule. See what is in a backup, what changed since, and its log, right in Morpheus.
- Restore everything, some namespaces, one namespace as a copy, or single objects.
- Restore a backup of one cluster into another cluster.

Works with Morpheus 9.0.2 and later. HKS clusters only.

## Walkthrough

[![Walkthrough: set up, back up and restore](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-preview.gif)](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-walkthrough.mp4)

[Watch the full walkthrough (MP4)](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-walkthrough.mp4):
upload the plugin, add buckets and Git repositories, install Velero on three clusters, back up an app with its
volume, delete it and restore it, restore one object, add a schedule, push a Git copy, and restore one cluster's
backup into another. Long waits are shown in fast forward.

| Set up | Back up and restore |
|---|---|
| ![Set up](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-setup.gif) | ![Back up and restore](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-backup-restore.gif) |
| **Restore one object** | **Restore into another cluster** |
| ![Restore one object](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-pick-items.gif) | ![Restore into another cluster](https://github.com/NixndME/morpheus-HKS-velero-plugin/releases/latest/download/hks-velero-other-cluster.gif) |

## Install

1. Download `morpheus-hks-velero-plugin.jar` from the latest release.
2. Administration > Integrations > Plugins > Add Plugin, pick the jar, Upload.
3. Integrations > New Integration > HKS Velero, give it a name, Save.
4. Give your roles the "HKS Velero" permission: Read to see backups, Full to back up, restore and change settings.
   Full can restore any object, Secrets too, into any namespace of a cluster the user can open. Give it only to
   people you would trust with that cluster.

To upgrade, upload the new jar over the old one. Settings stay.

## Set up

Open Administration > Integrations > HKS Velero.

1. **S3 buckets.** Add bucket. Pick a bucket or AWS cloud that is already in Morpheus, or type the endpoint, bucket
   and keys. Save and test writes, reads and deletes a small file.
2. **Git repositories (optional).** Add repository. Pick a Git integration from Morpheus, or type the URL, user and
   token. The test clones and pushes.
3. **Clusters.** Every HKS cluster is listed. Press Install, or Use when a cluster already runs Velero.
   Change picks the bucket and repository of a cluster. Clusters without a pick use the first one.
4. **Activity** shows recent backups, schedules and a live log.

Nothing runs until the test of its bucket passes. Keys and tokens are kept in Morpheus Cypher.

## Back up and restore

Open a cluster and click the Backup tab.

- **Back up now:** all namespaces or the ones you pick, keep for N days, with or without volume data. It shows
  which bucket the backup goes to. Tick Git copy to also push the YAML to Git.
- **Click a backup** to open it:
  - Contents: the backup as a tree, namespaces then kinds. Click a kind to list its objects on the right, tick
    the ones you want, Restore picked. Search, zoom out and Fit help with big backups.
  - Changes: what is new or gone in the cluster since the backup.
  - Log: the Velero log, with a switch for warnings and errors only.
  - Restore: everything, picked namespaces, or one namespace into a new namespace. Delete removes it from storage.
- **Schedules:** every hour, day, week or your own cron line. Click one for its backups, Run now, Edit, Pause.
- **Restores:** every restore with what came back, also the single object and Git ones.
- **Git copy:** every object as a YAML file in Git, so you can see what changed and when. Push now, a schedule,
  and Restore from Git.
- **Other clusters:** backups that other clusters keep in the same bucket. Show them, then restore one here.

Esc closes an open row.

## Good to know

- Velero runs inside each cluster it protects. Clusters share a bucket, each in its own folder.
- Volume data is copied by Velero's file copy (node agent). Volumes of type hostPath can not be copied.
- The Git copy and single object restores put back the YAML only, no volume data. Secrets never go to Git.
- Removing the plugin leaves Velero on the clusters. Use Remove on the Clusters tab first.
- Velero 1.18.4 and the AWS plugin 1.14.4 are bundled. The cluster nodes pull these images on install.

## Build

Needs JDK 17 and Gradle 8.5.

```
gradle -p plugin shadowJar
```

The jar is in `plugin/build/libs/`. The Velero YAML in `plugin/src/main/resources/velero/` is made with
`tools/render-velero.sh` (needs helm).
