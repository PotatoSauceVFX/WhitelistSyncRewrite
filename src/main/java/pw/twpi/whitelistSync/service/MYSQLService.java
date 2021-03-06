/*
 * Copyright 2018 TWPI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package pw.twpi.whitelistSync.service;

import com.mojang.authlib.GameProfile;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import pw.twpi.whitelistSync.WhitelistSync;
import pw.twpi.whitelistSync.model.OpUser;
import pw.twpi.whitelistSync.util.ConfigHandler;
import pw.twpi.whitelistSync.util.MYsqlBDError;
import pw.twpi.whitelistSync.util.OPlistRead;
import pw.twpi.whitelistSync.util.WhitelistRead;

/**
 * @author PotatoSauceVFX <rj@potatosaucevfx.com>
 */
public class MYSQLService implements BaseService {

    private Connection conn = null;
    private String S_SQL = "";

    private String url;
    private String username;
    private String password;

    public MYSQLService() {
        this.url = "jdbc:mysql://" + ConfigHandler.mySQL_IP + ":" + ConfigHandler.mySQL_PORT + "/";
        this.username = ConfigHandler.mySQL_Username;
        this.password = ConfigHandler.mySQL_Password;

        try {
            conn = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            WhitelistSync.logger.error("Failed to connect to the mySQL database! Did you set one up in the config?\n" + e.getMessage());
            throw new MYsqlBDError("Failed to connect to the mySQL database! Did you set one up in the config?\n" + e.getMessage());
        }

        loadDatabase();
    }

    private Connection getConnection() {
        return conn;
    }

    private void loadDatabase() {
        // Create database
        try {

            // Create database
            S_SQL = "CREATE DATABASE IF NOT EXISTS " + ConfigHandler.mySQL_DBname + ";";

            // Create statement
            Connection conn = getConnection();
            PreparedStatement stmt = conn.prepareStatement(S_SQL);
            stmt.execute();

            // Create table
            S_SQL = "CREATE TABLE IF NOT EXISTS " + ConfigHandler.mySQL_DBname + ".whitelist ("
                    + "`uuid` VARCHAR(60) NOT NULL,"
                    + "`name` VARCHAR(20) NOT NULL,"
                    + "`whitelisted` TINYINT NOT NULL DEFAULT 1,"
                    + "PRIMARY KEY (`uuid`)"
                    + ")";
            PreparedStatement stmt2 = conn.prepareStatement(S_SQL);
            stmt2.execute();

            // Create table for op list
            if (ConfigHandler.SYNC_OP_LIST) {
                S_SQL = "CREATE TABLE IF NOT EXISTS " + ConfigHandler.mySQL_DBname + ".op ("
                        + "`uuid` VARCHAR(60) NOT NULL,"
                        + "`name` VARCHAR(20) NOT NULL,"
                        + "`level` INT NOT NULL,"
                        + "`bypassesPlayerLimit` TINYINT NOT NULL DEFAULT 0,"
                        + "`isOp` TINYINT NOT NULL DEFAULT 1,"
                        + "PRIMARY KEY (`uuid`)"
                        + ")";
                PreparedStatement stmt3 = conn.prepareStatement(S_SQL);
                stmt3.execute();

                WhitelistSync.logger.info("OP Sync is ENABLED!");
            } else {
                WhitelistSync.logger.info("OP Sync is DISABLED!");
            }

            WhitelistSync.logger.info("Loaded mySQL database!");

        } catch (SQLException e) {
            e.printStackTrace();
        } catch (Exception ee) {
            ee.printStackTrace();
        }
    }

