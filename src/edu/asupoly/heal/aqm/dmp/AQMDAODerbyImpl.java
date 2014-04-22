package edu.asupoly.heal.aqm.dmp;

import java.util.Enumeration;
import java.util.Properties;

public class AQMDAODerbyImpl extends AQMDAOJDBCImpl {

    public AQMDAODerbyImpl() {
        super();
    }

    public void init(Properties p) throws Exception {
        super.init(p);
        // In case we need to modify system properties for Derby
        Properties sysProps = System.getProperties();
        Enumeration<?> keys = p.keys();
        while (keys.hasMoreElements()) {
            String key = (String)keys.nextElement();
            if (key.startsWith("derby")) {
                sysProps.setProperty(key, p.getProperty(key));
            }
        }
    }
}
