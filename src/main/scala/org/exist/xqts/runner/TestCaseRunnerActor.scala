/*
 * Copyright (C) 2018  The eXist Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.exist.xqts.runner

import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.{Files, Path}

import akka.actor.{Actor, ActorRef}
import org.exist.xqts.runner.TestCaseRunnerActor.RunTestCase
import org.exist.xqts.runner.XQTSParserActor._
import scalaz.{-\/, \/, \/-}
import scalaz.syntax.either._
import TestCaseRunnerActor._
import IgnorableWrapper._
import org.exist.xqts.runner.ExistServer._
import org.exist.xquery.value._
import java.nio.charset.StandardCharsets.UTF_8
import java.util.regex.Pattern

import cats.effect.{IO, Resource}
import grizzled.slf4j.Logger
import org.exist.dom.memtree.DocumentImpl
import org.exist.xqts.runner.AssertTypeParser.TypeNode.{ExistTypeDescription, ExplicitExistTypeDescription, WildcardExistTypeDescription}
import org.exist.xqts.runner.CommonResourceCacheActor.{CachedResource, GetResource, ResourceGetError}
import org.exist.xqts.runner.XQTSRunnerActor.{RanTestCase, RunningTestCase}
import org.exist.xquery.Cardinality
import org.xmlunit.builder.{DiffBuilder, Input}
import org.xmlunit.diff.{Comparison, ComparisonType, DefaultComparisonFormatter}

import scala.util.{Failure, Success}

/**
  * Actor that executes an XQTS test-case
  * and checks the results against the
  * assertions specified in the test-case.
  *
  * @param existServer The eXist-db server to execute the test case against.
  * @param commonResourceCacheActor An actor for retrieving common resources.
  *
  * @author Adam Retter <adam@evolvedbinary.com>
  */
class TestCaseRunnerActor(existServer: ExistServer, commonResourceCacheActor: ActorRef) extends Actor {

  private val logger = Logger(classOf[TestCaseRunnerActor])

  private var awaitingSchemas: Map[Path, Seq[TestCaseId]] = Map.empty
  private var awaitingSources: Map[Path, Seq[TestCaseId]] = Map.empty
  private var awaitingResources: Map[Path, Seq[TestCaseId]] = Map.empty
  private var awaitingQueryStr: Map[Path, Seq[TestCaseId]] = Map.empty
  private var pendingTestCases: Map[TestCaseId, PendingTestCase] = Map.empty

  override def receive: Receive = {

    case rtc @ RunTestCase(testSetRef, testCase, manager) =>

      testCase.test match {
        case Some(-\/(queryStr)) =>
          testCase.environment match {
            case Some(environment) if(environment.schemas.nonEmpty || environment.sources.nonEmpty || environment.resources.nonEmpty || environment.collections.flatMap(_.sources).nonEmpty) =>
              environment.schemas.map(schema => commonResourceCacheActor ! GetResource(schema.file))
              awaitingSchemas = merge(awaitingSchemas)((testSetRef.name, testCase.name), environment.schemas)
              environment.sources.map(source => commonResourceCacheActor ! GetResource(source.file))
              awaitingSources = merge(awaitingSources)((testSetRef.name, testCase.name), environment.sources)
              environment.resources.map(resource => commonResourceCacheActor ! GetResource(resource.file))
              awaitingResources = merge(awaitingResources)((testSetRef.name, testCase.name), environment.resources)
              environment.collections.map(_.sources.map(source => commonResourceCacheActor ! GetResource(source.file)))
              awaitingSources = merge(awaitingSources)((testSetRef.name, testCase.name), environment.collections.flatMap(_.sources))

              pendingTestCases = addIfNotPresent(pendingTestCases)(rtc)

            case _ =>
              // we have everything we need - schedule the test case
              self ! RunTestCaseInternal(rtc, ResolvedEnvironment())
          }

        case Some(\/-(queryPath)) =>
          commonResourceCacheActor ! GetResource(queryPath)
          awaitingQueryStr = merge1(awaitingQueryStr)((testSetRef.name, testCase.name), queryPath)

          testCase.environment match {
            case Some(environment) if(environment.schemas.nonEmpty || environment.sources.nonEmpty || environment.resources.nonEmpty || environment.collections.flatMap(_.sources).nonEmpty) =>
              environment.schemas.map(schema => commonResourceCacheActor ! GetResource(schema.file))
              awaitingSchemas = merge(awaitingSchemas)((testSetRef.name, testCase.name), environment.schemas)
              environment.sources.map(source => commonResourceCacheActor ! GetResource(source.file))
              awaitingSources = merge(awaitingSources)((testSetRef.name, testCase.name), environment.sources)
              environment.resources.map(resource => commonResourceCacheActor ! GetResource(resource.file))
              awaitingResources = merge(awaitingResources)((testSetRef.name, testCase.name), environment.resources)
              environment.collections.map(_.sources.map(source => commonResourceCacheActor ! GetResource(source.file)))
              awaitingSources = merge(awaitingSources)((testSetRef.name, testCase.name), environment.collections.flatMap(_.sources))

              pendingTestCases = addIfNotPresent(pendingTestCases)(rtc)
            case _ =>
              // we are awaiting the queryStr
              pendingTestCases = addIfNotPresent(pendingTestCases)(rtc)
          }

        case None =>
          // error - invalid test case
          //TODO(AR) detect this in catalog parser and inform the manager there!
          //TODO(AR) replace these two messages with InvalidTestCase() - and let the manager deal with it
          manager ! RunningTestCase(testSetRef, testCase.name)
          manager ! RanTestCase(testSetRef, ErrorResult(testSetRef.name, testCase.name, -1, -1, new IllegalStateException("Invalid Test Case: No test defined for test-case")))
      }


    case CachedResource(path, value) =>
      val testCasesAwaitingSchema = awaitingSchemas.get(path).getOrElse(Seq.empty)
      pendingTestCases = addSchemas(pendingTestCases)(testCasesAwaitingSchema, path, value)
      awaitingSchemas = awaitingSchemas - path

      val testCasesAwaitingSources = awaitingSources.get(path).getOrElse(Seq.empty)
      pendingTestCases = addSources(pendingTestCases)(testCasesAwaitingSources, path, value)
      awaitingSources = awaitingSources - path

      val testCasesAwaitingResources = awaitingResources.get(path).getOrElse(Seq.empty)
      pendingTestCases = addResources(pendingTestCases)(testCasesAwaitingResources, path, value)
      awaitingResources = awaitingResources - path

      val testCasesAwaitingQueryStr = awaitingQueryStr.get(path).getOrElse(Seq.empty)
      pendingTestCases = addQueryStrs(pendingTestCases)(testCasesAwaitingQueryStr, path, value)
      awaitingQueryStr = awaitingQueryStr - path

      // check if we have any test cases that are no longer pending
      val allUpdatedPendingTestCases = testCasesAwaitingSchema.toSet ++ testCasesAwaitingSources.toSet ++ testCasesAwaitingResources.toSet ++ testCasesAwaitingQueryStr.toSet
      val fulfilledPendingTestCaseIds = allUpdatedPendingTestCases
        .filter(updatedPendingTestCase => notIn(awaitingSchemas)(updatedPendingTestCase) && notIn(awaitingSources)(updatedPendingTestCase) && notIn(awaitingResources)(updatedPendingTestCase) && notIn(awaitingQueryStr)(updatedPendingTestCase))
      val fulfilledPendingTestCases = fulfilledPendingTestCaseIds.map(pendingTestCases(_))

      // fulfilledPendingTestCases now have everything they need to run
      fulfilledPendingTestCases.map(fulfilledPendingTestCase =>
        // we have everything we need - schedule the test case
        self ! RunTestCaseInternal(fulfilledPendingTestCase.runTestCase, fulfilledPendingTestCase.resolvedEnvironment)
      )
      pendingTestCases = pendingTestCases -- fulfilledPendingTestCaseIds


    case ResourceGetError(path, error) =>
      val testCasesAwaitingSchema = awaitingSchemas.get(path).getOrElse(Seq.empty)
      val testCasesAwaitingSources = awaitingSources.get(path).getOrElse(Seq.empty)
      val testCasesAwaitingResources = awaitingResources.get(path).getOrElse(Seq.empty)
      val testCasesAwaitingQueryStr = awaitingQueryStr.get(path).getOrElse(Seq.empty)

      // these are the test cases which were waiting for the the resource
      val pathPendingTestCaseIds = testCasesAwaitingSchema.toSet ++ testCasesAwaitingSources.toSet ++ testCasesAwaitingResources.toSet ++ testCasesAwaitingQueryStr

      // without the resource we cannot complete the test case, so we have to fail it
      for (pathPendingTestCaseId <- pathPendingTestCaseIds) {
        val pendingTestCase = pendingTestCases(pathPendingTestCaseId)
        val manager = pendingTestCase.runTestCase.manager
        val testSetRef = pendingTestCase.runTestCase.testSetRef
        val testCase = pendingTestCase.runTestCase.testCase

        //TODO(AR) replace these two messages with InvalidTestCase() - and let the manager deal with it
        manager ! RunningTestCase(testSetRef, testCase.name)
        manager ! RanTestCase(testSetRef, ErrorResult(testSetRef.name, testCase.name, -1, -1, error))
      }
      awaitingSchemas = awaitingSchemas - path
      awaitingSources = awaitingSources - path
      awaitingResources = awaitingResources - path
      awaitingQueryStr = awaitingQueryStr - path
      pendingTestCases = pendingTestCases -- pathPendingTestCaseIds


    case RunTestCaseInternal(RunTestCase(testSetRef, testCase, manager), resolvedEnvironment) =>
      manager ! RunningTestCase(testSetRef, testCase.name)
      // actually run the test case!
      val result = runTestCaseWithExist(testSetRef.name, testCase, resolvedEnvironment)
      manager ! RanTestCase(testSetRef, result)
  }

