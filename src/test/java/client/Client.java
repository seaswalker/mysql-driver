package client;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.*;

/**
 * Mysql客户端.
 *
 * @author skywalker
 */
public class Client {

    private Connection connection;

    @Before
    public void init() throws ClassNotFoundException, SQLException {
        Class.forName("com.mysql.jdbc.Driver");
        connection = DriverManager.getConnection("jdbc:mysql://localhost:3306/test?cachePrepStmts=true&useServerPrepStmts=false", "root", "1234");
    }

    @Test
    public void query() throws SQLException {
        final String sql = "select * from student where name = ? and age = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, "skywalker");
        ps.setInt(2, 22);
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
            System.out.println("User: " + rs.getString("name") + ".");
        }
    }

    @After
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignore) {
            }
        }
    }

}
