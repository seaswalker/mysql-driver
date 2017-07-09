以下列源码为例进行说明:

```java
@Test
public void query() throws SQLException {
    final String sql = "select * from student";
    PreparedStatement ps = connection.prepareStatement(sql);
    ResultSet rs = ps.executeQuery();
    while (rs.next()) {
        System.out.println("User: " + rs.getString("name") + ".");
    }
}
```

prepareStatement方法的实现位于ConnectionImpl:

```java
public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareStatement(sql, DEFAULT_RESULT_SET_TYPE, DEFAULT_RESULT_SET_CONCURRENCY);
}
```

DEFAULT_RESULT_SET_TYPE的定义如下:

```java
private static final int DEFAULT_RESULT_SET_TYPE = ResultSet.TYPE_FORWARD_ONLY;
```

含义为通过此种类型结果集得到的cursor只能单向向后进行遍历。

DEFAULT_RESULT_SET_CONCURRENCY定义:

```java
private static final int DEFAULT_RESULT_SET_CONCURRENCY = ResultSet.CONCUR_READ_ONLY;
```

prepareStatement方法的源码实现较长，这里分部分进行说明。

# 线程安全

prepareStatement方法所有的逻辑均在锁的保护下执行:

```java
synchronized (getConnectionMutex()) {
    //...
}
```

getConnectionMutex方法获得的其实就是连接对象本身，为什么要加锁呢，因为一个连接对象完全有可能在多个线程中被使用。