package edu.asupoly.heal.aqm.dmp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;

import edu.asupoly.heal.aqm.model.SensordroneReading;
import edu.asupoly.heal.aqm.model.DylosReading;
import edu.asupoly.heal.aqm.model.ServerPushEvent;

public class AQMDAOJDBCImpl implements IAQMDAO {
    @SuppressWarnings("unused")
    private static final long MS_ONE_YEAR_FROM_NOW = 1000L * 60L * 60 * 24L * 365L;

    protected String __jdbcURL;
    protected Properties __jdbcProperties;

    public AQMDAOJDBCImpl() {
    }

    @Override
    public void init(Properties p) throws Exception {
        __jdbcProperties = new Properties();
        String jdbcDriver = p.getProperty("jdbc.driver");
        String jdbcURL = p.getProperty("jdbc.url");
        // do we need user and password?
        // these would be in the properties if defined (embedded derby does not need)
        __jdbcProperties.setProperty("user", p.getProperty("jdbc.user"));
        __jdbcProperties.setProperty("password", p.getProperty("jdbc.passwd"));
        
        if (jdbcDriver == null || jdbcURL == null) {
            throw new Exception("JDBC not configured");
        }

        // load the driver, test the URL
        try {
            // read in all the JDBC properties and SQL queries we need
            Enumeration<?> keys = p.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                if (key.startsWith("sql")) {
                    __jdbcProperties.setProperty(key, p.getProperty(key));
                }
            }

            Class.forName(jdbcDriver);
            __jdbcURL = jdbcURL;
            __jdbcProperties.setProperty("jdbc.driver", jdbcDriver);
            __jdbcProperties.setProperty("jdbc.url", jdbcURL);
            
            // test the connection
            if (!_testConnection(jdbcURL,
                    p.getProperty("sql.checkConnectionQuery"))) {
                System.out.println("Testing Connection Failed "
                        + p.getProperty("sql.checkConnectionQuery"));
                throw new Exception("Unable to connect to database");
            } else {
                System.out.println("Testing DAO Connection -- OK");
            }
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }
    
    protected Connection _getConnection() throws SQLException {
        return DriverManager.getConnection(__jdbcURL, __jdbcProperties);
    }
    