  /**
    * Execute an XQTS test-case against eXist-db.
    *
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCase the test-case to execute.
    * @param resolvedEnvironment the environment resources for the test-case.
    *
    * @return the result of executing the XQTS test-case.
    */
  private def runTestCaseWithExist(testSetName: TestSetName, testCase: TestCase, resolvedEnvironment: ResolvedEnvironment) : TestResult = {
    val existIO = Resource.make(IO { existServer.getConnection() })(connection => IO { connection.close() })
    val testCaseIO = existIO.use(connection => IO {
      try {
        runTestCase(connection, testSetName, testCase, resolvedEnvironment)
      } catch {

        case e: java.lang.OutOfMemoryError =>
          println(s"OutOfMemoryError: $testSetName ${testCase.name}")
          throw e

        case e: java.lang.StackOverflowError =>
          println(s"StackOverflowError: $testSetName ${testCase.name}")
          throw e
      }
    })

    // execute test case IO
    testCaseIO.unsafeRunSync()
  }

  /**
    * Run's an XQTS test-case against eXist-db.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCase the test-case to execute.
    * @param resolvedEnvironment the environment resources for the test-case.
    *
    * @return the result of executing the XQTS test-case.
    */
  private def runTestCase(connection: ExistConnection, testSetName: TestSetName, testCase: TestCase, resolvedEnvironment: ResolvedEnvironment) : TestResult = {
    testCase.test match {
      case Some(test) =>

        // get the XQuery to execute
        val queryString: String = test.map(_ => resolvedEnvironment.resolvedQuery.get).merge

        // get the static baseURI for the XQuery
        val baseUri = testCase.environment
          .flatMap(_.staticBaseUri.orElse(Some(testCase.file.toUri.toString)))
          .filterNot(_ == "#UNDEFINED")

        // execute the query
        getContextSequence(connection)(testCase, resolvedEnvironment).flatMap(contextSequence =>
          getDynamicContextAvailableDocuments(connection)(testCase, resolvedEnvironment).flatMap(availableDocuments =>
            getDynamicContextAvailableCollections(connection)(testCase, resolvedEnvironment).flatMap(availableCollections =>
              getDynamicContextAvailableTextResources(connection)(testCase, resolvedEnvironment).flatMap(availableTextResources =>
                getVariableDeclarations(connection)(testCase).flatMap(variableDeclarations =>
                  connection.executeQuery(queryString, false, baseUri, contextSequence, availableDocuments, availableCollections, availableTextResources, variableDeclarations)
                )
              )
            )
          )
        ) match {

          // exception occurred whilst executing the query
          case -\/(existServerException) =>
            ErrorResult(testSetName, testCase.name, existServerException.compilationTime, existServerException.executionTime, existServerException)

          case \/-(Result(result, compilationTime, executionTime)) =>
            result match {

              // executing query returned an error
              case -\/(queryError) =>
                testCase.result match {
                  case Some(expectedResult) =>
                    expectedResult match {
                      case Error(expectedError) if(expectedError == queryError.errorCode) =>
                        PassResult(testSetName, testCase.name, compilationTime, executionTime)
                      case anyOf @ AnyOf(_) if(anyOfContainsError(anyOf, queryError.errorCode)) =>
                        PassResult(testSetName, testCase.name, compilationTime, executionTime)
                      case _ =>
                        FailureResult(testSetName, testCase.name, compilationTime, executionTime, failureMessage(expectedResult, queryError))
                    }
                  case None =>
                    ErrorResult(testSetName, testCase.name, compilationTime, executionTime, new IllegalStateException("No defined expected result"))
                }

              // executing query returned a result
              case \/-(queryResult) if(queryResult == null) =>
                ErrorResult(testSetName, testCase.name, compilationTime, executionTime, new IllegalStateException("eXist-db returned null from the query, this should never happen!"))

              case \/-(queryResult) =>
                testCase.result match {
                  case (Some(expectedError: Error)) =>  // expected an error, but got a query result
                    FailureResult(testSetName, testCase.name, compilationTime, executionTime, failureMessage(connection)(expectedError, queryResult))

                  case (Some(expectedResult)) =>
                    processAssertion(connection, testSetName, testCase.name, compilationTime, executionTime)(expectedResult, queryResult)

                  case None =>
                    ErrorResult(testSetName, testCase.name, compilationTime, executionTime, new IllegalStateException("No defined expected result"))
                }
            }
        }

      case None =>
        ErrorResult(testSetName, testCase.name, -1, -1, new IllegalStateException("No test defined for test-case"))
    }
  }

  /**
    * Get the context sequence for the XQuery.
    *
    * @param connection a connection to an eXist-db server.
    *
    * @param testCase a test-case which describes the context sequence.
    * @param resolvedEnvironment the environment resources for the test-case.
    *
    * @return the context sequence if present, or an exception
    */
  private def getContextSequence(connection: ExistConnection)(testCase: TestCase, resolvedEnvironment: ResolvedEnvironment) : ExistServerException \/ Option[Sequence] = {
    testCase.environment match {
      case Some(env) if (env.name == "empty") =>
        \/-(Some(Sequence.EMPTY_SEQUENCE))

      case Some(env) =>
        env.sources.filter(_.role.filter(_ == ".").nonEmpty).headOption
          .map(resolveSource(resolvedEnvironment, _))
          .map(_.flatMap(resolvedSource => connection.parseXml(resolvedSource.data)).map(Some(_)))
          .getOrElse(\/-(None))

      case None => \/-(None)
    }
  }

  /**
    * Resolves a source from the environment.
    *
    * @param resolvedEnvironment the environment resources for the test-case.
    * @param source the source to resolve from the environment.
    *
    * @return the resolved source, or an exception.
    */
  private def resolveSource(resolvedEnvironment: ResolvedEnvironment, source: Source): ExistServerException \/ ResolvedSource = {
    resolvedEnvironment.resolvedSources.find(_.path == source.file)
      .map(\/-(_))
      .getOrElse(-\/(ExistServerException(new IllegalStateException(s"Could not resolve source ${source.file}"))))
  }

