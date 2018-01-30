package bloop.bsp

import java.nio.file.Files

import bloop.cli.Commands
import bloop.io.AbsolutePath
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{
  BuildClientCapabilities,
  InitializeBuildParams,
  InitializedBuildParams
}
import ch.epfl.scala.bsp.endpoints
import monix.execution.{ExecutionModel, Scheduler}
import monix.{eval => me}
import org.langmeta.jsonrpc.{BaseProtocolMessage, Response, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}
import org.scalasbt.ipcsocket.Win32NamedPipeSocket

import scala.concurrent.duration.FiniteDuration

object BspClientTest {
  private final val slf4jLogger = com.typesafe.scalalogging.Logger("test")

  def cleanUpLastResources(cmd: Commands.ValidatedBsp) = {
    cmd match {
      case cmd: Commands.WindowsLocalBsp => ()
      // We delete the socket file created by the BSP communication
      case cmd: Commands.UnixLocalBsp =>
        if (!Files.exists(cmd.socket)) ()
        else Files.delete(cmd.socket)
      case cmd: Commands.TcpBsp => ()
    }
  }

  def setupBspCommand(cmd: Commands.ValidatedBsp,
                      cwd: AbsolutePath,
                      configDir: AbsolutePath): Commands.ValidatedBsp = {
    val common = cmd.cliOptions.common.copy(workingDirectory = cwd.syntax)
    val cliOptions = cmd.cliOptions.copy(configDir = Some(configDir.underlying), common = common)
    cmd match {
      case cmd: Commands.WindowsLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.UnixLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.TcpBsp => cmd.copy(cliOptions = cliOptions)
    }
  }

  implicit val scheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution)
  def runTest[T](cmd: Commands.ValidatedBsp, configDirectory: AbsolutePath)(
      runEndpoints: LanguageClient => me.Task[Either[Response.Error, T]]): Unit = {

    val projectName = cmd.cliOptions.common.workingPath.underlying.getFileName.toString
    val state = ProjectHelpers.loadTestProject(projectName)
    val bspServer = BspServer.run(cmd, state, scheduler)

    val bspClient = establishClientConnection(cmd).flatMap { socket =>
      val in = socket.getInputStream
      val out = socket.getOutputStream
      val services = Services.empty
      implicit val lsClient = new LanguageClient(out, slf4jLogger)
      val messages = BaseProtocolMessage.fromInputStream(in)
      messages.doOnSubscriptionCancel(() => "I am being cancelled!")
      val lsServer = new LanguageServer(messages, lsClient, services, scheduler, slf4jLogger)
      val runningClientServer = lsServer.startTask
        .doOnFinish(_ => me.Task(println("runningClientServer.finish")))
        .runAsync(scheduler)

      val cwd = configDirectory.getParent
      val initializeServer = endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = cwd.syntax,
          Some(BuildClientCapabilities(List("scala")))
        )
      )

      val requests = for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer.delayExecution(FiniteDuration(1, "s"))
        _ = endpoints.Build.initialized.notify(InitializedBuildParams())
        otherCalls <- runEndpoints(lsClient)
      } yield {
        socket.close()
      }
      requests
    }
    val s = bspServer.runAsync(scheduler)
    val c = bspClient.runAsync(scheduler)
    val app = for {
      _ <- s
      _ <- c
    } yield println("Done!")
    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    Await.result(app, FiniteDuration(4, "s"))
    println("Await!")
  }

  private def establishClientConnection(cmd: Commands.ValidatedBsp): me.Task[java.net.Socket] = {
    import org.scalasbt.ipcsocket.UnixDomainSocket
    val connectToServer = me.Task {
      println(s"Starting client connection ${System.currentTimeMillis()}")
      cmd match {
        case cmd: Commands.WindowsLocalBsp => new Win32NamedPipeSocket(cmd.pipeName)
        case cmd: Commands.UnixLocalBsp => new UnixDomainSocket(cmd.socket.toAbsolutePath.toString)
        case cmd: Commands.TcpBsp => new java.net.Socket(cmd.host, cmd.port)
      }
    }
    retryBackoff(connectToServer, 4, FiniteDuration(1, "s"))
  }

  def retryBackoff[A](source: me.Task[A],
                      maxRetries: Int,
                      firstDelay: FiniteDuration): me.Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          // Recursive call, it's OK as Monix is stack-safe
          retryBackoff(source, maxRetries - 1, firstDelay * 2)
            .delayExecution(firstDelay)
        else
          me.Task.raiseError(ex)
    }
  }
}
