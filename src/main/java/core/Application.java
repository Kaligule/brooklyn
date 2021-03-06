/*
 * Copyright 2017 Davide Riva driva95@protonmail.com
 *
 * This is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package core;

import bots.Bot;
import bots.TelegramBot;
import models.FileStorage;
import models.MessagesModel;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;

public final class Application {
    private static Connection database;

    public static void main(String[] args) throws InterruptedException {
        // TODO: find a way to replace this temporary fix
        TelegramBot.init();

        Config conf = new Config(1 > args.length ? Config.DEFAULT_FILENAME : args[0]);

        try {
            conf.load();
        } catch (IOException e) {
            System.err.println(String.format("Error while loading config file: %s.", e.getMessage()));
            System.exit(1);
        }

        Map<String, Object> channelsConfig = conf.getChannels();

        Application.initDatabase(conf.getDbUri());

        Map<String, String> webserverConfig = conf.getWebserverConfig();
        FileStorage.init(webserverConfig);

        Map<String, Bot> bots = initBots(conf.getBots(), channelsConfig);
        manageBridges(bots, channelsConfig, conf.getBridges());

        handleShutdown(bots);
    }

    private static void initDatabase(String dbUri) {
        try {
            Application.database = DriverManager.getConnection(dbUri);
            MessagesModel.init(Application.database);
        } catch (SQLException e) {
            System.err.println("Error loading the database");
            e.printStackTrace();
        }
    }

    private static Map<String, Bot> initBots(Map<String, Object> botsConfig,
                                             Map<String, Object> channelsConfig) {
        int AVG_BOTS_N = 3;
        Map<String, Bot> bots = new LinkedHashMap<>(AVG_BOTS_N);
        botsConfig.forEach((key, value) -> {
            Map<String, String> botConfig = (Map<String, String>) value;
            try {
                Object newClass = Class.forName(Bot.class.getPackage().getName() + '.' + botConfig.get(Config.BOT_TYPE_KEY)).newInstance();
                if (newClass instanceof Bot) {
                    Bot bot = (Bot) newClass;
                    String[] channels = Application.getChannelsName(key, channelsConfig);
                    if (bot.init(key, botConfig, channels)) {
                        bots.put(key, bot);
                        System.out.println(String.format("Bot '%s' initialized.", key));
                    } else
                        System.err.println(String.format("Failed to init '%s' bot.", key));
                } else
                    System.err.println(String.format("'%s' is not a valid bot.", botConfig.get(Config.BOT_TYPE_KEY)));
            } catch (Exception e) {
                System.err.println(String.format("Class of type '%s' can't be instantiated.", botConfig.get(Config.BOT_TYPE_KEY)));
                e.printStackTrace();
            }
        });

        return bots;
    }

    private static String[] getChannelsName(String botName,
                                            Map<String, Object> channelsConfig) {
        List<String> channels = new LinkedList<>();
        channelsConfig.forEach((key, value) -> {
            Map<String, String> channelConfig = (Map<String, String>) value;
            if (botName.equals(channelConfig.get(Config.BOT_KEY))) {
                if (channelConfig.containsKey(Config.NAME_KEY))
                    channels.add(channelConfig.get(Config.NAME_KEY));
            }
        });

        return channels.toArray(new String[channels.size()]);
    }

    private static void manageBridges(Map<String, Bot> bots,
                                      Map<String, Object> channelsConfig,
                                      Iterable<ArrayList<String>> bridgesConfig) {
        bridgesConfig.forEach(bridgeConfig -> bridgeConfig.forEach(fromChannelId -> {
            Optional<String> fromBotId = Application.channelToBotId(fromChannelId, channelsConfig);
            if (fromBotId.isPresent() && bots.containsKey(fromBotId.get())) {
                Bot fromBot = bots.get(fromBotId.get());

                bridgeConfig.forEach(toChannelId -> {
                    Optional<String> toBotId = Application.channelToBotId(toChannelId, channelsConfig);
                    toBotId.ifPresent(id -> {
                        Bot toBot = bots.get(id);
                        Map<String, String> toChannelConfig = (Map<String, String>) channelsConfig.get(toChannelId);
                        Map<String, String> fromChannelConfig = (Map<String, String>) channelsConfig.get(fromChannelId);

                        if (!fromChannelId.equals(toChannelId))
                            fromBot.addBridge(toBot, toChannelConfig.get(Config.NAME_KEY), fromChannelConfig.get(Config.NAME_KEY));
                    });
                });
            }
        }));
    }

    private static Optional<String> channelToBotId(String channelId,
                                                   Map<String, Object> channelsConfig) {
        for (Entry<String, Object> entry : channelsConfig.entrySet()) {
            if (entry.getKey().equals(channelId)) {
                Map<String, String> channelConfig = (Map<String, String>) entry.getValue();
                return Optional.ofNullable(channelConfig.get(Config.BOT_KEY));
            }
        }

        return Optional.empty();
    }

    private static void handleShutdown(Map<String, Bot> bots) throws InterruptedException {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                MessagesModel.clean();
                Application.database.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }

            bots.entrySet().stream()
                    .map(Entry::getValue)
                    .forEach(bot -> {
                        try {
                            bot.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });

            System.out.println("Application terminated");
        }));

        // Wait forever unless the process is killed
        Thread.currentThread().join();
    }
}
