import java.io.File

object Main {

  import reader.Extraction.Extractor._

  def main(args: Array[String]): Unit = {
    val filepath = new File("./reader/resources/MegaTester.pdf")
    //    val text = readPDF(filepath)
    //    getJSONObjects(text, List(("name", POSTag.PN), ("weight", POSTag.NUM)), "null").foreach(println)
    //        val objs = getAllObjects(text, List(("name", POSTag.PN), ("weight", POSTag.NUM)))
    //    println(objs)
    //    objs.foreach(println)
    //    objs.foreach(o => println(makeJSONString(o)))
    //    println(makeJSONString(List(("name", List("Lucas", "Margarida")), ("weight", List("31", "12", "3")))))
  }
}