package com.pagerduty.akka.http.aggregator.aggregator

import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import com.pagerduty.akka.http.aggregator.AggregatorUpstream
import com.pagerduty.akka.http.requestauthentication.model.AuthenticationConfig
import ujson.Js

/**
  * This Aggregator can be used when the gateway needs to make multiple parallel requests upstream based on the result
  * of an initial upstream request. All upstream responses are assumed to be JSON, and the gateway builds the outgoing response
  * based on the response to the initial request and the responses to the subsequent upstream requests.
  *
  * Motivating Use Case:
  * - Fetch an entity from a service, then hydrate the entities' objects from another service, then build a single
  *   response with hydrated objects instead of object references
  *
  * Assumptions:
  * - A single upstream request should be made first
  * - The response to that request is JSON
  * - Many parallel requests will be made based on that initial response, if that response has a 200 OK status code
  * - Those responses are also JSON
  * - It's OK if any failure to parse JSON will result in a 500 to the client
  *
  * If any of these assumptions are untrue, look at using a supertype of this trait instead. Also, tell #core about your
  * use case!
  */
trait TwoStepJsonHydrationAggregator[AddressingConfig]
    extends TwoStepAggregator[String,
                              Either[HttpResponse, Js.Value],
                              AddressingConfig] {

  // implement these three methods
  def handleIncomingRequest(incomingRequest: HttpRequest)
    : (AggregatorUpstream[AddressingConfig], HttpRequest)

  def handleJsonUpstreamResponse(upstreamResponse: HttpResponse,
                                 upstreamJson: Js.Value): RequestMap

  def buildOutgoingJsonResponse(
      initialUpstreamJson: Js.Value,
      upstreamResponseMap: Map[String, (HttpResponse, Js.Value)]
  ): HttpResponse

  // the rest is internal implementation
  private val emptyState = ujson.read("{}")
  private val initialRequestKey = "initial"

  def handleIncomingRequest(
      authConfig: AuthenticationConfig
  )(incomingRequest: HttpRequest, authData: authConfig.AuthData)
    : (Either[HttpResponse, Js.Value], RequestMap) = {
    val initialRequest = handleIncomingRequest(incomingRequest)
    (Right(emptyState), Map(initialRequestKey -> initialRequest))
  }

  def handleUpstreamResponses(
      initialState: Either[HttpResponse, Js.Value],
      upstreamResponseMap: ResponseMap
  ): (Either[HttpResponse, Js.Value], RequestMap) = {
    val (upstreamResponse, upstreamEntity) = upstreamResponseMap(
      initialRequestKey)

    if (upstreamResponse.status == StatusCodes.OK) {
      val upstreamResponseJson = ujson.read(upstreamEntity)
      val requests =
        handleJsonUpstreamResponse(upstreamResponse, upstreamResponseJson)
      (Right(upstreamResponseJson), requests)
    } else {
      (Left(upstreamResponse), Map())
    }
  }

  def buildOutgoingResponse(
      accumulatedState: Either[HttpResponse, Js.Value],
      upstreamResponses: ResponseMap
  ): HttpResponse = {
    accumulatedState match {
      case Right(initialUpstreamJson) =>
        val jsonResponseMap = upstreamResponses.map {
          case (requestKey, (response, entity)) =>
            (requestKey, (response, ujson.read(entity)))
        }

        buildOutgoingJsonResponse(initialUpstreamJson, jsonResponseMap)
      case Left(failedInitialResponse) =>
        failedInitialResponse
    }
  }
}
