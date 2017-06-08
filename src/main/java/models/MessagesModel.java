package models;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class MessagesModel {
    private static Connection database;

    public static void init(Connection database) throws SQLException {
        MessagesModel.database = database;
        MessageBuilder.init(database);

        String messageTableSql = "CREATE TABLE IF NOT EXISTS messages (\n"
                + "	id integer PRIMARY KEY AUTOINCREMENT,\n"
                + "	bot varchar(255) NOT NULL,\n"
                + "	channel varchar(255) NOT NULL,\n"
                + "	message varchar(255) NOT NULL\n"
                + ");";

        String bridgeTableSql = "CREATE TABLE IF NOT EXISTS bridge (\n"
                + "fromId integer REFERENCES messages(id),\n"
                + "toId integer REFERENCES messages(id),\n"
                + "PRIMARY KEY(fromId, toId)"
                + ");";

        Statement createTables = database.createStatement();
        createTables.execute(messageTableSql);
        createTables.execute(bridgeTableSql);
    }

    public static void clean() {
        String deleteBridge = "DROP TABLE bridge;";
        String deleteMessages = "DROP TABLE messages;";
        try {
            Statement createTables = database.createStatement();
            createTables.execute(deleteBridge);
            createTables.execute(deleteMessages);
        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }
}