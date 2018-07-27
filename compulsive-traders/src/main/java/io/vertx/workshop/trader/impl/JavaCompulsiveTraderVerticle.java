package io.vertx.workshop.trader.impl;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.servicediscovery.types.EventBusService;
import io.vertx.servicediscovery.types.MessageSource;
import io.vertx.workshop.common.MicroServiceVerticle;
import io.vertx.workshop.portfolio.PortfolioService;

/**
 * A compulsive trader...
 */
public class JavaCompulsiveTraderVerticle extends MicroServiceVerticle {

  @Override
  public void start(Future<Void> future) {
    super.start();

    String company = TraderUtils.pickACompany();
    int shares = TraderUtils.pickANumber();

    Future<MessageConsumer<JsonObject>> marketFuture = Future.future();
    Future<PortfolioService> portfolioServiceFuture = Future.future();

    MessageSource.getConsumer(discovery, new JsonObject().put("name", "market-data"), marketFuture.completer());
    EventBusService.getProxy(discovery, PortfolioService.class, portfolioServiceFuture.completer());

    CompositeFuture.all(marketFuture, portfolioServiceFuture).setHandler(ar->{
      if (ar.failed()) {
        future.fail(ar.cause());
      } else {
        MessageConsumer<JsonObject> marketConsumer = marketFuture.result();
        PortfolioService portfolioService = portfolioServiceFuture.result();

        marketConsumer.handler(message->{
          JsonObject quote = message.body();
          TraderUtils.dumbTradingLogic(company, shares, portfolioService, quote);
        });
        future.complete();
      }
    });
  }


}