  /**
    * Get the dynamically available collections for the XQuery Context.
    *
    * @param connection a connection to an eXist-db server.
    *
    * @param testCase a test-case which describes the dynamically available collections.
    * @param resolvedEnvironment the environment resources for the test-case.
    *
    * @return the dynamically available collections, or an exception
    */
  private def getDynamicContextAvailableCollections(connection: ExistConnection)(testCase: TestCase, resolvedEnvironment: ResolvedEnvironment) : ExistServerException \/ List[(String, List[DocumentImpl])] = {
    val initAccum: ExistServerException \/ List[(String, List[DocumentImpl])] = \/-(List.empty)
    testCase.environment
      .map(env => env.collections)
      .getOrElse(List.empty)
      .foldLeft(initAccum) { case (accum, x) =>
        accum match {
          case error@ -\/(_) => error
          case \/-(results) =>
            val initInnerAccum : ExistServerException \/ List[DocumentImpl] = \/-(List.empty)
            x.sources.foldLeft(initInnerAccum) { case (innerAccum, y) =>
                innerAccum match {
                  case resolveError@ -\/(_) => resolveError
                  case \/-(resolvedSources) =>
                    resolveSource(resolvedEnvironment, y)
                      .flatMap(resolveSource => connection.parseXml(resolveSource.data))
                      .map(_ +: resolvedSources)

                }
              }
              .map((x.uri.toString, _))
              .map(_ +: results)
        }
      }
  }

  /**
    * Get the dynamically available documents for the XQuery Context.
    *
    * @param connection a connection to an eXist-db server.
    *
    * @param testCase a test-case which describes the dynamically available documents.
    * @param resolvedEnvironment the environment resources for the test-case.
    *
    * @return the dynamically available documents, or an exception
    */
  private def getDynamicContextAvailableDocuments(connection: ExistConnection)(testCase: TestCase, resolvedEnvironment: ResolvedEnvironment) : ExistServerException \/ List[(String, DocumentImpl)] = {
    val initAccum: ExistServerException \/ List[(String, DocumentImpl)] = \/-(List.empty)
    testCase.environment
      .map(env => env.sources.filter(source => source.role.isEmpty && source.uri.nonEmpty))
      .getOrElse(List.empty)
      .foldLeft(initAccum) { case (accum, x) =>
        accum match {
          case error@ -\/(_) => error
          case \/-(results) =>
            x.uri
              .flatMap(uri => resolvedEnvironment.resolvedSources.find(_.path == x.file)
                .map(resolvedSource => (uri, resolvedSource.data))
                .map { case (uri, data) => connection.parseXml(data).map(doc => (uri, doc)) }
              )
              .map(_.map(result => result +: results))
              .getOrElse(-\/(ExistServerException(new IllegalStateException(s"Could not resolve source ${x.file}"))))
        }
      }
  }

  /**
    * Get the dynamically available text resources for the XQuery Context.
    *
    * @param connection a connection to an eXist-db server.
    *
    * @param testCase a test-case which describes the dynamically text resources.
    * @param resolvedEnvironment the environment resources for the test-case.
    *
    * @return the dynamically available text resources, or an exception
    */
  private def getDynamicContextAvailableTextResources(connection: ExistConnection)(testCase: TestCase, resolvedEnvironment: ResolvedEnvironment) : ExistServerException \/ List[(String, Charset, String)] = {
    def toString(data: Array[Byte], encoding: Option[String]) : ExistServerException \/ (Charset, String) = {
      \/.fromTryCatchNonFatal(encoding.map(Charset.forName).getOrElse(UTF_8))
        .map(charset => (charset, new String(data, charset)))
        .leftMap(ExistServerException(_))
    }

    val initAccum: ExistServerException \/ List[(String, Charset, String)] = \/-(List.empty)
    testCase.environment
      .map(env => env.resources)
      .getOrElse(List.empty)
      .foldLeft(initAccum) { case (accum, x) =>
        accum match {
          case error@ -\/(_) => error
          case \/-(results) =>
              resolvedEnvironment.resolvedResources.find(_.path == x.file)
                .map(resolvedResource => toString(resolvedResource.data, x.encoding).map{ case (charset, string) => (x.uri, charset, string) })
                .map(_.map(result => result +: results))
                .getOrElse(-\/(ExistServerException(new IllegalStateException(s"Could not resolve resource ${x.file} with encoding ${x.encoding.getOrElse("UTF-8")}"))))
        }
      }
  }

  /**
    * Get the external variable declarations for the XQuery Context.
    *
    * @param connection a connection to an eXist-db server.
    *
    * @param testCase a test-case which describes the external variable declarations.
    *
    * @return the external variable declarations, or an exception
    */
  private def getVariableDeclarations(connection: ExistConnection)(testCase: TestCase): ExistServerException \/ List[(String, Sequence)] = {
    def variableSelectToSequence(`type`: Int, select: Option[String]): ExistServerException \/ Sequence = {
      select match {
        case None =>
          \/-(Sequence.EMPTY_SEQUENCE)

        case Some(selectExpr) =>
          `type` match {
            case Type.EMPTY =>
              \/-(Sequence.EMPTY_SEQUENCE)

            case _ =>
              connection.executeQuery(selectExpr, false, None, None)
                .flatMap(_.result.leftMap(queryError => ExistServerException(new IllegalStateException(s"Could not calculate param select: ${queryError.errorCode}: ${queryError.message}"))))
          }
      }
    }

    val initAccum : ExistServerException \/ List[(String, Sequence)] = \/-(List.empty)

    testCase.environment
      .map(env => env.params.map(param => (param.name, param.as.map(Type.getType).getOrElse(Type.ANY_TYPE), param.select)))
      .getOrElse(List.empty)
      .foldLeft(initAccum) { case (accum, (name, typ, select)) =>
        accum match {
          case error@ -\/(_) => error
          case \/-(results) =>
            variableSelectToSequence(typ, select)
              .map(seq => (name, seq))
              .map(result => result +: results)
        }
      }
  }

  /**
    * Checks if an XQTS any-of assertion contains a specific error assertion.
    *
    * @param anyOf the any-of assertion.
    * @param expectedError the error to search for in the any-of
    *
    * @return true if the any-of contains the error, false otherwise.
    */
  private def anyOfContainsError(anyOf: AnyOf, expectedError: String) : Boolean = {
    def expand(result: XQTSParserActor.Result) : List[XQTSParserActor.Result] = {
      Some(result)
        .filter(_.isInstanceOf[Assertions])
        .map(_.asInstanceOf[Assertions])
        .map(_.assertions)
        .getOrElse(List(result))
    }

    anyOf.assertions.map(expand).flatten
      .filter(_.isInstanceOf[Error])
      .map(_.asInstanceOf[Error])
      .find(_.expected == expectedError)
      .nonEmpty
  }

