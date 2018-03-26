import java.io.{File, PrintWriter}

import com.google.common.collect.Iterators
import javax.imageio.ImageIO
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.fit.pdfdom.{PDFDomTree, PDFDomTreeConfig}
import org.odftoolkit.odfdom.`type`.Color
import org.odftoolkit.simple.TextDocument
import org.odftoolkit.simple.style.{Font, StyleTypeDefinitions}
import scala.io.Source
import ImageProcessing._

/**
  * Singleton object that implements the functions regarding the conversion of pdf into other file types
  */
object Converter {

  /**
    * Method that converts a pdf file into a png image
    *
    * @param filePath - String containing the URI to the pdf file
    * @param fileType - FileType with one of the possible file types of the Enum FileType to be converted into
    * @return - A Boolean saying if the conversion was successful
    * @throws IllegalArgumentException if the specified fileType did not equal the supported file types
    */
  @throws[IllegalArgumentException]
  def convertPDFtoIMG(filePath: String, fileType: FileType.Value): Boolean = {
    require(fileType != null, "File type must be one of png, jpg, gif or jpeg")

    try {
      val pdf = PDDocument.load(new File(filePath))
      val renderer = new PDFRenderer(pdf)
      val dir = new File("./target/PDFtoIMG")
      if (!dir.exists) dir.mkdir
      for (i <- 0 until pdf.getNumberOfPages) {
        val image = renderer.renderImage(i)
        ImageIO.write(image, fileType.toString, new File("./target/PDFtoIMG/Converted_Page" + i + "_" + System.nanoTime() + "." + fileType.toString))
      }
      true
    } catch {
      case e: Exception => e.printStackTrace(); false
    }
  }

  /**
    * Method that creates a odf file with the information taken from a pdf (Note: does not maintain full formating)
    *
    * @param filePath - String containing the URI to the pdf file
    * @return - A Boolean saying if the conversion was successful
    */
  def convertPDFtoODT(filePath: String): Boolean = {
    try {
      convertPDFtoHTML(filePath)
      convertHTMLtoODT(filePath)
      cleanImageDir()
      true
    } catch {
      case e: Exception => e.printStackTrace(); false
    }
  }

  /**
    * Method that creates a html file with the information from a pdf
    *
    * @param filePath - String containing the URI to the pdf file
    */
  private def convertPDFtoHTML(filePath: String) = {
    val outfile = "out.html"
    val config = PDFDomTreeConfig.createDefaultConfig
    val document = PDDocument.load(new File(filePath))
    val parser = new PDFDomTree(config)
    val output = new PrintWriter(outfile, "utf-8")
    parser.writeText(document, output)
    output.close()
    document.close()
  }

  /**
    * Method that creates a odt file from a html previously converted from a pdf
    *
    * @param filePath - String containg the URI from the original pdf file
    */
  private def convertHTMLtoODT(filePath: String) = {

    /**
      * Auxiliary method that iterates through the html lines, parses the information and returns the converted odt file
      *
      * @param lines    - the html remaining lines to be read
      * @param prevLine - the previous line read
      * @param imgList  - the remaining images that need to be added to the odt file
      * @param document - current state of the odt document
      * @return - the document with all the information extracted from the html
      */
    def recFunc(lines: List[String], prevLine: String, imgList: List[File], document: TextDocument): TextDocument = {
      val regexLine = "<(/body|div class=\"page\"|div class=\"p\"|img).*(?:>|)".r
      val regexPara = ".*<.*style=\"top:(\\d+).*font-family:(.*);font-size:(\\d+).*\">(.*)</div>".r
      val regexColo = ".*;color:(.{7});.*".r

      val line = lines.head
      val group = regexLine.findAllIn(line).matchData.map(_.group(1)).toList.mkString("")
      val parTotal: Int = Iterators.size(document.getParagraphIterator)
      val par = document.getParagraphByIndex(parTotal - 1, false)

      group match {
        case "div class=\"page\"" =>
          if (line.contains("page_0")) {} else {
            document.addPageBreak()
          }
          recFunc(lines.tail, lines.head, imgList, document)
        case "div class=\"p\"" =>
          val regexPara(top, fontType, fontSize, text) = line
          val colorStr = if (line.contains("color")) {
            regexColo.findFirstMatchIn(line).map(_.group(1)).mkString("")
          } else {
            "#000000"
          }
          val color = Color.valueOf(colorStr)
          if (prevLine.contains("div class=\"p\"")) {
            val regexPara(topP, _, _, _) = prevLine
            if (topP.toInt < top.toInt) {
              val newPar = document.addParagraph(text)
              newPar.setFont(new Font(fontType, StyleTypeDefinitions.FontStyle.REGULAR, Integer.parseInt(fontSize), color))
              recFunc(lines.tail, lines.head, imgList, document)
            } else {
              par.appendTextContent(" " + text)
              recFunc(lines.tail, lines.head, imgList, document)
            }
          } else {
            val newPar = document.addParagraph(text)
            newPar.setFont(new Font(fontType, StyleTypeDefinitions.FontStyle.REGULAR, Integer.parseInt(fontSize), color))
            recFunc(lines.tail, lines.head, imgList, document)
          }
        case "img" =>
          if (imgList.nonEmpty) {
            document.addParagraph("")
            document.newImage(imgList.head.toURI)
            document.addParagraph("")
            recFunc(lines.tail, lines.head, imgList.tail, document)
          } else {
            recFunc(lines.tail, lines.head, imgList, document)
          }
        case "/body" => document
        case _ => recFunc(lines.tail, lines.head, imgList, document)
      }

    }

    val bufferedSource = Source.fromFile(new File("out.html"))
    val htmlLines = bufferedSource.getLines.toList
    bufferedSource.close()
    val pdf = PDDocument.load(new File(filePath))
    extractImgs(pdf)
    pdf.close()
    val dir = new File("./target/images")
    val imgs = dir.listFiles.filter(_.isFile).toList.reverse

    val newOdt = recFunc(htmlLines, "", imgs, TextDocument.newTextDocument())

    newOdt.save("out.odt")
  }

  /**
    * Method that deletes all files from the image folder
    */
  private def cleanImageDir() {
    val dir = new File("./target/images")
    if(dir.exists) {
      val files = dir.listFiles.filter(_.isFile).toList
      files.foreach(_.delete)
    }
  }
}