    protected boolean _testConnection(String url, String query) {
        Connection c = null;
        Statement s = null;
        try {
            c = _getConnection();
            s = c.createStatement();
            return s.execute(query);
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        } finally {
            try {
                if (s != null)
                    s.close();
                if (c != null)
                    c.close();
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }


    public boolean importReadings(String toImport) throws Exception {
        Object obj = JSONValue.parse(toImport);
        if (obj instanceof JSONArray) {	
            JSONArray jsonary = (JSONArray) obj;
            if (jsonary.isEmpty())
                return false;
            JSONObject rd = (JSONObject)jsonary.get(0);
            String type = (String)rd.get("type");
            if (type.equals("dylos") ) {
                System.out.println("data type = "+type);
                return importDylosReading(toImport);
            }
        }
        else if (obj instanceof JSONObject) {
            JSONObject rd = (JSONObject) obj;
            if (rd.isEmpty())
                return false;
            String type = (String)rd.get("type");
            if (type.equals("sensordrone")) {
                System.out.println("data type = "+type);
                return importSensordroneReading(toImport);
            }
        }
        return false;
    }

    // received Dylos json string sample:
    // [{"deviceId":"aqm1","userId":"patient1","dateTime":"Sat Mar 08 22:24:10 MST 2014",
    // "smallParticle":76,"largeParticle":16,
    // "geoLatitude":33.3099177,"geoLongitude":-111.6726974,"geoMethod":"manual"},{...},...]
    @Override
    public boolean importDylosReading(String toImport) throws Exception {
        Connection c = null;
        PreparedStatement ps1 = null;
        PreparedStatement ps2 = null;

        JSONArray jsonary = new JSONArray();
        JSONParser parser = new JSONParser();
        JSONObject jsonobj = new JSONObject();
        jsonary = (JSONArray) parser.parse(toImport);
        if (jsonary.isEmpty())
            return false;

        try {
            c = _getConnection();
            c.setAutoCommit(false);
            ps1 = c.prepareStatement(__jdbcProperties.getProperty("sql.importCommonReadings"));
            ps2 = c.prepareStatement(__jdbcProperties.getProperty("sql.importDylosReadings"));

            for (int i = 0; i < jsonary.size(); i++) {
                jsonobj = (JSONObject) jsonary.get(i);

                String dateTime = (String) jsonobj.get("dateTime");
                Date d = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).parse(dateTime);

                ps1.setString(1, (String) jsonobj.get("deviceId"));
                ps1.setTimestamp(2, new java.sql.Timestamp(d.getTime()), AQMDAOFactory.AQM_CALENDAR);

                ps1.setDouble(3, (Double) jsonobj.get("geoLatitude"));
                ps1.setDouble(4, (Double) jsonobj.get("geoLongitude"));
                ps1.setString(5, (String) jsonobj.get("geoMethod"));

                ps2.setString(1, (String) jsonobj.get("deviceId"));
                ps2.setTimestamp(2, new java.sql.Timestamp(d.getTime()), AQMDAOFactory.AQM_CALENDAR);
                ps2.setInt(3, ((Long) jsonobj.get("smallParticle")).intValue());
                ps2.setInt(4, ((Long) jsonobj.get("largeParticle")).intValue());
                ps2.setString(5, (String) jsonobj.get("userId"));

                ps1.executeUpdate();
                ps1.clearParameters();
                ps2.executeUpdate();
                ps2.clearParameters();
            }
            c.commit();

        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            try {
                if (ps1 != null)
                    ps1.close();
                if (ps2 != null)
                    ps2.close();
                if (c != null) {
                    c.rollback();
                    c.close();
                }
            } catch (SQLException se2) {
                se2.printStackTrace();
            }
        }
        return true;
    }

    // received Sensordrone json string sample
    // {"deviceId":"SensorDroneB8:FF:FE:B9:D9:A0","dateTime":"20140313_195444",
    // "co2DeviceID":"UNKNOWN","coData":-2,"co2Data":-1,
    // "presureData":96128,"tempData":27,"humidityData":42,
    // "geoLatitude":33.2830173,"geoLongitude":-111.7627723,"geoMethod":"Network"}
    public boolean importSensordroneReading(String toImport) throws Exception {
        Connection c = null;
        PreparedStatement ps1 = null;
        PreparedStatement ps2 = null;

        JSONParser parser = new JSONParser();
        JSONObject jsonobj = new JSONObject();
        jsonobj = (JSONObject) parser.parse(toImport);
        if (jsonobj.isEmpty())
            return false;

        try {
            c = _getConnection();
            c.setAutoCommit(false);
            ps1 = c.prepareStatement(__jdbcProperties.getProperty("sql.importCommonReadings"));
            ps2 = c.prepareStatement(__jdbcProperties.getProperty("sql.importSensordroneReadings"));

            String dateTime = (String) jsonobj.get("dateTime");
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dateTime);

            ps1.setString(1, (String) jsonobj.get("deviceId"));
            ps1.setTimestamp(2, new java.sql.Timestamp(d.getTime()), AQMDAOFactory.AQM_CALENDAR);
            ps1.setDouble(3, (Double) jsonobj.get("geoLatitude"));
            ps1.setDouble(4, (Double) jsonobj.get("geoLongitude"));
            ps1.setString(5, (String) jsonobj.get("geoMethod"));

            ps2.setString(1, (String) jsonobj.get("deviceId"));
            ps2.setTimestamp(2, new java.sql.Timestamp(d.getTime()), AQMDAOFactory.AQM_CALENDAR);
            ps2.setInt(3, ((Long) jsonobj.get("presureData")).intValue());
            ps2.setInt(4, ((Long) jsonobj.get("tempData")).intValue());
            ps2.setInt(5, ((Long) jsonobj.get("coData")).intValue());
            ps2.setInt(6, ((Long) jsonobj.get("humidityData")).intValue());
            ps2.setString(7, (String) jsonobj.get("co2DeviceID"));
            ps2.setInt(8, ((Long) jsonobj.get("co2Data")).intValue());

            ps1.executeUpdate();
            ps1.clearParameters();
            ps2.executeUpdate();
            ps2.clearParameters();
            c.commit();

        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            try {
                if (ps1 != null)
                    ps1.close();
                if (ps2 != null)
                    ps2.close();
                if (c != null) {
                    c.rollback();
                    c.close();
                }
            } catch (SQLException se2) {
                se2.printStackTrace();
            }
        }
        return true;
    }