  /**
    * Processes an expected XQTS assertion to compare
    * it against the actual result of executing an XQuery.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expectedResult the assertion which describes the expected result(s).
    * @param actualResult the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def processAssertion(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expectedResult: XQTSParserActor.Result, actualResult: ExistServer.QueryResult) : TestResult = {
    expectedResult match {
      case AllOf(assertions) =>
        allOf(connection, testSetName, testCaseName, compilationTime, executionTime)(assertions, actualResult)

      case AnyOf(assertions) =>
        anyOf(connection, testSetName, testCaseName, compilationTime, executionTime)(assertions, actualResult)

      case Assert(xpath) =>
        assertXpath(connection, testSetName, testCaseName, compilationTime, executionTime)(xpath, actualResult)

      case AssertCount(expectedCount) =>
        assertCount(testSetName, testCaseName, compilationTime, executionTime)(expectedCount, actualResult)

      case AssertDeepEquals(expected) =>
        assertDeepEquals(connection, testSetName, testCaseName, compilationTime, executionTime)(expected, actualResult)

      case AssertEq(expected) =>
        assertEq(connection, testSetName, testCaseName, compilationTime, executionTime)(expected, actualResult)

      case AssertPermutation(expected) =>
        assertPermutation(connection, testSetName, testCaseName, compilationTime, executionTime)(expected, actualResult)

      case AssertSerializationError(expected) =>
        assertSerializationError(connection, testSetName, testCaseName, compilationTime, executionTime)(expected, actualResult)

      case AssertStringValue(expected, normalizeSpace) =>
        assertStringValue(connection, testSetName, testCaseName, compilationTime, executionTime)(expected, normalizeSpace, actualResult)

      case AssertType(expectedType) =>
        assertType(testSetName, testCaseName, compilationTime, executionTime)(expectedType, actualResult)

      case AssertXml(expectedXml, ignorePrefixes) =>
        assertXml(connection, testSetName, testCaseName, compilationTime, executionTime)(expectedXml, ignorePrefixes, actualResult)

      case SerializationMatches(expected, flags) =>
        serializationMatches(connection, testSetName, testCaseName, compilationTime, executionTime)(expected, flags, actualResult)

      case AssertEmpty =>
        assertEmpty(connection, testSetName, testCaseName, compilationTime, executionTime)(actualResult)

      case AssertFalse =>
        assertFalse(testSetName, testCaseName, compilationTime, executionTime)(actualResult)

      case AssertTrue =>
        assertTrue(testSetName, testCaseName, compilationTime, executionTime)(actualResult)

      case Error(expected) =>
        FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"error: expected='$expected', but no error was raised")

      case _ =>
        ErrorResult(testSetName, testCaseName, compilationTime, executionTime, new IllegalStateException("Unknown defined result assertion"))
    }
  }

  /**
    * Handles the XQTS {@code all-of} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param assertions the assertions that make up the {@code all-of} assertion.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def allOf(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(assertions: List[XQTSParserActor.Result], actual: ExistServer.QueryResult): TestResult = {
    val problem : Option[ErrorResult \/ FailureResult] = assertions.foldLeft(Option.empty[ErrorResult \/ FailureResult]) { case (failed, assertion) =>
        if(failed.nonEmpty) {
          failed
        } else {
          processAssertion(connection, testSetName, testCaseName, compilationTime, executionTime)(assertion, actual) match {
            case error: ErrorResult =>
              Some(error.left)
            case failure: FailureResult =>
              Some(failure.right)
            case pass: PassResult =>
              None
            case assumptionFailed: AssumptionFailedResult =>
              throw new IllegalStateException("Assumption should have already been evaluated")
          }
        }
    }

    problem.map(e => e.fold(_.asInstanceOf[TestResult], _.asInstanceOf[TestResult]))
      .getOrElse(PassResult(testSetName, testCaseName, compilationTime, executionTime))
  }

  /**
    * Handles the XQTS {@code any-of} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param assertions the assertions that make up the {@code any-of} assertion.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def anyOf(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(assertions: List[XQTSParserActor.Result], actual: ExistServer.QueryResult): TestResult = {
    def passOrFails() : Seq[ErrorResult \/ FailureResult] \/ PassResult= {
      val accum = Seq.empty[ErrorResult \/ FailureResult].left[PassResult]
      assertions.foldLeft(accum) { case (results, assertion) =>
        results match {
          case passed @ \/-(_) =>
            passed  // if we have passed one, we don't need to evaluate any more assertions

          case errors @ -\/(_) =>
            // evaluate the next assertion
            processAssertion(connection, testSetName, testCaseName, compilationTime, executionTime)(assertion, actual) match {
              case pass: PassResult =>
                \/-(pass)

              case failure: FailureResult =>
                errors.leftMap(_ :+ \/-(failure))

              case error: ErrorResult =>
                errors.leftMap(_ :+ -\/(error))

              case assumptionFailed: AssumptionFailedResult =>
                throw new IllegalStateException("Assumption should have already been evaluated")
            }
        }
      }
    }

    passOrFails()
      .map(_.asInstanceOf[TestResult])
      .leftMap(errors => FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"anyOf: expected='$assertions', actual='${connection.sequenceToStringAdaptive(actual)}', results=('${errors.map(_.fold(_.t.getMessage, _.reason)).mkString("', '")}')"))
      .leftMap(_.asInstanceOf[TestResult])
      .merge
  }

  /**
    * Handles the XQTS {@code assert-xpath} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param xpath the xpath to assert.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertXpath(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(xpath: String, actual: ExistServer.QueryResult): TestResult = {
    executeQueryWith$Result(connection, xpath, true, None, actual) match {
      case -\/(existServerException) =>
        ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

      case \/-(Result(-\/(queryError), errCompilationTime, errExecutionTime)) =>
        ErrorResult(testSetName, testCaseName, compilationTime + errCompilationTime, executionTime + errExecutionTime, new IllegalStateException(s"Error whilst comparing XPath: ${queryError.errorCode}: ${queryError.message}"))

      case \/-(Result(\/-(actualQueryResult), resCompilationTime, resExecutionTime)) =>
        val totalCompilationTime = compilationTime + resCompilationTime
        val totalExecutionTime = executionTime + resExecutionTime
        if (actualQueryResult.getItemCount == 1
          && actualQueryResult.itemAt(0).isInstanceOf[BooleanValue]
          && actualQueryResult.itemAt(0).asInstanceOf[BooleanValue].effectiveBooleanValue()) {
          PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
        } else {
          FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert: expected='$xpath', actual='${connection.sequenceToStringAdaptive(actual)}'")
        }
    }
  }

  /**
    * Handles the XQTS {@code assert-count} assertion.
    *
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected the expected count of results.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertCount(testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: Int, actual: ExistServer.QueryResult): TestResult = {
    val actualCount = actual.getItemCount
    if (expected == actualCount) {
      PassResult(testSetName, testCaseName, compilationTime, executionTime)
    } else {
      FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-count: expected=$expected, actual=$actualCount")
    }
  }

  /**
    * Handles the XQTS {@code assert-deep-eq} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected the expected sequence of results to assert.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertDeepEquals(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: String, actual: ExistServer.QueryResult): TestResult = {
    executeQueryWith$Result(connection, s"deep-equal(($expected), $$result)", true, None, actual) match {
      case -\/(existServerException) =>
        ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

      case \/-(Result(-\/(queryError), errCompilationTime, errExecutionTime)) =>
        ErrorResult(testSetName, testCaseName, compilationTime + errCompilationTime, executionTime + errExecutionTime, new IllegalStateException(s"Error whilst comparing deep-equals: ${queryError.errorCode}: ${queryError.message}}"))

      case \/-(Result(\/-(actualQueryResult), resCompilationTime, resExecutionTime)) =>
        val totalCompilationTime = compilationTime + resCompilationTime
        val totalExecutionTime = executionTime + resExecutionTime
        if (actualQueryResult.getItemCount == 1
          && actualQueryResult.itemAt(0).isInstanceOf[BooleanValue]
          && actualQueryResult.itemAt(0).asInstanceOf[BooleanValue].effectiveBooleanValue()) {
          PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
        } else {
          FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert-deep-eq: expected='$expected', actual='${connection.sequenceToStringAdaptive(actual)}'")
        }
    }
  }

  /**
    * Handles the XQTS {@code assert-eq} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected an XPath which describes that expected result is {@code eq} to the actual result.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertEq(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: String, actual: ExistServer.QueryResult): TestResult = {
    executeQueryWith$Result(connection, s"$expected eq $$result", false, None, actual) match {
      case -\/(existServerException) =>
        ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

      case \/-(Result(-\/(queryError), errCompilationTime, errExecutionTime)) =>
        ErrorResult(testSetName, testCaseName, compilationTime + errCompilationTime, executionTime + errExecutionTime, new IllegalStateException(s"Error whilst comparing eq: ${queryError.errorCode}: ${queryError.message}"))

      case \/-(Result(\/-(actualQueryResult), resCompilationTime, resExecutionTime)) =>
        val totalCompilationTime = compilationTime + resCompilationTime
        val totalExecutionTime = executionTime + resExecutionTime
        if (actualQueryResult.getItemCount == 1
            && actualQueryResult.itemAt(0).isInstanceOf[BooleanValue]
            && actualQueryResult.itemAt(0).asInstanceOf[BooleanValue].getValue) {
          PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
        } else {
          FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert-eq: expected='$expected', actual='${connection.sequenceToStringAdaptive(actual)}'")
        }
    }
  }

  /**
    * Handles the XQTS {@code assert-permutation} assertion.
    *
    * This is very similar to {@code assert-deep-eq},
    * but we sort both the expected and actual results
    * into the same order first, so that we can
    * ignore permutations.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected the expected sequence of results to assert.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertPermutation(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: String, actual: ExistServer.QueryResult): TestResult = {
    val expectedQuery = s"""
                        | let $$sort-key-fun := function($$key) {
                        |   let $$data := fn:data($$key)
                        |   return
                        |     if ($$key instance of xs:string) then
                        |       concat("str_", $$data)
                        |     else
                        |       $$data cast as xs:string
                        | }
                        | return
                        |   fn:deep-equal(fn:sort(($expected), (), $$sort-key-fun), fn:sort($$result, (), $$sort-key-fun))
                        |""".stripMargin

    executeQueryWith$Result(connection, expectedQuery, true, None, actual) match {
      case -\/(existServerException) =>
        ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

      case \/-(Result(-\/(queryError), errCompilationTime, errExecutionTime)) =>
        ErrorResult(testSetName, testCaseName, compilationTime + errCompilationTime, executionTime + errExecutionTime, new IllegalStateException(s"Error whilst comparing permutation: ${queryError.errorCode}: ${queryError.message}"))

      case \/-(Result(\/-(actualQueryResult), resCompilationTime, resExecutionTime)) =>
        val totalCompilationTime = compilationTime + resCompilationTime
        val totalExecutionTime = executionTime + resExecutionTime
        if (actualQueryResult.getItemCount == 1
          && actualQueryResult.itemAt(0).isInstanceOf[BooleanValue]
          && actualQueryResult.itemAt(0).asInstanceOf[BooleanValue].effectiveBooleanValue()) {
          PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
        } else {
          FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert-permutation: expected='$expected', actual='${connection.sequenceToStringAdaptive(actual)}'")
        }
    }
  }

  /**
    * Handles the XQTS {@code assert-serialization-error} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected the expected XQuery Error code.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertSerializationError(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: String, actual: ExistServer.QueryResult): TestResult = {
    executeQueryWith$Result(connection, QUERY_ASSERT_XML_SERIALIZATION, true, None, actual) match {
      case -\/(existServerException) =>
        ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

      case \/-(Result(-\/(queryError), errActualCompilationTime, errActualExecutionTime)) if (queryError.errorCode == expected || expected == "*") =>
        PassResult(testSetName, testCaseName, compilationTime + errActualCompilationTime, executionTime + errActualExecutionTime)

      case \/-(Result(-\/(queryError), errActualCompilationTime, errActualExecutionTime)) =>
        FailureResult(testSetName, testCaseName, compilationTime + errActualCompilationTime, executionTime + errActualExecutionTime, s"assert-serialization-error: expected='$expected' actual='${queryError.errorCode}'")

      case \/-(Result(\/-(actualQueryResult), actualQueryCompilationTime, actualQueryExecutionTime)) =>
        FailureResult(testSetName, testCaseName, compilationTime + actualQueryCompilationTime, executionTime + actualQueryExecutionTime, s"assert-serialization-error: expected='$expected', but query returned result='$actualQueryResult'")
    }
  }

  /**
    * Handles the XQTS {@code assert-empty} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertEmpty(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(actual: ExistServer.QueryResult): TestResult = {
    if (actual.isEmpty) {
      PassResult(testSetName, testCaseName, compilationTime, executionTime)
    } else {
      FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-empty: actual='${connection.sequenceToStringAdaptive(actual)}'")
    }
  }

  /**
    * Handles the XQTS {@code assert-false} assertion.
    *
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertFalse(testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(actual: ExistServer.QueryResult): TestResult = {
    if (actual.getItemCount == 1
        && actual.itemAt(0).getType == Type.BOOLEAN
        && actual.itemAt(0).asInstanceOf[BooleanValue].getValue == false) {
      PassResult(testSetName, testCaseName, compilationTime, executionTime)
    } else {
      FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-false: actual='$actual'")
    }
  }

  /**
    * Handles the XQTS {@code assert-true} assertion.
    *
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertTrue(testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(actual: ExistServer.QueryResult): TestResult = {
    if (actual.getItemCount == 1
      && actual.itemAt(0).getType == Type.BOOLEAN
      && actual.itemAt(0).asInstanceOf[BooleanValue].getValue == true) {
      PassResult(testSetName, testCaseName, compilationTime, executionTime)
    } else {
      FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-true: actual='$actual'")
    }
  }

  /**
    * Handles the XQTS {@code assert-string-value} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected the expected string value result of the XQuery.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertStringValue(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: String, normalizeSpace: Boolean, actual: ExistServer.QueryResult): TestResult = {
      if (normalizeSpace) {
        // normalize the expected
        executeQueryWith$Result(connection, QUERY_NORMALIZED_SPACE, true, None, new StringValue(expected)) match {
          case -\/(existServerException) =>
            ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

          case \/-(Result(-\/(queryError), errExpectedCompilationTime, errExpectedExecutionTime)) =>
            ErrorResult(testSetName, testCaseName, compilationTime + errExpectedCompilationTime, executionTime + errExpectedExecutionTime, new IllegalStateException(s"Error whilst normalizing expected value: ${queryError.errorCode}: ${queryError.message}"))

          case \/-(Result(\/-(expectedQueryResult), expectedQueryCompilationTime, expectedQueryExecutionTime)) =>
            // get the actual string value and normalize
            executeQueryWith$Result(connection, QUERY_ASSERT_STRING_VALUE_NORMALIZED_SPACE, true, None, actual) match {
              case -\/(existServerException) =>
                ErrorResult(testSetName, testCaseName, compilationTime + expectedQueryCompilationTime + existServerException.compilationTime, executionTime + expectedQueryExecutionTime + existServerException.executionTime, existServerException)

              case \/-(Result(-\/(queryError), errActualCompilationTime, errActualExecutionTime)) =>
                ErrorResult(testSetName, testCaseName, compilationTime + expectedQueryCompilationTime + errActualCompilationTime, executionTime + expectedQueryExecutionTime + errActualExecutionTime, new IllegalStateException(s"Error whilst processing and normalizing actual value: ${queryError.errorCode}: ${queryError.message}"))

              case \/-(Result(\/-(actualQueryResult), actualQueryCompilationTime, actualQueryExecutionTime)) =>

                val stringExpected : String = expectedQueryResult.asInstanceOf[StringValue].getStringValue()
                val stringActual : String = actualQueryResult.asInstanceOf[StringValue].getStringValue()
                val totalCompilationTime = compilationTime + expectedQueryCompilationTime + actualQueryCompilationTime
                val totalExecutionTime = executionTime + expectedQueryExecutionTime + actualQueryExecutionTime

                if (stringActual == stringExpected) {
                  PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
                } else {
                  FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert-string-value: expected='$stringExpected', actual='$stringActual'")
                }
            }
        }

      } else {
        // get the actual string value
        executeQueryWith$Result(connection, QUERY_ASSERT_STRING_VALUE, true, None, actual) match {
          case -\/(existServerException) =>
            ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

          case \/-(Result(-\/(queryError), errCompilationTime, errExecutionTime)) =>
            ErrorResult(testSetName, testCaseName, compilationTime + errCompilationTime, executionTime + errExecutionTime, new IllegalStateException(s"Error whilst processing and normalizing actual value: ${queryError.errorCode}: ${queryError.message}"))

          case \/-(Result(\/-(actualQueryResult), actualQueryCompilationTime, actualQueryExecutionTime)) =>
            val stringActual : String = actualQueryResult.asInstanceOf[StringValue].getStringValue()
            val totalCompilationTime = compilationTime + actualQueryCompilationTime
            val totalExecutionTime = executionTime + actualQueryExecutionTime

            if (stringActual == expected) {
              PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
            } else {
              FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert-string-value: expected='$expected', actual='$stringActual'")
            }
        }
      }
  }

  /**
    * Handles the XQTS {@code assert-type} assertion.
    *
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expectedType the XQTS description of the expected XDM type.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertType(testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expectedType: String, actual: ExistServer.QueryResult): TestResult = {
    def getTypes(seq: Sequence): Seq[Int] = {
      for (i <- (0 until seq.getItemCount))
        yield seq.itemAt(i).getType
    }

    def matchCardinality(expectedType: ExistTypeDescription, count: Int) : Boolean = {
      expectedType match {
        case WildcardExistTypeDescription =>
          true
        case ExplicitExistTypeDescription(_, _, expectedCardinality) =>
          expectedCardinality.getOrElse(Cardinality.EXACTLY_ONE) match {
            case Cardinality.ZERO_OR_ONE =>
              count <= 1
            case Cardinality.EXACTLY_ONE =>
              count == 1
            case Cardinality.ONE_OR_MORE =>
              count >= 1
            case Cardinality.ZERO_OR_MORE =>
              count >= 0
          }
      }
    }

    def matchType(expectedType: ExistTypeDescription, actualTypes: Seq[Int]) : Boolean = {
      if (expectedType.hasParameterTypes) {
        logger.warn("assert-type has parameter types, but eXist-db does not correctly support parameter types. Ignoring parameter types. NOTE: this may lead to false positives in test results!")
      }
      expectedType match {
        case WildcardExistTypeDescription =>
          true
        case ExplicitExistTypeDescription(expectedBaseType, _, _) =>
          actualTypes.filterNot(Type.subTypeOf(_, expectedBaseType)).isEmpty
      }
    }

    \/.fromTryCatchNonFatal(AssertTypeParser.parse(expectedType)).map(_.map(_.asExistTypeDescription)) match {
      case -\/(t) => ErrorResult(testSetName, testCaseName, compilationTime, executionTime, t)
      case \/-(Failure(t)) => ErrorResult(testSetName, testCaseName, compilationTime, executionTime, t)

      // query result returned an empty sequence
      case \/-(Success(expectedType)) if (actual.isEmpty) =>
        if (expectedType == WildcardExistTypeDescription || expectedType.asInstanceOf[ExplicitExistTypeDescription].base == Type.EMPTY) {
          // OK, we expected empty
          PassResult(testSetName, testCaseName, compilationTime, executionTime)
        } else {
          // NOT OK, we expected something else
          FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-type: expected=$expectedType, actual=empty-sequence()")
        }

      // query result returned a non-empty sequence
      case \/-(Success(expectedType)) =>
        val actualTypes = getTypes(actual)
        if (matchCardinality(expectedType, actualTypes.size)) {
          if (matchType(expectedType, actualTypes)) {
            // OK, all types in the sequence matched
            PassResult(testSetName, testCaseName, compilationTime, executionTime)
          } else {
            // NOT OK, one or more types did not match
            FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-type: type did not match, expected=$expectedType, actual=${actualTypes.map(Type.getTypeName(_)).mkString}")
          }
        } else {
          // NOT OK, cardinality of type did not match
          FailureResult(testSetName, testCaseName, compilationTime, executionTime, s"assert-type: cardinality did not match, expected=$expectedType, actual=${actualTypes.map(Type.getTypeName(_)).mkString}")
        }
    }
  }

  /**
    * Handles the XQTS {@code assert-xml} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expectedXml the XML that is expected as the result of the XQuery.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def assertXml(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expectedXml: String \/ Path, ignorePrefixes: Boolean, actual: ExistServer.QueryResult): TestResult = {
    expectedXml.map(readTextFile(_)).fold(\/-(_), r => r) match {
      case -\/(t) =>
        ErrorResult(testSetName, testCaseName, compilationTime, executionTime, t)

      case \/-(expectedXmlStr) =>
        /*
        We first have to serialize the expectedXml just as XQuery would do with
        serialization parameters: method="xml" indent="no" omit-xml-declaration="yes"
        */
        val expectedQuery = s"""
                               | $QUERY_DEFAULT_SERIALIZATION
                               |
                               | fn:serialize(<$IGNORABLE_WRAPPER_ELEM_NAME>$expectedXmlStr</$IGNORABLE_WRAPPER_ELEM_NAME>/child::node(), $$local:default-serialization)
                               |""".stripMargin

