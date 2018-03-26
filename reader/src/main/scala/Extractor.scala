import java.io.File
import java.text.Normalizer
import org.apache.pdfbox.text.PDFTextStripper
import scala.util.matching.Regex
import OpenNLP._
import FileHandler._
import ImageProcessing._
import scala.annotation.tailrec
import scala.io.Source
import SpellChecker._

/**
  * Singleton object that implements all the functionality regarding the extraction of information from a PDF document
  */
object Extractor {

  type Keyword = String
  type MatchedPair = List[(Keyword, List[String])]

  /**
    * Method that given a file path (maybe change to a real file) will load that PDF file and read the text from it
    *
    * @param file - File to be loaded and parsed
    * @return An Option wrapping a String containing all the text found in the document. Returns None in case of Exception
    */
  def readPDF(file: File, readImages: Boolean = true): Option[String] = {
    val pdfOption = loadPDF(file)
    pdfOption match {
      case Some(pdf) =>
        val document: PDFTextStripper = new PDFTextStripper
        val str = Normalizer.normalize(document.getText(pdf), Normalizer.Form.NFD)
          .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")

        val imgText =
          if (readImages) {
            val imageList = extractImgs(pdf).getOrElse(List())
            val imageTexts = imageList.map(img => readImageText(img).getOrElse("")).mkString

            correctText(imageTexts)
          } else ""

        pdf.close()
        cleanImageDir()
        Option(imgText + str)
      case _ => None
    }
  }

