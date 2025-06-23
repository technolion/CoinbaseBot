# CoinbaseBot

## Summary

CoinbaseBot is a project which autonomously trades Crypto currency on Coinbase. It uses the [Coinbase Advanced API](https://www.coinbase.com/developer-platform/products/advanced-trade-api).

## Functionality

The bot uses a simple strategy to buy when the market is down, holds the coins, and sells when a certain profit level is reached or when the coin is held longer than a week, accepting losses but freeing liquidity.

The following rules apply:

* A coin is bought when the current market price is down for a configurable percentage (`purchaseDropPercent` e.g. 3.0 %) compared to 24 hours before
* A configurable portion of the cash currency USDC is being used per initial purchase (`useFundsPortionPerTrade` e.g. 0.045 meaning 4.5%) 
* The bot buys as many different currency as defined in the configuration (`maxHeldCoins` e.g. 4)
* The current market price for every coin is checked every 15 seconds
* If the market price falls below the purchase price the bot tries to average down the purchase price by buying the same amount of the held coin at a lower price. Multiple levels of averaging down stages can be configured (`averageDownSteps`)
* The bot records reached profit levels per held coin. These levels are configurable (`profitLevels`)
* If the market price of a held coin drops below the previous profit level and if this level is equal or higher than the configurable minimum profit level (`minimumProfitLevelForRegularSale`), the bot sells the coin, cashing in the profit.
* If a coin is held longer than three weeks and the current price is below the average purchase price, the bot sells the coin accepting the following losses:
  * after 3 week with 0% profit/loss
  * after 4 weeks with 1% loss
  * after 5 weeks with 2% loss
  * and so on. This can be configured with `negativeProfitLevels`.
* The bot always uses the average purchase price for a coin, when comparing against market prices. For example
  * A coin was bought for 100 UDSC
  * The market price falls to 97 USDC (3% to the initial purchase price) triggering a second purchase of the same coin (averaging down). The average purchase price is now 98 USDC.
  * The market price falls to 95 USDC (5% to the initial purchase price, but but less than 5% to the average purchase price) NOT triggering a third purchase of the same coin.
  * The market price falls to 92,15 USDC (5% to the average purchase price) triggering a third purchase of the same coin (averaging down). The average purchase price is now 95,075 USDC.
  * The market price rises to 96,03 USDC (more than 1% above the average purchase price), not triggering a sale, because this coin was averaged down 2 times but not 3 times.
  * The market price rises to 98,878 USDC (4% above the average purchase price). This profit level is recorded.
  * The market price falls to 97,356 UDSC (less thank 2,5% above the average purchase price). The bit sells the coin, because the market price dropped below a previously reached profit level, and this level is the minimum level for regular sale.

## Configuration

you need a `config.json` file. I am using these values. Play with them and find the values that fit best for your trading expectations:

```
{
  "apiKey": "organizations/xxx/apiKeys/yyy",
  "apiSecret": "-----BEGIN EC PRIVATE KEY-----\nxxxyyyzzz\n",
  "portfolioId": "123-456-789",
  "coins": ["BTC", "SOL", "ETH", "XRP", "DOGE", "HBAR", "SUI", "XLM", "ADA", "AVAX", "LINK", "DOT", "BCH", "UNI", "LTC", "NEAR"],
  "purchaseDropPercent": 3.5,
  "maxHeldCoins": 4,
  "useFundsPortionPerTrade": 0.04,
  "profitLevels": [0.0, 1.5, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0],
  "negativeProfitLevels": [1.0, 2.0, 3.0, 4.0, 5.0],
  "averageDownSteps": [0.0, 4.0, 6.0, 8.0],
  "minimumProfitLevelForRegularSale": 2,
  "takerFeePercentage": 0.4,
  "logLevel": "INFO",
  "timeZone": "Europe/Berlin"
}
```

## Logging and persistence

The bot logs everything matching the configured log level into a file called `trading.log`. Log output below the configured log level it displayed on the comman line when starting the bot.
The currently held assets are stored in a file called `currentAssets.json`.

## Building and starting

Switch to the CoinbaseBot repository and build the main project with

`mvn clean install`

You can start it with

`java -jar target/CoinbaseBot-1.0-SNAPSHOT-jar-with-dependencies.jar`

It is recommended to use a tool to put the process into the background such as [screen](https://wiki.debian.org/screen) in order to leave the shell without stopping the bot.

## Contributions

You are welcome to report issues or submit PR.

