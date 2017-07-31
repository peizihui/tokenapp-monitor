package modum.io.monitor;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class DatabaseWatcher {
  private Connection conn;
  private PostgresTriggerListener listener;

  DatabaseWatcher(String jdbcURL, TriggerAction newBitcoinAddress, TriggerAction newEtherAddress)
      throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", System.getenv("DATASOURCE_USERNAME"));
    props.setProperty("password", System.getenv("DATASOURCE_PASSWORD"));
    conn = DriverManager.getConnection(jdbcURL, props);
    setUpTrigger();

    Map<String, TriggerAction> actionMap = new HashMap<>();
    actionMap.put("bitcoin", newBitcoinAddress);
    actionMap.put("ether", newEtherAddress);

    listener = new PostgresTriggerListener(conn, actionMap);
    listener.start();
  }

  void stop() {
    listener.gracefulStop();
  }

  private void setUpTrigger() throws SQLException {
    Statement statement = conn.createStatement();
    String createFunction = ""
        + "CREATE OR REPLACE FUNCTION notify_new_payin_address()\n"
        + "RETURNS TRIGGER AS $$\n"
        + "BEGIN\n"
        + "  PERFORM pg_notify(CAST('bitcoin' AS TEXT),NEW.pay_in_bitcoin_address);\n"
        + "  PERFORM pg_notify(CAST('ether' AS TEXT),NEW.pay_in_ether_address);\n"
        + "  RETURN NEW;\n"
        + "END;\n"
        + "$$ LANGUAGE 'plpgsql';";
    statement.execute("DROP TRIGGER IF EXISTS notify_new_payin_address ON investor;");
    String createTrigger = ""
        + "CREATE TRIGGER notify_new_payin_address\n"
        + "  AFTER UPDATE OF pay_in_ether_address, pay_in_bitcoin_address ON investor\n"
        + "  FOR EACH ROW\n"
        + "  EXECUTE PROCEDURE notify_new_payin_address()";
    statement.execute(createFunction);
    statement.execute(createTrigger);
    statement.close();
  }

}
