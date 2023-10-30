import akka.actor.{Actor, ActorLogging, ActorSystem, DeadLetter, Props}
import akka.actor.{ActorRef, FSM}
import akka.pattern.ask
import akka.util.Timeout

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.net.{ServerSocket, Socket}
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

// Actor Messages
case class ParseCommand(data: String)
case class StartTransaction()
case class CommitTransaction(tmpStore: mutable.HashMap[String, (String, Long)], tmpDeleteSet: mutable.HashMap[String, Long])
case class RollbackTransaction()
case class Put(key: String, value: String, expTime: Long)
case class Get(key: String)
case class Delete(key: String)

sealed trait ClientState
case object Idle extends ClientState
case object TransactionStarted extends ClientState
case object TransactionCommitted extends ClientState
case object TransactionRolledBack extends ClientState
case class ClientData(tmpStore: mutable.HashMap[String, (String, Long)], tmpDeleteSet: mutable.Set[String])


class DBDeadLetterListener extends Actor with ActorLogging {
  def receive = {
    case DeadLetter(message, sender, recipient) =>
      log.error(s"DeadLetter captured: message [$message] from [$sender] to [$recipient]")
  }
}
/**
 * Actor responsible for managing data in a mutable HashMap.
 */
class DatastoreActor extends Actor with ActorLogging{
  val dataStore: mutable.HashMap[String, (String, Long)] = mutable.HashMap()

  def receive = {
    case CommitTransaction(tmpStore, tmpDeleteSet) =>
      val isCommitSuccessful = tmpStore.forall {
        case (key, (_, tmpVersion)) =>
          dataStore.get(key) match {
            case None => true  // Key doesn't exist in dataStore, allow commit
            case Some((_, dataStoreVersion)) => dataStoreVersion == tmpVersion  // Versions must match
          }
      } && tmpDeleteSet.forall {
        case (key, tmpVersion) =>
          dataStore.get(key) match {
            case None => true  // Key doesn't exist in dataStore, allow commit
            case Some((_, dataStoreVersion)) => dataStoreVersion == tmpVersion  // Versions must match
          }
      }
      log.debug(s"Commit successful: $isCommitSuccessful")
      if (isCommitSuccessful) {
        tmpStore.foreach { case (key, (value, _)) =>
          val newVersion = dataStore.get(key).map(_._2 + 1).getOrElse(1L)
          dataStore(key) = (value, newVersion)
        }
        tmpDeleteSet.foreach { case (key: String, _: Long) => dataStore -= key }
        sender() ! "CommitSuccessful"
      } else {
        sender() ! "CommitFailed"
      }

    case Get(key) =>
      dataStore.get(key) match {
        case Some((value, version)) =>
          sender() ! (value, version)  // Ensure this line executes
        case None =>
          sender() ! ("Not Found", 0L)  // Even if not found, send a reply
      }

    case "SHOW ALL" =>
      sender() ! dataStore
  }
}

/**
 * Client Actor responsible for handling client-side operations and interacting with DatastoreActor.
 *
 * @param datastoreActor ActorRef to the DatastoreActor
 */
class ClientActor(datastoreActor: ActorRef) extends Actor with ActorLogging{
  implicit val timeout: Timeout = Timeout(5.seconds)
  var tmpStore: mutable.HashMap[String, (String, Long)] = mutable.HashMap()
  var tmpDeleteSet: mutable.HashMap[String, Long] = mutable.HashMap()
  var isTransactionActive = false

