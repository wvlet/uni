package example.parquetbench

import wvlet.uni.weaver.Weaver

/** A row of the flat TPC-H lineitem-like table. DATE columns reach case classes as ISO strings. */
case class LineItem(
    lOrderkey: Long,
    lPartkey: Long,
    lSuppkey: Long,
    lLinenumber: Int,
    lQuantity: Double,
    lExtendedprice: Double,
    lDiscount: Double,
    lTax: Double,
    lReturnflag: String,
    lLinestatus: String,
    lShipdate: String,
    lCommitdate: String,
    lReceiptdate: String,
    lShipinstruct: String,
    lShipmode: String,
    lComment: String
) derives Weaver

/** One line of an order in the nested table */
case class OrderLine(
    lPartkey: Long,
    lSuppkey: Long,
    lLinenumber: Int,
    lQuantity: Double,
    lExtendedprice: Double,
    lDiscount: Double,
    lShipmode: String,
    lComment: String
) derives Weaver

/** A row of the nested table: an order with its lines (LIST of STRUCT) and attributes (MAP) */
case class Order(
    oOrderkey: Long,
    oCustkey: Long,
    oOrderstatus: String,
    oTotalprice: Double,
    oOrderdate: String,
    oOrderpriority: String,
    oComment: String,
    lines: Seq[OrderLine],
    attrs: Option[Map[String, Option[String]]]
) derives Weaver