        connection.executeQuery(expectedQuery, true, None, None) match {
          case -\/(existServerException) =>
            ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

          case \/-(Result(-\/(queryError), errExpectedCompilationTime, errExpectedExecutionTime)) =>
            ErrorResult(testSetName, testCaseName, compilationTime + errExpectedCompilationTime, executionTime + errExpectedExecutionTime, new IllegalStateException(s"Error whilst executing XQuery for serializing expected value: ${queryError.errorCode}: ${queryError.message}"))

          case \/-(Result(\/-(expectedQueryResult), expectedQueryCompilationTime, expectedQueryExecutionTime)) if(!allAreStrings(expectedQueryResult)) =>
            ErrorResult(testSetName, testCaseName, compilationTime + expectedQueryCompilationTime, executionTime + expectedQueryExecutionTime, new IllegalStateException(s"Test case did not define nodes in assert-xml, expected (after serialization) was: ${connection.sequenceToStringAdaptive(expectedQueryResult)}"))

          case \/-(Result(\/-(expectedQueryResult), expectedQueryCompilationTime, expectedQueryExecutionTime)) =>
            /*
            Next we have to serialize the actual xml in the same way as the expectedXml
            */
            executeQueryWith$Result(connection, QUERY_ASSERT_XML_SERIALIZATION, true, None, actual) match {
              case -\/(existServerException) =>
                ErrorResult(testSetName, testCaseName, compilationTime + expectedQueryCompilationTime + existServerException.compilationTime, executionTime + expectedQueryExecutionTime + existServerException.executionTime, existServerException)

              case \/-(Result(-\/(queryError), errActualCompilationTime, errActualExecutionTime)) =>
                ErrorResult(testSetName, testCaseName, compilationTime + expectedQueryCompilationTime + errActualCompilationTime, executionTime + expectedQueryExecutionTime + errActualExecutionTime, new IllegalStateException(s"Error whilst XQuery serializing actual value: ${queryError.errorCode}: ${queryError.message}"))

              case \/-(Result(\/-(actualQueryResult), actualQueryCompilationTime, actualQueryExecutionTime)) if(!allAreStrings(actualQueryResult)) =>
                ErrorResult(testSetName, testCaseName, compilationTime + expectedQueryCompilationTime + actualQueryCompilationTime, expectedQueryExecutionTime + actualQueryExecutionTime, new IllegalStateException(s"Test case did not produce nodes in assert-xml, actual (after serialization) was: ${connection.sequenceToStringAdaptive(actualQueryResult)}"))

              case \/-(Result(\/-(actualQueryResult), actualQueryCompilationTime, actualQueryExecutionTime)) =>
                val totalCompilationTime = compilationTime + expectedQueryCompilationTime + actualQueryCompilationTime
                val totalExecutionTime = executionTime + expectedQueryExecutionTime + actualQueryExecutionTime

                try {
                  val differences = (
                    for (i <- 0 until expectedQueryResult.getItemCount)
                      yield findDifferences(expectedQueryResult.itemAt(i).asInstanceOf[StringValue].getStringValue, actualQueryResult.itemAt(0).asInstanceOf[StringValue].getStringValue())
                    ).flatten

                  if (differences.isEmpty) {
                    PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
                  } else {
                    FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"assert-xml: differences='${differences.mkString(". ")}")
                  }
                } catch {
                  // TODO(AR) temp try/catch for NPE due to a problem with XmlDiff and eXist-db's DOM(s)?
                  case npe : NullPointerException =>
                    ErrorResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, npe)
                }
            }
        }
    }
  }

  /**
    * Handles the XQTS {@code serialization-matches} assertion.
    *
    * @param connection a connection to an eXist-db server.
    * @param testSetName the name of the test-set of which the test-case is a part.
    * @param testCaseName the name of the test-case which was executed.
    * @param compilationTime the time taken to compile the XQuery.
    * @param executionTime the time taken to execute the XQuery.
    *
    * @param expected the regular expression that should match the serialialized result of the XQuery.
    * @param actual the actual result from executing the XQuery.
    *
    * @return the test result from processing the assertion.
    */
  private def serializationMatches(connection: ExistConnection, testSetName: TestSetName, testCaseName: TestCaseName, compilationTime: CompilationTime, executionTime: ExecutionTime)(expected: String \/ Path, flags: Option[String], actual: ExistServer.QueryResult): TestResult = {
    expected.map(readTextFile(_)).fold(\/-(_), r => r) match {
      case -\/(t) =>
        ErrorResult(testSetName, testCaseName, compilationTime, executionTime, t)

      case \/-(expectedRegexStr) =>
        val expectedQuery = s"""
                               | fn:matches($$result, "$expectedRegexStr", "${flags.getOrElse("")}")
                               |""".stripMargin

        val actualStr = connection.sequenceToString(actual)
        executeQueryWith$Result(connection, expectedQuery, true, None, new StringValue(actualStr)) match {
          case -\/(existServerException) =>
            ErrorResult(testSetName, testCaseName, compilationTime + existServerException.compilationTime, executionTime + existServerException.executionTime, existServerException)

          case \/-(Result(-\/(queryError), errCompilationTime, errExecutionTime)) =>
            ErrorResult(testSetName, testCaseName, compilationTime + errCompilationTime, executionTime + errExecutionTime, new IllegalStateException(s"Error whilst comparing serialization: ${queryError.errorCode}: ${queryError.message}"))

          case \/-(Result(\/-(actualQueryResult), resCompilationTime, resExecutionTime)) =>
            val totalCompilationTime = compilationTime + resCompilationTime
            val totalExecutionTime = executionTime + resExecutionTime
            if (actualQueryResult.getItemCount == 1
              && actualQueryResult.itemAt(0).isInstanceOf[BooleanValue]
              && actualQueryResult.itemAt(0).asInstanceOf[BooleanValue].effectiveBooleanValue()) {
              PassResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime)
            } else {
              FailureResult(testSetName, testCaseName, totalCompilationTime, totalExecutionTime, s"serializationMatches: expected='$expectedRegexStr', actual='$actualStr'")
            }
        }
    }
  }

  /**
    * Finds the differences between two XML documents
    *
    * @param expected the expected XML document.
    * @param actual the actual XML document.
    *
    * @return Some string describing the differences, or None of there are no differences.
    */
  private def findDifferences(expected : String, actual: String) : Option[String] = {
    val expectedSource = Input.fromString(s"<$IGNORABLE_WRAPPER_ELEM_NAME>$expected</$IGNORABLE_WRAPPER_ELEM_NAME>").build()
    val actualSource = Input.fromString(s"<$IGNORABLE_WRAPPER_ELEM_NAME>$actual</$IGNORABLE_WRAPPER_ELEM_NAME>").build()
    val diff = DiffBuilder.compare(actualSource)
      .withTest(expectedSource)
      .checkForIdentical()
      .withComparisonFormatter(ignorableWrapperComparisonFormatter)
      .checkForSimilar()
      .build()

    if (diff.hasDifferences) {
      Some(diff.toString)
    } else {
      None
    }
  }

  /**
    * Returns true if all items in the sequence are Nodes.
    *
    * @param sequence the sequence to test.
    *
    * @return true if all sequence items are nodes, false otherwise.
    */
  private def allAreNodes(sequence: Sequence) : Boolean = {
    val types = for (i <- (0 until sequence.getItemCount))
      yield sequence.itemAt(i).getType
    types.filterNot(Type.subTypeOf(_, Type.NODE)).isEmpty
  }

  /**
    * Returns true if all items in the sequence are Strings.
    *
    * @param sequence the sequence to test.
    *
    * @return true if all sequence items are strings, false otherwise.
    */
  private def allAreStrings(sequence: Sequence) : Boolean = {
    val types = for (i <- (0 until sequence.getItemCount))
      yield sequence.itemAt(i).getType
    types.filterNot(Type.subTypeOf(_, Type.STRING)).isEmpty
  }

  /**
    * Reads the content of a text file.
    *
    * NOTE: this function does not use any caching, and
    * should only be used where resources will not be used
    * repeatedly. If you want caching, you should instead
    * use the {@link CommonResourceCacheActor}.
    *
    * @param path the path to the file.
    * @param charset the character encoding of the file; Defaults to UTF-8.
    *
    * @return the content of the file as a string, or an exception.
    */
  private def readTextFile(path: Path, charset: Charset = UTF_8) : IOException \/ String = {
    IO {
      \/.fromTryCatchThrowable[String, IOException] {
        new String(Files.readAllBytes(path), charset)
      }
    }.unsafeRunSync()
  }

  /**
    * Executes an XQuery where the external
    * variable {@code $result} is bound to a provided
    * sequence (typically the result of a previous
    * query execution).
    *
    * @param connection a connection to an eXist-db server.
    * @param query the XQuery to execute.
    * @param cacheCompiled true if the compiled form of the XQuery should be cached.
    * @param contextSequence an optional context sequence for the XQuery to operate on.
    * @param $result the sequence to be bound to the {@code $result} variable.
    *
    * @return the result or executing the query, or an exception.
    */
  private def executeQueryWith$Result(connection: ExistConnection, query: String, cacheCompiled: Boolean, contextSequence: Option[Sequence], $result: Sequence) = {
    connection.executeQuery(query, cacheCompiled, None, contextSequence, Seq.empty, Seq.empty, Seq.empty, Seq(RESULT_VARIABLE_NAME -> $result))
  }

  /**
    * Formats a failure message.
    *
    * @param expectedResult the expected result.
    * @param actual the actual result.
    *
    * @return the formatted failure message.
    */
  private def failureMessage(expectedResult: XQTSParserActor.Result, actual: QueryError): String = {
    s"Expected: '$expectedResult', but query returned an error: $actual"
  }

  /**
    * Formats a failure message.
    *
    * @param connection a connection to an eXist-db server; Used for serialization of the actual results.
    * @param expectedResult the expected result.
    * @param actual the actual result.
    *
    * @return the formatted failure message.
    */
  private def failureMessage(connection: ExistConnection)(expectedResult: XQTSParserActor.Result, actual: ExistServer.QueryResult): String = {
    try {
      s"Expected: '$expectedResult', but query returned: '${connection.sequenceToStringAdaptive(actual)}'"
    } catch {
      // TODO(AR) temp try/catch for NPE due to a problem with OrderedValueSequence.toString
      case npe : NullPointerException =>
        npe.toString
    }
  }
}

