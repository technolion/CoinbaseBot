# CoinbaseBot

## Summary

CoinbaseBot is a project which autonomously trades Crypto currency on Coinbase. It uses the [Coinbase Advanced API](https://www.coinbase.com/developer-platform/products/advanced-trade-api).

## Functionality

The bot uses a simple strategy to buy when the market is down, holds the coins, and sells when a certain profit level is reached or when the stop loss price has been underpassed.

The following rules apply:

* A coin is initially bought when the current market price is down for a configurable percentage (e.g. 2.0 %)compared to 24 hours before
* When a coin is bought a stop loss price is set, depending on a configurable stop loss percentage (e.g. 10%)
* A configurable portion of the cash currency USDC is being used per initial purchase (e.g. 0.05 meaning 5%) 
* The bot buys as many different currency as defined in the configuration (e.g. max. 4)
* The current market price for every coin is checked every 30 seconds
* If the market price falls below the purchase price the bot tries to average down the purchase price by buying the same amount of the held coin at a lower price. Multiple levels of averaging down stages can be configured (e.g. 2.0%, 4.0%, 6.0%)
* If the market price rises above the purchase price, the stop loss price is increased (trailing stop loss)
* The bot records reached profit levels per held coin. These levels are configurable (e.g. 3%, 4%, 5%, 6%)
* If the market price of a held coin drops below the previous profit level, the bot sells the coin, cashing in the profit.

## Configuration

you need a `config.json` file. I am using these values. Play with them and find the values that fit best for your trading expectations:

```
{
  "apiKey": "organizations/xxx/apiKeys/yyy",
  "apiSecret": "-----BEGIN EC PRIVATE KEY-----\nxxxyyyzzz\n",
  "portfolioId": "123-456-789",
  "coins": ["BTC", "SOL", "ETH", "XRP", "DOGE", "HBAR", "SUI", "XLM", "ADA", "AVAX", "SHIB", "LINK", "DOT", "BCH", "UNI", "LTC", "NEAR", "LDO"],
  "purchaseDropPercent": 2.0,
  "maxHeldCoins": 4,
  "useFundsPortionPerTrade": 0.05,
  "trailingStopLossPercent": 12.0,
  "profitLevels": [0.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0],
  "averageDownSteps": [2.0, 4.0, 6.0],
  "logLevel": "INFO"
}
```

## Logging and persistence

The bot logs everything matching the configured log level into a file called `trading.log`. Log output below the configured log level it displayed on the comman line when starting the bot.
The currently held assets are stored in a file called `currentAssets.json`.

## Building and starting

The code uses the advanced-sdk-java from Coinbase's github repository. Unfortunately it is not well maintained so I had to fork the project [here](https://github.com/technolion/advanced-sdk-java). You need to clone that repository, too and build it with 

`mvn clean install`

Then witch to the CoinbaseBot repository and build the main project with

`mvn clean install`

You can start it with

`java -jar target/CoinbaseBot-1.0-SNAPSHOT-jar-with-dependencies.jar`

It is recommended to use a tool to put the process into the background such as [screen](https://wiki.debian.org/screen) in order to leave the shell without stopping the bot.

## Contributions

You are welcome to report issues or submit PR.

