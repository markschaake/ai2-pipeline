package org.allenai.pipeline

import java.io.File
import java.lang.reflect.Field

import org.allenai.common.testkit.UnitSpec
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterAll

import scala.util.Random

/** Created by rodneykinney on 8/19/14.
  */
class TestProducer extends UnitSpec with BeforeAndAfterAll {

  import scala.language.reflectiveCalls

  val rand = new Random

  import org.allenai.pipeline.IOHelpers._

  val outputDir = new File("test-output")

  implicit val output = new FileSystem(outputDir)

  val randomNumbers = new Producer[Iterable[Double]] with CachingDisabled {
    def create = {
      for (i <- (0 until 20)) yield rand.nextDouble
    }
  }

  val cachedRandomNumbers = new Producer[Iterable[Double]] with CachingEnabled {
    def create = {
      for (i <- (0 until 20)) yield rand.nextDouble
    }
  }

  "Uncached random numbers" should "regenerate on each invocation" in {
    randomNumbers.get should not equal (randomNumbers.get)

    val cached = randomNumbers.enableCaching

    cached.get should equal(cached.get)
  }

  "PersistedProducer" should "read from file if exists" in {
    val pStep = randomNumbers.saveAsTsv("savedNumbers.txt")

    pStep.get should equal(pStep.get)

    val otherStep = cachedRandomNumbers.saveAsTsv("savedNumbers.txt")
    otherStep.get should equal(pStep.get)
  }

  "CachedProducer" should "use cached value" in {
    cachedRandomNumbers.get should equal(cachedRandomNumbers.get)

    val uncached = cachedRandomNumbers.disableCaching

    uncached.get should not equal (uncached.get)
  }

  "PersistentCachedProducer" should "read from file if exists" in {
    val pStep = cachedRandomNumbers.saveAsTsv("savedCachedNumbers.txt")

    pStep.get should equal(pStep.get)

    val otherStep = randomNumbers.saveAsTsv("savedCachedNumbers.txt")
    otherStep.get should equal(pStep.get)
  }

  val randomIterator = new Producer[Iterator[Double]] {
    def create = {
      for (i <- (0 until 20).iterator) yield rand.nextDouble
    }
  }

  "Random iterator" should "never cache" in {
    randomIterator.get.toList should not equal (randomIterator.get.toList)
  }

  "Persisted iterator" should "re-use value" in {
    val persisted = randomIterator.saveAsTsv("randomIterator.txt")
    persisted.get.toList should equal(persisted.get.toList)
  }

  "Persisted iterator" should "read from file if exists" in {
    val persisted = randomIterator.enableCaching.saveAsTsv("savedCachedIterator.txt")
    val otherStep = randomIterator.disableCaching.saveAsTsv("savedCachedIterator.txt")
    otherStep.get.toList should equal(persisted.get.toList)
  }

  "Auto-assigned paths" should "be reusable" in {
    import spray.json.DefaultJsonProtocol._
    implicit val pathFinder = new GeneratedPath(new FileSystem(outputDir))

    class RNG(val seed: Int, val length: Int) extends Producer[Iterable[Double]] with AutoSignature {
      val fields = Set("seed", "length")
      private val rand = new Random(seed)

      def create = (0 until length).map(i => rand.nextDouble)
    }

    val rng1 = new RNG(42, 100)
    val rng2 = new RNG(117, 100)

    rng1.signature should equal(rng2.signature)
  }

  override def beforeAll: Unit = {
    require((outputDir.exists && outputDir.isDirectory) || outputDir.mkdirs, s"Unable to create test output directory $outputDir")
  }

  override def afterAll: Unit = {
    FileUtils.deleteDirectory(outputDir)
  }
}
