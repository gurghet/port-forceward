//> using dep "dev.zio::zio-cli:0.5.0"
//> using dep "dev.zio::zio-process:0.7.2"
//> using dep "org.apache.commons:commons-text:1.10.0"

import zio._
import zio.Console.printLine
import zio.cli.HelpDoc.Span.text
import zio.cli._
import zio.Scope
import zio.ZIOAppArgs
import zio.process.{Command => ProcCommand}
import zio.stream.ZPipeline
import zio.cli.ValidationErrorType.InvalidArgument

object PortForceward extends ZIOCliDefault {
  import java.nio.file.Path

  val resourceTypeOpt =
    Options
      .text("resource-type")
      .alias("t", "type")
      .withDefault("pod") ?? "The resource type to forward"

  val resourceNameOpt =
    Options
      .text("resource-name")
      .alias("r", "res", "resource") ?? "The resource name to forward"

  val portOpt =
    Options.text("ports").alias("p", "port").mapOrFail {
      case s if s.matches("\\d+:\\d+") => Right(s)
      case _ =>
        Left(
          ValidationError(
            InvalidArgument,
            HelpDoc.p("Ports must be in the format 'local:remote'")
          )
        )
    } ?? "The ports to forward"

  val kubeConfigOpt =
    Options.text("kubeconfig").optional ?? "The path to the kubeconfig file"

  val forceward =
    Command(
      "forceward",
      resourceTypeOpt ++ resourceNameOpt ++ portOpt ++ kubeConfigOpt,
      Args.none
    )

  val helpDoc = HelpDoc.p("A forceful port forwarder for kubectl")

  def portForwardResource(
      rscType: String,
      rscName: String,
      ports: String,
      kubeconfig: Option[String]
  ) =
    val args = List(
      "port-forward",
      rscType + "/" + rscName,
      ports
    ) ++ kubeconfig.map(k => List("--kubeconfig", k)).getOrElse(List.empty)
    ProcCommand("kubectl", args: _*)

  def tryToPortForwardAndGiveUpAfter5Seconds(
      rscType: String,
      rscName: String,
      ports: String,
      kubeconfig: Option[String]
  ) =
    for {
      outputContainer <- Queue.unbounded[String]
      connected <- Ref.make(Option.empty[Boolean])
      handle <- portForwardResource(rscType, rscName, ports, kubeconfig).run
      f <- handle.stdout.linesStream
        .tap(Console.printLine(_))
        .foreach(line => outputContainer.offer(line))
        .fork
      e <- handle.stderr.linesStream
        .tap(Console.printLine(_))
        .foreach(line => outputContainer.offer(line))
        .fork
      senseConnectionOrFailure = outputContainer.take.flatMap { line =>
        if line.contains("Forwarding from") then
          ZIO.succeed(handle) <*
            connected.set(Some(true))
        else
          ZIO.fail(new Exception("Port forwarding failed")) <*
            connected.set(Some(false))
      }
      _ <- ZIO.sleep(5.seconds).race(senseConnectionOrFailure)
      killItWithFire =
        handle.killTreeForcibly *> e.interrupt *> f.interrupt *> ZIO.fail(
          new Exception("Port forwarding failed")
        ) <* ZIO.sleep(1.second) // make sure the process is dead
      result <- connected.get.flatMap {
        case Some(true)  => ZIO.succeed(handle)
        case Some(false) => killItWithFire
        case None        => killItWithFire
      }
    } yield result

  def getResourceTypesCommand(kubeconfigOpt: Option[String]) =
    val args = List(
      "api-resources",
      "--no-headers"
    ) ++ kubeconfigOpt.map(k => List("--kubeconfig", k)).getOrElse(List.empty)
    ProcCommand("kubectl", args: _*)

  def verifyResourceTypeExists(resType: String, kubeconfigOpt: Option[String]) =
    for {
      handle <- getResourceTypesCommand(kubeconfigOpt).run
      _ <- handle.stderr.linesStream.foreach(Console.printLine(_)).fork
      chunk <- handle.stdout.linesStream.map(_.split("\\s+")).runCollect
      resTypeChunks = chunk.flatMap(arr => Chunk.fromArray(arr))
      modifiedChunks = resTypeChunks.flatMap(resTypeMaybePlural =>
        if resTypeMaybePlural.endsWith("s") then
          Chunk(resTypeMaybePlural, resTypeMaybePlural.dropRight(1))
        else Chunk(resTypeMaybePlural)
      )
      result = modifiedChunks.contains(resType)
    } yield result

  def getResourceListCommand(
      resourceType: String,
      kubeconfigOpt: Option[String]
  ) =
    val args = List(
      "get",
      resourceType,
      "--no-headers",
      "-o",
      """jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'"""
    ) ++ kubeconfigOpt.map(k => List("--kubeconfig", k)).getOrElse(List.empty)
    ProcCommand("kubectl", args: _*)

