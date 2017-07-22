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

# PrepareStatement创建

我们以客户端编译同时开启PrepareStatement缓存为例，ConnectionImpl.clientPrepareStatement方法相关源码:

```java
if (getCachePreparedStatements()) {
    PreparedStatement.ParseInfo pStmtInfo = this.cachedPreparedStatementParams.get(nativeSql);
    if (pStmtInfo == null) {
        //反射创建PreparedStatement对象
        pStmt = com.mysql.jdbc.PreparedStatement.getInstance(getMultiHostSafeProxy(), nativeSql, this.database);
        this.cachedPreparedStatementParams.put(nativeSql, pStmt.getParseInfo());
    } else {
        pStmt = new com.mysql.jdbc.PreparedStatement(getMultiHostSafeProxy(), nativeSql, this.database, pStmtInfo);
    }
}
```

核心的缓存数据结构cachedPreparedStatementParams其实就是一个继承自LinkedHashMap实现的LRU缓存，可以看出，缓存的并不是PreparedStatement，而是ParseInfo对象，缓存的key就是我们的SQL语句。

ParseInfo代表着一条SQL语句在客户端"编译"的结果，对于SQL的编译的入口位于PreparedStatement的构造器：

```java
this.parseInfo = new ParseInfo(sql, this.connection, this.dbmd, this.charEncoding, this.charConverter);
```

ParseInfo类在其构造器中完成对SQL的编译，其本身就是PreparedStatement的嵌套类，那么这里的编译指的是什么呢?我们将测试用的SQL语句稍作改造:

```java
final String sql = "select * from student where name = ? and age = ?";
PreparedStatement ps = connection.prepareStatement(sql);
ps.setString(1, "skywalker");
ps.setInt(2, 22);
```

ParseInfo内部有一个关键的属性:

```java
byte[][] staticSql = null
```

通过调试可以发现，解析之后此属性的值变成了:

![staticSql](images/parseinfo_staticsql.PNG)

可以推测: 在对PreparedStatement进行参数设置时，必定是在数组的各个元素之间插入，至于为什么要使用byte数组而不是String数组，猜测是为了便于后续利用网络进行传输。

所以这里可以得出结论: 对PreparedStatement的编译其实就是**将SQL语句按照占位符进行分割**，对ParseInfo进行缓存而不是PreparedStatement的原因便是**PreparedStatement必定要保存具体的参数值**。

# 参数设置

我们以方法:

```java
ps.setString(1, "skywalker");
```

为例，具体实现位于com.mysql.jdbc.PreparedStatement中。

## 字符串包装

对于字符串类型，驱动会对其用单引号包装，相应源码:

```java
if (needsQuoted) {
    parameterAsBytes = StringUtils.getBytesWrapped(parameterAsString, '\'', '\'', this.charConverter, this.charEncoding,
            this.connection.getServerCharset(), this.connection.parserKnowsUnicode(), getExceptionInterceptor());
}
```

## 设置

PreparedStatement.setInternal方法:

```java
protected final void setInternal(int paramIndex, byte[] val) {
    synchronized (checkClosed().getConnectionMutex()) {
        int parameterIndexOffset = getParameterIndexOffset();
        checkBounds(paramIndex, parameterIndexOffset);
        this.isStream[paramIndex - 1 + parameterIndexOffset] = false;
        this.isNull[paramIndex - 1 + parameterIndexOffset] = false;
        this.parameterStreams[paramIndex - 1 + parameterIndexOffset] = null;
        this.parameterValues[paramIndex - 1 + parameterIndexOffset] = val;
    }
}
```

从中我们可以看出几点:

1. PreparedStatement的setXXX方法的序号是从1开始的。
2. PreparedStatement允许我们以输入流/Reader的形式作为值传入。
3. parameterValues是一个byte二维数组。

## 参数类型保存

PreparedStatement.setString相关源码:

```java
this.parameterTypes[parameterIndex - 1 + getParameterIndexOffset()] = Types.VARCHAR;
```

parameterTypes是一个int数组，依次保存了所有参数的类型。

# 查询

入口位于com.mysql.jdbc.PreparedStatement的executeQuery方法，依然是在加锁的情况下执行的。

## 流式查询

```java
boolean doStreaming = createStreamingResultSet();
```

驱动支持流式的从数据库服务获得查询结果而不是一次性全部将结果取回，但默认是没有开启的:

```java
protected boolean createStreamingResultSet() {
    synchronized (checkClosed().getConnectionMutex()) {
        return ((this.resultSetType == java.sql.ResultSet.TYPE_FORWARD_ONLY) && 
        (this.resultSetConcurrency == java.sql.ResultSet.CONCUR_READ_ONLY) && (this.fetchSize == Integer.MIN_VALUE));
    }
}
```

TYPE_FORWARD_ONLY指结果集只能单向向后移动，CONCUR_READ_ONLY指结果集只读，不满足的是最后一个条件，默认情况下fetchSize为0，我们可以通过将参数defaultFetchSize设为int最小值以支持这一特性。

## 查询Packet创建

fillSendPacket方法:

```java
protected Buffer fillSendPacket() throws SQLException {
    synchronized (checkClosed().getConnectionMutex()) {
        return fillSendPacket(this.parameterValues, this.parameterStreams, this.isStream, this.streamLengths);
    }
}
```

