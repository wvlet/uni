object TestLocalSimpleName:
  abstract class Animal
  def main(args:Array[String]): Unit =
    case class Fish(depth:Int) extends Animal
    println(classOf[Fish].getSimpleName)
    println(Fish(1).getClass.getSimpleName)