    @Override
    public void pushLocalToDatabase(MinecraftServer server) {
        // Load local whitelist to memory.
        ArrayList<String> uuids = WhitelistRead.getWhitelistUUIDs();
        ArrayList<String> names = WhitelistRead.getWhitelistNames();

        ArrayList<OpUser> opUsers = OPlistRead.getOppedUsers();


        // Start job on thread to avoid lag.
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Keep track of records.
                int records = 0;

                try {
                    // Connect to database.
                    Connection conn = getConnection();
                    long startTime = System.currentTimeMillis();

                    // Loop through local whitelist and insert into database.
                    for (int i = 0; i < uuids.size() || i < names.size(); i++) {
                        if ((uuids.get(i) != null) && (names.get(i) != null)) {
                            try {
                                PreparedStatement sql = conn.prepareStatement("INSERT IGNORE INTO " + ConfigHandler.mySQL_DBname + ".whitelist(uuid, name, whitelisted) VALUES (?, ?, 1)");
                                sql.setString(1, uuids.get(i));
                                sql.setString(2, names.get(i));
                                sql.executeUpdate();
                                records++;
                            } catch (ClassCastException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    // Record time taken.
                    long timeTaken = System.currentTimeMillis() - startTime;
                    WhitelistSync.logger.info("Wrote " + records + " to whitelist table in " + timeTaken + "ms.");
                    WhitelistSync.logger.debug("Whitelist Table Updated | Took " + timeTaken + "ms | Wrote " + records + " records.");

                    // If syncing op list
                    if (ConfigHandler.SYNC_OP_LIST) {
                        records = 0;
                        long opStartTime = System.currentTimeMillis();

                        // Loop through ops list and add to DB
                        for (OpUser opUser : opUsers) {
                            try {
                                PreparedStatement sql = conn.prepareStatement("INSERT IGNORE INTO " + ConfigHandler.mySQL_DBname + ".op(uuid, name, level, bypassesPlayerLimit, isOp) VALUES (?, ?, ?, ?, true)");
                                sql.setString(1, opUser.getUuid());
                                sql.setString(2, opUser.getName());
                                sql.setInt(3, opUser.getLevel());
                                sql.setBoolean(4, opUser.isBypassesPlayerLimit());
                                sql.executeUpdate();
                                records++;
                            } catch (ClassCastException e) {
                                e.printStackTrace();
                            }
                            records++;
                        }
                        // Record time taken.
                        long opTimeTaken = System.currentTimeMillis() - opStartTime;
                        WhitelistSync.logger.info("Wrote " + records + " to op table in " + opTimeTaken + "ms.");
                        WhitelistSync.logger.debug("Op Table Updated | Took " + opTimeTaken + "ms | Wrote " + records + " records.");
                    }

                } catch (SQLException e) {
                    WhitelistSync.logger.error("Failed to update database with local records.\n" + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public ArrayList<String> pullUuidsFromDatabase(MinecraftServer server) {
        // ArrayList for uuids.
        ArrayList<String> uuids = new ArrayList<String>();

        try {
            // Keep track of records.
            int records = 0;

            // Connect to database.
            Connection conn = getConnection();
            long startTime = System.currentTimeMillis();

            String sql = "SELECT uuid, whitelisted FROM " + ConfigHandler.mySQL_DBname + ".whitelist";
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();

            // Add querried results to arraylist.
            while (rs.next()) {
                if (rs.getInt("whitelisted") == 1) {
                    uuids.add(rs.getString("uuid"));
                }
                records++;
            }

            // Time taken
            long timeTaken = System.currentTimeMillis() - startTime;

            WhitelistSync.logger.debug("Whitelist Database Pulled | Took " + timeTaken + "ms | Read " + records + " records.");
        } catch (SQLException e) {
            WhitelistSync.logger.error("Error querrying uuids from whitelist database!\n" + e.getMessage());
        }
        return uuids;
    }

    @Override
    public ArrayList<String> pullOpUuidsFromDatabase(MinecraftServer server) {
        // ArrayList for uuids.
        ArrayList<String> uuids = new ArrayList<String>();

        try {
            // Keep track of records.
            int records = 0;

            // Connect to database.
            Connection conn = getConnection();
            long startTime = System.currentTimeMillis();

            String sql = "SELECT uuid, isOp FROM " + ConfigHandler.mySQL_DBname + ".op";
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();

            // Add querried results to arraylist.
            while (rs.next()) {
                if (rs.getInt("isOp") == 1) {
                    uuids.add(rs.getString("uuid"));
                }
                records++;
            }

            // Time taken
            long timeTaken = System.currentTimeMillis() - startTime;

            WhitelistSync.logger.debug("Op Database Pulled | Took " + timeTaken + "ms | Read " + records + " records.");
        } catch (SQLException e) {
            WhitelistSync.logger.error("Error querrying uuids from op database!\n" + e.getMessage());
        }
        return uuids;
    }

    @Override
    public ArrayList<String> pullNamesFromDatabase(MinecraftServer server) {
        // ArrayList for names.
        ArrayList<String> names = new ArrayList<String>();

        try {

            // Keep track of records.
            int records = 0;

            // Connect to database.
            Connection conn = getConnection();
            long startTime = System.currentTimeMillis();

            String sql = "SELECT name, whitelisted FROM " + ConfigHandler.mySQL_DBname + ".whitelist";
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();

            // Save querried return to names list.
            while (rs.next()) {
                if (rs.getInt("whitelisted") == 1) {
                    names.add(rs.getString("name"));
                }
                records++;
            }

            // Total time taken.
            long timeTaken = System.currentTimeMillis() - startTime;

            WhitelistSync.logger.debug("Whitelist Database Pulled | Took " + timeTaken + "ms | Read " + records + " records.");
        } catch (SQLException e) {
            WhitelistSync.logger.error("Error querrying names from whitelist database!\n" + e.getMessage());
        }
        return names;
    }

    @Override
    public ArrayList<String> pullOpNamesFromDatabase(MinecraftServer server) {
        // ArrayList for names.
        ArrayList<String> names = new ArrayList<String>();

        try {

            // Keep track of records.
            int records = 0;

            // Connect to database.
            Connection conn = getConnection();
            long startTime = System.currentTimeMillis();

            String sql = "SELECT name, isOp FROM " + ConfigHandler.mySQL_DBname + ".op";
            PreparedStatement stmt = conn.prepareStatement(sql);
            ResultSet rs = stmt.executeQuery();

            // Save querried return to names list.
            while (rs.next()) {
                if (rs.getInt("isOp") == 1) {
                    names.add(rs.getString("name"));
                }
                records++;
            }

            // Total time taken.
            long timeTaken = System.currentTimeMillis() - startTime;

            WhitelistSync.logger.debug("Op Database Pulled | Took " + timeTaken + "ms | Read " + records + " records.");
        } catch (SQLException e) {
            WhitelistSync.logger.error("Error querrying names from op database!\n" + e.getMessage());
        }
        return names;
    }


    // TODO: Add boolean feedback.
    @Override
    public void addPlayerToDatabase(GameProfile player) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    // Start time.
                    long startTime = System.currentTimeMillis();

                    // Open connection
                    Connection conn = getConnection();
                    String sql = "REPLACE INTO " + ConfigHandler.mySQL_DBname + ".whitelist(uuid, name, whitelisted) VALUES (?, ?, 1)";

                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, String.valueOf(player.getId()));
                    stmt.setString(2, player.getName());

                    // Execute statement.
                    stmt.execute();

                    // Time taken.
                    long timeTaken = System.currentTimeMillis() - startTime;

                    WhitelistSync.logger.debug("Database Added " + player.getName() + " | Took " + timeTaken + "ms");
                } catch (SQLException e) {
                    WhitelistSync.logger.error("Error adding " + player.getName() + " to database!\n" + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void addOpPlayerToDatabase(GameProfile player) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    ArrayList<OpUser> oppedUsers = OPlistRead.getOppedUsers();

                    // Start time.
                    long startTime = System.currentTimeMillis();

                    // Open connection
                    Connection conn = getConnection();
                    String sql = "REPLACE INTO " + ConfigHandler.mySQL_DBname + ".op(uuid, name, level, isOp) VALUES (?, ?, ?, true)";

                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, String.valueOf(player.getId()));
                    stmt.setString(2, player.getName());

                    for (OpUser opUser : oppedUsers) {
                        if (opUser.getUuid().equals(player.getId())) {
                            stmt.setInt(3, opUser.getLevel());
                            stmt.setBoolean(4, opUser.isBypassesPlayerLimit());
                        }
                    }

                    // Execute statement.
                    stmt.execute();

                    // Time taken.
                    long timeTaken = System.currentTimeMillis() - startTime;

                    WhitelistSync.logger.debug("Op Database Added " + player.getName() + " | Took " + timeTaken + "ms");
                } catch (SQLException e) {
                    WhitelistSync.logger.error("Error adding " + player.getName() + " to op database!\n" + e.getMessage());
                }
            }
        }).start();
    }

