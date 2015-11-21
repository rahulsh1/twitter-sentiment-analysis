import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.twitter.TwitterUtils
import org.apache.spark.streaming.{Seconds, StreamingContext}

/**
 * Performs Twitter sentiment analysis.
 * Creates a tuple of (positive, negative, neutral) sentiment and combines them in a window of 10 seconds.
 */
object TwitterSentimentAnalysis {

  def main(args: Array[String]) {
    if (args.length < 4) {
      System.err.println("Usage: TwitterSentimentAnalysis <consumer key> <consumer secret> " +
        "<access token> <access token secret> [<filters>]")
      System.exit(1)
    }

    val Array(consumerKey, consumerSecret, accessToken, accessTokenSecret) = args.take(4)
    val filters = args.takeRight(args.length - 4)

    // Set the system properties so that Twitter4j library used by twitter stream
    // can use them to generate OAuth credentials
    System.setProperty("twitter4j.oauth.consumerKey", consumerKey)
    System.setProperty("twitter4j.oauth.consumerSecret", consumerSecret)
    System.setProperty("twitter4j.oauth.accessToken", accessToken)
    System.setProperty("twitter4j.oauth.accessTokenSecret", accessTokenSecret)

    // Do the Analysis
    doAnalysis(filters)
  }

  def doAnalysis(filters: Array[String]): Unit = {
    @transient val sparkConf = new SparkConf().setAppName("TwitterSentimentAnalysis").setMaster("local[*]")
    @transient val streamingCtxt = new StreamingContext(sparkConf, Seconds(5))

    // Stop Words
    val stopFile = streamingCtxt.sparkContext.textFile("src/main/resources/stop-words.txt")
    val stopWordsSet = stopFile.collect().toSet

    // Positive Words
    val posFile = streamingCtxt.sparkContext.textFile("src/main/resources/pos-words.txt")
    val posWordsSet = posFile.collect().toSet

    // Negative Words
    val negFile = streamingCtxt.sparkContext.textFile("src/main/resources/neg-words.txt")
    val negWordsSet = negFile.collect().toSet

    // Create stream from Twitter feeds.
    val twRecvDStream = TwitterUtils.createStream(streamingCtxt, None, filters)

    // Filter so we have only english language tweets with no punctuation chars and valid contents
    // Filter empty tweets as well as some only contain ??? or punctuation chars.
    val filteredTweetDStream = twRecvDStream.filter(s => "en".equals(s.getUser.getLang))
      .map(s => s.getText.replaceAll("[^a-zA-Z\\s]", "").trim)
      .filter(s => s.length > 0)

    // Remove words from the tweet that contain stop words.
    val cleanTweetDStream = filteredTweetDStream.map(twt => {
      val splitWords = twt.split(" ")
      var result = ""
      for (w <- splitWords) {
        if (!stopWordsSet.contains(w)) {
          result += w + " "
        }
      }
      result
    })

    // Returns the count of words in a given set.
    val computeCount = (words: Array[String], set: Set[String]) => {
      var result = 0
      for (w <- words) {
        if (set.contains(w)) {
          result += 1
        }
      }
      result
    }

    // Compute Score - assign 1 for positive, negative or neutral sentiment
    val computeScoreFunction = (inputRDD: RDD[(String)]) => {
      inputRDD.map(s => {
        val splitWords = s.split(" ")
        val posWordCount = computeCount(splitWords, posWordsSet)
        val negWordCount = computeCount(splitWords, negWordsSet)
        val score = posWordCount - negWordCount
        if (score > 0) {
          (s, 1, 0, 0)
        } else if (score < 0) {
          (s, 0, 1, 0)
        } else {
          (s, 0, 0, 1)
        }
      })
    }

    val twScoreDStream = cleanTweetDStream.transform(computeScoreFunction)
    // Print the Tweet along with scores
    twScoreDStream.foreachRDD(rdd => {
      val records = rdd.take(10)
      records.foreach(v => {
        println("Tweet:" + v._1 + "\t Pos:" + v._2 + "\t Neg:" + v._3 + "\t Neutral:" + v._4)
      })
    })

    // Reduce function to combine all scores for single RDD
    val myReduceFunc = (v1: (Int, Int, Int), v2: (Int, Int, Int)) => {
      (v1._1 + v2._1, v1._2 + v2._2, v1._3 + v2._3)
    }

    // Accumulate # of positive, negative and neutral sentiment counts and print out them every 10 seconds
    // and 10 second sliding window
    val last10SecSentiment = twScoreDStream.map(v => (v._2, v._3, v._4))
      .reduceByWindow(myReduceFunc, Seconds(10), Seconds(10))

    // Print tweet sentiment in last 10 sec
    last10SecSentiment.foreachRDD(rdd => {
      val record = rdd.take(1)
      println("\nTwitter Sentiment in last 10 seconds")
      record.foreach { case (pos, neg, neut) => println("Positive: " + pos + " Negative:" + neg + " Neutral:" + neut) }
    })

    streamingCtxt.start()
    streamingCtxt.awaitTermination()
  }
}
