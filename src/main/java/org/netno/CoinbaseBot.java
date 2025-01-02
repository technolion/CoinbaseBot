package org.netno;

import com.coinbase.advanced.client.CoinbaseAdvancedClient;
import com.coinbase.advanced.credentials.CoinbaseAdvancedCredentials;
import org.json.JSONObject;

import java.util.List;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

public class CoinbaseBot {

    private static final String CONFIG_FILE = "config.json";
    private static Config config;
    private static CoinbaseAdvancedClient client;

    public CoinbaseAdvancedClient getClient() {
        return client;
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        String command;

        unlock();

        System.out.println("Welcome to CoinbaseBot!");
        System.out.println("Type 'help' for available commands.");

        while (true) {
            System.out.print("> ");
            command = scanner.nextLine().trim().toLowerCase();

            switch (command) {
                case "st":
                    startTrading();
                    break;
                case "exit":
                    System.out.println("Exiting CoinbaseBot.");
                    scanner.close();
                    System.exit(0);
                case "help":
                    System.out.println("Available commands:");
                    System.out.println("'st' - Start automatic trading.");
                    System.out.println("'exit' - Exit the program.");
                    break;
                default:
                    System.out.println("Unknown command. Try 'help'.");
            }
        }
    }

    private static void unlock() {
        try {
            config = Config.loadConfig(CONFIG_FILE);
            System.out.println("Configuration loaded successfully.");

            // Initialize the client
            JSONObject credentialsJson = new JSONObject();
            credentialsJson.put("apiKeyName", config.getApiKey());
            credentialsJson.put("privateKey", config.getApiSecret());
            CoinbaseAdvancedCredentials credentials = new CoinbaseAdvancedCredentials(credentialsJson.toString());
            client = new CoinbaseAdvancedClient(credentials);

            System.out.println("API unlocked successfully.");
        } catch (Exception e) {
            System.out.println("Failed to unlock API: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void startTrading() {
        if (client == null) {
            System.out.println("Error: API is not unlocked. Use 'unlock' first.");
            return;
        }

        System.out.println("Starting trading loop...");

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    double usdcBalance = 1000.0; // Replace this with balance retrieval logic

                    TradingBot bot = new TradingBot(new CoinbaseBot(), usdcBalance, config);
                    List<String> coinsToTrade = config.getCoins();

                    for (String coin : coinsToTrade) {
                        bot.executeTrade(coin);
                    }
                } catch (Exception e) {
                    System.out.println("Error during trading loop: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, 0, 60_000); // Execute every 60 seconds
    }
}