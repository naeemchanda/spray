/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.can.server

import spray.can.rendering.ResponsePartRenderingContext
import spray.can.server.RequestParsing.HttpMessageStartEvent
import spray.io._
import spray.http._
import spray.can.Http

private object RequestChunkAggregation {

  def apply(limit: Int): PipelineStage =
    new PipelineStage {
      def apply(context: PipelineContext, commandPL: CPL, eventPL: EPL): Pipelines =
        new Pipelines with DynamicEventPipeline {
          val commandPipeline = commandPL

          val initialEventPipeline: EPL = {
            case ev @ HttpMessageStartEvent(ChunkedRequestStart(request), _) ⇒
              eventPipeline.become(aggregating(ev, request, HttpData.newBuilder += request.entity.data))

            case ev ⇒ eventPL(ev)
          }

          def aggregating(mse: HttpMessageStartEvent, request: HttpRequest, builder: HttpData.Builder): EPL = {
            case Http.MessageEvent(MessageChunk(data, _)) ⇒
              if (builder.byteCount + data.length <= limit)
                builder += data
              else closeWithError()

            case Http.MessageEvent(_: ChunkedMessageEnd) ⇒
              val contentType = request.header[HttpHeaders.`Content-Type`] match {
                case Some(x) ⇒ x.contentType
                case None    ⇒ ContentTypes.`application/octet-stream`
              }
              val aggregatedRequest = request.copy(entity = HttpEntity(contentType, builder.result()))
              eventPL(mse.copy(messagePart = aggregatedRequest))
              eventPipeline.become(initialEventPipeline)

            case ev ⇒ eventPL(ev)
          }

          def closeWithError(): Unit = {
            val msg = "Aggregated request entity greater than configured limit of " + limit + " bytes"
            context.log.error(msg + ", closing connection")
            commandPL(ResponsePartRenderingContext(HttpResponse(StatusCodes.RequestEntityTooLarge, msg)))
            commandPL(Http.Close)
            eventPipeline.become(eventPL) // disable this stage
          }
        }
    }
}