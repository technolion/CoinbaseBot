# CoinbaseBot

## Summary

CoinbaseBot is a project which autonomously trades Crypto currency on Coinbase. It uses the [Coinbase Advanced API](https://www.coinbase.com/developer-platform/products/advanced-trade-api).

## Functionality

The bot uses a simple strategy to buy when the market is down, holds the coins, and sells when a certain profit level is reached or when the stop loss price has been underpassed.

The following rules apply:

* A coin is bought when the current market price is down for a configurable percentage (`purchaseDropPercent` e.g. 3.0 %) compared to 24 hours before
* When a coin is bought a stop loss price is set, depending on a configurable stop loss percentage (`trailingStopLossPercent` e.g. 10%)
* A configurable portion of the cash currency USDC is being used per initial purchase (`useFundsPortionPerTrade` e.g. 0.045 meaning 4.5%) 
* The bot buys as many different currency as defined in the configuration (`maxHeldCoins` e.g. 4)
* The current market price for every coin is checked every 30 seconds
* If the market price falls below the purchase price the bot tries to average down the purchase price by buying the same amount of the held coin at a lower price. Multiple levels of averaging down stages can be configured (`averageDownSteps`)
* If the market price rises above the purchase price, the stop loss price is increased (trailing stop loss)
* If a coin has been averaged down on the last available step and then reaches the configurable recovery profit level (`profitLevelForRecoverySale`, e.g. 1), then a recovery sale is initiated to free funds and lower risk.
* The bot records reached profit levels per held coin. These levels are configurable (`profitLevels`)
* If the market price of a held coin drops below the previous profit level and if this level is equal or higher than the configurable minimum profit level (`minimumProfitLevelForRegularSale`), the bot sells the coin, cashing in the profit.
* If a stop loss sale is done, the bot halts any new purchases until the whole market recovers (the average price increase over 24 hours for all configured coins is higher than `marketRecoveryPercent`)
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
  "purchaseDropPercent": 3.0,
  "maxHeldCoins": 4,
  "useFundsPortionPerTrade": 0.045,
  "trailingStopLossPercent": 10.0,
  "profitLevels": [0.0, 1.0, 2.5, 4.0, 5.5, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0],
  "averageDownSteps": [0.0, 3.0, 5.0, 7.0],
  "minimumProfitLevelForRegularSale": 2,
  "profitLevelForRecoverySale": 1,
  "marketRecoveryPercent": 3.0,
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