object TestCaseRunnerActor {
  case class RunTestCase(testSetRef: TestSetRef, testCase: TestCase, manager: ActorRef)

  private case class RunTestCaseInternal(runTestCase: RunTestCase, resolvedEnvironment: ResolvedEnvironment)

  /**
    * The result of executing an XQTS test-case.
    */
  sealed trait TestResult {
    def testSet: String
    def testCase: String
    def compilationTime: CompilationTime
    def executionTime: ExecutionTime
  }
  case class PassResult(testSet: String, testCase: String, compilationTime: CompilationTime, executionTime: ExecutionTime) extends TestResult
  case class AssumptionFailedResult(testSet: String, testCase: String, compilationTime: CompilationTime, executionTime: ExecutionTime, reason: String) extends TestResult
  case class FailureResult(testSet: String, testCase: String, compilationTime: CompilationTime, executionTime: ExecutionTime, reason: String) extends TestResult
  case class ErrorResult(testSet: String, testCase: String, compilationTime: CompilationTime, executionTime: ExecutionTime, t: Throwable) extends TestResult

  private val RESULT_VARIABLE_NAME = "result"
  private val QUERY_NORMALIZED_SPACE = "normalize-space($result)"
  private val QUERY_ASSERT_STRING_VALUE_NORMALIZED_SPACE = """normalize-space(string-join(for $r in $result return string($r), " "))"""
  private val QUERY_ASSERT_STRING_VALUE = """string-join(for $r in $result return string($r), " ")"""
  private val QUERY_DEFAULT_SERIALIZATION = """
                                              |xquery version "3.1";
                                              |declare namespace output = "http://www.w3.org/2010/xslt-xquery-serialization";
                                              |
                                              |declare variable $local:default-serialization :=
                                              |  <output:serialization-parameters>
                                              |    <output:method value="xml"/>
                                              |    <output:indent value="no"/>
                                              |    <output:omit-xml-declaration value="yes"/>
                                              |  </output:serialization-parameters>;
                                              |
                                              |""".stripMargin
  private val QUERY_ASSERT_XML_SERIALIZATION = s"""
                                                  | $QUERY_DEFAULT_SERIALIZATION
                                                  |
                                                  | fn:serialize($$result, $$local:default-serialization)
                                                  |""".stripMargin

