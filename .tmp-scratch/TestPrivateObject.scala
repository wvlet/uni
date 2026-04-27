object TestPrivateObject:
  trait Signal
  private object Hidden extends Signal
  def main(args:Array[String]): Unit =
    val cls = classOf[Hidden.type]
    println(s"class=${cls}, simple=${cls.getSimpleName}")
    try
      val f = cls.getField("MODULE$")
      println(s"field=${f}")
      println(s"value=${f.get(null)}")
    catch
      case e: Throwable => e.printStackTrace()
