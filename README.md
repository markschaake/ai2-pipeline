AI2 Pipeline Framework
=========================


Design Goals
============

A common pain point in analysis-driven software development is the
management of data sets and experimental results.  In the absence of an
organizing framework, the tendency is for individuals to write
standalone executables that read and parse data from disk, transform the
data, and write back to disk.  A pipeline consisting of such steps is
difficult to manage by hand for the following reasons:

1.  No validation checks on the compatibility of data written as the
    output of one step with the input needed for a following step
2.  No record in the code of the upstream steps needed to produce a
    particular intermediate step
3.  Code is difficult to reuse and expensive to migrate to other (e.g.
    cloud-based) storage systems.

These problems can be alleviated by appropriately chosen and enforced
conventions, but even better is a framework that solves them for
developers in a consistent way.  Such a framework should:

1.  Be a standalone library (i.e. not a hosted solution)
2.  Be as convenient to use as writing typical standalone executables
3.  Have the ability to express an end-to-end pipeline in compiled code
    (i.e. not specified in config files)
4.  Enforce consistency between connected outputs and inputs
5.  Cache datasets that are re-used by multiple consumers
6.  Support streaming calculations on out-of-RAM datasets
7.  Support easy swapping of storage implementations

Pipeline Abstractions
=====================

There are three central abstractions in the data pipeline framework.

Data Transformation
-------------------

The most essential abstraction is the logic transforming one data
structure into another.  This is represented in the framework by the
Producer[T] trait.  A Producer[T] provides a lazily-computed value of
type T returned by the get method.  Only the output type is
parameterized, because different producers may require different inputs.
 An implementation may specify a default for whether the result is
cached in memory, but this can be overridden when using is in a
pipeline.  

Data Storage
------------

A data structure saved in persistent storage is represented by the
Artifact trait.  An artifact may represent a flat file, a directory, a
zip archive, or an S3 blob.  Future implementations could represent an
HDFS dataset or other mechanisms.  If a data structure has been saved in
an Artifact, then it will be read from that Artifact when needed, rather
than recomputing the value from the underlying Producer.  In this way,
expensive calculations are transparently cached to disk when necessary.
 The author of a pipeline specifies which data structures should be
persisted and select the desired persistence mechanism and path names.

Data Serialization
------------------

Serialization of a data structure of type T into an artifact of type
A is represented by the ArtifactIo[T,A] trait.  Because common cases,
such as serialization to JSON and delimited-columns, are implemented by the framework,
many pipelines can be implemented end-to-end without any code that
performs I/O.  Different serialization formats, i.e. different
implementations of ArtifactIo, can be specified when the pipeline is
constructed, while the Artifact instance specifies the physical location
where the data will be stored.

Example Pipeline 
================

The complete code for this example can be found in
src/test/scala/org/allenai/pipeline/SamplePipeline.scala

The Basic Pipeline
------------------

As an example let us take the familiar case of training and measuring a
classification model.  Our pipeline consists of the following steps:

1.  Read a collection of labels from TSV
2.  Read a collection of feature vectors from TSV
3.  Join the features with the labels and split into train/test sets
4.  Train a classifier
5.  Measure the classifier accuracy on the test set

First, we specify the persistence implementation to use for I/O.  These
have methods for providing artifact representing a flat file or
structured dataset (zip file or directory)

    import IoHelpers._
    val input = new FileSystem(inputDir)
    val output = new FileSystem(outputDir)

We read the labels using the framework’s built-in delimited-column parsing methods:
 

    val labelData: Producer[Iterable[Boolean]]
        = Read.Collection.fromText[Boolean](input.flatArtifact(labelFile))

Similarly for features

    val featureData: Producer[Iterable[Array[Double]]]
    = Read.arrayCollection.fromText[Double](input.flatArtifact(featureFile))

Step 3 takes steps 1 and 2 as input, as well as a parameter determining
the relative size of the test set.  It produces a pair of datasets with
both features and labels

  

    class JoinAndSplitData(features: Producer[Features]
                           labels: Producer[Labels],
                           testSizeRatio: Double)
     extends Producer[(Iterable[(Boolean, Array[Double])], Iterable[(Boolean, Array[Double])])]

