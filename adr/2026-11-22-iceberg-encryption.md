# Encrypted Iceberg tables stay unsupported

- Status: Accepted
- Date: 2026-11-22

## Context

An Iceberg table can encrypt its files (format version 3 metadata, written by iceberg-java when the table sets `encryption.key-id`). Uni refuses these tables when the dataset opens ("is an encrypted Iceberg table"), and refuses encrypted Parquet files (`PARE` footers, encrypted columns) in any dataset. This ADR records what reading them would take, and why Uni doesn't.

What the spec and iceberg-java require:

- **Envelope keys through a KMS.** The table property `encryption.key-id` names a master key in a key management service (AWS KMS, Google Cloud KMS, Azure Key Vault, or a custom `encryption.kms-impl`). The metadata's `encryption-keys` list holds key-encryption keys wrapped by that master key (`encrypted-by-id`). A snapshot's `key-id` names the one that protects its manifest list's key metadata. Reading anything starts with a KMS unwrap call made with the reader's cloud credentials and permissions.
- **AES-GCM streams for metadata files.** Manifest lists and manifests (and Avro data files) are AGS1 files: a magic number, a block length, then blocks that are each AES-GCM encrypted, with a 12-byte nonce, a 16-byte tag, and an AAD of the file's AAD prefix plus the block number. Each file's key, AAD prefix, and length are in `key_metadata` (an Avro record), which the parent file holds.
- **Parquet Modular Encryption for data files.** Parquet data and delete files use the Parquet format's own encryption (`AES_GCM_V1` or `AES_GCM_CTR_V1`). The footer, every page header, and every page are separate modules with their own AADs, and the column keys come from the file's key metadata. A reader decrypts at every page, inside the Parquet reader's hot path.
- **Writers** must create keys, wrap them through the KMS, and encrypt every file they commit.

## Decision

- **Don't implement table encryption in Uni.** The table and file checks stay, with their messages. Read encrypted tables with iceberg-java (Spark, Trino, Flink), which has the KMS clients and Parquet Modular Encryption, and use Uni with tables that aren't encrypted.
- **Why:**
  - **A KMS client per cloud is out of scope.** Unwrapping keys needs signed calls to each provider's KMS, IAM setup, and key caching and rotation. Uni's object stores read and write bytes; they don't manage keys. Adding KMS clients would make the Parquet module depend on cloud key services that most Uni users never touch.
  - **No AES-GCM on every platform.** The JVM has `javax.crypto`, and Node.js has `crypto`. Browsers have only the asynchronous WebCrypto API, which the synchronous reader can't call. Scala Native's javalib has no `javax.crypto`, so AES there would mean new C bindings to a crypto library. A pure-Scala AES-GCM would be a security primitive that Uni would have to keep constant-time and audited on three platforms.
  - **Parquet Modular Encryption touches the whole reader.** Per-page decryption, per-module AADs, and encrypted column metadata reach every page read. That is a large change for a feature few tables use.
  - **There are few such tables, and no realistic fixtures.** DuckDB, pyiceberg, and Snowflake don't write encrypted Iceberg tables. A fixture would need Spark with iceberg-java and a KMS, or iceberg-java's test KMS, which says little about real deployments.
- **Revisit when** a user brings a concrete need and a key setup to test against. The likely shape then is a pluggable key unwrapper supplied by the caller, JVM-only AES through `javax.crypto`, and Parquet Modular Encryption in the reader, with the other platforms still failing by name.

## Consequences

- Opening an encrypted table fails before any data file is read, naming the metadata file. An encrypted Parquet file fails by name in any dataset.
- Uni never holds or asks for encryption keys or KMS credentials.
- The docs list encrypted tables under what Iceberg reads refuse, and point readers to iceberg-java engines.
