package io.vertx.workshop.portfolio.impl;

import io.vertx.core.*;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.ServiceDiscovery;
import io.vertx.servicediscovery.types.HttpEndpoint;
import io.vertx.workshop.portfolio.Portfolio;
import io.vertx.workshop.portfolio.PortfolioService;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.stream.Collectors;

import io.vertx.ext.web.client.WebClient;

/**
 * The portfolio service implementation.
 */
public class PortfolioServiceImpl implements PortfolioService {

  private final Vertx vertx;
  private final Portfolio portfolio;
  private final ServiceDiscovery discovery;

  public PortfolioServiceImpl(Vertx vertx, ServiceDiscovery discovery, double initialCash) {
    this.vertx = vertx;
    this.portfolio = new Portfolio().setCash(initialCash);
    this.discovery = discovery;
  }

  @Override
  public void getPortfolio(Handler<AsyncResult<Portfolio>> resultHandler) {
    resultHandler.handle(Future.succeededFuture(portfolio));
  }

  private void sendActionOnTheEventBus(String action, int amount, JsonObject quote, int newAmount) {
    JsonObject jsonObject = new JsonObject();
    jsonObject.put("action", action);
    jsonObject.put("quote", quote);
    jsonObject.put("amount", amount);
    jsonObject.put("date", System.currentTimeMillis());
    jsonObject.put("owned", newAmount);

    vertx.eventBus().publish(EVENT_ADDRESS, jsonObject);
  }

  @Override
  public void evaluate(Handler<AsyncResult<Double>> resultHandler) {
    HttpEndpoint.getClient(discovery, new JsonObject().put("name", "quotes"), httpClientAsyncResult -> {
      if (httpClientAsyncResult.failed()) {
        resultHandler.handle(Future.failedFuture(httpClientAsyncResult.cause()));
      } else {
        HttpClient httpClient = httpClientAsyncResult.result();
        computeEvaluation(httpClient, resultHandler);
      }
    });
  }

  private void computeEvaluation(HttpClient httpClient, Handler<AsyncResult<Double>> resultHandler) {
    // We need to call the service for each company we own shares
    List<Future> results = portfolio.getShares().entrySet().stream()
        .map(entry -> getValueForCompany(httpClient, entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());

    // We need to return only when we have all results, for this we create a composite future. The set handler
    // is called when all the futures has been assigned.
    CompositeFuture.all(results).setHandler(
        ar -> {
          double sum = results.stream().mapToDouble(fut -> (double) fut.result()).sum();
          resultHandler.handle(Future.succeededFuture(sum));
        });
  }

  private Future<Double> getValueForCompany(HttpClient httpClient, String company, int numberOfShares) {
    // Create the future object that will  get the value once the value have been retrieved
    Future<Double> future = Future.future();

    httpClient.get("/?name=" + company, httpClientResponse -> {
      if (httpClientResponse.statusCode() == 2000) {
        httpClientResponse.bodyHandler(buffer->{
          double v = numberOfShares * buffer.toJsonObject().getDouble("bid");
          future.complete(v);
        });
      } else {
        future.complete(0.0);
      }
    }).exceptionHandler(future::fail).end();

    return future;
  }


  @Override
  public void buy(int amount, JsonObject quote, Handler<AsyncResult<Portfolio>> resultHandler) {
    if (amount <= 0) {
      resultHandler.handle(Future.failedFuture("Cannot buy " + quote.getString("name") + " - the amount must be " +
          "greater than 0"));
      return;
    }

    if (quote.getInteger("shares") < amount) {
      resultHandler.handle(Future.failedFuture("Cannot buy " + amount + " - not enough " +
          "stocks on the market (" + quote.getInteger("shares") + ")"));
      return;
    }

    double price = amount * quote.getDouble("ask");
    String name = quote.getString("name");
    // 1) do we have enough money
    if (portfolio.getCash() >= price) {
      // Yes, buy it
      portfolio.setCash(portfolio.getCash() - price);
      int current = portfolio.getAmount(name);
      int newAmount = current + amount;
      portfolio.getShares().put(name, newAmount);
      sendActionOnTheEventBus("BUY", amount, quote, newAmount);
      resultHandler.handle(Future.succeededFuture(portfolio));
    } else {
      resultHandler.handle(Future.failedFuture("Cannot buy " + amount + " of " + name + " - " + "not enough money, " +
          "need " + price + ", has " + portfolio.getCash()));
    }
  }


  @Override
  public void sell(int amount, JsonObject quote, Handler<AsyncResult<Portfolio>> resultHandler) {
    if (amount <= 0) {
      resultHandler.handle(Future.failedFuture("Cannot sell " + quote.getString("name") + " - the amount must be " +
          "greater than 0"));
      return;
    }

    double price = amount * quote.getDouble("bid");
    String name = quote.getString("name");
    int current = portfolio.getAmount(name);
    // 1) do we have enough stocks
    if (current >= amount) {
      // Yes, sell it
      int newAmount = current - amount;
      if (newAmount == 0) {
        portfolio.getShares().remove(name);
      } else {
        portfolio.getShares().put(name, newAmount);
      }
      portfolio.setCash(portfolio.getCash() + price);
      sendActionOnTheEventBus("SELL", amount, quote, newAmount);
      resultHandler.handle(Future.succeededFuture(portfolio));
    } else {
      resultHandler.handle(Future.failedFuture("Cannot sell " + amount + " of " + name + " - " + "not enough stocks " +
          "in portfolio"));
    }

  }

  private static String encode(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException("Unsupported encoding");
    }
  }


}