Step 4 takes a Iterable[(Boolean, Array[Double])] producer as input and produces a
TrainedModel object

    class TrainModel(trainingData: Producer[Iterable[(Boolean, Array[Double])]]) 
        extends Producer[TrainedModel]

Step 5 takes producers of Iterable[(Boolean, Array[Double])] and TrainedModel and
produces a P/R measurement

    // Threshold, precision, recall.
    type PRMeasurement = Iterable[(Double, Double, Double)]

    class MeasureModel(model: Producer[TrainedModel], testData: Producer[Iterable[(Boolean, Array[Double])]])
          extends Producer[PrecisionRecallMeasurement]

The pipeline is defined by simply chaining the producers together

    val Producer2(trainData: Producer[Iterable[(Boolean, Array[Double])]],
                  testData: Producer[Iterable[(Boolean, Array[Double])]])
                 = new JoinAndSplitData(featureData, labelData, 0.2)
    val model: Producer[TrainedModel] = new TrainModel(trainData)
    val measure: Producer[Iterable[(Double, Double, Double)]] = new MeasureModel(model, testData)

Note the use of Producer2.unapply, which converts a Producer of a Tuple
to a Tuple of Producers. To run the pipeline, we invoke the get method
of the final step

    val result = measure.get

Persisting the Output 
---------------------

At this point, the result of the calculation has been created in memory,
but is not being persisted.  We would like to persist not only the final
Iterable[(Double, Double, Double)] object, but the intermediate TrainedModel instance.  The
earlier import of IoHelpers adds PersistedXXX methods to
Producer instances that persist their data before passing it on to
downstream consumers.  To use them, we must also provide an implicit
persistence implementation.

    implicit val location = output
    val model: Producer[TrainedModel]
            = Persist.Singleton.asJson(new TrainModel(trainData), "model.json")
    val measure: Producer[Iterable[(Double, Double, Double)]]
            = Persist.Collection.asText(new MeasureModel(model, testData), "PR.txt")

We have opted not to persist the Iterable[(Boolean, Array[Double])] data, but we could
do so in the same way.  Note that we have written no code that performs
I/O directly.  Instead, we need to define the transformation between our
data objects and JSON or column format

    import spray.json.DefaultJsonProtocol._
    implicit val modelFormat = jsonFormat1(TrainedModel)
    implicit val prMeasurementFormat 
      = tuple3ColumnFormat[Double, Double, Double](',')

Furthermore, all that is required to have our pipeline persist data to
S3 is to set the persistence implementation differently

    val s3Config = S3Config("ai2-pipeline-sample")
    implicit val location = new S3(s3Config)

An important point is that when a Producer is persisted, its serialized
output acts as a cached result.  That is, if the pipeline is rerun, even
in a subsequent process, and that Producer’s output it found in the
expected location, the result will be deserialized from the store rather
than re-computed from its inputs.  In the "Tracking Overlapping Pipelines" section we 
will see how this is used for pipelines that have some shared computations.

Out-of-Core Datasets 
--------------------

Instead of reading feature data from disk, suppose now that we compute
it on the fly by processing XML documents from a source directory,
producing a feature vector for each document.  Suppose further that the
entire set of documents is too large to fit in memory.  In this case, we
must implement a different Producer instance that will process
an input stream of ParsedDocument objects

    class FeaturizeDocuments(documents:Producer[Iterator[ParsedDocument]]) extends Producer[Features]

Because this class has an Iterator as its input type, it will not hold
the raw document dataset in memory.  To produce the Iterator of parsed
documents, we must implement an ArtifactIo class.  Recall that an
ArtifactIo class is parameterized with the output type (in this case,
Iterator[ParsedDocument]) and the artifact type.  We will define ours in
terms of the more general StructuredArtifact rather than the narrow
DirectoryArtifact. This will allow us to read from Zip archives on the
local file system or in S3 with the same implementation class.  The
ArtifactIo interface includes both read and write operations, to ensure
consistency of serialization/deserialization code throughout the
pipeline.  For this use case, however, we only need implement the read
operation.

    object ParseDocumentsFromXML
               extends ArtifactIo[Iterator[ParsedDocument], StructuredArtifact] {
      def read(a: StructuredArtifact): Iterator[ParsedDocument] = {
        for ((entry, is) \<- a.reader.readAll) yield parse(is)
      }
      def parse(is: InputStream): ParsedDocument = ???
      // Writing back to XML not supported
      def write(data: Iterator[ParsedDocument], artifact: StructuredArtifact) = ???
    }