  def jsonResponse(status: String, action: String, additional: Map[String, String] = Map()): String = {
    val additionalJson = additional.map { case (k,v) => s""""$k":"$v"""" }.mkString(", ")
    s"""{"status":"$status", "action":"$action"${
      if (additional.nonEmpty) s", $additionalJson"
      else ""
    }}"""
  }

  def receive = {
    case ParseCommand(data) =>
      val parts = data.trim.split("\\s+", 3)
      val command = parts(0).toUpperCase
      command match {
        case "START" =>
          isTransactionActive = true
          tmpStore.clear()
          tmpDeleteSet.clear()
          sender() ! """{"status":"Ok", "action":"START"}"""

        case "PUT" if isTransactionActive =>
          val key = parts(1)
          val value = parts(2)

          def handlePut(version: Long) = {
            tmpStore(key) = (value, version)
            sender() ! """{"status":"Ok", "action":"PUT"}"""
          }
          val version = tmpStore.get(key) match {
            case Some((_, existingVersion)) => existingVersion  // Use the existing version if key is already in tmpStore
            case None =>  // Fetch version from datastoreActor if key is not in tmpStore
              val future = (datastoreActor ? Get(key))(Timeout(5.seconds)).mapTo[(String, Long)]
              var fetchedVersion = 0L
              future.onComplete {
                case Success((_, version)) => handlePut(version)
                case Failure(_) => handlePut(0L) // Default if fetch fails
              }
              fetchedVersion
          }
          tmpStore(key) = (value, version)
          sender() ! """{"status":"Ok", "action":"PUT"}"""

        case "GET" if isTransactionActive =>
          val key = parts(1)
          val originalSender = sender()
          if (tmpStore.contains(key)) {
            val value = tmpStore(key)._1
            originalSender ! s"""{"status":"Ok", "action":"GET", "value":"$value"}"""
          } else {
            val future = (datastoreActor ? Get(key))(Timeout(5.seconds)).mapTo[(String, Long)]
            future.onComplete {
              case Success((value, version)) =>
                tmpStore(key) = (value, version)
                originalSender ! s"""{"status":"Ok", "action":"GET", "value":"$value"}"""
              case Failure(e) =>
                originalSender ! s"""{"status":"Error", "action":"GET", "message":"${e.getMessage}"}"""
            }
          }

        case "SHOW" if isTransactionActive =>
          val originalSender = sender()
          val future = (datastoreActor ? "SHOW ALL")(Timeout(5.seconds)).mapTo[mutable.HashMap[String, (String, Long)]]
          future.onComplete {
            case Success(dataStoreContent) =>
              val dataStoreState = dataStoreContent.map { case (k, (v, ver)) => s"$k:$v(version:$ver)" }.mkString(", ")
              val tmpStoreState = tmpStore.map { case (k, (v, ver)) => s"$k:$v(version:$ver)" }.mkString(", ")
              originalSender ! s"""{"status":"Ok", "action":"SHOW ALL", "tmpStore":{$tmpStoreState}, "dataStore":{$dataStoreState}}"""
            case Failure(e) =>
              originalSender ! s"""{"status":"Error", "action":"SHOW ALL", "message":"${e.getMessage}"}"""
          }


        case "DELETE" if isTransactionActive =>
          val key = parts(1)
          val version = tmpStore.get(key) match {
            case Some((_, existingVersion)) => existingVersion  // Use the existing version if key is already in tmpStore
            case None =>  // Fetch version from datastoreActor if key is not in tmpStore
              val future = (datastoreActor ? Get(key))(Timeout(5.seconds)).mapTo[(String, Long)]
              var fetchedVersion = 0L
              future.onComplete {
                case Success((_, version)) => fetchedVersion = version
                case Failure(_) => fetchedVersion = 0L // Default if fetch fails
              }
              fetchedVersion
          }
          tmpStore -= key
          tmpDeleteSet += key -> version  // Add version information to the delete set
          sender() ! """{"status":"Ok", "action":"DELETE"}"""


        case "COMMIT" if isTransactionActive =>
          log.info("Received COMMIT command")

          val originalSender = sender()
          val future = (datastoreActor ? CommitTransaction(tmpStore, tmpDeleteSet))(Timeout(5.seconds)).mapTo[String]
          future.onComplete {
            case Success(status) =>
              if (status == "CommitSuccessful") {
                tmpStore.clear()
                tmpDeleteSet.clear()
                isTransactionActive = false
                println("Commit successful")
                originalSender ! jsonResponse("OK", "COMMIT")
              }else{
                originalSender ! jsonResponse("CommitFailed", "COMMIT")
              }
            case Failure(_) =>
              originalSender ! jsonResponse("CommitFailed", "COMMIT")
          }

        case "ROLLBACK" if isTransactionActive =>
          log.info("Received ROLLBACK command")
          tmpStore.clear()
          tmpDeleteSet.clear()
          isTransactionActive = false
          sender() ! """{"status":"Ok", "action":"ROLLBACK"}"""

        case _ =>
          log.warning("Received unknown command")
          sender() ! """{"status":"FAILED", "action":"UNEXPECTED ERROR! OR NO ACTIVE TRANSACTION"}"""
      }
  }
}

/**
 * Main function initiating the ActorSystem, starting DatastoreActor and setting up the server socket for accepting client connections.
 */
object DatastoreServer {
  def main(args: Array[String]): Unit = {
    println("Starting DatastoreServer...")
    val akkaDbArt =
      """
        |
        | ________  ___  __    ___  __    ________                 ________  ________
        ||\   __  \|\  \|\  \ |\  \|\  \ |\   __  \               |\   ___ \|\   __  \
        |\ \  \|\  \ \  \/  /|\ \  \/  /|\ \  \|\  \  ____________\ \  \_|\ \ \  \|\ /_
        | \ \   __  \ \   ___  \ \   ___  \ \   __  \|\____________\ \  \ \\ \ \   __  \
        |  \ \  \ \  \ \  \\ \  \ \  \\ \  \ \  \ \  \|____________|\ \  \_\\ \ \  \|\  \
        |   \ \__\ \__\ \__\\ \__\ \__\\ \__\ \__\ \__\              \ \_______\ \_______\
        |    \|__|\|__|\|__| \|__|\|__| \|__|\|__|\|__|               \|_______|\|_______|
        |
        |
        |
        |
        |""".stripMargin

    println(akkaDbArt)
    println("Starting Akka-Db Server at port 11210...")


    implicit val system: ActorSystem = ActorSystem("DatastoreSystem")
    val datastoreActor = system.actorOf(Props[DatastoreActor], "datastoreActor")
    val serverSocket = new ServerSocket(11210)

    val deadLetterListener = system.actorOf(Props[DBDeadLetterListener])
    system.eventStream.subscribe(deadLetterListener, classOf[DeadLetter])
    
    /**
     * Consistently accepts client connections, starts a ClientActor for each connection, and processes client commands through handleClient function.
     */
    while (true) {
      val clientSocket = serverSocket.accept()
      println("New client connected")
      val clientActor = system.actorOf(Props(new ClientActor(datastoreActor)))
      handleClient(clientSocket, clientActor)
    }
  }

  /**
   * Handle the client connections
   *
   * @param clientSocket The client's socket
   * @param clientActor  Actor for client-side operations
   */

  def handleClient(clientSocket: Socket, clientActor: ActorRef): Unit = {
    new Thread(new Runnable {
      def run(): Unit = {
        implicit val timeout: Timeout = Timeout(5.seconds)
        val reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream, "UTF-8"))
        val writer = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream, "UTF-8"), true)

        while (true) {
          val command = reader.readLine()
          if (command != null) {
            println(s"Received command: $command")
            val future = (clientActor ? ParseCommand(command))(Timeout(5.seconds)).mapTo[String]
            future.onComplete {
              case Success(response) => writer.println(response)
              case Failure(e) => writer.println(s"""{"status":"Error", "mesg":"${e.getMessage}"}""")
            }
          }
        }
      }
    }).start()
  }
}