  private lazy val ignorableWrapperComparisonFormatter = new IgnorableWrapperComparisonFormatter()

  /*
   Resolved Environment resources
   */
  case class ResolvedSchema(path: Path, data: Array[Byte])
  case class ResolvedSource(path: Path, data: Array[Byte])
  case class ResolvedResource(path: Path, data: Array[Byte])
  case class ResolvedEnvironment(resolvedSchemas: Seq[ResolvedSchema] = Seq.empty, resolvedSources: Seq[ResolvedSource] = Seq.empty, resolvedResources: Seq[ResolvedResource] = Seq.empty, resolvedQuery: Option[String] = None)
  case class PendingTestCase(runTestCase: RunTestCase, resolvedEnvironment: ResolvedEnvironment)

  private def merge(existing: Map[Path, Seq[TestCaseId]])(id: TestCaseId, additions: List[{ def file: Path}]) : Map[Path, Seq[TestCaseId]] = {
    additions.foldLeft(existing){(accum, x) =>
      accum + (x.file -> (accum.get(x.file).getOrElse(Seq.empty) :+ id))
    }
  }

  private def merge1(existing: Map[Path, Seq[TestCaseId]])(id: TestCaseId, addition: Path) : Map[Path, Seq[TestCaseId]] = {
    existing + (addition -> (existing.get(addition).getOrElse(Seq.empty) :+ id))
  }