Now we can use our document featurizer as a drop-in replacement for the
feature data we had originally read from TSV

    val docDir = new File("raw-xml")
    val docs = readFromArtifact(ParseDocumentsFromXML,
                                          new DirectoryArtifact(docDir))
    val docFeatures = new FeaturizeDocuments(docs) 
    // use in place of featureData above

Out-of-Process Computation
--------------------------

Most data transformations are assumed to be implemented in Scala code.
 However, it is sometimes necessary for components in a pipeline to be
implemented outside the JVM.  For example, our TrainModel class might
invoke a Python trainer via a shell command.  The only appropriate input
type for such Producer classes is an Artifact, since the JVM will only
communicate with outside processes via some persistent store. In the
constructor, we also supply an ArtifactIo instance to deserialize the
output of the outside process.  A Producer that does training via a
shell command is

    class TrainModelPython(data: Producer[FileArtifact],
                           io: ArtifactIo[TrainedModel, FileArtifact])
          extends Producer[TrainedModel] {
      def create: TrainedModel = {
        val outputFile = File.createTempFile("model", ".json")
        import sys.process.\_
        import scala.language.postfixOps
        val stdout = s"train.py -input ${data.get.file} -output $outputFile" !!
        val model = io.read(new FileArtifact(outputFile))
        model
      }
    }

Any upstream Producer that persists its results via the standard mechanism can be converted to a 
Producer of the appropriate Artifact type, so that a a downstream out-of-JVM step can consume it.
 Otherwise, the structure of the pipeline is unchanged.

    val labelData: Producer[Labels]
         = Read.Collection.fromText[Boolean](input.flatArtifact(labelFile))
    
    val Producer2(trainData: Producer[Iterable[(Boolean, Array[Double])]],
                  testData: Producer[Iterable[(Boolean, Array[Double])]])
         = new JoinAndSplitData(docFeatures, labelData, 0.2)
    
    val trainingDataFile = Persist.Collection.asText(trainData, "trainData.tsv").asArtifact
    val model = Persist.Singleton.asJson(new TrainModelPython(trainingDataFile,
          SingletonIo.json[TrainedModel]), "model.json")
    val measure: Producer[PRMeasurement] 
        = Persist.Collection.asText(new MeasureModel(model, testData), "PR.txt")

Tracking Overlapping Pipelines
------------------------------
The source code for this example is found in src/test/scala/org/allenai/pipeline/SampleExperiment.scala

For most projects, we would expect to run many variants of a core pipeline, 
specifying different parameters, different featurizations, etc., but all producing the same 
kind of final output, for example a trained model and measurement metrics. In the previous 
sections, the location of stored output was specified explicitly.  It is possible
to have multiple different pipelines storing data into the same directory, 
but it becomes difficult to make sure that the names of the output files do not conflict.  
Alternatively, one could specify a separate output directory for each variant, 
but then the variants cannot share intermediate calculations they may have in common.  To help 
with the management of many different but closely related pipelines, 
the framework provides the PipelineRunner class and the PipelineRunnerSupport interface.

The PipelineRunner automatically determines the location to which Producers will persist their 
results.  If a PipelineRunner instance is implicitly in scope, no file name needs to be specified
when persisting a Producer:

    implicit val runner = PipelineRunner.writeToDirectory(outputDir)
    val trainDataPersisted = Persist.Collection.asText(trainData)
    val model = Persist.Singleton.asJson(new TrainModel(trainDataPersisted))

