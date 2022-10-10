package com.yahoo.ycsb.db;

/**
 * Constants used by the JDBC client.
 *
 * @author sudipto
 *
 */
public interface JdbcDBClientConstants {

    /** The class to use as the jdbc driver. */
    public static final String DRIVER_CLASS = "db.driver";

    /** The URL to connect to the database. */
    public static final String CONNECTION_URL = "db.url";

    /** The user name to use to connect to the database. */
    public static final String CONNECTION_USER = "db.user";

    /** The password to use for establishing the connection. */
    public static final String CONNECTION_PASSWD = "db.passwd";

    /** The JDBC fetch size hinted to the driver. */
    public static final String JDBC_FETCH_SIZE = "jdbc.fetchsize";

    /** The JDBC connection auto-commit property for the driver. */
    public static final String JDBC_AUTO_COMMIT = "jdbc.autocommit";

    /** The name of the property for the number of fields in a record. */
    public static final String FIELD_COUNT_PROPERTY = "fieldcount";

    /** Default number of fields in a record. */
    public static final String FIELD_COUNT_PROPERTY_DEFAULT = "10";

    /** Representing a NULL value. */
    public static final String NULL_VALUE = "NULL";

    /** The code to return when the call succeeds. */
    public static final int SUCCESS = 0;

    /** The Timestamp key in the user table.*/
    public static String TIMESTAMP_KEY = "YCSB_KEY";
}
