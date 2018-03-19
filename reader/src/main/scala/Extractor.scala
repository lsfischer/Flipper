import java.io.{File, FileNotFoundException}
import java.text.Normalizer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import scala.util.matching.Regex
import OpenNLP._
import ImageProcessing._
import scala.annotation.tailrec
import scala.io.Source

/**
  * Singleton object that implements all the functionalities regarding the extraction of information from a PDF document
  */
object Extractor {

  type Keyword = String
  type MatchedPair = List[(Keyword, List[String])]

  /**
    * Method that given a file path (maybe change to a real file) will load that PDF file and read the text from it
    *
    * @param filePath - String containing the URI for the file to be loaded
    * @return An Option wrapping a String containing all the text found in the document. Returns None in case of Exception
    */
  def readPDF(filePath: String, readImages: Boolean = true): Option[String] = {
    try {
      val pdf = PDDocument.load(new File(filePath))
      val document = new PDFTextStripper

      val str = Normalizer.normalize(document.getText(pdf), Normalizer.Form.NFD)
        .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")

      if (readImages) {
        val imagesList = extractImgs(pdf)
        val imageListContent = imagesList.getOrElse(List())
        imageListContent.foreach(f => println(readImageText(f)))
      }
      //If we want to add the images text to str, we can do so, although its not very precise

      pdf.close()
      cleanImageDir()
      Option(str)
    } catch {
      case t: FileNotFoundException => t.printStackTrace(); None
      case tr: Throwable => tr.printStackTrace(); None
    }
  }

