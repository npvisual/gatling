/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.metrics

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

import io.gatling.core.action.{ BaseActor, system }
import io.gatling.core.action.system.dispatcher
import io.gatling.core.config.GatlingConfiguration.configuration
import io.gatling.core.result.message._
import io.gatling.core.result.writer.DataWriter
import io.gatling.core.util.TimeHelper.nowSeconds
import io.gatling.metrics.sender.MetricsSender
import io.gatling.metrics.types.{ Metrics, RequestMetrics, UserMetric }

import akka.actor.Props

class GraphiteDataWriter extends DataWriter {

	private val graphiteSender = context.actorOf(Props(new GraphiteSender))
	private val rootPathPrefix = configuration.data.graphite.rootPathPrefix.split('.').toList
	private var metricRootPath: List[String] = Nil
	private val allRequests = new RequestMetrics
	private val perRequest = mutable.Map.empty[List[String], RequestMetrics]
	private var allUsers: UserMetric = _
	private val usersPerScenario = mutable.Map.empty[String, UserMetric]
	private val percentiles1 = configuration.charting.indicators.percentile1
	private val percentiles1Name = "percentiles" + percentiles1
	private val percentiles2 = configuration.charting.indicators.percentile2
	private val percentiles2Name = "percentiles" + percentiles2

	def onInitializeDataWriter(run: RunMessage, scenarios: Seq[ShortScenarioDescription]) {
		metricRootPath = rootPathPrefix :+ run.simulationId
		allUsers = new UserMetric(scenarios.map(_.nbUsers).sum)
		scenarios.foreach(scenario => usersPerScenario += scenario.name -> new UserMetric(scenario.nbUsers))
		system.scheduler.schedule(0 millisecond, 1000 milliseconds, self, Send)
	}

	def onScenarioMessage(scenario: ScenarioMessage) {
		usersPerScenario(scenario.scenarioName).update(scenario)
		allUsers.update(scenario)
	}

	def onGroupMessage(group: GroupMessage) {}

	def onRequestMessage(request: RequestMessage) {
		if (!configuration.data.graphite.light) {
			val path = request.name :: request.groupStack.map(_.name)
			val metric = perRequest.getOrElseUpdate(path.reverse, new RequestMetrics)
			metric.update(request)
		}
		allRequests.update(request)
	}

	def onFlushDataWriter {
		graphiteSender ! Flush
	}

	override def receive = uninitialized

	override def initialized: Receive = super.initialized.orElse {
		case m => graphiteSender forward m
	}

	private class GraphiteSender extends BaseActor {

		private val sanitizeStringMemo = mutable.Map.empty[String, String]
		private val sanitizeStringListMemo = mutable.Map.empty[List[String], List[String]]
		private var metricsSender: MetricsSender = _

		override def preStart {
			metricsSender = MetricsSender.newMetricsSender
		}

		def receive = {
			case Send => sendMetricsToGraphite(nowSeconds)
			case Flush => metricsSender.flush
		}

		private def sendMetricsToGraphite(epoch: Long) {

			def sanitizeString(s: String) = sanitizeStringMemo.getOrElseUpdate(s, s.replace(' ', '_').replace('.', '-').replace('\\', '-'))

			def sanitizeStringList(list: List[String]) = sanitizeStringListMemo.getOrElseUpdate(list, list.map(sanitizeString))

			def sendToGraphite(metricPath: MetricPath, value: Long) = metricsSender.sendToGraphite(metricPath.toString, value, epoch)

			def sendUserMetrics(scenarioName: String, userMetric: UserMetric) {
				val rootPath = MetricPath(List("users", sanitizeString(scenarioName)))
				sendToGraphite(rootPath + "active", userMetric.active)
				sendToGraphite(rootPath + "waiting", userMetric.waiting)
				sendToGraphite(rootPath + "done", userMetric.done)
			}

			def sendMetrics(metricPath: MetricPath, metrics: Metrics) {
				sendToGraphite(metricPath + "count", metrics.count)

				if (metrics.count > 0L) {
					sendToGraphite(metricPath + "max", metrics.max)
					sendToGraphite(metricPath + "min", metrics.min)
					sendToGraphite(metricPath + percentiles1Name, metrics.getQuantile(percentiles1))
					sendToGraphite(metricPath + percentiles2Name, metrics.getQuantile(percentiles2))
				}
			}

			def sendRequestMetrics(path: List[String], requestMetrics: RequestMetrics) {
				val rootPath = MetricPath(sanitizeStringList(path))

				val (okMetrics, koMetrics, allMetrics) = requestMetrics.metrics

				sendMetrics(rootPath + "ok", okMetrics)
				sendMetrics(rootPath + "ko", koMetrics)
				sendMetrics(rootPath + "all", allMetrics)

				requestMetrics.reset
			}

			sendUserMetrics("allUsers", allUsers)
			for ((scenarioName, userMetric) <- usersPerScenario) sendUserMetrics(scenarioName, userMetric)

			sendRequestMetrics(List("allRequests"), allRequests)
			if (!configuration.data.graphite.light)
				for ((path, requestMetric) <- perRequest) sendRequestMetrics(path, requestMetric)

			metricsSender.flush
		}
	}

	private object MetricPath {

		def apply(elements: List[String]) = new MetricPath(metricRootPath ::: elements)
	}

	private class MetricPath(path: List[String]) {

		def +(element: String) = new MetricPath(path :+ element)

		override def toString = path.mkString(".")
	}
}