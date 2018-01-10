package bloop.engine

import java.io.{
  ByteArrayInputStream,
  ByteArrayOutputStream,
  PipedInputStream,
  PipedOutputStream,
  PrintStream,
  PrintWriter
}
import java.util.UUID

import bloop.cli.{CliOptions, Commands}
import bloop.logging.BloopLogger
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{InitializeBuildParams, InitializedBuildParams}
import org.junit.Test
import org.junit.experimental.categories.Category
import guru.nidi.graphviz.parse.Parser
import org.langmeta.lsp.LanguageClient

import scala.concurrent.Await

@Category(Array(classOf[bloop.FastTests]))
class InterpreterSpec {
  private final val initialState = ProjectHelpers.loadTestProject("sbt")
  import InterpreterSpec.changeOut

  @Test def ShowDotGraphOfSbtProjects(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action = Run(Commands.Projects(dotGraph = true, cliOptions))
    Interpreter.execute(action, state)

    val dotGraph = outStream.toString("UTF-8")
    val graph = Parser.read(dotGraph)
    assert(graph.isDirected, "Dot graph for sbt is not directed")
    ()
  }

  @Test def TestBSP(): Unit = {
    val state0 = initialState
    val testOut = new ByteArrayOutputStream()
    val newOut = new PrintStream(testOut)

    val inputStream = new PipedInputStream()

    import ExecutionContext.bspScheduler
    val bspClient = {
      val out = new PipedOutputStream(inputStream)
      val dummyLogger = com.typesafe.scalalogging.Logger(this.getClass())
      implicit val languageServer = new LanguageClient(out, dummyLogger)
      import ch.epfl.scala.bsp.endpoints.Build._
      val params = InitializeBuildParams()
      initialize.request(params)
    }

    val loggerName = UUID.randomUUID().toString
    val newLogger = BloopLogger.at(loggerName, newOut, newOut)
    val defaultCli = CliOptions.default
    val newCommonOptions = state0.commonOptions.copy(out = newOut, in = inputStream)
    val state = state0.copy(logger = newLogger, commonOptions = newCommonOptions)
    val cliOptions = defaultCli.copy(common = newCommonOptions)

    val bspServer = monix.eval.Task {
      val action = Run(Commands.Bsp(cliOptions = cliOptions))
      val state1 = Interpreter.execute(action, state)
      println(testOut.toString)
    }

    val test = for {
      _ <- bspServer
      client <- bspClient
    } yield {
      pprint.log(client)
      pprint.log(testOut.toString())
    }

    Await.result(test.runAsync, scala.concurrent.duration.Duration("5s"))
  }

  @Test def ShowProjectsInCustomCommonOptions(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action = Run(Commands.Projects(cliOptions = cliOptions))
    Interpreter.execute(action, state)
    val output = outStream.toString("UTF-8")
    assert(output.contains("Projects loaded from"), "Loaded projects were not shown on the logger.")
  }

  @Test def ShowAbout(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action = Run(Commands.About(cliOptions = cliOptions))
    Interpreter.execute(action, state)
    val output = outStream.toString("UTF-8")
    assert(output.contains("Bloop-frontend version"))
    assert(output.contains("Zinc version"))
    assert(output.contains("Scala version"))
    assert(output.contains("maintained by"))
    assert(output.contains("Scala Center"))
  }

  @Test def SupportDynamicCoreSetup(): Unit = {
    val (state, cliOptions, outStream) = changeOut(initialState)
    val action1 = Run(Commands.Configure(cliOptions = cliOptions))
    val state1 = Interpreter.execute(action1, state)
    val output1 = outStream.toString("UTF-8")
    // Make sure that threads are set to 6.
    assert(!output1.contains("Reconfiguring the number of bloop threads to 4."))

    val action2 = Run(Commands.Configure(threads = 6, cliOptions = cliOptions))
    val state2 = Interpreter.execute(action2, state1)
    val action3 = Run(Commands.Configure(threads = 4, cliOptions = cliOptions))
    val _ = Interpreter.execute(action3, state2)
    val output23 = outStream.toString("UTF-8")

    // Make sure that threads are set to 6.
    assert(output23.contains("Reconfiguring the number of bloop threads to 6."))
    // Make sure that threads are set to 4.
    assert(output23.contains("Reconfiguring the number of bloop threads to 4."))
  }
}

object InterpreterSpec {
  def changeOut(state: State): (State, CliOptions, ByteArrayOutputStream) = {
    val inMemory = new ByteArrayOutputStream()
    val newOut = new PrintStream(inMemory)
    val loggerName = UUID.randomUUID().toString
    val newLogger = BloopLogger.at(loggerName, newOut, newOut)
    val defaultCli = CliOptions.default
    val newCommonOptions = state.commonOptions.copy(out = newOut)
    val newState = state.copy(logger = newLogger, commonOptions = newCommonOptions)
    val newCli = defaultCli.copy(common = newCommonOptions)
    (newState, newCli, inMemory)
  }
}