    public boolean addPushEvent(ServerPushEvent s) throws Exception {
        if (s == null) return false;

        Connection c = null;
        PreparedStatement ps = null;

        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.addServerPushEvent"));

            String dateTime = s.getEventTime();
            Date d = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US)
            .parse(dateTime);
            ps.setTimestamp(1, new java.sql.Timestamp(d.getTime()), AQMDAOFactory.AQM_CALENDAR);

            ps.setInt(2,  s.getResponseCode());
            ps.setInt(3, s.getDeviceType());
            ps.setString(4, s.getMessage());
            ps.executeUpdate();
            ps.clearParameters();
            c.commit();

        } catch (SQLException se) {
            se.printStackTrace();
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            try {
                if (ps != null) ps.close();
                if (c != null) c.close();
            } catch (SQLException se2) {
                se2.printStackTrace();
            }
        }
        return true;

    }


    // [{"deviceId":"aqm1","userId":"patient1","dateTime":"Sat Mar 08 22:24:10 MST 2014",
    // "smallParticle":76,"largeParticle":16,
    // "geoLatitude":33.3099177,"geoLongitude":-111.6726974,"geoMethod":"manual"},{...},...]
    @SuppressWarnings("unchecked")
    public JSONArray findDylosReadingsTest() throws Exception {
        int count = 10;
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        try {
            c = _getConnection();
            //			Statement statement = c.createStatement();
            //			statement.setMaxRows(10);
            //			rs = statement
            //					.executeQuery("select * from particle_reading order by dateTime desc");

            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findDylosReadingsTest"));
            rs = ps.executeQuery();
            while (rs.next() && count > 0) {
                Timestamp t = rs.getTimestamp("dateTime", AQMDAOFactory.AQM_CALENDAR);
                Date d = new Date(t.getTime());

                String deviceId = rs.getString("deviceId");
                String dateTime = d.toString();
                double geoLatitude = rs.getDouble("latitude");
                double geoLongitude = rs.getDouble("longitude");
                String geoMethod = rs.getString("method");
                int smallParticle = rs.getInt("smallParticle");
                int largeParticle = rs.getInt("largeParticle");
                String userId = rs.getString("userId");

                DylosReading prd = new DylosReading(deviceId, userId, dateTime,
                        smallParticle, largeParticle, geoLatitude,
                        geoLongitude, geoMethod);

                rd.add(prd);
                count--;
            }
        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }

        return rd;
    }

    // [{"deviceId":"SensorDroneB8:FF:FE:B9:D9:A0","dateTime":"20140313_195444",
    // "co2DeviceID":"UNKNOWN","coData":-2,"co2Data":-1,
    // "presureData":96128,"tempData":27,"humidityData":42,
    // "geoLatitude":33.2830173,"geoLongitude":-111.7627723,"geoMethod":"Network"},{...},...]
    @SuppressWarnings("unchecked")
    public JSONArray findSensordroneReadingsTest() throws Exception {
        int count = 10;
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findSensordroneReadingsTest"));
            rs = ps.executeQuery();
            while (rs.next() && count > 0) {
                Timestamp t = rs.getTimestamp("dateTime", AQMDAOFactory.AQM_CALENDAR);
                Date d = new Date(t.getTime());

                String deviceId = rs.getString("deviceId");
                String dateTime = d.toString();
                double geoLatitude = rs.getDouble("latitude");
                double geoLongitude = rs.getDouble("longitude");
                String geoMethod = rs.getString("method");
                int presureData = rs.getInt("pressureData");
                int tempData = rs.getInt("tempData");
                int coData = rs.getInt("coData");
                int humidityData = rs.getInt("humidityData");
                String co2DeviceID = rs.getString("co2sensorid");
                int co2Data = rs.getInt("co2Data");

                SensordroneReading ssr = new SensordroneReading(deviceId,
                        dateTime, co2DeviceID, coData, co2Data, presureData,
                        tempData, humidityData, geoLatitude, geoLongitude,
                        geoMethod);

                rd.add(ssr);
                count--;
            }
        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }

        return rd;
    }

    @SuppressWarnings("unchecked")
    public JSONArray findCommonReadingsTest() throws Exception {
        int count = 10;
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findCommonReadingsTest"));
            rs = ps.executeQuery();
            while (rs.next() && count > 0) {
                JSONObject obj = new JSONObject();
                Timestamp t = rs.getTimestamp("dateTime", AQMDAOFactory.AQM_CALENDAR);
                Date d = new Date(t.getTime());

                String deviceId = rs.getString("deviceId");
                String dateTime = d.toString();
                double geoLatitude = rs.getDouble("latitude");
                double geoLongitude = rs.getDouble("longitude");
                String geoMethod = rs.getString("method");

                obj.put("deviceId", deviceId);
                obj.put("dateTime", dateTime);
                obj.put("geoLatitude", geoLatitude);
                obj.put("geoLongitude", geoLongitude);
                obj.put("geoMethod", geoMethod);

                rd.add(obj);
                count--;
            }
        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }

        return rd;
    }

    public void findDeviceIdinDylos(PrintWriter out) throws Exception {
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        List<String> deviceids = new ArrayList<String>();

        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findDeviceIdinDylos"));
            rs = ps.executeQuery();
            while (rs.next()) {
                deviceids.add(rs.getString("deviceid"));
            }

            System.out.println(" have "+ deviceids.size() + " deviceids in Dylos." );
            out.println("  contains "+deviceids.size() +" devices now");
            for (int i = 0; i < deviceids.size(); i++) {
                out.println("\n"+ (i+1) +". deviceid=" + deviceids.get(i)+ "\n");
                rd = (JSONArray)findDylosReadingsByGroup(deviceids.get(i), Integer.MAX_VALUE);
                StringWriter json = new StringWriter();
                rd.writeJSONString(json);
                out.print(json.toString() + "\n");
            }

        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }
    }

    public void findDeviceIdinSensordrone(PrintWriter out) throws Exception {
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        List<String> deviceids = new ArrayList<String>();

        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findDeviceIdinSensordrone"));
            rs = ps.executeQuery();
            while (rs.next()) {
                deviceids.add(rs.getString("deviceid"));
            }

            System.out.println(" have "+ deviceids.size() + " deviceids in Sensordrone." );
            out.println("  contains "+deviceids.size() +" devices now");
            for (int i = 0; i < deviceids.size(); i++) {
                out.println("\n"+ (i+1) +". deviceid=" + deviceids.get(i)+ "\n");
                rd = (JSONArray)findSensordroneReadingsByGroup(deviceids.get(i), Integer.MAX_VALUE);
                StringWriter json = new StringWriter();
                rd.writeJSONString(json);
                out.print(json.toString() + "\n");
            }

        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }
    }

    @SuppressWarnings("unchecked")
    public JSONArray findDylosReadingsByGroup(String deviceid, int tail, boolean isGeoJson) throws Exception {
        if (tail == Integer.MAX_VALUE) tail = 10;

        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        JSONArray geoArray = new JSONArray();
        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findDylosReadingsByGroup"));
            if (deviceid == null) 
                ps.setString(1,  "%");
            else ps.setString(1, deviceid);

            rs = ps.executeQuery();
            while (rs.next() && tail > 0) {
                Timestamp t = rs.getTimestamp("dateTime", AQMDAOFactory.AQM_CALENDAR);
                Date d = new Date(t.getTime());

                String deviceId = rs.getString("deviceId");
                String dateTime = d.toString();
                double geoLatitude = rs.getDouble("latitude");
                double geoLongitude = rs.getDouble("longitude");
                String geoMethod = rs.getString("method");
                int smallParticle = rs.getInt("smallParticle");
                int largeParticle = rs.getInt("largeParticle");
                String userId = rs.getString("userId");

                DylosReading prd = new DylosReading(deviceId, userId, dateTime,
                        smallParticle, largeParticle, geoLatitude,
                        geoLongitude, geoMethod);
                rd.add(prd);

                JSONObject geojson = prd.getGeoJSONFeature();
                geoArray.add(geojson);

                tail--;
            }
        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }

        if (!isGeoJson) return rd;
        else return geoArray;
    }

    @SuppressWarnings("unchecked")
    public JSONArray findSensordroneReadingsByGroup(String deviceid, int tail, boolean isGeoJson) throws Exception {
        if (tail == Integer.MAX_VALUE) tail = 10;
        Connection c = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        JSONArray rd = new JSONArray();
        JSONArray geoArray = new JSONArray();
        try {
            c = _getConnection();
            ps = c.prepareStatement(__jdbcProperties.getProperty("sql.findSensordroneReadingsByGroup"));
            ps.setString(1, deviceid);
            if (deviceid == null) 
                ps.setString(1,  "%");
            else ps.setString(1, deviceid);

            rs = ps.executeQuery();
            while (rs.next() && tail > 0) {
                Timestamp t = rs.getTimestamp("dateTime", AQMDAOFactory.AQM_CALENDAR);
                Date d = new Date(t.getTime());

                String deviceId = rs.getString("deviceId");
                String dateTime = d.toString();
                double geoLatitude = rs.getDouble("latitude");
                double geoLongitude = rs.getDouble("longitude");
                String geoMethod = rs.getString("method");
                int presureData = rs.getInt("pressureData");
                int tempData = rs.getInt("tempData");
                int coData = rs.getInt("coData");
                int humidityData = rs.getInt("humidityData");
                String co2DeviceID = rs.getString("co2sensorid");
                int co2Data = rs.getInt("co2Data");

                SensordroneReading ssr = new SensordroneReading(deviceId,
                        dateTime, co2DeviceID, coData, co2Data, presureData,
                        tempData, humidityData, geoLatitude, geoLongitude,
                        geoMethod);

                rd.add(ssr);

                JSONObject geojson = ssr.getGeoJSONFeature();
                geoArray.add(geojson);

                tail--;
            }
        } catch (SQLException se) {
            se.printStackTrace();
            throw new Exception(se);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new Exception(t);
        } finally {
            if (c != null) c.close();
            if (ps != null) ps.close();
            if (rs != null) rs.close();
        }

        if (!isGeoJson) return rd;
        else return geoArray;
    }

    public JSONArray findDylosReadingsByGroup(String deviceid, int tail) throws Exception {
        return findDylosReadingsByGroup(deviceid, tail, false);
    }

    public JSONArray findSensordroneReadingsByGroup(String deviceid, int tail) throws Exception {
        return findSensordroneReadingsByGroup(deviceid, tail, false);
    }

    public ServerPushEvent getLastServerPush() throws Exception {
        // TODO Auto-generated method stub
        return null;
    }

    public JSONArray findDylosReadingsForUserBetween(String userId, Date start,
            Date end) throws Exception {
        // TODO Auto-generated method stub
        return null;
    }
}
