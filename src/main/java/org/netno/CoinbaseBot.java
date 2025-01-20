package org.netno;

import com.coinbase.advanced.client.CoinbaseAdvancedClient;
import com.coinbase.advanced.credentials.CoinbaseAdvancedCredentials;
import org.json.JSONObject;
import java.util.Scanner;

public class CoinbaseBot {

    private static final String CONFIG_FILE = "config.json";
    private static Config config;
    private static CoinbaseAdvancedClient client;
    private static TradingBot tradingBot;

    public CoinbaseAdvancedClient getClient() {
        return client;
    }

    public Config getConfig() {
        return config;
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
                case "start":
                    startTrading();
                    break;
                case "stop":
                    stopTrading();
                    break;
                case "exit":
                    System.out.println("Exiting CoinbaseBot.");
                    scanner.close();
                    System.exit(0);
                case "help":
                    System.out.println("Available commands:");
                    System.out.println("'start' - Start automatic trading.");
                    System.out.println("'stop' - Stop automatic trading.");
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
            credentialsJson.put("apiKeyName", config.apiKey);
            credentialsJson.put("privateKey", config.apiSecret);
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

        if (tradingBot != null) {
            System.out.println("Trading is already running. Stop it first!");
            return;
        }

        System.out.println("Starting trading loop...");

        tradingBot = new TradingBot(client, config);
        if (tradingBot.initialized) {
            tradingBot.startTrading();
            WebServer webServer = new WebServer(tradingBot);
            try {
                webServer.start();
            } catch (Exception e) {
                System.out.println("Failed to start web server: " + e.getLocalizedMessage());
            }
        } else {
            System.out.println("Failed to start trading!");
        }
    }

    private static void stopTrading() {
        if (tradingBot != null) {
            tradingBot.stopTrading();
            tradingBot = null;
            System.out.println("Trading loop stopped. Returning to main menu...");
        } else {
            System.out.println("Trading is not currently running.");
        }
    }
}