  def findSimilarlyNamedResource(
      res: String,
      list: Chunk[String]
  ): Option[String] =
    list
      .map { r =>
        val distance = org.apache.commons.text.similarity.LevenshteinDistance
          .getDefaultInstance()
          .apply(r, res)
        (r, distance)
      }
      .sortBy(_._2)
      .headOption
      .map(_._1)

  def findResourceOrSuggestSimilarlyNamed(
      resourceType: String,
      resourceName: String,
      kubeconfigOpt: Option[String]
  ) =
    def resVerification(resourceType: String) = for {
      exists <- verifyResourceTypeExists(resourceType, kubeconfigOpt)
      _ <-
        if exists then ZIO.unit
        else
          printLine(
            "Resource type " + resourceType + " does not exist. Please check your spelling."
          ) *> ZIO.fail(new Exception("Resource type does not exist"))
    } yield exists
    for {
      _ <- resVerification(resourceType)
      handle <- getResourceListCommand(resourceType, kubeconfigOpt).run
      resources <- handle.stdout.linesStream.runCollect
      _ <-
        if resources.contains(resourceName) then ZIO.unit
        else
          val suggestionString =
            findSimilarlyNamedResource(resourceName, resources)
              .map(s => s" Did you mean '$s'?")
              .getOrElse("")
          printLine(
            s"Resource $resourceType/$resourceName does not exist." + suggestionString
          ) *> ZIO.fail(new Exception("Resource does not exist"))
    } yield (resourceType, resourceName)

  def cliApp = CliApp.make(
    name = "port-forceward",
    version = "0.1.0",
    summary = text("A forceful port forwarder for kubectl"),
    command = forceward
  ) { case (rscTypeIn, rscNameIn, ports, kubeconfig) =>
    for {
      _ <- printLine(
        s"User input: resource-type:'$rscTypeIn' resource-name:'$rscNameIn' ports:'$ports' kubeconfig:'$kubeconfig'"
      )
      _ <- findResourceOrSuggestSimilarlyNamed(rscTypeIn, rscNameIn, kubeconfig)
      n <- forcewardWithTypeAndName(rscTypeIn, rscNameIn, ports, kubeconfig)
    } yield n
  }

  def forcewardWithTypeAndName(
      rscType: String,
      rscName: String,
      ports: String,
      kubeconfig: Option[String]
  ) = for {
    _ <- printLine("Starting...")
    _ <- printLine(s"Resource type: '$rscType'")
    _ <- printLine(s"Resource name: '$rscName'")
    _ <- printLine("Local port: " + ports.split(":")(0))
    _ <- printLine("Remote port: " + ports.split(":")(1))
    _ <- printLine("Kubeconfig: " + kubeconfig)
    runningProcess <- Ref.make(Option.empty[process.Process])
    trialLoop = for {
      trialCounter <- Ref.make(0)
      proc <- tryToPortForwardAndGiveUpAfter5Seconds(
        rscType,
        rscName,
        ports,
        kubeconfig
      )
        .tapError { _ =>
          for {
            count <- trialCounter.get
            _ <- printLine("Retrying... (" + count + ")")
            _ <- trialCounter.update(_ + 1)
          } yield ()
        }
        .retryN(100)
      _ <- runningProcess.set(Some(proc))
      _ <- printLine(
        s"Congratulations! You have successfully port-forwarded $rscType/$rscName to your local machine."
      )
      brokenPipeSentinel <- Promise.make[Nothing, Unit]
      _ <- proc.stderr.linesStream.merge(proc.stdout.linesStream)
        .filter(t => t.toLowerCase().contains("error"))
        .foreach(_ => brokenPipeSentinel.succeed(()))
        .fork
      _ <- brokenPipeSentinel.await
      _ <- proc.killTreeForcibly
      _ <- runningProcess.set(None)
      _ <- printLine("Connection lost! Retrying...")
    } yield ()
    pressToStop = for {
      _ <- printLine("Press enter to stop...")
      _ <- ZIO.attempt(scala.io.StdIn.readLine())
    } yield ()
    _ <- trialLoop.forever.disconnect.race(pressToStop)
    _ <- printLine("Stopping...")
    _ <- runningProcess.get.flatMap {
      case Some(proc) => proc.killTreeForcibly
      case None       => ZIO.unit
    }
    _ <- printLine("Stopped!")
  } yield 0
}

val runCliZio = ZIO.scoped {
  PortForceward.cliApp
    .run(args.toList)
    .provideSomeLayer[Scope](ZIOAppArgs.empty)
}

val runtime = Runtime.default
Unsafe.unsafe { implicit unsafe =>
  runtime.unsafe.run(
    runCliZio
  )
}
