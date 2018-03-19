import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import Converter._

@RunWith(classOf[JUnitRunner])
class ConverterSuite extends FunSuite {

  val validPath: String = "./converter/resources/cv.pdf"

  /**
    * Tests that sending a invalid filePath will return false
    */
  test("convertPDFtoIMG with an invalid filePath") {
    val testList = List(
      convertPDFtoIMG("", FileType.png),
      convertPDFtoIMG("non-existing file path", FileType.png)
    )
    assert(testList.forall(test => !test))
  }

  /**
    * Test that sending a null filepath return false since the conversion was not successful
    */
  test("convertPDFtoIMG with null filepath") {
    val res = convertPDFtoIMG(null, FileType.png)
    assert(!res)
  }

  /**
    * Test that sending a null fileType returns IllegalArgumentException
    */
  test("convertPDFtoIMG with null fileType") {
    assertThrows[IllegalArgumentException](
      convertPDFtoIMG(validPath, null)
    )
  }

  /**
    * Tests that sending a valid filePath and fileType to convertPDFtoIMG will return true
    * This means that the method successfully converted the pdf to the specified file type
    */
  test("convertPDFtoIMG with a valid filePath and fileType") {
    assert(convertPDFtoIMG(validPath, FileType.png))
  }

  /**
    * Tests that sending an invalid filePath (null, empty, non-existing) to convertPDFtoODF
    * will return false
    */
  test("convertPDFtoODF with invalid filePath") {
    val nullPath = convertPDFtoODT(null)
    val emptyPath = convertPDFtoODT("")
    val nonExistingPath = convertPDFtoODT("non-existing path")
    assert(!nullPath && !emptyPath && !nonExistingPath)
  }

  /**
    * Tests that sending a valid filePath to convertPDFtoODF will return true
    * meaning it successfully converted the PDF to ODF
    */
  test("convertPDFtoODF with valid filePath") {
    assert(convertPDFtoODT(validPath))
  }
}