    // TODO: Add boolean feedback.
    @Override
    public void removePlayerFromDatabase(GameProfile player) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    // Start time.
                    long startTime = System.currentTimeMillis();

                    // Open connection
                    Connection conn = getConnection();
                    String sql = "REPLACE INTO " + ConfigHandler.mySQL_DBname + ".whitelist(uuid, name, whitelisted) VALUES (?, ?, 0)";

                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, String.valueOf(player.getId()));
                    stmt.setString(2, player.getName());

                    // Execute statement.
                    stmt.execute();

                    // Time taken.
                    long timeTaken = System.currentTimeMillis() - startTime;

                    WhitelistSync.logger.debug("Database Removed " + player.getName() + " | Took " + timeTaken + "ms");
                } catch (SQLException e) {
                    WhitelistSync.logger.error("Error removing " + player.getName() + " to database!\n" + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void removeOpPlayerFromDatabase(GameProfile player) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    // Start time.
                    long startTime = System.currentTimeMillis();

                    // Open connection
                    Connection conn = getConnection();
                    String sql = "REPLACE INTO " + ConfigHandler.mySQL_DBname + ".op(uuid, name, isOp) VALUES (?, ?, false)";

                    PreparedStatement stmt = conn.prepareStatement(sql);
                    stmt.setString(1, String.valueOf(player.getId()));
                    stmt.setString(2, player.getName());

                    // Execute statement.
                    stmt.execute();

                    // Time taken.
                    long timeTaken = System.currentTimeMillis() - startTime;

                    WhitelistSync.logger.debug("Op Database Removed " + player.getName() + " | Took " + timeTaken + "ms");
                } catch (SQLException e) {
                    WhitelistSync.logger.error("Error removing " + player.getName() + " from op database!\n" + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void updateLocalWhitelistFromDatabase(MinecraftServer server) {
        new Thread(() -> {
            try {
                int records = 0;

                // Start time
                long startTime = System.currentTimeMillis();

                // Open connection
                Connection conn = getConnection();
                String sql = "SELECT name, uuid, whitelisted FROM " + ConfigHandler.mySQL_DBname + ".whitelist";
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery();
                ArrayList<String> localUuids = WhitelistRead.getWhitelistUUIDs();
                while (rs.next()) {
                    int whitelisted = rs.getInt("whitelisted");
                    String uuid = rs.getString("uuid");
                    String name = rs.getString("name");
                    GameProfile player = new GameProfile(UUID.fromString(uuid), name);

                    if (whitelisted == 1) {
                        if (!localUuids.contains(uuid)) {
                            try {
                                server.getPlayerList().addWhitelistedPlayer(player);
                            } catch (NullPointerException e) {
                                WhitelistSync.logger.error("Player is null?\n" + e.getMessage());
                            }
                        }
                    } else {
                        WhitelistSync.logger.debug(uuid + " is NOT whitelisted.");
                        if (localUuids.contains(uuid)) {
                            server.getPlayerList().removePlayerFromWhitelist(player);
                            WhitelistSync.logger.debug("Removed player " + name);
                        }
                    }
                    records++;
                }
                long timeTaken = System.currentTimeMillis() - startTime;
                WhitelistSync.logger.debug("Database Pulled | Took " + timeTaken + "ms | Wrote " + records + " records.");
                WhitelistSync.logger.debug("Local whitelist.json up to date!");
            } catch (SQLException e) {
                WhitelistSync.logger.error("Error querying whitelisted players from database!\n" + e.getMessage());
            }
        }).start();
    }

    @Override
    public void updateLocalOpListFromDatabase(MinecraftServer server) {
        new Thread(() -> {
            try {
                int records = 0;

                // Start time
                long startTime = System.currentTimeMillis();

                // Open connection
                Connection conn = getConnection();
                String sql = "SELECT name, uuid, level, bypassesPlayerLimit, isOp FROM " + ConfigHandler.mySQL_DBname + ".op";
                PreparedStatement stmt = conn.prepareStatement(sql);
                ResultSet rs = stmt.executeQuery();
                ArrayList<String> localUuids = OPlistRead.getOpsUUIDs();

                while (rs.next()) {
                    String uuid = rs.getString("uuid");
                    String name = rs.getString("name");
                    int level = rs.getInt("level");
                    boolean bypassesPlayerLimit = rs.getBoolean("bypassesPlayerLimit");
                    boolean isOp = rs.getBoolean("isOp");

                    GameProfile player = new GameProfile(UUID.fromString(uuid), name);

                    if (isOp) {
                        if (!localUuids.contains(uuid)) {
                            try {
                                server.getPlayerList().addOp(player);
                            } catch (NullPointerException e) {
                                WhitelistSync.logger.error("Player is null?\n" + e.getMessage());
                            }
                        }
                    } else {
                        WhitelistSync.logger.debug(uuid + " is NOT whitelisted.");
                        if (localUuids.contains(uuid)) {
                            server.getPlayerList().removeOp(player);
                            WhitelistSync.logger.debug("Removed player " + name);
                        }
                    }
                    records++;
                }
                long timeTaken = System.currentTimeMillis() - startTime;
                WhitelistSync.logger.debug("Database Pulled | Took " + timeTaken + "ms | Wrote " + records + " records.");
                WhitelistSync.logger.debug("Local ops.json up to date!");
            } catch (SQLException e) {
                WhitelistSync.logger.error("Error querying opped players from database!\n" + e.getMessage());
            }
        }).start();
    }




}