  private def addIfNotPresent(existing: Map[TestCaseId, PendingTestCase])(runTestCase: RunTestCase) : Map[TestCaseId, PendingTestCase] = {
    val testCaseId = (runTestCase.testSetRef.name, runTestCase.testCase.name)
    existing.get(testCaseId) match {
      case Some(_) => existing
      case None => existing + (testCaseId -> PendingTestCase(runTestCase, ResolvedEnvironment()))
    }
  }

  private def addSchemas(pending: Map[TestCaseId, PendingTestCase])(testCases: Seq[TestCaseId], path: Path, value: Array[Byte]) : Map[TestCaseId, PendingTestCase] = {
    testCases.foldLeft(pending){(accum, x) =>
      val pendingTestCase = accum(x)
      accum + (x -> pendingTestCase.copy(resolvedEnvironment = pendingTestCase.resolvedEnvironment.copy(resolvedSchemas = pendingTestCase.resolvedEnvironment.resolvedSchemas :+ ResolvedSchema(path, value))))
    }
  }

  private def addSources(pending: Map[TestCaseId, PendingTestCase])(testCases: Seq[TestCaseId], path: Path, value: Array[Byte]) : Map[TestCaseId, PendingTestCase] = {
    testCases.foldLeft(pending){(accum, x) =>
      val pendingTestCase = accum(x)
      accum + (x -> pendingTestCase.copy(resolvedEnvironment = pendingTestCase.resolvedEnvironment.copy(resolvedSources = pendingTestCase.resolvedEnvironment.resolvedSources :+ ResolvedSource(path, value))))
    }
  }

  private def addResources(pending: Map[TestCaseId, PendingTestCase])(testCases: Seq[TestCaseId], path: Path, value: Array[Byte]) : Map[TestCaseId, PendingTestCase] = {
    testCases.foldLeft(pending){(accum, x) =>
      val pendingTestCase = accum(x)
      accum + (x -> pendingTestCase.copy(resolvedEnvironment = pendingTestCase.resolvedEnvironment.copy(resolvedResources = pendingTestCase.resolvedEnvironment.resolvedResources :+ ResolvedResource(path, value))))
    }
  }

  private def addQueryStrs(pending: Map[TestCaseId, PendingTestCase])(testCases: Seq[TestCaseId], path: Path, value: Array[Byte]) : Map[TestCaseId, PendingTestCase] = {
    testCases.foldLeft(pending){(accum, x) =>
      val pendingTestCase = accum(x)
      accum + (x -> pendingTestCase.copy(resolvedEnvironment = pendingTestCase.resolvedEnvironment.copy(resolvedQuery = Some(new String(value, UTF_8)))))
    }
  }

  private def notIn(existing: Map[Path, Seq[TestCaseId]])(testCase: TestCaseId) : Boolean = {
    !existing.values.flatten.toSeq.contains(testCase)
  }
}

private object IgnorableWrapper {
  val IGNORABLE_WRAPPER_ELEM_NAME = "ignorable-wrapper"
  lazy val IGNORABLE_WRAPPER_XPATH_PREFIX = Pattern.compile("""^/ignorable-wrapper(?:\[[0-9]+\])?""")
}

/**
  * Exactly the same as {@link DefaultComparisonFormatter#getDescription(Comparison)}
  * except that we drop the leading "/ignorable-wrapper" from the XPath.
  */
private class IgnorableWrapperComparisonFormatter extends DefaultComparisonFormatter {

  private lazy val MTC_IGNORABLE_WRAPPER_XPATH_PREFIX = IGNORABLE_WRAPPER_XPATH_PREFIX.matcher("")

  private def abbridgeXPath(xpath: String) : String = {
    MTC_IGNORABLE_WRAPPER_XPATH_PREFIX.reset(xpath).replaceFirst("") match {
      case empty if empty.isEmpty => "/"
      case xpath => xpath
    }
  }

  override def getDescription(difference: Comparison): String = {
    val `type` = difference.getType
    val description = `type`.getDescription
    val controlDetails = difference.getControlDetails
    val testDetails = difference.getTestDetails

    val controlXPath = abbridgeXPath(controlDetails.getXPath)
    val testXPath = abbridgeXPath(testDetails.getXPath)

    val controlTarget = getShortString(controlDetails.getTarget, controlXPath, `type`)
    val testTarget = getShortString(testDetails.getTarget, testXPath, `type`)
    if (`type` eq ComparisonType.ATTR_NAME_LOOKUP) {
      String.format("Expected %s '%s' - comparing %s to %s", description, controlXPath, controlTarget, testTarget)
    } else {
      String.format("Expected %s '%s' but was '%s' - comparing %s to %s", description, getValue(controlDetails.getValue, `type`), getValue(testDetails.getValue, `type`), controlTarget, testTarget)
    }
  }
}