If a second pipeline is defined using a PipelineRunner that saves to the same directory, 
even in a separate project and run on different days, the second pipeline will look for 
persisted data in the same location, and it will re-use any calculations that are shared 
with a previous run of a different pipeline.  In this example, the second pipeline produces its 
training data in the same way as the first.  When the second pipeline is run, 
it will read the training feature data from the persistent store, rather than duplicating the 
(typically expensive) feature calculation.  By contrast, the second pipeline uses different 
logic to train the model, so the output of the model training will be stored in a different location.

    implicit val runner = PipelineRunner.writeToDirectory(outputDir)
    val trainDataPersisted = Persist.Collection.asText(trainData)
    val model = Persist.Singleton.asJson(new TrainModelPython(trainDataPersisted.asArtifact,
      SingletonIo.json[TrainedModel]))

The file name chosen by PipelineRunner is based on a hash of the parameters, inputs, 
and code version of the Producer instance being persisted.  These are provided by the 
PipelineRunnerSupport class and represented by an instance of the Signature class.  There are 
various factory convenience methods for building Signature objects.  If the Producer instance is 
a case class, one can declare
 
    override def signature = Signature.fromObject(this)
    
Alternatively, one can declare the names of the publicly-accessible fields that contain the 
parameters and inputs:

    override def signature = Signature.fromFields(this, "features", "labels", "testSizeRatio")

The code version is specified by an instance of the CodeInfo class.  This is most conveniently 
done by mixing in the Ai2CodeInfo trait, which uses information created by the sbt release plugin.  
The PipelineRunner assumes by default that the logic of a particular Producer class does not change
between releases.  In case the logic does differ from a previous release, 
the updateVersionHistory field can be updated so that it contains a history of all the release 
ids in which the logic of the class differs.

The second purpose of the PipelineRunner is to produce a summary of a pipeline run in the form of
 an HTML page. The page will be written to the same directory as the output data and contains a
 visualization of the pipeline workflow with URL links to where output data is  stored. The page is
 produced automatically when using the PipelineRunner.run method instead of Producer.get
 
    runner.run(measure)

Using the PipelineRunner class writing to S3 is a convenient way of managing projects with 
many different contributors.  Users running experiments can re-use data, even from calculations 
run on different machines.  The HTML pages stored into S3 are visible in a browser and serve as
a record of results of the group as a whole.

Summary
=======

The sample pipeline illustrates many of the benefits of the framework
for managing a pipeline.  Here is a summary:

-   Guaranteed input/output location and format compatibility.  The
    persistence path of input/output data is specified in a single
    place.  There is no need to match a string specifying an upstream
    step’s output with another string specifying a downstream step’s
    input.  Similarly, it is impossible for an upstream step to write
    data in a format different from the format expected by a downstream
    step.  For example, if data is written using comma delimiters,
    nothing will ever attempt to read it using tab delimiters.
-   Guaranteed input/output type compatibility.  The interfaces between
    pipeline steps are defined in terms of Scala classes, and are
    therefore subject to compile-time type checking.  It is impossible,
    for example, for the training to be run on a data set that uses
    Booleans for labels, while the measurement is done on a data set
    that uses 0/1 for labels.  This can easily happen if the pipeline
    steps are defined in terms of file paths.  Using the framework, such
    a pipeline would simply not compile.
-   Easy swapping of persistence implementations. A pipeline can be
    developed and fully debugged using local filesystem persistence and
    then trivially and transparently migrated to use S3 for production.
     It is highly unlikely for this migration to introduce bugs because
    the persistence implementation is hidden from the code implementing
    the pipeline steps.
-   Highly modular and reusable code.  Data transformation logic is
    fully isolated from having to know where its data comes from or is
    bound for.  Only the top-level code that defines the pipeline has
    control over which outputs are cached in memory, which are persisted
    in storage, the location where they are stored, and the format used
    to store them. A Producer instance is lightweight and easily used
    even in code that does not otherwise interact with the framework.
     Similarly, ArtifactIo instances are lightweight, self-contained,
    and reusable outside the framework.  While it is certainly possible
    to write reusable code without the framework, using the framework
    makes it impossible not to write modular code.
-   Distinct users running different (but related) pipelines can gain efficiency by sharing data 
    between pipelines and are automatically provided with a record of past pipeline runs and their 
    outputs.   


















