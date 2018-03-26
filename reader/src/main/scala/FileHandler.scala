import java.io.File
import com.sksamuel.scrimage.Image

import org.apache.pdfbox.pdmodel.PDDocument

/**
  * Singleton object that handles all operations regarding file input
  */
object FileHandler {


  /**
    * Method that loads the file received as input and returns the loaded document
    *
    * @param file - File to be loaded
    * @return a PDDocument object representing the loaded PDF document
    */
  def loadPDF(file: File): Option[PDDocument] = {
    try {
      val pdf: PDDocument = PDDocument.load(file)
      Option(pdf)
    } catch {
      case e: Exception => e.printStackTrace(); None
    }
  }

  /**
    * Method that loads a picture from a file and returns a Scrimage Image object representing the loaded image
    *
    * @param file - The image file to be loaded
    * @return an Image object representing the loaded image from the given file
    */
  def loadImage(file: File): Option[Image] = {
    try {
      val image = Image.fromFile(file)
      Option(image)
    } catch {
      case e: Exception => e.printStackTrace(); None
    }
  }

}
