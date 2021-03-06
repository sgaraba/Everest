/*
 * Copyright 2018 Rohit Awate.
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

package com.rohitawate.everest.sync;

import com.rohitawate.everest.logging.LoggingService;
import com.rohitawate.everest.models.requests.HTTPConstants;
import com.rohitawate.everest.settings.Settings;
import com.rohitawate.everest.state.ComposerState;
import com.rohitawate.everest.state.FieldState;
import javafx.util.Pair;

import java.io.File;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

class SQLiteManager implements DataManager {
    private Connection conn;
    private PreparedStatement statement;

    private static class Queries {
        private static final String[] createQueries = {
                "CREATE TABLE IF NOT EXISTS Requests(ID INTEGER PRIMARY KEY, Type TEXT NOT NULL, Target TEXT NOT NULL, Date TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS RequestContentMap(RequestID INTEGER, ContentType TEXT NOT NULL, FOREIGN KEY(RequestID) REFERENCES Requests(ID))",
                "CREATE TABLE IF NOT EXISTS Bodies(RequestID INTEGER, Type TEXT NOT NULL CHECK(Type IN ('application/json', 'application/xml', 'text/html', 'text/plain')), Body TEXT NOT NULL, FOREIGN KEY(RequestID) REFERENCES Requests(ID))",
                "CREATE TABLE IF NOT EXISTS FilePaths(RequestID INTEGER, Path TEXT NOT NULL, FOREIGN KEY(RequestID) REFERENCES Requests(ID))",
                "CREATE TABLE IF NOT EXISTS Tuples(RequestID INTEGER, Type TEXT NOT NULL CHECK(Type IN ('Header', 'Param', 'URLString', 'FormString', 'File')), Key TEXT NOT NULL, Value TEXT NOT NULL, Checked INTEGER CHECK (Checked IN (0, 1)), FOREIGN KEY(RequestID) REFERENCES Requests(ID))"
        };

        private static final String saveRequest = "INSERT INTO Requests(Type, Target, Date) VALUES(?, ?, ?)";
        private static final String saveRequestContentPair = "INSERT INTO RequestContentMap(RequestID, ContentType) VALUES(?, ?)";
        private static final String saveBody = "INSERT INTO Bodies(RequestID, Body, Type) VALUES(?, ?, ?)";
        private static final String saveFilePath = "INSERT INTO FilePaths(RequestID, Path) VALUES(?, ?)";
        private static final String saveTuple = "INSERT INTO Tuples(RequestID, Type, Key, Value, Checked) VALUES(?, ?, ?, ?, ?)";
        private static final String selectRecentRequests = "SELECT * FROM Requests WHERE Requests.Date > ?";
        private static final String selectRequestContentType = "SELECT ContentType FROM RequestContentMap WHERE RequestID == ?";
        private static final String selectRequestBody = "SELECT Body, Type FROM Bodies WHERE RequestID == ?";
        private static final String selectFilePath = "SELECT Path FROM FilePaths WHERE RequestID == ?";
        private static final String selectTuplesByType = "SELECT * FROM Tuples WHERE RequestID == ? AND Type == ?";
        private static final String selectMostRecentRequest = "SELECT * FROM Requests ORDER BY ID DESC LIMIT 1";
    }

    public SQLiteManager() {
        try {
            String configPath = "Everest/config/";
            File configFolder = new File(configPath);
            if (!configFolder.exists()) {
                if (configFolder.mkdirs())
                    LoggingService.logSevere("Unable to create directory: " + configPath, null, LocalDateTime.now());
            }

            conn = DriverManager.getConnection("jdbc:sqlite:Everest/config/history.sqlite");
            createDatabase();
            LoggingService.logInfo("Connected to database", LocalDateTime.now());
        } catch (Exception E) {
            LoggingService.logSevere("Exception while initializing DataManager.", E, LocalDateTime.now());
        }
    }

    /**
     * Creates and initializes the database with necessary tables if not already done.
     */
    private void createDatabase() throws SQLException {
        for (String query : Queries.createQueries) {
            statement = conn.prepareStatement(query);
            statement.execute();
        }
    }

    /**
     * Saves the request to the database if it is not identical to one made exactly before it.
     * Method is synchronized to allow only one database transaction at a time.
     *
     * @param newState - The state of the Dashboard while making the request.
     */
    @Override
    public synchronized void saveState(ComposerState newState) throws SQLException {
        statement = conn.prepareStatement(Queries.saveRequest);

        statement.setString(1, newState.httpMethod);
        statement.setString(2, newState.target);
        statement.setString(3, LocalDate.now().toString());
        statement.executeUpdate();

        // Get latest RequestID to insert into Headers table
        statement = conn.prepareStatement("SELECT MAX(ID) AS MaxID FROM Requests");

        ResultSet RS = statement.executeQuery();
        int requestID = -1;
        if (RS.next())
            requestID = RS.getInt("MaxID");

        saveTuple(newState.headers, "Header", requestID);
        saveTuple(newState.params, "Param", requestID);

        if (!(newState.httpMethod.equals(HTTPConstants.GET) || newState.httpMethod.equals(HTTPConstants.DELETE))) {
            // Maps the request to its ContentType for faster retrieval
            statement = conn.prepareStatement(Queries.saveRequestContentPair);
            statement.setInt(1, requestID);
            statement.setString(2, newState.contentType);
            statement.executeUpdate();

            statement = conn.prepareStatement(Queries.saveBody);
            statement.setInt(1, requestID);
            statement.setString(2, newState.rawBody);
            statement.setString(3, newState.rawBodyBoxValue);
            statement.executeUpdate();

            statement = conn.prepareStatement(Queries.saveFilePath);
            statement.setInt(1, requestID);
            statement.setString(2, newState.binaryFilePath);
            statement.executeUpdate();

            saveTuple(newState.urlStringTuples, "URLString", requestID);
            saveTuple(newState.formStringTuples, "FormString", requestID);
            saveTuple(newState.formFileTuples, "File", requestID);
        }
    }

    /**
     * Returns a list of all the recent requests.
     */
    @Override
    public synchronized List<ComposerState> getHistory() throws SQLException {
        List<ComposerState> history = new ArrayList<>();
        // Loads the requests from the last x number of days, x being Settings.showHistoryRange
        statement = conn.prepareStatement(Queries.selectRecentRequests);
        String historyStartDate = LocalDate.now().minusDays(Settings.showHistoryRange).toString();
        statement.setString(1, historyStartDate);

        ResultSet resultSet = statement.executeQuery();

        ComposerState state;
        while (resultSet.next()) {
            state = new ComposerState();

            state.target = resultSet.getString("Target");

            int requestID = resultSet.getInt("ID");
            state.headers = getTuples(requestID, "Header");
            state.params = getTuples(requestID, "Param");
            state.httpMethod = resultSet.getString("Type");

            if (!(state.httpMethod.equals(HTTPConstants.GET) || state.httpMethod.equals(HTTPConstants.DELETE))) {
                // Retrieves request body ContentType for querying corresponding table
                state.contentType = getRequestContentType(requestID);

                Pair<String, String> rawBodyAndType = getRequestBody(requestID);

                if (rawBodyAndType != null) {
                    state.rawBody = rawBodyAndType.getKey();
                    state.rawBodyBoxValue = rawBodyAndType.getValue();
                }

                state.binaryFilePath = getFilePath(requestID);

                state.urlStringTuples = getTuples(requestID, "URLString");
                state.formStringTuples = getTuples(requestID, "FormString");
                state.formFileTuples = getTuples(requestID, "File");
            }

            history.add(state);
        }

        return history;
    }

    private String getRequestContentType(int requestID) throws SQLException {
        String contentType = null;

        statement = conn.prepareStatement(Queries.selectRequestContentType);
        statement.setInt(1, requestID);

        ResultSet RS = statement.executeQuery();

        if (RS.next())
            contentType = RS.getString("ContentType");

        return contentType;
    }

    /**
     * @param requestID Database ID of the request whose tuples are needed.
     * @param type      Type of tuples needed ('URLString', 'FormString', 'File', 'Header' or 'Param')
     * @return fieldStates - List of FieldStates for the tuples
     */
    private List<FieldState> getTuples(int requestID, String type) throws SQLException {
        if (!(type.equals("FormString") || type.equals("URLString") ||
                type.equals("File") || type.equals("Param") || type.equals("Header")))
            return null;

        ArrayList<FieldState> fieldStates = new ArrayList<>();

        PreparedStatement statement = conn.prepareStatement(Queries.selectTuplesByType);
        statement.setInt(1, requestID);
        statement.setString(2, type);

        ResultSet RS = statement.executeQuery();

        String key, value;
        boolean checked;
        while (RS.next()) {
            key = RS.getString("Key");
            value = RS.getString("Value");
            checked = RS.getBoolean("Checked");
            fieldStates.add(new FieldState(key, value, checked));
        }

        return fieldStates;
    }

    @Override
    public ComposerState getLastAdded() {
        ComposerState lastRequest = new ComposerState();
        try {
            statement = conn.prepareStatement(Queries.selectMostRecentRequest);
            ResultSet RS = statement.executeQuery();

            int requestID = -1;
            if (RS.next()) {
                requestID = RS.getInt("ID");
                lastRequest.target = RS.getString("Target");
                lastRequest.httpMethod = RS.getString("Type");
            }

            lastRequest.headers = getTuples(requestID, "Header");
            lastRequest.params = getTuples(requestID, "Param");
            lastRequest.urlStringTuples = getTuples(requestID, "URLString");
            lastRequest.formStringTuples = getTuples(requestID, "FormString");
            lastRequest.formFileTuples = getTuples(requestID, "File");

            lastRequest.contentType = getRequestContentType(requestID);

            lastRequest.binaryFilePath = getFilePath(requestID);

            Pair<String, String> rawBodyAndType = getRequestBody(requestID);

            if (rawBodyAndType != null) {
                lastRequest.rawBody = rawBodyAndType.getKey();
                lastRequest.rawBodyBoxValue = rawBodyAndType.getValue();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lastRequest;
    }

    private Pair<String, String> getRequestBody(int requestID) throws SQLException {
        statement = conn.prepareStatement(Queries.selectRequestBody);
        statement.setInt(1, requestID);

        ResultSet RS = statement.executeQuery();

        if (RS.next()) {
            return new Pair<>(RS.getString("Body"), RS.getString("Type"));
        } else {
            return null;
        }
    }

    private String getFilePath(int requestID) throws SQLException {
        statement = conn.prepareStatement(Queries.selectFilePath);
        statement.setInt(1, requestID);

        ResultSet RS = statement.executeQuery();

        if (RS.next())
            return RS.getString("Path");
        else
            return null;
    }

    private void saveTuple(List<FieldState> tuples, String tupleType, int requestID) {
        if (tuples.size() > 0) {
            try {
                for (FieldState fieldState : tuples) {
                    statement = conn.prepareStatement(Queries.saveTuple);
                    statement.setInt(1, requestID);
                    statement.setString(2, tupleType);
                    statement.setString(3, fieldState.key);
                    statement.setString(4, fieldState.value);
                    statement.setInt(5, fieldState.checked ? 1 : 0);
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (SQLException e) {
                LoggingService.logSevere("Database error.", e, LocalDateTime.now());
            }
        }
    }

    @Override
    public String getIdentifier() {
        return "SQLite";
    }
}