  /**
    * Method that will iterate through a list of given keywords and will try to obtain a value for that keyword
    *
    * @param text        - Text from the PDF extracted from readPDF method
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    *                    use that regular expression instead of ours
    * @throws IllegalArgumentException If the keywords list is empty
    * @return List containing pairs of Keywords and a List (non-repeating) of values found for that keyword
    */
  @throws[IllegalArgumentException]
  def getAllMatchedValues(text: Option[String], keywords: List[(Keyword, String)], clientRegEx: Map[Keyword, Regex] = Map()): MatchedPair = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    val textContent = text.getOrElse("")
    if (textContent.isEmpty) List()
    else {
      val knownRegEx: Map[String, Regex] = importRegExFile(textContent) //load correct RegEx map
      val matched = keywords.map(key => {

        //If the client sent a custom RegEx to use on this key, use it
        if (clientRegEx.contains(key._1)) //&& clientRegEx != null ??
          (key._1, clientRegEx(key._1).findAllIn(textContent).matchData.map(_.group(1)).toList.distinct)

        //if we already know a good RegEx for this keyword, use it
        else if (knownRegEx.contains(key._1))
          (key._1, knownRegEx(key._1).findAllIn(textContent).matchData.map(_.group(1)).toList.distinct)

        else findKeywordInText(key._1, key._2, textContent) //to be changed, here we need to manually search for the keywords in the text
      })
      filterNewLines(matched)
    }
  }

  /**
    * Method that operates like getAllMatchedValues but instead returns only the first element it finds for a given
    * keyword, representing a single JSON object
    *
    * @param text        - Text from the PDF extracted from readPDF method
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    * @throws IllegalArgumentException If the keywords list is empty
    * @return A List containing pairs of keywords with a single matched value
    */
  @throws[IllegalArgumentException]
  def getSingleMatchedValue(text: Option[String], keywords: List[(Keyword, String)], clientRegEx: Map[Keyword, Regex] = Map()): MatchedPair = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    val textContent = text.getOrElse("")
    if (textContent.isEmpty) List()
    else getAllMatchedValues(text, keywords, clientRegEx).map(pair => if (pair._2.nonEmpty) (pair._1, List(pair._2.head)) else (pair._1, List()))
  }

  /**
    * Method that will return a List of Matched Pairs. This method operates as getSingleMatcheValue, but instead
    * of returning just one object, returns a list containing all of them
    *
    * @param text        - Text from the PDF extracted from readPDF method
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    * @throws IllegalArgumentException If the keywords list is empty
    * @return A List containing sublists of pairs of keywords with single matched values
    */
  @throws[IllegalArgumentException]
  def getAllObjects(text: Option[String], keywords: List[(Keyword, String)], clientRegEx: Map[Keyword, Regex] = Map()): List[MatchedPair] = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    val textContent = text.getOrElse("")
    if (textContent.isEmpty) List()
    else {
      def getListSizes(matchedValues: MatchedPair): List[(Keyword, Int)] = {
        for (m <- matchedValues) yield (m._1, m._2.size)
      }

      val matchedValues = getAllMatchedValues(text, keywords, clientRegEx)
      val mostFound = getListSizes(matchedValues).maxBy(_._2) //Gets the size of the pair that has the most values

      val mappedValues = for (i <- 0 to mostFound._2; m <- matchedValues) yield {
        if (m._2.size > i) //Prevent array out of bounds exception
          List(m._2(i))
        else List()
      }
      mappedValues.zipWithIndex.map(pair => (keywords(pair._2 % keywords.length)._1, pair._1)).toList.grouped(keywords.size).toList
    }
  }

  /**
    * Method that given a List of pairs of keywords and their respective values will create a string in JSON format
    *
    * This method receives an optional flag with information on how to return non existing values,
    * this flag can be :
    * "empty" (default) - returns an empty string
    * "null" - returns the value null (in quotations, can be changed)
    * "remove" - removes that specific field altogether
    *
    * @param listJSON - List of pairs of keywords and their respective values
    * @param flag     -Optional flag with information on how to return non-existing values
    * @return
    */
  def makeJSONString(listJSON: MatchedPair, flag: String = "empty"): String = {
    //    def isAllDigits(x: String) = x forall Character.isDigit //TODO when all the characters are numbers dont add the quotes

    lazy val quote = "\""

    val pseudoJSON = listJSON.map(k =>
      if (k._2.nonEmpty) {

        if (k._2.size > 1)
          s"${quote + k._1 + quote} : ${quote + k._2.mkString("[", ", ", "]") + quote}"
        else
          s"${quote + k._1 + quote} : ${quote + k._2.head + quote}"

      } else {

        if (flag == "empty")
          s"${quote + k._1 + quote} : ${quote + quote}"
        else if (flag == "null")
          s"${quote + k._1 + quote} : null"

      }
    )
    if (flag == "remove") pseudoJSON.filter(_ != ()).mkString("{", ", ", "}") else pseudoJSON.mkString("{", ", ", "}")
  }

  /**
    * Method that will remove all the new line characters from the list of values obtain from a keyword
    *
    * @param matchedValues - List of pairs of Keyword and the values obtained for that keyword
    * @return The same list as passed by parameter but with no new line characters
    */
  private def filterNewLines(matchedValues: MatchedPair): MatchedPair = {
    matchedValues.map(pair => {
      val setOfValues = pair._2
      (pair._1, setOfValues.map(_.replaceAll("[\\r\\n]", "").trim)) //remove all new line characters and trim all elements
    })
  }

  /**
    * Method that gets all keywords and respective values from know form and returns a JSON string
    *
    * @param text - Text from the PDF extracted from readPDF method
    * @return - A JSON String containing all the information in the text passed by arguments
    */
  def getJSONFromForm(text: Option[String]): String = {
    val textContent = text.getOrElse("")
    val formRegex = "(.+):\\s+(.+)".r
    val form = formRegex.findAllIn(textContent).matchData.map(l => (l.group(1), List(l.group(2)))).toList
    makeJSONString(form)
  }

  /**
    * Method that will try to find a value for a given keyword if we do not have any RegEx for that keyword
    * (or the client didn't send any).
    * This method uses Apaches openNLP for determining the POS Tag (Part of Speech) to make sure we return a correct value
    *
    * @param keyword - The keyword to find the value for
    * @param text    - The text in which to look for the value
    * @param tag     - The POS Tag of the value we want to return
    * @return A pair containing the keyword and a list of values found for that keyword
    */
  private def findKeywordInText(keyword: Keyword, tag: String, text: String): (Keyword, List[String]) = {
    //TODO convert tags, the client could send Noun and we have to translate to NN, or force client to send the correct way
    val (splittedWords, tags) = tagText(text)

    val arrLength = splittedWords.length

    //Iterate through the words (that have been slipped by whitespaces)
    //if we find a word that equal to the passed keyword
    // then search from that point forward for a word whose POS tag matches the one passed by arguments
    val valuesList: List[String] = (for (i <- splittedWords.indices if splittedWords(i).toLowerCase == keyword.toLowerCase) yield {
      if (i < arrLength) {
        val wordList = for (j <- i + 1 until arrLength if tags(j) == tag) yield splittedWords(j)
        if (wordList.nonEmpty) wordList.head else ""
      } else {
        "" //In case the keyword found is the last word in the text we're not going to find a value for it
      }
    }).toList

    val badStr = " .,;:"
    val cleanList: List[String] = valuesList.filter(_ != "").map(s => strClean(s, badStr))

    (keyword, cleanList)
  }

  /**
    * Method that initializes the regular expressions from the given language identified in the pdf
    *
    * @param text - The text extracted from the pdf document
    * @return - A Map containing all RegEx defined for each keyword
    */
  private def importRegExFile(text: String): Map[Keyword, Regex] = {
    val lang = detectLanguage(text) match {
      case "por" => "por"
      case _ => "eng"
    }
    val fileLines = Source.fromFile("./reader/resources/regex/" + lang + ".txt").getLines //Should we try and catch a file not found exception here ?
    fileLines.map(l => {
      val splitLine = l.split(";")
      splitLine(0) -> splitLine(1).r
    }).toMap
  }

  /**
    * Method that takes 2 input strings, one to clean up and one with the possible characters to be removed.
    * This method removes all the unwanted characters in the beginning and end of a string
    *
    * @param s   - String to clean up
    * @param bad - String with the characters to be rejected
    * @return - Clean string
    */
  private def strClean(s: String, bad: String): String = {

    @tailrec def start(n: Int): String =
      if (n == s.length) ""
      else if (bad.indexOf(s.charAt(n)) < 0) end(n, s.length)
      else start(1 + n)

    @tailrec def end(a: Int, n: Int): String =
      if (n <= a) s.substring(a, n)
      else if (bad.indexOf(s.charAt(n - 1)) < 0) s.substring(a, n)
      else end(a, n - 1)

    start(0)
  }

  /**
    * Method that deletes all files from the image folder
    */
  private def cleanImageDir(){
    val dir = new File("./target/images")
    val files = dir.listFiles.filter(_.isFile).toList
    files.foreach(_.delete)
  }
}