  /**
    * Method that will iterate through a list of given keywords and will try to obtain a value for that keyword
    *
    * @param text        - Text in which to look for values for the specified keywords
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    *                    use that regular expression instead of ours
    * @throws IllegalArgumentException If the keywords list is empty
    * @return List containing pairs of Keywords and a List (non-repeating) of values found for that keyword
    */
  @throws[IllegalArgumentException]
  def getAllMatchedValues(text: Option[String], keywords: List[(Keyword, POSTag.Value)], clientRegEx: Map[Keyword, Regex] = Map()): MatchedPair = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    text match {
      case Some("") => List()
      case Some(t) =>
        val knownRegEx: Map[String, Regex] = importRegExFile(t) //load correct RegEx map
        val matched:MatchedPair = keywords.map{case(key,tag) =>
          //If the client sent a custom RegEx to use on this key, use it
          if (clientRegEx.contains(key)) //&& clientRegEx != null ??
            (key, clientRegEx(key).findAllIn(t).matchData.map(_.group(1)).toList.distinct)

          //if we already know a good RegEx for this keyword, use it
          else if (knownRegEx.contains(key))
            (key, knownRegEx(key).findAllIn(t).matchData.map(_.group(1)).toList.distinct)

          else findKeywordInText(key, tag, t) //to be changed, here we need to manually search for the keywords in the text
        }
        filterNewLines(matched)
      case None => List()
      case _ => ??? //TODO throw NullPointerException??
    }
  }

  /**
    * Method that will iterate through a list of given keywords and will try to obtain only the first value it finds for a given
    * keyword, representing a single JSON object
    *
    * @param text        - Text in which to look for values for the specified keywords
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    * @throws IllegalArgumentException If the keywords list is empty
    * @return A List containing pairs of keywords with a single matched value
    */
  @throws[IllegalArgumentException]
  def getSingleMatchedValue(text: Option[String], keywords: List[(Keyword, POSTag.Value)], clientRegEx: Map[Keyword, Regex] = Map()): MatchedPair = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    text match {
      case Some(_) =>
        getAllMatchedValues(text, keywords, clientRegEx).map { case (keyword, value) =>
          value.headOption match {
            case Some(entry) => (keyword, List(entry))
            case None => (keyword, List())
          }
        }
      case _ => List()
    }
  }

  /**
    * Method that will iterate through a list of given keywords and will try to obtain a list containing
    * sub-lists that have all keywords and only one value for each of them (representing a single JSON object for each of the sub-lists)
    *
    * @param text        - Text in which to look for values for the specified keywords
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    * @throws IllegalArgumentException If the keywords list is empty
    * @return A List containing sub-lists of pairs of keywords with single matched values
    */
  @throws[IllegalArgumentException]
  def getAllObjects(text: Option[String], keywords: List[(Keyword, POSTag.Value)], clientRegEx: Map[Keyword, Regex] = Map()): List[MatchedPair] = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    text match {
      case Some("") => List()
      case Some(_) =>
        def getListSizes(matchedValues: MatchedPair): List[(Keyword, Int)] = {
          for ((key, listMatched) <- matchedValues) yield (key, listMatched.size)
        }

        val matchedValues = getAllMatchedValues(text, keywords, clientRegEx)
        val (_, mostFound) = getListSizes(matchedValues).maxBy {
          case (_, sizes: Int) => sizes
          case _ => 0
        } //Gets the size of the pair that has the most values
        val mappedValues = for (i <- 0 to mostFound; (_, listMatched) <- matchedValues) yield {
          if (listMatched.size > i) //Prevent array out of bounds exception
            List(listMatched(i))
          else List()
        }
        val keywordList = keywords.map { case (key, _) => key }
        mappedValues.zipWithIndex.map { case (key, values) => (keywordList(values % keywords.length), key) }.toList.grouped(keywords.size).toList
      case None => List()
      case _ => ??? //TODO throw nullPointerException?
    }
  }


  /**
    * Method that encapsulates the entire process of finding values for the given keywords list and converting the MatchedPair type to a JSON Object
    *
    * @param text        - Text in which to look for values for the specified keywords
    * @param keywords    - List containing all the keywords we want to find values for
    * @param clientRegEx - Optional parameter - If the client already has a predefined Regular Expression for a given key
    * @throws IllegalArgumentException If the keywords list is empty
    * @return a List of Strings representing a JSON object for each MatchedPair type
    */
  @throws[IllegalArgumentException]
  def getJSONObjects(text: Option[String], keywords: List[(Keyword, POSTag.Value)], flag: String = "empty", clientRegEx: Map[Keyword, Regex] = Map()): List[String] = {
    require(keywords.nonEmpty, "The list of keywords should not be empty")
    val objs = getAllObjects(text, keywords, clientRegEx)
    objs.map(makeJSONString(_, flag))
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
    def isAllDigits(x: String) = try {
      x.toDouble; true
    } catch {
      case _: Exception => false
    }

    lazy val quote = "\""

    val pseudoJSON = listJSON.map { case (key, matchedList) =>
      matchedList match{
        case List(_) =>
          if (matchedList.size > 1) {
            val left = s"${quote + key + quote} : "
            val right = "[" + matchedList.map(str => if (isAllDigits(str)) str + ", " else s"${quote + str + quote}, ").mkString + "]"
            (left + right).replaceAll(", ]", "]") //Remove trailing commas
          } else {
            lazy val headValue = matchedList.head
            if (isAllDigits(headValue))
              s"${quote + key + quote} : $headValue"
            else
              s"${quote + key + quote} : ${quote + headValue + quote}"
          }
        case _ =>
          flag match {
            case "empty" => s"${quote + key + quote} : ${quote + quote}"
            case "null" => s"${quote + key + quote} : null"
          }
      }
    }

    flag match {
      case "remove" => pseudoJSON.filter(_ != ()).mkString("{", ", ", "}")
      case _ => pseudoJSON.mkString("{", ", ", "}")
    }
  }

  /**
    * Method that gets all keywords and respective values from know form and returns a JSON string
    *
    * @param text - Text in which to look for key-value pairs
    * @return - A JSON String containing all the information in the text passed by arguments
    */
  def getJSONFromForm(text: Option[String]): String = {
    val textContent = text.getOrElse("")
    val formRegex = "(.+):\\s+(.+)".r
    val form = formRegex.findAllIn(textContent).matchData.map(l => (l.group(1), List(l.group(2)))).toList
    makeJSONString(form)
  }

  /**
    * Method that will remove all the new line characters from the list of values obtain from a keyword
    *
    * @param matchedValues - List of pairs of Keyword and the values obtained for that keyword
    * @return The same list as passed by parameter but with no new line characters
    */
  private def filterNewLines(matchedValues: MatchedPair): MatchedPair = {
    matchedValues.map{case(key,matchedList) =>
      val setOfValues = matchedList
      (key, setOfValues.map(_.replaceAll("[\\r\\n]", "").trim)) //remove all new line characters and trim all elements
    }
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
  private def findKeywordInText(keyword: Keyword, tag: POSTag.Value, text: String): (Keyword, List[String]) = {
    val (splittedWords, tags) = tagText(text)

    val arrLength = splittedWords.length

    //Iterate through the words (that have been slipped by whitespaces)
    //if we find a word that equal to the passed keyword
    // then search from that point forward for a word whose POS tag matches the one passed by arguments
    val valuesList: List[String] = (for (i <- splittedWords.indices if splittedWords(i).toLowerCase == keyword.toLowerCase) yield {
      if (i < arrLength) {
        val wordList = for (j <- i + 1 until arrLength if tags(j) == tag.toString) yield splittedWords(j)
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
  private def cleanImageDir() {
    val dir = new File("./target/images")
    if (dir.exists) {
      val files = dir.listFiles.filter(_.isFile).toList
      files.foreach(_.delete)
    }
  }
}