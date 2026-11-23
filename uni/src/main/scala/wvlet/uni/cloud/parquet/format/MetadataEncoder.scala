package wvlet.uni.cloud.parquet.format

import wvlet.uni.cloud.parquet.thrift.CompactWriter

/** Encodes metadata classes as Thrift structs: the inverse of [[MetadataDecoder]] */
private[parquet] object MetadataEncoder:

  def fileMetaData(m: FileMetaData): Array[Byte] =
    // Leaf columns whose order is unsigned, so the signed deprecated min/max would mislead
    val unsigned = m
      .schema
      .filter(e => e.physicalType.isDefined && e.numChildren.forall(_ == 0))
      .map(
        _.logicalType
          .exists {
            case LogicalType.Integer(_, false) =>
              true
            case _ =>
              false
          }
      )
    val w = CompactWriter()
    w.struct {
      w.i32(1, m.version)
      w.structList(2, m.schema)(schemaElement(w, _))
      w.i64(3, m.numRows)
      w.structList(4, m.rowGroups)(rowGroup(w, _, unsigned))
      if m.keyValueMetadata.nonEmpty then
        w.structList(5, m.keyValueMetadata.toSeq.sortBy(_._1)) { (k, v) =>
          w.string(1, k)
          w.string(2, v)
        }
      m.createdBy.foreach(w.string(6, _))
      // column_orders: TYPE_DEFINED_ORDER for every leaf, so readers trust min_value/max_value
      val leaves = m.schema.count(e => e.physicalType.isDefined && e.numChildren.forall(_ == 0))
      w.structList(7, Seq.fill(leaves)(())) { _ =>
        w.emptyStructField(1)
      }
    }
    w.toByteArray

  end fileMetaData

  private def schemaElement(w: CompactWriter, e: SchemaElement): Unit =
    e.physicalType.foreach(t => w.i32(1, t.id))
    e.typeLength.foreach(w.i32(2, _))
    e.repetition.foreach(r => w.i32(3, r.id))
    w.string(4, e.name)
    e.numChildren.foreach(w.i32(5, _))
    e.logicalType.flatMap(convertedType).foreach(w.i32(6, _))
    e.logicalType match
      case Some(LogicalType.Decimal(scale, precision)) =>
        w.i32(7, scale)
        w.i32(8, precision)
      case _ =>
    e.fieldId.foreach(w.i32(9, _))
    e.logicalType
      .foreach { lt =>
        if hasLogicalTypeStruct(lt) then
          w.structField(10)(logicalType(w, lt))
      }

  /** The legacy converted type, which older readers use instead of the logical type */
  private def convertedType(lt: LogicalType): Option[Int] =
    lt match
      case LogicalType.String =>
        Some(0)
      case LogicalType.Map =>
        Some(1)
      case LogicalType.List =>
        Some(3)
      case LogicalType.Enum =>
        Some(4)
      case LogicalType.Decimal(_, _) =>
        Some(5)
      case LogicalType.Date =>
        Some(6)
      case LogicalType.Time(true, TimeUnit.MILLIS) =>
        Some(7)
      case LogicalType.Time(true, TimeUnit.MICROS) =>
        Some(8)
      case LogicalType.Timestamp(true, TimeUnit.MILLIS) =>
        Some(9)
      case LogicalType.Timestamp(true, TimeUnit.MICROS) =>
        Some(10)
      case LogicalType.Integer(bits, signed) if Set(8, 16, 32, 64).contains(bits) =>
        val base =
          if signed then
            15
          else
            11
        Some(base + Integer.numberOfTrailingZeros(bits) - 3)
      case LogicalType.Json =>
        Some(19)
      case LogicalType.Bson =>
        Some(20)
      case LogicalType.Interval =>
        Some(21)
      case LogicalType.MapKeyValue =>
        Some(2)
      case _ =>
        None

  private def hasLogicalTypeStruct(lt: LogicalType): Boolean =
    lt match
      case LogicalType.Interval | LogicalType.MapKeyValue | LogicalType.Other(_) =>
        false
      case _ =>
        true

  private def timeUnit(w: CompactWriter, unit: TimeUnit): Unit =
    unit match
      case TimeUnit.MILLIS =>
        w.emptyStructField(1)
      case TimeUnit.MICROS =>
        w.emptyStructField(2)
      case TimeUnit.NANOS =>
        w.emptyStructField(3)

  private def logicalType(w: CompactWriter, lt: LogicalType): Unit =
    lt match
      case LogicalType.String =>
        w.emptyStructField(1)
      case LogicalType.Map =>
        w.emptyStructField(2)
      case LogicalType.List =>
        w.emptyStructField(3)
      case LogicalType.Enum =>
        w.emptyStructField(4)
      case LogicalType.Decimal(scale, precision) =>
        w.structField(5) {
          w.i32(1, scale)
          w.i32(2, precision)
        }
      case LogicalType.Date =>
        w.emptyStructField(6)
      case LogicalType.Time(utc, unit) =>
        w.structField(7) {
          w.bool(1, utc)
          w.structField(2)(timeUnit(w, unit))
        }
      case LogicalType.Timestamp(utc, unit) =>
        w.structField(8) {
          w.bool(1, utc)
          w.structField(2)(timeUnit(w, unit))
        }
      case LogicalType.Integer(bits, signed) =>
        w.structField(10) {
          w.i8(1, bits)
          w.bool(2, signed)
        }
      case LogicalType.Unknown =>
        w.emptyStructField(11)
      case LogicalType.Json =>
        w.emptyStructField(12)
      case LogicalType.Bson =>
        w.emptyStructField(13)
      case LogicalType.Uuid =>
        w.emptyStructField(14)
      case LogicalType.Float16 =>
        w.emptyStructField(15)
      case LogicalType.Variant =>
        w.emptyStructField(16)
      case LogicalType.Geometry(crs) =>
        w.structField(17)(crs.foreach(w.string(1, _)))
      case LogicalType.Geography(crs, algorithm) =>
        w.structField(18) {
          crs.foreach(w.string(1, _))
          // A known name, or the number of an algorithm this reader doesn't know
          algorithm
            .flatMap(a =>
              Some(LogicalType.GeographyAlgorithms.indexOf(a)).filter(_ >= 0).orElse(a.toIntOption)
            )
            .foreach(w.i32(2, _))
        }
      case LogicalType.Interval | LogicalType.MapKeyValue | LogicalType.Other(_) =>
        ()

  private def rowGroup(w: CompactWriter, g: RowGroupMeta, unsigned: IndexedSeq[Boolean]): Unit =
    w.structList(1, g.columns.zipWithIndex) { (c, i) =>
      columnChunk(w, c, unsigned.lift(i).getOrElse(false))
    }
    w.i64(2, g.totalByteSize)
    w.i64(3, g.numRows)
    if g.sortingColumns.nonEmpty then
      w.structList(4, g.sortingColumns) { c =>
        w.i32(1, c.columnIdx)
        w.bool(2, c.descending)
        w.bool(3, c.nullsFirst)
      }
    g.columns.headOption.foreach(c => w.i64(5, c.startOffset))
    w.i64(6, g.columns.map(_.totalCompressedSize).sum)
    g.ordinal.foreach(w.i16(7, _))

  private def columnChunk(w: CompactWriter, c: ColumnChunkMeta, unsigned: Boolean): Unit =
    c.filePath.foreach(w.string(1, _))
    w.i64(2, c.startOffset)
    w.structField(3) {
      w.i32(1, c.physicalType.id)
      w.i32List(2, c.encodings.map(_.id))
      w.stringList(3, c.path)
      w.i32(4, c.codec.id)
      w.i64(5, c.numValues)
      w.i64(6, c.totalUncompressedSize)
      w.i64(7, c.totalCompressedSize)
      w.i64(9, c.dataPageOffset)
      c.dictionaryPageOffset.foreach(w.i64(11, _))
      c.statistics.foreach(s => w.structField(12)(statistics(w, s, c.physicalType, unsigned)))
    }

  private def statistics(
      w: CompactWriter,
      s: Statistics,
      tpe: PhysicalType,
      unsigned: Boolean
  ): Unit =
    // Older readers know only the deprecated min/max (2, 1), which use signed order. Write them
    // only for signed numbers and booleans, where that order is the column's order.
    if !unsigned && tpe != PhysicalType.BYTE_ARRAY && tpe != PhysicalType.FIXED_LEN_BYTE_ARRAY then
      s.max.foreach(w.binary(1, _))
      s.min.foreach(w.binary(2, _))
    s.nullCount.foreach(w.i64(3, _))
    s.distinctCount.foreach(w.i64(4, _))
    s.max.foreach(w.binary(5, _))
    s.min.foreach(w.binary(6, _))

  def pageHeader(h: PageHeader): Array[Byte] =
    val w = CompactWriter()
    w.struct {
      w.i32(1, h.pageType.id)
      w.i32(2, h.uncompressedSize)
      w.i32(3, h.compressedSize)
      h.crc.foreach(w.i32(4, _))
      h.dataPage
        .foreach { d =>
          w.structField(5) {
            w.i32(1, d.numValues)
            w.i32(2, d.encoding.id)
            w.i32(3, d.definitionLevelEncoding.id)
            w.i32(4, d.repetitionLevelEncoding.id)
          }
        }
      h.dictionaryPage
        .foreach { d =>
          w.structField(7) {
            w.i32(1, d.numValues)
            w.i32(2, d.encoding.id)
          }
        }
    }
    w.toByteArray

end MetadataEncoder
