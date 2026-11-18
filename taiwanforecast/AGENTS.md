# Release continuity

The user develops this application here and expects the agent to manage version retention and signing backups. Search available project backups before asking the user for files.

- Retain previous signed APKs at their versioned paths and GitHub Releases. Keep corresponding source revisions and release hashes.
- Preserve each signing private key and certificate in durable private storage, with recovery instructions and the certificate fingerprint. Never commit private keys or passwords to this public repository.
- Before publishing, verify the candidate APK signature matches the installed release lineage and verify the signing backup is recoverable. A temporary workspace is insufficient storage for signing material.
- If original signing material is missing, report the verified recovery status accurately. Do not publish a new-signature APK as an in-place update.

Recovery status on 2026-11-18: the original private key has not been located in accessible workspace files or named Library backups. Existing signed APKs remain in versioned directories. GitHub's release workflow publishes pre-signed APKs and does not itself sign builds. This status does not establish that all possible backups are absent.
