---
description: Read and write MPC1 files, the column-oriented MessagePack format of PlazmaDB partitions, in pure Scala on the JVM, Scala.js, and Scala Native, with column projection over range reads from local files, HTTP, and object storage.
---

# MPC1 Files

`wvlet.uni.cloud.plazma` reads and writes MPC1 files, the column-oriented MessagePack format of
PlazmaDB partitions. MPC1 stores rows of any shape (MessagePack maps without a fixed schema) by
column. Each column is its own compressed block of MessagePack values, one per row, so a reader
fetches only the columns it needs. Like the rest of Uni, it is pure Scala, so the same code runs on
the JVM, Scala.js, and Scala Native.

## Write a file

Columns come from the rows. Each distinct key becomes a column, and a row without a value for a
column (an absent key or nil) stores nil there.

```scala
import wvlet.uni.cloud.plazma.*
import wvlet.uni.io.IOPath
import wvlet.uni.msgpack.spi.Value.*

val writer = MPC1.newWriter()
writer.addRow("time" -> LongValue(1700000000L), "user" -> StringValue("aina"))
writer.addRow("time" -> LongValue(1700000060L), "path" -> StringValue("/docs"))
writer.writeTo(IOPath.parse("part.mpc1"))
// or writer.toByteArray, or writer.writeTo(outputStream)
```

`writer.add(mapValue)` takes a row decoded as a MessagePack `MapValue`. Column names must be strings.

Blocks are gzip-compressed by default. `MPC1WriteOptions` sets the codec and the header metadata:

```scala
val options = MPC1WriteOptions
  .default
  .withCompression(MPC1Compression.GZIP)
  .withMetadata(MapValue(Map(StringValue("source") -> StringValue("import"))))
val tagged = MPC1.newWriter(options)
```

Other MPC1 readers always gunzip the column-name block, so keep GZIP for files other tools will
read. Uni reads both codecs. The writer holds the uncompressed columns in memory until it writes
the file, so split large outputs into several files. It keeps rarely set columns as (row, value)
pairs, so wide, sparse rows don't fill memory with nils.

## Read a file

Opening a file reads only its header and column names:

```scala
val file = MPC1.open("part.mpc1")
file.columnNames  // the columns, in file order
file.countRows()  // counted by scanning the smallest column

// Rows as MapValues; a column whose value is nil in a row is left out of that row
file.readRows().foreach(row => println(row.toJson))

// Only the columns you need: each column block is a separate range read
val users = file.readRows(Seq("time", "user")).toSeq

// One column, with NilValue for rows without a value
val times = file.readColumn("time").toSeq
file.close()
```

`MPC1.openUrl(url)` reads over HTTP range requests. `MPC1.openS3("s3://bucket/key")`,
`MPC1.openGcs("gs://bucket/key")`, and `MPC1.openAzure("az://container/key")` read from object
storage with the credential chains of [Object Storage](./storage). For a projected read, they
fetch the header and then only the selected column blocks.

## Format

```text
c6 'M' 'P' 0a | header size (int32, big-endian) | header | column-name block | column block ...
```

The header is the MessagePack array `["MPC1", [codec or nil], metadata, 0]`, followed by the size
of every block in a variable-byte code. The column-name block lists the column names as MessagePack
strings. Each column block holds one MessagePack value per row. Each block is compressed separately.

## Platforms

Reading works on every platform, browsers included. Writing gzip files needs a gzip codec. It is
available on the JVM, Scala Native, and Node.js, but not in browsers, where
`MPC1Compression.NONE` still works.
